package com.prasi.mobile.proxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.util.concurrent.TimeUnit

class ProxyServer(private val context: Context) {
    private val tag = "ProxyServer"
    private val cacheDir = File(context.cacheDir, "proxy_cache")
    private val cacheSize = 50L * 1024L * 1024L // 50 MB cache

    private val cachingFileTypes = setOf(
        ".js", ".css", ".html", ".htm",
        ".png", ".jpg", ".jpeg", ".gif", ".webp",
        ".woff", ".woff2", ".ttf", ".otf", ".eot",
        ".svg", ".ico"
    )

    private val dispatcher: Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val urlPath = request.path ?: return MockResponse().setResponseCode(400).setBody("Missing path")
            
            try {
                // Extract the full URL from the request path
                // The path should contain the full URL after the first slash
                val fullUrl = if (urlPath.startsWith("/http")) {
                    // Handle URLs that start with http
                    urlPath.substring(1) // Remove leading slash
                } else if (urlPath.contains("://")) {
                    // Handle URL that somehow contains the protocol but not at the start
                    urlPath.substring(urlPath.indexOf("://") - 4) // Assuming http or https (4 or 5 chars)
                } else {
                    // Fallback to GitHub if path doesn't contain a URL
                    "https://github.com" + urlPath
                }
                
                Log.d(tag, "Proxying request to: $fullUrl")

                // Check if we should try to use cache based on file type
                val shouldCache = cachingFileTypes.any { fullUrl.endsWith(it, ignoreCase = true) }

                // Copy headers from the original request
                val requestBuilder = Request.Builder()
                    .url(fullUrl)
                
                // Copy all original headers except Host (which needs to match the target server)
                val headers = request.headers
                for (i in 0 until headers.size) {
                    val name = headers.name(i)
                    val value = headers.value(i)
                    if (name.lowercase() != "host") {
                        requestBuilder.addHeader(name, value)
                    }
                }
                
                // Set the request body and method
                val networkRequest = requestBuilder
                    .method(request.method ?: "GET", request.body?.let {
                        if (it.size > 0) {
                            val buffer = Buffer()
                            it.copyTo(buffer)
                            val bytes = buffer.readByteArray()
                            val contentType = request.getHeader("Content-Type")?.toMediaTypeOrNull()
                            bytes.toRequestBody(contentType)
                        } else {
                            null
                        }
                    })
                    .build()

                // Use runBlocking to handle the coroutine from a non-suspending context
                return runBlocking {
                    if (shouldCache) {
                        // Always try cache first
                        val cachedRequest = networkRequest.newBuilder()
                            .cacheControl(CacheControl.FORCE_CACHE)
                            .build()

                        try {
                            val cachedResponse = executeRequestAsync(cachedRequest)
                            if (cachedResponse.code != 504) {
                                Log.d(tag, "Cache hit for: $fullUrl")
                                return@runBlocking createMockResponse(cachedResponse)
                            }
                            Log.d(tag, "Cache miss for: $fullUrl")
                        } catch (e: Exception) {
                            Log.e(tag, "Cache error for $fullUrl: ${e.message}")
                        }

                        // If cache failed, try network (which will also update the cache)
                        try {
                            return@runBlocking proxyRequestAsync(fullUrl, networkRequest)
                        } catch (e: Exception) {
                            Log.e(tag, "Network error for $fullUrl: ${e.message}")
                            // Return a better offline error response
                            return@runBlocking MockResponse()
                                .setResponseCode(503)
                                .setBody("Offline: Content not available in cache.")
                        }
                    } else {
                        Log.d(tag, "Direct request for: $fullUrl")
                        try {
                            proxyRequestAsync(fullUrl, networkRequest)
                        } catch (e: Exception) {
                            Log.e(tag, "Network error for $fullUrl: ${e.message}")
                            MockResponse()
                                .setResponseCode(503)
                                .setBody("Offline: This content requires network connection.")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error proxying request: $urlPath", e)
                return MockResponse()
                    .setResponseCode(500)
                    .setBody("Proxy error: ${e.message}")
            }
        }

        private suspend fun proxyRequestAsync(url: String, request: Request): MockResponse {
            return withContext(Dispatchers.IO) {
                try {
                    executeRequestAsync(request).use { response ->
                        Log.d(tag, "Response for $url: ${response.code}")
                        createMockResponse(response)
                    }
                } catch (e: Exception) {
                    // Check if this is a network connectivity error
                    val message = e.message ?: ""
                    val isOfflineError = message.contains("Unable to resolve host") || 
                                         message.contains("Failed to connect") ||
                                         message.contains("No address associated")
                    
                    Log.e(tag, "Network error for $url: ${e.message}", e)
                    
                    if (isOfflineError) {
                        throw e // Rethrow to be caught by the caller for offline handling
                    } else {
                        MockResponse()
                            .setResponseCode(502)
                            .setBody("Gateway error: ${e.message}")
                    }
                }
            }
        }

        private suspend fun executeRequestAsync(request: Request): Response {
            return withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
        }

        private fun createMockResponse(response: Response): MockResponse {
            return MockResponse().apply {
                // Set the response code
                val responseCode = response.code
                setResponseCode(responseCode)
                
                if (!response.isSuccessful) {
                    Log.w(tag, "Received error response with code: $responseCode, message: ${response.message}")
                    // Optionally add diagnostic information for error responses
                    setBody("Error: HTTP $responseCode ${response.message}")
                }

                // Copy headers
                val headers = response.headers
                for (i in 0 until headers.size) {
                    val name = headers.name(i)
                    val value = headers.value(i)
                    addHeader(name, value)
                }

                response.body?.let { responseBody ->
                    try {
                        // Create a buffer to hold the body
                        val buffer = Buffer()
                        responseBody.source().readAll(buffer)

                        // Set the body and content type
                        setBody(buffer)
                        responseBody.contentType()?.let { contentType ->
                            setHeader("Content-Type", contentType.toString())
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error reading response body: ${e.message}", e)
                        setBody("Error reading response: ${e.message}")
                    }
                }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .cache(Cache(cacheDir, cacheSize))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Add a network interceptor that forces caching even when headers say not to
        .addNetworkInterceptor { chain ->
            val originalResponse = chain.proceed(chain.request())
            // Force cache all responses for 1 day
            originalResponse.newBuilder()
                .header("Cache-Control", "public, max-age=86400")
                .build()
        }
        .build()

    private val server = MockWebServer()

    fun start() {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        server.dispatcher = dispatcher
        server.start()
        Log.i(tag, "Proxy server started at ${getProxyUrl()}")
    }

    fun stop() {
        server.shutdown()
        Log.i(tag, "Proxy server stopped")
    }

    fun getProxyUrl(): String {
        return "http://${server.hostName}:${server.port}"
    }
}