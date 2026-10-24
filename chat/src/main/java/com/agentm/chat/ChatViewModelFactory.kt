package com.agentm.chat

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentm.app.AgentMApplication
import com.agentm.orchestrator.orchestrator.Orchestrator

/**
 * Chat 模块 ViewModel 工厂：从 [AgentMApplication] 依赖容器取 [Orchestrator]。
 *
 * 屏幕侧用法：
 * ```
 * ChatScreen(viewModel = chatViewModel())
 * ```
 */
@Composable
fun chatViewModel(): ChatViewModel = viewModel(factory = ChatViewModelFactory())

/** 绑定 [ChatViewModel] 到 [AgentMApplication.orchestrator] 的工厂。 */
class ChatViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val orchestrator = AgentMApplication.app.orchestrator
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(orchestrator) as T
    }
}
