package com.web.webide.ui.terminal

object TerminalConfig {
    // === 优化后的按键布局 (使用 Unicode 图标) ===
    const val VIRTUAL_KEYS_JSON = """
    [
      [
        {"key": "ESC", "display": "ESC"},
        {"key": "TAB", "display": "↹"},
        {"key": "CTRL", "display": "CTRL"},
        {"key": "ALT", "display": "ALT"},
        {"key": "-", "popup": "|", "display": "-"},
        {"key": "DOWN", "display": "▼"},
        {"key": "UP", "display": "▲"}
      ],
      [
        {"key": "/", "popup": "\\", "display": "/"},
        {"key": "HOME", "display": "Home"},
        {"key": "END", "display": "End"},
        {"key": "PGUP", "display": "PgUp"},
        {"key": "PGDN", "display": "PgDn"},
        {"key": "LEFT", "display": "◀"},
        {"key": "RIGHT", "display": "▶"}
      ]
    ]
    """

    // ... 颜色配置保持不变
    const val COLOR_BACKGROUND = 0xFF282A36.toInt()
    const val COLOR_FOREGROUND = 0xFFF8F8F2.toInt()
    // 稍微调亮一点按键背景，增加对比度
    const val COLOR_KEYS_BACKGROUND = 0xFF353746.toInt()
    const val COLOR_KEYS_TEXT = 0xFFBD93F9.toInt()

    // ... 调色板保持不变
    val COLOR_PALETTE = intArrayOf(
        0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
        0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFBFBFBF.toInt(),
        0xFF4D4D4D.toInt(), 0xFFFF6E67.toInt(), 0xFF5AF78E.toInt(), 0xFFF4F99D.toInt(),
        0xFFCAA9FA.toInt(), 0xFFFF92D0.toInt(), 0xFF9AEDFE.toInt(), 0xFFE6E6E6.toInt()
    )
}