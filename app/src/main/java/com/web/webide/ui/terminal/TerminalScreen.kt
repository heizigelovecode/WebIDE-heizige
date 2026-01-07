package com.web.webide.ui.terminal

import android.app.Application
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doOnTextChanged
import com.termux.view.TerminalView
import java.lang.ref.WeakReference

// rk imports
import com.rk.terminal.ui.screens.terminal.TerminalBackEnd
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysInfo
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysListener
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysConstants
import com.rk.libcommons.application

@Composable
fun TerminalScreen() {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (application == null) application = context.applicationContext as Application
        if (SessionManager.sessions.isEmpty()) SessionManager.addNewSession(context)
    }

    val currentSession = SessionManager.currentSession
    var terminalViewRef by remember { mutableStateOf<WeakReference<TerminalView>?>(null) }
    var virtualKeysViewRef by remember { mutableStateOf<WeakReference<VirtualKeysView>?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        // 1. 顶部 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(MaterialTheme.colorScheme.surface),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScrollableTabRow(
                selectedTabIndex = SessionManager.currentSessionIndex,
                containerColor = Color.Transparent,
                edgePadding = 0.dp,
                divider = {},
                indicator = { tabPositions ->
                    if (SessionManager.currentSessionIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[SessionManager.currentSessionIndex]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                modifier = Modifier.weight(1f)
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
                                text = session.title ?: "终端 ${index + 1}",
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
                            virtualKeysViewRef?.get()?.virtualKeysViewClient = VirtualKeysListener(currentSession)
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
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val primaryColor = MaterialTheme.colorScheme.primary.toArgb()

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.height(56.dp)
                ) { page ->
                    when (page) {
                        0 -> {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    VirtualKeysView(ctx, null).apply {
                                        virtualKeysViewRef = WeakReference(this)
                                        buttonTextColor = onSurfaceColor
                                        buttonActiveTextColor = primaryColor
                                        buttonBackgroundColor = android.graphics.Color.TRANSPARENT
                                        buttonActiveBackgroundColor = primaryColor and 0x33FFFFFF
                                        virtualKeysViewClient = VirtualKeysListener(currentSession)
                                        reload(VirtualKeysInfo(VIRTUAL_KEYS_CONFIG, "", VirtualKeysConstants.CONTROL_CHARS_ALIASES))
                                    }
                                },
                                update = { v -> v.buttonTextColor = onSurfaceColor }
                            )
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

private const val VIRTUAL_KEYS_CONFIG = """
[
  [
    "ESC",
    {"key": "/", "popup": "\\"},
    {"key": "-", "popup": "|"},
    "HOME", "UP", "END", "PGUP"
  ],
  [
    "TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"
  ]
]
"""