package com.prasi.mobile.proxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

class ProxyServer(
    private val context: Context, 
    private var baseUrl: String,
    private var basePathSegment: String
) {
    private val tag = "ProxyServer"
    private val cacheDir = File(context.cacheDir, "proxy_cache")
    private val cacheSize = 50L * 1024L * 1024L // 50 MB cache
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                val fullUrl = if (urlPath.startsWith("/http")) {
                    urlPath.substring(1)
                } else if (urlPath.contains("://")) {
                    urlPath.substring(urlPath.indexOf("://") - 4)
                } else {
                    val normalizedPath = if (urlPath.startsWith("/") && baseUrl.endsWith("/")) {
                        urlPath.substring(1)
                    } else if (!urlPath.startsWith("/") && !baseUrl.endsWith("/")) {
                        "/$urlPath"
                    } else {
                        urlPath
                    }

                    val finalUrl = if (normalizedPath.contains(basePathSegment)) {
                        val pathParts = normalizedPath.split(basePathSegment)
                        if (pathParts.size > 1) {
                            val basePart = baseUrl.substringBeforeLast(basePathSegment, baseUrl)
                            "$basePart$basePathSegment${pathParts.last()}"
                        } else {
                            baseUrl + normalizedPath
                        }
                    } else {
                        baseUrl + normalizedPath
                    }

                    Log.d(tag, "Proxying request to: $finalUrl from path: $urlPath")
                    finalUrl
                }

                val shouldCache = cachingFileTypes.any { fullUrl.endsWith(it, ignoreCase = true) }

                val requestBuilder = Request.Builder()
                    .url(fullUrl)

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
                                // Even though we got a cache hit, still update the cache in the background
                                refreshCacheAsync(fullUrl, networkRequest)
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

        private fun refreshCacheAsync(url: String, request: Request) {
            // Use a non-blocking approach to update the cache in the background
            coroutineScope.launch {
                try {
                    // Create a new request that will ignore cache and force network
                    val networkRequest = request.newBuilder()
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build()
                    
                    val response = client.newCall(networkRequest).execute()
                    if (response.isSuccessful) {
                        Log.d(tag, "Successfully refreshed cache for: $url")
                    } else {
                        Log.d(tag, "Cache refresh failed for: $url with code: ${response.code}")
                    }
                    // Close the response to ensure resources are released
                    response.close()
                } catch (e: Exception) {
                    // Just log the error but don't do anything else since this is a background refresh
                    Log.e(tag, "Error refreshing cache for $url: ${e.message}")
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
                .removeHeader("Pragma") // Remove potential no-cache directives
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

    fun setBaseUrl(url: String) {
        baseUrl = if (url.endsWith("/")) url else "$url/"
    }
    
    fun setBasePathSegment(path: String) {
        basePathSegment = path
    }
}