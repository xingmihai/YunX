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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 切换列表里的一项（与具体平台实体解耦，六个网盘共用同一套 UI） */
data class AccountSwitchItem(
    val id: String,
    /** 展示名（昵称，为空时调用方应回退为平台默认名） */
    val title: String,
    /** 副标题：登录时间 */
    val subtitle: String,
    val isActive: Boolean
)

/**
 * 账号切换区块：列出本平台已保存的全部账号，点击即切换为生效账号。
 *
 * ★ 为什么抽成通用组件：六个网盘的 AccountSheet 结构高度相似，
 *   若各自实现一遍，六份代码会各自漂移（漏掉某个平台的删除确认之类的）。
 *
 * @param onSwitch 切换到该账号（传 id）
 * @param onRemove 删除该账号（传 id）。删除生效账号时，Repository 会自动激活剩余最近的一个
 */
@Composable
fun AccountSwitcherSection(
    items: List<AccountSwitchItem>,
    platformName: String,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 只有一个账号时没必要展示切换列表，避免噪音
    if (items.size <= 1) return

    var pendingRemove by remember { mutableStateOf<AccountSwitchItem?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "已保存账号（${items.size}）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                items.forEach { item ->
                    AccountRow(
                        item = item,
                        onSwitch = { onSwitch(item.id) },
                        onRemove = { pendingRemove = item }
                    )
                }
            }
        }
    }

    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除账号") },
            text = {
                Text(
                    if (target.isActive) {
                        "确定删除「${target.title}」吗？它是当前生效账号，删除后会自动切换到其它已保存账号。"
                    } else {
                        "确定删除「${target.title}」吗？"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemove = null
                        onRemove(target.id)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AccountRow(
    item: AccountSwitchItem,
    onSwitch: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.isActive) { onSwitch() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.title.take(1),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (item.isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.isActive) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = "当前生效",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除账号",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 账号列表的时间格式（与 AccountSheet 的登录时间保持一致） */
internal fun formatAccountTime(updatedAt: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))

/** 昵称缺失时的回退名，避免出现空标题 */
internal fun accountTitle(nickname: String, platformName: String): String =
    nickname.ifBlank { "${platformName}用户" }
