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


package com.web.webide.ui.editor.components

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.web.webide.ui.ThemeViewModel
import com.web.webide.ui.ThemeViewModelFactory
import com.web.webide.ui.editor.viewmodel.CodeEditorState
import com.web.webide.ui.editor.viewmodel.EditorViewModel
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File

// 定义主题名称常量
private const val THEME_LIGHT = "quietlight" // 浅色主题文件名 (assets/textmate/quietlight.json)
private const val THEME_DARK = "darcula"     // 深色主题文件名 (assets/textmate/darcula.json)

@Composable
fun CodeEditorView(
    modifier: Modifier = Modifier,
    state: CodeEditorState,
    viewModel: EditorViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadEditorConfig(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var isEditorReady by remember { mutableStateOf(false) }

    val editorConfig = viewModel.editorConfig

    // === 字体加载逻辑 ===
    val editorTypeface = remember(editorConfig.fontPath) {
        if (editorConfig.fontPath.isBlank()) {
            Typeface.MONOSPACE
        } else {
            try {
                val file = File(editorConfig.fontPath)
                if (file.exists() && file.isFile && file.canRead()) {
                    Typeface.createFromFile(file)
                } else {
                    Typeface.createFromAsset(context.assets, editorConfig.fontPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Typeface.MONOSPACE
            }
        }
    }

    val themeViewModel: ThemeViewModel = viewModel(factory = ThemeViewModelFactory(context))
    val themeState by themeViewModel.themeState.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeState.selectedModeIndex) {
        0 -> systemDark
        1 -> false
        2 -> true
        else -> systemDark
    }
    val seedColor = if (themeState.isCustomTheme) themeState.customColor else MaterialTheme.colorScheme.primary

    val editor = remember(state.file.absolutePath) { viewModel.getOrCreateEditor(context, state) }

    // 初始化 TextMate
    LaunchedEffect(Unit) {
        if (!TextMateInitializer.isReady()) {
            TextMateInitializer.initialize(context) {
                isEditorReady = true
            }
        } else {
            isEditorReady = true
        }
    }

    // 🔥 核心逻辑：监听深色模式变化，切换高亮主题 🔥
    LaunchedEffect(seedColor, isDark, isEditorReady) {
        if (isEditorReady) {
            try {
                // 1. 切换 TextMate 的基础语法高亮主题
                val targetTheme = if (isDark) THEME_DARK else THEME_LIGHT
                ThemeRegistry.getInstance().setTheme(targetTheme)

                // 2. 重新创建配色方案应用到编辑器 (这会加载新的高亮颜色)
                val newScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                editor.colorScheme = newScheme

                // 3. 再次调用 ViewModel 更新 UI 颜色 (行号、背景色等，使其匹配 App 的 Material 主题)
                // 注意：EditorColorSchemeManager 会覆盖掉 TextMate 主题里的背景色，这正是我们想要的
                viewModel.updateEditorTheme(seedColor, isDark)

                // 强制重绘
                editor.invalidate()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (isEditorReady) {
            AndroidView(
                factory = { _ ->
                    (editor.parent as? ViewGroup)?.removeView(editor)
                    editor
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.props.singleDirectionDragging = false
                    view.typefaceText = editorTypeface
                    view.typefaceLineNumber = editorTypeface
                    view.isWordwrap = editorConfig.wordWrap
                    view.tabWidth = editorConfig.tabWidth
                    view.setFoldingEnabled(editorConfig.codeFolding)

                    if (editorConfig.showInvisibles) {
                        view.nonPrintablePaintingFlags =
                            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                                    CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                                    CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
                                    CodeEditor.FLAG_DRAW_LINE_SEPARATOR
                    } else {
                        view.nonPrintablePaintingFlags = 0
                    }

                    if (view.text.toString() != state.content) {
                        val cursor = view.cursor
                        val cursorLine = cursor.leftLine
                        val cursorColumn = cursor.leftColumn
                        view.setText(state.content)
                        try {
                            val lineCount = view.text.lineCount
                            val targetLine = cursorLine.coerceIn(0, lineCount - 1)
                            val lineLength = if (targetLine < view.text.lineCount) view.text.getColumnCount(targetLine) else 0
                            val targetColumn = cursorColumn.coerceIn(0, lineLength)
                            view.setSelection(targetLine, targetColumn)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    view.isEnabled = true
                    view.visibility = android.view.View.VISIBLE
                    view.requestLayout()
                }
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text(
                    text = "正在初始化编辑器...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

object TextMateInitializer {
    private var isInitialized = false
    private var isInitializing = false
    private val callbacks = mutableListOf<() -> Unit>()

    @OptIn(DelicateCoroutinesApi::class)
    @Synchronized
    fun initialize(context: Context, onComplete: (() -> Unit)? = null) {
        if (isInitialized) {
            onComplete?.invoke()
            return
        }
        if (isInitializing) {
            onComplete?.let { callbacks.add(it) }
            return
        }
        isInitializing = true
        onComplete?.let { callbacks.add(it) }

        kotlinx.coroutines.GlobalScope.launch {
            try {
                val appContext = context.applicationContext
                val assetsFileResolver = AssetsFileResolver(appContext.assets)
                FileProviderRegistry.getInstance().addFileProvider(assetsFileResolver)
                val themeRegistry = ThemeRegistry.getInstance()

                // 🔥 修改：加载深色和浅色两个主题 🔥
                // 确保你的 assets/textmate/ 目录下有这两个文件
                val themes = mapOf(
                    THEME_LIGHT to "textmate/$THEME_LIGHT.json",
                    THEME_DARK to "textmate/$THEME_DARK.json"
                )

                themes.forEach { (name, path) ->
                    try {
                        FileProviderRegistry.getInstance().tryGetInputStream(path)?.use { inputStream ->
                            themeRegistry.loadTheme(
                                ThemeModel(
                                    IThemeSource.fromInputStream(inputStream, path, null),
                                    name
                                )
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 加载语法定义
                GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

                synchronized(this) {
                    isInitialized = true
                    isInitializing = false
                    callbacks.forEach { it.invoke() }
                    callbacks.clear()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                synchronized(this) {
                    isInitializing = false
                    callbacks.clear()
                }
            }
        }
    }

    fun isReady() = isInitialized
}