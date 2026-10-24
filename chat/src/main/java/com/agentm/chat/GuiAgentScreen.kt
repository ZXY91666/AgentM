package com.agentm.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentm.guiagent.agent.GuiAction
import com.agentm.guiagent.agent.GuiAgentLoop
import com.agentm.guiagent.agent.GuiTask
import com.agentm.guiagent.service.GuiAgentController
import com.agentm.core.ui.amPressBounce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GUI Agent 屏：任务输入 + 开始/停止 + 实时事件流（截图占位 + 动作日志）。
 *
 * 数据流：
 * - 用户输入 goal / successCriteria / maxSteps → [GuiTask]
 * - [start] 用占位 [GuiAgentLoop.VisionBackend]（未接入真实视觉 LLM 时返回 Done，
 *   避免无监督执行破坏设备；接 LlmGateway 后替换为真实 decide）
 * - 每步 [GuiAgentLoop.StepResult] 推入日志；[stop] 取消协程保留已完成步骤
 *
 * 视觉：
 * - 截图占位：固定卡片，深色画布底（am_canvas_bg）+ 网格纹理
 * - 事件日志：LazyColumn，每步卡片带序号徽标 + 动作名 + reasoning
 */
@Composable
fun GuiAgentScreen() {
    // controller 每次访问重新解析 GuiAgentService.instance（服务开关无需重建）
    val controller = remember { GuiAgentController() }
    val scope = rememberCoroutineScope()

    // ---- 任务输入 ----
    var goal by remember { mutableStateOf("") }
    var successCriteria by remember { mutableStateOf("") }
    var maxSteps by remember { mutableStateOf(12) }
    var runningJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var serviceEnabled by remember { mutableStateOf(GuiAgentServiceState.check()) }

    // ---- 事件日志 ----
    var logLines by remember { mutableStateOf<List<LogLine>>(emptyList()) }

    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }

    // 服务状态轮询（无障碍服务开关后自动更新）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            serviceEnabled = GuiAgentServiceState.check()
        }
    }

    fun start() {
        if (goal.isBlank() || isRunning) return
        isRunning = true
        logLines = listOf(LogLine(0, "START", "任务启动：${goal.take(60)}", true))

        // 占位视觉后端：未接入真实 LLM 时安全终止，不执行破坏性动作
        val visionBackend = object : GuiAgentLoop.VisionBackend {
            override fun decide(
                task: GuiTask,
                stepIndex: Int,
                screenshotBase64: String?,
                screenshotFormat: String,
                elements: List<com.agentm.guiagent.service.UiElement>,
                history: List<GuiAction>
            ): GuiAction = GuiAction.Done(
                success = true,
                summary = "占位后端：未接入视觉 LLM，安全终止",
                reasoning = "step $stepIndex placeholder"
            )
        }

        val loop = GuiAgentLoop(controller, visionBackend)
        val task = GuiTask(
            goal = goal,
            successCriteria = successCriteria.ifBlank { "LLM 认为任务目标已达成" },
            maxSteps = maxSteps.coerceIn(1, 64)
        )

        runningJob = scope.launch {
            val result = withContext(Dispatchers.IO) { loop.run(task) }
            result.steps.forEach { step ->
                logLines = logLines + LogLine(
                    index = step.stepIndex + 1,
                    action = describeAction(step.action),
                    reasoning = step.observation ?: step.action.reasoning,
                    ok = step.executed
                )
            }
            logLines = logLines + LogLine(
                index = result.steps.size + 1,
                action = "FINAL",
                reasoning = "success=${result.success} · ${describeAction(result.finalAction)}",
                ok = result.success
            )
            isRunning = false
            runningJob = null
        }
    }

    fun stop() {
        runningJob?.cancel()
        runningJob = null
        isRunning = false
        logLines = logLines + LogLine(logLines.size + 1, "STOP", "用户停止", false)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GUI Agent", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (serviceEnabled) "无障碍服务已启用"
                            else "⚠ 无障碍服务未启用（设置 → 无障碍 → AgentM GUI Agent）",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (serviceEnabled) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---------- 任务输入 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = { Text("任务目标") },
                    placeholder = { Text("例如：打开设置，进入 Wi-Fi 页面") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp) // am_r_input
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = successCriteria,
                        onValueChange = { successCriteria = it },
                        label = { Text("成功判定（可选）") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxSteps.toString(),
                        onValueChange = { maxSteps = it.toIntOrNull() ?: 12 },
                        label = { Text("maxSteps") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.width(90.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.amPressBounce(onTap = { start() })
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "开始",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                    if (isRunning) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.amPressBounce(onTap = { stop() })
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "停止",
                                    color = MaterialTheme.colorScheme.onError,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 截图占位 + 事件日志 ----------
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ScreenshotPlaceholder(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 14.dp)
                ) {
                    items(logLines) { line -> LogLineCard(line = line) }
                    if (logLines.isEmpty()) {
                        item {
                            Text(
                                "暂无事件。填写任务后点「开始」。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 子组件
// ============================================================

/** 日志行。 */
data class LogLine(
    val index: Int,
    val action: String,
    val reasoning: String,
    val ok: Boolean
)

/** 无障碍服务状态（读 GuiAgentService.instance，解耦屏幕与 service 生命周期）。 */
object GuiAgentServiceState {
    fun check(): Boolean =
        com.agentm.guiagent.service.GuiAgentService.instance != null
}

/** 描述一个 GuiAction 为短文本。 */
private fun describeAction(a: GuiAction): String = when (a) {
    is GuiAction.Tap -> "Tap(${a.x},${a.y})" + (a.elementIndex?.let { " el[$it]" } ?: "")
    is GuiAction.Swipe -> "Swipe(${a.x1},${a.y1}→${a.x2},${a.y2})"
    is GuiAction.Input -> "Input(${a.text})"
    is GuiAction.Back -> "Back"
    is GuiAction.Launch -> "Launch(${a.packageName})"
    is GuiAction.Done -> "Done(success=${a.success})"
    is GuiAction.Error -> "Error(${a.message})"
}

/** 截图占位：深色画布 + 网格 + 中央说明。 */
@Composable
fun ScreenshotPlaceholder(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp), // am_r_card
        color = Color(0xFF141218) // am_canvas_bg（与 res am_canvas_bg 一致）
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x22FFFFFF)) // am_canvas_grid
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "截图占位",
                    color = accent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "等待无障碍服务启用后\n展示真实截图",
                    color = Color(0xFFC7CAD6),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

/** 一条日志卡片：序号徽标 + 动作名 + reasoning。 */
@Composable
fun LogLineCard(line: LogLine) {
    Card(
        shape = RoundedCornerShape(12.dp), // am_r_bubble
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (line.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            line.index.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiary,
                            fontSize = 9.sp
                        )
                    }
                }
                Text(
                    line.action,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (line.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (line.reasoning.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    line.reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
