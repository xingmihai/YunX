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

package com.yunx.app.data.update

import android.content.Context
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * GitHub Release 更新检测。
 *
 * 三级取值策略（★ 核心：未认证请求只有 60 次/小时/IP，共享出口 IP 极易被打满）：
 * 1. REST API `api.github.com/repos/xingmihai/YunX/releases/latest`，带 `If-None-Match` 条件请求
 *    —— 命中 304 时**不计入限流额度**，日常检查基本零消耗；
 * 2. 被限流（403 + X-RateLimit-Remaining=0）时读 `X-RateLimit-Reset` 进入冷却期，
 *    冷却期内不再触碰 API，避免每次开 App 都浪费请求并误报「检查更新失败」；
 * 3. 冷却期或 API 不可用时，走 `github.com` 网页端点（releases.atom / expanded_assets），
 *    该域名不属于 REST API 限流桶，可正常取到最新 tag 与 APK 直链。
 */
object UpdateChecker {

    private const val OWNER = "xingmihai"
    private const val REPO = "YunX"

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 网页端点：Release Atom 源（github.com，不受 REST API 限流约束） */
    private const val RELEASES_ATOM_URL =
        "https://github.com/$OWNER/$REPO/releases.atom"

    /** 网页端点：指定 tag 的附件列表（GitHub 网页异步展开 assets 用的片段） */
    private fun expandedAssetsUrl(tag: String) =
        "https://github.com/$OWNER/$REPO/releases/expanded_assets/$tag"

    /** GitHub 下载加速镜像站前缀（国内直连 GitHub 慢/失败时的兜底下载通道） */
    const val MIRROR_PREFIX = "https://cdn.gh-proxy.org/"

    /** 把 GitHub release 直链转成镜像站直链：https://cdn.gh-proxy.org/<原直链> */
    fun mirrorUrl(url: String): String = MIRROR_PREFIX + url

    private const val PREFS = "yunx_update_check"
    private const val KEY_ETAG = "etag"
    private const val KEY_CACHE = "cached_json"
    private const val KEY_RESET_AT = "rate_limit_reset_at"

    /** API 限流冷却截止时间戳（毫秒）；在此时间前不再请求 REST API */
    @Volatile
    private var rateLimitResetAt: Long = 0L

    /** context 不可用时的进程内兜底缓存（单次会话内有效） */
    @Volatile
    private var memEtag: String? = null

    @Volatile
    private var memCache: String? = null

    @Volatile
    private var stateRestored = false

    data class Asset(
        val name: String,
        val downloadUrl: String,
        /** 附件体积（字节）；网页兜底通道拿不到时为 null */
        val sizeBytes: Long? = null
    )

    data class Release(
        val tagName: String,
        val body: String,
        val assets: List<Asset>,
        val publishedAt: String
    )

    /**
     * 从 Release 附件中挑选应当安装的 APK。
     *
     * ★ 不能用 `firstOrNull { it.name.endsWith(".apk") }`：GitHub 按上传顺序返回附件，
     *   本仓库 workflow 先上传 debug 后上传 release，直接取首个会下到 **debug 版**
     *   （体积大、带调试标志、非发布签名），用户点「下载更新」却装到调试包。
     *
     * 选取策略：先排除 debug/unsigned/unaligned，再优先 release/signed，最后回退首个。
     */
    fun preferredApk(assets: List<Asset>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk", true) }
        if (apks.isEmpty()) return null
        val pool = apks.filterNot { a ->
            val n = a.name.lowercase()
            n.contains("debug") || n.contains("unsigned") || n.contains("unaligned")
        }.ifEmpty { apks }
        return pool.firstOrNull { a ->
            val n = a.name.lowercase()
            n.contains("release") || n.contains("signed")
        } ?: pool.first()
    }

    /** 比较两个版本号：v1 > v2 返回正数，v1 < v2 返回负数，相等返回 0 */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = normalizeVersion(v1).split(".")
        val parts2 = normalizeVersion(v2).split(".")
        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val num1 = parts1.getOrNull(i)?.toIntOrNull() ?: 0
            val num2 = parts2.getOrNull(i)?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }

    /** 当前应用版本号（packageManager.versionName） */
    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"

    /**
     * 请求 GitHub 最新 Release；网络失败 / 仓库无 Release（404）返回 null。
     *
     * @param context 传入后可跨进程复用 ETag 缓存与限流冷却时间（不传则仅内存生效）
     */
    suspend fun fetchLatestRelease(context: Context? = null): Release? = withContext(Dispatchers.IO) {
        restoreState(context)
        // 冷却期内不碰 API，直接走网页端点
        if (System.currentTimeMillis() < rateLimitResetAt) {
            return@withContext fetchViaWeb()
        }
        fetchViaApi(context) ?: fetchViaWeb()
    }

    // ------------------------------------------------------------------ REST API

    private suspend fun fetchViaApi(context: Context?): Release? = runCatching {
        val p = prefs(context)
        val etag = p?.getString(KEY_ETAG, null) ?: memEtag
        val builder = Request.Builder()
            .url(RELEASES_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YunX")
        // 条件请求：服务端内容未变时返回 304，且 304 不计入限流额度
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)

        val client = HttpClients.apiClient()
        client.newCall(builder.get().build()).execute().use { resp ->
            val remaining = resp.header("X-RateLimit-Remaining")?.toLongOrNull()
            val resetSec = resp.header("X-RateLimit-Reset")?.toLongOrNull()

            when (resp.code) {
                304 -> {
                    // 内容未变化：直接复用上次缓存的完整响应
                    val cached = p?.getString(KEY_CACHE, null) ?: memCache
                    return@runCatching cached?.let { parseRelease(it) }
                }
                403 -> {
                    // 额度耗尽（未认证 60 次/小时，移动网络/公司出口 IP 常被同网段占满）：
                    // 记录重置时刻，冷却期内改走网页端点
                    if (remaining == 0L && resetSec != null) {
                        rateLimitResetAt = resetSec * 1000L + 5_000L
                        p?.edit()?.putLong(KEY_RESET_AT, rateLimitResetAt)?.apply()
                    }
                    return@runCatching null
                }
                404 -> return@runCatching null // 仓库尚未发布任何 Release
            }
            if (!resp.isSuccessful) return@runCatching null

            val body = resp.body?.string()
            if (body.isNullOrBlank()) return@runCatching null
            val newEtag = resp.header("ETag")
            memEtag = newEtag
            memCache = body
            p?.edit()?.let { editor ->
                if (newEtag != null) editor.putString(KEY_ETAG, newEtag) else editor.remove(KEY_ETAG)
                editor.putString(KEY_CACHE, body)
                editor.remove(KEY_RESET_AT)
            }?.apply()
            parseRelease(body)
        }
    }.getOrNull()

    private fun parseRelease(json: String): Release? = runCatching {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name")
        if (tag.isBlank()) return@runCatching null
        val assets = buildList {
            obj.optJSONArray("assets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    add(
                        Asset(
                            name = a.optString("name"),
                            downloadUrl = a.optString("browser_download_url"),
                            sizeBytes = a.optLong("size", -1L).takeIf { it > 0 }
                        )
                    )
                }
            }
        }
        Release(
            tagName = tag,
            body = obj.optString("body"),
            assets = assets,
            publishedAt = obj.optString("published_at")
        )
    }.getOrNull()

    // ------------------------------------------------------- 网页端点兜底（不限流）

    /**
     * 通过 github.com 网页端点获取最新 Release。
     * Atom 源提供 tag 与更新说明，附件直链需要再取 expanded_assets 片段。
     */
    private suspend fun fetchViaWeb(): Release? = runCatching {
        val atom = httpGet(RELEASES_ATOM_URL) ?: return@runCatching null
        // 只取第一个 entry（Atom 按发布时间倒序，首个即最新 Release）；
        // 多个 entry 时必须截断，否则后续标签解析会跨 entry 取到错误内容
        val entry = atom.substringAfter("<entry>", "")
            .substringBefore("</entry>", "")
            .ifBlank { return@runCatching null }
        // ★ <title> 是 Release **名称**（如 "YunX v1.3.0"），不是 tag，绝不能当 tag 用：
        //   拿它拼 expanded_assets 会 404，且 compareVersions 会解析出 0 → 误判"无新版本"。
        val tag = extractTag(entry) ?: return@runCatching null
        val updated = entry.substringAfter("<updated>", "").substringBefore("</updated>").trim()
        val content = entry.substringAfter("<content", "")
            .substringAfter(">", "")
            .substringBefore("</content>")
        Release(
            tagName = tag,
            // ★ Atom 的 <content> 是 **HTML**（GitHub 渲染后的产物），不是 Markdown 源码。
            //   若只做 stripTags 会得到纯文本，结构（标题/列表/引用）全丢，
            //   弹窗里再拿它当 Markdown 解析就等于没渲染。必须先转成 Markdown。
            body = unescapeHtml(content).htmlToMarkdown(),
            assets = fetchAssetsFromWeb(tag),
            publishedAt = updated
        )
    }.getOrNull()

    /**
     * 从 Atom entry 提取真正的 git tag，按可靠性降序取第一个非空结果：
     * 1. `<link rel="alternate" href=".../releases/tag/<tag>>"` —— 权威来源
     * 2. `<id>tag:github.com,2008:Repository/<id>/<tag></id>` —— 末段即 tag
     * 3. 从 `<title>` 里正则抽取形如 `v1.3.0` 的版本号 —— Release 名称带前缀时的兜底
     */
    private fun extractTag(entry: String): String? {
        val fromLink = entry.substringAfter("""href="https://github.com/$OWNER/$REPO/releases/tag/""", "")
            .substringBefore("\"", "")
            .trim()
        if (fromLink.isNotBlank()) return fromLink

        val fromId = entry.substringAfter("<id>", "")
            .substringBefore("</id>", "")
            .substringAfterLast('/', "")
            .trim()
        if (fromId.isNotBlank()) return fromId

        val title = entry.substringAfter("<title>", "").substringBefore("</title>").trim()
        return VERSION_IN_TEXT.find(title)?.value
    }

    /** 文本中的版本号（带 v 前缀），用于从 Release 名称中兜底提取 */
    private val VERSION_IN_TEXT = Regex("""v\d+(?:\.\d+)*""")

    /**
     * 归一化版本号：剥离 v 前缀、Release 名称前缀与后缀说明。
     * 例如 "YunX v1.3.0" / "v1.3.0（预览）" → "1.3.0"，
     * 避免非数字前缀导致 toIntOrNull() 变 0、把新版本误判为旧版本。
     */
    fun normalizeVersion(raw: String): String {
        val s = raw.trim()
        VERSION_IN_TEXT.find(s)?.value?.let { return it.trimStart('v') }
        return s.trimStart('v', 'V').takeWhile { it.isDigit() || it == '.' }.trim('.')
    }

    /** 从网页片段解析 APK 直链：/xingmihai/YunX/releases/download/<tag>/<file>.apk */
    private suspend fun fetchAssetsFromWeb(tag: String): List<Asset> = runCatching {
        val html = httpGet(expandedAssetsUrl(tag)) ?: return@runCatching emptyList()
        // 主匹配：完整 download 路径（带引号的 href）
        val regex = Regex("href=\"/$OWNER/$REPO/releases/download/[^\"]+\\.apk\"")
        val found = regex.findAll(html).mapNotNull { m ->
            val path = m.value.substringAfter("href=\"").substringBefore("\"")
            if (path.isBlank()) null else Asset(path.substringAfterLast('/'), "https://github.com$path")
        }.toList()
        if (found.isNotEmpty()) return@runCatching found
        // 兜底：属性顺序/引号风格变化时，直接匹配 download 路径本身
        Regex("/$OWNER/$REPO/releases/download/[^\"'\\s]+\\.apk").findAll(html).map { m ->
            val path = m.value
            Asset(path.substringAfterLast('/'), "https://github.com$path")
        }.toList()
    }.getOrDefault(emptyList())

    private fun httpGet(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "YunX")
            .get()
            .build()
        HttpClients.apiClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    // ------------------------------------------------------------------ 状态持久化

    private fun prefs(context: Context?) =
        context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun restoreState(context: Context?) {
        if (stateRestored) return
        val p = prefs(context) ?: return // context 为空时暂不恢复，下次带 context 调用再试
        stateRestored = true
        rateLimitResetAt = p.getLong(KEY_RESET_AT, 0L)
        memEtag = p.getString(KEY_ETAG, null)
        memCache = p.getString(KEY_CACHE, null)
    }

    private fun String.stripTags(): String =
        replace(Regex("<[^>]+>"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    // ------------------------------------------------- Atom 的 HTML → Markdown

    private val BLOCK_CLOSE = Regex(
        "</?(p|div|h[1-6]|li|ul|ol|blockquote|pre|tr|table|section)[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val H_TAG = Regex("<h([1-6])[^>]*>(.*?)</h\\1>", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
    private val LI_TAG = Regex("<li[^>]*>(.*?)</li>", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
    private val QUOTE_BLOCK = Regex(
        "<blockquote[^>]*>(.*?)</blockquote>",
        RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE
    )
    private val STRONG = Regex("<(strong|b)[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
    private val EM = Regex("<(em|i)[^>]*>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
    private val CODE_HTML = Regex("<code[^>]*>(.*?)</code>", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
    private val PRE_HTML = Regex("<pre[^>]*>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
    private val ANCHOR = Regex("<a\\s[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE)
    private val HR_HTML = Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE)
    private val BR_HTML = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /**
     * 把 Atom `<content>` 里的 HTML 转成 Markdown，使两个数据源（REST API 的 Markdown、
     * Atom 的 HTML）在弹窗侧能用同一个 MarkdownText 渲染。
     *
     * 顺序有讲究：先抽出 `<pre>` 围栏整段保留（内部不做替换），再处理标题/列表/强调，
     * 最后才去剩余标签 —— 反过来会把代码块里的 `<...>` 也吃掉。
     */
    private fun String.htmlToMarkdown(): String {
        var s = this

        // 1) 代码块整段抽成 ``` 围栏（内部内容原样保留）
        s = PRE_HTML.replace(s) { m ->
            val inner = m.groupValues[1].replace(Regex("<[^>]+>"), "")
            "\n```\n${inner.trim()}\n```\n"
        }
        // 2) 引用块整段处理：内部先去掉块级标签，再逐行加 "> " 前缀。
        //    若只在 <blockquote> 处插入 "> "，其内部的 <p> 会先换行 → 引用内容跑到块外
        s = QUOTE_BLOCK.replace(s) { m ->
            val inner = m.groupValues[1]
                .replace(Regex("</?(p|div|br\\s*/?)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            val lines = inner.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
            "\n" + lines.joinToString("\n") { "> $it" } + "\n"
        }
        // 3) 分隔线 / 换行
        s = HR_HTML.replace(s, "\n---\n")
        s = BR_HTML.replace(s, "\n")
        // 4) 标题
        s = H_TAG.replace(s) { m ->
            "\n${"#".repeat(m.groupValues[1].toIntOrNull() ?: 2)} ${m.groupValues[2].trim()}\n"
        }
        // 5) 列表项（去空行，保持条目紧凑）
        s = LI_TAG.replace(s) { m -> "- ${m.groupValues[1].trim()}" }
        s = s.replace(Regex("(?m)^\\s*$"), "") // 列表项之间不留空行
        // 6) 强调与行内代码
        s = STRONG.replace(s) { m -> "**${m.groupValues[2].trim()}**" }
        s = EM.replace(s) { m -> "*${m.groupValues[2].trim()}*" }
        s = CODE_HTML.replace(s) { m -> "`${m.groupValues[1].trim()}`" }
        // 7) 链接：[文本](url)，无文本时只留 url
        s = ANCHOR.replace(s) { m ->
            val text = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val url = m.groupValues[1]
            if (text.isBlank()) url else "[$text]($url)"
        }
        // 8) 块级标签收尾处补换行，保证分段
        s = BLOCK_CLOSE.replace(s, "\n")
        // 9) 剩余行内标签一并去掉
        s = s.replace(Regex("<[^>]+>"), "")
        // 10) 归一化空行
        return s.lineSequence()
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
