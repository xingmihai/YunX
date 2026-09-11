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

import com.yunx.app.data.db.LanzouAccountDao
import com.yunx.app.data.db.LanzouAccountEntity
import com.yunx.app.data.network.LanzouConstants
import kotlinx.coroutines.flow.Flow

/** 保存结果：区分失败原因，避免出现「明明登录了却提示未登录」 */
sealed interface LanzouSaveResult {
    object Success : LanzouSaveResult
    /** Cookie 为空：压根没取到（域名不对 / WebView 未同步） */
    object NoCookie : LanzouSaveResult
    /** 取到了 Cookie 但没有 ylogin：确实还没登录 */
    object NotLoggedIn : LanzouSaveResult
}

/**
 * 蓝奏云账号仓库：凭证的校验与落库。
 *
 * ★ 为什么**不做网络校验**：
 *   之前在 save 里调 [LanzouApi.fetchVei] 做在线校验，失败就返回 false。
 *   但蓝奏云前面挂了 WAF（acw_tc / cdn_sec_tc / acw_sc__v2 是 JS 挑战凭证），
 *   OkHttp 直接请求很可能被拦 —— 于是**即使 Cookie 完全正确也会保存失败**，
 *   并弹出误导性的「未检测到有效登录状态」。
 *
 *   改为只用本地判据：Cookie 里有 ylogin 就视为已登录。
 *   真正的接口连通性放到浏览时验证（那里失败会提示「登录状态已失效」）。
 */
class LanzouAccountRepository(private val dao: LanzouAccountDao) {

    fun observeAccount(): Flow<LanzouAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): LanzouAccountEntity? = dao.getAccount()

    /** 保存 Cookie。成功即说明检测到登录态。 */
    suspend fun save(cookie: String): LanzouSaveResult {
        if (cookie.isBlank()) return LanzouSaveResult.NoCookie
        val uid = LanzouConstants.extractUid(cookie)
        if (uid.isEmpty()) return LanzouSaveResult.NotLoggedIn
        dao.upsert(LanzouAccountEntity(cookie = cookie, uid = uid))
        return LanzouSaveResult.Success
    }

    suspend fun clear() = dao.clear()
}
