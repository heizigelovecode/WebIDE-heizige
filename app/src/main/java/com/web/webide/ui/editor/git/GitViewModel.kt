package com.web.webide.ui.editor.git

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.eclipse.jgit.revwalk.RevCommit
import androidx.core.content.edit

class GitViewModel(application: Application) : AndroidViewModel(application) {

    var isGitProject by mutableStateOf(false)
    var changedFiles by mutableStateOf<List<GitFileChange>>(emptyList())
    var currentBranch by mutableStateOf("")
    var commitLog by mutableStateOf<List<GitCommitUI>>(emptyList())
    var isLoading by mutableStateOf(false)
    var statusMessage by mutableStateOf<String?>(null)

    // 🔥 配置相关
    var remoteUrl by mutableStateOf("")
    var userEmail by mutableStateOf("")
    var savedAuth by mutableStateOf<GitAuth?>(null)

    private var gitManager: GitManager? = null

    private val laneColors = listOf(
        Color(0xFFFF5252), Color(0xFF40C4FF), Color(0xFFE040FB),
        Color(0xFF69F0AE), Color(0xFFFFAB40), Color(0xFFFFD740),
        Color(0xFF9E9E9E), Color(0xFF795548)
    )

    fun initialize(projectPath: String) {
        gitManager = GitManager(projectPath)
        loadConfig()
        refreshAll()
    }

    private fun loadConfig() {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("git_config", Context.MODE_PRIVATE)

        remoteUrl = prefs.getString("remote_url", "") ?: ""
        userEmail = prefs.getString("user_email", "") ?: ""

        // 读取认证信息
        val authTypeStr = prefs.getString("auth_type", "HTTPS") ?: "HTTPS"
        val username = prefs.getString("username", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        val privateKey = prefs.getString("private_key", "") ?: ""
        val passphrase = prefs.getString("passphrase", "") ?: ""

        val authType = if (authTypeStr == "SSH") AuthType.SSH else AuthType.HTTPS

        savedAuth = GitAuth(
            type = authType,
            username = username,
            token = token,
            privateKey = privateKey,
            passphrase = passphrase
        )
    }

    fun saveConfig(
        remote: String,
        email: String,
        authType: AuthType,
        username: String,
        token: String,
        privateKey: String,
        passphrase: String
    ) {
        viewModelScope.launch {
            gitManager?.addRemote("origin", remote)
            remoteUrl = remote
            userEmail = email

            savedAuth = GitAuth(authType, username, token, privateKey, passphrase)

            val prefs = getApplication<Application>().getSharedPreferences("git_config", Context.MODE_PRIVATE)
            prefs.edit {
                putString("remote_url", remote)
                putString("user_email", email)
                putString("auth_type", authType.name)
                putString("username", username)
                putString("token", token)
                putString("private_key", privateKey)
                putString("passphrase", passphrase)
            }
            statusMessage = "配置已保存"
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            isLoading = true
            val manager = gitManager ?: return@launch
            isGitProject = manager.isGitRepo()
            if (isGitProject) {
                try {
                    changedFiles = manager.getStatus()
                    currentBranch = manager.getCurrentBranch()
                    val (rawCommits, refMap) = manager.getCommitLog()
                    commitLog = calculateGraph(rawCommits, refMap)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            isLoading = false
        }
    }

    private fun calculateGraph(
        commits: List<RevCommit>,
        refMap: Map<String, List<GitRefUI>>
    ): List<GitCommitUI> {
        val result = mutableListOf<GitCommitUI>()
        val slots = ArrayList<String?>()
        val colorMap = HashMap<String, Int>()
        var nextColorIndex = 0

        commits.forEach { commit ->
            val hash = commit.name
            val parents = commit.parents.map { it.name }

            var myLane = slots.indexOf(hash)
            if (myLane == -1) {
                myLane = slots.indexOf(null)
                if (myLane == -1) {
                    myLane = slots.size
                    slots.add(hash)
                } else {
                    slots[myLane] = hash
                }
            }

            var myColorIdx = colorMap[hash]
            if (myColorIdx == null) {
                myColorIdx = nextColorIndex++
                colorMap[hash] = myColorIdx
            }
            val myColor = laneColors[myColorIdx % laneColors.size]

            val parentLanes = mutableListOf<Int>()
            if (parents.isNotEmpty()) {
                val firstParent = parents[0]
                slots[myLane] = firstParent
                parentLanes.add(myLane)
                colorMap[firstParent] = myColorIdx

                for (i in 1 until parents.size) {
                    val otherParent = parents[i]
                    var otherLane = slots.indexOf(otherParent)

                    if (otherLane == -1) {
                        otherLane = slots.indexOf(null)
                        if (otherLane == -1) {
                            otherLane = slots.size
                            slots.add(otherParent)
                        } else {
                            slots[otherLane] = otherParent
                        }
                        colorMap[otherParent] = (nextColorIndex++ % laneColors.size)
                    }
                    parentLanes.add(otherLane)
                }
            } else {
                slots[myLane] = null
            }

            while (slots.isNotEmpty() && slots.last() == null) slots.removeAt(slots.lastIndex)

            result.add(GitCommitUI(
                hash = hash,
                shortHash = hash.substring(0, 7),
                message = commit.shortMessage.trim(),
                fullMessage = commit.fullMessage.trim(),
                author = commit.authorIdent.name,
                email = commit.authorIdent.emailAddress ?: "",
                time = commit.commitTime * 1000L,
                parents = parents,
                refs = refMap[hash] ?: emptyList(),
                lane = myLane,
                totalLanes = slots.size,
                childLanes = emptyList(),
                parentLanes = parentLanes,
                color = myColor
            ))
        }
        return result
    }

    // --- Git 操作 ---
    fun initRepo() { viewModelScope.launch { gitManager?.initRepo(); refreshAll() } }

    fun commit(msg: String, pushAfter: Boolean) {
        viewModelScope.launch {
            isLoading = true
            try {
                val author = savedAuth?.username?.ifEmpty { "AndroidUser" } ?: "AndroidUser"
                val email = userEmail.ifEmpty { "user@ide.com" }
                gitManager?.commitAll(msg, author, email)
                if (pushAfter) push() else statusMessage = "提交成功"
                refreshAll()
            } catch (e: Exception) {
                statusMessage = "操作失败: ${e.message}"
            } finally { isLoading = false }
        }
    }

    fun push() {
        viewModelScope.launch {
            isLoading = true
            try {
                val auth = savedAuth ?: throw Exception("未配置账号")
                gitManager?.push(auth)
                statusMessage = "推送成功"
            } catch (e: Exception) {
                statusMessage = "推送失败: ${e.message}"
                e.printStackTrace()
            } finally { isLoading = false }
        }
    }

    fun pull() { updateProject(false) }

    fun updateProject(rebase: Boolean) {
        viewModelScope.launch {
            isLoading = true
            try {
                val auth = savedAuth ?: throw Exception("未配置账号")
                if (rebase) gitManager?.pullRebase(auth) else gitManager?.pull(auth)
                statusMessage = "更新成功"
                refreshAll()
            } catch (e: Exception) {
                statusMessage = "更新失败: ${e.message}"
            } finally { isLoading = false }
        }
    }

    fun createBranch(name: String) {
        viewModelScope.launch {
            isLoading = true
            try {
                gitManager?.createBranch(name)
                statusMessage = "分支 $name 创建并切换成功"
                refreshAll()
            } catch (e: Exception) { statusMessage = "创建分支失败: ${e.message}" }
            finally { isLoading = false }
        }
    }

    fun createTag(name: String, msg: String) {
        viewModelScope.launch {
            isLoading = true
            try {
                gitManager?.createTag(name, msg)
                statusMessage = "标签 $name 创建成功"
                refreshAll()
            } catch (e: Exception) { statusMessage = "创建标签失败: ${e.message}" }
            finally { isLoading = false }
        }
    }

    fun checkout(name: String) {
        viewModelScope.launch {
            isLoading = true
            try {
                gitManager?.checkout(name)
                statusMessage = "切换到 $name"
                refreshAll()
            } catch (e: Exception) { statusMessage = "切换失败: ${e.message}" }
            finally { isLoading = false }
        }
    }

    suspend fun getBranches() = gitManager?.getBranches() ?: emptyList()
    fun clearMessage() { statusMessage = null }
}