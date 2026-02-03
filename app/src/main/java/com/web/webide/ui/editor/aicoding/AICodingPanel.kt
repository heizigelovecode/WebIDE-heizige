package com.web.webide.ui.editor.aicoding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay

import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.shape.CircleShape

@Composable
fun AICodingPanel(
    state: AICodingState = rememberAICodingState()
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
    ) {
        val layoutMaxWidth = maxWidth
        val layoutMaxHeight = maxHeight
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        
        // Helper to convert Dp to Px
        val dpToPx = { dp: Dp -> with(density) { dp.toPx() } }
        val pxToDp = { px: Float -> with(density) { px.toDp() } }

        // Initialize position to right side center if not set
        LaunchedEffect(Unit) {
            if (state.windowOffset == Offset.Zero) {
                state.dockSide = DockSide.Right
                state.windowOffset = Offset(
                    x = maxWidthPx - dpToPx(32.dp), // Docked width
                    y = maxHeightPx / 2
                )
            }
        }

        val transition = updateTransition(targetState = state.isExpanded, label = "WindowTransition")

        // Animation values
        val width by transition.animateDp(
            transitionSpec = { spring(stiffness = Spring.StiffnessLow) },
            label = "width"
        ) { expanded ->
            if (expanded) {
                if (state.isMaximized) layoutMaxWidth else state.windowWidth
            } else 32.dp
        }

        val height by transition.animateDp(
            transitionSpec = { spring(stiffness = Spring.StiffnessLow) },
            label = "height"
        ) { expanded ->
            if (expanded) {
                if (state.isMaximized) layoutMaxHeight else state.windowHeight
            } else 64.dp
        }
        
        val alpha by transition.animateFloat(
            transitionSpec = { tween(300) },
            label = "alpha"
        ) { expanded ->
             if (expanded) 1f else 0.9f
        }

        // Animated Offset - Split into X and Y for independent animations (X=Spring, Y=Decay)
        val animX = remember { Animatable(state.windowOffset.x) }
        val animY = remember { Animatable(state.windowOffset.y) }
        
        // Derived offset for calculations
        val currentOffset = Offset(animX.value, animY.value)

        // Dynamic Corner Radius Calculation based on Distance to Edge
        val maxRadius = 16.dp
        val maxRadiusPx = dpToPx(maxRadius)
        val thresholdRadius = 32.dp
        val thresholdRadiusPx = dpToPx(thresholdRadius)

        // Calculate distances to edges based on current animated offset
        val distanceToLeft = currentOffset.x
        val distanceToRight = maxWidthPx - (currentOffset.x + dpToPx(width))
        
        // Calculate radii: 0 when touching edge, maxRadius when distance > threshold
        val currentTopStartRadius = pxToDp(
            (distanceToLeft / thresholdRadiusPx).coerceIn(0f, 1f) * maxRadiusPx
        )
        val currentBottomStartRadius = pxToDp(
            (distanceToLeft / thresholdRadiusPx).coerceIn(0f, 1f) * maxRadiusPx
        )
        
        val currentTopEndRadius = pxToDp(
            (distanceToRight / thresholdRadiusPx).coerceIn(0f, 1f) * maxRadiusPx
        )
        val currentBottomEndRadius = pxToDp(
            (distanceToRight / thresholdRadiusPx).coerceIn(0f, 1f) * maxRadiusPx
        )

        // Arrow rotation animation
        // 0 degrees for Right dock (pointing Left)
        // 180 degrees for Left dock (pointing Right)
        val arrowRotation by animateFloatAsState(
            targetValue = if (state.dockSide == DockSide.Right) 0f else 180f,
            animationSpec = spring(stiffness = Spring.StiffnessLow)
        )
        
        LaunchedEffect(state.isExpanded, state.dockSide, state.isMaximized) {
             if (state.isExpanded) {
                 if (state.isMaximized) {
                     launch { animX.animateTo(0f, spring(stiffness = Spring.StiffnessLow)) }
                     launch { animY.animateTo(0f, spring(stiffness = Spring.StiffnessLow)) }
                 } else {
                     // Calculate target position: Center or Last Known
                     val targetW = dpToPx(state.windowWidth)
                     val targetH = dpToPx(state.windowHeight)
                     
                     val targetX: Float
                     val targetY: Float
                     
                     // Always Default to Horizontal Center, Vertical follows Tab (Inertia friendly)
                     // We intentionally ignore lastFloatingPosition for X to ensure it always centers nicely on open
                     // unless the user is dragging it RIGHT NOW (which is handled by drag logic, not here)
                     targetX = (maxWidthPx - targetW) / 2f
                     targetY = animY.value
                     
                     // Ensure within bounds
                     val constrainedX = targetX.coerceIn(0f, maxWidthPx - targetW)
                     val constrainedY = targetY.coerceIn(0f, maxHeightPx - targetH)
                     
                     launch { animX.animateTo(constrainedX, spring(stiffness = Spring.StiffnessLow)) }
                     launch { animY.animateTo(constrainedY, spring(stiffness = Spring.StiffnessLow)) }
                 }
             } else {
                 // Slide to dock
                 // Only animate if NOT dragging (dragging controls offset directly)
                 if (!state.isDragging) {
                     val targetX = if (state.dockSide == DockSide.Right) maxWidthPx - dpToPx(32.dp) else 0f
                     val targetY = state.windowOffset.y.coerceIn(0f, maxHeightPx - dpToPx(64.dp))
                     launch { animX.animateTo(targetX, spring(stiffness = Spring.StiffnessLow)) }
                     launch { animY.animateTo(targetY, spring(stiffness = Spring.StiffnessLow)) }
                 }
             }
        }
        
        // Sync state offset for restoration
        if (!state.isDragging && !state.isMaximized) {
            state.windowOffset = Offset(animX.value, animY.value)
        }

        // The Window/Tab
        // We use a Box as the container to handle positioning and the outer glow effect
        // The Surface inside handles the content and clipping
        Box(
            modifier = Modifier
                .offset { IntOffset(animX.value.roundToInt(), animY.value.roundToInt()) }
                .size(width, height)
                .alpha(alpha)
                .oneShotGlow(
                    trigger = state.isExpanded,
                    topStart = currentTopStartRadius,
                    topEnd = currentTopEndRadius,
                    bottomEnd = currentBottomEndRadius,
                    bottomStart = currentBottomStartRadius
                )
                .pointerInput(state.isExpanded) {
                    if (!state.isExpanded) {
                        // Custom drag handler to capture velocity for inertia
                        awaitPointerEventScope {
                            val velocityTracker = VelocityTracker()
                            
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                state.isDragging = true
                                velocityTracker.resetTracking()
                                
                                var dragChanges = Offset.Zero
                                
                                drag(down.id) { change ->
                                    change.consume()
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                    
                                    val dragAmount = change.position - change.previousPosition
                                    dragChanges += dragAmount
                                    
                                    scope.launch {
                                        val currentW = dpToPx(width)
                                        val currentH = dpToPx(height)
                                        
                                        val newX = (animX.value + dragAmount.x).coerceIn(0f, maxWidthPx - currentW)
                                        val newY = (animY.value + dragAmount.y).coerceIn(0f, maxHeightPx - currentH)
                                        
                                        animX.snapTo(newX)
                                        animY.snapTo(newY)
                                        
                                        val centerX = maxWidthPx / 2
                                        val newSide = if (newX + (currentW / 2) > centerX) DockSide.Right else DockSide.Left
                                        if (state.dockSide != newSide) {
                                            state.dockSide = newSide
                                        }
                                    }
                                }
                                
                                state.isDragging = false
                                val velocity = velocityTracker.calculateVelocity()
                                val velocityY = velocity.y
                                
                                // Final snap logic
                                val centerX = maxWidthPx / 2
                                val newSide = if (animX.value > centerX) DockSide.Right else DockSide.Left
                                state.dockSide = newSide
                                val targetX = if (newSide == DockSide.Right) maxWidthPx - dpToPx(32.dp) else 0f
                                
                                scope.launch {
                                    launch {
                                        animX.animateTo(
                                            targetValue = targetX, 
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                        )
                                    }
                                    
                                    launch {
                                        // Inertia decay for Y
                                        val decay = exponentialDecay<Float>(frictionMultiplier = 2f)
                                        val currentH = dpToPx(height)
                                        val maxY = maxHeightPx - currentH
                                        
                                        animY.updateBounds(lowerBound = 0f, upperBound = maxY)
                                        try {
                                            animY.animateDecay(velocityY, decay)
                                        } finally {
                                            animY.updateBounds(lowerBound = null, upperBound = null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (!state.isExpanded) {
                                state.isExpanded = true
                            }
                        }
                    )
                }
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(
                    topStart = currentTopStartRadius,
                    topEnd = currentTopEndRadius,
                    bottomEnd = currentBottomEndRadius,
                    bottomStart = currentBottomStartRadius
                ),
                color = if (state.isExpanded) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                if (state.isExpanded) {
                    // Expanded Content
                    Box(modifier = Modifier.fillMaxSize()) {
                         Column(modifier = Modifier.fillMaxSize()) {
                            // Title Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp) // Taller title bar
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            MaterialTheme.colorScheme.surfaceContainer
                                        )
                                    )
                                )
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        if (state.isMaximized) return@detectDragGestures
                                        change.consume()
                                        scope.launch {
                                            val currentW = dpToPx(width)
                                            val currentH = dpToPx(height)
                                            val constrainedX = (animX.value + dragAmount.x).coerceIn(0f, maxWidthPx - currentW)
                                            val constrainedY = (animY.value + dragAmount.y).coerceIn(0f, maxHeightPx - currentH)
                                            
                                            animX.snapTo(constrainedX)
                                            animY.snapTo(constrainedY)
                                            
                                            // Update last known floating position
                                            state.lastFloatingPosition = Offset(constrainedX, constrainedY)
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Icon / Logo
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                // Simple "AI" text or icon
                                Text("AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                            }
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Text(
                                text = "AI Assistant",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            Spacer(modifier = Modifier.weight(1f))
                            
                            // Maximize/Restore Button
                            IconButton(
                                onClick = {
                                    if (state.isMaximized) {
                                        // Restore
                                        state.isMaximized = false
                                        state.windowWidth = state.restoreWidth
                                        state.windowHeight = state.restoreHeight
                                    } else {
                                        // Maximize
                                        state.restoreWidth = state.windowWidth
                                        state.restoreHeight = state.windowHeight
                                        state.isMaximized = true
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CropSquare,
                                    contentDescription = "Maximize",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            // Minimize/Dock Button
                            IconButton(onClick = { state.isExpanded = false }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Minimize",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        
                        // Main Content Area (Empty as requested)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                        )
                    }
                    
                    // Custom Resize Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    if (state.isMaximized) return@detectDragGestures
                                    change.consume()
                                    val newW = (state.windowWidth + pxToDp(dragAmount.x)).coerceIn(200.dp, layoutMaxWidth - 40.dp)
                                    val newH = (state.windowHeight + pxToDp(dragAmount.y)).coerceIn(200.dp, layoutMaxHeight - 40.dp)
                                    state.windowWidth = newW
                                    state.windowHeight = newH
                                }
                            }
                            .drawBehind {
                                val strokeWidth = 2.dp.toPx()
                                val color = Color.Gray.copy(alpha = 0.6f)
                                
                                // Draw stylish diagonal grip lines
                                val spacing = 5.dp.toPx()
                                val sizePx = size.width
                                
                                // Draw 3 lines with increasing length
                                for (i in 0..2) {
                                    // Start from bottom-right, go up-left
                                    // Line 1 (smallest, closest to corner)
                                    // Line 3 (largest, furthest from corner)
                                    val startX = sizePx - (spacing * (i + 1))
                                    val startY = size.height
                                    val endX = sizePx
                                    val endY = size.height - (spacing * (i + 1))
                                    
                                    drawLine(
                                        color = color,
                                        start = Offset(startX, startY),
                                        end = Offset(endX, endY),
                                        strokeWidth = strokeWidth,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                    )
                    }
                } else {
                    // Collapsed Content
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Expand",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.rotate(arrowRotation)
                        )
                    }
                }
            }
        }
    }
}

// One-shot Glow Effect
fun Modifier.oneShotGlow(
    trigger: Boolean,
    topStart: Dp,
    topEnd: Dp,
    bottomEnd: Dp,
    bottomStart: Dp
): Modifier = composed {
    val density = LocalDensity.current
    val alphaAnim = remember { Animatable(0f) }
    val rotateAnim = remember { Animatable(0f) }
    
    LaunchedEffect(trigger) {
        if (trigger) {
            alphaAnim.snapTo(1f)
            rotateAnim.snapTo(0f)
            
            // Play briefly: Rotate 2 loops over 2s, then fade out
            launch {
                delay(1500)
                alphaAnim.animateTo(0f, tween(500))
            }
            launch {
                rotateAnim.animateTo(720f, tween(2000, easing = LinearEasing))
            }
        } else {
            alphaAnim.snapTo(0f)
        }
    }
    
    val colors = listOf(
        Color(0xFF00FFFF), // Cyan
        Color(0xFF0000FF), // Blue
        Color(0xFFFF00FF), // Magenta
        Color(0xFF00FFFF)  // Cyan
    )
    
    val colorInts = remember(colors) { colors.map { it.toArgb() }.toIntArray() }
    
    drawBehind {
        if (alphaAnim.value > 0f) {
            val topStartPx = with(density) { topStart.toPx() }
            val topEndPx = with(density) { topEnd.toPx() }
            val bottomEndPx = with(density) { bottomEnd.toPx() }
            val bottomStartPx = with(density) { bottomStart.toPx() }

            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(offset = Offset.Zero, size = size),
                        topLeft = CornerRadius(topStartPx),
                        topRight = CornerRadius(topEndPx),
                        bottomRight = CornerRadius(bottomEndPx),
                        bottomLeft = CornerRadius(bottomStartPx)
                    )
                )
            }
            
            // Create a SweepGradient shader and rotate it using a local matrix.
            // This rotates the *colors* around the center, while keeping the *path* (border shape) stationary.
            val shader = android.graphics.SweepGradient(
                center.x,
                center.y,
                colorInts,
                null
            )
            val matrix = android.graphics.Matrix()
            matrix.setRotate(rotateAnim.value, center.x, center.y)
            shader.setLocalMatrix(matrix)
            
            drawPath(
                path = path,
                brush = ShaderBrush(shader),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                alpha = alphaAnim.value
            )
        }
    }
}


