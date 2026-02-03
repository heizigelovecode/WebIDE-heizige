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
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

@Composable
fun AICodingPanel(
    state: AICodingState = rememberAICodingState(),
    viewModel: AICodingViewModel = viewModel()
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        var newApiKey by remember { mutableStateOf(viewModel.apiKey) }
        var newBaseUrl by remember { mutableStateOf(viewModel.baseUrl) }
        var newModel by remember { mutableStateOf(viewModel.model) }
        var selectedProvider by remember { mutableStateOf(AICodingViewModel.ApiProvider.fromUrl(viewModel.baseUrl)) }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("AI Assistant Settings") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Provider Selection
                    Box(modifier = Modifier.fillMaxWidth()) {
                        var expanded by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = selectedProvider.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Provider (Preset)") },
                            trailingIcon = {
                                IconButton(onClick = { expanded = !expanded }) {
                                    Icon(Icons.Default.ArrowDropDown, "Select Provider")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Invisible clickable overlay to ensure the whole field triggers dropdown
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0f)
                                .clickable { expanded = true }
                        )
                        
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) {
                            AICodingViewModel.ApiProvider.entries.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.displayName) },
                                    onClick = {
                                        selectedProvider = provider
                                        expanded = false
                                        if (provider != AICodingViewModel.ApiProvider.CUSTOM) {
                                            newBaseUrl = provider.defaultBaseUrl
                                            newModel = provider.defaultModel
                                        }
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = newApiKey,
                        onValueChange = { newApiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newBaseUrl,
                        onValueChange = { 
                            newBaseUrl = it 
                            // Check if custom or matches a provider
                            val matched = AICodingViewModel.ApiProvider.fromUrl(it)
                            selectedProvider = if (matched != AICodingViewModel.ApiProvider.CUSTOM) {
                                matched
                            } else {
                                AICodingViewModel.ApiProvider.CUSTOM
                            }
                        },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    // Model Selection with Dropdown and Fetch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            var expanded by remember { mutableStateOf(false) }
                            
                            OutlinedTextField(
                                value = newModel,
                                onValueChange = { newModel = it },
                                label = { Text("Model") },
                                placeholder = { Text("gpt-3.5-turbo") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { expanded = true }) {
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Model")
                                    }
                                }
                            )
                            
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier.heightIn(max = 200.dp)
                            ) {
                                if (viewModel.availableModels.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No models found. Click Refresh (🔄)") },
                                        onClick = { expanded = false }
                                    )
                                } else {
                                    viewModel.availableModels.forEach { modelName ->
                                        DropdownMenuItem(
                                            text = { Text(modelName) },
                                            onClick = {
                                                newModel = modelName
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        IconButton(
                            onClick = { viewModel.fetchModels() },
                            enabled = !viewModel.isFetchingModels
                        ) {
                            if (viewModel.isFetchingModels) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Fetch Models")
                            }
                        }
                    }
                    
                    Button(
                        onClick = { viewModel.clearChat() },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                         Text("Clear Chat History")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateSettings(newApiKey, newBaseUrl, newModel)
                        showSettings = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("Cancel")
                }
            }
        )
    }

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

                     // Always Default to Horizontal Center, Vertical follows Tab (Inertia friendly)
                     // We intentionally ignore lastFloatingPosition for X to ensure it always centers nicely on open
                     // unless the user is dragging it RIGHT NOW (which is handled by drag logic, not here)
                     val targetX: Float = (maxWidthPx - targetW) / 2f
                     val targetY: Float = animY.value
                     
                     // Ensure within bounds
                     val constrainedX = targetX.coerceIn(0f, (maxWidthPx - targetW).coerceAtLeast(0f))
                     val constrainedY = targetY.coerceIn(0f, (maxHeightPx - targetH).coerceAtLeast(0f))
                     
                     launch { animX.animateTo(constrainedX, spring(stiffness = Spring.StiffnessLow)) }
                     launch { animY.animateTo(constrainedY, spring(stiffness = Spring.StiffnessLow)) }
                 }
             } else {
                 // Slide to dock
                 // Only animate if NOT dragging (dragging controls offset directly)
                 if (!state.isDragging) {
                     val targetX = if (state.dockSide == DockSide.Right) maxWidthPx - dpToPx(32.dp) else 0f
                     val targetY = state.windowOffset.y.coerceIn(0f, (maxHeightPx - dpToPx(64.dp)).coerceAtLeast(0f))
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
                                        
                                        val newX = (animX.value + dragAmount.x).coerceIn(0f, (maxWidthPx - currentW).coerceAtLeast(0f))
                                        val newY = (animY.value + dragAmount.y).coerceIn(0f, (maxHeightPx - currentH).coerceAtLeast(0f))
                                        
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
                                            val constrainedX = (animX.value + dragAmount.x).coerceIn(0f, (maxWidthPx - currentW).coerceAtLeast(0f))
                                            val constrainedY = (animY.value + dragAmount.y).coerceIn(0f, (maxHeightPx - currentH).coerceAtLeast(0f))
                                            
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
                            
                            // Settings Button
                            IconButton(
                                onClick = { showSettings = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
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
                        
                        // Main Content Area
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            var inputText by remember { mutableStateOf("") }
                            val listState = rememberLazyListState()

                            LaunchedEffect(viewModel.messages.size) {
                                if (viewModel.messages.isNotEmpty()) {
                                    listState.animateScrollToItem(viewModel.messages.size - 1)
                                }
                            }

                            // Messages List
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(viewModel.messages) { message ->
                                    val isUser = message.role == "user"
                                    val isError = message.isError
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(
                                                topStart = 16.dp,
                                                topEnd = 16.dp,
                                                bottomStart = if (isUser) 16.dp else 4.dp,
                                                bottomEnd = if (isUser) 4.dp else 16.dp
                                            ),
                                            color = when {
                                                isError -> MaterialTheme.colorScheme.errorContainer
                                                isUser -> MaterialTheme.colorScheme.primaryContainer
                                                else -> MaterialTheme.colorScheme.secondaryContainer
                                            },
                                            modifier = Modifier.widthIn(max = 280.dp)
                                        ) {
                                            SelectionContainer {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    // Reasoning Content Section
                                                    if (!message.reasoningContent.isNullOrBlank()) {
                                                        var isThinkingExpanded by remember { mutableStateOf(false) }
                                                        
                                                        Column(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(bottom = 8.dp)
                                                                .background(
                                                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                                                                    shape = RoundedCornerShape(8.dp)
                                                                )
                                                                .padding(8.dp)
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clickable { isThinkingExpanded = !isThinkingExpanded }
                                                            ) {
                                                                Text(
                                                                    text = "💭 Thinking Process",
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                                Spacer(modifier = Modifier.weight(1f))
                                                                Icon(
                                                                    imageVector = if (isThinkingExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                                    contentDescription = if (isThinkingExpanded) "Collapse" else "Expand",
                                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                            
                                                            if (isThinkingExpanded) {
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                                Text(
                                                                    text = message.reasoningContent,
                                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                                        fontFamily = FontFamily.Monospace,
                                                                        fontSize = 11.sp
                                                                    ),
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                )
                                                            }
                                                        }
                                                    }

                                                    // Main Content
                                                    Text(
                                                        text = message.content,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = when {
                                                            isError -> MaterialTheme.colorScheme.onErrorContainer
                                                            isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                                                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                if (viewModel.isLoading) {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Start
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp).padding(start = 8.dp),
                                                strokeWidth = 2.dp
                                            )
                                        }
                                    }
                                }
                            }

                            // Input Area
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Ask anything...") },
                                    singleLine = false,
                                    maxLines = 3,
                                    shape = RoundedCornerShape(24.dp)
                                )
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                IconButton(
                                    onClick = {
                                        if (inputText.isNotBlank()) {
                                            viewModel.sendMessage(inputText)
                                            inputText = ""
                                        }
                                    },
                                    enabled = !viewModel.isLoading && inputText.isNotBlank(),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
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
                                    val newW = (state.windowWidth + pxToDp(dragAmount.x)).coerceIn(200.dp, (layoutMaxWidth - 40.dp).coerceAtLeast(200.dp))
                                    val newH = (state.windowHeight + pxToDp(dragAmount.y)).coerceIn(200.dp, (layoutMaxHeight - 40.dp).coerceAtLeast(200.dp))
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


