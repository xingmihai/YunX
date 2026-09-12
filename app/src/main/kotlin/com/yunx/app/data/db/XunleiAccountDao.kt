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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface XunleiAccountDao {

    /**
     * 当前**生效**账号（isActive = 1）。
     *
     * ★ 方法名沿用旧的 observeAccount，但语义从「唯一那行」变为「生效那行」。
     *   上层调用点（Repository / ViewModel / UI）因此可以不动 ——
     *   多账号改造要么在这里收口，要么就得改遍所有调用方。
     */
    @Query("SELECT * FROM xunlei_account WHERE isActive = 1 LIMIT 1")
    fun observeAccount(): Flow<XunleiAccountEntity?>

    /** 本平台全部已保存账号（最近更新的在前），用于账号切换列表 */
    @Query("SELECT * FROM xunlei_account ORDER BY updatedAt DESC")
    fun observeAccounts(): Flow<List<XunleiAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: XunleiAccountEntity)

    @Query("SELECT * FROM xunlei_account WHERE isActive = 1 LIMIT 1")
    suspend fun getAccount(): XunleiAccountEntity?

    /**
     * 切换生效账号：全部置 0 → 目标置 1。
     *
     * ★ 两步顺序固定（先清后置）。这里刻意**不加 @Transaction** ——
     *   Room 对 Kotlin 接口默认方法上的 @Transaction 支持不确定，
     *   加了可能在编译期报错。中途被观察到的最坏情况是短暂「无生效账号」，
     *   下一次查询即恢复，不会造成数据错误。
     */
    suspend fun setActive(id: String) {
        clearActive()
        markActive(id)
    }

    @Query("UPDATE xunlei_account SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE xunlei_account SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: String)

    /** 删除指定账号（其余账号不受影响） */
    @Query("DELETE FROM xunlei_account WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空本平台全部账号（退出登录沿用此行为，与单账号时代一致） */
    @Query("DELETE FROM xunlei_account")
    suspend fun clear()
    /**
     * 按昵称查找已有账号的 id。
     *
     * ★ 作用：登录时优先复用同昵称账号的行，避免产生重复账号。
     *   两个场景必须靠它兜住（见 Repository 的 save*）：
     *   1. v14→v15 迁移保留了旧主键（"quark" 等），若新登录直接用 hash 派生 id，
     *      重登同一账号会插入第二行，留下重复的孤立账号；
     *   2. cookie 会被服务端轮换（如夸克 __puus），id 若基于完整 cookie 派生，
     *      轮换后重登同样会算出新 id 而多出一行。
     *
     * 昵称是账号的**人眼标识**，不随会话轮换，适合做「是不是同一个账号」的判据。
     */
    @Query("SELECT id FROM xunlei_account WHERE nickname = :nickname LIMIT 1")
    suspend fun findIdByNickname(nickname: String): String?
    /**
     * 保存账号并**立即设为生效**：先全部置 0，再 upsert(active=1)。
     *
     * ★ 为什么不直接 upsert：多账号下若新行的 isActive 由调用方传入，
     *   漏传就会存进一个「没人生效」的账号，UI 上看它存在却用不了。
     *   在这里统一置 1，调用方不必关心。
     *
     * ★ 这是接口默认实现（不是抽象），因此走的是**装饰器**的 this.upsert，
     *   加密才会生效；若由 Room 直接实现则会绕过加密层写入明文。
     *
     * ★ 这里刻意**不加 @Transaction**：Room 对 Kotlin 接口默认方法上的 @Transaction
     *   支持不确定，加了可能导致编译期报错。两步顺序固定（先 clearActive），
     *   中途被观察到的最坏情况是短暂"无生效账号"，下次查询即恢复。
     */
    suspend fun insertAsActive(account: XunleiAccountEntity) {
        clearActive()
        upsert(account.copy(isActive = 1))
    }

    /**
     * 删除指定账号；若删掉的正好是生效账号，则自动激活剩下最近的一个。
     * ★ 同样必须是接口默认实现，理由同 [insertAsActive]。
     */
    suspend fun deleteAndEnsureActive(id: String) {
        deleteById(id)
        if (countActive() == 0) firstId()?.let { markActive(it) }
    }

    @Query("SELECT COUNT(*) FROM xunlei_account WHERE isActive = 1")
    suspend fun countActive(): Int

    @Query("SELECT id FROM xunlei_account ORDER BY updatedAt DESC LIMIT 1")
    suspend fun firstId(): String?
}