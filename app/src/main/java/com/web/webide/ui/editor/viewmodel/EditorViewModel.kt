

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
import com.web.webide.core.utils.LogCatcher
import com.web.webide.core.utils.PermissionManager
import com.web.webide.ui.editor.EditorColorSchemeManager
import com.web.webide.ui.editor.components.TextMateInitializer
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
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
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.json.TSLanguageJson
import io.github.rosemoe.sora.editor.ts.CssLanguage
import io.github.rosemoe.sora.editor.ts.HtmlLanguage
import io.github.rosemoe.sora.editor.ts.JavaScriptLanguage


// --- 数据类定义 ---

data class CodeEditorState(
    val file: File,
    val languageScopeName: String, // 主要用于 TextMate 的标识
) {
    var content by mutableStateOf("")
    private var savedContent by mutableStateOf("")
    val isModified: Boolean get() = content != savedContent

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

    // TextMate 支持的 Scope 列表 (用于回退判断)
    private val supportedTextMateScopes = setOf("text.html.basic", "source.css", "source.js", "text.plain", "source.json")

    private var hasPermissions = false
    private lateinit var appContext: Context

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
                    (existingEditor.parent as? ViewGroup)?.removeView(existingEditor)
                    existingEditor.release()
                } catch (e: Exception) { e.printStackTrace() }
                editorInstances.remove(filePath)
            } else {
                (existingEditor.parent as? ViewGroup)?.removeView(existingEditor)
                return existingEditor
            }
        }

        // 2. 确保 TextMate 初始化 (作为后备方案)
        if (!TextMateInitializer.isReady()) {
            TextMateInitializer.initialize(context)
        }

        // 3. 创建新的 CodeEditor 实例
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

            // 初始化配色 (使用 TextMate 的主题作为基础)
            colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())

            // ------------------------------------------------------------
            // 语言加载策略：TreeSitter (优) -> TextMate (良) -> Plain (差)
            // ------------------------------------------------------------
            val fileExtension = state.file.extension.lowercase()

            // 尝试加载 TreeSitter
            val tsLanguage = loadTreeSitterLanguage(context, fileExtension)

            if (tsLanguage != null) {
                Log.d("EditorViewModel", "使用 TreeSitter 引擎: $fileExtension")
                setEditorLanguage(tsLanguage)
                // TreeSitter 必须手动配置彩虹括号的颜色，否则看不见
                configureRainbowColors(colorScheme)
            } else {
                // 回退到 TextMate
                Log.d("EditorViewModel", "TreeSitter 不可用，回退到 TextMate: ${state.languageScopeName}")
                if (state.languageScopeName in supportedTextMateScopes) {
                    try {
                        val language = TextMateLanguage.create(state.languageScopeName, true)
                        setEditorLanguage(language)
                    } catch (e: Exception) {
                        LogCatcher.e("EditorViewModel", "TextMate 加载失败", e)
                    }
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

        editorInstances[filePath] = editor
        return editor
    }

    // ======================================================
    // TreeSitter 加载逻辑
    // ======================================================

    /**
     * 尝试根据文件后缀加载 TreeSitter 语言
     */
    /**
     * 尝试根据文件后缀加载 TreeSitter 语言
     */
    /**
     * 尝试根据文件后缀加载 TreeSitter 语言
     */
    private fun loadTreeSitterLanguage(context: Context, extension: String): TsLanguage? {
        try {
            // 1. 获取对应的原生语言对象
            val language: TSLanguage = when (extension) {
                "html", "htm" -> HtmlLanguage()
                "css" -> CssLanguage()
                "js", "javascript" -> JavaScriptLanguage()

                // 【修复点 1】 使用 TSLanguageJson.getInstance()
                "json", "JSON" -> TSLanguageJson.getInstance()

                else -> return null
            }

            // 2. 确定资源文件夹名称
            val langFolderName = when(extension) {
                "js", "javascript" -> "javascript"
                "htm" -> "html"
                else -> extension
            }

            // 3. 读取 SCM 规则文件
            // 【重要】即使是用官方库，这些 scm 文件也必须存在于你的 assets/queries/json/ 目录下

            // 高亮规则
            val highlightsScm = readAssetFile(context, "queries/$langFolderName/highlights.scm")
            if (highlightsScm.isBlank()) {
                Log.w("EditorViewModel", "未找到高亮规则: queries/$langFolderName/highlights.scm")
                return null
            }

            val spec = TsLanguageSpec(
                language,
                highlightsScm,
            ).apply {
                rainbowBracketsEnabled = true // 开启彩虹括号
            }

            // 5. 返回语言实例
            return TsLanguage(spec) {
                // 这里可以留空
            }

        } catch (e: Throwable) {
            Log.e("EditorViewModel", "TreeSitter 加载异常: $extension", e)
            return null
        }
    }
    /**
     * 辅助方法：读取 assets 文本
     */
    private fun readAssetFile(context: Context, path: String): String {
        return try {
            context.assets.open(path).use {
                InputStreamReader(it).readText()
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 辅助方法：配置彩虹括号颜色 (ID 256-261)
     */
    private fun configureRainbowColors(scheme: EditorColorScheme) {
        // 使用一组高辨识度的颜色
        scheme.setColor(256, 0xFFFF6B6B.toInt()) // Level 1: Red
        scheme.setColor(257, 0xFFFFD93D.toInt()) // Level 2: Orange
        scheme.setColor(258, 0xFF6BCB77.toInt()) // Level 3: Green
        scheme.setColor(259, 0xFF4D96FF.toInt()) // Level 4: Blue
        scheme.setColor(260, 0xFF9D4EDD.toInt()) // Level 5: Purple
        scheme.setColor(261, 0xFF00E5FF.toInt()) // Level 6: Cyan
    }

    // ======================================================
    // 基础配置与权限
    // ======================================================

    fun reloadEditorConfig(context: Context) {
        val prefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
        editorConfig = EditorConfig(
            fontSize = prefs.getFloat("editor_font_size", 14f),
            tabWidth = prefs.getInt("editor_tab_width", 4),
            wordWrap = prefs.getBoolean("editor_word_wrap", false),
            showInvisibles = prefs.getBoolean("editor_show_invisibles", false),
            codeFolding = prefs.getBoolean("editor_code_folding", true), // [新增] 读取配置
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

    // 动态更新主题（适配 Dark/Light 模式）
    fun updateEditorTheme(seedColor: Color, isDark: Boolean) {
        editorInstances.values.forEach { editor ->
            val currentScheme = editor.colorScheme
            EditorColorSchemeManager.applyThemeColors(currentScheme, seedColor, isDark)

            // 如果是 TreeSitter，applyThemeColors 可能会覆盖掉自定义颜色，需重新应用彩虹色
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
                val language = getLanguageScope(file.extension)
                val newState = CodeEditorState(file = file, languageScopeName = language)
                newState.onContentLoaded(content)
                openFiles = openFiles + newState
                activeFileIndex = openFiles.lastIndex
            }
        }
    }

    // 给 TextMate 用的映射
    private fun getLanguageScope(extension: String): String = when (extension.lowercase()) {
        "html", "htm" -> "text.html.basic"
        "css" -> "source.css"
        "js" -> "source.js"
        "json" -> "source.json"
        "java" -> "source.java"
        "kt", "kts" -> "source.kotlin"
        "xml" -> "text.xml"
        else -> "text.plain"
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
        openFiles.forEach { state -> editorInstances.remove(state.file.absolutePath)?.release() }
        openFiles = emptyList()
        activeFileIndex = -1
    }

    fun closeOtherFiles(indexToKeep: Int) {
        if (indexToKeep !in openFiles.indices) return
        openFiles.forEachIndexed { index, state ->
            if (index != indexToKeep) editorInstances.remove(state.file.absolutePath)?.release()
        }
        openFiles = listOf(openFiles[indexToKeep])
        activeFileIndex = 0
    }

    fun closeFile(indexToClose: Int) {
        if (indexToClose !in openFiles.indices) return
        openFiles.getOrNull(indexToClose)?.file?.absolutePath?.let { path ->
            editorInstances.remove(path)?.release()
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
            try {
                editor.searcher.gotoNext()
            } catch (e: Exception) {
                LogCatcher.e("Search", "Next failed", e)
            }
        }
    }

    fun searchPrev() {
        val editor = getActiveEditor() ?: return
        if (editor.searcher.hasQuery()) {
            try {
                editor.searcher.gotoPrevious()
            } catch (e: Exception) {
                LogCatcher.e("Search", "Prev failed", e)
            }
        }
    }

    fun replaceCurrent(replaceText: String) {
        try {
            getActiveEditor()?.searcher?.replaceCurrentMatch(replaceText)
        } catch (e: Exception) {
            LogCatcher.e("Search", "Replace failed", e)
        }
    }

    fun replaceAll(replaceText: String) {
        try {
            getActiveEditor()?.searcher?.replaceAll(replaceText)
        } catch (e: Exception) {
            LogCatcher.e("Search", "Replace all failed", e)
        }
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
                // 使用自定义的 CodeFormatter 工具类
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

    fun undo() {
        getActiveEditor()?.undo()
    }

    fun redo() {
        getActiveEditor()?.redo()
    }

    fun insertSymbol(symbol: String) {
        val editor = getActiveEditor() ?: return
        val processedSymbol = if (symbol == "Tab") "\t" else symbol
        editor.insertText(processedSymbol, processedSymbol.length)
    }

    fun insertText(text: String) {
        insertSymbol(text)
    }

    override fun onCleared() {
        super.onCleared()
        editorInstances.values.forEach {
            try { it.release() } catch (e: Exception) { e.printStackTrace() }
        }
        editorInstances.clear()
    }
}