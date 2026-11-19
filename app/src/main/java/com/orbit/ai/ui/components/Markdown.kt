package com.orbit.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A deliberately small Markdown subset: code fences, headings, bullets, **bold** and `inline code`. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val parts = text.split("```")
    Column(modifier) {
        parts.forEachIndexed { i, part ->
            if (i % 2 == 1) {
                // fenced code: the first line may be a language name
                val firstBreak = part.indexOf('\n')
                val code = if (firstBreak >= 0 && part.take(firstBreak).trim().all { it.isLetterOrDigit() || it == '+' || it == '#' })
                    part.substring(firstBreak + 1) else part
                CodeBlock(code.trimEnd())
            } else if (part.isNotBlank()) {
                part.trim('\n').lines().forEach { line -> MarkdownLine(line) }
            }
        }
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val color = MaterialTheme.colorScheme.onSurface
    when {
        line.isBlank() -> Spacer(Modifier.height(6.dp))
        line.startsWith("### ") -> Text(styled(line.removePrefix("### ")), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = color)
        line.startsWith("## ") -> Text(styled(line.removePrefix("## ")), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = color)
        line.startsWith("# ") -> Text(styled(line.removePrefix("# ")), fontWeight = FontWeight.Bold, fontSize = 19.sp, color = color)
        line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
            Text(styled("•  " + line.trimStart().drop(2)), color = color, modifier = Modifier.padding(start = 6.dp))
        else -> Text(styled(line), color = color)
    }
}

@Composable
private fun styled(s: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var last = 0
        for (m in re.findAll(s)) {
            append(s.substring(last, m.range.first))
            if (m.groupValues[1].isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            } else {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(m.groupValues[2]) }
            }
            last = m.range.last + 1
        }
        append(s.substring(last))
    }
}

@Composable
fun CodeBlock(code: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(18.dp))
            }
        }
        Text(
            code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        )
    }
}
