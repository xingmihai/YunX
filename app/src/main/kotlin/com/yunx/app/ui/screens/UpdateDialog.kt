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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yunx.app.data.update.UpdateChecker
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
    downloading: Boolean = false,
    /** 使用镜像站下载（可选）；为 null 时不显示镜像站按钮 */
    onDownloadMirror: (() -> Unit)? = null
) {
    val apk = remember(release) { UpdateChecker.preferredApk(release.assets) }
    val published = remember(release) { release.publishedAt.substringBefore("T", "") }

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
                                text = currentVersion,
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
                                    text = release.tagName,
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
                        MarkdownBody(markdown = body)
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

                if (onDownloadMirror != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onDownloadMirror,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("使用镜像站下载")
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

// ---------------------------------------------------------------- Markdown 轻量渲染

private enum class MdType { H1, H2, H3, BULLET, QUOTE, TEXT }

private data class MdBlock(val type: MdType, val text: String)

/** 行内语法：**粗体** 与 `代码` */
private val INLINE = Regex("""\*\*([^*]+)\*\*|`([^`]+)`""")

/** 分隔线（---）：不渲染，仅用于切段 */
private val HR = Regex("^-{3,}$")

private fun parseMarkdown(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val quote = StringBuilder()
    fun flushQuote() {
        if (quote.isNotBlank()) {
            out.add(MdBlock(MdType.QUOTE, quote.toString().trim()))
            quote.clear()
        }
    }
    for (raw in src.lineSequence()) {
        val t = raw.trim()
        when {
            t.isBlank() -> flushQuote()
            t.startsWith(">") -> quote.appendLine(t.removePrefix(">").trim())
            t.startsWith("### ") -> { flushQuote(); out.add(MdBlock(MdType.H3, t.removePrefix("### ").trim())) }
            t.startsWith("## ") -> { flushQuote(); out.add(MdBlock(MdType.H2, t.removePrefix("## ").trim())) }
            t.startsWith("# ") -> { flushQuote(); out.add(MdBlock(MdType.H1, t.removePrefix("# ").trim())) }
            t.matches(HR) -> flushQuote()
            t.startsWith("- ") || t.startsWith("* ") -> {
                flushQuote(); out.add(MdBlock(MdType.BULLET, t.drop(2).trim()))
            }
            else -> { flushQuote(); out.add(MdBlock(MdType.TEXT, t)) }
        }
    }
    flushQuote()
    return out
}

private fun renderInline(src: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (m in INLINE.findAll(src)) {
        append(src.substring(cursor, m.range.first))
        val bold = m.groupValues[1]
        val code = m.groupValues[2]
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
        }
        cursor = m.range.last + 1
    }
    append(src.substring(cursor))
}

@Composable
private fun MdText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val annotated = remember(text) { renderInline(text) }
    Text(text = annotated, style = style, color = color, modifier = modifier)
}

@Composable
private fun MarkdownBody(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val bodySmall = MaterialTheme.typography.bodySmall
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                val gap = when (block.type) {
                    MdType.H1, MdType.H2, MdType.H3 -> 14.dp
                    MdType.BULLET -> 3.dp
                    else -> 6.dp
                }
                Spacer(modifier = Modifier.height(gap))
            }
            when (block.type) {
                MdType.H1 -> MdText(
                    block.text,
                    MaterialTheme.typography.titleMedium,
                    MaterialTheme.colorScheme.onSurface
                )
                MdType.H2 -> MdText(
                    block.text,
                    MaterialTheme.typography.titleSmall,
                    MaterialTheme.colorScheme.onSurface
                )
                MdType.H3 -> MdText(
                    block.text,
                    MaterialTheme.typography.labelLarge,
                    MaterialTheme.colorScheme.primary
                )
                MdType.BULLET -> Row(modifier = Modifier.padding(start = 2.dp)) {
                    Text(
                        text = "•",
                        style = bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    MdText(
                        text = block.text,
                        style = bodySmall.copy(lineHeight = 19.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                MdType.QUOTE -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(3.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    MdText(
                        text = block.text,
                        style = bodySmall.copy(lineHeight = 19.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                MdType.TEXT -> MdText(
                    block.text,
                    bodySmall.copy(lineHeight = 19.sp),
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val mb = bytes / 1_048_576.0
    return if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
