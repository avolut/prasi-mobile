package com.prasi.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.view.Window
import android.view.WindowInsets as AndroidWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.prasi.mobile.web.AccompanistWebViewClient
import com.prasi.mobile.web.WebView
import com.prasi.mobile.web.rememberWebViewNavigator
import com.prasi.mobile.web.rememberWebViewState


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun App() {
    MaterialTheme {
        val url by remember { mutableStateOf("https://github.com/") }
        val webViewState = rememberWebViewState(url)
        val webViewNavigator = rememberWebViewNavigator()
        var statusBarColor by remember { mutableStateOf(Color.Black) }
        var statusBarDarkIcons by remember { mutableStateOf(true) }
        val context = LocalContext.current

        DisposableEffect(statusBarColor, statusBarDarkIcons) {
            val window = (context as Activity).window

            // Set the status bar color
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                    view.setBackgroundColor(statusBarColor.toArgb())
                    insets
                }
            } else {
                window.statusBarColor = statusBarColor.toArgb()
            }

            // Make sure the status bar is visible
            window.addFlags(Window.FEATURE_NO_TITLE)
            window.clearFlags(Window.FEATURE_ACTION_BAR)
            window.clearFlags(Window.FEATURE_ACTION_BAR_OVERLAY)

            // Configure system UI
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.apply {
                isAppearanceLightStatusBars = !statusBarDarkIcons
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(AndroidWindowInsets.Type.statusBars())
                }
            }
            onDispose {}
        }

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val processedUrls = remember { mutableSetOf<String>() }
            val client = remember {
                object : AccompanistWebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null && processedUrls.add(url)) {
                            view.evaluateJavascript(
                                """
function getBGColor(el) {
    const style = window.getComputedStyle(el);
    const bg = style.backgroundColor;
    if ((bg === "transparent" || bg === "rgba(0, 0, 0, 0)" || bg === "rgba(255, 255, 255, 0)") && el.parentElement) {
        return getBGColor(el.parentElement);
    }
    return bg;
}

(function() {
    try {
        const headerElement = document.querySelector('header');
        if (headerElement) {
            const color = getBGColor(headerElement);
            return color;
        }
        
        const bodyElement = document.body || document.documentElement;
        const color = getBGColor(bodyElement);
        return color;
    } catch(e) {
        console.error("Error detecting color:", e);
        return "rgb(255, 255, 255)";  // fallback to white
    }
})();
""".trimIndent()
                            ) { result ->
                                println("JavaScript result: $result")
                                if (result == "null") {
                                    statusBarColor = Color.White
                                    statusBarDarkIcons = true
                                    return@evaluateJavascript
                                }

                                val cleanResult = result.trim('"')

                                try {
                                    val detectedColor = parseColor(cleanResult)
                                    statusBarColor = detectedColor

                                    // Calculate luminance to determine if we should use dark icons
                                    val luminance =
                                        (0.299f * detectedColor.red + 0.587f * detectedColor.green + 0.114f * detectedColor.blue)
                                    statusBarDarkIcons = luminance > 0.5f
                                } catch (e: Exception) {
                                    println("Error parsing color: $cleanResult")
                                    e.printStackTrace()
                                }
                            }
                        }
                    }

                    override fun onPageStarted(
                        view: android.webkit.WebView,
                        url: String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        processedUrls.clear() // Clear the set when a new page load starts
                    }
                }
            }

            WebView(
                state = webViewState,
                navigator = webViewNavigator,
                modifier = Modifier.fillMaxSize(),
                client = client,
                onCreated = {
                    println("WebView created")
                    it.settings.javaScriptEnabled = true
                }
            )
        }
    }
}

fun parseColor(colorStr: String): Color {
    return when {
        // Parse rgb format: rgb(r, g, b)
        colorStr.startsWith("rgb(") -> {
            val rgb = colorStr.removePrefix("rgb(").removeSuffix(")").split(",")
                .map { it.trim().toFloat() / 255f }
            Color(rgb[0], rgb[1], rgb[2])
        }

        // Parse rgba format: rgba(r, g, b, a)
        colorStr.startsWith("rgba(") -> {
            val rgba = colorStr.removePrefix("rgba(").removeSuffix(")").split(",")
                .map { it.trim().toFloat() }
            Color(rgba[0] / 255f, rgba[1] / 255f, rgba[2] / 255f, rgba[3])
        }

        // Parse hex format: #RRGGBB or #RRGGBBAA
        colorStr.startsWith("#") -> {
            val hex = colorStr.removePrefix("#")
            when (hex.length) {
                6 -> Color(
                    red = hex.substring(0, 2).toInt(16) / 255f,
                    green = hex.substring(2, 4).toInt(16) / 255f,
                    blue = hex.substring(4, 6).toInt(16) / 255f
                )

                8 -> Color(
                    red = hex.substring(0, 2).toInt(16) / 255f,
                    green = hex.substring(2, 4).toInt(16) / 255f,
                    blue = hex.substring(4, 6).toInt(16) / 255f,
                    alpha = hex.substring(6, 8).toInt(16) / 255f
                )

                else -> Color.White
            }
        }

        else -> Color.White
    }
}