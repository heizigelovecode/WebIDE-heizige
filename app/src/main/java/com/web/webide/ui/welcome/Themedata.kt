package com.web.webide.ui.welcome

import androidx.compose.ui.graphics.Color

// 1. 保持这个新的结构不变
data class ThemeColorSpec(
    val background: Color,
    val surface: Color,
    val primary: Color,
    val accent: Color
)

// 2. 修改这里：加上“兼容层”
data class ThemeColor(
    val name: String,
    val dark: ThemeColorSpec,
    val light: ThemeColorSpec
) {
    val primaryColor: Color    get() = dark.primary
    // =========== ⬆️ 兼容代码结束 ⬆️ ===========
}

// 3. 数据列表保持之前给你的双色配置 (直接复制覆盖即可)
val themeColors = listOf(
    ThemeColor(
        name = "Catppuccin",
        dark = ThemeColorSpec(Color(0xFF1E1E2E), Color(0xFF313244), Color(0xFFCBA6F7), Color(0xFFF5C2E7)),
        light = ThemeColorSpec(Color(0xFFEFF1F5), Color(0xFFE6E9EF), Color(0xFF8839EF), Color(0xFFEA76CB))
    ),
    ThemeColor(
        name = "Apple",
        dark = ThemeColorSpec(Color(0xFF000000), Color(0xFF1C1C1E), Color(0xFF32D74B), Color(0xFF0A84FF)),
        light = ThemeColorSpec(Color(0xFFF2F2F7), Color(0xFFFFFFFF), Color(0xFF34C759), Color(0xFF007AFF))
    ),
    ThemeColor(
        name = "Lavender",
        dark = ThemeColorSpec(Color(0xFF1A1626), Color(0xFF49454F), Color(0xFFB8ADFF), Color(0xFFCBC0FF)),
        light = ThemeColorSpec(Color(0xFFFFFBFF), Color(0xFFE6E0EC), Color(0xFF6750A4), Color(0xFFEADDFF))
    ),
    ThemeColor(
        name = "Midnight",
        dark = ThemeColorSpec(Color(0xFF0D1117), Color(0xFF161B22), Color(0xFF58A6FF), Color(0xFF79C0FF)),
        light = ThemeColorSpec(Color(0xFFFFFFFF), Color(0xFFF6F8FA), Color(0xFF0969DA), Color(0xFF54AEFF))
    ),
    ThemeColor(
        name = "Nord",
        dark = ThemeColorSpec(Color(0xFF2E3440), Color(0xFF3B4252), Color(0xFF88C0D0), Color(0xFF81A1C1)),
        light = ThemeColorSpec(Color(0xFFECEFF4), Color(0xFFE5E9F0), Color(0xFF5E81AC), Color(0xFF88C0D0))
    ),
    ThemeColor(
        name = "Strawberry",
        dark = ThemeColorSpec(Color(0xFF201418), Color(0xFF524347), Color(0xFFFF8FB4), Color(0xFFFFB1C8)),
        light = ThemeColorSpec(Color(0xFFFFF0F5), Color(0xFFFFE6ED), Color(0xFFD63384), Color(0xFFFF87AB))
    ),
    ThemeColor(
        name = "Tako",
        dark = ThemeColorSpec(Color(0xFF1A1625), Color(0xFF2D2640), Color(0xFF9D7CD8), Color(0xFFB79FE8)),
        light = ThemeColorSpec(Color(0xFFF3E8FF), Color(0xFFE9D5FF), Color(0xFF7E22CE), Color(0xFFA855F7))
    )
)

