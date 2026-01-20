/*
 * WebIDE - A powerful IDE for Android web development.
 * Copyright (C) 2025  如日中天  <3382198490@qq.com>
 */
package com.web.webide.ui.terminal

import android.view.View
import android.widget.Button
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeyButton
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.termux.terminal.TerminalSession

// 🔥 直接搬运 rk terminal 的实现
class VirtualKeysListener(val session: TerminalSession) : VirtualKeysView.IVirtualKeysView {

    override fun onVirtualKeyButtonClick(
        view: View?,
        buttonInfo: VirtualKeyButton?,
        button: Button?,
    ) {

        val key = buttonInfo?.key ?: return
        val writeable: String =
            when (key) {
                "UP" -> "\u001B[A"
                "DOWN" -> "\u001B[B"
                "LEFT" -> "\u001B[D"
                "RIGHT" -> "\u001B[C"
                "ENTER" -> "\u000D"
                "PGUP" -> "\u001B[5~"
                "PGDN" -> "\u001B[6~"
                "TAB" -> "\u0009"
                "HOME" -> "\u001B[H"
                "END" -> "\u001B[F"
                "ESC" -> "\u001B"
                else -> key
            }

        session.write(writeable)
    }

    override fun performVirtualKeyButtonHapticFeedback(
        view: View?,
        buttonInfo: VirtualKeyButton?,
        button: Button?,
    ): Boolean {
        return false
    }
}