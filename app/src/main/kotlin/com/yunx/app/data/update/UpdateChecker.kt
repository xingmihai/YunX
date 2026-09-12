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
import android.os.Build
import android.util.Base64
import com.yunx.app.BuildConfig
import com.yunx.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * GitHub Release 更新检测 —— **只走 edge 加速镜像的网页端点，不用 REST API**。
 *
 * 为什么不用 `api.github.com`：未认证请求只有 60 次/小时/IP，移动网络与公司/校园
 * 共享出口 IP 极易被同网段占满，更新检测会 403 并被误报为「检查更新失败」。
 * 而且 REST 与网页端点返回的 body 格式不同（Markdown vs HTML），
 * 双通道意味着两套解析逻辑，是 bug 温床。
 *
 * 统一走镜像站 https://edge.gh.xmhai.cn/github.com/<owner>/<repo>：
 * - `releases.atom` —— 最新 Release 的 tag、更新时间、HTML 形式的更新说明
 * - `releases/expanded_assets/<tag>` —— 该 tag 的附件列表（网页异步展开片段）
 * - `releases/download/<tag>/<file>.apk` —— 下载直链
 *
 * 注意：Atom 的 `<content>` 是 **HTML**（GitHub 已渲染），必须经
 * `htmlToMarkdown()` 转成 Markdown 后才能交给 MarkdownText 渲染。
 */
object UpdateChecker {

    private const val OWNER = "xingmihai"
    private const val REPO = "YunX"

    /** edge 加速镜像根地址：镜像站路径为「<前缀>/github.com/<owner>/<repo>」 */
    private const val EDGE_BASE = "https://edge.gh.xmhai.cn/github.com/$OWNER/$REPO"

    /** 官方地址：仅在**未配置**加速站密码时作为回退，保证不因缺配置而失效 */
    private const val GITHUB_BASE = "https://github.com/$OWNER/$REPO"

    /**
     * 加速站访问密码，来自 `BuildConfig.EDGE_PROXY_PASS`（编译期注入，不进仓库）。
     *
     * ★ 见 app/build.gradle.kts 中 edgeProxyPass 的说明：本地取自 local.properties，
     *   CI 取自仓库 Secrets。为空表示未配置，此时回退官方地址。
     */
    private val proxyPass: String get() = BuildConfig.EDGE_PROXY_PASS

    /** 实际使用的基址：配了密码走加速镜像，否则回退官方 GitHub */
    private val baseUrl: String get() = if (proxyPass.isBlank()) GITHUB_BASE else EDGE_BASE

    /** Release Atom 源 */
    private val releasesAtomUrl: String get() = "$baseUrl/releases.atom"

    /** 指定 tag 的附件列表（GitHub 网页异步展开 assets 用的片段） */
    private fun expandedAssetsUrl(tag: String) = "$baseUrl/releases/expanded_assets/$tag"

    /**
     * 加速站的 Basic Auth 头；未配置密码时返回 null（不发该头）。
     *
     * ★ 为什么用 Basic Auth 而不是 `?key=`：
     *   `?key=` 命中后服务端返回 302 + Set-Cookie，要求客户端保存并回传 Cookie。
     *   本项目 OkHttp 未配置 CookieJar（默认 NO_COOKIES），重定向后会丢掉凭证再次 401，
     *   对 APK 下载这类大文件尤其不可靠。Basic Auth 每次请求都带，无状态、最稳。
     *   用户名任意（服务端只校验冒号后的密码部分）。
     */
    private fun authHeader(): String? {
        val pass = proxyPass
        if (pass.isBlank()) return null
        return "Basic " + Base64.encodeToString("yunx:$pass".toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 下载 APK 时需要携带的请求头（供 DownloadManager.enqueue 使用）。
     * 走加速站时是 Authorization，未配置时为空 Map。
     */
    fun downloadAuthHeaders(): Map<String, String> =
        authHeader()?.let { mapOf("Authorization" to it) } ?: emptyMap()

    data class Asset(
        val name: String,
        val downloadUrl: String,
        /** 附件体积（字节）；从 expanded_assets 的「6.24 MB」文本解析，解析不到时为 null */
        val sizeBytes: Long? = null
    )

    /**
     * @param tagName git tag。**约定为 `v<versionCode>`**（如 `v2026091113`），
     *   这是 Atom 通道唯一能拿到真实 versionCode 的途径（Atom 不提供该字段）。
     *   用于版本比较与「忽略本次」去重。
     * @param displayName Release 名称，约定为 `v<versionName>`（如 `v2026.09.11`），用于展示。
     * @param versionCode 从 tag 解析出的 versionCode；tag 不符合约定时为 null。
     */
    data class Release(
        val tagName: String,
        val displayName: String = tagName,
        val versionCode: Long? = null,
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

    /** 当前应用 versionCode（API 28+ 用 longVersionCode，低版本回退已废弃的 versionCode） */
    fun currentVersionCode(context: Context): Long =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)

    /**
     * 判断远程 Release 是否比本地更新。
     *
     * ★ 优先比较 **versionCode**，而非 versionName：
     * - versionName 可能回退（如 1.3.1 → 1.0.0），回退会让已装旧版的用户收不到更新提示；
     * - versionName 可能重复（按构建日期生成时，同一天构建 versionName 恒为 yyyy.MM.dd），
     *   重复会导致「明明是新构建却判定无更新」。
     *   versionCode 单调递增，两种情况都能正确处理。
     *
     * 拿不到 versionCode（tag 不符合约定）时回退比较 versionName，保证旧版本仍可用。
     */
    fun isNewer(release: Release, context: Context): Boolean {
        val remote = release.versionCode
        if (remote != null) return remote > currentVersionCode(context)
        return compareVersions(release.tagName, currentVersion(context)) > 0
    }

    /**
     * 从 git tag 解析 versionCode：约定 tag 为 `v<versionCode>`。
     * 返回 null 表示 tag 不符合约定（如历史 tag `v1.3.1`），此时走 versionName 比较。
     */
    private fun extractVersionCode(tag: String): Long? =
        tag.trimStart('v', 'V').toLongOrNull()

    /**
     * 请求最新 Release；网络失败或仓库无 Release 时返回 null。
     *
     * @param context 保留参数以兼容调用点；当前无状态需要持久化
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchLatestRelease(context: Context? = null): Release? = withContext(Dispatchers.IO) {
        fetchViaAtom()
    }

    // ------------------------------------------------------------- Atom 通道

    /**
     * 通过 edge 镜像的网页端点获取最新 Release。
     * Atom 源提供 tag 与更新说明，附件直链需要再取 expanded_assets 片段。
     */
    private suspend fun fetchViaAtom(): Release? = runCatching {
        val atom = httpGet(releasesAtomUrl) ?: return@runCatching null
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
            // <title> 是 Release 名称（v2026.09.11），比 tag（v2026091113）可读，用于展示
            displayName = entry.substringAfter("<title>", "")
                .substringBefore("</title>")
                .trim()
                .ifBlank { tag },
            versionCode = extractVersionCode(tag),
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
        // 用 "/releases/tag/" 定位而非写死域名：镜像站可能把 href 改写成自己的域名，
        // 写死 github.com 会导致提取失败而静默回退到 title
        val fromLink = entry.substringAfter("/releases/tag/", "")
            .substringBefore("\"", "")
            .trim()
        if (fromLink.isNotBlank() && !fromLink.contains('/')) return fromLink

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
        val href = Regex("/$OWNER/$REPO/releases/download/[^\"'\\s]+\\.apk")
        val size = Regex("(\\d+(?:\\.\\d+)?)\\s*(KB|MB|GB)", RegexOption.IGNORE_CASE)

        // ★ 按 <li> 切块再逐块配对：整个页面有多个附件，全局找体积会张冠李戴
        //   （第一个附件可能匹配到第二个附件的体积）
        val items = html.split("<li", ignoreCase = true).drop(1)
        val assets = items.mapNotNull { item ->
            val path = href.find(item)?.value ?: return@mapNotNull null
            val name = path.substringAfterLast('/')
            val bytes = size.find(item)?.let { m ->
                val num = m.groupValues[1].toDoubleOrNull() ?: return@let null
                val unit = m.groupValues[2].uppercase()
                val factor = when (unit) {
                    "KB" -> 1024L
                    "MB" -> 1024L * 1024
                    "GB" -> 1024L * 1024 * 1024
                    else -> 1L
                }
                (num * factor).toLong()
            }
            Asset(
                name = name,
                downloadUrl = "$baseUrl/releases/download/$tag/$name",
                sizeBytes = bytes
            )
        }
        assets.ifEmpty {
            // 兜底：切块失败时退回全局匹配（此时拿不到体积）
            href.findAll(html).map { m ->
                val name = m.value.substringAfterLast('/')
                Asset(name, "$baseUrl/releases/download/$tag/$name")
            }.toList()
        }
    }.getOrDefault(emptyList())

    private fun httpGet(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "YunX")
            .apply { authHeader()?.let { header("Authorization", it) } }
            .get()
            .build()
        HttpClients.apiClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        }
    }.getOrNull()

    private fun String.stripTags(): String =
        replace(Regex("<[^>]+>"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    // ------------------------------------------------- Atom 的 HTML → Markdown

    private val BLOCK_CLOSE = Regex(
        "</?(p|div|h[1-6]|li|ul|ol|blockquote|pre|tr|table|section)[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val H_TAG = Regex("<h([1-6])[^>]*>(.*?)</h\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val LI_TAG = Regex("<li[^>]*>(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val QUOTE_BLOCK = Regex(
        "<blockquote[^>]*>(.*?)</blockquote>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val STRONG = Regex("<(strong|b)[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val EM = Regex("<(em|i)[^>]*>(.*?)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CODE_HTML = Regex("<code[^>]*>(.*?)</code>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val PRE_HTML = Regex("<pre[^>]*>(.*?)</pre>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ANCHOR = Regex("<a\\s[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val HR_HTML = Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE)
    /** `<br>` 后吃掉紧跟的空白/换行：源码中 `<br>` 后通常还有一个真实换行，
     *  只替换标签会得到 `\n\n` → 段落断开，列表项续行被拆散 */
    private val BR_HTML = Regex("<br\\s*/?>\\s*", RegexOption.IGNORE_CASE)

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
        // 5) 列表项
        s = LI_TAG.replace(s) { m -> "- ${m.groupValues[1].trim()}" }
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
