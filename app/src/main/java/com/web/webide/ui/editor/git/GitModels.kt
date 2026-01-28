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
package com.web.webide.ui.editor.git

import androidx.compose.ui.graphics.Color

// UI使用的 Commit 数据模型
data class GitCommitUI(
    val hash: String,
    val shortHash: String,
    val message: String,
    val fullMessage: String,
    val author: String,
    val email: String,
    val time: Long,
    val parents: List<String>,
    val refs: List<GitRefUI>,

    // 绘图数据
    val lane: Int,
    val totalLanes: Int,
    val childLanes: List<Int>,
    val parentLanes: List<Int>,
    val color: Color
)

data class GitRefUI(val name: String, val type: RefType)
enum class RefType { HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG }

// 分支数据模型
data class GitBranch(
    val name: String,        // 显示名称 (如 main, origin/main)
    val fullRef: String,     // 完整引用 (refs/heads/main)
    val type: BranchType,    // 类型
    val isCurrent: Boolean   // 是否是当前分支
)

enum class BranchType { LOCAL, REMOTE }

data class GitFileChange(val filePath: String, val status: GitFileStatus)

// 🔥 修改：增强认证模型，支持 HTTPS 和 SSH
data class GitAuth(
    val type: AuthType = AuthType.HTTPS,
    val username: String = "",
    val token: String = "",       // HTTPS: 密码/Token
    val privateKey: String = "",  // SSH: 私钥内容
    val passphrase: String = ""   // SSH: 私钥密码 (可选)
)

enum class AuthType { HTTPS, SSH }

enum class GitFileStatus { ADDED, MODIFIED, UNTRACKED, MISSING, REMOVED, CONFLICTING }