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

import com.yunx.app.data.security.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureAccountDaosTest {
    @Test
    fun encryptsWritesAndMigratesLegacyPlaintextOnRead() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(cookie = "legacy-secret"))
        val secure = SecureAccountDaos.quark(raw, FakeCipher())

        assertEquals("legacy-secret", secure.getAccount()?.cookie)
        assertTrue(raw.value()?.cookie?.startsWith("sealed:") == true)
        assertFalse(raw.value()?.cookie?.contains("legacy-secret") == true)

        // getAccount() 取的是「生效那行」，因此这里显式置 isActive = 1
        secure.upsert(QuarkAccountEntity(id = "cur", cookie = "new-secret", isActive = 1))
        assertEquals("new-secret", secure.getAccount()?.cookie)
        assertFalse(raw.value()?.cookie?.contains("new-secret") == true)
    }

    /**
     * 保存新账号后，同平台**至多一个**生效账号。
     *
     * 这条守的是「请求用错账号凭证」的风险：若 insertAsActive 没有先清除旧的，
     * 就会留下多行 isActive = 1，而 observeAccount() 取 LIMIT 1，
     * 取到哪一行是不确定的。
     */
    @Test
    fun insertAsActiveLeavesExactlyOneActive() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(id = "a", cookie = "secret-a"))
        val secure = SecureAccountDaos.quark(raw, FakeCipher())

        secure.insertAsActive(QuarkAccountEntity(id = "b", cookie = "secret-b"))

        assertEquals(1, raw.countActive())
        assertEquals("b", secure.getAccount()?.id)
        assertEquals(2, raw.values().size)
    }

    /**
     * 删除生效账号后，自动激活剩下的一个 —— 否则会陷入「有账号却没人生效」，
     * UI 显示已登录但所有请求都拿不到凭证。
     */
    @Test
    fun deleteAndEnsureActiveFallsBackToRemaining() = runBlocking {
        val raw = FakeQuarkDao(QuarkAccountEntity(id = "a", cookie = "secret-a"))
        val secure = SecureAccountDaos.quark(raw, FakeCipher())
        secure.insertAsActive(QuarkAccountEntity(id = "b", cookie = "secret-b"))

        secure.deleteAndEnsureActive("b")

        assertEquals(1, raw.values().size)
        assertEquals(1, raw.countActive())
        assertEquals("a", secure.getAccount()?.id)
    }

    /**
     * 某个账号解密失败时**只删那一行**，不能清空整表。
     *
     * ★ 这是多账号改造最容易踩的坑：单账号时代「解密失败 = 清空」是无害的，
     *   多账号下同样一句 clear() 会连带删掉所有其他账号。
     */
    @Test
    fun decryptionFailureRemovesOnlyTheBrokenRow() = runBlocking {
        val raw = FakeQuarkDao(
            QuarkAccountEntity(id = "a", cookie = "sealed:a"),
            QuarkAccountEntity(id = "b", cookie = "sealed:b")
        )
        val secure = SecureAccountDaos.quark(raw, ThrowingCipher())

        assertNull(secure.getAccount())
        assertEquals(1, raw.values().size)
        assertEquals("b", raw.values().single().id)
    }

    /**
     * 内存实现的 Fake DAO。
     *
     * ★ 构造时把首个账号置为生效：模拟 14→15 迁移后的状态
     *   （迁移会把原本唯一那行的 isActive 置 1）。否则 getAccount()
     *   按「生效那行」取会返回 null，旧用例就失去意义了。
     */
    private class FakeQuarkDao(vararg initial: QuarkAccountEntity?) : QuarkAccountDao {
        private val state = MutableStateFlow(initial.filterNotNull().mapIndexed { i, e ->
            if (i == 0) e.copy(isActive = 1) else e
        })

        fun value(): QuarkAccountEntity? = state.value.firstOrNull { it.isActive == 1 }
            ?: state.value.firstOrNull()
        fun values(): List<QuarkAccountEntity> = state.value

        override fun observeAccount(): Flow<QuarkAccountEntity?> =
            state.map { list -> list.firstOrNull { it.isActive == 1 } }

        override fun observeAccounts(): Flow<List<QuarkAccountEntity>> = state

        override suspend fun upsert(account: QuarkAccountEntity) {
            state.value = listOf(account) + state.value.filterNot { it.id == account.id }
        }

        override suspend fun getAccount(): QuarkAccountEntity? = state.value.firstOrNull { it.isActive == 1 }
        override suspend fun clear() { state.value = emptyList() }
        override suspend fun clearActive() { state.value = state.value.map { it.copy(isActive = 0) } }
        override suspend fun markActive(id: String) {
            state.value = state.value.map { if (it.id == id) it.copy(isActive = 1) else it }
        }
        override suspend fun deleteById(id: String) { state.value = state.value.filterNot { it.id == id } }
        override suspend fun findIdByNickname(nickname: String): String? =
            state.value.firstOrNull { it.nickname == nickname }?.id
        override suspend fun countActive(): Int = state.value.count { it.isActive == 1 }
        override suspend fun firstId(): String? = state.value.firstOrNull()?.id
    }

    private class FakeCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String =
            "sealed:${purpose.reversed()}:${plaintext.reversed()}"

        override fun decrypt(stored: String, purpose: String): String =
            if (isEncrypted(stored)) stored.substringAfterLast(':').reversed() else stored

        override fun isEncrypted(stored: String): Boolean = stored.startsWith("sealed:")
    }

    /** 模拟密钥不可用：任何解密都抛异常，用于验证失败时的隔离行为 */
    private class ThrowingCipher : CredentialCipher {
        override fun encrypt(plaintext: String, purpose: String): String = "sealed:$purpose:$plaintext"
        override fun decrypt(stored: String, purpose: String): String =
            throw IllegalStateException("密钥不可用")
        override fun isEncrypted(stored: String): Boolean = stored.startsWith("sealed:")
    }
}
