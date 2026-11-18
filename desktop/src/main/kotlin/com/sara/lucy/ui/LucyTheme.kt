package com.sara.lucy.ui

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import java.awt.Color
import java.awt.Font
import javax.swing.UIManager

/**
 * Estética de Lucy: oscura, violeta y con todo redondeado (botones tipo píldora, campos, pestañas,
 * casillas y ventanas), con FlatLaf. Los colores son los mismos de Sara en Android.
 */
object LucyTheme {
    val VIOLET = Color(0x8B, 0x7B, 0xFF)
    val CYAN = Color(0x3D, 0xD9, 0xEB)
    val SURFACE = Color(0x14, 0x16, 0x1F)
    val SURFACE_HIGH = Color(0x1C, 0x1F, 0x2B)
    val TEXT = Color(0xED, 0xEE, 0xF5)

    fun install() {
        FlatLaf.setGlobalExtraDefaults(
            mapOf(
                "@accentColor" to "#8B7BFF",
                "@background" to "#0E0B1E",
                "@foreground" to "#EDEEF5",
            ),
        )
        runCatching { FlatDarkLaf.setup() }
        val arc = 999
        UIManager.put("Button.arc", arc)
        UIManager.put("Component.arc", 18)
        UIManager.put("TextComponent.arc", 18)
        UIManager.put("CheckBox.arc", 8)
        UIManager.put("ProgressBar.arc", arc)
        UIManager.put("ScrollBar.thumbArc", arc)
        UIManager.put("ScrollBar.thumbInsets", java.awt.Insets(2, 2, 2, 2))
        UIManager.put("ScrollBar.width", 10)
        UIManager.put("Component.focusWidth", 1)
        UIManager.put("Button.innerFocusWidth", 0)
        UIManager.put("TabbedPane.tabType", "card")
        UIManager.put("TabbedPane.tabArc", 16)
        UIManager.put("TabbedPane.tabSelectionArc", 16)
        UIManager.put("TabbedPane.cardTabArc", 16)
        UIManager.put("TabbedPane.showTabSeparators", false)
        UIManager.put("TabbedPane.tabHeight", 36)
        UIManager.put("Slider.thumbSize", java.awt.Dimension(16, 16))
        UIManager.put("Popup.borderCornerRadius", 14)
        UIManager.put("MenuItem.selectionArc", 10)
        UIManager.put("ToolTip.borderCornerRadius", 10)
        UIManager.put("TitlePane.unifiedBackground", true)
        UIManager.put("defaultFont", Font("Segoe UI", Font.PLAIN, 13))
    }
}
