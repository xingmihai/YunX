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

package com.yunx.app.data.repository

import android.webkit.CookieManager
import com.yunx.app.data.db.UCAccountDao
import com.yunx.app.data.db.UCAccountEntity
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.UCConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UC 账号数据仓库：Room 持久化 + 网络验证 + __puus 会话刷新（与夸克同源，修复取链/直链过期失败）。
 * __puus 约 3 小时过期，是取链接口（/file/download 等）必须携带的有效会话字段；
 * 取链前惰性刷新 + 响应 Set-Cookie 自动回写双保险。
 */
class UCAccountRepository(
    private val dao: UCAccountDao,
    private val api: UCApi
) {

    /** 上次主动刷新 __puus 的时间戳（进程内；跨进程重启后首次取链会因间隔超时触发刷新） */
    private var lastRefreshTs = 0L

    /** cookieSink 落库用独立作用域（非 UI 线程，避免阻塞 API 调用链） */
    private val sinkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 每次 API 响应若带 Set-Cookie（__puus/__pus），自动合并并落库，保持会话始终新鲜
        api.cookieSink = { merged ->
            sinkScope.launch {
                dao.getAccount()?.let { acc ->
                    if (acc.cookie != merged) {
                        // 仅更新凭证，不切换生效账号（多账号下这里若用 insertAsActive，
                        // 会把“刷新 cookie”变成“切换账号”）
                        dao.upsert(acc.copy(cookie = merged, updatedAt = System.currentTimeMillis()))
                    }
                }
            }
        }
    }

    fun observeAccount(): Flow<UCAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): UCAccountEntity? = dao.getAccount()
    /** 本平台全部已保存账号（最近更新的在前），用于账号切换列表 */
    fun observeAccounts(): Flow<List<UCAccountEntity>> = dao.observeAccounts()

    /** 切换生效账号：此后该平台的 API 请求都使用它的凭证 */
    suspend fun switchAccount(id: String) = dao.setActive(id)

    /** 删除指定账号；若删掉的正好是生效账号，自动激活剩余最近的一个 */
    suspend fun removeAccount(id: String) = dao.deleteAndEnsureActive(id)

    /**
     * 返回「保证 __puus 未过期」的 Cookie（取链/下载前调用）：
     * - 距上次刷新超过 PUUS_REFRESH_INTERVAL_MS 时，先 refreshSession 再落库；
     * - 刷新失败则回退返回当前 Cookie（不让取链/下载直接崩）。
     */
    suspend fun getFreshCookie(): String? {
        val acc = dao.getAccount() ?: return null
        val need = System.currentTimeMillis() - lastRefreshTs > UCConstants.PUUS_REFRESH_INTERVAL_MS
        if (!need) return acc.cookie
        val refreshed = api.refreshSession(acc.cookie)
        return if (refreshed != null) {
            dao.upsert(acc.copy(cookie = refreshed, updatedAt = System.currentTimeMillis()))
            lastRefreshTs = System.currentTimeMillis()
            refreshed
        } else {
            acc.cookie
        }
    }

    suspend fun logoutUC() {
        withContext(Dispatchers.IO) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
        // ★ 多账号：只退出**当前生效**账号，其余已保存账号保留。
        //   单账号时代 clear() 与"删当前账号"等价；多账号下 clear() 会清空整表，
        //   用户点一次"退出登录"就会丢掉所有账号，属于静默数据丢失。
        //   删除后 Repository 会自动激活剩余最近的一个。
        dao.getAccount()?.let { dao.deleteAndEnsureActive(it.id) }
    }

    suspend fun saveUCAccount(cookie: String): Boolean {
        if (!UCConstants.isValidCookie(cookie)) return false
        val nickname = api.fetchNickname(cookie) ?: "UC用户"
        dao.insertAsActive(
            UCAccountEntity(
                // 优先复用同昵称账号的行：cookie 会被服务端轮换，
                // 若每次登录都用完整 cookie 派生 id，轮换后重登会多出一行
                id = AccountIds.resolve("uc", cookie, nickname, "UC用户", dao::findIdByNickname),
                cookie = cookie,
                nickname = nickname
            )
        )
        // 重置刷新计时：新登录的 __puus 是新鲜的，避免立刻触发一次无谓刷新
        lastRefreshTs = System.currentTimeMillis()
        return true
    }
}