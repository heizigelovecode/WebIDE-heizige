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

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.luminance
import com.web.webide.ui.editor.EditorColorSchemeManager
import com.web.webide.ui.editor.viewmodel.DiffEditorState
import com.web.webide.ui.editor.viewmodel.DiffViewMode
import com.web.webide.ui.editor.viewmodel.EditorViewModel
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.event.TextSizeChangeEvent
import java.util.LinkedList
import kotlin.math.max

// ==========================================
// 1. 核心逻辑：Wake + Lock 同步器 (Ultimate Fix)
// ==========================================
// 核心修正版 Synchronizer
// ==========================================
// 1. 核心逻辑：纯净事件镜像 (Butter Smooth Version)
// ==========================================

class ScrollSynchronizer {
    private var leftEditor: CodeEditor? = null
    private var rightEditor: CodeEditor? = null
    private val receipts = ArrayList<SubscriptionReceipt<*>>()

    // 0 = 无/未知, 1 = 左控右, 2 = 右控左
    // 默认为 0，只有当用户触摸某一边时，该边才成为 Driver
    private var activeDriver = 0

    fun setEditors(left: CodeEditor?, right: CodeEditor?) {
        if (this.leftEditor === left && this.rightEditor === right) return
        unbind()
        this.leftEditor = left
        this.rightEditor = right
        bindEvents()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindEvents() {
        val left = leftEditor ?: return
        val right = rightEditor ?: return

        // 开启缩放功能
        left.isScalable = true
        right.isScalable = true

        // 1. Touch 监听：确立 Driver 身份，并终止对方的惯性
        val touchListener = { id: Int, other: CodeEditor ->
            android.view.View.OnTouchListener { _, event ->
                // ACTION_DOWN 确立主控方
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    activeDriver = id
                    // 立即停止对方的 Scroller，防止“两个物理引擎打架”
                    // 使用 eventHandler.scroller 以确保访问到正确的内部滚动器
                    val scroller = other.eventHandler.scroller
                    if (!scroller.isFinished) {
                        scroller.forceFinished(true)
                    }
                }
                false // 不消费事件，交给 Editor 内部处理
            }
        }

        left.setOnTouchListener(touchListener(1, right))
        right.setOnTouchListener(touchListener(2, left))

        // 2. Scroll 监听：Master -> Slave 绝对坐标同步
        // 修正：使用 scroller.startScroll() 而非简单的 scrollTo()
        // 原因：用户反馈“editor本身在移动”，这通常是因为直接调用 View.scrollTo() 导致
        // View 的视口位置与 Editor 内部 Scroller (OverScroller) 的状态不同步。
        // 当用户随后触摸 Slave 编辑器时，Scroller 会从旧位置（通常是 0）开始，导致跳变。
        // 使用 startScroll(x, y, 0, 0, 0) 可以同时更新 View 位置和 Scroller 状态，
        // 实现真正的“内部滚动操作”同步。
        val scrollListener = { id: Int, target: CodeEditor ->
            android.view.View.OnScrollChangeListener { _, scrollX, scrollY, _, _ ->
                if (activeDriver == id) {
                    val scroller = target.eventHandler.scroller
                    // 只有当位置真正改变时才同步，避免循环调用（虽然 activeDriver 已防护）
                    // 注意：这里我们强制同步 Scroller 的状态
                    if (scroller.currX != scrollX || scroller.currY != scrollY) {
                        // duration = 0 表示瞬时跳转，但更新了 Scroller 内部状态
                        scroller.startScroll(scrollX, scrollY, 0, 0, 0)
                        // 通知编辑器已滚动，触发滚动条绘制等副作用
                        target.eventHandler.notifyScrolled()
                    }
                }
            }
        }

        left.setOnScrollChangeListener(scrollListener(1, right))
        right.setOnScrollChangeListener(scrollListener(2, left))

        // 3. Zoom 监听：同步缩放比例
        val zoomListener = { id: Int, target: CodeEditor ->
            io.github.rosemoe.sora.event.EventReceiver<TextSizeChangeEvent> { event, _ ->
                if (activeDriver == id) {
                    if (target.textSizePx != event.newTextSize) {
                        target.setTextSizePx(event.newTextSize)
                    }
                }
            }
        }

        receipts.add(left.subscribeEvent(TextSizeChangeEvent::class.java, zoomListener(1, right)))
        receipts.add(right.subscribeEvent(TextSizeChangeEvent::class.java, zoomListener(2, left)))
    }

    private fun unbind() {
        leftEditor?.setOnTouchListener(null)
        leftEditor?.setOnScrollChangeListener(null)
        rightEditor?.setOnTouchListener(null)
        rightEditor?.setOnScrollChangeListener(null)
        
        receipts.forEach { it.unsubscribe() }
        receipts.clear()
        
        // SoraEditor 的事件订阅系统没有直接的 "unsubscribeAll"，但我们重新创建实例时会丢弃旧对象
        // 如果要严谨，应该保存 SubscriptionReceipt 并取消订阅，但这里我们简化处理，
        // 依赖 Garbage Collection，因为 SubscriptionReceipt 是强引用
        // 更好的做法是保存 receipts 列表并在 unbind 时 unsubscribe
        // 但在这个简单的 Synchronizer 生命周期中，直接置空引用通常足够，
        // 除非 CodeEditor 也是长生命周期的（在这个 Compose 场景中是每次重组可能变化）
    }
}

// ==========================================
// 2. 数据结构
// ==========================================
data class AlignedDiffResult(
    val leftContent: CharSequence,
    val rightContent: CharSequence,
    val adds: Int,
    val deletes: Int
)

// ==========================================
// 3. UI 组件
// ==========================================

@Composable
fun DiffViewer(
    viewModel: EditorViewModel,
    state: DiffEditorState,
    modifier: Modifier = Modifier
) {
    // 异步计算差异
    var diffData by remember(state.originalContent, state.currentContent) {
        mutableStateOf<AlignedDiffResult?>(null)
    }

    LaunchedEffect(state.originalContent, state.currentContent) {
        withContext(Dispatchers.Default) {
            diffData = DiffAligner.align(state.originalContent, state.currentContent)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        DiffToolbar(state, diffData)

        if (diffData == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
                Text("正在计算差异...", modifier = Modifier.padding(top = 48.dp))
            }
        } else {
            val data = diffData!!
            if (state.viewMode == DiffViewMode.SPLIT) {
                SplitDiffView(state, viewModel, data)
            } else {
                UnifiedDiffView(state, viewModel, data)
            }
        }
    }
}

@Composable
fun DiffToolbar(state: DiffEditorState, data: AlignedDiffResult?) {
    val added = data?.adds ?: 0
    val deleted = data?.deletes ?: 0

    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().height(42.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(
                text = state.file.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.width(16.dp))
            if (added > 0) {
                Text("+$added", color = Color(0xFF4CAF50), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
            }
            if (deleted > 0) {
                Text("-$deleted", color = Color(0xFFEF5350), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { state.viewMode = DiffViewMode.SPLIT }) {
                Icon(
                    Icons.Default.ViewColumn, "Split",
                    tint = if (state.viewMode == DiffViewMode.SPLIT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { state.viewMode = DiffViewMode.UNIFIED }) {
                Icon(
                    Icons.AutoMirrored.Filled.ViewList, "Unified",
                    tint = if (state.viewMode == DiffViewMode.UNIFIED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SplitDiffView(
    state: DiffEditorState,
    viewModel: EditorViewModel,
    data: AlignedDiffResult
) {
    // 保持 Synchronizer 实例
    val synchronizer = remember { ScrollSynchronizer() }

    // 使用 ref 引用编辑器，避免 Compose 重组导致对象丢失
    var leftEditorRef by remember { mutableStateOf<CodeEditor?>(null) }
    var rightEditorRef by remember { mutableStateOf<CodeEditor?>(null) }

    // 【重要】当编辑器实例变化时，重新绑定
    DisposableEffect(leftEditorRef, rightEditorRef) {
        if (leftEditorRef != null && rightEditorRef != null) {
            synchronizer.setEditors(leftEditorRef, rightEditorRef)
        }
        onDispose {
            // 组件销毁时可以在这里做清理，或者由 Synchronizer 内部处理
            // synchronizer.unbind()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(halfWidth).fillMaxHeight().clipToBounds()) {
                DiffHeader("HEAD", Color(0xFFD32F2F))
                DiffEditorInstance(
                    content = data.leftContent,
                    fileName = state.file.name,
                    viewModel = viewModel,
                    onEditorCreated = { leftEditorRef = it }
                )
            }
            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.width(halfWidth).fillMaxHeight().clipToBounds()) {
                DiffHeader("Working", Color(0xFF388E3C))
                DiffEditorInstance(
                    content = data.rightContent,
                    fileName = state.file.name,
                    viewModel = viewModel,
                    onEditorCreated = { rightEditorRef = it }
                )
            }
        }
    }
}

@Composable
fun UnifiedDiffView(state: DiffEditorState, viewModel: EditorViewModel, data: AlignedDiffResult) {
    Column(modifier = Modifier.fillMaxSize()) {
        DiffEditorInstance(
            content = data.rightContent,
            fileName = state.file.name,
            viewModel = viewModel,
            onEditorCreated = {}
        )
    }
}

@Composable
fun DiffHeader(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(color, MaterialTheme.shapes.small))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DiffEditorInstance(
    content: CharSequence,
    fileName: String,
    viewModel: EditorViewModel,
    onEditorCreated: (CodeEditor) -> Unit
) {
    val editorConfig = viewModel.editorConfig
    val primaryColor = MaterialTheme.colorScheme.primary
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    AndroidView(
        factory = { ctx ->
            CodeEditor(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // --- 基础 ---
                isEditable = false
                isFocusable = true // 必须开启

                // --- 核心优化 ---
                // 1. 关闭换行：保证每一行的“高度”绝对一致，防止左右行高错位
                isWordwrap = false

                // 2. 移除边缘光晕：防止同步时一边闪蓝光一边不闪，影响视觉流畅度
                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                // 3. 字体强制等宽：这是对齐的基础
                typefaceText = android.graphics.Typeface.MONOSPACE
                typefaceLineNumber = android.graphics.Typeface.MONOSPACE

                // 4. 设置字号和Tab：必须完全一致
                tabWidth = editorConfig.tabWidth

                try {
                    viewModel.applyLanguageToEditor(this, java.io.File(fileName).extension)
                } catch (_: Exception) {}

                EditorColorSchemeManager.applyThemeColors(colorScheme, primaryColor, isDark)

                onEditorCreated(this)
            }
        },
        update = { editor ->
            // 确保每次重组时都更新主题色，以响应系统主题变化
            EditorColorSchemeManager.applyThemeColors(editor.colorScheme, primaryColor, isDark)

            // 只有内容真变了才 Set，防止重置位置
            if (editor.text.toString() != content.toString()) {
                editor.setText(content)
                // 强制刷新一次
                editor.postInvalidate()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ==========================================
// 4. Diff 算法逻辑 (LCS + Padding)
// ==========================================
object DiffAligner {
    private const val COLOR_DELETE_BG = 0x40B71C1C
    private const val COLOR_ADD_BG = 0x401B5E20
    private const val PHANTOM_TEXT = " \n"

    fun align(oldText: String, newText: String): AlignedDiffResult {
        val oldSafe = oldText.ifEmpty { "" }
        val newSafe = newText.ifEmpty { "" }

        val oldLines = oldSafe.lines()
        val newLines = newSafe.lines()

        val m = oldLines.size
        val n = newLines.size

        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                if (oldLines[i - 1] == newLines[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = max(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val leftBuilder = SpannableStringBuilder()
        val rightBuilder = SpannableStringBuilder()

        var i = m
        var j = n
        var adds = 0
        var deletes = 0

        val leftStack = LinkedList<CharSequence>()
        val rightStack = LinkedList<CharSequence>()

        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
                leftStack.push(oldLines[i - 1] + "\n")
                rightStack.push(newLines[j - 1] + "\n")
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                leftStack.push(createPhantomLine())
                rightStack.push(createColoredLine(newLines[j - 1] + "\n", COLOR_ADD_BG))
                adds++
                j--
            } else {
                leftStack.push(createColoredLine(oldLines[i - 1] + "\n", COLOR_DELETE_BG))
                rightStack.push(createPhantomLine())
                deletes++
                i--
            }
        }

        leftStack.forEach { leftBuilder.append(it) }
        rightStack.forEach { rightBuilder.append(it) }

        if (leftBuilder.isNotEmpty() && leftBuilder.last() == '\n') leftBuilder.delete(leftBuilder.length - 1, leftBuilder.length)
        if (rightBuilder.isNotEmpty() && rightBuilder.last() == '\n') rightBuilder.delete(rightBuilder.length - 1, rightBuilder.length)

        return AlignedDiffResult(leftBuilder, rightBuilder, adds, deletes)
    }

    private fun createColoredLine(text: String, bgColor: Int): SpannableString {
        val span = SpannableString(text)
        span.setSpan(
            BackgroundColorSpan(bgColor),
            0,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    private fun createPhantomLine(): SpannableString {
        val span = SpannableString(PHANTOM_TEXT)
        span.setSpan(
            ForegroundColorSpan(AndroidColor.TRANSPARENT),
            0,
            PHANTOM_TEXT.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }
}

class SpannableString(source: CharSequence) : android.text.SpannableString(source)