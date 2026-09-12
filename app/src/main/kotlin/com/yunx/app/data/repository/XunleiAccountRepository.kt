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

import com.yunx.app.data.db.XunleiAccountDao
import com.yunx.app.data.db.XunleiAccountEntity
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.network.XunleiLoginStep
import kotlinx.coroutines.flow.Flow
import com.yunx.app.data.db.AccountIds

/**
 * 迅雷账号仓库：账号+密码登录（可能触发短信验证）→ 换 token 落库。
 */
class XunleiAccountRepository(
    private val dao: XunleiAccountDao,
    private val api: XunleiApi
) {

    fun observeAccount(): Flow<XunleiAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): XunleiAccountEntity? = dao.getAccount()
    /** 本平台全部已保存账号（最近更新的在前），用于账号切换列表 */
    fun observeAccounts(): Flow<List<XunleiAccountEntity>> = dao.observeAccounts()

    /** 切换生效账号：此后该平台的 API 请求都使用它的凭证 */
    suspend fun switchAccount(id: String) = dao.setActive(id)

    /** 删除指定账号；若删掉的正好是生效账号，自动激活剩余最近的一个 */
    suspend fun removeAccount(id: String) = dao.deleteAndEnsureActive(id)

    /** 账号密码登录；返回登录步骤（needSms=true 表示需短信验证，携带 smsCreditKey/smsToken） */
    suspend fun loginWithPassword(
        username: String,
        password: String
    ): XunleiLoginStep {
        // 必须用官方真实设备 ID（devicesign 配套），否则 userinfo_expired
        val deviceId = XunleiApi.newDeviceId()
        // 官方首次登录：v3/login 不带任何 creditkey/captcha，返回 review_panel(1007) 触发短信验证
        return api.loginWithPassword(username, password, deviceId)
    }

    /** 发送短信验证码（登录触发 review_panel 后调用） */
    suspend fun sendSms(mobile: String): XunleiLoginStep {
        val deviceId = XunleiApi.newDeviceId()
        return api.sendSms(mobile, deviceId)
    }

    /** 短信验证码登录并换取 token，成功落库返回 true */
    suspend fun loginWithSms(
        mobile: String,
        smsCode: String,
        creditKey: String,
        smsToken: String
    ): Boolean {
        val deviceId = XunleiApi.newDeviceId()
        val step = api.smsLogin(mobile, smsCode, creditKey, smsToken, deviceId)
        if (step.sessionId.isBlank()) return false
        // 换 token 前先 initCaptcha 拿 captcha_token（官方时序：smslogin → captcha/init → signin/token）
        val captchaToken = api.initCaptcha(deviceId, mobile) ?: ""
        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken) ?: return false
        val nickname = step.nickname.ifBlank { "迅雷用户" }
        dao.insertAsActive(
            XunleiAccountEntity(
                // 优先复用同昵称账号的行：凭证会被服务端轮换，
                // 若每次登录都用完整凭证派生 id，轮换后重登会多出一行
                id = AccountIds.resolve("xunlei", tokens.second, nickname, "迅雷用户", dao::findIdByNickname),
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = nickname
            )
        )
        return true
    }

    /** 密码登录成功后用 sessionID 换 token 落库 */
    suspend fun finishLogin(
        step: XunleiLoginStep,
        username: String
    ): Boolean {
        if (step.sessionId.isBlank()) return false
        val deviceId = XunleiApi.newDeviceId()
        val captchaToken = api.initCaptcha(deviceId, username) ?: ""
        val tokens = api.exchangeToken(step.sessionId, deviceId, captchaToken) ?: return false
        val nickname = step.nickname.ifBlank { "迅雷用户" }
        dao.insertAsActive(
            XunleiAccountEntity(
                // 优先复用同昵称账号的行：凭证会被服务端轮换，
                // 若每次登录都用完整凭证派生 id，轮换后重登会多出一行
                id = AccountIds.resolve("xunlei", tokens.second, nickname, "迅雷用户", dao::findIdByNickname),
                accessToken = tokens.first,
                refreshToken = tokens.second,
                deviceId = deviceId,
                captchaToken = captchaToken,
                nickname = nickname
            )
        )
        return true
    }

    /** 刷新 token 后更新 accessToken/refreshToken（deviceId/captchaToken 保持不变） */
    suspend fun updateTokens(accessToken: String, refreshToken: String) {
        val acc = dao.getAccount() ?: return
        dao.upsert(acc.copy(accessToken = accessToken, refreshToken = refreshToken))
    }

    suspend fun logout() {
        // ★ 多账号：只退出**当前生效**账号，其余已保存账号保留。
        //   单账号时代 clear() 与"删当前账号"等价；多账号下 clear() 会清空整表，
        //   用户点一次"退出登录"就会丢掉所有账号，属于静默数据丢失。
        //   删除后 Repository 会自动激活剩余最近的一个。
        dao.getAccount()?.let { dao.deleteAndEnsureActive(it.id) }
    }
}