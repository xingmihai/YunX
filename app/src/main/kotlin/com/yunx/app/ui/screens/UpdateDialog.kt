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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.ui.components.MarkdownText
import java.util.Locale

/**
 * 发现新版本弹窗（自定义 Dialog，非 AlertDialog）。
 *
 * 排版要点：
 * - **更新说明按 Markdown 渲染**：Release body 是 Markdown 源码，直接当纯文本显示会看到
 *   `## `、`**粗体**`、`- ` 等符号，观感很差；这里做了轻量渲染（标题/列表/引用/粗体/行内代码）。
 * - **版本对比**：当前版本 → 新版本，新版本用主色胶囊突出。
 * - **全宽操作按钮**：AlertDialog 的 confirm/dismiss 槽位放 4 个按钮会挤在右下角，
 *   改为自定义布局后主操作可占满宽度，手机上更好点。
 * - **标注将下载的文件**：显示 APK 文件名与体积，避免下到 debug 版却无感知。
 */
@Composable
fun UpdateDialog(
    currentVersion: String,
    release: UpdateChecker.Release,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    downloading: Boolean = false
) {
    val apk = remember(release) { UpdateChecker.preferredApk(release.assets) }
    val published = remember(release) { release.publishedAt.substringBefore("T", "") }
    // 两侧统一格式：均为 versionName（yyyy.MM.dd.HH.mm），统一去掉 v 前缀
    val labels = remember(release, currentVersion) { versionLabels(release, currentVersion) }

    Dialog(onDismissRequest = onLater) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {

                // ---------------- 头部：图标 + 标题 + 版本对比 + 关闭
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "发现新版本",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        // 当前版本 → 新版本
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = labels.first,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "  →  ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = labels.second,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    IconButton(onClick = onLater, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (published.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "发布于 $published",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ---------------- 更新说明（Markdown 渲染，可滚动）
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        val body = release.body.trim().ifBlank { "暂无更新说明" }
                        MarkdownText(markdown = body)
                    }
                }

                // ---------------- 将下载的文件
                if (apk != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val size = remember(apk) { formatSize(apk.sizeBytes) }
                    Text(
                        text = if (size != null) "将下载 ${apk.name}（$size）" else "将下载 ${apk.name}",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ---------------- 操作按钮（全宽，主操作在上）
                Button(
                    onClick = onDownload,
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (downloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载中…")
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载更新")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onLater) { Text("稍后") }
                    TextButton(onClick = onIgnore) {
                        Text("忽略本次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * 版本对比文案，两侧**统一格式**（都去掉 `v` 前缀）。
 *
 * versionName 现在是 `yyyy.MM.dd.HH.mm`（分钟精度），已足以区分同日多次构建，
 * 展示直接用它即可；判定新旧仍由 UpdateChecker.isNewer() 用 versionCode 完成。
 */
private fun versionLabels(
    release: UpdateChecker.Release,
    fallbackCurrent: String
): Pair<String, String> = fallbackCurrent.trimStart('v', 'V') to
    release.displayName.trim().trimStart('v', 'V')

private fun formatSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val mb = bytes / 1_048_576.0
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
