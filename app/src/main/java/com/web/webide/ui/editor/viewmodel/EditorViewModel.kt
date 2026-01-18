package com.web.webide.ui.editor.viewmodel

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.json.TSLanguageJson
import com.web.webide.core.utils.LogCatcher
import com.web.webide.core.utils.PermissionManager
import com.web.webide.ui.editor.EditorColorSchemeManager
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentListener
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader

// TreeSitter 相关导入
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.editor.ts.CssLanguage
import io.github.rosemoe.sora.editor.ts.HtmlLanguage
import io.github.rosemoe.sora.editor.ts.JavaScriptLanguage

// TextMate 备用方案
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

// LSP 支持
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import com.web.webide.lsp.ProotStreamConnectionProvider


// --- 数据类定义 ---

data class CodeEditorState(
    val file: File,
) {
    var content by mutableStateOf("")
    private var savedContent by mutableStateOf("")
    val isModified: Boolean get() = content != savedContent
    var lspEditor: LspEditor? = null

    fun onContentLoaded(loadedContent: String) {
        content = loadedContent
        savedContent = loadedContent
    }

    fun onContentSaved() {
        savedContent = content
    }
}

data class EditorConfig(
    val fontSize: Float = 14f,
    val tabWidth: Int = 4,
    val showLineNumbers: Boolean = true,
    val wordWrap: Boolean = false,
    val showInvisibles: Boolean = false,
    val codeFolding: Boolean = true,
    val showToolbar: Boolean = true,
    val fontPath: String = "",
    val customSymbols: String = "Tab,<,>,/,=,\",',!,?,;,:,{,},[,],(,),+,-,*,_,&,|"
) {
    fun getSymbolList(): List<String> = customSymbols.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

// --- ViewModel 实现 ---

class EditorViewModel : ViewModel() {

    // 状态管理
    var hasShownInitialLoader by mutableStateOf(false)
        private set
    var openFiles by mutableStateOf<List<CodeEditorState>>(emptyList())
        private set
    var activeFileIndex by mutableStateOf(-1)
        private set
    var currentProjectPath by mutableStateOf<String?>(null)
        private set
    var editorConfig by mutableStateOf(EditorConfig())
        private set

    // 编辑器实例缓存 (Key: 文件绝对路径)
    private val editorInstances = mutableMapOf<String, CodeEditor>()

    private var hasPermissions = false
    private lateinit var appContext: Context

    // TextMate 初始化标志
    private var textMateInitialized = false

    // LSP 项目实例
    private var lspProject: LspProject? = null
    // 记录已添加的 LSP 定义，防止重复添加
    private val addedLspDefinitions = mutableSetOf<String>()

    // 搜索状态
    private var lastSearchQuery = ""
    private var isIgnoreCase = true
    private var isFormatting = false

    // ======================================================
    // 核心逻辑：获取或创建 Editor 实例
    // ======================================================
    @Synchronized
    fun getOrCreateEditor(context: Context, state: CodeEditorState): CodeEditor {
        val filePath = state.file.absolutePath

        // 1. 检查缓存与 Context 有效性
        editorInstances[filePath]?.let { existingEditor ->
            // 如果 Context 发生变化（如旋转屏幕、深色模式切换），必须重建 Editor
            if (existingEditor.context != context) {
                try {
                    // 重建前先清理旧的 LSP，防止泄露
                    state.lspEditor?.dispose()
                    state.lspEditor = null

                    (existingEditor.parent as? ViewGroup)?.removeView(existingEditor)
                    existingEditor.release()
                } catch (e: Exception) { e.printStackTrace() }
                editorInstances.remove(filePath)
            } else {
                (existingEditor.parent as? ViewGroup)?.removeView(existingEditor)
                return existingEditor
            }
        }

        // 2. 创建新的 CodeEditor 实例
        val editor = CodeEditor(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            isFocusable = true
            isFocusableInTouchMode = true
            isEnabled = true

            // 设置初始内容
            setText(state.content)

            // ------------------------------------------------------------
            // 语言加载策略：TreeSitter (优) -> TextMate (备用) + LSP
            // ------------------------------------------------------------
            val fileExtension = state.file.extension.lowercase()

            // 尝试加载 TreeSitter
            val tsLanguage = loadTreeSitterLanguage(context, fileExtension)

            if (tsLanguage != null) {
                Log.d("EditorViewModel", "使用 TreeSitter 引擎: $fileExtension")
                setEditorLanguage(tsLanguage)
                configureRainbowColors(colorScheme)
            } else {
                // TreeSitter 失败，尝试 TextMate
                val tmLanguage = loadTextMateLanguage(context, fileExtension)
                if (tmLanguage != null) {
                    Log.d("EditorViewModel", "使用 TextMate 引擎: $fileExtension")
                    setEditorLanguage(tmLanguage)
                    // 应用 TextMate 配色方案
                    try {
                        colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                        Log.d("EditorViewModel", "TextMate 配色方案应用成功")
                    } catch (e: Exception) {
                        Log.w("EditorViewModel", "TextMate 配色方案应用失败", e)
                    }
                } else {
                    Log.d("EditorViewModel", "无高亮支持，使用 Plain: $fileExtension")
                    setEditorLanguage(EmptyLanguage())
                }
            }
            // ------------------------------------------------------------

            // 初始光标位置
            setSelection(0, 0)
            ensureSelectionVisible()

            // 监听内容变化同步到 State
            text.addContentListener(object : ContentListener {
                override fun beforeReplace(content: Content) {}
                override fun afterInsert(content: Content, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, inserted: CharSequence) {
                    val newText = content.toString()
                    if (state.content != newText) state.content = newText
                }
                override fun afterDelete(content: Content, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int, deleted: CharSequence) {
                    val newText = content.toString()
                    if (state.content != newText) state.content = newText
                }
            })
        }

        // 3. 初始化 LSP 支持（传递 TextMate 语言用于包装）
        val currentLanguage = editor.editorLanguage
        val textMateLanguage = if (currentLanguage is TextMateLanguage) currentLanguage else null
        setupLspForEditor(context, state, editor, textMateLanguage)

        editorInstances[filePath] = editor
        return editor
    }

    // ======================================================
    // TreeSitter 加载逻辑 (保持不变)
    // ======================================================

    private fun loadTreeSitterLanguage(context: Context, extension: String): TsLanguage? {
        try {
            val language: TSLanguage = when (extension) {
                "html", "htm" -> HtmlLanguage()
                "css" -> CssLanguage()
                "js", "javascript" -> JavaScriptLanguage()
                "json", "JSON" -> TSLanguageJson.getInstance()
                else -> return null
            }

            val langFolderName = when(extension) {
                "js", "javascript" -> "javascript"
                "htm" -> "html"
                else -> extension
            }

            val highlightsScm = readAssetFile(context, "queries/$langFolderName/highlights.scm")
            if (highlightsScm.isBlank()) return null

            val spec = TsLanguageSpec(language, highlightsScm).apply {
                rainbowBracketsEnabled = true
            }

            return TsLanguage(spec) {
                TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL) applyTo ""
                TextStyle.makeStyle(EditorColorScheme.KEYWORD) applyTo "keyword"
                TextStyle.makeStyle(EditorColorScheme.COMMENT) applyTo "comment"
                TextStyle.makeStyle(EditorColorScheme.OPERATOR) applyTo arrayOf("operator", "punctuation.bracket", "punctuation.delimiter", "punctuation.special")
                val stringColorId = if (langFolderName == "html") EditorColorScheme.ATTRIBUTE_VALUE else EditorColorScheme.LITERAL
                TextStyle.makeStyle(stringColorId) applyTo arrayOf("string", "string.special")
                TextStyle.makeStyle(EditorColorScheme.LITERAL) applyTo arrayOf("number", "constant", "constant.builtin")
                TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME) applyTo arrayOf("function", "function.method", "function.builtin", "constructor")
                TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_VAR) applyTo arrayOf("variable", "variable.builtin")
                TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_NAME) applyTo arrayOf("property", "type")
                TextStyle.makeStyle(EditorColorScheme.HTML_TAG) applyTo arrayOf("tag", "tag.error")
                TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME) applyTo "attribute"
            }
        } catch (e: Throwable) {
            Log.e("EditorViewModel", "TreeSitter 加载异常: $extension", e)
            return null
        }
    }

    private fun readAssetFile(context: Context, path: String): String {
        return try {
            context.assets.open(path).use { InputStreamReader(it).readText() }
        } catch (_: Exception) { "" }
    }

    // ======================================================
    // TextMate 加载逻辑 (保持不变)
    // ======================================================

    private fun ensureTextMateInitialized(context: Context) {
        if (textMateInitialized) return
        try {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.assets))
            try {
                val themeRegistry = ThemeRegistry.getInstance()
                val themeName = "darcula"
                val themePath = "textmate/$themeName.json"
                val themeInputStream = FileProviderRegistry.getInstance().tryGetInputStream(themePath)
                if (themeInputStream != null) {
                    themeRegistry.loadTheme(ThemeModel(IThemeSource.fromInputStream(themeInputStream, themePath, null), themeName))
                    themeRegistry.setTheme(themeName)
                }
            } catch (e: Exception) {
                Log.w("EditorViewModel", "TextMate 主题加载失败，使用默认主题", e)
            }
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            textMateInitialized = true
        } catch (e: Exception) {
            Log.e("EditorViewModel", "TextMate 初始化失败", e)
        }
    }

    private fun loadTextMateLanguage(context: Context, extension: String): TextMateLanguage? {
        return try {
            ensureTextMateInitialized(context)
            val scopeName = when (extension) {
                "html", "htm" -> "text.html.basic"
                "css" -> "source.css"
                "js", "javascript" -> "source.js"
                else -> return null
            }
            val prefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
            val lspEnabled = prefs.getBoolean("editor_lsp_enabled", false)
            val enableAutoComplete = !lspEnabled
            TextMateLanguage.create(scopeName, enableAutoComplete)
        } catch (e: Exception) {
            Log.e("EditorViewModel", "TextMate 加载失败: $extension", e)
            null
        }
    }

    private fun configureRainbowColors(scheme: EditorColorScheme) {
        scheme.setColor(256, 0xFFFF6B6B.toInt())
        scheme.setColor(257, 0xFFFFD93D.toInt())
        scheme.setColor(258, 0xFF6BCB77.toInt())
        scheme.setColor(259, 0xFF4D96FF.toInt())
        scheme.setColor(260, 0xFF9D4EDD.toInt())
        scheme.setColor(261, 0xFF00E5FF.toInt())
    }

    // ======================================================
    // 基础配置与权限
    // ======================================================
    fun updateRenamedFile(oldFile: File, newFile: File) {
        val index = openFiles.indexOfFirst { it.file.absolutePath == oldFile.absolutePath }
        if (index != -1) {
            val oldState = openFiles[index]
            val newState = oldState.copy(file = newFile)
            val mutableList = openFiles.toMutableList()
            mutableList[index] = newState
            openFiles = mutableList
            val oldEditor = editorInstances.remove(oldFile.absolutePath)
            if (oldEditor != null) {
                editorInstances[newFile.absolutePath] = oldEditor
            }
        }
    }
    fun reloadEditorConfig(context: Context) {
        val prefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
        editorConfig = EditorConfig(
            fontSize = prefs.getFloat("editor_font_size", 14f),
            tabWidth = prefs.getInt("editor_tab_width", 4),
            wordWrap = prefs.getBoolean("editor_word_wrap", false),
            showInvisibles = prefs.getBoolean("editor_show_invisibles", false),
            codeFolding = prefs.getBoolean("editor_code_folding", true),
            showToolbar = prefs.getBoolean("editor_show_toolbar", true),
            fontPath = prefs.getString("editor_font_path", "") ?: "",
            customSymbols = prefs.getString("editor_custom_symbols", "Tab,<,>,/,=,\",',!,?,;,:,{,},[,],(,),+,-,*,_,&,|") ?: ""
        )
    }

    fun initializePermissions(context: Context) {
        appContext = context.applicationContext
        hasPermissions = PermissionManager.hasRequiredPermissions(appContext)
    }

    private fun checkPermissions(): Boolean = hasPermissions

    fun onInitialLoaderShown() {
        hasShownInitialLoader = true
    }

    fun updateEditorTheme(seedColor: Color, isDark: Boolean) {
        editorInstances.values.forEach { editor ->
            val currentScheme = editor.colorScheme
            EditorColorSchemeManager.applyThemeColors(currentScheme, seedColor, isDark)
            if (editor.editorLanguage is TsLanguage) {
                configureRainbowColors(currentScheme)
            }
            editor.invalidate()
        }
    }

    // ======================================================
    // 文件操作
    // ======================================================

    fun loadInitialFile(projectPath: String) {
        if (projectPath != currentProjectPath) {
            closeAllFiles()
            currentProjectPath = projectPath
            val indexFile = File(projectPath, "index.html")
            if (indexFile.exists() && indexFile.isFile && indexFile.canRead()) {
                openFile(indexFile)
            }
        }
    }

    fun openFile(file: File) {
        if (file.isDirectory || !file.exists() || !file.canRead()) return
        viewModelScope.launch {
            val existingIndex = openFiles.indexOfFirst { it.file.absolutePath == file.absolutePath }
            if (existingIndex != -1) {
                activeFileIndex = existingIndex
            } else {
                val content = withContext(Dispatchers.IO) {
                    try {
                        file.readText(Charsets.UTF_8)
                    } catch (_: Exception) {
                        ""
                    }
                }
                val newState = CodeEditorState(file = file)
                newState.onContentLoaded(content)
                openFiles = openFiles + newState
                activeFileIndex = openFiles.lastIndex
            }
        }
    }

    suspend fun saveAllModifiedFiles(snackbarHostState: SnackbarHostState) {
        withContext(Dispatchers.IO) {
            val modifiedFiles = openFiles.filter { it.isModified }
            if (modifiedFiles.isEmpty()) return@withContext

            if (!checkPermissions()) {
                withContext(Dispatchers.Main) {
                    viewModelScope.launch { snackbarHostState.showSnackbar("需要存储权限才能保存文件") }
                }
                return@withContext
            }

            var successCount = 0
            modifiedFiles.forEach { state ->
                try {
                    state.file.outputStream().use { output ->
                        output.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write(state.content)
                        }
                    }
                    state.onContentSaved()
                    successCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    viewModelScope.launch { snackbarHostState.showSnackbar("已保存 $successCount 个文件") }
                }
            }
        }
    }

    fun createNewItem(parentPath: String, name: String, isFile: Boolean, onSuccess: (File) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newItem = File(parentPath, name)
                if (newItem.exists()) return@launch

                val success = if (isFile) {
                    newItem.createNewFile()
                } else {
                    newItem.mkdirs()
                }

                if (success) {
                    withContext(Dispatchers.Main) {
                        onSuccess(newItem)
                    }
                }
            } catch (e: Exception) {
                LogCatcher.e("FileOps", "创建失败", e)
            }
        }
    }

    fun closeAllFiles() {
        openFiles.forEach { state ->
            try {
                state.lspEditor?.dispose()
                state.lspEditor = null
            } catch (e: Exception) { e.printStackTrace() }
            editorInstances.remove(state.file.absolutePath)?.release()
        }
        openFiles = emptyList()
        activeFileIndex = -1
    }

    fun closeOtherFiles(indexToKeep: Int) {
        if (indexToKeep !in openFiles.indices) return
        openFiles.forEachIndexed { index, state ->
            if (index != indexToKeep) {
                try {
                    state.lspEditor?.dispose()
                    state.lspEditor = null
                } catch (e: Exception) { e.printStackTrace() }
                editorInstances.remove(state.file.absolutePath)?.release()
            }
        }
        openFiles = listOf(openFiles[indexToKeep])
        activeFileIndex = 0
    }

    fun closeFile(indexToClose: Int) {
        if (indexToClose !in openFiles.indices) return
        val state = openFiles.getOrNull(indexToClose)

        state?.let {
            try {
                it.lspEditor?.dispose()
                it.lspEditor = null
            } catch (e: Exception) { e.printStackTrace() }
            editorInstances.remove(it.file.absolutePath)?.release()
        }

        openFiles = openFiles.toMutableList().also { it.removeAt(indexToClose) }
        if (openFiles.isEmpty()) {
            activeFileIndex = -1
        } else if (activeFileIndex >= indexToClose) {
            activeFileIndex = (activeFileIndex - 1).coerceAtLeast(0)
        }
    }

    fun changeActiveFileIndex(index: Int) {
        if (index in openFiles.indices) activeFileIndex = index
    }

    // ======================================================
    // 编辑器操作 (搜索/格式化/撤销/符号)
    // ======================================================

    fun getActiveEditor(): CodeEditor? {
        val activeFile = openFiles.getOrNull(activeFileIndex) ?: return null
        return editorInstances[activeFile.file.absolutePath]
    }

    fun searchText(query: String, ignoreCase: Boolean = isIgnoreCase) {
        lastSearchQuery = query
        isIgnoreCase = ignoreCase
        val editor = getActiveEditor() ?: return

        if (query.isNotEmpty()) {
            editor.searcher.search(query, EditorSearcher.SearchOptions(ignoreCase, false))
        } else {
            editor.searcher.stopSearch()
        }
    }

    fun searchNext() {
        val editor = getActiveEditor() ?: return
        if (editor.searcher.hasQuery()) {
            try { editor.searcher.gotoNext() } catch (e: Exception) { LogCatcher.e("Search", "Next failed", e) }
        }
    }

    fun searchPrev() {
        val editor = getActiveEditor() ?: return
        if (editor.searcher.hasQuery()) {
            try { editor.searcher.gotoPrevious() } catch (e: Exception) { LogCatcher.e("Search", "Prev failed", e) }
        }
    }

    fun replaceCurrent(replaceText: String) {
        try { getActiveEditor()?.searcher?.replaceCurrentMatch(replaceText) } catch (e: Exception) { LogCatcher.e("Search", "Replace failed", e) }
    }

    fun replaceAll(replaceText: String) {
        try { getActiveEditor()?.searcher?.replaceAll(replaceText) } catch (e: Exception) { LogCatcher.e("Search", "Replace all failed", e) }
    }

    fun stopSearch() {
        getActiveEditor()?.searcher?.stopSearch()
    }

    fun formatCode() {
        if (isFormatting) return
        isFormatting = true
        val activeFile = openFiles.getOrNull(activeFileIndex) ?: return
        val filePath = activeFile.file.absolutePath
        val editor = editorInstances[filePath] ?: return
        val extension = activeFile.file.extension

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val originalCode = editor.text.toString()
                val formattedCode = com.web.webide.core.utils.CodeFormatter.format(originalCode, extension, editorConfig.tabWidth)

                if (formattedCode != originalCode) {
                    withContext(Dispatchers.Main) {
                        val text = editor.text
                        val lastLine = text.lineCount - 1
                        val lastColumn = if(lastLine >= 0) text.getColumnCount(lastLine) else 0

                        text.beginBatchEdit()
                        text.replace(0, 0, lastLine, lastColumn, formattedCode)
                        text.endBatchEdit()

                        activeFile.content = formattedCode
                    }
                }
            } catch (e: Exception) {
                LogCatcher.e("Format", "Format failed", e)
            } finally {
                isFormatting = false
            }
        }
    }

    fun jumpToLine(lineStr: String) {
        val line = lineStr.toIntOrNull() ?: return
        val editor = getActiveEditor() ?: return
        val totalLines = editor.text.lineCount
        val targetLine = (line - 1).coerceIn(0, totalLines - 1)
        editor.setSelection(targetLine, 0)
        editor.ensureSelectionVisible()
    }

    fun undo() { getActiveEditor()?.undo() }
    fun redo() { getActiveEditor()?.redo() }

    fun insertSymbol(symbol: String) {
        val editor = getActiveEditor() ?: return
        val processedSymbol = if (symbol == "Tab") "\t" else symbol
        editor.insertText(processedSymbol, processedSymbol.length)
    }

    fun insertText(text: String) { insertSymbol(text) }

    fun getCursorPosition(): Pair<Int, Int> {
        val editor = getActiveEditor() ?: return Pair(1, 1)
        val cursor = editor.cursor
        return Pair(cursor.leftLine + 1, cursor.leftColumn + 1)
    }

    /**
     * 重新加载所有编辑器的语言配置（当 LSP 设置改变时调用）
     */
    fun reloadAllEditors(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentIndex = activeFileIndex

            openFiles.forEach { state ->
                val editor = editorInstances[state.file.absolutePath] ?: return@forEach
                val cursorLine = editor.cursor.leftLine
                val cursorColumn = editor.cursor.leftColumn

                // 1. 先销毁旧的 LSP (关键)
                try {
                    state.lspEditor?.dispose()
                } catch (e: Exception) {
                    Log.w("EditorViewModel", "Dispose old LSP failed", e)
                }
                state.lspEditor = null

                // 2. 重新加载语言
                val fileExtension = state.file.extension.lowercase()

                // 先尝试 TreeSitter
                val tsLanguage = loadTreeSitterLanguage(context, fileExtension)
                if (tsLanguage != null) {
                    editor.setEditorLanguage(tsLanguage)
                    val scheme = editor.colorScheme
                    configureRainbowColors(scheme)
                } else {
                    // 使用 TextMate
                    val tmLanguage = loadTextMateLanguage(context, fileExtension)
                    if (tmLanguage != null) {
                        editor.setEditorLanguage(tmLanguage)
                        try {
                            editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                        } catch (e: Exception) {
                            Log.w("EditorViewModel", "TextMate 配色方案应用失败", e)
                        }

                        // 3. 重新设置 LSP (setupLspForEditor 会创建新的连接)
                        setupLspForEditor(context, state, editor, tmLanguage)
                    }
                }

                // 恢复光标位置
                editor.setSelection(cursorLine, cursorColumn)
            }
            activeFileIndex = currentIndex
            Log.d("EditorViewModel", "所有编辑器已重新加载")
        }
    }

    // ======================================================
    // LSP 支持
    // ======================================================

    private fun setupLspForEditor(
        context: Context,
        state: CodeEditorState,
        editor: CodeEditor,
        textMateLanguage: TextMateLanguage?
    ) {
        val fileExtension = state.file.extension.lowercase()
        if (fileExtension !in listOf("html", "htm", "css", "js", "javascript")) return

        val prefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
        val lspEnabled = prefs.getBoolean("editor_lsp_enabled", false)

        if (!lspEnabled) {
            Log.d("EditorViewModel", "LSP 已被用户禁用")
            return
        }

        if (textMateLanguage == null) return

        try {
            // 初始化 LSP 项目
            if (lspProject == null) {
                val projectPath = File(context.filesDir, "lsp_workspace").apply { mkdirs() }.absolutePath
                lspProject = LspProject(projectPath)
                lspProject!!.init()

                val jsConfigContent = """{"compilerOptions": {"module": "commonjs", "target": "es6", "lib": ["es6", "dom"], "allowSyntheticDefaultImports": true}, "exclude": ["node_modules"]}"""
                File(projectPath, "jsconfig.json").writeText(jsConfigContent)
            }

            // === 关键修改：使用时间戳生成唯一的虚拟文件路径 ===
            // 解决问题：如果文件路径不变，lspProject.getOrCreateEditor 可能返回已 dispose 的旧实例
            val fileName = "editor_${System.identityHashCode(state)}_${System.currentTimeMillis()}.${fileExtension}"

            val project = lspProject!!
            val realFile = File(project.projectUri.path, fileName)

            if (!realFile.exists()) {
                realFile.writeText(state.content)
            }

            // 添加服务器定义 (仅添加一次，防止重复)
            val serverKey = fileExtension
            if (!addedLspDefinitions.contains(serverKey)) {
                val serverDefinition = when (fileExtension) {
                    "html", "htm" -> CustomLanguageServerDefinition(
                        ext = "html",
                        serverConnectProvider = { _ -> ProotStreamConnectionProvider(context, listOf("sh", "-c", "vscode-html-language-server --stdio")) }
                    )
                    "css" -> CustomLanguageServerDefinition(
                        ext = "css",
                        serverConnectProvider = { _ -> ProotStreamConnectionProvider(context, listOf("sh", "-c", "vscode-css-language-server --stdio")) }
                    )
                    "js", "javascript" -> CustomLanguageServerDefinition(
                        ext = "js",
                        serverConnectProvider = { _ -> ProotStreamConnectionProvider(context, listOf("sh", "-c", "typescript-language-server --stdio")) }
                    )
                    else -> null
                }

                if (serverDefinition != null) {
                    project.addServerDefinition(serverDefinition)
                    addedLspDefinitions.add(serverKey)
                }
            }

            // 创建新的 LSP Editor 实例 (由于路径唯一，这里保证是 New Instance)
            val lspEditor = project.getOrCreateEditor(realFile.absolutePath)

            lspEditor.wrapperLanguage = textMateLanguage
            lspEditor.isEnableHover = true
            lspEditor.isEnableSignatureHelp = true
            lspEditor.isEnableInlayHint = true
            lspEditor.editor = editor
            state.lspEditor = lspEditor

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // 双重检查，防止在连接前就被 dispose
                    if (state.lspEditor == lspEditor) {
                        lspEditor.connect()
                        Log.d("EditorViewModel", "LSP 连接成功: $fileExtension (Path: $fileName)")
                    }
                } catch (e: Exception) {
                    Log.e("EditorViewModel", "LSP 连接失败: $fileExtension", e)
                }
            }
        } catch (e: Exception) {
            Log.e("EditorViewModel", "LSP 设置失败", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        openFiles.forEach {
            try { it.lspEditor?.dispose() } catch (_: Exception) {}
        }
        editorInstances.values.forEach {
            try { it.release() } catch (e: Exception) { e.printStackTrace() }
        }
        editorInstances.clear()
        // 可选：清理 LSP Project
        // lspProject = null
        // addedLspDefinitions.clear()
    }
}