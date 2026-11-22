package com.mobilechat.app.ui

import io.noties.prism4j.Prism4j
import org.junit.Assert.*
import org.junit.Test

class CodeLanguagesTest {
    @Test fun bundledGrammarsPreserveCodeIncludingUnicodeAndWhitespace() {
        val prism = CodeLanguages.create()
        val samples = mapOf(
            "python" to "import requests\nprint(\"上海天气 🌤\", 31.2304)\n",
            "javascript" to "const weather = { city: '上海', temperature: 25 };\n",
            "kotlin" to "val city: String = \"上海\"\n",
            "cpp" to "#include <iostream>\nint main() { return 0; }\n",
            "json" to "{\"city\": \"上海\", \"temperature\": 25}\n",
        )
        for ((language, code) in samples) {
            val grammar = prism.grammar(language)
            assertNotNull(language, grammar)
            val tokens = prism.tokenize(code, grammar!!)
            assertTrue(language, tokens.any { it.isSyntax })
            assertEquals(language, code, tokens.joinToString("") { source(it) })
        }
        assertNull(prism.grammar("unregistered-language"))
    }

    private fun source(node: Prism4j.Node): String = when (node) {
        is Prism4j.Text -> node.literal()
        is Prism4j.Syntax -> node.children().joinToString("") { source(it) }
        else -> error("Unknown token type")
    }
}
