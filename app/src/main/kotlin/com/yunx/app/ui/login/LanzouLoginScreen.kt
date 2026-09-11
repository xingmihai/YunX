/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.ui.login

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.yunx.app.data.network.LanzouConstants
import com.yunx.app.data.repository.LanzouSaveResult
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import kotlinx.coroutines.launch

/**
 * 蓝奏云登录页：WebView 打开用户后台，用户登录后自动/手动保存 Cookie。
 *
 * ★ WebView 的 UA 强制设为 [LanzouConstants.USER_AGENT]（固定桌面 UA）：
 *   蓝奏云会校验 UA 一致性，若登录用 Android UA 而 API 用桌面 UA，
 *   极可能被判定为异常会话导致接口 403。因此两边共用同一常量。
 *
 * 凭证取**整个域的 Cookie 串**（含 WAF 挑战凭证），而非挑关键字段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanzouLoginScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    /** 落库并返回结果（由调用方注入 repository） */
    validateAndSave: suspend (String) -> LanzouSaveResult
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = rememberGlobalSnackbarHostState()
    var isSaving by remember { mutableStateOf(false) }

    /**
     * 读取 Cookie。
     *
     * ★ 必须 [CookieManager.flush]：WebView 的 cookie 写入是异步的，
     *   不 flush 就立刻 getCookie 可能读到空 —— 表现为「刚登录完却检测不到」。
     */
    fun readCookie(): String {
        val cm = CookieManager.getInstance()
        runCatching { cm.flush() }
        return LanzouConstants.extractCookie { cm.getCookie(it) }
    }

    rememberWebLoginAutoDetect(
        sampleCredential = { readCookie() },
        isPlausible = { LanzouConstants.isPlausibleCookie(it) },
        validateAndSave = { validateAndSave(it) is LanzouSaveResult.Success },
        isPaused = { isSaving },
        onInFlightChange = { isSaving = it },
        onAutoSaved = onSaved
    )


    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("蓝奏云登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                val cookie = readCookie()
                                val result = validateAndSave(cookie)
                                isSaving = false
                                when (result) {
                                    LanzouSaveResult.Success -> {
                                        SnackbarController.show("登录成功")
                                        onSaved()
                                    }
                                    LanzouSaveResult.NoCookie ->
                                        SnackbarController.show(
                                            "未读取到 Cookie（${LanzouConstants.cookieNames(cookie)}）"
                                        )
                                    LanzouSaveResult.NotLoggedIn ->
                                        SnackbarController.show(
                                            "Cookie 中没有登录信息，请先完成登录（${LanzouConstants.cookieNames(cookie)}）"
                                        )
                                }
                            }
                        },
                        enabled = !isSaving
                    ) { Text(if (isSaving) "保存中…" else "保存") }
                }
            )
        }
    ) { padding ->
        // ★ WebView 必须在离开页面时 destroy()：否则它的资源与浏览状态（含登录态）
        //   会一直留着，反复进出登录页会累积多个实例。
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        DisposableEffect(Unit) {
            onDispose {
                webViewRef?.apply {
                    stopLoading()
                    webViewClient = WebViewClient()
                    destroy()
                }
                webViewRef = null
            }
        }
        AndroidView(
            modifier = Modifier.padding(padding).fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = LanzouConstants.USER_AGENT
                    webViewClient = WebViewClient()
                    // 未登录时会跳转登录页；uid 未知，先打开后台首页由服务端重定向
                    loadUrl("https://pc.woozooo.com/mydisk.php")
                    webViewRef = this
                }
            }
        )
    }
}
