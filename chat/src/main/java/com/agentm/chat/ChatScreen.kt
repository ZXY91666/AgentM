package com.agentm.chat

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType.Companion.KeyDown
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agentm.core.model.ChatMessage
import com.agentm.core.model.ChatRole
import com.agentm.core.model.ToolCall
import com.agentm.core.ui.amPressBounce

/**
 * 聊天界面：LazyColumn 消息列表 + 流式渲染 + 工具调用气泡 + 停止按钮。
 *
 * 视觉节奏（对应 res dimens am_*）：
 * - 气泡圆角 12dp（am_r_bubble）、消息间距 14dp（am_m_message）
 * - 输入栏 48dp（am_size_input_bar），输入框圆角 18dp（am_r_input）
 * - 用户气泡 = primary；助手气泡 = surfaceVariant；工具气泡 = tertiaryContainer
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val activeAgent by viewModel.activeAgent.collectAsState()

    // 本地 UI 缓冲：正在流式输出的 assistant 文本 + 工具气泡（toolCallId → 信息）
    var streamingText by remember { mutableStateOf("") }
    var toolBubbles by remember { mutableStateOf<Map<String, ToolBubbleInfo>>(emptyMap()) }
    var input by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // 订阅 UI 事件：增量 / 工具气泡 / 完成 / 错误
    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { ev ->
            when (ev) {
                is ChatViewModel.UiEvent.AgentDelta -> streamingText += ev.text
                is ChatViewModel.UiEvent.AgentToolCall ->
                    toolBubbles = toolBubbles + (ev.toolCall.id to ToolBubbleInfo(ev.toolCall))
                is ChatViewModel.UiEvent.AgentToolResult ->
                    toolBubbles = toolBubbles.mapValues { (k, v) ->
                        if (k == ev.toolCallId) v.copy(result = ev.content, resultReady = true, isError = ev.isError)
                        else v
                    }
                is ChatViewModel.UiEvent.AgentDone -> streamingText = ""
                is ChatViewModel.UiEvent.AgentError ->
                    streamingText = if (streamingText.isEmpty()) "⚠ ${ev.message}"
                    else "$streamingText\n⚠ ${ev.message}"
                is ChatViewModel.UiEvent.AgentStarted -> Unit
            }
        }
    }

    fun onSend() {
        val text = input.trim()
        if (text.isEmpty() || isRunning) return
        input = ""
        streamingText = ""
        toolBubbles = emptyMap()
        viewModel.send(text)
    }

    // 列表内容总数（用于自动滚到底）
    val userItems = messages.count { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
    val totalItems = userItems + (if (streamingText.isNotEmpty()) 1 else 0) + toolBubbles.size

    LaunchedEffect(totalItems, streamingText) {
        if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AgentM Chat", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "agent: $activeAgent",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.Add, contentDescription = "新会话")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---------- 消息列表 ----------
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(14.dp), // am_m_message
                contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp, start = 14.dp, end = 14.dp)
            ) {
                items(messages.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }) { msg ->
                    if (msg.role == ChatRole.USER) UserBubble(msg) else AssistantBubble(msg)
                }
                if (streamingText.isNotEmpty()) {
                    item { StreamingBubble(text = streamingText, running = isRunning) }
                }
                toolBubbles.forEach { (id, info) ->
                    item(key = "tool_$id") { ToolCallBubble(info = info) }
                }
            }

            // ---------- 输入区 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp) // am_m_sm
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .onKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyDown) {
                                onSend()
                                true
                            } else false
                        },
                    placeholder = { Text("向 AgentM 提问…", style = MaterialTheme.typography.bodyMedium) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    shape = RoundedCornerShape(18.dp) // am_r_input
                )

                // 发送 / 停止 按钮（同一槽位，按压弹簧回弹）
                Box(
                    modifier = Modifier
                        .amPressBounce(onTap = {
                            if (isRunning) viewModel.stop() else onSend()
                        })
                        .size(48.dp) // am_size_input_bar
                ) {
                    if (isRunning) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "停止",
                            modifier = Modifier.align(Alignment.Center).padding(10.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            modifier = Modifier.align(Alignment.Center).padding(10.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// 子组件
// ============================================================

/** 工具气泡信息：调用 + 结果状态。 */
data class ToolBubbleInfo(
    val toolCall: ToolCall,
    val result: String = "",
    val resultReady: Boolean = false,
    val isError: Boolean = false
)

/** 用户消息气泡：右对齐，primary 底。 */
@Composable
private fun UserBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Card(
            shape = RoundedCornerShape(12.dp), // am_r_bubble
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = msg.content ?: "",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp) // am_m_bubble_h / v
            )
        }
        Spacer(modifier = Modifier.size(4.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("U", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiary)
            }
        }
    }
}

/** 助手消息气泡：左对齐，surfaceVariant 底。 */
@Composable
private fun AssistantBubble(msg: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("A", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondary)
            }
        }
        Spacer(modifier = Modifier.size(4.dp))
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                text = msg.content ?: "",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}

/** 正在流式输出的助手气泡：带打字点。 */
@Composable
private fun StreamingBubble(text: String, running: Boolean) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )
            if (running) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { TypingDot(it) }
                }
            }
        }
    }
}

/** 打字指示点：脉动 alpha。 */
@Composable
private fun TypingDot(index: Int) {
    val transition = rememberInfiniteTransition(label = "typing-dot-$index")
    val alpha = transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 800
                0.2f at 0
                1f at 400
                0.2f at 800
            }
        ),
        label = "typing-dot-$index"
    )
    Box(
        modifier = Modifier
            .size(5.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .alpha(alpha.value)
    )
}

/** 工具调用气泡：工具名 + 参数 + 结果（teal 调，入场用 Bouncy 弹簧）。 */
@Composable
private fun ToolCallBubble(info: ToolBubbleInfo) {
    val accent = if (info.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary

    Surface(
        shape = RoundedCornerShape(8.dp), // am_r_chip
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accent
                )
                Text(
                    text = "Tool · ${info.toolCall.function.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            val args = info.toolCall.function.arguments
            if (args.isNotEmpty() && args != "{}") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "args: ${args.take(200)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (info.resultReady) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (info.isError) "⚠ ${info.result}" else "✓ ${info.result}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (info.isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "执行中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
