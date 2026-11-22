package com.mobilechat.app.ui

private val cjkBoldEnding = Regex("(\\*\\*[^*\\n]+[\\p{P}])\\*\\*(?=[\\p{L}\\p{N}])")

internal fun normalizeMarkdown(source: String): String {
    val output = StringBuilder()
    val prose = StringBuilder()
    fun flush() {
        // CommonMark does not close emphasis after punctuation when Chinese text follows it.
        output.append(cjkBoldEnding.replace(prose.toString()) { "${it.groupValues[1]}** " })
        prose.setLength(0)
    }
    var index = 0
    while (index < source.length) {
        val codeEnd = codeEnd(source, index)
        if (codeEnd != null) {
            flush()
            output.append(source, index, codeEnd)
            index = codeEnd
            continue
        }
        val delimiter = when {
            escaped(source, index) -> null
            source.startsWith("\\[", index) -> "\\[" to "\\]"
            source.startsWith("\\(", index) -> "\\(" to "\\)"
            source.startsWith("$$", index) -> "$$" to "$$"
            source[index] == '$' && source.getOrNull(index + 1)?.isWhitespace() == false -> "$" to "$"
            else -> null
        }
        if (delimiter != null) {
            val (open, close) = delimiter
            var end = source.indexOf(close, index + open.length)
            while (end >= 0 && (escaped(source, end) || (open == "$" &&
                    (source.getOrNull(end - 1)?.isWhitespace() == true || source.getOrNull(end + 1)?.isDigit() == true ||
                        source.getOrNull(end + 1) == '$')))) end = source.indexOf(close, end + close.length)
            if (end > index + open.length) {
                val body = source.substring(index + open.length, end)
                val lineStart = source.lastIndexOf('\n', index - 1) + 1
                val lineEnd = source.indexOf('\n', end + close.length).takeIf { it >= 0 } ?: source.length
                val before = source.substring(lineStart, index)
                val after = source.substring(end + close.length, lineEnd)
                val display = open == "\\[" || (open == "$$" && before.isBlank() && after.isBlank())
                flush()
                if (display) {
                    if (before.isNotBlank()) output.append("\n\n")
                    output.append("$$\n").append(body.trim()).append("\n$$")
                    if (after.isNotBlank()) output.append("\n\n")
                } else output.append("$$").append(body).append("$$")
                index = end + close.length
                continue
            }
        }
        prose.append(source[index++])
    }
    flush()
    return output.toString()
}

private fun escaped(source: String, index: Int): Boolean {
    var previous = index - 1
    while (previous >= 0 && source[previous] == '\\') previous--
    return (index - previous - 1) % 2 == 1
}

private fun codeEnd(source: String, index: Int): Int? {
    val character = source[index]
    val atLineStart = index == 0 || source[index - 1] == '\n'
    if (character != '`' && character != '~' && !atLineStart) return null
    val lineStart = source.lastIndexOf('\n', index - 1) + 1
    if (index == lineStart && (source.startsWith("    ", index) || source[index] == '\t')) {
        return source.indexOf('\n', index).let { if (it < 0) source.length else it + 1 }
    }
    if ((character != '`' && character != '~') || escaped(source, index)) return null
    var runEnd = index + 1
    while (source.getOrNull(runEnd) == character) runEnd++
    val length = runEnd - index
    val prefix = source.substring(lineStart, index)
    if (length >= 3 && prefix.all { it == ' ' || it == '>' }) {
        val nextLine = source.indexOf('\n', runEnd)
        if (nextLine < 0) return source.length
        val closing = Regex("(?m)^[ >]*${Regex.escape(character.toString())}{$length,}[ \\t]*\\r?$")
            .find(source, nextLine + 1)
        return closing?.range?.last?.plus(1) ?: source.length
    }
    if (character != '`') return null
    var closing = source.indexOf("`".repeat(length), runEnd)
    while (closing >= 0) {
        val end = closing + length
        if (source.getOrNull(closing - 1) != '`' && source.getOrNull(end) != '`') return end
        closing = source.indexOf("`".repeat(length), end)
    }
    return null
}
