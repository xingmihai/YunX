package com.yunx.app.data.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 极简 WebDAV 客户端（仅实现备份/恢复需要的 PUT / GET / MKCOL）。
 *
 * 用 OkHttp 手写而不引入三方 WebDAV 库的原因：
 * 需求只有「上传一个文件、下载一个文件」，引库带来的体积与维护成本不划算，
 * 且 OkHttp 已在依赖里（更新检测、下载均在使用），无需新增依赖。
 *
 * ★ 所有方法都抛异常，由调用方决定如何提示 —— 不在这里吞掉错误，
 *   否则 UI 无法区分「密码错误」「地址不可达」「文件不存在」这几种情况。
 */
class WebDavClient(
    private val baseUrl: String,
    private val user: String,
    private val password: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val authHeader: String
        get() = Credentials.basic(user, password)

    /** 拼接完整 URL：base + 文件名（自动补 /，并对文件名编码） */
    private fun urlFor(fileName: String): String {
        val base = baseUrl.trimEnd('/')
        val name = fileName.trimStart('/')
        return "$base/" + name.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
    }

    /**
     * 上传（不存在则创建，存在则覆盖）。
     *
     * 使用 PUT：WebDAV 的标准做法，语义明确，不需要先 DELETE 再 PUT。
     * Content-Type 用 text/plain —— 内容是 Base64 密文，本来就是文本。
     */
    suspend fun upload(fileName: String, content: String) = withContext(Dispatchers.IO) {
        val body = content.toRequestBody("text/plain; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(urlFor(fileName))
            .header("Authorization", authHeader)
            .put(body)
            .build()
        client.newCall(request).execute().use { response ->
            // 409 = 父目录不存在；部分服务器要求先建目录，这里自动补一次
            if (response.code == 409) {
                mkcol(baseUrl.trimEnd('/'))
                val retry = Request.Builder()
                    .url(urlFor(fileName))
                    .header("Authorization", authHeader)
                    .put(content.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(retry).execute().use { r2 ->
                    if (!r2.isSuccessful) {
                        throw WebDavException("上传失败：HTTP ${r2.code}")
                    }
                }
                return@withContext
            }
            if (!response.isSuccessful) {
                throw WebDavException("上传失败：HTTP ${response.code}")
            }
        }
    }

    /**
     * 下载文件内容；文件不存在（404）返回 null 而不抛异常 ——
     * 「还没备份过」是正常状态，不该让用户看到错误。
     */
    suspend fun download(fileName: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlFor(fileName))
            .header("Authorization", authHeader)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> return@withContext null
                401, 403 -> throw WebDavException("认证失败：请检查 WebDAV 用户名与密码")
            }
            if (!response.isSuccessful) {
                throw WebDavException("下载失败：HTTP ${response.code}")
            }
            response.body?.string()
                ?: throw WebDavException("下载失败：响应体为空")
        }
    }

    /** 创建目录（已存在时多数服务器返回 405，视为成功） */
    private suspend fun mkcol(dirUrl: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(dirUrl.trimEnd('/') + "/")
            .header("Authorization", authHeader)
            .method("MKCOL", null)
            .build()
        runCatching {
            client.newCall(request).execute().use { /* 405 已存在，忽略 */ }
        }
    }

    /**
     * 连通性测试：列目录。配置页保存前调用，让用户立刻知道配置是否正确。
     * 少数服务器不支持 PROPFIND，此时退化为按 401/404 区分认证与路径问题。
     */
    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/")
                .header("Authorization", authHeader)
                .method("PROPFIND", null)
                .header("Depth", "0")
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401, 403 -> throw WebDavException("认证失败：请检查用户名与密码")
                    in 200..299 -> Unit
                    405 -> Unit // 不支持 PROPFIND，但地址可达
                    else -> throw WebDavException("无法访问：HTTP ${response.code}")
                }
            }
        }
    }
}

/** WebDAV 操作失败；message 直接面向用户展示 */
class WebDavException(message: String) : Exception(message)
