package com.pricefighter.data.ebay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches an HTML page over the network. Abstracts the HTTP engine so the on-device
 * path can use Cronet (Chrome's network stack, which clears eBay's anti-bot) while
 * JVM unit tests use a plain OkHttp client.
 */
interface PageFetcher {
    suspend fun get(url: String, referer: String?): String
}

/**
 * Plain OkHttp fetcher. Works from a JVM/host context. eBay returns HTTP 403 to a cold
 * request, so this warms a session (loads the homepage to pick up cookies) before the
 * first real fetch. On Android, OkHttp's TLS fingerprint is still flagged by eBay's
 * anti-bot — use [CronetPageFetcher] there.
 */
class OkHttpPageFetcher(
    private val http: OkHttpClient = defaultClient(),
) : PageFetcher {
    private val warmUpMutex = Mutex()
    @Volatile
    private var warmedUp = false

    override suspend fun get(url: String, referer: String?): String = withContext(Dispatchers.IO) {
        warmUpSession()
        rawGet(url, referer)
    }

    private suspend fun warmUpSession() {
        if (warmedUp) return
        warmUpMutex.withLock {
            if (warmedUp) return
            runCatching { rawGet(HOME_URL, referer = null) }
            warmedUp = true
        }
    }

    private fun rawGet(url: String, referer: String?): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Upgrade-Insecure-Requests", "1")
        if (referer != null) builder.header("Referer", referer)
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("eBay returned HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("Empty response from eBay")
        }
    }

    companion object {
        private const val HOME_URL = "https://www.ebay.com/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/** Minimal per-host, in-memory cookie store — enough to carry eBay's session forward. */
private class InMemoryCookieJar : CookieJar {
    private val store = HashMap<String, MutableMap<String, Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val byName = store.getOrPut(url.host) { LinkedHashMap() }
        for (cookie in cookies) byName[cookie.name] = cookie
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host]?.values?.filter { it.expiresAt > now } ?: emptyList()
    }
}
