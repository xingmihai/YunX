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

package com.yunx.app.data.prefs

import android.content.Context
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.security.AndroidKeystoreCredentialCipher

/**
 * 应用设置（SharedPreferences 持久化）。
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("yunx_settings", Context.MODE_PRIVATE)

    /** WebDAV 凭据加密器（Keystore 密钥不可导出，密文即使被提取也解不开） */
    private val credentialCipher = AndroidKeystoreCredentialCipher()

    /** 最近一次在「下载直链」弹窗中选择的线程数（下次弹窗预填，仍可修改） */
    var lastDownloadThreads: Int
        get() = prefs.getInt("last_download_threads", DEFAULT_DOWNLOAD_THREADS)
            .coerceIn(1, MAX_DOWNLOAD_THREADS)
        set(value) {
            prefs.edit().putInt("last_download_threads", value.coerceIn(1, MAX_DOWNLOAD_THREADS)).apply()
        }

    /**
     * 未在下载时指定线程数时的兜底值（手动添加、更新 APK、批量下载等入口）。
     * 迅雷固定 8：其 CDN 对单文件并发 Range 有阈值，超过会被降级为 200 整文件。
     */
    fun defaultThreadsFor(platform: String): Int = when (platform) {
        DownloadPlatform.XUNLEI -> XUNLEI_DOWNLOAD_THREADS
        // 蓝奏云固定单流：直链走 ESA WAF，并发 Range 请求会触发 http_auto_ratelimit
        // （返回 JS 挑战页而非文件），且服务端忽略 Range（返回 200 而非 206），
        // 多线程本就无效。单流可把请求数降到最低，避免把 IP 推入限流。
        DownloadPlatform.LANZOU -> LANZOU_DOWNLOAD_THREADS
        else -> lastDownloadThreads
    }

    /** 自定义下载保存目录（SAF tree Uri，content://...）；null/空 = 系统默认 Download 目录 */
    var downloadDirUri: String?
        get() = prefs.getString("download_dir_uri", null)
        set(value) {
            prefs.edit().putString("download_dir_uri", value).apply()
        }

    /** 最大同时下载任务数（默认 1：前台任务吃满带宽，其余排队；参考 IDM 默认单任务满速） */
    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent_downloads", DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        set(value) {
            prefs.edit().putInt("max_concurrent_downloads", value.coerceIn(1, 10)).apply()
        }

    /** 下载速度限制（字节/秒；0 = 不限速） */
    var downloadSpeedLimit: Long
        get() = prefs.getLong("download_speed_limit", 0L)
        set(value) {
            prefs.edit().putLong("download_speed_limit", value.coerceAtLeast(0L)).apply()
        }

    /** 下载失败后自动重试次数（默认 3，范围 0-10） */
    var downloadRetryCount: Int
        get() = prefs.getInt("download_retry_count", DEFAULT_DOWNLOAD_RETRY_COUNT)
        set(value) {
            prefs.edit().putInt("download_retry_count", value.coerceIn(0, 10)).apply()
        }

    /** 锁屏后保持下载：开启后下载时获取 WakeLock，并可引导加入「忽略电池优化」白名单（默认开启） */
    var keepDownloadWhenLocked: Boolean
        get() = prefs.getBoolean("keep_download_when_locked", true)
        set(value) {
            prefs.edit().putBoolean("keep_download_when_locked", value).apply()
        }

    /** 通知栏进度样式：true=完整通知（进度条+下载速度）；false=仅显示通知（隐藏速度） */
    var notificationShowSpeed: Boolean
        get() = prefs.getBoolean("notification_show_speed", true)
        set(value) {
            prefs.edit().putBoolean("notification_show_speed", value).apply()
        }

    /** 桌面图标样式：0=经典图标(icon)，1=新图标(icon2)；切换经 activity-alias 动态生效 */
    var appIconVariant: Int
        get() = prefs.getInt("app_icon_variant", 0)
        set(value) {
            prefs.edit().putInt("app_icon_variant", value.coerceIn(0, 1)).apply()
        }

    /** 忽略 SSL 证书校验（抓包调试用，隐藏菜单开启；默认关闭） */
    var ignoreSslCert: Boolean
        get() = prefs.getBoolean("ignore_ssl_cert", false)
        set(value) {
            prefs.edit().putBoolean("ignore_ssl_cert", value).apply()
        }

    /** 百度网盘大文件限速提示：是否已选择「不再显示」 */
    var baiduLimitHintDismissed: Boolean
        get() = prefs.getBoolean("baidu_limit_hint_dismissed", false)
        set(value) {
            prefs.edit().putBoolean("baidu_limit_hint_dismissed", value).apply()
        }

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode: Int
        get() = prefs.getInt("dark_mode", 0)
        set(value) {
            prefs.edit().putInt("dark_mode", value.coerceIn(0, 2)).apply()
        }

    /** 主题色模式：0=动态色彩（Android12+ 壁纸取色，低版本回退默认蓝），1=默认蓝色，2=自定义种子色 */
    var themeColorMode: Int
        get() = prefs.getInt("theme_color_mode", 0)
        set(value) {
            prefs.edit().putInt("theme_color_mode", value.coerceIn(0, 2)).apply()
        }

    /** 自定义主题种子色（ARGB 值） */
    var themeSeedColor: Long
        get() = prefs.getLong("theme_seed_color", DEFAULT_SEED_COLOR)
        set(value) {
            prefs.edit().putLong("theme_seed_color", value).apply()
        }

    // ------------------------------------------------------------------ 更新检测

    /**
     * 启动 App 时自动检查更新（默认开启）。
     *
     * 关闭后不再在冷启动时请求 Release 信息，但设置页的「检查更新」按钮仍可用 ——
     * 自动检查与手动检查是两个入口，关掉自动不应把手动也一并废掉。
     */
    var autoCheckUpdate: Boolean
        get() = prefs.getBoolean("auto_check_update", true)
        set(value) {
            prefs.edit().putBoolean("auto_check_update", value).apply()
        }

    // ------------------------------------------------------------------ WebDAV 备份

    /** WebDAV 服务器地址（如 https://dav.example.com/yunx）；空 = 未配置 */
    var webDavUrl: String
        get() = prefs.getString("webdav_url", null)?.trimEnd('/').orEmpty()
        set(value) {
            prefs.edit().putString("webdav_url", value.trim().trimEnd('/')).apply()
        }

    /** WebDAV 用户名（明文：用户名不是秘密，且需要在设置页回显给用户看） */
    var webDavUser: String
        get() = prefs.getString("webdav_user", null).orEmpty()
        set(value) {
            prefs.edit().putString("webdav_user", value.trim()).apply()
        }

    /**
     * WebDAV 密码（**Keystore 加密后存储**）。
     *
     * 加密原因：SharedPreferences 明文落在 /data/data，root 或 ADB 备份可提取，
     * 而 WebDAV 密码常被用户复用在其他服务上，泄漏影响面超出本 App。
     * purpose 固定为 "webdav_password"，作为 GCM 的 AAD 绑定用途，
     * 避免其他字段的密文被挪到此处复用。
     */
    //
    // ★ getter 无法表达「解密失败」，只能返回空串。因此**判断可用性必须看
    //   [isWebDavConfigured]**：它要求密码能成功解密且非空。否则一旦 Keystore
    //   密钥失效，App 会用空密码发起 Basic Auth，用户看到的是误导性的
    //   「认证失败」，而非「本地凭据不可用」。
    //
    val webDavPassword: String
        get() {
            val stored = prefs.getString("webdav_password", null) ?: return ""
            return runCatching { credentialCipher.decrypt(stored, WEBDAV_PURPOSE) }
                .getOrDefault("")
        }

    /**
     * 保存 WebDAV 密码。
     *
     * @return 是否保存成功。**加密失败（Keystore 不可用）时保留原有密文并返回 false** ——
     *   绝不退回明文存储，也不删除已有凭据（否则编辑一个正常工作的配置会静默弄坏它）。
     *   调用方须依据返回值决定如何提示用户。
     */
    fun saveWebDavPassword(value: String): Boolean {
        if (value.isEmpty()) {
            prefs.edit().remove("webdav_password").apply()
            return true
        }
        val enc = runCatching { credentialCipher.encrypt(value, WEBDAV_PURPOSE) }.getOrNull()
            ?: return false
        prefs.edit().putString("webdav_password", enc).apply()
        return true
    }

    /** WebDAV 是否已配置**且凭据可用**（地址、用户名、密码三者均非空） */
    val isWebDavConfigured: Boolean
        get() = webDavUrl.isNotBlank() && webDavUser.isNotBlank() && webDavPassword.isNotEmpty()

    companion object {
        /** WebDAV 密码加密的 AAD purpose（绑定用途，防止密文挪作他用） */
        private const val WEBDAV_PURPOSE = "webdav_password"

        const val DEFAULT_DOWNLOAD_THREADS = 32
        const val MAX_DOWNLOAD_THREADS = 512
        const val XUNLEI_DOWNLOAD_THREADS = 8
        /** 蓝奏云固定单线程：见 [defaultThreadsFor] 注释（WAF 限流 + 服务端忽略 Range） */
        const val LANZOU_DOWNLOAD_THREADS = 1
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 1
        const val DEFAULT_DOWNLOAD_RETRY_COUNT = 3

        /** 默认主题种子色：Material Blue（与内置默认方案一致） */
        const val DEFAULT_SEED_COLOR = 0xFF415F91L
    }
}
