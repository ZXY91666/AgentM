package com.agentm.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentm.core.model.LlmConfig
import com.agentm.localllm.client.PullResult
import com.agentm.localllm.model.OllamaModel
import com.agentm.core.ui.amPressBounce
import kotlinx.coroutines.launch

/**
 * 设置屏：LLM 双通道配置（本地 Ollama / 云端 OpenAI 兼容）+ Ollama 连接测试 + 模型列表 / 拉取。
 *
 * 数据流（全部走 [ChatGraphHolder.ollamaRepo] 的真实 API，app 启动时绑定）：
 * - 本地：host 输入 + [testConnection(host)] + [setHost] 保存 + [listAvailable] + [pull(id)]
 * - 云端：baseUrl / apiKey / model 表单 + 配置完整性校验（LlmConfig.isUsable）
 *
 * 视觉：卡片分组（am_r_card=16dp 圆角），卡片间距 14dp（am_m_message）。
 */
@Composable
fun SettingsScreen() {
    val repo = ChatGraphHolder.ollamaRepo
    val scope = rememberCoroutineScope()

    // ---- 通道状态 ----
    var channel by remember { mutableStateOf("local") } // local | cloud

    // ---- 本地 Ollama ----
    var ollamaHost by remember { mutableStateOf(repo.host) }
    var ollamaTestResult by remember { mutableStateOf<String?>(null) }
    var ollamaTesting by remember { mutableStateOf(false) }
    var ollamaModels by remember { mutableStateOf<List<OllamaModel>>(emptyList()) }
    var ollamaLoading by remember { mutableStateOf(false) }
    var pullingModel by remember { mutableStateOf<String?>(null) }
    var pullResult by remember { mutableStateOf<Pair<String, PullResult>?>(null) }

    // ---- 云端 OpenAI 兼容 ----
    var cloudBaseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var cloudApiKey by remember { mutableStateOf("") }
    var cloudModel by remember { mutableStateOf("gpt-4o-mini") }
    var cloudTestResult by remember { mutableStateOf<String?>(null) }

    fun loadModels() {
        ollamaLoading = true
        scope.launch {
            val list = runCatching { repo.listAvailable() }.getOrElse { emptyList() }
            ollamaModels = list
            if (list.isEmpty()) ollamaTestResult = "⚠ 远端没有模型，请拉取一个"
            ollamaLoading = false
        }
    }

    fun testOllama() {
        ollamaTesting = true
        ollamaTestResult = null
        scope.launch {
            val host = ollamaHost.trim()
            if (host.isEmpty()) {
                ollamaTestResult = "✗ host 为空"
                ollamaTesting = false
                return@launch
            }
            val ok = runCatching { repo.testConnection(host) }.getOrElse { false }
            if (ok) {
                repo.setHost(host)
                ollamaTestResult = "✓ 连接成功：$host"
                // 连接成功后顺带拉一次模型列表
                loadModels()
            } else {
                ollamaTestResult = "✗ 连接失败，请检查 host / 端口（$host）"
            }
            ollamaTesting = false
        }
    }

    fun pullModel(id: String) {
        pullingModel = id
        pullResult = null
        scope.launch {
            val r = runCatching { repo.pull(id) }.getOrElse {
                PullResult(id, "拉取失败：${it.message}", completed = false)
            }
            pullResult = id to r
            pullingModel = null
        }
    }

    fun testCloud() {
        cloudTestResult = null
        val cfg = LlmConfig(
            provider = "openai",
            baseUrl = cloudBaseUrl.trim(),
            apiKey = cloudApiKey.trim(),
            model = cloudModel.trim()
        )
        cloudTestResult = when {
            !cfg.isUsable -> "✗ 配置不完整（需 baseUrl + apiKey + model）"
            else -> "✓ 配置完整，可发起请求（连通性探测待 LlmGateway 落地）"
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            verticalArrangement = Arrangement.spacedBy(14.dp), // am_m_message
            contentPadding = PaddingValues(top = 14.dp, bottom = 14.dp, start = 14.dp, end = 14.dp)
        ) {
            // ---------- 通道切换 ----------
            item {
                SettingsCard(title = "LLM 通道") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChannelChip("本地 Ollama", channel == "local") { channel = "local" }
                        ChannelChip("云端 OpenAI 兼容", channel == "cloud") { channel = "cloud" }
                    }
                }
            }

            // ---------- 本地 Ollama ----------
            if (channel == "local") {
                item {
                    SettingsCard(title = "本地 Ollama") {
                        OutlinedTextField(
                            value = ollamaHost,
                            onValueChange = { ollamaHost = it },
                            label = { Text("Host（http://host:11434，手机访问本机用 10.0.2.2）") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallButton(
                                if (ollamaTesting) "测试中…" else "测试连接",
                                onClick = { testOllama() },
                                enabled = !ollamaTesting
                            )
                            SmallButton(
                                if (ollamaLoading) "拉取列表中…" else "模型列表",
                                onClick = { loadModels() },
                                enabled = !ollamaLoading
                            )
                        }
                        if (ollamaTestResult != null) {
                            val testRes = ollamaTestResult!!
                            Spacer(Modifier.height(8.dp))
                            Text(
                                testRes,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (testRes.startsWith("✓")) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
                            )
                        }

                        // 模型列表
                        if (ollamaModels.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "模型（${ollamaModels.size}）",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            ollamaModels.forEach { m ->
                                ModelRow(
                                    model = m,
                                    pulling = pullingModel == m.id,
                                    pullStatus = pullResult?.takeIf { it.first == m.id }?.second,
                                    onPull = { pullModel(m.id) }
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 云端 OpenAI 兼容 ----------
            if (channel == "cloud") {
                item {
                    SettingsCard(title = "云端 OpenAI 兼容") {
                        OutlinedTextField(
                            value = cloudBaseUrl,
                            onValueChange = { cloudBaseUrl = it },
                            label = { Text("Base URL") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cloudApiKey,
                            onValueChange = { cloudApiKey = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = cloudModel,
                            onValueChange = { cloudModel = it },
                            label = { Text("Model") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        SmallButton("验证配置", onClick = { testCloud() })
                        if (cloudTestResult != null) {
                            val cloudRes = cloudTestResult!!
                            Spacer(Modifier.height(8.dp))
                            Text(
                                cloudRes,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (cloudRes.startsWith("✓")) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error
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

/** 设置卡片：标题 + 内容插槽。 */
@Composable
fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp), // am_r_card
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), // am_el_1
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** 通道切换 chip。 */
@Composable
fun ChannelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp), // am_r_chip
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.amPressBounce(onTap = onClick)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 小按钮。 */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.amPressBounce(onTap = { if (enabled) onClick() })
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 模型行：id + 量化/参数量化 + 拉取按钮 + 结果。 */
@Composable
fun ModelRow(model: OllamaModel, pulling: Boolean, pullStatus: PullResult?, onPull: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                model.id,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            val meta = listOfNotNull(
                model.parameters.takeIf { it.isNotEmpty() },
                model.quantization.takeIf { it.isNotEmpty() }
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            pullStatus?.let { ps ->
                Text(
                    if (ps.completed) "✓ ${ps.status}" else "⏳ ${ps.status}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ps.completed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (pulling) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.amPressBounce(onTap = onPull)
        ) {
            Text(
                if (pulling) "拉取中…" else "拉取",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (pulling) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
