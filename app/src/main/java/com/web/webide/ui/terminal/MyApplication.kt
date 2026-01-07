package com.web.webide.ui.terminal

import android.app.Application
import com.rk.libcommons.application
import com.rk.resources.Res

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. 初始化 ReTerminal 的全局 Context
        application = this

        // 2. 初始化资源模块
        Res.application = this

        // 3. (可选) 如果你复制了 CrashHandler，也可以在这里初始化
    }
}