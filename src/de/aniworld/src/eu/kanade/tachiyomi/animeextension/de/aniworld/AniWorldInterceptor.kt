package eu.kanade.tachiyomi.animeextension.de.aniworld

import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import eu.kanade.tachiyomi.network.POST
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class AniWorldInterceptor(private val client: OkHttpClient, private val preferences: SharedPreferences) : Interceptor {

    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val cookies = cookieManager.getCookie(originalRequest.url.toString())
        val oldCookie = if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(originalRequest.url, it) }
        } else {
            emptyList()
        }
        val sessionCookie = oldCookie.firstOrNull { it.name == "rememberLogin" }
        if (!sessionCookie?.value.isNullOrEmpty()) {
            return chain.proceed(originalRequest)
        }

        val newCookie = getNewCookie(originalRequest.url)
            ?: throw Exception("Bitte im Browser oder in den Erweiterungs-Einstellungen einloggen.")
        val newCookieHeader = buildString {
            (oldCookie + newCookie).forEachIndexed { index, cookie ->
                if (index > 0) append("; ")
                append(cookie.name).append('=').append(cookie.value)
            }
        }

        return chain.proceed(
            originalRequest
                .newBuilder()
                .addHeader("cookie", newCookieHeader)
                .build()
        )
    }

    private fun getNewCookie(url: HttpUrl): Cookie? {
        val cookies = cookieManager.getCookie(url.toString())
        val oldCookie = if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
        val sessionCookie = oldCookie.firstOrNull { it.name == "rememberLogin" }
        if (!sessionCookie?.value.isNullOrEmpty()) {
            return sessionCookie
        }
        val email = AWConstants.getPrefBaseLogin(preferences)
        val password = AWConstants.getPrefBasePassword(preferences)
        if (email.isEmpty() || password.isEmpty()) return null
        val payload = FormBody.Builder()
            .add("email", email)
            .add("password", password)
            .add("autoLogin", "on")
            .build()
        return client.newCall(POST(AWConstants.LOGIN_URL, body = payload)).execute().header("set-cookie")?.let {
            Log.i("bruh", it)
            Cookie.parse(url, it)
        }
    }
}
