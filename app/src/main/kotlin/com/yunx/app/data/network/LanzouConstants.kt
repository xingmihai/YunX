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

package com.yunx.app.data.network

/**
 * 蓝奏云（lanzou / woozooo）常量。
 *
 * ★ 全部接口均**逆向自网页端抓包**，无官方 API，字段名与服务端保持一致。
 *   域名为 `pc.woozooo.com`（蓝奏云用户后台），与分享页域名（lanzoux.com 等）不同。
 *
 * ★ 关于 vei（关键）：
 *   每个接口都要带 `vei` 参数。实测它**与 folder_id / task 无关** ——
 *   同一页面内 task=47(folder=-1)、task=47(folder=13818484)、task=5(folder=13818484)
 *   三处的 vei 完全相同；但**跨会话会变**（两次 PHPSESSID 不同则值不同）。
 *   结论：vei 是服务端渲染 mydisk.php 时生成、写死进页面 JS 的**页面级令牌**。
 *   因此**不能写死**，必须从页面 HTML 用正则提取（见 [LanzouApi.fetchVei]）。
 */
object LanzouConstants {

    /** 用户后台域名（登录、文件管理；与分享页 lanzou*.com 不同） */
    const val BASE = "https://pc.woozooo.com"

    /**
     * Cookie 候选来源（CookieManager 取值用）。
     *
     * ★ 必须是**完整 URL**（带 scheme），不能是裸域名：
     *   `CookieManager.getCookie()` 的参数是 URL，传 "pc.woozooo.com" 会返回 null
     *   —— 对照 C139Constants 的写法（`https://mail.10086.cn`）即可确认。
     *
     * ★ 为什么列多个：蓝奏云的后台与登录页可能落在不同域/子域，
     *   只查一个域会漏。多域扫描后按 cookie 名合并去重。
     */
    val COOKIE_URLS = listOf(
        "$BASE",
        "https://.pc.woozooo.com",
        "https://.woozooo.com",
        "https://woozooo.com"
    )

    /** 用户后台主页：登录入口，同时也是 vei 的来源页面 */
    fun mydiskUrl(uid: String): String = "$BASE/mydisk.php?item=files&action=index&u=$uid"

    /** 统一业务接口（所有 task 都走它） */
    fun douploadUrl(uid: String): String = "$BASE/doupload.php?uid=$uid"

    /** task=47：列出子文件夹（响应 info 为面包屑、text 为子文件夹数组） */
    const val TASK_FOLDER_LIST = 47

    /** task=5：列出文件（响应 text 为文件数组，需带 pg 分页） */
    const val TASK_FILE_LIST = 5

    /** 根目录 id（蓝奏云用字符串 "-1" 表示根） */
    const val ROOT_FOLDER_ID = "-1"

    /** 登录态关键 Cookie 名：用户 id（存在 ylogin 中，如 5189768） */
    const val COOKIE_UID = "ylogin"

    /** 登录态关键 Cookie 名之一：持久化登录凭证 */
    const val COOKIE_PHPDISK_INFO = "phpdisk_info"

    /** 从 mydisk.php 页面 HTML 中提取 vei 的正则（页面 JS 形如 vei:'WFxQUlBWVwgHBQ9fC1E='） */
    val VEI_REGEX = Regex("""vei\s*:\s*'([^']+)'""")

    /**
     * 固定桌面 UA。
     *
     * ★ 登录 WebView 与所有 API 请求**必须共用同一个 UA**：蓝奏云会校验一致性，
     *   两边不一致（一边 Android 一边桌面）极可能被判定为异常会话。
     *   值取自实测可用的抓包样本。
     */
    /** 从 Cookie 串提取用户 id（形如 ylogin=5189768）；接口 URL 需要 ?uid= */
    fun extractUid(cookie: String): String =
        Regex("ylogin=(\\d+)").find(cookie)?.groupValues?.get(1).orEmpty()

    /**
     * 从 CookieManager 读取 Cookie 串（多域扫描后按名合并去重）。
     *
     * ★ 取**全部** cookie 而非挑字段：WAF 挑战凭证（acw_tc / cdn_sec_tc /
     *   acw_sc__v2）也在其中，丢了会让后续接口偶发 403。
     *   （其他网盘平台用 KEEP_KEYS 白名单挑关键字段，蓝奏云不能这么做。）
     */
    fun extractCookie(get: (String) -> String?): String {
        val out = linkedMapOf<String, String>()
        for (url in COOKIE_URLS) {
            val raw = get(url) ?: continue
            for (kv in raw.split(";")) {
                val part = kv.trim()
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val name = part.substring(0, eq)
                // 先出现的优先（更具体的域在前）
                if (name !in out) out[name] = part
            }
        }
        return out.values.joinToString("; ")
    }

    /**
     * 廉价预检：是否已登录（不发网络请求）。
     *
     * ★ 只要求 `ylogin`（用户 id）：这是判断登录态的必要条件，也是 [extractUid]
     *   的唯一来源。此前还要求 `phpdisk_info`，但该字段并非所有登录态都有，
     *   多一个条件就多一种「明明登录了却检测不到」的可能。
     */
    fun isPlausibleCookie(cookie: String): Boolean = cookie.contains("ylogin=")

    /** 诊断用：列出 cookie 的名称（**不含值**，可安全展示/上报） */
    fun cookieNames(cookie: String): String =
        cookie.split(";").mapNotNull { part ->
            val eq = part.trim().indexOf('=')
            if (eq > 0) part.trim().substring(0, eq) else null
        }.joinToString(", ").ifEmpty { "（无）" }

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
}
