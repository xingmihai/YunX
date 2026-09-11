/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.LanzouAccountDao
import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.LanzouConstants
import com.yunx.app.data.network.LanzouFile
import com.yunx.app.data.network.LanzouFolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 面包屑节点 */
data class LanzouPathNode(val id: String, val name: String)

sealed interface LanzouCloudUiState {
    object Loading : LanzouCloudUiState
    data class Loaded(
        val folders: List<LanzouFolder>,
        val files: List<LanzouFile>,
        val path: List<LanzouPathNode>
    ) : LanzouCloudUiState
    data class Error(val message: String) : LanzouCloudUiState
}

/**
 * 蓝奏云云盘浏览 ViewModel。
 *
 * ★ vei 的生命周期：vei 是页面级令牌（与目录无关、跨会话变化），
 *   这里在首次加载时取一次并缓存；若接口整体失败则判定为 vei 过期，
 *   重新取一次再重试（避免每次进目录都多一次请求）。
 */
class LanzouCloudViewModel(private val dao: LanzouAccountDao) : ViewModel() {

    private val api = LanzouApi()

    private val _uiState = MutableStateFlow<LanzouCloudUiState>(LanzouCloudUiState.Loading)
    val uiState: StateFlow<LanzouCloudUiState> = _uiState.asStateFlow()

    private var vei: String? = null
    private var path: List<LanzouPathNode> = listOf(
        LanzouPathNode(LanzouConstants.ROOT_FOLDER_ID, "根目录")
    )

    /** 上一次加载任务；新导航时取消，避免旧请求回写 uiState */
    private var loadJob: Job? = null
    /**
     * 加载代次。仅靠 cancel 仍存在竞态：协程已跑到「取消检查点之后」时，
     * cancel 拦不住它继续写 uiState。故再用代次号做一道兜底 ——
     * 每次开新加载就 +1，回写前发现代次已变则丢弃结果。
     */
    private var loadGeneration = 0

    fun loadRoot() {
        path = listOf(LanzouPathNode(LanzouConstants.ROOT_FOLDER_ID, "根目录"))
        load(path.last().id)
    }

    fun enterFolder(folderId: String, name: String) {
        path = path + LanzouPathNode(folderId, name)
        load(folderId)
    }

    fun navigateUp() {
        if (path.size <= 1) return
        path = path.dropLast(1)
        load(path.last().id)
    }

    fun refresh() = load(path.last().id)

    private fun load(folderId: String) {
        // 取消上一次加载，并递增代次（两道防线，见 [loadGeneration] 注释）
        loadJob?.cancel()
        val myGeneration = ++loadGeneration
        loadJob = viewModelScope.launch {
            _uiState.value = LanzouCloudUiState.Loading
            val account = dao.getAccount()
            if (account == null) {
                if (myGeneration != loadGeneration) return@launch
                _uiState.value = LanzouCloudUiState.Error("未登录蓝奏云")
                return@launch
            }
            val cookie = account.cookie
            val uid = account.uid

            val veiResult = vei?.let { Result.success(it) } ?: api.fetchVei(cookie, uid)
            val token = veiResult.getOrNull()
            if (token == null) {
                if (myGeneration != loadGeneration) return@launch
                // 展示真实原因，而不是一律说「登录失效」——
                // 后者曾把「vei 正则写错」误报成「登录过期」，排查方向完全跑偏。
                _uiState.value = LanzouCloudUiState.Error(
                    veiResult.exceptionOrNull()?.message ?: "登录状态已失效，请重新登录"
                )
                return@launch
            }
            vei = token

            var folders = api.listFolders(cookie, uid, folderId, token)
            var files = api.listAllFiles(cookie, uid, folderId, token)

            // 两个都失败：很可能是 vei 过期，取新的再试一次
            if (folders.isFailure && files.isFailure) {
                val fresh = api.fetchVei(cookie, uid).getOrNull()
                if (fresh != null) {
                    vei = fresh
                    folders = api.listFolders(cookie, uid, folderId, fresh)
                    files = api.listAllFiles(cookie, uid, folderId, fresh)
                }
            }

            val folderList = folders.getOrDefault(emptyList())
            val fileList = files.getOrDefault(emptyList())
            if (folders.isFailure && files.isFailure) {
                if (myGeneration != loadGeneration) return@launch
                _uiState.value = LanzouCloudUiState.Error(
                    folders.exceptionOrNull()?.message ?: "加载失败"
                )
                return@launch
            }
            if (myGeneration != loadGeneration) return@launch
            _uiState.value = LanzouCloudUiState.Loaded(folderList, fileList, path)
        }
    }

    class Factory(private val dao: LanzouAccountDao) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LanzouCloudViewModel(dao) as T
    }
}

