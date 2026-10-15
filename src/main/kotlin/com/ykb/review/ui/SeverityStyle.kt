package com.ykb.review.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.ykb.review.review.FindingsPresenter
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Önem seviyesi görünümünün TEK kaynağı: panel, editör vurgusu, diff işaretleri ve rozetler aynı renk/ikonları kullanır.
 * (Önceden panel düşük=yeşil, editör düşük=mavi gösteriyordu.)
 */
object SeverityStyle {
    private val HIGH = JBColor(Color(0xCF222E), Color(0xF85149))
    private val MEDIUM = JBColor(Color(0xBF8700), Color(0xD29922))
    private val LOW = JBColor(Color(0x1A7F37), Color(0x3FB950))

    val CODE_BACKGROUND: Color = JBColor(Color(0xF0F1F3), Color(0x2B2D30))

    fun color(severity: String): Color = when (severity) {
        "high" -> HIGH
        "medium" -> MEDIUM
        else -> LOW
    }

    fun label(severity: String): String = FindingsPresenter.severityLabel(severity)

    /** Ağaç / liste için küçük renkli nokta. */
    fun dot(severity: String): Icon = DotIcon(color(severity))

    /** Editör gutter'ı için platform ikonları (tema ile uyumlu). */
    fun gutterIcon(severity: String): Icon = when (severity) {
        "high" -> AllIcons.General.Error
        "medium" -> AllIcons.General.Warning
        else -> AllIcons.General.Information
    }

    fun hex(color: Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)

    /** HTML içinde kullanılacak renkli, yuvarlatılmış rozet (Swing HTML'i border-radius desteklemez; dolgu ile yaklaşık). */
    fun badgeHtml(text: String, color: Color): String =
        "<span style='color:#ffffff; background-color:${hex(color)}; font-weight:bold;'>&nbsp;${FindingsPresenter.escape(text)}&nbsp;</span>"

    private class DotIcon(private val color: Color) : Icon {
        private val size = JBUI.scale(10)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(x + 1, y + 1, size - 2, size - 2)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth() = size
        override fun getIconHeight() = size
    }
}
