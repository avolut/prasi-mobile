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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Interface for cache update notifications
interface CacheUpdateListener {
    fun onCacheUpdated(url: String)
}

class ProxyServer(
    private val context: Context, 
    private var baseUrl: String,
    private var basePathSegment: String
) {
    private val tag = "ProxyServer"
    private val cacheDir = File(context.cacheDir, "proxy_cache")
    private val cacheSize = 50L * 1024L * 1024L // 50 MB cache
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheUpdateListeners = ConcurrentHashMap<String, MutableList<CacheUpdateListener>>()

    // Keep track of currently refreshing URLs to avoid duplicate refreshes
    private val refreshingUrls = ConcurrentHashMap.newKeySet<String>()

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
                            // For all direct requests, also update the cache
                            refreshCacheAsync(fullUrl, networkRequest)
                            proxyRequestAsync(fullUrl, networkRequest)
                        } catch (e: Exception) {
                            Log.e(tag, "Network error for $fullUrl: ${e.message}")
                            // Try to get from cache even for non-cacheable files when offline
                            try {
                                val cachedRequest = networkRequest.newBuilder()
                                    .cacheControl(CacheControl.FORCE_CACHE)
                                    .build()
                                val cachedResponse = executeRequestAsync(cachedRequest)
                                if (cachedResponse.code != 504) {
                                    Log.d(tag, "Offline - using cached version for: $fullUrl")
                                    return@runBlocking createMockResponse(cachedResponse)
                                }
                            } catch (innerException: Exception) {
                                Log.d(tag, "No cache available for offline request: $fullUrl")
                            }
                            
                            MockResponse()
                                .setResponseCode(503)
                                .setBody("Offline: Content not available in cache.")
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
                    // Force network request to bypass cache
                    val networkRequest = request.newBuilder()
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .build()
                    
                    executeRequestAsync(networkRequest).use { response ->
                        Log.d(tag, "Response for $url: ${response.code}")
                        // If this is a successful response, store it in cache explicitly
                        if (response.isSuccessful) {
                            // Notify listeners about the update
                            notifyCacheListeners(url)
                        }
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

                // Add headers to disable browser caching
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
                addHeader("Pragma", "no-cache")
                addHeader("Expires", "0")

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

        private fun notifyCacheListeners(url: String) {
            Log.d(tag, "Notifying cache update for: $url")
            cacheUpdateListeners[url]?.forEach { listener ->
                listener.onCacheUpdated(url)
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
            val url = originalResponse.request.url.toString()
            val shouldCache = cachingFileTypes.any { url.endsWith(it, ignoreCase = true) }
            
            if (shouldCache) {
                // Force cache for cacheable file types, but with a shorter max-age
                // to encourage more frequent updates
                Log.d(tag, "Forcing cache for: $url")
                originalResponse.newBuilder()
                    .header("Cache-Control", "public, max-age=300") // 5 minutes
                    .removeHeader("Pragma") // Remove potential no-cache directives
                    .build()
            } else {
                // Cache all responses for a short period to help with offline access
                Log.d(tag, "Adding minimal cache for: $url")
                originalResponse.newBuilder()
                    .header("Cache-Control", "public, max-age=60") // 1 minute
                    .removeHeader("Pragma") 
                    .build()
            }
        }
        .build()

    private val server = MockWebServer()
    private var isServerRunning = false

    private fun refreshCacheAsync(url: String, request: Request) {
        // Skip if we're already refreshing this URL
        if (!refreshingUrls.add(url)) {
            Log.d(tag, "Already refreshing cache for: $url")
            return
        }

        // Use a non-blocking approach to update the cache in the background
        coroutineScope.launch {
            try {
                // First get the cached response to compare with network version later
                val cachedRequest = request.newBuilder()
                    .cacheControl(CacheControl.FORCE_CACHE)
                    .build()
                
                var cachedETag: String? = null
                var cachedLastModified: String? = null
                var cachedContent: ByteArray? = null
                
                try {
                    client.newCall(cachedRequest).execute().use { cachedResponse ->
                        // Extract ETag and Last-Modified for comparison
                        cachedETag = cachedResponse.header("ETag")
                        cachedLastModified = cachedResponse.header("Last-Modified")
                        
                        // Store content for byte-by-byte comparison if needed
                        cachedResponse.body?.let { body ->
                            cachedContent = body.bytes()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(tag, "No existing cache for comparison: $url")
                    // Continue with network request even if cache read fails
                }
                
                // Create a new request that will ignore cache and force network
                val networkRequest = request.newBuilder()
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .header("Cache-Control", "no-cache")
                    .build()
                
                val response = client.newCall(networkRequest).execute()
                if (response.isSuccessful) {
                    val networkETag = response.header("ETag")
                    val networkLastModified = response.header("Last-Modified")
                    
                    // Get network content for comparison
                    var contentChanged = false
                    val networkContent = response.body?.bytes()
                    
                    // Compare content directly if we have both cached and network content
                    if (cachedContent != null && networkContent != null) {
                        contentChanged = !cachedContent.contentEquals(networkContent)
                        Log.d(tag, "Content comparison for $url: changed=$contentChanged")
                    } else {
                        // Fall back to header-based detection
                        contentChanged = (cachedETag != null && networkETag != null && cachedETag != networkETag) ||
                                      (cachedLastModified != null && networkLastModified != null && 
                                       cachedLastModified != networkLastModified)
                    }
                    
                    Log.d(tag, "Successfully refreshed cache for: $url, content changed: $contentChanged")
                    
                    if (contentChanged) {
                        // Ensure the updated content is properly stored in cache
                        // by making a request that writes to the cache
                        val cacheUpdateRequest = Request.Builder()
                            .url(url)
                            .cacheControl(CacheControl.Builder()
                                .maxAge(5, TimeUnit.MINUTES)
                                .build())
                            .build()
                        
                        // Execute and close this request - it's just to update the cache
                        client.newCall(cacheUpdateRequest).execute().close()
                        
                        // Notify listeners that content has been updated
                        val listeners = cacheUpdateListeners[url]
                        listeners?.forEach { listener ->
                            listener.onCacheUpdated(url)
                        }
                    }
                } else {
                    Log.d(tag, "Cache refresh failed for: $url with code: ${response.code}")
                }
                // Close the response to ensure resources are released
                response.close()
            } catch (e: Exception) {
                // Just log the error but don't do anything else since this is a background refresh
                Log.e(tag, "Error refreshing cache for $url: ${e.message}")
            } finally {
                // Mark this URL as no longer being refreshed
                refreshingUrls.remove(url)
            }
        }
    }

    fun start() {
        if (isServerRunning) {
            Log.i(tag, "Proxy server already running at ${getProxyUrl()}")
            return
        }
        
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        server.dispatcher = dispatcher
        coroutineScope.launch {
            try {
                server.start()
                isServerRunning = true
                Log.i(tag, "Proxy server started at ${getProxyUrl()}")
            } catch (e: Exception) {
                Log.e(tag, "Error starting server: ${e.message}")
            }
        }
    }

    fun stop() {
        if (!isServerRunning) {
            Log.i(tag, "Proxy server already stopped")
            return
        }
        
        coroutineScope.launch {
            try {
                server.shutdown()
                isServerRunning = false
                Log.i(tag, "Proxy server stopped")
            } catch (e: Exception) {
                Log.e(tag, "Error stopping server: ${e.message}")
            }
        }
    }

    fun getProxyUrl(): String {
        if (!isServerRunning) {
            return ""
        }
        return "http://${server.hostName}:${server.port}"
    }

    fun setBaseUrl(url: String) {
        baseUrl = if (url.endsWith("/")) url else "$url/"
    }
    
    fun setBasePathSegment(path: String) {
        basePathSegment = path
    }
    
    /**
     * Register a listener to be notified when a specific URL's cache is updated
     * @param url The URL to monitor for updates
     * @param listener The listener to be called when the cache is updated
     */
    fun registerCacheUpdateListener(url: String, listener: CacheUpdateListener) {
        cacheUpdateListeners.getOrPut(url) { mutableListOf() }.add(listener)
    }
    
    /**
     * Unregister a cache update listener for a specific URL
     * @param url The URL to stop monitoring
     * @param listener The listener to remove
     */
    fun unregisterCacheUpdateListener(url: String, listener: CacheUpdateListener) {
        cacheUpdateListeners[url]?.remove(listener)
        if (cacheUpdateListeners[url]?.isEmpty() == true) {
            cacheUpdateListeners.remove(url)
        }
    }
    
    /**
     * Clears all cached responses
     * Call this when you need to force a complete refresh of all content
     */
    fun clearCache() {
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Delete the cache directory
                    if (cacheDir.exists()) {
                        val files = cacheDir.listFiles()
                        files?.forEach { file ->
                            file.delete()
                        }
                        Log.d(tag, "Cache cleared successfully")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error clearing cache: ${e.message}")
            }
        }
    }
    
    /**
     * Force refresh the cache for a specific URL
     * @param url The URL to refresh
     */
    fun forceCacheRefresh(url: String) {
        coroutineScope.launch {
            try {
                val request = Request.Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
                
                Log.d(tag, "Forcing cache refresh for: $url")
                this@ProxyServer.refreshCacheAsync(url, request)
            } catch (e: Exception) {
                Log.e(tag, "Error forcing cache refresh: ${e.message}")
            }
        }
    }
}