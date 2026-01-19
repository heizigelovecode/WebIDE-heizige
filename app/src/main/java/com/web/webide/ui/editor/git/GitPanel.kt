package com.web.webide.ui.editor.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

// 🔥🔥🔥 修复点：补回这个枚举，CodeEditScreen 需要它 🔥🔥🔥
enum class SidebarTab { FILES, GIT }

@Composable
fun GitPanel(
    projectPath: String,
    modifier: Modifier = Modifier,
    viewModel: GitViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("变更", "历史")

    var showConfigDialog by remember { mutableStateOf(false) }
    var showBranchDialog by remember { mutableStateOf(false) }

    LaunchedEffect(projectPath) { viewModel.initialize(projectPath) }
    LaunchedEffect(viewModel.statusMessage) {
        viewModel.statusMessage?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                GitToolbarCompact(
                    viewModel = viewModel,
                    onBranchClick = {
                        scope.launch {
                            viewModel.refreshAll()
                            showBranchDialog = true
                        }
                    },
                    onSettingsClick = { showConfigDialog = true },
                    onRefreshClick = { viewModel.refreshAll() }
                )

                if (viewModel.isGitProject) {
                    SecondaryTabRow(
                        selectedTabIndex = selectedTabIndex,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        divider = { HorizontalDivider() }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (viewModel.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            if (!viewModel.isGitProject) {
                EmptyGitState { viewModel.initRepo() }
            } else {
                when (selectedTabIndex) {
                    0 -> GitChangesPageCompact(viewModel)
                    1 -> GitGraphListCompact(viewModel.commitLog)
                }
            }
        }
    }

    if (showConfigDialog) ConfigDialog(viewModel) { showConfigDialog = false }
    if (showBranchDialog) BranchListDialog(viewModel) { showBranchDialog = false }
}

@Composable
fun GitToolbarCompact(
    viewModel: GitViewModel,
    onBranchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 8.dp)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        AssistChip(
            onClick = onBranchClick,
            label = {
                Text(
                    text = viewModel.currentBranch.ifEmpty { "HEAD" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium
                )
            },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.CallSplit, null, Modifier.size(14.dp)) },
            modifier = Modifier.weight(1f).height(32.dp),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(Modifier.width(4.dp))

        if (viewModel.isGitProject) {
            IconButton(onClick = { viewModel.push() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.CloudUpload, "Push", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { viewModel.pull() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.CloudDownload, "Pull", modifier = Modifier.size(18.dp))
            }
        }

        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("刷新") },
                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                    onClick = { onRefreshClick(); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("设置") },
                    leadingIcon = { Icon(Icons.Default.Settings, null) },
                    onClick = { onSettingsClick(); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("变基更新 (Rebase)") },
                    onClick = { viewModel.updateProject(true); showMenu = false }
                )
            }
        }
    }
}

@Composable
fun GitChangesPageCompact(viewModel: GitViewModel) {
    var message by remember { mutableStateOf("") }
    var pushAfter by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (viewModel.remoteUrl.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.padding(8.dp).fillMaxWidth()
            ) {
                Text(
                    "未配置远程仓库",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            if (viewModel.changedFiles.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("无文件变更", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                items(viewModel.changedFiles) { file -> GitFileItemCompact(file) }
            }
        }
        Surface(
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().imePadding()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    placeholder = { Text("提交信息...") },
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Checkbox(checked = pushAfter, onCheckedChange = { pushAfter = it }, modifier = Modifier.scale(0.8f))
                    Text("推送", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { viewModel.commit(message, pushAfter); message = "" },
                        enabled = viewModel.changedFiles.isNotEmpty() && message.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) { Text("提交") }
                }
            }
        }
    }
}

@Composable
fun GitFileItemCompact(file: GitFileChange) {
    val fileName = file.filePath.substringAfterLast("/")
    val fileDir = file.filePath.substringBeforeLast("/", "")
    Row(
        modifier = Modifier.fillMaxWidth().clickable { }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = when (file.status) {
            GitFileStatus.ADDED -> Color(0xFF4CAF50)
            GitFileStatus.MODIFIED -> Color(0xFF2196F3)
            else -> Color(0xFFF44336)
        }
        Surface(
            color = color.copy(alpha = 0.1f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = file.status.name.first().toString(), color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text = fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (fileDir.isNotEmpty()) {
                Text(text = fileDir, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun EmptyGitState(onInit: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Source, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("当前文件夹不是 Git 仓库", color = MaterialTheme.colorScheme.outline)
        Button(onClick = onInit, modifier = Modifier.padding(top = 16.dp)) { Text("初始化仓库") }
    }
}

@Composable
fun ConfigDialog(viewModel: GitViewModel, onDismiss: () -> Unit) {
    var remote by remember { mutableStateOf(viewModel.remoteUrl) }
    var email by remember { mutableStateOf(viewModel.userEmail) }

    // Auth 状态
    val currentAuth = viewModel.savedAuth
    var authType by remember { mutableStateOf(currentAuth?.type ?: AuthType.HTTPS) }

    var username by remember { mutableStateOf(currentAuth?.username ?: "") }
    var token by remember { mutableStateOf(currentAuth?.token ?: "") }
    var privateKey by remember { mutableStateOf(currentAuth?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(currentAuth?.passphrase ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("仓库配置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = remote,
                    onValueChange = { remote = it },
                    label = { Text("Remote URL") },
                    placeholder = { Text(if (authType == AuthType.SSH) "git@github.com:..." else "https://github.com/...") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (Committer)") },
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                Text("认证方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = authType == AuthType.HTTPS,
                        onClick = { authType = AuthType.HTTPS },
                        label = { Text("HTTPS") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = authType == AuthType.SSH,
                        onClick = { authType = AuthType.SSH },
                        label = { Text("SSH Key") },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (authType == AuthType.HTTPS) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Token / Password") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("Private Key (PEM/OpenSSH)") },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----...") }
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase (选填)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.saveConfig(remote, email, authType, username, token, privateKey, passphrase)
                onDismiss()
            }) { Text("保存配置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun BranchListDialog(viewModel: GitViewModel, onDismiss: () -> Unit) {
    var branches by remember { mutableStateOf(emptyList<GitBranch>()) }
    var showNewBranchInput by remember { mutableStateOf(false) }
    var showNewTagInput by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { branches = viewModel.getBranches() }

    if (showNewBranchInput) {
        NewBranchDialog(viewModel) {
            showNewBranchInput = false
            onDismiss()
        }
        return
    }

    if (showNewTagInput) {
        NewTagDialog(viewModel) {
            showNewTagInput = false
            onDismiss()
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.CallSplit, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("分支管理")
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { showNewBranchInput = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建分支")
                    }

                    OutlinedButton(
                        onClick = { showNewTagInput = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Label, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建标签")
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()

                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    val localBranches = branches.filter { it.type == BranchType.LOCAL }
                    if (localBranches.isNotEmpty()) {
                        item {
                            Text("Local Branches", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                        }
                        items(localBranches) { b -> BranchItem(b, viewModel, onDismiss) }
                    }

                    val remoteBranches = branches.filter { it.type == BranchType.REMOTE }
                    if (remoteBranches.isNotEmpty()) {
                        item {
                            Text("Remote Branches", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                        }
                        items(remoteBranches) { b -> BranchItem(b, viewModel, onDismiss) }
                    }

                    if (localBranches.isEmpty() && remoteBranches.isEmpty()) {
                        item { Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { Text("No branches found", color = Color.Gray) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun BranchItem(branch: GitBranch, viewModel: GitViewModel, onDismiss: () -> Unit) {
    val isCurrent = branch.isCurrent
    val bgColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable {
                if (!isCurrent) {
                    viewModel.checkout(branch.name)
                    onDismiss()
                }
            }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (branch.type == BranchType.LOCAL) Icons.Default.Computer else Icons.Default.Cloud,
            contentDescription = null,
            tint = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = branch.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isCurrent) {
            Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun NewBranchDialog(viewModel: GitViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分支") },
        text = {
            Column {
                Text("从当前 HEAD 创建新分支", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分支名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.createBranch(name); onDismiss() }, enabled = name.isNotBlank()) { Text("创建并切换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun NewTagDialog(viewModel: GitViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建标签") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("标签名 (v1.0)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(msg, { msg = it }, label = { Text("说明") }, modifier = Modifier.fillMaxWidth())
        }},
        confirmButton = {
            Button(onClick = { viewModel.createTag(name, msg); onDismiss() }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}