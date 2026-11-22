package com.mobilechat.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File

@RunWith(AndroidJUnit4::class)
class LatexRegressionTest {
    @Test fun screenshotFractionsProduceVisibleDrawables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val formulas = listOf("T_A=\\dfrac G2", "T_B=\\dfrac G{\\sqrt3}", "T_C=\\dfrac G{\\sqrt2}",
            "2T\\sin\\alpha=G\\implies T=\\frac{G}{2\\sin\\alpha}", "T_D=G")
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "latex-regression").apply { mkdirs() }
        formulas.forEachIndexed { index, formula ->
            val drawable = JLatexMathDrawable.builder(formula).textSize(48f).build()
            assertTrue(formula, drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
            val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(formula, pixels.any { it ushr 24 != 0 })
            File(directory, "formula-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

}
