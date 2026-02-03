package com.web.webide.ui.editor.aicoding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

enum class DockSide {
    Right,
    Left
}

@Stable
class AICodingState {
    var isExpanded by mutableStateOf(false)
    var dockSide by mutableStateOf(DockSide.Right)
    var windowWidth by mutableStateOf(300.dp)
    var windowHeight by mutableStateOf(400.dp)
    var windowOffset by mutableStateOf(Offset.Zero)
    var lastFloatingPosition by mutableStateOf<Offset?>(null)
    var isDragging by mutableStateOf(false)
    
    var isMaximized by mutableStateOf(false)
    internal var restoreWidth by mutableStateOf(300.dp)
    internal var restoreHeight by mutableStateOf(400.dp)
}

@Composable
fun rememberAICodingState(): AICodingState {
    return remember { AICodingState() }
}
