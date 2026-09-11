package com.yunx.app.data.repository

import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.LanzouShareApi
import com.yunx.app.data.network.LanzouShareConstants
import com.yunx.app.data.network.LanzouShareSessionData
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 蓝奏云分享解析仓库。
 *
 * ★ 与夸克/UC 最大的不同：**全程免登录**。
 *   抓包中 Cookie 只有 `codelen` / `m_adb1` / `m_ad3` 等匿名标记，
 *   没有任何登录凭证 —— 所以传入的网盘 cookie 直接忽略。
 *
 * ★ 不支持转存：转存走 api.ilanzou.com 且需要登录态，未抓包验证，
 *   故 [transferFile] 明确失败并说明原因，避免静默无反应。
 */
class LanzouResolveRepository : ShareResolveRepository {

    private val api = LanzouShareApi()

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val m = LanzouShareConstants.SHARE_URL_REGEX.find(link.trim())
            ?: return Result.failure(IllegalArgumentException("无法识别蓝奏云分享链接"))
        val host = m.groupValues[1]
        val shareId = m.groupValues[2]

        val html = api.fetchSharePage(host, shareId).getOrElse {
            return Result.failure(it)
        }
        if (html.isBlank()) return Result.failure(IllegalStateException("分享页为空"))

        val title = LanzouShareConstants.TITLE_REGEX.find(html)
            ?.groupValues?.get(1)?.trim().orEmpty()

        val folderFileId = LanzouShareConstants.FOLDER_FILE_ID_REGEX.find(html)
            ?.groupValues?.getOrNull(1).orEmpty()

        val effectivePwd = pwd?.trim().orEmpty()

        val data = if (folderFileId.isNotBlank()) {
            // 文件夹分享：把 filemoreajax.php 所需参数一并取出存进 session
            LanzouShareSessionData(
                host = host,
                shareId = shareId,
                isFolder = true,
                pwd = effectivePwd,
                folderFileId = folderFileId,
                t = LanzouShareConstants.T_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty(),
                k = LanzouShareConstants.K_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty(),
                puid = LanzouShareConstants.PUID_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty(),
                uid = LanzouShareConstants.UID_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
            )
        } else {
            LanzouShareSessionData(host = host, shareId = shareId, isFolder = false, pwd = effectivePwd)
        }

        return Result.success(ShareSession(shareId, data.encode(), title.ifBlank { "蓝奏云分享" }))
    }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> {
        val data = LanzouShareSessionData.decode(session.stoken)
            ?: return Result.failure(IllegalStateException("会话已失效，请重新解析"))

        // 单文件分享：列表里就是它自己
        if (!data.isFolder) {
            return Result.success(
                listOf(
                    ShareFile(
                        fid = data.shareId,
                        fname = session.title,
                        fsize = 0L,
                        isdir = false,
                        pdirFid = "",
                        fidToken = ""
                    )
                )
            )
        }

        val all = mutableListOf<ShareFile>()
        for (page in 1..LanzouShareConstants.MAX_PAGES) {
            val result = api.listFolderFiles(data, page).getOrElse {
                return Result.failure(it)
            }
            when (result.status) {
                LanzouShareApi.ListStatus.BAD_PWD ->
                    return Result.failure(IllegalStateException(result.message))
                LanzouShareApi.ListStatus.OTHER ->
                    return Result.failure(IllegalStateException(result.message))
                LanzouShareApi.ListStatus.EMPTY -> return Result.success(all)
                LanzouShareApi.ListStatus.OK -> {
                    result.items.forEach { o ->
                        // t == 1 是推广位（页面 JS 会给它加「推广」角标并直链外跳），不是真实文件
                        if (o.optInt("t", 0) == 1) return@forEach
                        all += ShareFile(
                            fid = o.optString("id"),
                            fname = o.optString("name_all").ifBlank { o.optString("name") },
                            fsize = LanzouApi.parseSize(o.optString("size")),
                            isdir = false,
                            pdirFid = "",
                            fidToken = "",
                            modifyTime = o.optString("time")
                        )
                    }
                    if (result.items.size < LanzouShareConstants.PAGE_SIZE) break
                }
            }
        }
        return Result.success(all)
    }

    override suspend fun ensureTempDir(cookie: String): Result<String> = Result.success("")

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = Result.failure(IllegalStateException("蓝奏云暂不支持转存，请直接下载"))

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(IllegalStateException("蓝奏云请使用 getShareDownloadLink"))

    /**
     * 取直链：分享页 → tp 中间页 →（有密码时）ajaxfile.php。
     *
     * ★ 每次都重新走完整流程而非复用：直链有时效性，
     *   且 tp 页的 `webtp` / `sign` 是一次性的，缓存反而会失效。
     */
    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> {
        val data = LanzouShareSessionData.decode(session.stoken)
            ?: return Result.failure(IllegalStateException("会话已失效，请重新解析"))

        // 单文件分享时 file.fid 就是 shareId；文件夹内文件时是 `iwXYz...?webpage=...`
        val path = file.fid.ifBlank { data.shareId }
        val sharePageUrl = "https://${data.host}/$path"

        val html = api.fetchSharePage(data.host, path).getOrElse {
            return Result.failure(it)
        }
        val tpPath = LanzouShareConstants.TP_LINK_REGEX.find(html)
            ?.groupValues?.getOrNull(1)
            ?.let { LanzouShareConstants.unescapeHtml(it) }
            ?: return Result.failure(IllegalStateException("未找到下载入口（链接可能已失效）"))

        val tpHtml = api.fetchTpPage(data.host, tpPath, sharePageUrl).getOrElse {
            return Result.failure(it)
        }

        // 无密码：tp 页 JS 直接给出了域名与 query
        val vkjxld = LanzouShareConstants.VKJXLD_REGEX.find(tpHtml)?.groupValues?.getOrNull(1)
        val hyggid = LanzouShareConstants.HYGGID_REGEX.find(tpHtml)?.groupValues?.getOrNull(1)
        if (!vkjxld.isNullOrBlank() && !hyggid.isNullOrBlank()) {
            return Result.success(
                DownloadLink(
                    fid = file.fid,
                    filename = file.fname,
                    downloadUrl = vkjxld + hyggid + LanzouShareConstants.LANOSSO_SUFFIX,
                    size = file.fsize
                )
            )
        }

        // 有密码：用 tp 页给的 sign 去 ajaxfile.php 换直链
        val sign = LanzouShareConstants.VIDKSEK_REGEX.find(tpHtml)?.groupValues?.getOrNull(1)
        val fileId = LanzouShareConstants.AJAXFILE_ID_REGEX.find(tpHtml)?.groupValues?.getOrNull(1)
        if (sign.isNullOrBlank() || fileId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("无法解析下载页（页面结构可能已变化）"))
        }
        if (data.pwd.isBlank()) {
            return Result.failure(IllegalStateException("该分享需要提取码，请填写后重试"))
        }

        val direct = api.requestDirectLink(
            host = data.host,
            fileId = fileId,
            sign = sign,
            pwd = data.pwd,
            referer = "https://${data.host}/${tpPath.trimStart('/')}"
        ).getOrElse { return Result.failure(it) }

        return Result.success(
            DownloadLink(
                fid = file.fid,
                filename = direct.filename.ifBlank { file.fname },
                downloadUrl = direct.url,
                size = file.fsize
            )
        )
    }
}
