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
 * 123 云盘登录凭证（JWT token 落库，后续 API 请求携带 Authorization: Bearer <token>）。
 * 凭证形态为 JWT（Bearer Token），来源为网页登录后 localStorage 中的 authorToken
 * （与旧登录接口 data.token 同源同形）；JWT exp 约 90 天后过期，
 * token 失效（code 非 0 或 401）时重新走网页登录。
 */
@Entity(tableName = "pan123_account")
data class Pan123AccountEntity(
    /**
     * 账号唯一标识：**cookie 的稳定 hash**（见各 Repository 的 save*），不再写死为 "pan123"。
     *
     * ★ 为什么改：写死常量意味着一张表只能存一行，登录第二个账号会直接覆盖第一个，
     *   也就无从"切换"。改成内容派生后 —— 同一 cookie 重复登录仍是同一行（覆盖更新，
     *   符合预期），不同账号则各行其是。
     */
    @PrimaryKey
    val id: String = "",
    /** Bearer JWT（ResolveViewModel.currentCredential 返回，作为 repository 的 cookie 参数） */
    val accessToken: String = "",
    /** 登录账号（网页登录拿不到手机号，留空；账号页展示时回退昵称） */
    val account: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 是否为当前生效账号（1 = 生效）。同一平台内**至多一行**为 1。
     * 旧数据经 14→15 迁移后，原本唯一的那行置为 1，行为不变。
     */
    val isActive: Int = 0
)