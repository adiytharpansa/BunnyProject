package com.bunny.app.presentation.screens.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.bunny.app.R
import com.bunny.app.presentation.screens.profile.AuthViewModel
import com.bunny.engine.lontar.AuthCookies

/**
 * Fase 5 sign-in: Google's own login page in a WebView, driven by the
 * user (Bunny never sees the password). The WebView presents a plain
 * desktop user agent — Google refuses sign-in to windows it can tell
 * are embedded WebViews. Whenever a page finishes, the youtube.com
 * cookie jar is checked: once it holds a real signed-in session, the
 * cookies are handed to the encrypted store and this screen closes
 * itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onFinished: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    var harvested by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title)) },
                navigationIcon = {
                    IconButton(onClick = onFinished) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.login_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                stringResource(R.string.login_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = DESKTOP_USER_AGENT
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (harvested) return
                                val header = CookieManager.getInstance()
                                    .getCookie("https://www.youtube.com")
                                if (header != null &&
                                    AuthCookies.fromCookieHeader(header) != null
                                ) {
                                    harvested = true
                                    viewModel.onCookiesHarvested(header) { ok ->
                                        if (ok) onFinished()
                                    }
                                }
                            }
                        }
                        loadUrl(SIGN_IN_URL)
                    }
                },
            )
        }
    }
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2F"
