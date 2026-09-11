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
 * 蓝奏云登录凭证（Cookie 串落库，后续 API 请求整体携带）。
 *
 * ★ 为什么存**整个 Cookie 串**而不是挑关键字段：
 *   蓝奏云前面挂了 WAF（acw_tc / cdn_sec_tc / acw_sc__v2 为 JS 挑战凭证，有有效期），
 *   挑字段存储会丢掉挑战凭证，导致接口偶发 403。
 *   Cookie 由 WebView 走完挑战后从 CookieManager 整体取出，原样存取最稳。
 *   （其他网盘平台是挑关键字段存的，蓝奏云是唯一例外。）
 */
@Entity(tableName = "lanzou_account")
data class LanzouAccountEntity(
    @PrimaryKey
    val id: String = "lanzou",
    /** 完整 Cookie 串（ylogin / phpdisk_info / PHPSESSID 等，含 WAF 挑战凭证） */
    val cookie: String = "",
    /** 用户 id（来自 ylogin，接口 URL 需要 ?uid=） */
    val uid: String = "",
    val nickname: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
