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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Segment
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.web.webide.R
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
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
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
    viewModel: EditorViewModel,
    onShowSearch: () -> Unit = {},
    onRun: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onShowJumpLine: () -> Unit = {},
    onShowCreate: () -> Unit = {},
    onShowColorPicker: () -> Unit = {}
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
    var showCommandDialog by remember { mutableStateOf(false) }

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

    // 添加自定义文本操作按钮 (在双击/长按选区后的弹窗中)
    DisposableEffect(editor) {
        val textActionWindow = editor.getComponent(EditorTextActionWindow::class.java)
        val provider = object : EditorTextActionWindow.ExtraButtonProvider {
            override fun getIconResource(): Int = R.drawable.ic_command // 使用 Command 图标
            override fun getContentDescription(): String = "命令"
            override fun shouldShowButton(editor: CodeEditor): Boolean = true
            override fun onButtonClick(editor: CodeEditor) {
                showCommandDialog = true
            }
        }
        textActionWindow.addExtraButtonProvider(provider)
        onDispose {
            textActionWindow.removeExtraButtonProvider(provider)
        }
    }

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

    if (showCommandDialog) {
        val focusRequester = remember { FocusRequester() }

        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCommandDialog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .fillMaxHeight(0.85f),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // 保持输入法焦点的隐形输入框
                        TextField(
                            value = "",
                            onValueChange = {},
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .size(1.dp)
                                .alpha(0f)
                        )

                        val actions = remember {
                            listOf(
                                Triple("剪切", Icons.Filled.ContentCut) { editor.cutText() },
                                Triple("复制", Icons.Filled.ContentCopy) { editor.copyText() },
                                Triple("粘贴", Icons.Filled.ContentPaste) { editor.pasteText() },
                                Triple("全选", Icons.Filled.SelectAll) { editor.selectAll() },
                                Triple("选择单行", Icons.AutoMirrored.Filled.Segment) {
                                    val cursor = editor.cursor
                                    val line = cursor.leftLine
                                    if (line < editor.text.lineCount) {
                                        try {
                                            editor.setSelectionRegion(line, 0, line, editor.text.getColumnCount(line))
                                        } catch (_: Exception) {
                                            editor.setSelection(line, 0)
                                        }
                                    }
                                },
                                Triple("保存", Icons.Filled.Save) {
                                    viewModel.onContentChanged(state.file, editor.text.toString(), saveToFile = true)
                                    state.onContentSaved()
                                },
                                Triple("保存全部", Icons.Filled.Save) {
                                viewModel.openFiles.filterIsInstance<CodeEditorState>().filter { it.isModified }.forEach { s ->
                                    viewModel.onContentChanged(s.file, s.content, saveToFile = true)
                                    s.onContentSaved()
                                }
                            },
                            Triple("撤销", Icons.AutoMirrored.Filled.Undo) { editor.undo() },
                            Triple("重做", Icons.AutoMirrored.Filled.Redo) { editor.redo() },
                            Triple("跳转行", Icons.Filled.SwapVert) { onShowJumpLine() },
                            Triple("格式化", Icons.Filled.Menu) { viewModel.formatCode() },
                            Triple("新建", Icons.Filled.Add) { onShowCreate() },
                            Triple("调色板", Icons.Filled.ColorLens) { onShowColorPicker() },
                            Triple("终端", Icons.Filled.Dns) { onNavigateToTerminal() },
                            Triple("运行", Icons.Filled.PlayArrow) {
                                onRun()
                            },
                                Triple("只读模式", Icons.Filled.Lock) {
                                    editor.isEditable = !editor.isEditable
                                },
                                Triple("搜索替换", Icons.Filled.Search) {
                                    onShowSearch()
                                },
                                Triple("重载", Icons.Filled.Refresh) {
                                    viewModel.reloadAllEditors(context)
                                }
                            )
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(1),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(actions) { (label, icon, action) ->
                                FilledTonalButton(
                                    onClick = {
                                        action()
                                        showCommandDialog = false
                                    },
                                    contentPadding = PaddingValues(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Start
                                    ) {
                                        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
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
                    // Remove zoom limits
                    view.setScaleTextSizes(2f, 300f)

                    editor.setHighlightBracketPair(true)

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
