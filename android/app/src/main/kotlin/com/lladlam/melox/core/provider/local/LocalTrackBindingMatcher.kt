package com.lladlam.melox.core.provider.local

import com.lladlam.melox.core.model.SearchSong
import com.lladlam.melox.core.music.provider.TrackAggregation
import java.util.Locale
import kotlin.math.abs

enum class LocalMatchConfidence {
    /** 歌名、歌手、版本标记与时长全部吻合，可自动绑定生效。 */
    HIGH,
    /** 存在版本/时长/歌手差异，仅作为待确认候选，不自动绑定。 */
    REVIEW,
    /** 未找到可靠候选。 */
    NONE,
}

data class LocalMatchCandidate(
    val track: LocalTrackRecord,
    val score: Int,
    val confidence: LocalMatchConfidence,
    val reason: String,
    val versionConflict: Boolean,
    val durationDiffMs: Long?,
)

data class LocalBatchMatchSummary(
    val totalSongs: Int,
    val autoBound: Int,
    val preservedManual: Int,
    val preservedExcluded: Int,
    val needsReview: Int,
    val unmatched: Int,
)

/**
 * 版本感知的本地音频匹配引擎。
 *
 * 设计原则：宁可漏配，绝不把原版误绑成 Live / Remix / DJ / 伴奏 / 翻唱 / 变速版本。
 */
object LocalTrackMatcher {
    private const val AUTO_BIND_MIN_SCORE = 85
    private const val REVIEW_MIN_SCORE = 52
    private const val STRICT_DURATION_TOLERANCE_MS = 4_500L
    private const val SOFT_DURATION_TOLERANCE_MS = 9_000L

    private data class ParsedLocalMeta(
        val track: LocalTrackRecord,
        val fullTitleNorm: String,
        val coreTitleNorm: String,
        val filenameTitleNorm: String,
        val versionTags: Set<String>,
        val artists: Set<String>,
        val albumNorm: String,
        val qualityRank: Int,
    )

    private val versionRules: List<Pair<String, List<String>>> = listOf(
        "live" to listOf("live", "现场", "演唱会", "concert", "巡演", "tour"),
        "remix" to listOf("remix", "dj", "串烧", "club mix", "mashup", "bootleg"),
        "instrumental" to listOf("伴奏", "消音", "纯音乐", "instrumental", "karaoke", "ktv", "off vocal", "inst."),
        "cover" to listOf("翻唱", "cover"),
        "acoustic" to listOf("acoustic", "不插电", "unplugged", "钢琴版", "吉他版", "piano ver", "guitar ver"),
        "speed" to listOf("变速", "加速", "减速", "慢放", "快放", "speed up", "speedup", "sped up", "slowed", "nightcore", "reverb", "空灵"),
        "demo" to listOf("demo", "小样"),
        "remaster" to listOf("remaster", "重制"),
    )

    fun formatTag(track: LocalTrackRecord): String {
        val ext = track.displayName.substringAfterLast('.', "").uppercase(Locale.ROOT)
        if (ext in setOf("FLAC", "ALAC", "WAV", "APE", "DSF", "DFF", "MP3", "M4A", "AAC", "OGG", "OPUS", "WMA")) {
            return ext
        }
        val mime = track.mimeType.orEmpty().lowercase(Locale.ROOT)
        return when {
            "flac" in mime -> "FLAC"
            "wav" in mime -> "WAV"
            "mpeg" in mime || "mp3" in mime -> "MP3"
            "mp4" in mime || "aac" in mime || "m4a" in mime -> "M4A"
            "ogg" in mime -> "OGG"
            "opus" in mime -> "OPUS"
            else -> "AUDIO"
        }
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "--:--"
        val totalSec = ms / 1_000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.ROOT, "%02d:%02d", min, sec)
    }

    fun rankCandidates(
        song: SearchSong,
        localTracks: List<LocalTrackRecord>,
        limit: Int = 8,
    ): List<LocalMatchCandidate> {
        if (localTracks.isEmpty()) return emptyList()
        val parsedLocals = localTracks.map(::parseLocal)
        return rankAgainstParsed(song, parsedLocals, limit)
    }

    fun bestMatch(
        song: SearchSong,
        localTracks: List<LocalTrackRecord>,
    ): LocalMatchCandidate? = rankCandidates(song, localTracks, limit = 1).firstOrNull()

    /** 仅解析一次本地曲库，批量为未绑定曲目生成最佳候选（避免 O(N*M) 重复解析导致界面卡顿）。 */
    fun suggestBestMatches(
        songs: List<SearchSong>,
        localTracks: List<LocalTrackRecord>,
    ): Map<String, LocalMatchCandidate> {
        if (songs.isEmpty() || localTracks.isEmpty()) return emptyMap()
        val parsedLocals = localTracks.map(::parseLocal)
        val result = HashMap<String, LocalMatchCandidate>(songs.size)
        songs.forEach { song ->
            val key = LocalTrackBindingStore.resourceKey(song)
            if (key !in result) {
                rankAgainstParsed(song, parsedLocals, limit = 1).firstOrNull()?.let {
                    result[key] = it
                }
            }
        }
        return result
    }

    /**
     * 对歌单批量执行本地匹配：
     * - 若 [overwriteAuto] 为 false，保留既有 AUTO/MANUAL/EXCLUDED 记录；
     * - 无论何时，[LocalBindingMode.MANUAL] 与 [LocalBindingMode.EXCLUDED] 都受人工保护，不会被批量自动匹配覆盖。
     */
    fun matchPlaylist(
        songs: List<SearchSong>,
        localTracks: List<LocalTrackRecord>,
        existingBindings: Map<String, LocalTrackBinding>,
        overwriteAuto: Boolean = true,
    ): Pair<List<LocalTrackBinding>, LocalBatchMatchSummary> {
        val parsedLocals = localTracks.map(::parseLocal)
        val newAutoBindings = mutableListOf<LocalTrackBinding>()
        var autoBound = 0
        var preservedManual = 0
        var preservedExcluded = 0
        var needsReview = 0
        var unmatched = 0

        songs.distinctBy { LocalTrackBindingStore.resourceKey(it) }.forEach { song ->
            val key = LocalTrackBindingStore.resourceKey(song)
            val existing = existingBindings[key]
            when (existing?.mode) {
                LocalBindingMode.MANUAL -> {
                    preservedManual++
                    return@forEach
                }
                LocalBindingMode.EXCLUDED -> {
                    preservedExcluded++
                    return@forEach
                }
                LocalBindingMode.AUTO -> {
                    if (!overwriteAuto && existing.isActiveLocal) {
                        autoBound++
                        return@forEach
                    }
                }
                null -> Unit
            }

            val top = rankAgainstParsed(song, parsedLocals, limit = 1).firstOrNull()
            when (top?.confidence) {
                LocalMatchConfidence.HIGH -> {
                    newAutoBindings += LocalTrackBinding(
                        resourceKey = key,
                        trackTitle = song.name,
                        trackArtist = song.artists,
                        trackAlbum = song.album,
                        trackDurationMs = song.durationMs,
                        mode = LocalBindingMode.AUTO,
                        localFileKey = top.track.fileKey,
                        localContentUri = top.track.contentUri,
                        localDisplayName = top.track.displayName,
                        localTitle = top.track.title,
                        localArtist = top.track.artist,
                        localAlbum = top.track.album,
                        localDurationMs = top.track.durationMs,
                        localMimeType = top.track.mimeType,
                        localSizeBytes = top.track.sizeBytes,
                        confidence = top.score,
                        matchReason = top.reason,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                    autoBound++
                }
                LocalMatchConfidence.REVIEW -> needsReview++
                else -> unmatched++
            }
        }

        val summary = LocalBatchMatchSummary(
            totalSongs = songs.distinctBy { LocalTrackBindingStore.resourceKey(it) }.size,
            autoBound = autoBound,
            preservedManual = preservedManual,
            preservedExcluded = preservedExcluded,
            needsReview = needsReview,
            unmatched = unmatched,
        )
        return newAutoBindings to summary
    }

    private fun rankAgainstParsed(
        song: SearchSong,
        parsedLocals: List<ParsedLocalMeta>,
        limit: Int,
    ): List<LocalMatchCandidate> {
        val targetFullNorm = normalizeTitle(song.name)
        val targetCoreNorm = normalizeTitle(stripBracketSuffix(song.name))
        if (targetFullNorm.isBlank() && targetCoreNorm.isBlank()) return emptyList()

        val targetTags = extractVersionTags("${song.name} ${song.album}")
        val targetArtists = splitArtists(song.artists)
        val targetAlbumNorm = normalizeTitle(song.album)
        val targetDuration = song.durationMs.takeIf { it > 0L }

        return parsedLocals.mapNotNull { local ->
            // 1. 网易云已识别 ID 直接命中（本地音频指纹识别结果）
            val recognizedIdHit = song.providerTrack == null &&
                song.id > 0L &&
                local.track.recognizedNeteaseId == song.id

            val titleScore: Int
            val exactCoreTitle: Boolean
            when {
                recognizedIdHit -> {
                    titleScore = 45
                    exactCoreTitle = true
                }
                targetFullNorm.isNotBlank() && local.fullTitleNorm == targetFullNorm -> {
                    titleScore = 45
                    exactCoreTitle = true
                }
                targetCoreNorm.isNotBlank() && (
                    local.coreTitleNorm == targetCoreNorm ||
                        local.filenameTitleNorm == targetCoreNorm
                    ) -> {
                    titleScore = 42
                    exactCoreTitle = true
                }
                targetCoreNorm.length >= 2 && (
                    local.fullTitleNorm.contains(targetCoreNorm) ||
                        local.coreTitleNorm.contains(targetCoreNorm) ||
                        local.filenameTitleNorm.contains(targetCoreNorm)
                    ) -> {
                    titleScore = 24
                    exactCoreTitle = false
                }
                local.coreTitleNorm.length >= 2 && targetCoreNorm.contains(local.coreTitleNorm) -> {
                    titleScore = 20
                    exactCoreTitle = false
                }
                else -> return@mapNotNull null
            }

            // 2. 版本标记冲突校验（严防原版 ↔ Live/翻唱/伴奏/DJ/变速错配）
            val versionConflict = targetTags != local.versionTags &&
                (targetTags.isNotEmpty() || local.versionTags.isNotEmpty())

            // 3. 歌手匹配
            val artistExact = targetArtists.isNotEmpty() && local.artists.any { la ->
                targetArtists.any { ta -> ta == la }
            }
            val artistPartial = !artistExact && targetArtists.isNotEmpty() && local.artists.any { la ->
                targetArtists.any { ta ->
                    (ta.length >= 2 && la.contains(ta)) || (la.length >= 2 && ta.contains(la))
                }
            }
            val artistScore = when {
                recognizedIdHit -> 30
                artistExact -> 30
                artistPartial -> 18
                targetArtists.isEmpty() -> 12
                else -> 0
            }

            // 4. 时长校验
            val durationDiff = if (targetDuration != null && local.track.durationMs > 0L) {
                abs(targetDuration - local.track.durationMs)
            } else {
                null
            }
            val durationScore = when {
                durationDiff == null -> 8
                durationDiff <= 2_500L -> 20
                durationDiff <= STRICT_DURATION_TOLERANCE_MS -> 14
                durationDiff <= SOFT_DURATION_TOLERANCE_MS -> 5
                else -> -20
            }

            // 5. 专辑加分
            val albumMatched = targetAlbumNorm.length >= 2 &&
                local.albumNorm.isNotBlank() &&
                (local.albumNorm == targetAlbumNorm ||
                    local.albumNorm.contains(targetAlbumNorm) ||
                    targetAlbumNorm.contains(local.albumNorm))
            val albumScore = if (albumMatched) 5 else 0

            val versionPenalty = if (versionConflict) -45 else 0
            val rawScore = (titleScore + artistScore + durationScore + albumScore + versionPenalty)
                .coerceIn(0, 100)

            val highAllowed = !versionConflict &&
                exactCoreTitle &&
                (artistExact || artistPartial || recognizedIdHit) &&
                (durationDiff == null || durationDiff <= STRICT_DURATION_TOLERANCE_MS) &&
                rawScore >= AUTO_BIND_MIN_SCORE

            val confidence = when {
                highAllowed -> LocalMatchConfidence.HIGH
                rawScore >= REVIEW_MIN_SCORE || (exactCoreTitle && (artistExact || artistPartial)) -> LocalMatchConfidence.REVIEW
                else -> LocalMatchConfidence.NONE
            }
            if (confidence == LocalMatchConfidence.NONE) return@mapNotNull null

            val reasons = buildList {
                if (recognizedIdHit) add("识别ID命中")
                else if (exactCoreTitle) add("歌名一致")
                else add("歌名相近")

                if (artistExact) add("歌手一致")
                else if (artistPartial) add("歌手相近")
                else add("歌手待核对")

                if (versionConflict) {
                    val diffTags = (local.versionTags - targetTags) + (targetTags - local.versionTags)
                    add("版本差异(${diffTags.joinToString("/")})")
                }
                if (durationDiff != null) {
                    val sec = durationDiff / 1_000L
                    if (durationDiff <= STRICT_DURATION_TOLERANCE_MS) add("时长吻合")
                    else add("时长差 ${sec}s")
                }
                if (albumMatched) add("专辑一致")
            }

            LocalMatchCandidate(
                track = local.track,
                score = rawScore,
                confidence = confidence,
                reason = reasons.joinToString(" · "),
                versionConflict = versionConflict,
                durationDiffMs = durationDiff,
            ) to local.qualityRank
        }.sortedWith(
            compareByDescending<Pair<LocalMatchCandidate, Int>> { it.first.confidence == LocalMatchConfidence.HIGH }
                .thenByDescending { it.first.score }
                .thenByDescending { it.second }
                .thenByDescending { it.first.track.sizeBytes },
        ).take(limit).map { it.first }
    }

    private fun parseLocal(track: LocalTrackRecord): ParsedLocalMeta {
        val baseFileName = track.displayName.substringBeforeLast('.')
        val rawTitle = track.title.ifBlank { baseFileName }
        val rawArtist = track.artist
        // 兼容未写 ID3 标签的「歌手 - 歌名.flac」文件命名
        val splitPair = if (baseFileName.contains(" - ")) {
            baseFileName.substringBefore(" - ").trim() to baseFileName.substringAfter(" - ").trim()
        } else if (baseFileName.contains("-")) {
            baseFileName.substringBefore("-").trim() to baseFileName.substringAfter("-").trim()
        } else {
            null
        }

        val effectiveArtists = buildSet {
            addAll(splitArtists(rawArtist))
            track.recognizedArtist?.let { addAll(splitArtists(it)) }
            if (isEmpty() && splitPair != null) {
                addAll(splitArtists(splitPair.first))
                addAll(splitArtists(splitPair.second))
            }
        }
        val filenameTitleCandidate = splitPair?.second ?: baseFileName
        val effectiveTitle = track.recognizedTitle?.takeIf(String::isNotBlank) ?: rawTitle
        val versionTags = extractVersionTags("$effectiveTitle ${track.displayName} ${track.album}")
        val fmt = formatTag(track)
        val qualityRank = when (fmt) {
            "DSF", "DFF" -> 5
            "FLAC", "ALAC", "WAV", "APE" -> 4
            "OPUS", "M4A", "AAC", "OGG" -> 2
            else -> 1
        }
        return ParsedLocalMeta(
            track = track,
            fullTitleNorm = normalizeTitle(effectiveTitle),
            coreTitleNorm = normalizeTitle(stripBracketSuffix(effectiveTitle)),
            filenameTitleNorm = normalizeTitle(stripBracketSuffix(filenameTitleCandidate)),
            versionTags = versionTags,
            artists = effectiveArtists,
            albumNorm = normalizeTitle(track.recognizedAlbum ?: track.album),
            qualityRank = qualityRank,
        )
    }

    private fun stripBracketSuffix(value: String): String {
        var result = value.trim()
        for (delimiter in listOf('(', '（', '[', '【', '『')) {
            val before = result.substringBefore(delimiter).trim()
            if (before.isNotBlank()) result = before
        }
        return result
    }

    private fun normalizeTitle(value: String): String =
        TrackAggregation.normalize(value)

    private fun splitArtists(raw: String): Set<String> = raw
        .split(Regex("\\s*(?:/|、|,|，|&|;|；|\\bfeat\\.?\\b|\\bft\\.?\\b)\\s*", RegexOption.IGNORE_CASE))
        .map(TrackAggregation::normalizeArtist)
        .filter { it.isNotBlank() && it != "unknown" && it != "未知歌手" }
        .toSet()

    private fun extractVersionTags(text: String): Set<String> {
        val lower = text.lowercase(Locale.ROOT)
        return versionRules.mapNotNull { (tag, keywords) ->
            if (keywords.any { lower.contains(it) }) tag else null
        }.toSet()
    }
}
