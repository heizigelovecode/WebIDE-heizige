/*
 * WebIDE - A powerful IDE for Android web development.
 * Copyright (C) 2025  如日中天  <3382198490@qq.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
// 文件: java/com/example/sorarunrun/terminal/TerminalConfig.kt
package com.web.webide.ui.terminal

object TerminalConfig {

    // === 动态颜色配置 ===
    const val VIRTUAL_KEYS_JSON = """
[
  [
    "ESC",
    {
      "key": "/",
      "popup": "\\"
    },
    {
      "key": "-",
      "popup": "|"
    },
    "HOME",
    "UP",
    "END",
    "PGUP"
  ],
  [
    "TAB",
    "CTRL",
    "ALT",
    "LEFT",
    "DOWN",
    "RIGHT",
    "PGDN"
  ]
]
"""
    // 获取背景色
    fun getBackgroundColor(isDark: Boolean): Int {
        return if (isDark) {
            0xFF000000.toInt() // 深色模式：纯黑
        } else {
            0xFFFFFFFF.toInt() // 浅色模式：纯白
        }
    }

}
