package com.lladlam.melox.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lladlam.melox.core.model.SearchSong
import com.lladlam.melox.core.provider.local.LocalBindingMode
import com.lladlam.melox.core.provider.local.LocalMatchCandidate
import com.lladlam.melox.core.provider.local.LocalMatchConfidence
import com.lladlam.melox.core.provider.local.LocalMediaScanner
import com.lladlam.melox.core.provider.local.LocalMusicRepository
import com.lladlam.melox.core.provider.local.LocalTrackBinding
import com.lladlam.melox.core.provider.local.LocalTrackBindingStore
import com.lladlam.melox.core.provider.local.LocalTrackMatcher
import com.lladlam.melox.core.provider.local.LocalTrackRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LocalMatchFilter(val label: String) {
    ALL("全部"),
    REVIEW("待确认"),
    BOUND("已绑定"),
    UNMATCHED("未匹配"),
}

private data class SongMatchRowState(
    val song: SearchSong,
    val resourceKey: String,
    val binding: LocalTrackBinding?,
    val suggested: LocalMatchCandidate?,
) {
    val isBound: Boolean get() = binding?.isActiveLocal == true
    val isExcluded: Boolean get() = binding?.mode == LocalBindingMode.EXCLUDED
    val needsReview: Boolean get() = !isBound && !isExcluded && suggested != null
}

/**
 * 歌单维度的「本地歌曲匹配与对照」面板。
 * 支持扫描本地音频、防错版自动匹配、三态筛选核对、单曲手动改绑/重试/排除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeloXPlaylistLocalMatchSheet(
    playlistName: String,
    songs: List<SearchSong>,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val localRepository = remember(app) { LocalMusicRepository(app) }
    val localScanner = remember(app) { LocalMediaScanner(app, localRepository) }

    var localTracks by remember { mutableStateOf<List<LocalTrackRecord>>(emptyList()) }
    var rows by remember { mutableStateOf<List<SongMatchRowState>>(emptyList()) }
    var filter by remember { mutableStateOf(LocalMatchFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var pickerTarget by remember { mutableStateOf<SongMatchRowState?>(null) }

    val uniqueSongs = remember(songs) {
        songs.distinctBy { LocalTrackBindingStore.resourceKey(it) }
    }

    /** 内存级即时更新单行状态，1 ms 响应，避免点「确认是这一首」时重读整个本地曲库并重算整张歌单。 */
    fun updateSingleRow(resourceKey: String, newBinding: LocalTrackBinding?, newSuggested: LocalMatchCandidate?) {
        rows = rows.map { row ->
            if (row.resourceKey == resourceKey) {
                row.copy(binding = newBinding, suggested = newSuggested)
            } else {
                row
            }
        }
    }

    suspend fun recomputeRows(tracks: List<LocalTrackRecord>) {
        val computed = withContext(Dispatchers.Default) {
            val allBindings = LocalTrackBindingStore.allBindings(app).associateBy { it.resourceKey }
            val unresolved = uniqueSongs.filter { song ->
                val b = allBindings[LocalTrackBindingStore.resourceKey(song)]
                b?.isActiveLocal != true && b?.mode != LocalBindingMode.EXCLUDED
            }
            val suggestions = LocalTrackMatcher.suggestBestMatches(unresolved, tracks)
            uniqueSongs.map { song ->
                val key = LocalTrackBindingStore.resourceKey(song)
                val binding = allBindings[key]
                SongMatchRowState(song, key, binding, suggestions[key])
            }
        }
        rows = computed
    }

    fun runScanAndMatch(forceRescan: Boolean) {
        if (busy) return
        busy = true
        statusBanner = if (forceRescan) "正在扫描本地音频并匹配版本…" else "正在重新匹配当前歌单…"
        scope.launch {
            runCatching {
                val tracks = withContext(Dispatchers.IO) {
                    if (forceRescan || localRepository.tracks().isEmpty()) {
                        localScanner.scanAll()
                    } else {
                        localRepository.tracks()
                    }
                }
                localTracks = tracks
                if (tracks.isEmpty()) {
                    recomputeRows(tracks)
                    statusBanner = "未扫描到本地音频，请先授予定位/音频权限或在「音乐服务 → 本地音乐」添加目录"
                    return@runCatching
                }
                val existing = withContext(Dispatchers.IO) {
                    LocalTrackBindingStore.allBindings(app).associateBy { it.resourceKey }
                }
                val (newBindings, summary) = withContext(Dispatchers.Default) {
                    LocalTrackMatcher.matchPlaylist(
                        songs = uniqueSongs,
                        localTracks = tracks,
                        existingBindings = existing,
                        overwriteAuto = true,
                    )
                }
                withContext(Dispatchers.IO) {
                    LocalTrackBindingStore.saveAll(app, newBindings)
                }
                recomputeRows(tracks)
                statusBanner = buildString {
                    append("完成：自动绑定 ${summary.autoBound} 首")
                    if (summary.preservedManual > 0) append("，保留手动 ${summary.preservedManual} 首")
                    if (summary.needsReview > 0) append("，待确认 ${summary.needsReview} 首")
                    if (summary.preservedExcluded > 0) append("，已排除 ${summary.preservedExcluded} 首")
                }
                if (summary.needsReview > 0) {
                    filter = LocalMatchFilter.REVIEW
                }
            }.onFailure { error ->
                statusBanner = "匹配失败：${error.message ?: "未知错误"}"
            }
            busy = false
        }
    }

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runScanAndMatch(forceRescan = true)
        } else {
            statusBanner = "未授予本地音频读取权限，请在系统设置开启或在「本地音乐」手动添加文件夹"
        }
    }

    fun requestScanAndMatch() {
        val granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (granted || localRepository.scanRoots().isNotEmpty()) {
            runScanAndMatch(forceRescan = true)
        } else {
            permissionLauncher.launch(audioPermission)
        }
    }

    LaunchedEffect(visible, uniqueSongs) {
        if (!visible) return@LaunchedEffect
        val tracks = withContext(Dispatchers.IO) { localRepository.tracks() }
        localTracks = tracks
        recomputeRows(tracks)
    }

    BackHandler(enabled = visible, onBack = onDismiss)

    val boundCount = remember(rows) { rows.count { it.isBound } }
    val reviewCount = remember(rows) { rows.count { it.needsReview } }
    val unmatchedCount = remember(rows) { rows.count { !it.isBound && !it.needsReview } }
    val visibleRows = remember(rows, filter, searchQuery) {
        val q = searchQuery.trim().lowercase()
        rows.filter { row ->
            val matchFilter = when (filter) {
                LocalMatchFilter.ALL -> true
                LocalMatchFilter.REVIEW -> row.needsReview
                LocalMatchFilter.BOUND -> row.isBound
                LocalMatchFilter.UNMATCHED -> !row.isBound && !row.needsReview
            }
            val matchQuery = q.isBlank() ||
                row.song.name.lowercase().contains(q) ||
                row.song.artists.lowercase().contains(q) ||
                row.binding?.localDisplayName.orEmpty().lowercase().contains(q) ||
                row.suggested?.track?.displayName.orEmpty().lowercase().contains(q)
            matchFilter && matchQuery
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.90f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "本地歌曲匹配",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "$playlistName · 共 ${uniqueSongs.size} 首 · 本地曲库 ${localTracks.size} 首",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("完成")
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { requestScanAndMatch() },
                    enabled = !busy && uniqueSongs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("扫描并一键匹配", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { runScanAndMatch(forceRescan = false) },
                    enabled = !busy && localTracks.isNotEmpty(),
                ) {
                    Text("重新匹配", maxLines = 1)
                }
            }

            statusBanner?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索歌单曲目、歌手或本地文件名", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    LocalMatchFilter.ALL to rows.size,
                    LocalMatchFilter.REVIEW to reviewCount,
                    LocalMatchFilter.BOUND to boundCount,
                    LocalMatchFilter.UNMATCHED to unmatchedCount,
                ).forEach { (item, count) ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text("${item.label} ($count)", fontSize = 12.sp) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            if (visibleRows.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (localTracks.isEmpty()) {
                            "点击上方「扫描并一键匹配」识别手机中的本地歌曲"
                        } else {
                            "当前筛选条件下没有歌曲"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleRows, key = { it.resourceKey }) { row ->
                        LocalMatchCompareCard(
                            row = row,
                            onClick = { pickerTarget = row },
                            onConfirmSuggested = { candidate ->
                                val saved = LocalTrackBindingStore.bindLocalTrack(
                                    context = app,
                                    song = row.song,
                                    localTrack = candidate.track,
                                    mode = LocalBindingMode.MANUAL,
                                    confidence = candidate.score,
                                    reason = "人工确认（${candidate.reason}）",
                                )
                                updateSingleRow(row.resourceKey, saved, null)
                                statusBanner = "已确认绑定：《${row.song.name}》→ ${candidate.track.displayName}"
                            },
                        )
                    }
                }
            }
        }
    }

    pickerTarget?.let { target ->
        SingleSongLocalPickerDialog(
            song = target.song,
            currentBinding = target.binding,
            localTracks = localTracks,
            onDismiss = { pickerTarget = null },
            onSelectLocal = { track, reason, score ->
                val saved = LocalTrackBindingStore.bindLocalTrack(
                    context = app,
                    song = target.song,
                    localTrack = track,
                    mode = LocalBindingMode.MANUAL,
                    confidence = score,
                    reason = reason,
                )
                updateSingleRow(target.resourceKey, saved, null)
                statusBanner = "已手动绑定：《${target.song.name}》→ ${track.displayName}"
                pickerTarget = null
            },
            onRetryAuto = {
                val best = LocalTrackMatcher.bestMatch(target.song, localTracks)
                if (best != null && best.confidence == LocalMatchConfidence.HIGH) {
                    val saved = LocalTrackBindingStore.bindLocalTrack(
                        context = app,
                        song = target.song,
                        localTrack = best.track,
                        mode = LocalBindingMode.AUTO,
                        confidence = best.score,
                        reason = best.reason,
                    )
                    updateSingleRow(target.resourceKey, saved, null)
                    statusBanner = "已自动重配：《${target.song.name}》→ ${best.track.displayName}"
                } else {
                    LocalTrackBindingStore.removeByKey(app, target.resourceKey)
                    updateSingleRow(target.resourceKey, null, best)
                    statusBanner = if (best != null) {
                        "《${target.song.name}》仅找到疑似候选（${best.reason}），请手动确认"
                    } else {
                        "《${target.song.name}》未找到可自动匹配的本地版本"
                    }
                }
                pickerTarget = null
            },
            onExclude = {
                LocalTrackBindingStore.exclude(app, target.song)
                val excluded = LocalTrackBindingStore.readByKey(app, target.resourceKey)
                updateSingleRow(target.resourceKey, excluded, null)
                statusBanner = "已排除《${target.song.name}》，将仅走在线播放"
                pickerTarget = null
            },
            onClearExclusion = {
                LocalTrackBindingStore.removeByKey(app, target.resourceKey)
                val best = LocalTrackMatcher.bestMatch(target.song, localTracks)
                updateSingleRow(target.resourceKey, null, best)
                statusBanner = "已取消排除《${target.song.name}》"
                pickerTarget = null
            },
        )
    }
}

@Composable
private fun LocalMatchCompareCard(
    row: SongMatchRowState,
    onClick: () -> Unit,
    onConfirmSuggested: (LocalMatchCandidate) -> Unit,
) {
    val badgeColor: Color
    val badgeText: String
    when {
        row.binding?.mode == LocalBindingMode.MANUAL && row.binding.isActiveLocal -> {
            badgeColor = Color(0xFF2E7D32)
            badgeText = "手动绑定"
        }
        row.binding?.mode == LocalBindingMode.AUTO && row.binding.isActiveLocal -> {
            badgeColor = Color(0xFF1565C0)
            badgeText = "自动 ${row.binding.confidence}%"
        }
        row.isExcluded -> {
            badgeColor = Color(0xFF757575)
            badgeText = "已排除"
        }
        row.needsReview -> {
            badgeColor = Color(0xFFEF6C00)
            badgeText = "待确认 ${row.suggested?.score ?: 0}%"
        }
        else -> {
            badgeColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            badgeText = "未匹配"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = 1.dp,
                color = if (row.needsReview) Color(0xFFEF6C00).copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.song.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = badgeText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = badgeColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Text(
            text = buildString {
                append("云端：")
                append(row.song.artists.ifBlank { "未知歌手" })
                if (row.song.album.isNotBlank()) append(" · ${row.song.album}")
                append(" · ${LocalTrackMatcher.formatDuration(row.song.durationMs)}")
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(Modifier.height(6.dp))

        when {
            row.isBound && row.binding != null -> {
                val b = row.binding
                val fmt = b.localDisplayName.orEmpty().substringAfterLast('.', "").uppercase().ifBlank { "LOCAL" }
                Text(
                    text = "本地：${b.localDisplayName ?: b.localTitle.orEmpty()}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$fmt · ${b.localArtist.orEmpty().ifBlank { "无歌手标签" }} · ${LocalTrackMatcher.formatDuration(b.localDurationMs)} · ${b.matchReason}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "点击可修改绑定 / 重新匹配 / 解除绑定",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            row.needsReview && row.suggested != null -> {
                val cand = row.suggested
                Text(
                    text = "候选：${cand.track.displayName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${LocalTrackMatcher.formatTag(cand.track)} · ${cand.track.artist.ifBlank { "无歌手标签" }} · ${LocalTrackMatcher.formatDuration(cand.track.durationMs)} · ${cand.reason}",
                    fontSize = 11.sp,
                    color = Color(0xFFEF6C00),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onConfirmSuggested(cand) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("确认是这一首", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = onClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("换其他本地文件", fontSize = 12.sp)
                    }
                }
            }
            row.isExcluded -> {
                Text(
                    text = "已手动排除（仅走在线播放，点击可重新绑定本地文件）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            else -> {
                Text(
                    text = "未找到高置信本地文件，点击可手动搜索指定",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
    }
}

@Composable
private fun SingleSongLocalPickerDialog(
    song: SearchSong,
    currentBinding: LocalTrackBinding?,
    localTracks: List<LocalTrackRecord>,
    onDismiss: () -> Unit,
    onSelectLocal: (LocalTrackRecord, String, Int) -> Unit,
    onRetryAuto: () -> Unit,
    onExclude: () -> Unit,
    onClearExclusion: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val candidates = remember(song, localTracks) {
        LocalTrackMatcher.rankCandidates(song, localTracks, limit = 8)
    }
    val searchedLocals = remember(query, localTracks) {
        val q = query.trim().lowercase()
        if (q.isBlank()) {
            emptyList()
        } else {
            localTracks.filter { track ->
                track.title.lowercase().contains(q) ||
                    track.artist.lowercase().contains(q) ||
                    track.album.lowercase().contains(q) ||
                    track.displayName.lowercase().contains(q)
            }.take(40)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = song.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${song.artists} · ${song.album.ifBlank { "无专辑" }} · ${LocalTrackMatcher.formatDuration(song.durationMs)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onRetryAuto,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("自动重配", fontSize = 12.sp)
                    }
                    if (currentBinding?.mode == LocalBindingMode.EXCLUDED) {
                        OutlinedButton(
                            onClick = onClearExclusion,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("取消排除", fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onExclude,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("解绑并排除", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("在全部 ${localTracks.size} 首本地歌曲中搜索…", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (query.isBlank()) {
                        item("hdr_cand") {
                            Text(
                                text = if (candidates.isNotEmpty()) "推荐本地候选（点击即手动锁定）" else "暂无自动候选，请在上方输入关键词搜索",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        items(candidates, key = { "cand_${it.track.fileKey}" }) { cand ->
                            val isSelected = currentBinding?.localFileKey == cand.track.fileKey && currentBinding.isActiveLocal
                            LocalCandidateOptionRow(
                                track = cand.track,
                                subtitle = "${LocalTrackMatcher.formatTag(cand.track)} · ${cand.track.artist.ifBlank { "未知歌手" }} · ${LocalTrackMatcher.formatDuration(cand.track.durationMs)} · ${cand.score}% (${cand.reason})",
                                highlighted = isSelected,
                                warning = cand.versionConflict,
                                onClick = {
                                    onSelectLocal(cand.track, "手动选择（${cand.reason}）", cand.score)
                                },
                            )
                        }
                    } else {
                        item("hdr_search") {
                            Text(
                                text = "本地搜索结果（${searchedLocals.size} 首）",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        items(searchedLocals, key = { "search_${it.fileKey}" }) { track ->
                            val isSelected = currentBinding?.localFileKey == track.fileKey && currentBinding.isActiveLocal
                            LocalCandidateOptionRow(
                                track = track,
                                subtitle = "${LocalTrackMatcher.formatTag(track)} · ${track.artist.ifBlank { "未知歌手" }} · ${track.album.ifBlank { "无专辑" }} · ${LocalTrackMatcher.formatDuration(track.durationMs)}",
                                highlighted = isSelected,
                                warning = false,
                                onClick = {
                                    onSelectLocal(track, "手动搜索指定", 100)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun LocalCandidateOptionRow(
    track: LocalTrackRecord,
    subtitle: String,
    highlighted: Boolean,
    warning: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = (if (highlighted) "✓ " else "") + track.displayName,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = if (warning) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 全局「已绑定本地歌曲管理」面板，供用户在音乐服务设置页集中查看、改绑或清理所有历史映射。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeloXAllLocalBindingsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val app = context.applicationContext
    val localRepository = remember(app) { LocalMusicRepository(app) }
    val revision by LocalTrackBindingStore.revision.collectAsState()
    var query by remember { mutableStateOf("") }
    var localTracks by remember { mutableStateOf<List<LocalTrackRecord>>(emptyList()) }
    var editingBinding by remember { mutableStateOf<LocalTrackBinding?>(null) }

    LaunchedEffect(visible) {
        if (visible) {
            localTracks = withContext(Dispatchers.IO) { localRepository.tracks() }
        }
    }

    val allBindings = remember(revision, visible) {
        if (!visible) emptyList() else LocalTrackBindingStore.allBindings(app)
    }
    val filtered = remember(allBindings, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) allBindings else allBindings.filter { b ->
            b.trackTitle.lowercase().contains(q) ||
                b.trackArtist.lowercase().contains(q) ||
                b.localDisplayName.orEmpty().lowercase().contains(q)
        }
    }

    BackHandler(enabled = visible, onBack = onDismiss)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("本地歌曲绑定管理", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    val active = allBindings.count { it.isActiveLocal }
                    val excluded = allBindings.count { it.mode == LocalBindingMode.EXCLUDED }
                    Text(
                        text = "生效绑定 $active 首 · 已排除 $excluded 首",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                    )
                }
                if (allBindings.isNotEmpty()) {
                    TextButton(onClick = { LocalTrackBindingStore.clearAll(app) }) {
                        Text("全部清空", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索云端歌名、歌手或本地文件名", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无绑定记录。打开任意歌单右上角菜单点「匹配本地歌曲」即可开始匹配。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.resourceKey }) { binding ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { editingBinding = binding }
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${binding.trackTitle} · ${binding.trackArtist}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = when (binding.mode) {
                                        LocalBindingMode.MANUAL -> "手动"
                                        LocalBindingMode.AUTO -> "自动 ${binding.confidence}%"
                                        LocalBindingMode.EXCLUDED -> "已排除"
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = if (binding.isActiveLocal) {
                                    "→ 本地：${binding.localDisplayName ?: binding.localTitle.orEmpty()} (${LocalTrackMatcher.formatDuration(binding.localDurationMs)})"
                                } else {
                                    "→ 已排除，仅在线播放"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    editingBinding?.let { current ->
        val fakeSong = SearchSong(
            id = current.resourceKey.substringAfter(':').toLongOrNull() ?: 0L,
            name = current.trackTitle,
            artists = current.trackArtist,
            album = current.trackAlbum,
            artworkUrl = null,
            durationMs = current.trackDurationMs,
        )
        SingleSongLocalPickerDialog(
            song = fakeSong,
            currentBinding = current,
            localTracks = localTracks,
            onDismiss = { editingBinding = null },
            onSelectLocal = { track, reason, score ->
                LocalTrackBindingStore.bindLocalTrackByKey(
                    context = app,
                    existing = current,
                    localTrack = track,
                    mode = LocalBindingMode.MANUAL,
                    confidence = score,
                    reason = reason,
                )
                editingBinding = null
            },
            onRetryAuto = {
                val best = LocalTrackMatcher.bestMatch(fakeSong, localTracks)
                if (best != null && best.confidence == LocalMatchConfidence.HIGH) {
                    LocalTrackBindingStore.bindLocalTrackByKey(
                        context = app,
                        existing = current,
                        localTrack = best.track,
                        mode = LocalBindingMode.AUTO,
                        confidence = best.score,
                        reason = best.reason,
                    )
                } else {
                    LocalTrackBindingStore.removeByKey(app, current.resourceKey)
                }
                editingBinding = null
            },
            onExclude = {
                LocalTrackBindingStore.save(
                    app,
                    current.copy(
                        mode = LocalBindingMode.EXCLUDED,
                        localFileKey = null,
                        localContentUri = null,
                        localDisplayName = null,
                        matchReason = "已手动排除，仅在线播放",
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
                editingBinding = null
            },
            onClearExclusion = {
                LocalTrackBindingStore.removeByKey(app, current.resourceKey)
                editingBinding = null
            },
        )
    }
}
