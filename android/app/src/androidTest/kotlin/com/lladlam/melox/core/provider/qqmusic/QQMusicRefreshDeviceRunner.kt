package com.lladlam.melox.core.provider.qqmusic

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class QQMusicRefreshDeviceRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val launch = targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName)
            checkNotNull(launch)
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            targetContext.startActivity(launch)
            Thread.sleep(1_500)
            checkRetry()
            result.putString("retry", "authenticated retry passed")
            val before = QQMusicSessionStore.read(targetContext)
            check(before.isLoggedIn) { "Sign in to the personal app first" }
            val next = refreshQQCredential(before, com.lladlam.melox.core.network.MeloXHttpClient.shared)
            check(QQMusicSessionStore.replaceIfCurrent(targetContext, before, next)) { "Session changed during validation" }
            check(QQMusicSessionStore.read(targetContext).cookie == next.cookie)
            result.putString("refresh", "live refresh and persistence passed")
            // Validate the saved session with the same profile endpoint used at login.
            kotlinx.coroutines.runBlocking {
                QQMusicApiClient({ QQMusicSessionStore.read(targetContext) }).accountProfile()
            }
            result.putString("profile", "refreshed account profile passed")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.javaClass.simpleName + ": " + error.message.orEmpty().take(160))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkRetry() {
        val old = QQMusicSessionStore.parse("uin=123; qm_keyst=old; psrf_qqrefresh_token=refresh")
        var current = old
        var refreshCalls = 0
        var requests = 0
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.host == "u.y.qq.com") {
                refreshCalls++
                """{"code":0,"request":{"code":0,"data":{"musickey":"new","musicid":123,"refresh_token":"next"}}}"""
            } else {
                requests++
                if (requests == 1) """{"code":1000}""" else {
                    check(request.header("Cookie").orEmpty().contains("qm_keyst=new"))
                    """{"code":0}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val client = OkHttpClient.Builder().addInterceptor(
            QQMusicCredentialInterceptor(transport, { current }, { expected, next ->
                check(expected == current)
                current = next
                true
            }),
        ).addInterceptor { chain -> transport.newCall(chain.request()).execute() }.build()
        val request = okhttp3.Request.Builder().url("https://c6.y.qq.com/profile")
            .header("Cookie", old.cookie).build()
        client.newCall(request).execute().use { check(it.body.string().contains("0")) }
        check(refreshCalls == 1 && requests == 2 && current.musicKey == "new")
        check(current.cookie.contains("psrf_qqrefresh_token=next"))
    }
}
