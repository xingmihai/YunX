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
import com.yunx.app.data.network.LanzouApi
import kotlinx.coroutines.flow.Flow

/**
 * 蓝奏云账号仓库：凭证的校验与落库。
 *
 * 校验方式：用 [LanzouApi.fetchVei] 试取 vei —— 能取到说明登录态有效
 * （未登录时 mydisk.php 会返回登录页，提取不到 vei）。
 * 比起额外找一个「用户信息」接口，这样复用已有调用，少一次请求。
 */
class LanzouAccountRepository(private val dao: LanzouAccountDao) {

    private val api = LanzouApi()

    fun observeAccount(): Flow<LanzouAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): LanzouAccountEntity? = dao.getAccount()

    /**
     * 校验并保存 Cookie。
     * @return 是否成功。失败（未登录 / 凭证过期 / 网络异常）返回 false 且不落库。
     */
    suspend fun save(cookie: String): Boolean {
        val uid = LanzouConstants.extractUid(cookie)
        if (uid.isEmpty()) return false
        if (api.fetchVei(cookie, uid).isFailure) return false
        dao.upsert(LanzouAccountEntity(cookie = cookie, uid = uid))
        return true
    }

    suspend fun clear() = dao.clear()
}
