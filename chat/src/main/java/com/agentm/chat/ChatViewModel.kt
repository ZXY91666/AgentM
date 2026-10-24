package com.agentm.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentm.core.model.AgentEvent
import com.agentm.core.model.ChatMessage
import com.agentm.orchestrator.orchestrator.Orchestrator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 聊天 ViewModel：持有 [Orchestrator]，暴露 [StateFlow] / [SharedFlow] UI 状态。
 *
 * 数据流：
 * - 用户输入 → [send] → orchestrator.dispatch(input) 事件流
 * - core AgentEvent 映射为 [UiEvent]（增量文本 / 工具气泡 / 完成 / 错误）
 * - UI 侧订阅 [uiEvents] 渲染消息气泡与流式文本
 *
 * 主/子 agent 切换：[switchAgent] 改变 activeAgent（UI 状态），
 * dispatch 目标路由由 Orchestrator.MainRouter 内部处理。
 */
class ChatViewModel(
    private val orchestrator: Orchestrator
) : AndroidViewModel(Application()) {

    /** UI 侧消费的事件（把 core AgentEvent 收敛为 UI 友好形状）。 */
    sealed interface UiEvent {
        data class AgentStarted(val runId: String) : UiEvent
        data class AgentDelta(val runId: String, val text: String, val isFinal: Boolean) : UiEvent
        data class AgentToolCall(val runId: String, val toolCall: com.agentm.core.model.ToolCall) : UiEvent
        data class AgentToolResult(val runId: String, val toolCallId: String, val content: String, val isError: Boolean) : UiEvent
        data class AgentDone(val runId: String, val final: ChatMessage) : UiEvent
        data class AgentError(val runId: String, val message: String) : UiEvent
    }

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** 当前主 agent id（UI 切换用；后续接 Orchestrator 路由 API）。 */
    private val _activeAgent = MutableStateFlow("main")
    val activeAgent: StateFlow<String> = _activeAgent.asStateFlow()

    private var runningJob: Job? = null

    /** 发送一条用户消息并启动一次 dispatch。 */
    fun send(text: String) {
        if (_isRunning.value || text.isBlank()) return
        val userMsg = ChatMessage.user(text)
        _messages.value = _messages.value + userMsg

        val runId = "run-${Random.nextInt(0, 0xFFFFFF).toString(16)}"
        _isRunning.value = true

        runningJob = viewModelScope.launch {
            try {
                _uiEvents.emit(UiEvent.AgentStarted(runId))
                val sb = StringBuilder()

                orchestrator.dispatch(text).collect { ev ->
                    when (ev) {
                        is AgentEvent.Delta -> {
                            sb.append(ev.text)
                            _uiEvents.emit(UiEvent.AgentDelta(runId, ev.text, ev.isFinal))
                        }
                        is AgentEvent.ToolCall ->
                            _uiEvents.emit(UiEvent.AgentToolCall(runId, ev.toolCall))
                        is AgentEvent.ToolResult ->
                            _uiEvents.emit(
                                UiEvent.AgentToolResult(runId, ev.toolCallId, ev.content, ev.isError)
                            )
                        is AgentEvent.Done ->
                            _uiEvents.emit(UiEvent.AgentDone(runId, ev.finalMessage))
                        is AgentEvent.Error ->
                            _uiEvents.emit(UiEvent.AgentError(runId, ev.message))
                        else -> Unit
                    }
                }

                if (sb.isNotEmpty()) {
                    _messages.value = _messages.value + ChatMessage.assistant(sb.toString())
                }
            } catch (t: Throwable) {
                _uiEvents.emit(UiEvent.AgentError(runId, t.message ?: t::class.simpleName ?: "unknown"))
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** 停止当前运行。 */
    fun stop() {
        runningJob?.cancel()
        runningJob = null
        _isRunning.value = false
    }

    /** 切换主/子 agent（占位：仅记 UI 状态，路由语义后续接 Orchestrator）。 */
    fun switchAgent(agentId: String) {
        _activeAgent.value = agentId
    }

    /** 清空对话。 */
    fun clear() {
        stop()
        _messages.value = emptyList()
    }
}

/** UI 侧状态切片（与各 StateFlow 解耦，便于预览 / 测试）。 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isRunning: Boolean = false,
    val activeAgent: String = "main",
    val lastRunId: String? = null,
    val pendingDeltas: String = "",
    val toolBubbles: List<com.agentm.core.model.ToolCall> = emptyList()
)
