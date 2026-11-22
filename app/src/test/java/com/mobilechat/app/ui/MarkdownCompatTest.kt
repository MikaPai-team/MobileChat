package com.mobilechat.app.ui

import org.junit.Assert.*
import org.junit.Test
import org.commonmark.parser.Parser
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.CustomBlock
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathPlugin
import java.io.File

class MarkdownCompatTest {
    @Test fun screenshotListFormulasRemainSeparateFromProse() {
        val source = File("src/androidTest/assets/latex_fix.txt").readText().replace("\r\n", "\n")
        val expected = Regex("\\\\\\[(.*?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
            .findAll(source).map { it.groupValues[1].trim() }.toList()
        val parser = Parser.builder().also { JLatexMathPlugin.create(16f).configureParser(it) }.build()
        val actual = mutableListOf<String>()
        parser.parse(normalizeMarkdown(source)).accept(object : AbstractVisitor() {
            override fun visit(block: CustomBlock) {
                if (block is JLatexMathBlock) actual += block.latex().trim()
                visitChildren(block)
            }
        })
        assertEquals(expected, actual)
    }

    @Test fun chemistryDisplayFormulasKeepTexAndBecomeMathBlocks() {
        val formula = "\\boxed{\\mathrm{Ag^{+}+Cl^{-}\\rightarrow AgCl\\downarrow}}"
        assertEquals("前文\n\n$$\n$formula\n$$\n\n后文", normalizeMarkdown("前文\\[$formula\\]后文"))
        assertEquals("$$\n$formula\n$$", normalizeMarkdown("$$$formula$$"))
    }

    @Test fun inlineFormulasKeepChargesAndDoNotBecomeBlocks() {
        assertEquals("电子为 \$\$e^{-}\$\$，铜离子为 \$\$\\mathrm{Cu^{2+}}\$\$。",
            normalizeMarkdown("电子为 \\(e^{-}\\)，铜离子为 $\\mathrm{Cu^{2+}}$。"))
    }

    @Test fun chineseBoldLabelsCloseBeforeAdjacentText() {
        val normalized = normalizeMarkdown("**① 写：**写出正确方程式。\n**② 拆：**把电解质拆成离子。")
        assertEquals("**① 写：** 写出正确方程式。\n**② 拆：** 把电解质拆成离子。", normalized)
        var bold = 0
        Parser.builder().build().parse(normalized).accept(object : AbstractVisitor() {
            override fun visit(strong: StrongEmphasis) { bold++; visitChildren(strong) }
        })
        assertEquals(2, bold)
    }

    @Test fun codeFencesInlineCodeAndIndentedCodeStayLiteral() {
        val code = "```latex\n\\[a^2\\]\n**① 写：**文字\n```\n~~~text\n\$x\$\n~~~\n`\\(x\\) \$x\$`\n    \\[x\\]\n"
        assertEquals(code, normalizeMarkdown(code))
    }

    @Test fun currencyEscapesAndIncompleteDelimitersStayReadable() {
        val text = "价格为 $100 和 $200，转义 \\\$x，未完成 \\[x^2"
        assertEquals(text, normalizeMarkdown(text))
    }

    @Test fun headingsListsQuotesAndTablesKeepTheirMarkdown() {
        val text = "## 电子守恒\n\n- **还原剂**失去电子\n\n> 总数相等。\n\n| 项目 | 内容 |\n| --- | --- |\n| 氧化 | 失电子 |\n"
        assertEquals(text, normalizeMarkdown(text))
    }
}
