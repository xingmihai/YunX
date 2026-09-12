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
import com.yunx.app.data.db.C139AccountDao
import com.yunx.app.data.db.C139AccountEntity
import com.yunx.app.data.network.C139Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.yunx.app.data.db.AccountIds

/**
 * 139 网盘账号数据仓库：Room 持久化 + Cookie 校验。
 * 登录态 = mail.10086.cn 的 Os_SSo_Sid + RMKEY（WebView 登录后提取）。
 */
class C139AccountRepository(
    private val dao: C139AccountDao
) {

    fun observeAccount(): Flow<C139AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): C139AccountEntity? = dao.getAccount()
    /** 本平台全部已保存账号（最近更新的在前），用于账号切换列表 */
    fun observeAccounts(): Flow<List<C139AccountEntity>> = dao.observeAccounts()

    /** 切换生效账号：此后该平台的 API 请求都使用它的凭证 */
    suspend fun switchAccount(id: String) = dao.setActive(id)

    /** 删除指定账号；若删掉的正好是生效账号，自动激活剩余最近的一个 */
    suspend fun removeAccount(id: String) = dao.deleteAndEnsureActive(id)

    /** 退出登录：清理 WebView Cookie + 清除本地记录 */
    suspend fun logoutC139() {
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

    /**
     * 校验 139 Cookie 有效性（Os_SSo_Sid+RMKEY 或 authorization 任一成立）；
     * 有效则提取账号与 authorization 并落库，返回 true。
     */
    suspend fun saveC139Account(cookie: String): Boolean {
        if (!C139Constants.isValidCookie(cookie)) return false
        val nickname = C139Constants.extractAccount(cookie) ?: "139用户"
        val authorization = C139Constants.extractAuthorization(cookie).orEmpty()
        dao.insertAsActive(
            C139AccountEntity(
                // 优先复用同昵称账号的行：凭证会被服务端轮换，
                // 若每次登录都用完整凭证派生 id，轮换后重登会多出一行
                id = AccountIds.resolve("c139", cookie, nickname, "139用户", dao::findIdByNickname),
                cookie = cookie,
                nickname = nickname,
                authorization = authorization
            )
        )
        return true
    }
}