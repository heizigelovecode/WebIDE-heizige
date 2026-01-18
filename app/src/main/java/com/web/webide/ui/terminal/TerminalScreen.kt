package com.web.webide.ui.terminal

import android.app.Application
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doOnTextChanged
import com.termux.view.TerminalView
import java.lang.ref.WeakReference
import com.rk.terminal.ui.screens.terminal.TerminalBackEnd
import com.rk.libcommons.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TerminalScreen() {
    val context = LocalContext.current

    // 🔥 新增状态：标记环境是否准备就绪
    var isEnvironmentReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (application == null) application = context.applicationContext as Application

        // 🔥 在 IO 线程执行资源复制和解压
        withContext(Dispatchers.IO) {
            SetupWorker.prepareEnvironment(context)
        }

        // 环境准备好后，标记为 true
        isEnvironmentReady = true

        // 只有在没有会话时才创建新会话
        if (SessionManager.sessions.isEmpty()) {
            SessionManager.addNewSession(context)
        }
    }

    // 🔥 如果环境没准备好，显示加载进度条
    if (!isEnvironmentReady) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在初始化 Linux 环境 (首次运行可能需要几十秒)...")
            }
        }
        return // 🚨 提前返回，不渲染下面的终端组件
    }

    LaunchedEffect(Unit) {
        if (application == null) application = context.applicationContext as Application
        if (SessionManager.sessions.isEmpty()) SessionManager.addNewSession(context)
    }

    val currentSession = SessionManager.currentSession
    // 只需要持有 TerminalView 的引用，用于发送按键事件
    var terminalViewRef by remember { mutableStateOf<WeakReference<TerminalView>?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        // 1. 顶部 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryScrollableTabRow(
                selectedTabIndex = SessionManager.currentSessionIndex,
                containerColor = Color.Transparent,
                edgePadding = 0.dp,
                divider = {}, // 不显示默认底部分割线
                modifier = Modifier.weight(1f),
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(SessionManager.currentSessionIndex),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                SessionManager.sessions.forEachIndexed { index, session ->
                    val isSelected = SessionManager.currentSessionIndex == index
                    Tab(
                        selected = isSelected,
                        onClick = { SessionManager.switchTo(index) },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = if(isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    modifier = Modifier.size(14.dp).clickable { SessionManager.removeSession(session) },
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            IconButton(onClick = { SessionManager.addNewSession(context) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // 2. 终端主体
        if (currentSession != null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        TerminalView(ctx, null).apply {
                            terminalViewRef = WeakReference(this)
                            setTextSize(42)
                            setTypeface(Typeface.MONOSPACE)
                            keepScreenOn = true
                            isFocusable = true
                            isFocusableInTouchMode = true
                            attachSession(currentSession)
                            val client = TerminalBackEnd(this, ctx)
                            setTerminalViewClient(client)
                            currentSession.updateTerminalSessionClient(client)
                        }
                    },
                    update = { view ->
                        if (view.currentSession != currentSession) {
                            view.attachSession(currentSession)
                            val client = TerminalBackEnd(view, context)
                            view.setTerminalViewClient(client)
                            currentSession.updateTerminalSessionClient(client)
                            view.onScreenUpdated()
                            // 🔥 这里删除了对 virtualKeysViewRef 的引用，因为我们改用 Compose 实现了
                        }
                    }
                )
            }

            // 3. 底部虚拟按键/输入
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth() // 高度自适应
            ) {
                val pagerState = rememberPagerState(pageCount = { 2 })

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.height(56.dp)
                ) { page ->
                    when (page) {
                        0 -> {
                            // === 🔥 Compose 自定义按键栏 (修复 CTRL/ALT 问题) ===

                            // 定义 CTRL 和 ALT 的状态
                            var isCtrlPressed by remember { mutableStateOf(false) }
                            var isAltPressed by remember { mutableStateOf(false) }

                            // 获取所有按键
                            val allKeys = KEY_PAGES.flatten()

                            LazyRow(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(allKeys) { key ->
                                    // 按钮激活状态样式
                                    val isActive = (key.label == "CTRL" && isCtrlPressed) || (key.label == "ALT" && isAltPressed)
                                    val backgroundColor = if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent
                                    val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

                                    Box(
                                        modifier = Modifier
                                            .height(40.dp)
                                            .defaultMinSize(minWidth = 40.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(backgroundColor)
                                            .clickable {
                                                // === 按键逻辑处理 ===
                                                val session = currentSession

                                                if (key.label == "CTRL") {
                                                    isCtrlPressed = !isCtrlPressed
                                                    return@clickable
                                                }
                                                if (key.label == "ALT") {
                                                    isAltPressed = !isAltPressed
                                                    return@clickable
                                                }

                                                var output = key.output ?: key.label

                                                // 处理 CTRL 组合键
                                                if (isCtrlPressed) {
                                                    // 如果是单个字符 (如 a-z, -, [ 等)
                                                    if (output.length == 1) {
                                                        val charCode = output.uppercase()[0].code
                                                        // ASCII 控制字符映射: A(65) -> 1
                                                        if (charCode in 64..95) {
                                                            output = (charCode - 64).toChar().toString()
                                                        } else if (charCode in 97..122) { // 处理小写 a-z
                                                            output = (charCode - 96).toChar().toString()
                                                        }
                                                    }
                                                    isCtrlPressed = false // 按下一次后重置
                                                }

                                                // 处理 ALT 组合键 (通常发送 ESC + 键)
                                                if (isAltPressed) {
                                                    output = "\u001b$output"
                                                    isAltPressed = false
                                                }

                                                session.write(output)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = key.label,
                                            color = contentColor,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 12.dp)
                                        )
                                    }
                                }
                            }
                        }
                        1 -> {
                            var text by rememberSaveable { mutableStateOf("") }
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    EditText(ctx).apply {
                                        maxLines = 1
                                        isSingleLine = true
                                        imeOptions = EditorInfo.IME_ACTION_DONE
                                        background = null
                                        hint = "  输入命令..."
                                        doOnTextChanged { t, _, _, _ -> text = t.toString() }
                                        setOnEditorActionListener { _, actionId, _ ->
                                            if (actionId == EditorInfo.IME_ACTION_DONE) {
                                                if (text.isEmpty()) {
                                                    val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                                                    terminalViewRef?.get()?.dispatchKeyEvent(event)
                                                } else {
                                                    terminalViewRef?.get()?.mTermSession?.write(text)
                                                    setText("")
                                                }
                                                true
                                            } else false
                                        }
                                    }
                                },
                                update = { if (it.text.toString() != text) it.setText(text) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// === 按键数据模型 ===

data class VirtualKey(
    val label: String,      // 显示的文字，如 "TAB"
    val output: String?,    // 直接输出的内容，如 "\t"。如果是特殊功能键(CTRL)则为 null
    val isToggle: Boolean = false // 是否是切换键 (CTRL, ALT)
)

// 定义按键布局
val KEY_PAGES = listOf(
    // 第一页
    listOf(
        VirtualKey("ESC", "\u001b"),
        VirtualKey("↹", "\t"), // TAB
        VirtualKey("CTRL", null, true),
        VirtualKey("ALT", null, true),
        VirtualKey("-", "-"),
        VirtualKey("▼", "\u001b[B"), // 下箭头
        VirtualKey("▲", "\u001b[A")  // 上箭头
    ),
    // 第二页
    listOf(
        VirtualKey("/", "/"),
        VirtualKey("Home", "\u001b[H"),
        VirtualKey("End", "\u001b[F"),
        VirtualKey("PgUp", "\u001b[5~"),
        VirtualKey("PgDn", "\u001b[6~"),
        VirtualKey("◀", "\u001b[D"), // 左箭头
        VirtualKey("▶", "\u001b[C")  // 右箭头
    )
)

// 🔥 已删除 SmartVirtualKeysListener 类，避免编译错误