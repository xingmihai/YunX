package com.yunx.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** 蓝奏云分享页解析出的会话数据（跨请求复用，随 ShareSession 传递） */
data class LanzouShareSessionData(
    /** 分享链接自带的 host（如 wwboz.lanzouw.com），不硬编码域名以抗漂移 */
    val host: String,
    /** 分享路径 id（如 ilXoR3yvb92b / b01bjlukfe） */
    val shareId: String,
    val isFolder: Boolean,
    /** 提取码（无则空串） */
    val pwd: String,
    /** 文件夹专属参数（filemoreajax.php 需要） */
    val folderFileId: String = "",
    val t: String = "",
    val k: String = "",
    val puid: String = "",
    val uid: String = ""
) {
    /**
     * 序列化进 ShareSession.stoken。
     *
     * ★ 各字段做 URL 编码后再用 `|` 连接：提取码等用户可控内容
     *   理论上可能含分隔符，编码后可避免解析错位。
     */
    fun encode(): String = listOf(host, shareId, if (isFolder) "1" else "0", pwd, folderFileId, t, k, puid, uid)
        .joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    companion object {
        fun decode(stoken: String): LanzouShareSessionData? {
            val p = stoken.split("|")
            if (p.size != 9) return null
            return runCatching {
                LanzouShareSessionData(
                    host = p[0], shareId = p[1], isFolder = p[2] == "1", pwd = p[3],
                    folderFileId = p[4], t = p[5], k = p[6], puid = p[7], uid = p[8]
                )
            }.getOrNull()
        }
    }
}

/**
 * 蓝奏云**分享解析**网络层（与 [LanzouApi] 的用户云盘接口相互独立）。
 *
 * ★ 自带内存 CookieJar：抓包显示服务器会通过 Set-Cookie 下发
 *   `codelen` / `m_adb1` / `m_ad3`，后续请求必须带回，否则可能拿不到 tp 页。
 */
class LanzouShareApi {

    private val cookieStore: ConcurrentHashMap<String, MutableMap<String, String>> = ConcurrentHashMap()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<okhttp3.Cookie>) {
            val bucket = cookieStore.getOrPut(url.host) { mutableMapOf() }
            cookies.forEach { bucket[it.name] = it.value }
        }
        override fun loadForRequest(url: HttpUrl): List<okhttp3.Cookie> {
            val bucket = cookieStore[url.host] ?: return emptyList()
            return bucket.map { (name, value) ->
                okhttp3.Cookie.Builder()
                    .domain(url.host).name(name).value(value).path("/").build()
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .build()

    /** 预置初始 Cookie（抓包首次请求即带 codelen=1） */
    private fun seedCookie(host: String) {
        cookieStore.getOrPut(host) { mutableMapOf() }["codelen"] = "1"
    }

    /**
     * 导出该 host 的 Cookie 请求头串（形如 `codelen=1; m_adb1=...; m_ad3=...`）。
     *
     * ★★ 为什么必须导出：下载由 DownloadManager 用**独立的 OkHttp 实例**发起，
     *   不会走本类的 CookieJar。而这些匿名 Cookie（codelen / m_adb1 / m_ad3）
     *   正是服务器用来校验会话连续性的 —— 缺了它们，直链会返回
     *   `200 + text/html`（广告/错误页）而不是文件，实测已确认：
     *     downloadChunk: task=7 返回 text/html（疑似广告/错误页），终止
     *   取链与下载必须使用同一份 Cookie。
     */
    fun cookieHeader(host: String): String {
        val bucket = cookieStore[host] ?: return ""
        return bucket.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 执行请求，命中 WAF 挑战时**自动算出 acw_sc__v2 并带 Cookie 重试**。
     *
     * ★ 为什么必须做：蓝奏云分享域名挂在阿里云 ESA 后面。触发风控时返回的
     *   不是数据，而是一段含 `var arg1='...'` 的混淆 JS 挑战页（HTTP 仍是 200）。
     *   浏览器会执行它并写入 acw_sc__v2 后自动重试；OkHttp 不执行 JS，
     *   于是一直卡在挑战页 —— 表现为「下载返回网页而非文件」「请刷新，重试0」。
     *
     * 处理：检测到 arg1 → 调用 [LanzouShareConstants.acwScV2] 计算 →
     * 写入 cookieStore（CookieJar 后续请求自动带上）→ 轮换 UA → 重试。
     *
     * ★ 轮换 UA 的原因：同一 UA 连续请求更像脚本，轮换可显著提高绕过率。
     *
     * @param buildRequest 每次重试都会重建 Request（UA 变化；OkHttp 的 Request 不可变）
     * @return 最终响应体；若始终命中挑战，返回最后一次的挑战页原文（交由上层解析报错）
     */
    private fun executeWithWafBypass(
        host: String,
        errorPrefix: String,
        buildRequest: (ua: String) -> Request
    ): String {
        var attempt = 0
        var lastBody = ""
        while (true) {
            val ua = if (attempt % 2 == 0) {
                LanzouShareConstants.USER_AGENT
            } else {
                LanzouShareConstants.DESKTOP_USER_AGENT
            }
            client.newCall(buildRequest(ua)).execute().use { response ->
                if (!response.isSuccessful) error("$errorPrefix：HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val arg1 = LanzouShareConstants.ARG1_REGEX.find(body)?.groupValues?.getOrNull(1)
                // 非挑战页：直接返回
                if (arg1 == null) return body
                val acw = LanzouShareConstants.acwScV2(arg1)
                // 算不出（arg1 异常）或已用尽重试：返回原文，让上层给出可读错误
                if (acw.isBlank() || attempt >= LanzouShareConstants.WAF_MAX_RETRIES) return body
                cookieStore.getOrPut(host) { mutableMapOf() }["acw_sc__v2"] = acw
                lastBody = body
                attempt++
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return lastBody
    }

    // ---------- 页面抓取 ----------

    /**
     * 拉取分享主页 HTML。
     * @param path 形如 `ilXoR3yvb92b`；文件夹内文件形如 `iwXYz413p17c?webpage=...`
     */
    suspend fun fetchSharePage(host: String, path: String): Result<String> = withContext(Dispatchers.IO) {
        seedCookie(host)
        val url = "https://$host/$path"
        runCatching {
            executeWithWafBypass(host, "打开分享页失败") { ua ->
                Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()
            }
        }
    }

    /** 拉取 tp 中间页 HTML（Referer 必须是对应的分享页，否则拿不到正常内容） */
    suspend fun fetchTpPage(host: String, tpPath: String, referer: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://$host/${tpPath.trimStart('/')}"
                executeWithWafBypass(host, "打开下载页失败") { ua ->
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", ua)
                        .header("Referer", referer)
                        .get()
                        .build()
                }
            }
        }

    // ---------- 文件夹列表 ----------

    /** filemoreajax.php 的返回态 */
    enum class ListStatus { OK, EMPTY, BAD_PWD, OTHER }

    data class FolderListResult(
        val status: ListStatus,
        val message: String,
        val items: List<JSONObject>
    )

    /**
     * 列文件夹内文件（自动翻页，页大小 [LanzouShareConstants.PAGE_SIZE]）。
     *
     * ★ zt 语义（来自抓包页面 JS）：
     *   1=成功 / 2=没有文件 / 3=密码错误 / 6=服务端给出提示文本（如已失效）
     */
    suspend fun listFolderFiles(
        data: LanzouShareSessionData,
        page: Int
    ): Result<FolderListResult> = withContext(Dispatchers.IO) {
        seedCookie(data.host)
        runCatching {
            // 每次重试都重建 body：OkHttp 的 Request/FormBody 不可变，且重试需换 UA
            fun buildBody() = FormBody.Builder()
                .add("lx", "2")
                .add("fid", data.folderFileId)
                .add("uid", data.uid)
                .add("puid", data.puid)
                .add("pg", page.toString())
                .add("rep", "0")
                .add("t", data.t)
                .add("k", data.k)
                .add("up", "1")
                .add("ls", "1")
                .add("pwd", data.pwd)
                .build()
            val raw = executeWithWafBypass(data.host, "获取文件列表失败") { ua ->
                Request.Builder()
                    .url("https://${data.host}/filemoreajax.php?file=${data.folderFileId}")
                    .header("User-Agent", ua)
                    .header("X-Requested-With", LanzouShareConstants.AJAX_HEADER)
                    .header("Referer", "https://${data.host}/${data.shareId}")
                    .post(buildBody())
                    .build()
            }
            // 重试耗尽后仍是挑战页：给出明确原因，而不是让 JSONObject 抛解析异常
            if (LanzouShareConstants.ARG1_REGEX.containsMatchIn(raw)) {
                error("被安全验证拦截（已重试 ${LanzouShareConstants.WAF_MAX_RETRIES} 次），请稍后再试")
            }
            val json = JSONObject(raw)
            val zt = json.optInt("zt", -1)
            val info = json.optString("info").orEmpty()
            val text = json.optJSONArray("text")
            val items = (0 until (text?.length() ?: 0)).mapNotNull { i -> text?.optJSONObject(i) }
            when (zt) {
                1 -> FolderListResult(ListStatus.OK, info, items)
                2 -> FolderListResult(ListStatus.EMPTY, "没有文件", emptyList())
                3 -> FolderListResult(ListStatus.BAD_PWD, info.ifBlank { "提取码错误" }, emptyList())
                else -> FolderListResult(ListStatus.OTHER, info.ifBlank { "获取失败（zt=$zt）" }, emptyList())
            }
        }
    }

    // ---------- 取直链 ----------

    data class DirectLink(val url: String, val filename: String)

    /**
     * 有密码文件：POST ajaxfile.php 换直链。
     * 响应形如 `{"zt":1,"dom":"https://u5189768.dmpdmp.com","url":"?VDIG...","inf":"文件名"}`
     * 直链 = dom + "/file/" + url
     */
    suspend fun requestDirectLink(
        host: String,
        fileId: String,
        sign: String,
        pwd: String,
        referer: String
    ): Result<DirectLink> = withContext(Dispatchers.IO) {
        seedCookie(host)
        runCatching {
            fun buildBody() = FormBody.Builder()
                .add("action", "downprocess")
                .add("sign", sign)
                .add("p", pwd)
                .add("kd", "1")
                .build()
            val raw = executeWithWafBypass(host, "获取直链失败") { ua ->
                Request.Builder()
                    .url("https://$host/ajaxfile.php?file=$fileId")
                    .header("User-Agent", ua)
                    .header("X-Requested-With", LanzouShareConstants.AJAX_HEADER)
                    .header("Referer", referer)
                    .post(buildBody())
                    .build()
            }
            if (LanzouShareConstants.ARG1_REGEX.containsMatchIn(raw)) {
                error("被安全验证拦截（已重试 ${LanzouShareConstants.WAF_MAX_RETRIES} 次），请稍后再试")
            }
            val json = JSONObject(raw)
            if (json.optInt("zt", 0) != 1) {
                error(json.optString("inf").takeIf { it.isNotBlank() } ?: "获取直链失败")
            }
            val dom = json.optString("dom")
            val url = json.optString("url")
            if (dom.isBlank() || url.isBlank()) error("响应缺少直链字段")
            DirectLink(
                url = dom + "/file/" + url + LanzouShareConstants.LANOSSO_SUFFIX,
                filename = json.optString("inf")
            )
        }
    }
}
