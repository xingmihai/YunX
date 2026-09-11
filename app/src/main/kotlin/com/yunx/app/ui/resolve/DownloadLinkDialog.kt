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

package com.yunx.app.ui.resolve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.rememberGlobalSnackbarHostState

/** 可选线程数档位：覆盖小文件单线程到大文件满并发，512 为安全上限 */
private val THREAD_OPTIONS = listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)

/**
 * 下载直链弹窗：展示文件名与直链（长按直链复制），下载前选定线程数，支持「开始下载」（分片多线程下载）。
 * 线程数按任务锁定：任务开始后不再变更，避免分片计划变化导致续传文件损坏。
 * 点「关闭」或弹窗外（管壁）关闭 = 放弃下载，由上层清理临时转存。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadLinkDialog(
    link: DownloadLink,
    /** 预选线程数（上次选择值） */
    initialThreads: Int = 32,
    /** 开始下载（携带本次选定的线程数） */
    onDownload: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Dialog 内提示宿主（AlertDialog 为独立窗口）
    val snackbarHostState = rememberGlobalSnackbarHostState()
    // 本次选择的线程数（预填上次数值，可改；确认后写入任务并锁定）
    var threads by remember { mutableIntStateOf(initialThreads.coerceIn(1, 512)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = link.filename,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            // 内容超高时（横屏/小屏）可滚动，避免按钮被挤出屏幕
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "下载直链已生成（有效期约 15-30 分钟）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Text(
                        text = link.downloadUrl,
                        modifier = Modifier
                            .padding(12.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    copyToClipboard(context, link.downloadUrl)
                                    SnackbarController.show("下载链接已复制")
                                }
                            ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "下载线程数",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    THREAD_OPTIONS.forEach { value ->
                        FilterChip(
                            selected = threads == value,
                            onClick = { threads = value },
                            label = { Text("$value") },
                            shape = MaterialTheme.shapes.small,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
                Text(
                    text = "线程数不是越多越好，网盘与 CDN 有并发上限（迅雷自动封顶 8）；任务开始后不可修改",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Dialog 内提示（AlertDialog 为独立窗口，需自带 Snackbar 宿主）
                SnackbarHost(hostState = snackbarHostState)
            }
        },
        confirmButton = {
            Button(
                onClick = { onDownload(threads) }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("开始下载（$threads 线程）")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        modifier = modifier
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("download_url", text))
}