package com.web.webide.ui.editor.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.web.webide.ui.editor.viewmodel.EditorViewModel
import com.web.webide.ui.terminal.TerminalScreen

enum class PanelPage(val title: String) {
    BUILD_LOG("构建"),
    DIAGNOSTICS("问题"),
    GIT("Git"),
    TERMINAL("终端")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorPanelLayout(
    viewModel: EditorViewModel,
    symbols: List<String>,
    modifier: Modifier = Modifier, // 接收父级传递的 Modifier，用于设置高度
    // 接收父级传递的 Modifier，用于设置高度
    // 默认露出高度稍微加一点，给导航栏留余地，或者由外部控制
    peekHeight: Dp = 76.dp,
    content: @Composable () -> Unit
) {
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = true
        )
    )

    val isExpanded = scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded
    var selectedTabIndex by remember { mutableIntStateOf(3) }
    val tabs = PanelPage.values()

    // 1. 使用 BoxWithConstraints 获取当前可用空间（即 TopBar 下方到屏幕底部的距离）
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 这是关键：获取父容器允许的最大高度
        val layoutMaxHeight = maxHeight

        BottomSheetScaffold(
            modifier = modifier.fillMaxSize(),
            scaffoldState = scaffoldState,
            sheetShape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContentColor = MaterialTheme.colorScheme.onSurface,
            sheetShadowElevation = 8.dp,
            sheetDragHandle = null,
            // 确保 peek 高度足够显示 Tab 栏，如果底部有手势条，可能需要加上 system bars 的高度
            sheetPeekHeight = peekHeight,
            sheetContent = {
                // 2. 强制 Sheet 的高度等于父容器高度
                // 这样展开时，它会正好填满 TopBar 下方的空间
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(layoutMaxHeight) // <--- 核心修改：使用精确高度
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // 移除 navigationBarsPadding()，防止背景被切掉
                            // 改用 imePadding 处理键盘遮挡
                           // .imePadding()
                    ) {
                        // --- 拖动手柄 ---
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp).height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            )
                        }

                        // --- 符号栏 ---
                        AnimatedVisibility(
                            visible = !isExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            SymbolBarRow(symbols) { viewModel.insertSymbol(it) }
                        }

                        // --- Tab 栏 ---
                        TabRow(
                            selectedTabIndex = selectedTabIndex,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                            divider = {},
                            indicator = { tabPositions ->
                                if (selectedTabIndex < tabPositions.size) {
                                    TabRowDefaults.SecondaryIndicator(
                                        Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.height(48.dp)
                        ) {
                            tabs.forEachIndexed { index, page ->
                                Tab(
                                    selected = selectedTabIndex == index,
                                    onClick = { selectedTabIndex = index },
                                    text = { Text(page.title) }
                                )
                            }
                        }

                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                        // --- 内容区域 ---
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (tabs[selectedTabIndex]) {
                                PanelPage.BUILD_LOG -> PlaceholderInfo("构建日志")
                                PanelPage.DIAGNOSTICS -> PlaceholderInfo("诊断信息")
                                PanelPage.GIT -> PlaceholderInfo("Git 面板")
                                PanelPage.TERMINAL -> TerminalScreen()
                            }
                        }

                        // --- 3. 底部导航栏占位 (手动处理) ---
                        // 我们不让 Column 自动 padding，而是加一个 Spacer。
                        // 这样 Sheet 的背景色会延伸到屏幕最底部，但内容不会被小白条挡住。
                        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        ) { innerPadding ->
            // 这里是编辑器内容区域
            Box(modifier = Modifier.padding(innerPadding)) {
                content()
            }
        }
    }
}

@Composable
fun SymbolBarRow(symbols: List<String>, onSymbolClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth().height(48.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(8.dp))
        symbols.forEach { symbol ->
            Box(
                modifier = Modifier
                    .clickable { onSymbolClick(symbol) }
                    .padding(horizontal = 14.dp).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = symbol, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}

@Composable
fun PlaceholderInfo(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}