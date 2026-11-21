package com.example.data.remote

import com.example.data.repository.YouTubeAccountStore
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewPipeDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val accountStore: YouTubeAccountStore
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = dataToSend?.let { bytes ->
            val contentType = headers["Content-Type"]?.firstOrNull()
                ?: "application/json; charset=utf-8"
            bytes.toRequestBody(contentType.toMediaTypeOrNull())
        }

        val activeAccount = if (isSecureYouTubeUrl(url)) accountStore.getActiveAccountSnapshot() else null

        val okHttpHeaders = buildMap {
            put("User-Agent", USER_AGENT)
            headers.forEach { (key, values) ->
                if (values.isNotEmpty()) {
                    put(key, values.joinToString(", "))
                }
            }
            if (activeAccount != null && activeAccount.cookieHeader.isNotBlank()) {
                put("Cookie", YouTubeMusicAccountApi.normalizeCookieHeader(activeAccount.cookieHeader))
                put("X-Goog-AuthUser", activeAccount.authUserIndex.coerceAtLeast(0).toString())
                YouTubeMusicAccountApi.buildSapisidAuthorizationHeader(
                    cookieHeader = activeAccount.cookieHeader,
                    origin = YouTubeMusicAccountApi.YOUTUBE_WEB_ORIGIN
                )?.let { authHeader ->
                    put("Authorization", authHeader)
                    put("X-Origin", YouTubeMusicAccountApi.YOUTUBE_WEB_ORIGIN)
                }
            }
        }.toHeaders()

        val okHttpRequest = okhttp3.Request.Builder()
            .url(url)
            .headers(okHttpHeaders)
            .method(httpMethod, requestBody)
            .build()

        okHttpClient.newCall(okHttpRequest).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("Rate limited by YouTube (HTTP 429)", url)
            }

            val responseBody = response.body?.string().orEmpty()
            val responseHeaders = mutableMapOf<String, List<String>>()
            for (name in response.headers.names()) {
                responseHeaders[name] = response.headers.values(name)
            }

            return Response(
                response.code,
                response.message,
                responseHeaders,
                responseBody,
                response.request.url.toString()
            )
        }
    }

    private fun isSecureYouTubeUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtubei.googleapis.com"
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
