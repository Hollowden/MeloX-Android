package com.lladlam.melox.core.provider.lxuser

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.provider.MeloXMusicProviders
import com.lladlam.melox.core.music.provider.MusicProviderSelectionStore
import com.lladlam.melox.core.music.provider.SearchCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Smoke checks for the personal build using only public song queries. */
class PersonalSearchDeviceRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val launch = checkNotNull(targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName))
            targetContext.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(1_500)
            val store = MusicProviderSelectionStore
            val enabled = store.songSearchEnabled(targetContext)
            val sources = store.songSearchSourceIds(targetContext)
            val preferences = targetContext.getSharedPreferences("melox_music_providers", 0)
            val aggregation = preferences.getBoolean("unified_enabled", false)
            preferences.edit().putBoolean("unified_enabled", !aggregation).commit()
            try {
                check(enabled == store.songSearchEnabled(targetContext))
                check(sources == store.songSearchSourceIds(targetContext))
            } finally {
                preferences.edit().putBoolean("unified_enabled", aggregation).commit()
            }
            result.putString("independent_search", "passed")
            val registry = MeloXMusicProviders.create(targetContext)
            runBlocking {
                for (source in listOf(MusicSource.QQMusic, MusicSource.Kuwo, MusicSource.Kugou, MusicSource.KugouLite)) {
                    val status = try {
                        val page = withTimeout(15_000) {
                            withContext(Dispatchers.IO) {
                                (registry.require(source) as SearchCapability).searchSongs("红尘客栈", 1, 10)
                            }
                        }
                        "results=" + page.items.size
                    } catch (error: Exception) {
                        "failed=" + error.javaClass.simpleName
                    }
                    result.putString(source.storageValue, status)
                }
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.javaClass.simpleName + ": " + error.message.orEmpty().take(160))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
