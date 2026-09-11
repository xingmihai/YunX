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

package com.yunx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（用于 Release 更新说明）。
 *
 * 设计：两阶段——先按行扫描归类成块，再对块内文本做行内富文本解析。
 * 相比逐行 if-else 拼装，块级状态机能正确处理这些容易出错的情形：
 *
 * - **列表项续行**：`- 首行` 后的缩进行属于同一列表项，不能拆成独立段落；
 * - **段落软换行**：无空行的连续文本行属于同一段落；
 * - **引用块聚合**：连续的 `>` 行合并为一个引用块；
 * - **围栏代码块**：``` 内部原样输出，不做 Markdown 解析；
 * - **分隔线**：`---` / `***` / `___` 渲染为水平线而非三个连字符。
 *
 * 不支持嵌套列表与表格（Release 说明基本用不到，强行支持会让解析复杂度失控）。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val base = MaterialTheme.typography.bodySmall
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(gapBefore(blocks[index - 1], block)))
            }
            RenderBlock(block, base)
        }
    }
}

// ----------------------------------------------------------------- 块解析

private enum class BlockType { HEADING, PARAGRAPH, BULLET, ORDERED, QUOTE, CODE, DIVIDER }

private class Block(
    val type: BlockType,
    val lines: MutableList<String> = mutableListOf(),
    /** 标题级别 1~6 */
    val level: Int = 0,
    /** 有序列表序号 */
    val number: Int? = null
) {
    /** 块内文本（多行用换行拼接，保留软换行） */
    val text: String get() = lines.joinToString("\n")
}

private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
private val DIVIDER = Regex("""^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
private val ORDERED = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
private val FENCE = Regex("""^\s{0,3}```""")

private fun parseBlocks(src: String): List<Block> {
    val blocks = ArrayList<Block>()
    var current: Block? = null
    var inFence = false

    fun close() {
        current?.let { blocks.add(it) }
        current = null
    }
    /** 结束当前块并开始一个新块 */
    fun open(block: Block): Block {
        close()
        current = block
        return block
    }

    for (rawLine in src.lineSequence()) {
        val line = rawLine.trimEnd()
        val t = line.trim()

        // 围栏代码块：开关切换，围栏行本身不输出
        if (FENCE.containsMatchIn(t)) {
            if (inFence) close() else open(Block(BlockType.CODE))
            inFence = !inFence
            continue
        }
        if (inFence) {
            (current ?: open(Block(BlockType.CODE))).lines.add(line)
            continue
        }

        val heading = HEADING.matchEntire(t)
        val bullet = BULLET.matchEntire(t)
        val ordered = ORDERED.matchEntire(t)
        val quote = QUOTE.matchEntire(t)

        when {
            // 空行 = 块分隔符
            t.isBlank() -> close()

            DIVIDER.matches(t) -> { close(); blocks.add(Block(BlockType.DIVIDER)) }

            heading != null -> {
                close()
                blocks.add(
                    Block(
                        type = BlockType.HEADING,
                        level = heading.groupValues[1].length,
                        lines = mutableListOf(heading.groupValues[2].trim())
                    )
                )
            }

            bullet != null -> open(Block(BlockType.BULLET)).lines.add(bullet.groupValues[1].trim())

            ordered != null -> open(
                Block(
                    type = BlockType.ORDERED,
                    number = ordered.groupValues[1].toIntOrNull()
                )
            ).lines.add(ordered.groupValues[2].trim())

            // 连续的 > 行合并为一个引用块
            quote != null -> {
                val target = if (current?.type == BlockType.QUOTE) current!! else open(Block(BlockType.QUOTE))
                target.lines.add(quote.groupValues[1].trim())
            }

            // 其余文本行：无当前块则开启段落；否则作为续行并入当前块
            // （列表项续行 / 段落软换行 / 引用的 lazy continuation 都走这里）
            else -> (current ?: open(Block(BlockType.PARAGRAPH))).lines.add(t)
        }
    }
    close()
    return blocks
}

// ------------------------------------------------------------- 行内富文本

private sealed interface Inline {
    data class Plain(val text: String) : Inline
    data class Bold(val text: String) : Inline
    data class Code(val text: String) : Inline
}

/** **粗体** 与 `行内代码` */
private val INLINE = Regex("""\*\*([^*]+)\*\*|`([^`]+)`""")

private fun parseInline(src: String): List<Inline> {
    val out = ArrayList<Inline>()
    var cursor = 0
    for (m in INLINE.findAll(src)) {
        if (m.range.first > cursor) {
            out.add(Inline.Plain(src.substring(cursor, m.range.first)))
        }
        val bold = m.groupValues[1]
        val code = m.groupValues[2]
        when {
            bold.isNotEmpty() -> out.add(Inline.Bold(bold))
            code.isNotEmpty() -> out.add(Inline.Code(code))
        }
        cursor = m.range.last + 1
    }
    if (cursor < src.length) out.add(Inline.Plain(src.substring(cursor)))
    return out
}

private fun annotate(src: String): AnnotatedString = buildAnnotatedString {
    for (part in parseInline(src)) {
        when (part) {
            is Inline.Plain -> append(part.text)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part.text) }
            is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part.text) }
        }
    }
}

// ----------------------------------------------------------------- 块渲染

private fun gapBefore(prev: Block, next: Block): Dp = when {
    next.type == BlockType.DIVIDER -> 12.dp
    prev.type == BlockType.DIVIDER -> 12.dp
    next.type == BlockType.HEADING -> 14.dp
    // 列表项之间紧凑排列
    prev.type in LIST_TYPES && next.type in LIST_TYPES -> 3.dp
    else -> 6.dp
}

private val LIST_TYPES = setOf(BlockType.BULLET, BlockType.ORDERED)

@Composable
private fun RenderBlock(block: Block, base: TextStyle) {
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val body = base.copy(lineHeight = 19.sp)
    when (block.type) {
        BlockType.HEADING -> when (block.level) {
            1 -> Styled(block.text, MaterialTheme.typography.titleMedium, MaterialTheme.colorScheme.onSurface)
            2 -> Styled(block.text, MaterialTheme.typography.titleSmall, MaterialTheme.colorScheme.onSurface)
            else -> Styled(block.text, MaterialTheme.typography.labelLarge, MaterialTheme.colorScheme.primary)
        }
        BlockType.BULLET -> Row(modifier = Modifier.padding(start = 2.dp)) {
            Text(text = "•", style = body, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(6.dp))
            Styled(block.text, body, bodyColor, Modifier.weight(1f))
        }
        BlockType.ORDERED -> Row(modifier = Modifier.padding(start = 2.dp)) {
            Text(
                text = "${block.number ?: 1}.",
                style = body,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Styled(block.text, body, bodyColor, Modifier.weight(1f))
        }
        BlockType.QUOTE -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
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
            Styled(block.text, body, bodyColor, Modifier.weight(1f))
        }
        BlockType.CODE -> Text(
            text = block.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            style = body.copy(fontFamily = FontFamily.Monospace),
            color = bodyColor
        )
        BlockType.DIVIDER -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(1.dp)
                )
        )
        BlockType.PARAGRAPH -> Styled(block.text, body, bodyColor)
    }
}

@Composable
private fun Styled(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val annotated = remember(text) { annotate(text) }
    Text(text = annotated, style = style, color = color, modifier = modifier)
}
