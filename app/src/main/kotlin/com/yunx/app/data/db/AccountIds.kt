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

import java.security.MessageDigest

/**
 * 多账号场景下账号行的**稳定主键**生成。
 *
 * ★ 为什么不能用自增 id：登录是「拿凭证 → 换昵称 → 落库」，若用数据库自增，
 *   同一账号重复登录会不断产生新行，列表越积越多；而用户期望的是覆盖更新。
 *
 * ★ 为什么不能用随机 UUID：同样会导致重复登录产生重复行。
 *
 * 因此取凭证内容的 hash —— 内容决定身份：
 * - 同一凭证再次登录 → 同一个 id → 覆盖那一行（符合直觉）
 * - 不同凭证 → 不同 id → 各自成行，可以并存与切换
 *
 * 取 SHA-256 前 16 位十六进制（64 bit）已远超可预见的账号数量，碰撞可忽略。
 */
object AccountIds {

    /**
     * @param platform 平台标识（如 "quark"），用于隔离：不同平台即便凭证相同也不会撞 id
     * @param secret 该账号的稳定凭证（cookie / refreshToken / accessToken）
     */
    /**
     * 登录落库时决定这一行该用哪个 id。
     *
     * 优先复用**同昵称**的已有账号，其次才用凭证 hash 派生。
     *
     * ★ 为什么必须复用：两个场景会产生重复行，都靠它兜住
     *   1. v14→v15 迁移保留了旧主键（"quark" / "uc" 等写死常量）。
     *      升级后重登同一账号，若直接用 hash 派生 id，会插成第二行，
     *      而迁移过来的那行还在 —— 切换器里出现重复的孤立账号。
     *   2. cookie 会被服务端轮换（如夸克 __puus）。id 若基于完整 cookie 派生，
     *      轮换后重登同样算出新 id，多出一行。
     *
     * 昵称是账号的**人眼标识**，不随会话轮换，因此用它判断"是不是同一个账号"。
     *
     * @param nickname 刚拉取到的昵称
     * @param fallbackNickname 拉取失败时的占位名；等于它时不做匹配
     *        （否则所有"昵称获取失败"的账号会互相覆盖成同一行）
     * @param findByNickname 查询已有账号 id 的挂起函数（由 DAO 提供）
     */
    suspend fun resolve(
        platform: String,
        secret: String,
        nickname: String,
        fallbackNickname: String,
        findByNickname: suspend (String) -> String?
    ): String {
        if (nickname.isNotBlank() && nickname != fallbackNickname) {
            findByNickname(nickname)?.let { return it }
        }
        return fromCredential(platform, secret)
    }

    fun fromCredential(platform: String, secret: String): String {
        // 凭证为空时不参与 hash，否则所有空凭证账号会挤在同一行
        val material = if (secret.isBlank()) {
            "$platform|anonymous|${System.currentTimeMillis()}"
        } else {
            "$platform|$secret"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        // Byte 有符号，必须 and 0xFF 再格式化，否则会出现 "ffffff80" 这类错误结果
        return digest.take(8).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
