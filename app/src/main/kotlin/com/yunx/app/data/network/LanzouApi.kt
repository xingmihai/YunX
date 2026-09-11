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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 蓝奏云文件夹（task=47 返回） */
data class LanzouFolder(
    val folderId: String,
    val name: String,
    val description: String = ""
)

/** 蓝奏云文件（task=5 返回） */
data class LanzouFile(
    val id: String,
    val name: String,
    /** 字节数（由 "80.1 M" 这类文本换算，见 [parseSize]） */
    val sizeBytes: Long,
    val sizeText: String,
    val time: String,
    /** 图标类型后缀（apk / zip / 图片等），用于 UI 选图标 */
    val icon: String,
    val downs: Int
)

/**
 * 蓝奏云用户后台 API（全部逆向自网页端抓包，无官方文档）。
 *
 * 三个接口共用 `POST /doupload.php?uid=<uid>`，靠 `task` 区分：
 * - task=47 → 子文件夹列表
 * - task=5  → 文件列表
 *
 * 所有接口都必须带 `vei`，它由 [fetchVei] 从 mydisk.php 页面提取。
 */
class LanzouApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun referer(uid: String) = LanzouConstants.mydiskUrl(uid)

    private fun request(cookie: String, uid: String, params: Map<String, String>): Request {
        val form = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        return Request.Builder()
            .url(LanzouConstants.douploadUrl(uid))
            .header("Cookie", cookie)
            .header("User-Agent", LanzouConstants.USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", LanzouConstants.BASE)
            .header("Referer", referer(uid))
            .post(form)
            .build()
    }

    /**
     * 从用户后台主页提取 vei。
     *
     * vei 是页面级令牌（与 folder_id / task 无关，但跨会话变化），
     * 写死会失效，必须每次进入云盘时从页面现取。
     */
    suspend fun fetchVei(cookie: String, uid: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LanzouConstants.mydiskUrl(uid))
                .header("Cookie", cookie)
                .header("User-Agent", LanzouConstants.USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("获取 vei 失败：HTTP ${response.code}")
                val html = response.body?.string().orEmpty()
                LanzouConstants.VEI_REGEX.find(html)?.groupValues?.get(1)
                    ?: error("未能从页面提取 vei（可能未登录或页面结构已变）")
            }
        }
    }

    /** task=47：列出 [folderId] 下的子文件夹 */
    suspend fun listFolders(
        cookie: String,
        uid: String,
        folderId: String,
        vei: String
    ): Result<List<LanzouFolder>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = callText(cookie, uid, mapOf(
                "task" to LanzouConstants.TASK_FOLDER_LIST.toString(),
                "folder_id" to folderId,
                "vei" to vei
            ))
            val text = JSONObject(body).optJSONArray("text") ?: return@runCatching emptyList()
            (0 until text.length()).mapNotNull { i ->
                val o = text.optJSONObject(i) ?: return@mapNotNull null
                LanzouFolder(
                    folderId = o.optString("folderid"),
                    name = o.optString("name"),
                    description = o.optString("folder_des")
                )
            }
        }
    }

    /** task=5：列出 [folderId] 下的文件（[pg] 为分页页码，从 1 开始） */
    suspend fun listFiles(
        cookie: String,
        uid: String,
        folderId: String,
        vei: String,
        pg: Int = 1
    ): Result<List<LanzouFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val body = callText(cookie, uid, mapOf(
                "task" to LanzouConstants.TASK_FILE_LIST.toString(),
                "folder_id" to folderId,
                "pg" to pg.toString(),
                "vei" to vei
            ))
            val text = JSONObject(body).optJSONArray("text") ?: return@runCatching emptyList()
            (0 until text.length()).mapNotNull { i ->
                val o = text.optJSONObject(i) ?: return@mapNotNull null
                val sizeText = o.optString("size")
                LanzouFile(
                    id = o.optString("id"),
                    name = o.optString("name_all").ifBlank { o.optString("name") },
                    sizeBytes = parseSize(sizeText),
                    sizeText = sizeText,
                    time = o.optString("time"),
                    icon = o.optString("icon"),
                    downs = o.optInt("downs")
                )
            }
        }
    }

    private fun callText(cookie: String, uid: String, params: Map<String, String>): String {
        client.newCall(request(cookie, uid, params)).execute().use { response ->
            if (!response.isSuccessful) error("请求失败：HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        /**
         * 把 "80.1 M" 这类文本换算成字节。
         *
         * ★ 进制为 **1024**（不是 1000）：实测同一目录下 3 个文件
         *   80.1+79.8+79.7 = 239.6 M，而接口返回的 folder_size = 251233543，
         *   239.6 × 1048576 ≈ 251238808，误差 0.002%，吻合；若按 1000 则差 4%。
         */
        fun parseSize(text: String?): Long {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty()) return 0L
            val parts = raw.split(Regex("\s+"))
            val num = parts.firstOrNull()?.toDoubleOrNull() ?: return 0L
            val unit = parts.getOrNull(1)?.uppercase().orEmpty()
            val multiplier = when {
                unit.startsWith("K") -> 1024L
                unit.startsWith("M") -> 1024L * 1024
                unit.startsWith("G") -> 1024L * 1024 * 1024
                unit.startsWith("T") -> 1024L * 1024 * 1024 * 1024
                else -> 1L
            }
            return (num * multiplier).toLong()
        }
    }
}
