package com.lladlam.melox.core.provider.local

import android.content.Context
import android.net.Uri
import android.util.Log
import com.lladlam.melox.core.model.SearchSong
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class LocalBindingMode {
    /** 高置信度自动匹配生效，后续一键匹配时可更新。 */
    AUTO,
    /** 用户手动确认或手动指定，人工锁定，后续自动匹配不会覆盖。 */
    MANUAL,
    /** 用户手动解除绑定并排除，仅在线播放，后续自动匹配不会重新绑定。 */
    EXCLUDED,
}

data class LocalTrackBinding(
    val resourceKey: String,
    val trackTitle: String,
    val trackArtist: String,
    val trackAlbum: String = "",
    val trackDurationMs: Long = 0L,
    val mode: LocalBindingMode,
    val localFileKey: String? = null,
    val localContentUri: String? = null,
    val localDisplayName: String? = null,
    val localTitle: String? = null,
    val localArtist: String? = null,
    val localAlbum: String? = null,
    val localDurationMs: Long = 0L,
    val localMimeType: String? = null,
    val localSizeBytes: Long = 0L,
    val confidence: Int = 0,
    val matchReason: String = "",
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val isActiveLocal: Boolean
        get() = (mode == LocalBindingMode.AUTO || mode == LocalBindingMode.MANUAL) &&
            !localContentUri.isNullOrBlank()

    fun formatBadge(): String {
        val ext = localDisplayName.orEmpty().substringAfterLast('.', "").uppercase()
        if (ext in setOf("FLAC", "ALAC", "WAV", "APE", "DSF", "DFF", "MP3", "M4A", "AAC", "OGG", "OPUS", "WMA")) {
            return ext
        }
        val mime = localMimeType.orEmpty().lowercase()
        return when {
            "flac" in mime -> "FLAC"
            "wav" in mime -> "WAV"
            "mpeg" in mime || "mp3" in mime -> "MP3"
            "mp4" in mime || "aac" in mime || "m4a" in mime -> "M4A"
            "ogg" in mime -> "OGG"
            "opus" in mime -> "OPUS"
            else -> "音频"
        }
    }
}

/**
 * 持久化管理「在线歌单曲目 ↔ 手机本地音频文件」的一一对应绑定关系。
 *
 * 播放解析层通过 [resolvePlaybackUri] 以最高优先级读取本地文件，
 * 实现免缓存、免流量的本地优先播放，并支持人工锁定与排除防误配。
 */
object LocalTrackBindingStore {
    private const val TAG = "LocalTrackBinding"
    private const val PREFS_NAME = "melox_local_track_bindings"

    private val lock = Any()
    @Volatile private var loaded = false
    private val cache = ConcurrentHashMap<String, LocalTrackBinding>()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun resourceIdFor(song: SearchSong): MusicResourceId =
        song.providerTrack?.id ?: MusicResourceId(MusicSource.Netease, song.id.toString())

    fun resourceKey(id: MusicResourceId): String = "${id.sourceId.value}:${id.value}"

    fun resourceKey(song: SearchSong): String = resourceKey(resourceIdFor(song))

    fun read(context: Context, id: MusicResourceId): LocalTrackBinding? {
        ensureLoaded(context)
        return cache[resourceKey(id)]
    }

    fun read(context: Context, song: SearchSong): LocalTrackBinding? =
        read(context, resourceIdFor(song))

    fun readByKey(context: Context, key: String): LocalTrackBinding? {
        ensureLoaded(context)
        return cache[key]
    }

    fun allBindings(context: Context): List<LocalTrackBinding> {
        ensureLoaded(context)
        return cache.values.sortedByDescending { it.updatedAtMs }
    }

    fun activeCount(context: Context): Int {
        ensureLoaded(context)
        return cache.values.count { it.isActiveLocal }
    }

    fun isBoundLocally(context: Context, id: MusicResourceId): Boolean =
        read(context, id)?.isActiveLocal == true

    fun isBoundLocally(context: Context, song: SearchSong): Boolean =
        read(context, song)?.isActiveLocal == true

    fun activeBinding(context: Context, id: MusicResourceId?): LocalTrackBinding? =
        id?.let { read(context, it)?.takeIf { b -> b.isActiveLocal } }

    fun activeBindingForMediaId(context: Context, mediaId: String?): LocalTrackBinding? {
        if (mediaId.isNullOrBlank()) return null
        val decoded = com.lladlam.melox.playback.PlaybackTrackIdentity.decode(mediaId)
            ?: mediaId.toLongOrNull()?.let { MusicResourceId(MusicSource.Netease, it.toString()) }
            ?: return null
        return activeBinding(context, decoded)
    }

    /**
     * 解析曲目对应的本地音频 URI；若未绑定、已排除或本地文件已失效，返回 null 回退在线播放。
     */
    fun resolvePlaybackUri(context: Context, id: MusicResourceId): Uri? {
        val binding = read(context, id) ?: return null
        if (!binding.isActiveLocal) return null
        val app = context.applicationContext
        // 优先尝试从本地库获取最新 contentUri（防止重新扫描后 URI 更新）。
        val freshUriString = binding.localFileKey
            ?.takeIf(String::isNotBlank)
            ?.let { key -> runCatching { LocalMusicRepository(app).track(key)?.contentUri }.getOrNull() }
            ?: binding.localContentUri
        val uriString = freshUriString?.takeIf(String::isNotBlank) ?: return null
        val uri = Uri.parse(uriString)
        val accessible = runCatching {
            app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!accessible) {
            Log.w(TAG, "Bound local file unreachable for ${binding.resourceKey}: $uriString")
            return null
        }
        return uri
    }

    fun bindLocalTrack(
        context: Context,
        song: SearchSong,
        localTrack: LocalTrackRecord,
        mode: LocalBindingMode,
        confidence: Int,
        reason: String,
    ): LocalTrackBinding {
        val binding = LocalTrackBinding(
            resourceKey = resourceKey(song),
            trackTitle = song.name,
            trackArtist = song.artists,
            trackAlbum = song.album,
            trackDurationMs = song.durationMs,
            mode = mode,
            localFileKey = localTrack.fileKey,
            localContentUri = localTrack.contentUri,
            localDisplayName = localTrack.displayName,
            localTitle = localTrack.title,
            localArtist = localTrack.artist,
            localAlbum = localTrack.album,
            localDurationMs = localTrack.durationMs,
            localMimeType = localTrack.mimeType,
            localSizeBytes = localTrack.sizeBytes,
            confidence = confidence.coerceIn(0, 100),
            matchReason = reason,
            updatedAtMs = System.currentTimeMillis(),
        )
        save(context, binding)
        return binding
    }

    fun bindLocalTrackByKey(
        context: Context,
        existing: LocalTrackBinding,
        localTrack: LocalTrackRecord,
        mode: LocalBindingMode,
        confidence: Int,
        reason: String,
    ): LocalTrackBinding {
        val updated = existing.copy(
            mode = mode,
            localFileKey = localTrack.fileKey,
            localContentUri = localTrack.contentUri,
            localDisplayName = localTrack.displayName,
            localTitle = localTrack.title,
            localArtist = localTrack.artist,
            localAlbum = localTrack.album,
            localDurationMs = localTrack.durationMs,
            localMimeType = localTrack.mimeType,
            localSizeBytes = localTrack.sizeBytes,
            confidence = confidence.coerceIn(0, 100),
            matchReason = reason,
            updatedAtMs = System.currentTimeMillis(),
        )
        save(context, updated)
        return updated
    }

    /** 解除绑定并标记排除，防止后续自动匹配重新绑回错版。 */
    fun exclude(context: Context, song: SearchSong) {
        val key = resourceKey(song)
        val excluded = LocalTrackBinding(
            resourceKey = key,
            trackTitle = song.name,
            trackArtist = song.artists,
            trackAlbum = song.album,
            trackDurationMs = song.durationMs,
            mode = LocalBindingMode.EXCLUDED,
            matchReason = "已手动排除，仅在线播放",
            updatedAtMs = System.currentTimeMillis(),
        )
        save(context, excluded)
    }

    fun save(context: Context, binding: LocalTrackBinding) {
        ensureLoaded(context)
        cache[binding.resourceKey] = binding
        preferences(context).edit()
            .putString(binding.resourceKey, encode(binding).toString())
            .apply()
        bumpRevision()
    }

    fun saveAll(context: Context, bindings: Collection<LocalTrackBinding>) {
        if (bindings.isEmpty()) return
        ensureLoaded(context)
        val editor = preferences(context).edit()
        bindings.forEach { binding ->
            cache[binding.resourceKey] = binding
            editor.putString(binding.resourceKey, encode(binding).toString())
        }
        editor.apply()
        bumpRevision()
    }

    fun removeByKey(context: Context, resourceKey: String) {
        ensureLoaded(context)
        cache.remove(resourceKey)
        preferences(context).edit().remove(resourceKey).apply()
        bumpRevision()
    }

    fun clearAll(context: Context) {
        ensureLoaded(context)
        cache.clear()
        preferences(context).edit().clear().apply()
        bumpRevision()
    }

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val all = preferences(context).all
            all.forEach { (key, value) ->
                val raw = value as? String ?: return@forEach
                decode(key, raw)?.let { cache[key] = it }
            }
            loaded = true
        }
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun encode(binding: LocalTrackBinding): JSONObject = JSONObject()
        .put("resourceKey", binding.resourceKey)
        .put("trackTitle", binding.trackTitle)
        .put("trackArtist", binding.trackArtist)
        .put("trackAlbum", binding.trackAlbum)
        .put("trackDurationMs", binding.trackDurationMs)
        .put("mode", binding.mode.name)
        .put("localFileKey", binding.localFileKey ?: "")
        .put("localContentUri", binding.localContentUri ?: "")
        .put("localDisplayName", binding.localDisplayName ?: "")
        .put("localTitle", binding.localTitle ?: "")
        .put("localArtist", binding.localArtist ?: "")
        .put("localAlbum", binding.localAlbum ?: "")
        .put("localDurationMs", binding.localDurationMs)
        .put("localMimeType", binding.localMimeType ?: "")
        .put("localSizeBytes", binding.localSizeBytes)
        .put("confidence", binding.confidence)
        .put("matchReason", binding.matchReason)
        .put("updatedAtMs", binding.updatedAtMs)

    private fun decode(fallbackKey: String, raw: String): LocalTrackBinding? = runCatching {
        val json = JSONObject(raw)
        val mode = runCatching { LocalBindingMode.valueOf(json.optString("mode")) }
            .getOrDefault(LocalBindingMode.AUTO)
        LocalTrackBinding(
            resourceKey = json.optString("resourceKey").ifBlank { fallbackKey },
            trackTitle = json.optString("trackTitle"),
            trackArtist = json.optString("trackArtist"),
            trackAlbum = json.optString("trackAlbum"),
            trackDurationMs = json.optLong("trackDurationMs", 0L),
            mode = mode,
            localFileKey = json.optString("localFileKey").takeIf(String::isNotBlank),
            localContentUri = json.optString("localContentUri").takeIf(String::isNotBlank),
            localDisplayName = json.optString("localDisplayName").takeIf(String::isNotBlank),
            localTitle = json.optString("localTitle").takeIf(String::isNotBlank),
            localArtist = json.optString("localArtist").takeIf(String::isNotBlank),
            localAlbum = json.optString("localAlbum").takeIf(String::isNotBlank),
            localDurationMs = json.optLong("localDurationMs", 0L),
            localMimeType = json.optString("localMimeType").takeIf(String::isNotBlank),
            localSizeBytes = json.optLong("localSizeBytes", 0L),
            confidence = json.optInt("confidence", 0),
            matchReason = json.optString("matchReason"),
            updatedAtMs = json.optLong("updatedAtMs", 0L),
        )
    }.getOrNull()
}
