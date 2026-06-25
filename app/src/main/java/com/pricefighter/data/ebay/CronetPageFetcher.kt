package com.pricefighter.data.ebay

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches pages through Cronet — Chromium's actual network stack — so requests carry
 * Chrome's TLS/HTTP-2 fingerprint.
 *
 * eBay's anti-bot still 403s a *cold* request (no session), so this warms a session by
 * loading the homepage first, captures the cookies eBay sets, and replays them (plus a
 * Referer) on the search request — the same browser-like sequence that gets a 200.
 */
class CronetPageFetcher(context: Context) : PageFetcher {

    private val appContext = context.applicationContext
    private val executor = Executors.newCachedThreadPool()
    private val cookies = ConcurrentHashMap<String, String>()
    private val warmUpMutex = Mutex()
    @Volatile
    private var warmedUp = false

    private val engine: CronetEngine by lazy {
        CronetEngine.Builder(appContext)
            .enableHttp2(true)
            .enableBrotli(true)
            .setUserAgent(USER_AGENT)
            .build()
    }

    override suspend fun get(url: String, referer: String?): String {
        warmUpSession()
        return fetch(url, referer)
    }

    /** Load the homepage once to collect eBay's session cookies. */
    private suspend fun warmUpSession() {
        if (warmedUp) return
        warmUpMutex.withLock {
            if (warmedUp) return
            runCatching { fetch(HOME_URL, referer = null) }
            warmedUp = true
        }
    }

    private suspend fun fetch(url: String, referer: String?): String =
        suspendCancellableCoroutine { cont ->
            val body = ByteArrayOutputStream()
            var status = 0

            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                    captureCookies(info)
                    request.followRedirect()
                }

                override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                    status = info.httpStatusCode
                    captureCookies(info)
                    request.read(ByteBuffer.allocateDirect(READ_BUFFER_BYTES))
                }

                override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                    byteBuffer.flip()
                    val chunk = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(chunk)
                    body.write(chunk)
                    byteBuffer.clear()
                    request.read(byteBuffer)
                }

                override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                    if (status in 200..299) {
                        cont.resume(body.toString(Charsets.UTF_8.name()))
                    } else {
                        cont.resumeWithException(IOException("eBay returned HTTP $status"))
                    }
                }

                override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                    cont.resumeWithException(error)
                }

                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                    if (cont.isActive) cont.resumeWithException(IOException("Request canceled"))
                }
            }

            val builder = engine.newUrlRequestBuilder(url, callback, executor)
                .setHttpMethod("GET")
                // Match a real Chrome-on-Android navigation so Akamai treats it as a browser.
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("sec-ch-ua", "\"Chromium\";v=\"143\", \"Google Chrome\";v=\"143\", \"Not?A_Brand\";v=\"24\"")
                .addHeader("sec-ch-ua-mobile", "?1")
                .addHeader("sec-ch-ua-platform", "\"Android\"")
                .addHeader("Sec-Fetch-Site", if (referer != null) "same-origin" else "none")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-User", "?1")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Upgrade-Insecure-Requests", "1")
            if (referer != null) builder.addHeader("Referer", referer)
            val cookieHeader = cookieHeader()
            if (cookieHeader.isNotEmpty()) builder.addHeader("Cookie", cookieHeader)

            val request = builder.build()
            cont.invokeOnCancellation { request.cancel() }
            request.start()
        }

    private fun captureCookies(info: UrlResponseInfo) {
        for (header in info.allHeadersAsList) {
            if (!header.key.equals("set-cookie", ignoreCase = true)) continue
            val pair = header.value.substringBefore(';').trim()
            val name = pair.substringBefore('=', missingDelimiterValue = "").trim()
            val value = pair.substringAfter('=', missingDelimiterValue = "")
            if (name.isNotEmpty()) cookies[name] = value
        }
    }

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    companion object {
        private const val READ_BUFFER_BYTES = 32 * 1024
        private const val HOME_URL = "https://www.ebay.com/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Pixel 10 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36"
    }
}
