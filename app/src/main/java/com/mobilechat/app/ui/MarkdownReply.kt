package com.mobilechat.app.ui

import android.text.method.LinkMovementMethod
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.TypedValue
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.Prism4jSyntaxHighlight
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.markwon.syntax.SyntaxHighlight
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp
import java.util.Locale
import java.util.concurrent.Executors

private val mathExecutor by lazy { Executors.newFixedThreadPool(2) }

private class ReplyRevealSpan(val start: Int, val end: Int, val began: Long, private val rise: Float) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) {
        val fraction = ((SystemClock.uptimeMillis() - began) / 320f).coerceIn(0f, 1f)
        paint.alpha = (paint.alpha * (0.25f + 0.75f * (1f - (1f - fraction) * (1f - fraction)))).toInt()
        if (fraction < 1f) paint.baselineShift += (rise * exp(-6f * fraction) * cos(10f * fraction)).toInt()
    }
}

private class StreamingReplyView(context: Context) : TextView(context) {
    private val reveals = mutableListOf<ReplyRevealSpan>()
    private var renderedContent: CharSequence? = null
    fun render(markwon: Markwon, value: CharSequence, animate: Boolean) {
        if (renderedContent === value) {
            if (!animate && reveals.isNotEmpty()) {
                (text as? Spannable)?.let { spans -> reveals.forEach(spans::removeSpan) }
                reveals.clear()
                invalidate()
            }
            return
        }
        val previous = text.toString()
        markwon.setParsedMarkdown(this, value as Spanned)
        val rendered = text.toString()
        var prefix = 0
        while (prefix < minOf(previous.length, rendered.length) && previous[prefix] == rendered[prefix]) prefix++
        val now = SystemClock.uptimeMillis()
        reveals.removeAll { !animate || it.end > prefix || now - it.began >= 320 }
        if (animate && ValueAnimator.areAnimatorsEnabled() && prefix < rendered.length) {
            reveals += ReplyRevealSpan(prefix, rendered.length, now, 3f * resources.displayMetrics.density)
        }
        if (reveals.isNotEmpty()) {
            val spans = SpannableStringBuilder(text)
            reveals.forEach { spans.setSpan(it, it.start, it.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            setText(spans, BufferType.SPANNABLE)
            postInvalidateOnAnimation()
        }
        renderedContent = value
    }
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val finished = reveals.filter { now - it.began >= 320 }
        (text as? Spannable)?.let { spans -> finished.forEach(spans::removeSpan) }
        reveals.removeAll(finished.toSet())
        super.onDraw(canvas)
        if (reveals.isNotEmpty()) postInvalidateOnAnimation()
    }
}

private sealed interface MarkdownPart {
    data class Prose(val content: CharSequence) : MarkdownPart
    data class Code(val language: String, val value: String) : MarkdownPart
}

private fun markdownParts(markwon: Markwon, text: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    val parsed = markwon.parse(normalizeMarkdown(text))
    var prose = Document()
    fun flush() {
        if (prose.firstChild != null) parts += MarkdownPart.Prose(markwon.render(prose))
        prose = Document()
    }
    var child = parsed.firstChild
    while (child != null) {
        val next = child.next
        when (child) {
            is FencedCodeBlock -> { flush(); parts += MarkdownPart.Code(codeLanguage(child.info.orEmpty()), child.literal) }
            is IndentedCodeBlock -> { flush(); parts += MarkdownPart.Code("", child.literal) }
            else -> prose.appendChild(child)
        }
        child = next
    }
    flush()
    return parts
}

private fun codeLanguage(info: String): String {
    val language = info.trim().takeWhile { !it.isWhitespace() }.lowercase(Locale.ROOT)
    return when (language) {
        "py" -> "python"
        "kt", "kts" -> "kotlin"
        "js" -> "javascript"
        "c++" -> "cpp"
        "c#", "cs" -> "csharp"
        "html", "xml", "svg" -> "markup"
        "yml" -> "yaml"
        "md" -> "markdown"
        else -> language
    }
}

private fun codeLabel(language: String): String = when (language) {
    "" -> "代码"
    "javascript" -> "JavaScript"
    "cpp" -> "C++"
    "csharp" -> "C#"
    "markup" -> "HTML / XML"
    "sql", "css", "json", "yaml" -> language.uppercase(Locale.ROOT)
    else -> language.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

private fun syntaxTheme(dark: Boolean, foreground: Int, background: Int): Prism4jThemeDefault {
    val palette = mapOf(
        "comment" to if (dark) 0xFF9AA3B2 else 0xFF687385,
        "keyword" to if (dark) 0xFFDAA0D8 else 0xFFAD287C,
        "string" to if (dark) 0xFF9BD3A5 else 0xFF277D39,
        "number" to if (dark) 0xFFF0BA90 else 0xFFB64D19,
        "function" to if (dark) 0xFFC0A7FF else 0xFF7545BD,
        "operator" to if (dark) 0xFFCAB7EE else 0xFF7545BD,
        "punctuation" to foreground.toLong(),
    ).mapValues { it.value.toInt() }
    val aliases = mapOf("char" to "string", "attr-value" to "string", "boolean" to "number", "constant" to "number",
        "builtin" to "function", "class-name" to "function", "variable" to "function", "property" to "function",
        "tag" to "keyword", "atrule" to "keyword", "selector" to "function", "attr-name" to "function",
        "triple-quoted-string" to "string", "prolog" to "comment", "doctype" to "comment", "cdata" to "comment")
    return object : Prism4jThemeDefault(background) {
        override fun textColor(): Int = foreground
        override fun color(language: String, type: String, alias: String?): Int =
            palette[type] ?: palette[aliases[type]] ?: palette[alias] ?: palette[aliases[alias]] ?: foreground
    }
}

@Composable
private fun CodeBlock(part: MarkdownPart.Code, syntax: SyntaxHighlight, background: Color, copy: (String) -> Unit) {
    val label = codeLabel(part.language)
    val highlighted = remember(part.language, part.value, syntax) {
        val spans = syntax.highlight(part.language.takeIf { it.isNotEmpty() }, part.value)
        buildAnnotatedString {
            append(spans.toString())
            if (spans is Spanned) spans.getSpans(0, spans.length, ForegroundColorSpan::class.java).forEach { span ->
                addStyle(SpanStyle(color = Color(span.foregroundColor)), spans.getSpanStart(span), spans.getSpanEnd(span))
            }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(background)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Code, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = { copy(part.value) }) {
                Icon(Icons.Default.ContentCopy, "复制 $label 代码", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SelectionContainer {
            Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun MarkdownReply(text: String, copy: (String) -> Unit, animate: Boolean = false,
    textColor: Color = MaterialTheme.colorScheme.onSurface, textSize: TextUnit = 16.sp) {
    val context = LocalContext.current
    val color = textColor.toArgb()
    val mathTextSize = with(LocalDensity.current) { textSize.toPx() }
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val codeBackground = if (dark) Color(0xFF242428) else Color(0xFFE8E8EB)
    val prism = remember { CodeLanguages.create() }
    val theme = remember(dark, color, codeBackground) { syntaxTheme(dark, color, codeBackground.toArgb()) }
    val syntax = remember(prism, theme) { Prism4jSyntaxHighlight.create(prism, theme) }
    val markwon = remember(context, prism, theme, color, mathTextSize) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(SyntaxHighlightPlugin.create(prism, theme))
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(mathTextSize) { builder ->
                builder.inlinesEnabled(true).executorService(mathExecutor)
                builder.theme().textColor(color).blockFitCanvas(true)
            }).build()
    }
    val parts = remember(text, markwon) { markdownParts(markwon, text) }
    val entrance = remember { Animatable(if (animate) 0f else 1f) }
    val opacity = remember { Animatable(if (animate) 0.3f else 1f) }
    LaunchedEffect(Unit) {
        launch { entrance.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
        launch { opacity.animateTo(1f, tween(260)) }
    }
    Column(Modifier.graphicsLayer {
        alpha = opacity.value
        translationY = (1f - entrance.value) * 6.dp.toPx()
        scaleX = 0.99f + 0.01f * entrance.value
        scaleY = scaleX
    }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        parts.forEachIndexed { index, part ->
            key(index, part::class) {
                when (part) {
                    is MarkdownPart.Prose -> AndroidView(factory = { StreamingReplyView(it).apply {
                        setLineSpacing(0f, 1.3f); setTextIsSelectable(true)
                        movementMethod = LinkMovementMethod.getInstance()
                        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    } }, update = { view ->
                        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, mathTextSize)
                        view.setTextColor(color); view.setLinkTextColor(link); view.render(markwon, part.content, animate)
                    }, modifier = Modifier.fillMaxWidth())
                    is MarkdownPart.Code -> CodeBlock(part, syntax, codeBackground, copy)
                }
            }
        }
    }
}
