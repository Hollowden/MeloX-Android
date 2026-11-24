package com.lladlam.melox.core.music

import com.lladlam.melox.core.music.model.MusicPage
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.SourceId
import com.lladlam.melox.core.music.provider.MusicCapability
import com.lladlam.melox.core.music.provider.MusicProvider
import com.lladlam.melox.core.music.provider.MusicProviderRegistry
import com.lladlam.melox.core.music.provider.SearchCapability
import com.lladlam.melox.core.music.provider.UnifiedMusicService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedMusicServiceTest {
    @Test fun dynamicWhitelistPreservesPaginationAndUnavailableIdentity() = runBlocking {
        val dynamic = SourceId.parse("plugin:org.example.search")!!
        val missing = SourceId.parse("plugin:org.example.missing")!!
        val registry = MusicProviderRegistry(listOf(FakeSearchProvider(MusicSource.Netease, mutableListOf())))
        val plugin = object : MusicProvider, SearchCapability {
            override val sourceId = dynamic
            override val displayName = "Dynamic"
            override val capabilities = setOf(MusicCapability.Search)
            override suspend fun searchSongs(query: String, page: Int, pageSize: Int) = MusicPage(
                listOf(MusicTrack(MusicResourceId(dynamic, "opaque"), query, emptyList())), page, pageSize, 101L, true)
        }
        registry.registerDynamic(plugin)
        val service = UnifiedMusicService(registry)
        val result = service.searchSourceIds("test", setOf(dynamic, missing), 2, 25)
        assertEquals(dynamic, result.tracks.single().id.sourceId)
        assertEquals(2, result.pages[dynamic]?.page)
        assertEquals(101L, result.pages[dynamic]?.total)
        assertEquals(missing, result.failures.single().sourceId)
        registry.unregisterDynamic(dynamic)
        val unavailable = service.searchSourceIds("test", setOf(dynamic))
        assertTrue(unavailable.tracks.isEmpty())
        assertEquals(dynamic, unavailable.failures.single().sourceId)
    }

    @Test fun cancelledProviderCallIsNotConvertedToPartialSuccess() {
        val provider = object : MusicProvider, SearchCapability {
            override val source = MusicSource.QQMusic
            override val displayName = "Cancelled"
            override val capabilities = setOf(MusicCapability.Search)
            override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }
        org.junit.Assert.assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { UnifiedMusicService(MusicProviderRegistry(listOf(provider))).searchSongs("test", setOf(MusicSource.QQMusic)) }
        }
    }
    @Test
    fun onlyExplicitlyWhitelistedProvidersAreQueried() = runBlocking {
        val calls = mutableListOf<MusicSource>()
        val netease = FakeSearchProvider(MusicSource.Netease, calls)
        val qq = FakeSearchProvider(MusicSource.QQMusic, calls)
        val kugou = FakeSearchProvider(MusicSource.Kugou, calls)
        val service = UnifiedMusicService(MusicProviderRegistry(listOf(netease, qq, kugou)))

        val result = service.searchSongs(
            query = "test",
            sources = setOf(MusicSource.QQMusic),
        )

        assertEquals(listOf(MusicSource.QQMusic), calls)
        assertEquals(1, result.tracks.size)
        assertEquals(MusicSource.QQMusic, result.tracks.single().id.source)
    }

    @Test
    fun providerFailureDoesNotEraseSuccessfulProviderResults() = runBlocking {
        val good = FakeSearchProvider(MusicSource.QQMusic, mutableListOf())
        val broken = object : MusicProvider, SearchCapability {
            override val source = MusicSource.Kugou
            override val displayName = source.displayName
            override val capabilities = setOf(MusicCapability.Search)
            override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
                error("simulated failure")
            }
        }
        val service = UnifiedMusicService(MusicProviderRegistry(listOf(good, broken)))

        val result = service.searchSongs(
            query = "test",
            sources = setOf(MusicSource.QQMusic, MusicSource.Kugou),
        )

        assertEquals(1, result.tracks.size)
        assertEquals(MusicSource.QQMusic, result.tracks.single().id.source)
        assertEquals(1, result.failures.size)
        assertEquals(MusicSource.Kugou, result.failures.single().source)
        assertTrue(result.failures.single().message.contains("simulated failure"))
    }

    @Test
    fun interleavedResultsKeepSameSongOnDifferentPlatforms() = runBlocking {
        val calls = mutableListOf<MusicSource>()
        val service = UnifiedMusicService(MusicProviderRegistry(listOf(
            FakeSearchProvider(MusicSource.Netease, calls),
            FakeSearchProvider(MusicSource.Kuwo, calls),
        )))
        val result = service.searchSongs("爱错", setOf(MusicSource.Netease, MusicSource.Kuwo))

        assertEquals(
            listOf(MusicSource.Kuwo, MusicSource.Netease),
            result.interleavedTracks(SourceId.fromBuiltIn(MusicSource.Kuwo)).map { it.id.source },
        )
    }

    @Test
    fun aggregateRankingPrioritizesExactSongAndArtistWithoutLosingSource() {
        val netease = SourceId.fromBuiltIn(MusicSource.Netease)
        val qq = SourceId.fromBuiltIn(MusicSource.QQMusic)
        val cover = MusicTrack(
            MusicResourceId(netease, "cover"), "人间共鸣（翻唱）", listOf(MusicArtistRef(name = "其他歌手")),
        )
        val sameTitle = MusicTrack(
            MusicResourceId(netease, "same-title"), "人间共鸣", listOf(MusicArtistRef(name = "其他歌手")),
        )
        val original = MusicTrack(
            MusicResourceId(qq, "original"), "人间共鸣", listOf(MusicArtistRef(name = "李健")),
        )
        val result = UnifiedMusicService.SearchResult(
            tracks = listOf(cover, sameTitle, original),
            pages = mapOf(netease to MusicPage(listOf(cover, sameTitle), 1, 25), qq to MusicPage(listOf(original), 1, 25)),
        )

        assertEquals(listOf(original, sameTitle, cover), result.interleavedTracks(netease, "人间共鸣"))
        assertEquals(listOf(original, cover, sameTitle), result.interleavedTracks(netease, "李健　人间共鸣"))
    }

    @Test
    fun limitedProviderCoolsDownWhileOtherProvidersStillSearch() = runBlocking {
        val goodCalls = mutableListOf<MusicSource>()
        var limitedCalls = 0
        val limited = object : MusicProvider, SearchCapability {
            override val source = MusicSource.Netease
            override val displayName = source.displayName
            override val capabilities = setOf(MusicCapability.Search)
            override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
                limitedCalls++
                error("网易云请求失败（405）：操作频繁，请稍候再试")
            }
        }
        val service = UnifiedMusicService(MusicProviderRegistry(listOf(
            limited,
            FakeSearchProvider(MusicSource.Kuwo, goodCalls),
        )))
        val sources = setOf(MusicSource.Netease, MusicSource.Kuwo)
        service.searchSongs("红尘客栈", sources)
        val result = service.searchSongs("梦想启动", sources)

        assertEquals(1, limitedCalls)
        assertEquals(2, goodCalls.size)
        assertEquals(MusicSource.Kuwo, result.tracks.single().id.source)
        assertEquals(MusicSource.Netease, result.failures.single().source)
    }

    private class FakeSearchProvider(
        override val source: MusicSource,
        private val calls: MutableList<MusicSource>,
    ) : MusicProvider, SearchCapability {
        override val displayName = source.displayName
        override val capabilities = setOf(MusicCapability.Search)

        override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
            calls += source
            return MusicPage(
                items = listOf(
                    MusicTrack(
                        id = MusicResourceId(source, "${source.storageValue}-track"),
                        title = query,
                        artists = emptyList(),
                    ),
                ),
                page = page,
                pageSize = pageSize,
                total = 1,
            )
        }
    }
}
