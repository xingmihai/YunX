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

package com.yunx.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 139 网盘（和彩云）登录凭证（mail.10086.cn / yun.139.com cookie 落库，后续 API 请求携带）。
 * @param authorization 网页版直接下发的 Authorization（§3.5.5，形如 "Basic cGM6..."），解析时直接用
 */
@Entity(tableName = "c139_account")
data class C139AccountEntity(
    /**
     * 账号唯一标识：**cookie 的稳定 hash**（见各 Repository 的 save*），不再写死为 "c139"。
     *
     * ★ 为什么改：写死常量意味着一张表只能存一行，登录第二个账号会直接覆盖第一个，
     *   也就无从"切换"。改成内容派生后 —— 同一 cookie 重复登录仍是同一行（覆盖更新，
     *   符合预期），不同账号则各行其是。
     */
    @PrimaryKey
    val id: String = "",
    val cookie: String = "",
    val nickname: String = "",
    val authorization: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 是否为当前生效账号（1 = 生效）。同一平台内**至多一行**为 1。
     * 旧数据经 14→15 迁移后，原本唯一的那行置为 1，行为不变。
     */
    val isActive: Int = 0
)
