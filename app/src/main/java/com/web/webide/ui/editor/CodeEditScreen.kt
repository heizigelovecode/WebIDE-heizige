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


package com.web.webide.ui.editor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.web.webide.R
import com.web.webide.build.ApkInstaller
import com.web.webide.core.utils.LogCatcher
import com.web.webide.core.utils.WorkspaceManager
import com.web.webide.files.FileTree
import com.web.webide.safeNavigate
import com.web.webide.ui.components.ColorPickerDialog
import com.web.webide.ui.components.colorToHex
import com.web.webide.ui.editor.aicoding.AICodingPanel
import com.web.webide.ui.editor.components.EditorPanelLayout
import com.web.webide.ui.editor.components.EditorToolbar
import com.web.webide.ui.editor.components.JumpLinePanel
import com.web.webide.ui.editor.components.SearchPanel
import com.web.webide.ui.editor.git.GitPanel
import com.web.webide.ui.editor.git.GitViewModel
import com.web.webide.ui.editor.git.SidebarTab.*
import com.web.webide.ui.editor.viewmodel.EditorViewModel
import com.web.webide.ui.terminal.AlpineManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 构建结果状态
sealed class BuildResultState {
    data class Finished(val message: String, val apkPath: String? = null) : BuildResultState()
}

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditScreen(folderName: String, navController: NavController, viewModel: EditorViewModel) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isMoreMenuExpanded by remember { mutableStateOf(false) }
    val workspacePath = WorkspaceManager.getWorkspacePath(context)
    val projectPath = File(workspacePath, folderName).absolutePath
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val gitViewModel: GitViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    // 初始化 Git (加载配置等)
    LaunchedEffect(projectPath) {
        gitViewModel.initialize(projectPath)
    }

    // 1. 定义状态来持有自动保存的时间间隔
    var autoSaveInterval by remember { mutableLongStateOf(0L) }
    // 🔥 优化：在初始化时直接读取 SharedPreferences，避免闪烁
    var isAiEnabled by remember {
        val editorPrefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
        mutableStateOf(editorPrefs.getBoolean("editor_ai_enabled", true))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    // 2. 监听生命周期：当从设置页返回时，重新读取 SharedPreferences
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val prefs = context.getSharedPreferences("WebIDE_Settings", Context.MODE_PRIVATE)
                autoSaveInterval = prefs.getLong("auto_save_interval", 0L)

                val editorPrefs = context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE)
                isAiEnabled = editorPrefs.getBoolean("editor_ai_enabled", true)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(projectPath) {
        viewModel.loadInitialFile(projectPath)
        // 这样即使之后跳转终端不传参，AlpineManager 也能知道当前是在哪个项目
        AlpineManager.currentProject = projectPath
    }
    LaunchedEffect(autoSaveInterval) {
        if (autoSaveInterval > 0) {
            while (isActive) {
                delay(autoSaveInterval)
                // 执行自动保存
                viewModel.autoSaveProject(context, projectPath)

                // ➡️ 顺便刷新 Git 状态！(实现实时更新的关键)
                gitViewModel.refreshAll()
            }
        }
    }
    val focusManager = LocalFocusManager.current
    // 🔥 进阶：自动检测该项目下是否存在已构建的 APK
    LaunchedEffect(projectPath) {
        // 如果 ViewModel 里还没记录（比如刚打开APP），尝试去硬盘找最新的 release 包
        if (viewModel.lastBuiltApk == null) {
            val buildDir = File(projectPath, "build")
            if (buildDir.exists() && buildDir.isDirectory) {
                // 找 build 目录下以 _release.apk 结尾且最新的文件
                val lastApk = buildDir.listFiles()
                    ?.filter { it.name.endsWith("_release.apk") }
                    ?.maxByOrNull { it.lastModified() }

                if (lastApk != null) {
                    viewModel.updateLastBuild(lastApk.absolutePath)
                }
            }
        }
    }


    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }
    // 加载配置
    LaunchedEffect(Unit) {
        viewModel.reloadEditorConfig(context)
    }
    val editorConfig = viewModel.editorConfig

    // 初始加载进度条状态
    var showInitialLoader by remember { mutableStateOf(!viewModel.hasShownInitialLoader) }
    // 构建过程中的进度条状态
    var isBuilding by remember { mutableStateOf(false) }
    val hasOpenFiles = viewModel.openFiles.isNotEmpty()
    // 构建结果状态
    var buildResult by remember { mutableStateOf<BuildResultState?>(null) }

    // 检测 webapp.json 和 src/main/assets/ 是否同时存在
    val hasWebAppConfig = remember(projectPath) {
        val configFile = File(projectPath, "webapp.json")
        val assetsDir = File(projectPath, "src/main/assets")
        configFile.exists() && assetsDir.exists() && assetsDir.isDirectory
    }

    var isOpenSearch by remember { mutableStateOf(false) }
    var currentSearchText by remember { mutableStateOf("") }
    LaunchedEffect(viewModel.activeFileIndex) {
        if (isOpenSearch && currentSearchText.isNotEmpty()) {
            delay(200)
            viewModel.searchText(currentSearchText)
        }
    }
    var ignoreCaseState by remember { mutableStateOf(true) }
    LaunchedEffect(viewModel.activeFileIndex) {
        if (isOpenSearch && currentSearchText.isNotEmpty()) {
            viewModel.searchText(currentSearchText, ignoreCaseState)
        }
    }

    var isOpenJump by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    LaunchedEffect(folderName) {
        if (viewModel.openFiles.isNotEmpty()) {
            val firstFile = viewModel.openFiles.first().file
            if (!firstFile.absolutePath.startsWith(projectPath)) {
                viewModel.closeAllFiles()
            }
        }
    }

    LaunchedEffect(projectPath) {
        viewModel.loadInitialFile(projectPath)
    }

    LaunchedEffect(Unit) {
        if (showInitialLoader) {
            delay(500L)
            showInitialLoader = false
            viewModel.onInitialLoaderShown()
        }
    }
    var selectedTab by remember { mutableStateOf(FILES) }
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    // 2. 计算动态宽度
    // 逻辑：如果是平板(宽>600dp)，侧边栏给 400dp；如果是手机，给屏幕宽度的 85%
    val drawerWidth = if (screenWidth > 600.dp) 400.dp else (screenWidth * 0.85f)

    DismissibleNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            // --- 修改：增加宽度以容纳 NavigationRail ---
            ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // --- 左侧：NavigationRail ---
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface, // 与Drawer背景融合
                        modifier = Modifier.width(80.dp) // 固定Rail宽度
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        // 1. 文件树按钮
                        NavigationRailItem(
                            selected = selectedTab == FILES,
                            onClick = { selectedTab = FILES },
                            icon = { Icon(Icons.Default.Folder, contentDescription = "文件") },
                            label = { Text("文件树") }
                        )
                        // 2. Git 按钮
                        NavigationRailItem(
                            selected = selectedTab == GIT,
                            onClick = {
                                selectedTab = GIT
                                gitViewModel.refreshAll()
                                      },
                            icon = {
                                val changeCount = gitViewModel.changedFiles.size
                                BadgedBox(
                                    badge = {
                                        if (changeCount > 0) {
                                            Badge {
                                                // 如果数字太大，显示 99+，否则显示具体数字
                                                Text(if(changeCount > 99) "99+" else changeCount.toString())
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        // 🔥 这里修改：使用 painterResource 加载 xml 资源
                                        painter = painterResource(id = R.drawable.ic_git),
                                        contentDescription = "Git",
                                        modifier = Modifier.size(24.dp) // 建议加上尺寸限制，防止图标过大
                                    )
                                }
                            },
                            label = { Text("Git") }
                        )
                    }

                    // --- 中间分割线 (可选) ---
                    VerticalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // --- 右侧：内容区域 ---
                    Box(modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()) {
                        when (selectedTab) {
                            FILES -> {
                                // 原有的文件管理器
                                FileManagerDrawer(
                                    projectPath = projectPath,
                                    onFileClick = { file ->
                                        viewModel.openFile(file)
                                        scope.launch { drawerState.close() }
                                    },
                                    onFileRenamed = { oldFile, newFile ->
                                        viewModel.updateRenamedFile(oldFile, newFile)
                                        gitViewModel.refreshAll()
                                    }
                                )
                            }
                            GIT -> {
                                // 新增的空 Git 面板
                                GitPanel(
                                    projectPath = projectPath,
                                    viewModel = gitViewModel,
                                    editorViewModel = viewModel // 🔥 加上这一行
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.statusBars,
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("WebIDE", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = folderName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            navigationIcon = {
                                AnimatedDrawerToggle(
                                    isOpen = drawerState.targetValue == DrawerValue.Open,
                                    onClick = {
                                        scope.launch {
                                            if (drawerState.isClosed) drawerState.open() else drawerState.close()
                                        }
                                    }
                                )
                            },
                            actions = {
                                IconButton(onClick = { viewModel.undo() }) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, "撤销")
                                }
                                IconButton(onClick = { viewModel.redo() }) {
                                    Icon(Icons.AutoMirrored.Filled.Redo, "重做")
                                }
                                IconButton(onClick = {
                                    // 🔥 修改：使用一个协程顺序执行
                                    scope.launch {
                                        // 1. 先保存所有文件 (这是一个 suspend 函数，会等待 IO 完成)
                                        viewModel.saveAllModifiedFiles(snackbarHostState)

                                        // 2. 保存完成后，再跳转 (Undo 栈不会丢失，因为 Editor 实例没变)
                                        navController.safeNavigate("preview/$folderName")
                                    }
                                }) {
                                    Icon(Icons.Filled.PlayArrow, "运行")
                                }
                                Box {
                                    IconButton(onClick = { isMoreMenuExpanded = true }) {
                                        Icon(Icons.Filled.MoreVert, "更多选项")
                                    }
                                    DropdownMenu(
                                        expanded = isMoreMenuExpanded,
                                        onDismissRequest = { isMoreMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("全部保存") },
                                            onClick = {
                                                scope.launch {
                                                    viewModel.saveAllModifiedFiles(snackbarHostState)
                                                }
                                                gitViewModel.refreshAll() // <--- 加这一行
                                                isMoreMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (isOpenSearch) "关闭搜索" else "搜索") },
                                            onClick = {
                                                if (isOpenSearch) viewModel.stopSearch()
                                                isOpenSearch = !isOpenSearch
                                                isMoreMenuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("终端") },
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                // 🔥 跳转到独立的终端页面
                                                navController.navigate("terminal")
                                            }
                                        )

                                        // 只有文件存在且路径属于当前项目(可选逻辑)时才显示
                                        if (viewModel.lastBuiltApk != null && viewModel.lastBuiltApk!!.exists()) {
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text("安装上次构建的 APK")
                                                        Text(
                                                            text = "版本: ${viewModel.lastBuiltApk!!.name}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.secondary
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    isMoreMenuExpanded = false
                                                    // 调用安装器
                                                    ApkInstaller.install(context, viewModel.lastBuiltApk!!)
                                                }
                                            )
                                            HorizontalDivider() // 加个分割线好看点
                                        }

                                        if (hasWebAppConfig) {
                                            DropdownMenuItem(
                                                text = { Text("构建 APK") },
                                                enabled = !isBuilding,
                                                onClick = {
                                                    isMoreMenuExpanded = false
                                                    scope.launch {
                                                        isBuilding = true
                                                        performBuild(
                                                            context = context,
                                                            projectPath = projectPath,
                                                            folderName = folderName,
                                                            viewModel = viewModel,
                                                            snackbarHostState = snackbarHostState,
                                                            onResult = { resultState ->
                                                                buildResult = resultState
                                                                isBuilding = false
                                                                // 🔥 新增：如果构建成功，保存路径到 ViewModel
                                                                if (resultState is BuildResultState.Finished && resultState.apkPath != null) {
                                                                    viewModel.updateLastBuild(resultState.apkPath)
                                                                }
                                                            }
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        // 工具栏 (根据配置显示)
                        if (editorConfig.showToolbar) {
                            EditorToolbar(
                                onSave = {
                                    scope.launch {
                                        viewModel.saveAllModifiedFiles(
                                            snackbarHostState
                                        )
                                    }
                                    gitViewModel.refreshAll() // <--- 加这一行
                                },
                                onSearch = {
                                    isOpenSearch = !isOpenSearch
                                    isOpenJump = false
                                },
                                onJump = {
                                    isOpenJump = !isOpenJump
                                    isOpenSearch = false
                                },
                                onFormat = { viewModel.formatCode() },
                                onCreate = { showCreateDialog = true },
                                onPalette = { showColorPicker = true },
                                isBuilding = isBuilding,
                                hasWebAppConfig = hasWebAppConfig,
                                onBuild = {
                                    scope.launch {
                                        isBuilding = true
                                        performBuild(
                                            context = context,
                                            projectPath = projectPath,
                                            folderName = folderName,
                                            viewModel = viewModel,
                                            snackbarHostState = snackbarHostState,
                                            onResult = { resultState ->
                                                buildResult = resultState
                                                isBuilding = false
                                            }
                                        )
                                    }
                                },
                            )
                        }

                        // 搜索面板
                        AnimatedVisibility(visible = isOpenSearch) {
                            Column {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                SearchPanel(
                                    viewModel = viewModel,
                                    searchText = currentSearchText,
                                    onSearchTextChange = { currentSearchText = it },
                                    onClose = {
                                        viewModel.stopSearch()
                                        isOpenSearch = false
                                    }
                                )
                            }
                        }
                        AnimatedVisibility(visible = isOpenJump) {
                            Column {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                JumpLinePanel(
                                    onJump = { line -> viewModel.jumpToLine(line) },
                                    onClose = {
                                        isOpenJump = false
                                        viewModel.getActiveEditor()?.requestFocus()
                                    }
                                )
                            }
                        }
                    }
                },

                content = { innerPadding ->
                    BoxWithConstraints(modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                        .imePadding()) {
                        val availableEditorHeight = maxHeight // 这就是 EditorPanelLayout 可以用的全部高度
                        val currentSymbols = if (hasOpenFiles) editorConfig.getSymbolList() else emptyList()

                        EditorPanelLayout(
                            viewModel = viewModel,
                            symbols = currentSymbols,
                            modifier = Modifier.height(availableEditorHeight) // 将精确高度传递给 EditorPanelLayout
                            // 将精确高度传递给 EditorPanelLayout
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                AnimatedVisibility(visible = showInitialLoader || isBuilding) {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        strokeCap = StrokeCap.Butt
                                    )
                                }
                                EditCode(
                                    modifier = Modifier.fillMaxSize(),
                                    viewModel = viewModel,
                                    onShowSearch = { isOpenSearch = true },
                                    snackbarHostState = snackbarHostState,
                                    navController = navController,
                                    folderName = folderName,
                                    onNavigateToTerminal = { navController.safeNavigate("terminal") },
                                    onShowJumpLine = { isOpenJump = true },
                                    onShowCreate = { showCreateDialog = true },
                                    onShowColorPicker = { showColorPicker = true }
                                )
                            }
                        }
                        
                        if (isAiEnabled) {
                            AICodingPanel()
                        }
                    }
                }
            )
        }
        if (drawerState.isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                // 点击时：关闭侧滑栏
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
            )
        }
    }
        // 1. 新建对话框
        if (showCreateDialog) {
            var nameInput by remember { mutableStateOf("") }
            var isFileType by remember { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("新建内容") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = isFileType, onClick = { isFileType = true })
                            Text("文件")
                            Spacer(Modifier.width(16.dp))
                            RadioButton(selected = !isFileType, onClick = { isFileType = false })
                            Text("文件夹")
                        }
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("例test.txt") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (nameInput.isNotBlank()) {
                            viewModel.createNewItem(projectPath, nameInput, isFileType) { newItem ->
                                if (isFileType) viewModel.openFile(newItem)
                                gitViewModel.refreshAll()
                            }
                        }
                        showCreateDialog = false
                    }) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
                }
            )
        }

        // 2. 调色板
        if (showColorPicker) {
            ColorPickerDialog(
                initialColor = MaterialTheme.colorScheme.primary,
                onDismiss = { showColorPicker = false },
                onColorSelected = { color ->
                    val hex = colorToHex(color, color.alpha < 1f)
                    viewModel.insertText(hex)
                    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData.newPlainText("Hex Color", hex)
                    clipboardManager.setPrimaryClip(clipData)
                    showColorPicker = false
                }
            )
        }

        // 3. 构建结果弹窗
        buildResult?.let { result ->
            if (result is BuildResultState.Finished) {
                val isSuccess = result.apkPath != null
                AlertDialog(
                    onDismissRequest = { buildResult = null },
                    title = { Text(if (isSuccess) "构建成功" else "构建失败") },
                    text = {
                        Column {
                            if (isSuccess) {
                                Text("APK 已生成，是否立即安装？")
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("输出路径:", style = MaterialTheme.typography.titleSmall)
                                Text(result.apkPath, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("错误信息：", color = MaterialTheme.colorScheme.error)
                                Text(result.message)
                            }
                        }
                    },
                    confirmButton = {
                        if (isSuccess) {
                            TextButton(onClick = {
                                val apkFile = File(result.apkPath)
                                ApkInstaller.install(context, apkFile)
                                buildResult = null
                            }) { Text("安装") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { buildResult = null }) { Text("关闭") }
                    }
                )
            }
        }
    }


// ---------------- 辅助函数 ----------------



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCode(
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel,
    onShowSearch: () -> Unit,
    snackbarHostState: SnackbarHostState,
    navController: NavController,
    folderName: String,
    onNavigateToTerminal: () -> Unit,
    onShowJumpLine: () -> Unit,
    onShowCreate: () -> Unit,
    onShowColorPicker: () -> Unit
) {
    val openFiles = viewModel.openFiles
    val activeFileIndex = viewModel.activeFileIndex
    val scope = rememberCoroutineScope()
    var expandedTabIndex by remember { mutableStateOf<Int?>(null) }
    val currentFiles by rememberUpdatedState(openFiles)
    val currentIndex by rememberUpdatedState(activeFileIndex)

    val pagerState = rememberPagerState(
        initialPage = activeFileIndex.coerceIn(0, maxOf(0, openFiles.size - 1)),
        pageCount = { currentFiles.size }
    )

    LaunchedEffect(currentIndex, currentFiles.size) {
        if (currentFiles.isNotEmpty() && currentIndex >= 0 && currentIndex < currentFiles.size && pagerState.currentPage != currentIndex) {
            pagerState.scrollToPage(currentIndex)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (currentFiles.isNotEmpty() && page in currentFiles.indices && page != currentIndex) {
                viewModel.changeActiveFileIndex(page)
            }
        }
    }

    Column(modifier = modifier) {
        if (openFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("未打开任何文件")
            }
        } else {
            SecondaryScrollableTabRow(
                selectedTabIndex = pagerState.currentPage.coerceIn(0, openFiles.size - 1),
                edgePadding = 0.dp,
                divider = { },
                indicator = {
                    Box(
                        modifier = Modifier
                            .tabIndicatorOffset(pagerState.currentPage.coerceIn(0, openFiles.size - 1))
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(percent = 50))
                    )
                }
            ) {
                openFiles.forEachIndexed { index, tab ->
                    Box {
                        // 1. 获取标题
                        val displayName = tab.title

                        // 2. 区分颜色 (Diff 模式显示不同颜色)
                        val isDiff = tab is com.web.webide.ui.editor.viewmodel.DiffEditorState
                        val tabColor = if (isDiff) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface

                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                if (pagerState.currentPage == index) expandedTabIndex = index
                                else scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            text = {
                                Text(
                                    text = displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else tabColor
                                )
                            }
                        )

                        DropdownMenu(
                            expanded = expandedTabIndex == index,
                            onDismissRequest = { expandedTabIndex = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text("关闭") },
                                onClick = { expandedTabIndex = null; viewModel.closeFile(index) }
                            )
                            DropdownMenuItem(
                                text = { Text("关闭其他") },
                                onClick = { expandedTabIndex = null; viewModel.closeOtherFiles(index) }
                            )
                            DropdownMenuItem(
                                text = { Text("关闭全部") },
                                onClick = { expandedTabIndex = null; viewModel.closeAllFiles() }
                            )
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = false,
                key = { index -> if (index < openFiles.size) openFiles[index].uniqueId else "empty_$index" }
            ) { page ->
                if (page in openFiles.indices) {
                    val tab = openFiles[page]

                    // 🔥🔥 根据类型渲染组件 🔥🔥
                    when (tab) {
                        is com.web.webide.ui.editor.viewmodel.CodeEditorState -> {
                            com.web.webide.ui.editor.components.CodeEditorView(
                                modifier = Modifier.fillMaxSize(),
                                state = tab,
                                viewModel = viewModel,
                                onShowSearch = onShowSearch,
                                onRun = {
                                    scope.launch {
                                        viewModel.saveAllModifiedFiles(snackbarHostState)
                                        navController.safeNavigate("preview/$folderName")
                                    }
                                },
                                onNavigateToTerminal = onNavigateToTerminal,
                                onShowJumpLine = onShowJumpLine,
                                onShowCreate = onShowCreate,
                                onShowColorPicker = onShowColorPicker
                            )
                        }
                        is com.web.webide.ui.editor.viewmodel.DiffEditorState -> {
                            com.web.webide.ui.editor.components.DiffViewer(
                                viewModel = viewModel,
                                state = tab,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

@Composable
fun FileManagerDrawer(projectPath: String, onFileClick: (File) -> Unit,onFileRenamed: (File, File) -> Unit ) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "文件树",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )
        FileTree(
            rootPath = projectPath,
            modifier = Modifier.fillMaxSize(),
            onFileClick = onFileClick,
            onFileRenamed = onFileRenamed
        )
    }
}





@Composable
fun AnimatedDrawerToggle(
    isOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 0f = 菜单, 1f = 箭头
    val progress by animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        label = "DrawerToggleProgress",
        animationSpec = tween(durationMillis = 300)
    )

    IconButton(onClick = onClick, modifier = modifier) {
        val color = LocalContentColor.current

        Canvas(
            modifier = Modifier.size(24.dp)
        ) {
            val strokeWidth = 2.dp.toPx()
            val cap = StrokeCap.Square // 保持直角

            val w = size.width
            val h = size.height
            val centerX = w / 2
            val centerY = h / 2

            // --- 1. 菜单状态参数 (宽一点) ---
            val menuWidth = 18.dp.toPx() // 菜单总宽 18dp (标准 Material 尺寸)
            val startX = (w - menuWidth) / 2
            val endX = w - startX
            val menuGap = 6.dp.toPx() // 汉堡菜单间距

            // --- 2. 箭头状态参数 (短一点) ---
            // 箭头尖端 X (保持在 endX，即最右侧，作为旋转中心)
            val arrowTipX = endX

            // 缩短箭头的关键：
            // 让箭头的“尾巴”比菜单的“左边”更靠右
            val shrinkOffset = 3.dp.toPx() // 缩进量
            val arrowShaftStartX = startX + shrinkOffset

            // 箭头翅膀的起始 X (决定翅膀长度)
            // 稍微往右移一点，让翅膀不要太长
            val arrowWingStartX = centerX + 1.dp.toPx()

            // 计算直角(90度)所需的 Y 偏移量
            // 翅膀长度在 X轴的投影 = arrowTipX - arrowWingStartX
            val arrowWingYOffset = (arrowTipX - arrowWingStartX)

            rotate(degrees = progress * 180f, pivot = Offset(centerX, centerY)) {

                // --- 中间线 (轴) ---
                // 菜单: startX -> endX
                // 箭头: arrowShaftStartX -> endX (变短了)
                val midStartX = lerp(startX, arrowShaftStartX, progress)
                drawLine(
                    color = color,
                    strokeWidth = strokeWidth,
                    cap = cap,
                    start = Offset(midStartX, centerY),
                    end = Offset(endX, centerY)
                )

                // --- 上面的线 ---
                // 菜单: 左侧 -> 右侧
                // 箭头: 翅膀末端 -> 尖端
                val topStartX = lerp(startX, arrowWingStartX, progress)
                val topStartY = lerp(centerY - menuGap, centerY - arrowWingYOffset, progress)

                val topEndX = lerp(endX, arrowTipX, progress) // 始终固定在右侧
                val topEndY = lerp(centerY - menuGap, centerY, progress) // 右侧下沉到中心

                drawLine(
                    color = color,
                    strokeWidth = strokeWidth,
                    cap = cap,
                    start = Offset(topStartX, topStartY),
                    end = Offset(topEndX, topEndY)
                )

                // --- 下面的线 ---
                val bottomStartX = lerp(startX, arrowWingStartX, progress)
                val bottomStartY = lerp(centerY + menuGap, centerY + arrowWingYOffset, progress)

                val bottomEndX = lerp(endX, arrowTipX, progress)
                val bottomEndY = lerp(centerY + menuGap, centerY, progress) // 右侧上浮到中心

                drawLine(
                    color = color,
                    strokeWidth = strokeWidth,
                    cap = cap,
                    start = Offset(bottomStartX, bottomStartY),
                    end = Offset(bottomEndX, bottomEndY)
                )
            }
        }
    }
}



private suspend fun performBuild(
    context: Context,
    projectPath: String,
    folderName: String,
    viewModel: EditorViewModel,
    snackbarHostState: SnackbarHostState,
    onResult: (BuildResultState) -> Unit
) {
    viewModel.saveAllModifiedFiles(snackbarHostState)
    val prefs = context.getSharedPreferences("WebIDE_Project_Settings", Context.MODE_PRIVATE)
    val isDebug = prefs.getBoolean("debug_$folderName", false)

    val configFile = File(projectPath, "webapp.json")

    // 默认基础配置
    var pkg = "com.example.webapp"
    var verName = "1.0"
    var verCode = "1"
    var iconPath = ""
    var permissions: Array<String>? = null

    // 🔥 新增：签名变量初始化
    var customKeyPath: String? = null
    var customStorePass: String? = null
    var customAlias: String? = null
    var customKeyPass: String? = null

    // 加密配置 (默认为 true)
    var enableEncryption = true

    if (configFile.exists()) {
        try {
            var jsonStr = withContext(Dispatchers.IO) {
                configFile.readText()
            }
            // 过滤注释
            jsonStr = jsonStr.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

            val json = org.json.JSONObject(jsonStr)

            // 1. 基础信息解析 (保持原有)
            pkg = json.optString("package", pkg)
            verName = json.optString("versionName", verName)
            verCode = json.optString("versionCode", verCode)
            enableEncryption = json.optBoolean("encryption", true)

            val iconName = json.optString("icon", "")
            if (iconName.isNotEmpty()) {
                val iconFile = File(projectPath, iconName)
                if (iconFile.exists()) iconPath = iconFile.absolutePath
            }

            val jsonPerms = json.optJSONArray("permissions")
            if (jsonPerms != null && jsonPerms.length() > 0) {
                val list = ArrayList<String>()
                for (i in 0 until jsonPerms.length()) {
                    list.add(jsonPerms.getString(i))
                }
                permissions = list.toTypedArray()
            }

            // 🔥 2. 解析签名配置 (新增逻辑)
            val signingObj = json.optJSONObject("signing")
            if (signingObj != null) {
                val keyFileName = signingObj.optString("keystore")
                if (keyFileName.isNotEmpty()) {
                    // 拼接完整路径：项目目录 + key文件名
                    val keyFile = File(projectPath, keyFileName)
                    // 只有文件存在时才传递路径，否则 ApkBuilder 会回退到默认
                    if (keyFile.exists()) {
                        customKeyPath = keyFile.absolutePath
                        customStorePass = signingObj.optString("storePassword")
                        customAlias = signingObj.optString("alias")
                        // 如果别名密码为空，通常默认与库密码相同，或者尝试读取 keyPassword
                        customKeyPass = signingObj.optString("keyPassword", customStorePass)
                    } else {
                        LogCatcher.w("Build", "webapp.json 中指定了 keystore 但文件未找到: $keyFileName")
                    }
                }
            }

        } catch (e: Exception) {
            LogCatcher.e("Build", "JSON Error", e)
            onResult(BuildResultState.Finished("webapp.json 格式错误: ${e.message}"))
            return
        }
    }

    val result = withContext(Dispatchers.IO) {
        com.web.webide.build.ApkBuilder.bin(
            context,
            WorkspaceManager.getWorkspacePath(context),
            projectPath,
            folderName,
            pkg,
            verName,
            verCode,
            iconPath,
            permissions,
            isDebug,
            enableEncryption, // 🔥 传入加密配置
            // 🔥 传入解析出的签名参数 (如果上面没解析到，这些就是 null)
            customKeyPath,
            customStorePass,
            customAlias,
            customKeyPass
        )
    }

    if (result.startsWith("error:")) {
        onResult(BuildResultState.Finished(result, null))
    } else {
        onResult(BuildResultState.Finished("构建成功", result))
    }
}