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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Minimize
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
            if (expanded) state.windowWidth else 32.dp
        }

        val height by transition.animateDp(
            transitionSpec = { spring(stiffness = Spring.StiffnessLow) },
            label = "height"
        ) { expanded ->
            if (expanded) state.windowHeight else 64.dp
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
        
        LaunchedEffect(state.isExpanded, state.dockSide) {
             if (state.isExpanded) {
                 // Slide to center-ish or last known position
                 val targetX = (maxWidthPx - dpToPx(state.windowWidth)) / 2
                 val targetY = state.windowOffset.y.coerceIn(0f, maxHeightPx - dpToPx(state.windowHeight))
                 
                 launch { animX.animateTo(targetX, spring(stiffness = Spring.StiffnessLow)) }
                 launch { animY.animateTo(targetY, spring(stiffness = Spring.StiffnessLow)) }
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
        if (!state.isDragging) {
            state.windowOffset = Offset(animX.value, animY.value)
        }

        // The Window/Tab Surface
        Surface(
            modifier = Modifier
                .offset { IntOffset(animX.value.roundToInt(), animY.value.roundToInt()) }
                .size(width, height)
                .alpha(alpha)
                .then(
                    if (state.isExpanded) Modifier.glowBorder(
                        topStart = currentTopStartRadius,
                        topEnd = currentTopEndRadius,
                        bottomEnd = currentBottomEndRadius,
                        bottomStart = currentBottomStartRadius
                    ) else Modifier
                )
                .clip(RoundedCornerShape(
                    topStart = currentTopStartRadius,
                    bottomStart = currentBottomStartRadius,
                    topEnd = currentTopEndRadius,
                    bottomEnd = currentBottomEndRadius
                ))
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
                },
            color = if (state.isExpanded) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,
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
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        scope.launch {
                                            val currentW = dpToPx(width)
                                            val currentH = dpToPx(height)
                                            val constrainedX = (animX.value + dragAmount.x).coerceIn(0f, maxWidthPx - currentW)
                                            val constrainedY = (animY.value + dragAmount.y).coerceIn(0f, maxHeightPx - currentH)
                                            
                                            animX.snapTo(constrainedX)
                                            animY.snapTo(constrainedY)
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { state.isExpanded = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Minimize, "Minimize", modifier = Modifier.size(16.dp))
                            }
                        }
                        
                        // Main Content Placeholder
                        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                            Text("AI Coding Assistant")
                        }
                    }
                    
                    // Resize Handle (Bottom Right)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val newW = (state.windowWidth + pxToDp(dragAmount.x)).coerceIn(200.dp, layoutMaxWidth - 40.dp)
                                    val newH = (state.windowHeight + pxToDp(dragAmount.y)).coerceIn(200.dp, layoutMaxHeight - 40.dp)
                                    state.windowWidth = newW
                                    state.windowHeight = newH
                                }
                            }
                    ) {
                        // Visual indicator for resize
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_sort_by_size), // Placeholder
                            contentDescription = "Resize",
                            modifier = Modifier.size(12.dp).align(Alignment.BottomEnd).alpha(0.5f)
                        )
                    }
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

// GLSL-like Glow Border Effect
fun Modifier.glowBorder(
    topStart: Dp = 12.dp,
    topEnd: Dp = 12.dp,
    bottomEnd: Dp = 12.dp,
    bottomStart: Dp = 12.dp
): Modifier = composed {
    val density = LocalDensity.current
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "glow")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "angle"
    )
    
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primary
    )
    
    this.drawBehind {
        rotate(angle) {
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

            drawPath(
                path = path,
                brush = Brush.sweepGradient(colors),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}
