package com.ykb.review.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Eklentiye özel ikonlar. plugin.xml'de `com.ykb.review.ui.ReviewIcons.ToolWindow` olarak referans verilir. */
object ReviewIcons {
    /** 13x13 tool window ikonu; koyu tema için `_dark` varyantı otomatik seçilir. */
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/ykbReview.svg", ReviewIcons::class.java)
}
