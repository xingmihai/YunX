package com.yunx.app.data.network

/**
 * 蓝奏云**分享链接解析**常量（与 [LanzouConstants] 的「用户云盘」是两套体系）。
 *
 * ★ 全部逆向自网页端抓包，无官方 API。与云盘部分的关键差异：
 *   1. 域名不同：分享页是 `lanzou*.com`（如 wwboz.lanzouw.com），
 *      云盘后台是 `pc.woozooo.com`。且分享域名会漂移，故**不硬编码域名** ——
 *      直接用分享链接里自带的 host，天然抗漂移。
 *   2. UA 不同：分享页是移动端页面，用移动 UA；云盘用桌面 UA。
 *      两者不可混用（见 [LanzouConstants.USER_AGENT] 的注释）。
 *   3. **解析免登录**：抓包中的 Cookie 只有 `codelen` / `m_adb1` / `m_ad3`
 *      等匿名标记，没有任何登录凭证。所以不需要网盘登录态。
 *
 * ★ 直链取得有两条路径（取决于分享是否设了提取码）：
 *   - 无密码：tp 页面 JS 里直接给出 `vkjxld` + `hyggid`，拼起来即直链；
 *   - 有密码：tp 页面 JS 里给 `vidksek`（作为 sign），POST `ajaxfile.php`
 *     带 `p=<密码>`，响应返回 `dom` + `url`，拼成 `dom + "/file/" + url`。
 */
object LanzouShareConstants {

    /**
     * 移动 UA（与抓包一致）。
     *
     * ★ 必须与后续所有请求保持一致：分享页是移动端页面，
     *   用桌面 UA 可能拿不到 `vkjxld` / `hyggid` 这段 JS。
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16; PLQ110 Build/BP2A.250605.015) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.7922.199 Mobile Safari/537.36"

    /** 发起会话时的初始 Cookie（抓包首次请求即带 codelen=1） */
    const val INITIAL_COOKIE = "codelen=1"

    /** ajax 请求标识（filemoreajax.php / ajaxfile.php 都需要） */
    const val AJAX_HEADER = "XMLHttpRequest"

    /** 文件列表单页条数：抓包 JS 里 `if(data.length<50)` 隐藏「显示更多」 */
    const val PAGE_SIZE = 50

    /** 翻页上限兜底 */
    const val MAX_PAGES = 100

    /**
     * 直链后缀（防 DNS 劫持标记）。
     *
     * ★ 这是一个**未完全确认**的点：tp 页面 JS 为 `vkjxld + hyggid + lanosso`，
     *   而 `lanosso` 在 `kdns.js` 加载失败时是 `&lanosso2`。App 不加载该 JS，
     *   按 JS 逻辑应当拼上 `&lanosso2`。
     *   但抓包未覆盖到**实际下载请求**，无法确认带上是否会被拒。
     *   故默认留空（多数第三方实现亦不加），若下载失败改此常量即可。
     */
    const val LANOSSO_SUFFIX = ""

    // ---------- 分享页（如 /ilXoR3yvb92b）----------

    /** 分享页标题：`<title>xxx - 蓝奏云网盘</title>`（文件夹页无后缀） */
    val TITLE_REGEX = Regex("""<title>(.*?)(?:\s*-\s*蓝奏云网盘)?\s*</title>""")

    /** tp 中间页链接：`<a href="/tp/xxx?webtp=yyy" id="downurl">` */
    val TP_LINK_REGEX = Regex("""<a\s+href="(/tp/[^"]+)"\s+id="downurl"""")

    /** 文件夹分享标志 + 文件 id：`filemoreajax.php?file=13818484` */
    val FOLDER_FILE_ID_REGEX = Regex("""filemoreajax\.php\?file=(\d+)""")

    /** 文件夹是否需要密码（受保护时页面含 `<div id="pwdload">`） */
    val FOLDER_PWD_FLAG_REGEX = Regex("""<div\s+id="pwdload"""")

    // ---------- tp 中间页 ----------

    /** 无密码：`var vkjxld = 'https://u5189768.dmpdmp.com/file/'` */
    val VKJXLD_REGEX = Regex("""var\s+vkjxld\s*=\s*'([^']+)'""")

    /** 无密码：`var hyggid = '?VTNWaAk4...'`（直链 query 部分） */
    val HYGGID_REGEX = Regex("""var\s+hyggid\s*=\s*'([^']+)'""")

    /** 有密码：`var vidksek = 'AWdUal5v...'`（作为 ajaxfile.php 的 sign） */
    val VIDKSEK_REGEX = Regex("""var\s+vidksek\s*=\s*'([^']+)'""")

    /** 有密码：`url : '/ajaxfile.php?file=302136179'` */
    val AJAXFILE_ID_REGEX = Regex("""ajaxfile\.php\?file=(\d+)""")

    // ---------- 文件夹分享页（filemoreajax.php 所需参数）----------

    /** `var ib06pz = '1789152735'` → t（服务端下发的时间戳，有有效期） */
    val T_REGEX = Regex("""var\s+ib06pz\s*=\s*'([^']+)'""")

    /** `var _gyirs = '670e0fd4fa378cc5d48c25c7d70386d9'` → k */
    val K_REGEX = Regex("""var\s+_gyirs\s*=\s*'([^']+)'""")

    /** `'puid':'BzJXMQBl...'` */
    val PUID_REGEX = Regex("""['"]?puid['"]?\s*:\s*'([^']+)'""")

    /**
     * `'uid':'5189768'`
     *
     * ★ 必须用 `(?<![\w])` 排除 `puid`：**`puid` 里也含 `uid`**，
     *   且其后同样紧跟 `':`，少了边界就会把 puid 的值误当成 uid
     *   （两者格式不同：uid 是纯数字、puid 是 base64，误匹配后
     *   filemoreajax.php 会因 uid 非法直接失败）。
     */
    val UID_REGEX = Regex("""['"]?(?<![\w])uid['"]?\s*:\s*'(\d+)'""")

    /**
     * 分享链接识别：`https://<host>/<id>`
     *
     * ★ host 用 `lanzou` 匹配而不硬编码，覆盖 lanzoux/lanzouw/lanzoui 及其子域漂移。
     * ★ 末尾用 `$` 限制 id 后不再有路径段，避免误匹配
     *   `api.ilanzou.com/unproved/pd/url?id=...`（转存接口，不是分享页）。
     */
    val SHARE_URL_REGEX = Regex("""https?://([\w.-]*lanzou[\w.-]*)/([A-Za-z0-9]+)(?:[?#][^\s]*)?$""", RegexOption.IGNORE_CASE)

    /** HTML 实体反转义（tp 链接里可能出现 `&amp;`） */
    fun unescapeHtml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}
