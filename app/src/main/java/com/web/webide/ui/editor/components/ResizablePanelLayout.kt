

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
/*
 * ResizablePanelLayout.kt
 */
package com.web.webide.ui.editor.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.web.webide.ui.editor.viewmodel.CodeEditorState
import com.web.webide.ui.editor.viewmodel.EditorViewModel

enum class PanelPage(val title: String) {
    BUILD_LOG("构建"),
    DIAGNOSTICS("问题"),
}

@SuppressLint("FrequentlyChangingValue")
@Suppress("DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EditorPanelLayout(
    viewModel: EditorViewModel,
    symbols: List<String>,
    modifier: Modifier = Modifier,
    peekHeight: Dp = 86.dp,
    content: @Composable () -> Unit
) {
    val hasActiveEditor = viewModel.openFiles.isNotEmpty()
    val density = LocalDensity.current
    val minPeekHeightDp = if (hasActiveEditor) peekHeight else 50.dp
    val animatedMinPeekHeight by animateDpAsState(targetValue = minPeekHeightDp, animationSpec = tween(durationMillis = 300), label = "MinPeekHeight")

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layoutHeight = constraints.maxHeight.toFloat()
        val minPeekHeightPx = with(density) { animatedMinPeekHeight.toPx() }
        var panelHeightPx by remember { mutableFloatStateOf(minPeekHeightPx) }

        LaunchedEffect(minPeekHeightPx) {
            if (panelHeightPx < minPeekHeightPx + 100) {
                panelHeightPx = minPeekHeightPx
            }
        }

        fun clampHeight(newHeight: Float): Float = newHeight.coerceIn(minPeekHeightPx, layoutHeight)
        val draggableState = rememberDraggableState { delta -> panelHeightPx = clampHeight(panelHeightPx - delta) }
        val nestedScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val delta = available.y
                    val oldHeight = panelHeightPx
                    panelHeightPx = clampHeight(panelHeightPx - delta)
                    return Offset(0f, oldHeight - panelHeightPx)
                }
            }
        }

        val currentPanelHeightDp = with(density) { panelHeightPx.toDp() }
        val hideThresholdPx = layoutHeight * 0.8f
        val showSymbolBar = hasActiveEditor && (panelHeightPx < hideThresholdPx)
        val contentAlpha = remember(panelHeightPx, minPeekHeightPx) {
            val diff = panelHeightPx - minPeekHeightPx
            val fadeDistance = density.density * 50
            (diff / fadeDistance).coerceIn(0f, 1f)
        }

        BackHandler(enabled = panelHeightPx > minPeekHeightPx) { panelHeightPx = minPeekHeightPx }

        Box(modifier = Modifier.fillMaxSize().padding(bottom = currentPanelHeightDp)) { content() }

        Surface(
            modifier = Modifier.fillMaxWidth().height(currentPanelHeightDp).align(Alignment.BottomCenter).nestedScroll(nestedScrollConnection),
            shape = RoundedCornerShape(0.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().draggable(state = draggableState, orientation = Orientation.Vertical).background(MaterialTheme.colorScheme.surface)) {
                    PanelTopBar(viewModel = viewModel, hasActiveEditor = hasActiveEditor, showLspAndCursor = showSymbolBar)
                }

                AnimatedVisibility(visible = showSymbolBar, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    SymbolBarRow(symbols) { viewModel.insertSymbol(it) }
                }

                var selectedTabIndex by remember { mutableIntStateOf(0) }
                val tabs = PanelPage.entries.toTypedArray()

                AnimatedVisibility(visible = hasActiveEditor, enter = fadeIn(), exit = fadeOut()) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }

                SecondaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.height(48.dp).alpha(contentAlpha).draggable(state = draggableState, orientation = Orientation.Vertical),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { TabRowDefaults.SecondaryIndicator(modifier = Modifier.tabIndicatorOffset(selectedTabIndex), color = MaterialTheme.colorScheme.primary) },
                    divider = { },
                    tabs = {
                        tabs.forEachIndexed { index, page ->
                            Tab(selected = selectedTabIndex == index, onClick = { selectedTabIndex = index }, text = { Text(page.title) }, enabled = contentAlpha > 0.5f)
                        }
                    }
                )

                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface).alpha(contentAlpha)) {
                    when (tabs[selectedTabIndex]) {
                        PanelPage.BUILD_LOG -> PlaceholderInfo("构建日志")
                        PanelPage.DIAGNOSTICS -> PlaceholderInfo("诊断信息")
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelTopBar(
    viewModel: EditorViewModel,
    hasActiveEditor: Boolean,
    showLspAndCursor: Boolean
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("WebIDE_Editor_Settings", Context.MODE_PRIVATE) }
    val lspEnabled = prefs.getBoolean("editor_lsp_enabled", false)

    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 16.dp).height(24.dp)
    ) {
        AnimatedVisibility(
            visible = hasActiveEditor && lspEnabled && showLspAndCursor,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // 🔥 修复报错: 先检查是否是 CodeEditorState 再访问 lspEditor
                val activeTab = viewModel.openFiles.getOrNull(viewModel.activeFileIndex)
                val lspConnected = if (activeTab is CodeEditorState) activeTab.lspEditor != null else false

                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (lspConnected) Color(0xFF4CAF50) else Color(0xFFF44336)))
                Text(text = if (lspConnected) "LSP Success" else "LSP Error", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Box(modifier = Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)).align(Alignment.Center))

        AnimatedVisibility(
            visible = hasActiveEditor && showLspAndCursor,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            var cursorPosition by remember { mutableStateOf(Pair(1, 1)) }
            LaunchedEffect(viewModel.activeFileIndex) {
                while (true) {
                    cursorPosition = viewModel.getCursorPosition()
                    kotlinx.coroutines.delay(100)
                }
            }
            Text(text = "Ln ${cursorPosition.first}, Col ${cursorPosition.second}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SymbolBarRow(symbols: List<String>, onSymbolClick: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(8.dp))
        symbols.forEach { symbol ->
            Box(modifier = Modifier.clickable { onSymbolClick(symbol) }.padding(horizontal = 14.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
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