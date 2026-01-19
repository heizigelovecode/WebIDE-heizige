package com.web.webide.ui.editor.git

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import java.io.File
import java.net.InetSocketAddress
import java.security.PublicKey

class GitManager(projectPath: String) {
    private val rootDir = File(projectPath)

    // 用来存放临时 SSH 密钥的目录 (在项目目录下隐藏文件夹)
    private val sshConfigDir = File(rootDir, ".git_ssh_config")

    fun isGitRepo(): Boolean = File(rootDir, ".git").exists()

    // --- 分支获取 ---
    suspend fun getBranches(): List<GitBranch> = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext emptyList()
        val git = Git.open(rootDir)
        val repo = git.repository
        val currentBranchRef = repo.fullBranch

        val branchList = mutableListOf<GitBranch>()
        val refs = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()

        refs.forEach { ref ->
            val fullName = ref.name
            var displayName = fullName
            var type = BranchType.LOCAL

            if (fullName.startsWith(Constants.R_HEADS)) {
                displayName = fullName.substring(Constants.R_HEADS.length)
                type = BranchType.LOCAL
            } else if (fullName.startsWith(Constants.R_REMOTES)) {
                displayName = fullName.substring(Constants.R_REMOTES.length)
                type = BranchType.REMOTE
            }

            val isCurrent = (fullName == currentBranchRef)
            branchList.add(GitBranch(displayName, fullName, type, isCurrent))
        }

        git.close()
        branchList.sortedWith(compareByDescending<GitBranch> { it.isCurrent }
            .thenBy { it.type }
            .thenBy { it.name }
        )
    }

    suspend fun initRepo() = withContext(Dispatchers.IO) {
        Git.init().setDirectory(rootDir).call().close()
    }

    suspend fun getStatus(): List<GitFileChange> = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext emptyList()
        val git = Git.open(rootDir)
        val status = git.status().call()
        val changes = mutableListOf<GitFileChange>()

        status.added.forEach { changes.add(GitFileChange(it, GitFileStatus.ADDED)) }
        status.changed.forEach { changes.add(GitFileChange(it, GitFileStatus.MODIFIED)) }
        status.modified.forEach { changes.add(GitFileChange(it, GitFileStatus.MODIFIED)) }
        status.untracked.forEach { changes.add(GitFileChange(it, GitFileStatus.UNTRACKED)) }
        status.missing.forEach { changes.add(GitFileChange(it, GitFileStatus.MISSING)) }
        status.removed.forEach { changes.add(GitFileChange(it, GitFileStatus.REMOVED)) }
        status.conflicting.forEach { changes.add(GitFileChange(it, GitFileStatus.CONFLICTING)) }

        git.close()
        changes.sortedBy { it.filePath }
    }

    suspend fun commitAll(message: String, author: String, email: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.add().addFilepattern(".").call()

        val status = git.status().call()
        if (status.missing.isNotEmpty() || status.removed.isNotEmpty()) {
            val rm = git.rm()
            status.missing.forEach { rm.addFilepattern(it) }
            status.removed.forEach { rm.addFilepattern(it) }
            rm.call()
        }

        val person = PersonIdent(author, email)
        git.commit()
            .setMessage(message)
            .setAuthor(person)
            .setCommitter(person)
            .call()

        git.close()
    }

    // -----------------------------------------------------------------------
    // 🔥 Apache SSHD 核心配置
    // -----------------------------------------------------------------------

    /**
     * 自定义 SessionFactory，告诉 SSHD 去哪里找 id_rsa 文件
     */
    class CustomSshSessionFactory(private val sshDir: File) : SshdSessionFactory() {
        override fun getSshDirectory(): File = sshDir
        override fun getHomeDirectory(): File = sshDir.parentFile

        // 🔥 修复点：ServerKeyDatabase 是接口，不能用 Lambda，必须完整实现
        override fun getServerKeyDatabase(homeDir: File, sshDir: File): ServerKeyDatabase {
            return object : ServerKeyDatabase {
                override fun lookup(
                    connectAddress: String,
                    remoteAddress: InetSocketAddress,
                    config: ServerKeyDatabase.Configuration
                ): List<PublicKey> {
                    return emptyList()
                }

                override fun accept(
                    connectAddress: String,
                    remoteAddress: InetSocketAddress,
                    serverKey: PublicKey,
                    config: ServerKeyDatabase.Configuration,
                    provider: CredentialsProvider?
                ): Boolean {
                    return true // 始终信任所有主机 Key (类似 StrictHostKeyChecking=no)
                }
            }
        }
    }

    /**
     * 准备 SSH 环境：将内存中的私钥字符串写入临时文件，供 SSHD 读取
     */
    private fun prepareSshEnvironment(auth: GitAuth): TransportConfigCallback {
        return TransportConfigCallback { transport ->
            if (transport is SshTransport) {
                // 1. 准备目录
                if (!sshConfigDir.exists()) sshConfigDir.mkdirs()

                // 2. 写入私钥 (如果提供了)
                if (auth.privateKey.isNotBlank()) {
                    val keyFile = File(sshConfigDir, "id_rsa")
                    // 为了安全，只有内容变动时才重写
                    if (!keyFile.exists() || keyFile.readText() != auth.privateKey) {
                        keyFile.writeText(auth.privateKey)
                    }
                }

                // 3. 设置 Factory
                transport.sshSessionFactory = CustomSshSessionFactory(sshConfigDir)
            }
        }
    }

    // --- 推送 (Push) ---
    suspend fun push(auth: GitAuth, remote: String = "origin") = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        val cmd = git.push().setRemote(remote)

        if (auth.type == AuthType.HTTPS) {
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.token))
        } else {
            cmd.setTransportConfigCallback(prepareSshEnvironment(auth))
        }

        cmd.call()
        git.close()
    }

    // --- 拉取 (Pull) ---
    suspend fun pull(auth: GitAuth, remote: String = "origin") = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        val cmd = git.pull().setRemote(remote)

        if (auth.type == AuthType.HTTPS) {
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.token))
        } else {
            cmd.setTransportConfigCallback(prepareSshEnvironment(auth))
        }

        cmd.call()
        git.close()
    }

    // --- 变基拉取 (Rebase) ---
    suspend fun pullRebase(auth: GitAuth, remote: String = "origin") = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        val cmd = git.pull().setRemote(remote).setRebase(true)

        if (auth.type == AuthType.HTTPS) {
            cmd.setCredentialsProvider(UsernamePasswordCredentialsProvider(auth.username, auth.token))
        } else {
            cmd.setTransportConfigCallback(prepareSshEnvironment(auth))
        }

        cmd.call()
        git.close()
    }

    // --- 辅助方法 ---
    suspend fun createBranch(name: String, checkout: Boolean = true) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.branchCreate().setName(name).call()
        if (checkout) {
            git.checkout().setName(name).call()
        }
        git.close()
    }

    suspend fun createTag(name: String, message: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.tag().setName(name).setMessage(message).call()
        git.close()
    }

    suspend fun checkout(name: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        git.checkout().setName(name).call()
        git.close()
    }

    suspend fun getCurrentBranch(): String = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext ""
        val git = Git.open(rootDir)
        val b = git.repository.branch
        git.close()
        b ?: "HEAD"
    }

    suspend fun addRemote(name: String, url: String) = withContext(Dispatchers.IO) {
        val git = Git.open(rootDir)
        val config = git.repository.config
        config.setString("remote", name, "url", url)
        config.save()
        git.close()
    }

    suspend fun getCommitLog(): Pair<List<RevCommit>, Map<String, List<GitRefUI>>> = withContext(Dispatchers.IO) {
        if (!isGitRepo()) return@withContext Pair(emptyList(), emptyMap())
        val git = Git.open(rootDir)
        val repo = git.repository

        val refMap = mutableMapOf<String, MutableList<GitRefUI>>()

        val head = repo.resolve(Constants.HEAD)
        if (head != null) {
            val headId = head.name
            refMap.getOrPut(headId) { mutableListOf() }.add(GitRefUI("HEAD", RefType.HEAD))
        }

        repo.refDatabase.refs.forEach { ref ->
            val id = ref.objectId.name
            val name = ref.name
            val simpleName = RepositoryUtils.shortenRefName(name)

            val type = when {
                name.startsWith(Constants.R_HEADS) -> RefType.LOCAL_BRANCH
                name.startsWith(Constants.R_REMOTES) -> RefType.REMOTE_BRANCH
                name.startsWith(Constants.R_TAGS) -> RefType.TAG
                else -> RefType.LOCAL_BRANCH
            }

            if (name != Constants.HEAD) {
                refMap.getOrPut(id) { mutableListOf() }.add(GitRefUI(simpleName, type))
            }
        }

        val walk = RevWalk(repo)
        val allRefs = repo.refDatabase.refs
        allRefs.forEach { ref ->
            if (ref.objectId != null) {
                try {
                    walk.markStart(walk.parseCommit(ref.objectId))
                } catch (_: Exception) { /* ignore */ }
            }
        }
        walk.sort(org.eclipse.jgit.revwalk.RevSort.COMMIT_TIME_DESC)
        walk.sort(org.eclipse.jgit.revwalk.RevSort.TOPO)

        val commits = mutableListOf<RevCommit>()
        for (commit in walk) {
            commits.add(commit)
        }

        walk.dispose()
        git.close()

        Pair(commits, refMap)
    }
}

// 🔥🔥 修复点：补回了 RepositoryUtils 对象 🔥🔥
object RepositoryUtils {
    fun shortenRefName(refName: String): String {
        if (refName.startsWith(Constants.R_HEADS)) return refName.substring(Constants.R_HEADS.length)
        if (refName.startsWith(Constants.R_TAGS)) return refName.substring(Constants.R_TAGS.length)
        if (refName.startsWith(Constants.R_REMOTES)) return refName.substring(Constants.R_REMOTES.length)
        return refName
    }
}