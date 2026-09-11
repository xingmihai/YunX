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

package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.LanzouAccountEntity

/**
 * 蓝奏云账号信息底部弹窗：查看 Cookie 片段 / 退出登录。
 *
 * Cookie 只显示前 60 字符 —— 完整 Cookie 等于账号凭证，全量展示容易被旁观者拍屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanzouAccountSheet(
    account: LanzouAccountEntity,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val cookiePreview = remember(account.cookie) {
        account.cookie.take(60).let { if (account.cookie.length > 60) "$it…" else it }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("蓝奏云账号", style = MaterialTheme.typography.titleMedium)
            Text(text = "用户 ID：${account.uid}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Cookie：$cookiePreview",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (showLogoutConfirm) {
                Text("确定退出登录？退出后需要重新在网页登录。", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
                    Button(onClick = onLogout) { Text("确认退出") }
                }
            } else {
                OutlinedButton(onClick = { showLogoutConfirm = true }) { Text("退出登录") }
            }
        }
    }
}
