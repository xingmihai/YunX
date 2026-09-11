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
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BaiduAccountDao {

    /**
     * 当前**生效**账号（isActive = 1）。
     *
     * ★ 方法名沿用旧的 observeAccount，但语义从「唯一那行」变为「生效那行」。
     *   上层调用点（Repository / ViewModel / UI）因此可以不动 ——
     *   多账号改造要么在这里收口，要么就得改遍所有调用方。
     */
    @Query("SELECT * FROM baidu_account WHERE isActive = 1 LIMIT 1")
    fun observeAccount(): Flow<BaiduAccountEntity?>

    /** 本平台全部已保存账号（最近更新的在前），用于账号切换列表 */
    @Query("SELECT * FROM baidu_account ORDER BY updatedAt DESC")
    fun observeAccounts(): Flow<List<BaiduAccountEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: BaiduAccountEntity)

    @Query("SELECT * FROM baidu_account WHERE isActive = 1 LIMIT 1")
    suspend fun getAccount(): BaiduAccountEntity?

    /**
     * 切换生效账号：全部置 0 → 目标置 1。
     * ★ 两步必须在**同一事务**内：分开调用时若中途失败或被观察，
     *   会出现「没有任何账号生效」的空窗，UI 立刻退成未登录态。
     */
    @Transaction
    suspend fun setActive(id: String) {
        clearActive()
        markActive(id)
    }

    @Query("UPDATE baidu_account SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE baidu_account SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: String)

    /** 删除指定账号（其余账号不受影响） */
    @Query("DELETE FROM baidu_account WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空本平台全部账号（退出登录沿用此行为，与单账号时代一致） */
    @Query("DELETE FROM baidu_account")
    suspend fun clear()
    /**
     * 保存账号并**立即设为生效**：先全部置 0，再 upsert(active=1)。
     *
     * ★ 为什么不直接 upsert：多账号下若新行的 isActive 由调用方传入，
     *   漏传就会存进一个「没人生效」的账号，UI 上看它存在却用不了。
     *   在这里统一置 1，调用方不必关心。
     *
     * ★ 这是接口默认实现（不是抽象），因此走的是**装饰器**的 this.upsert，
     *   加密才会生效；若由 Room 直接实现则会绕过加密层写入明文。
     */
    @Transaction
    suspend fun insertAsActive(account: BaiduAccountEntity) {
        clearActive()
        upsert(account.copy(isActive = 1))
    }

    /**
     * 删除指定账号；若删掉的正好是生效账号，则自动激活剩下最近的一个。
     * ★ 同样必须是接口默认实现，理由同 [insertAsActive]。
     */
    @Transaction
    suspend fun deleteAndEnsureActive(id: String) {
        deleteById(id)
        if (countActive() == 0) firstId()?.let { markActive(it) }
    }

    @Query("SELECT COUNT(*) FROM baidu_account WHERE isActive = 1")
    suspend fun countActive(): Int

    @Query("SELECT id FROM baidu_account ORDER BY updatedAt DESC LIMIT 1")
    suspend fun firstId(): String?
}