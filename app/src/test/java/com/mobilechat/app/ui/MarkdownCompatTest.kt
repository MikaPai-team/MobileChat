package com.mobilechat.app.ui

import org.junit.Assert.*
import org.junit.Test
import org.commonmark.parser.Parser
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.StrongEmphasis

class MarkdownCompatTest {
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
