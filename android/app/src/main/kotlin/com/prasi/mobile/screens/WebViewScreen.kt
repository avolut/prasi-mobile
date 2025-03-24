package com.prasi.mobile.screens

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Scaffold
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.prasi.mobile.R
import com.prasi.mobile.proxy.CacheUpdateListener
import com.prasi.mobile.proxy.ProxyServer
import com.prasi.mobile.web.LoadingState
import com.prasi.mobile.web.WebView
import com.prasi.mobile.web.rememberWebViewNavigator
import com.prasi.mobile.web.rememberWebViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    baseUrl: String,
    basePathname: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val proxyServer = remember {
        ProxyServer(context, baseUrl, basePathname)
    }
    
    var showRefreshDialog by remember { mutableStateOf(false) }
    var updateUrl by remember { mutableStateOf("") }
    
    var proxyUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var retryCount by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                proxyServer.start()
                while (proxyUrl.isEmpty() && retryCount < 10) {
                    val proxy = proxyServer.getProxyUrl()
                    if (proxy.isNotEmpty()) {
                        proxyUrl = "$proxy$basePathname"
                    } else {
                        retryCount++
                        delay(500)
                    }
                }
                if (proxyUrl.isEmpty()) {
                    Log.e("WebViewScreen", "Failed to initialize proxy server after $retryCount retries")
                }
            } catch (e: Exception) {
                Log.e("WebViewScreen", "Error initializing proxy server", e)
            } finally {
                isLoading = false
            }
        }
    }
    
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Loading",
                modifier = Modifier
                    .size(100.dp)
                    .align(Alignment.Center)
            )
        }
        return
    }
    
    if (proxyUrl.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Text("Failed to initialize proxy server. Please check your connection and try again.")
        }
        return
    }
    
    val webViewState = rememberWebViewState(url = proxyUrl)
    val navigator = rememberWebViewNavigator()
    
    val cacheUpdateListener = remember {
        object : CacheUpdateListener {
            override fun onCacheUpdated(url: String) {
                Log.d("WebViewScreen", "Cache updated for: $url")
                scope.launch {
                    updateUrl = url
                    showRefreshDialog = true
                }
            }
        }
    }
    
    LaunchedEffect(webViewState.lastLoadedUrl) {
        webViewState.lastLoadedUrl?.let { url ->
            proxyServer.registerCacheUpdateListener(url, cacheUpdateListener)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            webViewState.lastLoadedUrl?.let { url ->
                proxyServer.unregisterCacheUpdateListener(url, cacheUpdateListener)
            }
            proxyServer.stop()
        }
    }
    
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            WebView(
                state = webViewState,
                navigator = navigator,
                modifier = Modifier.fillMaxSize(),
                onCreated = { webView ->
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                }
            )
            
            if (webViewState.loadingState is LoadingState.Loading) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "Loading",
                    modifier = Modifier
                        .size(100.dp)
                        .align(Alignment.Center)
                )
            }
            
            if (showRefreshDialog) {
                AlertDialog(
                    onDismissRequest = { showRefreshDialog = false },
                    title = { Text("Content Updated") },
                    text = { Text("New content is available. Would you like to refresh to see the latest version?") },
                    confirmButton = {
                        Button(
                            onClick = {
                                navigator.reload()
                                showRefreshDialog = false
                            }
                        ) {
                            Text("Refresh")
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { showRefreshDialog = false }
                        ) {
                            Text("Later")
                        }
                    }
                )
            }
        }
    }
}