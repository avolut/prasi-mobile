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
import com.prasi.mobile.screens.WebViewScreen

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun App() {
    MaterialTheme {
        val context = LocalContext.current
        var statusBarColor by remember { mutableStateOf(Color.Black) }
        var statusBarDarkIcons by remember { mutableStateOf(true) }

        // Configure the WebView parameters
        val baseUrl = "https://prasi.avolut.com"
        val basePathname = "/prod/bf706e40-2a3a-4148-9cdd-75d4483328d7/moka/coba"

        // Set up status bar appearance
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
            // Use the new WebViewScreen with cache update notification support
            WebViewScreen(
                baseUrl = baseUrl,
                basePathname = basePathname
            )
        }
    }
}