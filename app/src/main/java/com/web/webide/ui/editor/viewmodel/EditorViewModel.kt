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
package com.web.webide.ui.editor.viewmodel

import android.app.Application
import android.content.Context
import android.view.ViewGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.json.TSLanguageJson
import com.web.webide.core.utils.BackupUtils
import com.web.webide.core.utils.LogCatcher
import com.web.webide.core.utils.PermissionManager
import com.web.webide.ui.editor.EditorColorSchemeManager
import com.web.webide.ui.editor.git.GitManager
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
import java.util.UUID

// TreeSitter
import io.github.rosemoe.sora.editor.ts.TsLanguage
import io.github.rosemoe.sora.editor.ts.TsLanguageSpec
import io.github.rosemoe.sora.editor.ts.CssLanguage
import io.github.rosemoe.sora.editor.ts.HtmlLanguage
import io.github.rosemoe.sora.editor.ts.JavaScriptLanguage

// TextMate
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

// LSP
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import com.web.webide.lsp.ProotStreamConnectionProvider

// ================== 核心数据结构 ==================

interface IEditorTab {
    val title: String
    val file: File
    val uniqueId: String
}

enum class DiffViewMode { SPLIT, UNIFIED }

class DiffEditorState(
    override val file: File,
    val originalContent: String,
    val currentContent: String
) : IEditorTab {
    override val title: String = "${file.name} (Diff)"
    override val uniqueId: String = "diff_${file.absolutePath}_${UUID.randomUUID()}"
    var viewMode by mutableStateOf(DiffViewMode.SPLIT)
    var isReadOnly by mutableStateOf(true)
}

data class CodeEditorState(
    override val file: File,
) : IEditorTab {
    override val uniqueId: String = file.absolutePath
    override val title: String get() = if (isModified) "*${file.name}" else file.name

    var content by mutableStateOf("")
    var savedContent by mutableStateOf("")
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

// ================== ViewModel 实现 ==================

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    var hasShownInitialLoader by mutableStateOf(false)
        private set

    var openFiles by mutableStateOf<List<IEditorTab>>(emptyList())
        private set

    var activeFileIndex by mutableIntStateOf(-1)
        private set
    var currentProjectPath by mutableStateOf<String?>(null)
        private set
    var editorConfig by mutableStateOf(EditorConfig())
        private set
    var lastBuiltApk: File? by mutableStateOf(null)
        private set

    private val editorInstances = mutableMapOf<String, CodeEditor>()
    private var hasPermissions = false
    private lateinit var appContext: Context
    private var textMateInitialized = false

    private var lspProject: LspProject? = null
    private val addedLspDefinitions = mutableSetOf<String>()
    private var lastSearchQuery = ""
    private var isIgnoreCase = true
    private var isFormatting = false

    @Synchronized
    fun getOrCreateEditor(context: Context, state: CodeEditorState): CodeEditor {
        val filePath = state.file.absolutePath

        editorInstances[filePath]?.let { existingEditor ->
            if (existingEditor.context != context) {
                try {
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

        val editor = CodeEditor(context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            isFocusable = true
            isEnabled = true
            setText(state.content)

            applyLanguageToEditor(this, state.file.extension)

            setSelection(0, 0)
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

        val currentLanguage = editor.editorLanguage
        val textMateLanguage = currentLanguage as? TextMateLanguage
        setupLspForEditor(context, state, editor, textMateLanguage)

        editorInstances[filePath] = editor
        return editor
    }

    fun applyLanguageToEditor(editor: CodeEditor, extension: String) {
        val context = getApplication<Application>()
        val ext = extension.lowercase()
        val tsLanguage = loadTreeSitterLanguage(context, ext)

        if (tsLanguage != null) {
            editor.setEditorLanguage(tsLanguage)
            configureRainbowColors(editor.colorScheme)
        } else {
            val tmLanguage = loadTextMateLanguage(context, ext)
            if (tmLanguage != null) {
                editor.setEditorLanguage(tmLanguage)
                try {
                    editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                } catch (_: Exception) {}
            } else {
                editor.setEditorLanguage(EmptyLanguage())
            }
        }
    }

    // 🔥 修复报错: 补充 reloadAllEditors 方法
    fun reloadAllEditors(context: Context) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentIndex = activeFileIndex
            openFiles.forEach { tab ->
                if (tab is CodeEditorState) {
                    val editor = editorInstances[tab.file.absolutePath] ?: return@forEach
                    val cursorLine = editor.cursor.leftLine
                    val cursorColumn = editor.cursor.leftColumn

                    try { tab.lspEditor?.dispose() } catch (_: Exception) {}
                    tab.lspEditor = null

                    applyLanguageToEditor(editor, tab.file.extension)

                    val currentLang = editor.editorLanguage
                    if (currentLang is TextMateLanguage) {
                        setupLspForEditor(context, tab, editor, currentLang)
                    }

                    editor.setSelection(cursorLine, cursorColumn)
                }
            }
            activeFileIndex = currentIndex
        }
    }

    fun openDiff(projectPath: String, file: File) {
        viewModelScope.launch {
            val gitManager = GitManager(projectPath)
            val headContent = gitManager.getFileContentAtHead(file.absolutePath)
            val currentContent = withContext(Dispatchers.IO) {
                try { file.readText() } catch (_: Exception) { "" }
            }

            val diffState = DiffEditorState(file, headContent, currentContent)
            val existingIndex = openFiles.indexOfFirst {
                it is DiffEditorState && it.file.absolutePath == file.absolutePath
            }

            if (existingIndex != -1) {
                activeFileIndex = existingIndex
            } else {
                openFiles = openFiles + diffState
                activeFileIndex = openFiles.lastIndex
            }
        }
    }

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
            val highlightsScm = try {
                context.assets.open("queries/$langFolderName/highlights.scm").use { InputStreamReader(it).readText() }
            } catch (_: Exception) { "" }

            if (highlightsScm.isBlank()) return null
            val spec = TsLanguageSpec(language, highlightsScm).apply { rainbowBracketsEnabled = true }
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
        } catch (_: Throwable) { return null }
    }

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
            } catch (_: Exception) {}
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            textMateInitialized = true
        } catch (_: Exception) {}
    }

    private fun loadTextMateLanguage(context: Context, extension: String): TextMateLanguage? {
        return try {
            ensureTextMateInitialized(context)
            val scopeName = when (extension) {
                "html", "htm" -> "text.html.basic"
                "css" -> "source.css"
                "js", "javascript" -> "source.js"
                "glsl", "vert", "frag" -> "source.c"
                "c", "h" -> "source.c"
                "cpp", "hpp", "cc" -> "source.cpp"
                else -> return null
            }
            val prefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
            val lspEnabled = prefs.getBoolean("editor_lsp_enabled", false)
            TextMateLanguage.create(scopeName, !lspEnabled)
        } catch (_: Exception) { null }
    }

    fun configureRainbowColors(scheme: EditorColorScheme) {
        scheme.setColor(256, 0xFFFF6B6B.toInt())
        scheme.setColor(257, 0xFFFFD93D.toInt())
        scheme.setColor(258, 0xFF6BCB77.toInt())
        scheme.setColor(259, 0xFF4D96FF.toInt())
        scheme.setColor(260, 0xFF9D4EDD.toInt())
        scheme.setColor(261, 0xFF00E5FF.toInt())
    }

    fun openFile(file: File) {
        if (file.isDirectory || !file.exists()) return
        viewModelScope.launch {
            val existingIndex = openFiles.indexOfFirst {
                it is CodeEditorState && it.file.absolutePath == file.absolutePath
            }
            if (existingIndex != -1) {
                activeFileIndex = existingIndex
            } else {
                val content = withContext(Dispatchers.IO) {
                    try { file.readText(Charsets.UTF_8) } catch (_: Exception) { "" }
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
            val modifiedFiles = openFiles.filterIsInstance<CodeEditorState>().filter { it.isModified }
            if (modifiedFiles.isEmpty()) return@withContext

            modifiedFiles.forEach { state ->
                try {
                    state.file.writeText(state.content)
                    state.onContentSaved()
                } catch (e: Exception) { e.printStackTrace() }
            }
            withContext(Dispatchers.Main) {
                viewModelScope.launch { snackbarHostState.showSnackbar("已保存 ${modifiedFiles.size} 个文件") }
            }
        }
    }

    fun closeFile(indexToClose: Int) {
        if (indexToClose !in openFiles.indices) return
        val tab = openFiles[indexToClose]

        if (tab is CodeEditorState) {
            try { tab.lspEditor?.dispose() } catch (_: Exception) {}
            editorInstances.remove(tab.file.absolutePath)?.release()
        }

        openFiles = openFiles.toMutableList().also { it.removeAt(indexToClose) }
        if (openFiles.isEmpty()) {
            activeFileIndex = -1
        } else if (activeFileIndex >= indexToClose) {
            activeFileIndex = (activeFileIndex - 1).coerceAtLeast(0)
        }
    }

    fun closeOtherFiles(indexToKeep: Int) {
        if (indexToKeep !in openFiles.indices) return
        val keepTab = openFiles[indexToKeep]

        openFiles.forEachIndexed { index, tab ->
            if (index != indexToKeep && tab is CodeEditorState) {
                try { tab.lspEditor?.dispose() } catch (_: Exception) {}
                editorInstances.remove(tab.file.absolutePath)?.release()
            }
        }
        openFiles = listOf(keepTab)
        activeFileIndex = 0
    }

    fun closeAllFiles() {
        openFiles.filterIsInstance<CodeEditorState>().forEach { try { it.lspEditor?.dispose() } catch (_: Exception) {} }
        editorInstances.values.forEach { try { it.release() } catch (_: Exception) {} }
        editorInstances.clear()
        openFiles = emptyList()
        activeFileIndex = -1
    }

    fun changeActiveFileIndex(index: Int) {
        if (index in openFiles.indices) activeFileIndex = index
    }

    fun getActiveEditor(): CodeEditor? {
        val activeTab = openFiles.getOrNull(activeFileIndex) ?: return null
        if (activeTab is CodeEditorState) {
            return editorInstances[activeTab.file.absolutePath]
        }
        return null
    }

    fun undo() { getActiveEditor()?.undo() }
    fun redo() { getActiveEditor()?.redo() }

    fun insertSymbol(symbol: String) {
        val p = if (symbol == "Tab") "\t" else symbol
        getActiveEditor()?.insertText(p, p.length)
    }
    fun insertText(text: String) { insertSymbol(text) }

    fun jumpToLine(lineStr: String) {
        val line = lineStr.toIntOrNull() ?: return
        getActiveEditor()?.let { editor ->
            val totalLines = editor.text.lineCount
            val targetLine = (line - 1).coerceIn(0, totalLines - 1)
            editor.setSelection(targetLine, 0)
            editor.ensureSelectionVisible()
        }
    }

    fun loadInitialFile(projectPath: String) {
        if (projectPath != currentProjectPath) {
            closeAllFiles()
            currentProjectPath = projectPath
            val indexFile = File(projectPath, "index.html")
            if (indexFile.exists()) openFile(indexFile)
        }
    }

    suspend fun autoSaveProject(context: Context, projectPath: String) {
        withContext(Dispatchers.IO) {
            val modifiedFiles = openFiles.filterIsInstance<CodeEditorState>().filter { it.isModified }
            if (modifiedFiles.isNotEmpty()) {
                modifiedFiles.forEach { state ->
                    try {
                        state.file.writeText(state.content)
                        state.onContentSaved()
                    } catch (_: Exception) {}
                }
                BackupUtils.backupProject(context, projectPath)
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

    fun onInitialLoaderShown() { hasShownInitialLoader = true }
    fun updateLastBuild(path: String?) { lastBuiltApk = if (path != null) File(path) else null }

    fun updateEditorTheme(seedColor: Color, isDark: Boolean) {
        editorInstances.values.forEach { editor ->
            EditorColorSchemeManager.applyThemeColors(editor.colorScheme, seedColor, isDark)
            if (isDark) {
                editor.colorScheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFF1E1F22.toInt())
                editor.colorScheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, 0xFFFFFFFF.toInt())
            } else {
                editor.colorScheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFFF2F2F2.toInt())
                editor.colorScheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, 0xFF000000.toInt())
            }
            if (editor.editorLanguage is TsLanguage) configureRainbowColors(editor.colorScheme)
            editor.invalidate()
        }
    }

    fun createNewItem(parentPath: String, name: String, isFile: Boolean, onSuccess: (File) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newItem = File(parentPath, name)
                if (newItem.exists()) return@launch
                val success = if (isFile) newItem.createNewFile() else newItem.mkdirs()
                if (success) withContext(Dispatchers.Main) { onSuccess(newItem) }
            } catch (e: Exception) { LogCatcher.e("FileOps", "Create failed", e) }
        }
    }

    fun updateRenamedFile(oldFile: File, newFile: File) {
        val index = openFiles.indexOfFirst { it.file.absolutePath == oldFile.absolutePath }
        if (index != -1) {
            val oldTab = openFiles[index]
            if (oldTab is CodeEditorState) {
                val newState = oldTab.copy(file = newFile)
                newState.content = oldTab.content
                newState.savedContent = oldTab.savedContent
                newState.lspEditor = null

                val mutableList = openFiles.toMutableList()
                mutableList[index] = newState
                openFiles = mutableList

                val oldEditor = editorInstances.remove(oldFile.absolutePath)
                if (oldEditor != null) {
                    editorInstances[newFile.absolutePath] = oldEditor
                }
            }
        }
    }

    fun searchText(query: String, ignoreCase: Boolean = isIgnoreCase) {
        lastSearchQuery = query
        isIgnoreCase = ignoreCase
        val editor = getActiveEditor() ?: return
        if (query.isNotEmpty()) editor.searcher.search(query, EditorSearcher.SearchOptions(ignoreCase, false))
        else editor.searcher.stopSearch()
    }
    fun searchNext() { getActiveEditor()?.searcher?.gotoNext() }
    fun searchPrev() { getActiveEditor()?.searcher?.gotoPrevious() }
    fun replaceCurrent(text: String) { getActiveEditor()?.searcher?.replaceCurrentMatch(text) }
    fun replaceAll(text: String) { getActiveEditor()?.searcher?.replaceAll(text) }
    fun stopSearch() { getActiveEditor()?.searcher?.stopSearch() }

    fun formatCode() {
        if (isFormatting) return
        isFormatting = true
        val editor = getActiveEditor() ?: return
        val ext = openFiles[activeFileIndex].file.extension
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val original = editor.text.toString()
                val formatted = com.web.webide.core.utils.CodeFormatter.format(original, ext, editorConfig.tabWidth)
                if (formatted != original) {
                    withContext(Dispatchers.Main) {
                        editor.setText(formatted)
                        (openFiles[activeFileIndex] as CodeEditorState).content = formatted
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            finally { isFormatting = false }
        }
    }

    fun getCursorPosition(): Pair<Int, Int> {
        val editor = getActiveEditor() ?: return Pair(1, 1)
        val cursor = editor.cursor
        return Pair(cursor.leftLine + 1, cursor.leftColumn + 1)
    }

    private fun setupLspForEditor(context: Context, state: CodeEditorState, editor: CodeEditor, textMateLanguage: TextMateLanguage?) {
        val fileExtension = state.file.extension.lowercase()
        if (fileExtension !in listOf("html", "htm", "css", "js", "javascript")) return
        val prefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("editor_lsp_enabled", false) || textMateLanguage == null) return

        try {
            if (lspProject == null) {
                val projectPath = File(context.filesDir, "lsp_workspace").apply { mkdirs() }.absolutePath
                lspProject = LspProject(projectPath)
                lspProject!!.init()
            }
            val fileName = "editor_${System.identityHashCode(state)}_${System.currentTimeMillis()}.${fileExtension}"
            val project = lspProject!!
            val realFile = File(project.projectUri.path, fileName)
            if (!realFile.exists()) realFile.writeText(state.content)

            if (!addedLspDefinitions.contains(fileExtension)) {
                // 🔥 修复报错: 使用具名参数调用
                val def = when(fileExtension) {
                    "html", "htm" -> CustomLanguageServerDefinition(ext = "html", serverConnectProvider = { ProotStreamConnectionProvider(context, listOf("vscode-html-language-server", "--stdio")) })
                    "css" -> CustomLanguageServerDefinition(ext = "css", serverConnectProvider = { ProotStreamConnectionProvider(context, listOf("vscode-css-language-server", "--stdio")) })
                    "js", "javascript" -> CustomLanguageServerDefinition(ext = "js", serverConnectProvider = { ProotStreamConnectionProvider(context, listOf("typescript-language-server", "--stdio")) })
                    else -> null
                }
                if (def != null) { project.addServerDefinition(def); addedLspDefinitions.add(fileExtension) }
            }

            val lspEditor = project.getOrCreateEditor(realFile.absolutePath)
            lspEditor.wrapperLanguage = textMateLanguage
            lspEditor.editor = editor
            state.lspEditor = lspEditor
            viewModelScope.launch(Dispatchers.IO) { try { lspEditor.connect() } catch (_: Exception){} }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onCleared() {
        super.onCleared()
        closeAllFiles()
    }
}