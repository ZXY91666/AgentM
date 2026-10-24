package com.agentm.chat

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentm.orchestrator.orchestrator.Orchestrator

/**
 * Chat 模块 ViewModel 工厂：从 [ChatGraphHolder] 取 [Orchestrator]
 * （app 在 Application.onCreate 里绑定，避免 chat 反向依赖 :app）。
 *
 * 屏幕侧用法：
 * ```
 * ChatScreen(viewModel = chatViewModel())
 * ```
 */
@Composable
fun chatViewModel(): ChatViewModel = viewModel(factory = ChatViewModelFactory())

/** 绑定 [ChatViewModel] 到 [ChatGraphHolder.orchestrator] 的工厂。 */
class ChatViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val orchestrator: Orchestrator = ChatGraphHolder.orchestrator
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(orchestrator) as T
    }
}
