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
import com.yunx.app.data.db.BaiduAccountDao
import com.yunx.app.data.db.BaiduAccountEntity
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 百度账号数据仓库：Room 持久化 + 网络验证（gettemplatevariable 拿昵称）。
 */
class BaiduAccountRepository(
    private val dao: BaiduAccountDao,
    private val api: BaiduApi
) {

    fun observeAccount(): Flow<BaiduAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): BaiduAccountEntity? = dao.getAccount()
    /** 本平台全部已保存账号（最近更新的在前），用于账号切换列表 */
    fun observeAccounts(): Flow<List<BaiduAccountEntity>> = dao.observeAccounts()

    /** 切换生效账号：此后该平台的 API 请求都使用它的凭证 */
    suspend fun switchAccount(id: String) = dao.setActive(id)

    /** 删除指定账号；若删掉的正好是生效账号，自动激活剩余最近的一个 */
    suspend fun removeAccount(id: String) = dao.deleteAndEnsureActive(id)

    /** 退出登录：清理 WebView Cookie + 清除本地记录 */
    suspend fun logoutBaidu() {
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
     * 校验 Cookie 有效性（需含 BDUSS）；有效则拉取昵称并落库，返回 true；无效返回 false。
     */
    suspend fun saveBaiduAccount(cookie: String): Boolean {
        if (!BaiduConstants.isValidCookie(cookie)) return false
        val nickname = api.fetchNickname(cookie) ?: "百度用户"
        dao.insertAsActive(
            BaiduAccountEntity(
                // 优先复用同昵称账号的行：凭证会被服务端轮换，
                // 若每次登录都用完整凭证派生 id，轮换后重登会多出一行
                id = AccountIds.resolve("baidu", cookie, nickname, "百度用户", dao::findIdByNickname),
                cookie = cookie,
                nickname = nickname
            )
        )
        return true
    }
}