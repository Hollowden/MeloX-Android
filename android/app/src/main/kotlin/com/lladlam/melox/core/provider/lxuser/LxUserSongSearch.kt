package com.lladlam.melox.core.provider.lxuser

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

internal data class LxSongSearchHit(
    val key: String,
    val scriptName: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
)

internal data class LxSongSearchResult(
    val hits: List<LxSongSearchHit> = emptyList(),
    val failures: List<String> = emptyList(),
    val hasMore: Boolean = false,
)

internal suspend fun searchInstalledLxSources(
    context: Context,
    query: String,
    page: Int,
    pageSize: Int,
): LxSongSearchResult = coroutineScope {
    val results = LxUserSourceStore.list(context).map { record ->
        async {
            runInterruptible(Dispatchers.IO) {
                val name = record.metadata.name ?: record.id
                try {
                    val script = LxUserSourceStore.script(context, record.id)
                        ?: return@runInterruptible LxSongSearchResult()
                    searchScript(record.id, name, script, query, page, pageSize)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: InterruptedException) {
                    throw error
                } catch (_: Exception) {
                    LxSongSearchResult(failures = listOf(name))
                }
            }
        }
    }.awaitAll()
    LxSongSearchResult(
        hits = results.flatMap { it.hits },
        failures = results.flatMap { it.failures },
        hasMore = results.any { it.hasMore },
    )
}

internal fun lxSearchSessionId(id: String): String = "search:$id"

private fun searchScript(
    id: String,
    name: String,
    script: String,
    query: String,
    page: Int,
    pageSize: Int,
): LxSongSearchResult {
    val runtime = LxUserRuntime.session(lxSearchSessionId(id), script)
    // Some scripts declare the same search handler under all of their playback
    // platforms. Call it once per script to avoid sending identical requests.
    val source = runtime.declaredSearchSources().firstOrNull() ?: return LxSongSearchResult()
    val raw = runtime.callAction("search", mapOf(
        "source" to source,
        "text" to query,
        "page" to page,
        "limit" to pageSize,
    )) as? String ?: error("Empty LX search response")
    val result = JSONObject(raw)
    val songs = result.optJSONArray("list") ?: error("Invalid LX search response")
    val hits = buildList {
        for (index in 0 until minOf(songs.length(), pageSize)) {
            val song = songs.optJSONObject(index) ?: continue
            val title = song.optString("name").trim()
            val artist = song.optString("singer").ifBlank { song.optString("artist") }.trim()
            if (title.isBlank() || artist.isBlank()) continue
            add(LxSongSearchHit(
                key = "lx:$id:$index",
                scriptName = name,
                title = title,
                artist = artist,
                album = song.optString("albumName"),
                artworkUrl = song.optString("pic").takeIf { it.startsWith("https://") },
            ))
        }
    }
    return LxSongSearchResult(hits = hits, hasMore = !result.optBoolean("isEnd", songs.length() < pageSize))
}
