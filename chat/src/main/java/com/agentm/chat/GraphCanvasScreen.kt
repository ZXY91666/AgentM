package com.agentm.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.menuAnchor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agentm.app.AgentMApplication
import com.agentm.orchestrator.persistence.GraphSpec
import com.agentm.core.ui.amPressBounce

/**
 * 编排画布：节点画布（纵向可滚动）、节点增删、连线（from/to 下拉选择）、保存为 GraphSpec JSON。
 *
 * 数据模型：
 * - [CanvasNode]：画布节点（id + agent 引用 + 标签）
 * - [CanvasEdge]：连线（from + to + 可选 condition）
 * - 保存：序列化为 [GraphSpec] JSON，交 [com.agentm.orchestrator.orchestrator.Orchestrator.loadGraphSpec]
 *   加载注册（可重复覆盖同名图）
 *
 * 连线简化：每个节点带「→ 目标」下拉（选择目标即建边）；边在底部面板列出、可单独删除。
 * 节点属性编辑：agent 选择（ExposedDropdown）+ 标签输入（写入 GraphSpec.node.config）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphCanvasScreen() {
    // ---- 画布状态 ----
    var graphId by remember { mutableStateOf("graph-1") }
    var nodes by remember { mutableStateOf<List<CanvasNode>>(listOf(CanvasNode("n1", "ui", "n1"))) }
    var edges by remember { mutableStateOf<List<CanvasEdge>>(emptyList()) }
    var saveMsg by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    // 内置 agent 候选（来自 SubAgents.defs）
    val agentOptions = remember {
        com.agentm.orchestrator.agent.SubAgents.defs().map { it.id }
    }

    fun addNode() {
        val id = "n${nodes.size + 1}"
        nodes = nodes + CanvasNode(id = id, agent = agentOptions.firstOrNull() ?: "ui", label = id)
        saveMsg = ""
    }

    fun removeNode(id: String) {
        nodes = nodes.filter { it.id != id }
        edges = edges.filter { it.from != id && it.to != id }
    }

    fun addEdge(from: String, to: String) {
        if (from == to || edges.any { it.from == from && it.to == to }) return
        edges = edges + CanvasEdge(from, to)
    }

    fun updateNode(id: String, agent: String) {
        nodes = nodes.map { if (it.id == id) it.copy(agent = agent) else it }
    }

    fun updateLabel(id: String, label: String) {
        nodes = nodes.map { if (it.id == id) it.copy(label = label) else it }
    }

    /** 序列化为 GraphSpec JSON。 */
    fun buildJson(): String {
        val spec = GraphSpec(
            id = graphId,
            entry = nodes.firstOrNull()?.id ?: "",
            nodes = nodes.map {
                GraphSpec.NodeSpec(id = it.id, agent = it.agent, config = mapOf("label" to it.label))
            },
            edges = edges.map {
                GraphSpec.EdgeSpec(from = it.from, to = it.to, condition = it.condition)
            }
        )
        return GraphSpec.toJson(spec)
    }

    fun save() {
        if (nodes.isEmpty()) {
            errorMsg = "画布为空，无法保存"
            return
        }
        val json = buildJson()
        try {
            AgentMApplication.app.orchestrator.loadGraphSpec(json)
            saveMsg = "已保存 ${json.length} 字符 JSON，图「$graphId」已注册到 Orchestrator"
            errorMsg = ""
        } catch (t: Throwable) {
            errorMsg = "保存失败：${t.message}"
            saveMsg = ""
        }
    }

    val listState = rememberLazyListState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("编排画布", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${nodes.size} 节点 · ${edges.size} 连线",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { addNode() }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("添加节点")
                    }
                    TextButton(onClick = { save() }) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("保存 JSON")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp), // am_m_message
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 14.dp, bottom = 14.dp, start = 14.dp, end = 14.dp
            )
        ) {
            // 图 id
            item(key = "graph-id") {
                OutlinedTextField(
                    value = graphId,
                    onValueChange = { graphId = it },
                    label = { Text("图 ID（保存时注册为同名图）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 保存 / 错误提示
            if (saveMsg.isNotEmpty() || errorMsg.isNotEmpty()) {
                item(key = "msg") {
                    Text(
                        if (saveMsg.isNotEmpty()) saveMsg else errorMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (saveMsg.isNotEmpty()) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
            }

            // 节点卡片
            nodes.forEach { node ->
                item(key = "node_${node.id}") {
                    NodeCard(
                        node = node,
                        agentOptions = agentOptions,
                        candidates = nodes.filter { it.id != node.id }.map { it.id },
                        onAgentChange = { updateNode(node.id, it) },
                        onLabelChange = { updateLabel(node.id, it) },
                        onAddEdge = { addEdge(node.id, it) },
                        onRemove = { removeNode(node.id) }
                    )
                }
            }

            // 连线面板
            if (edges.isNotEmpty()) {
                item(key = "edges") {
                    EdgePanel(edges = edges) { e ->
                        edges = edges.filter { it !== e }
                    }
                }
            }

            if (nodes.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "画布为空。点击顶部「添加节点」创建第一个节点。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

// ============================================================
// 数据模型（画布侧，保存时映射到 core GraphSpec）
// ============================================================

/** 画布节点：id + agent 引用 + 标签（标签写入 GraphSpec.node.config）。 */
data class CanvasNode(
    val id: String,
    val agent: String,
    var label: String
)

/** 连线：from + to + 可选 condition。 */
data class CanvasEdge(
    val from: String,
    val to: String,
    val condition: String? = null
)

// ============================================================
// 子组件
// ============================================================

/** 节点卡片：属性编辑（agent/标签）+ 连线（选目标建边）+ 删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeCard(
    node: CanvasNode,
    agentOptions: List<String>,
    candidates: List<String>,
    onAgentChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onAddEdge: (String) -> Unit,
    onRemove: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var label by remember(node.label) { mutableStateOf(node.label) }

    Card(
        shape = RoundedCornerShape(16.dp), // am_r_card
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .amPressBounce(onTap = {})
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行：节点 id + 删除
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp), // am_r_chip
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                ) {
                    Text(
                        node.id,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    "节点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除节点",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // agent 选择（下拉）
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it }
            ) {
                OutlinedTextField(
                    value = node.agent,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Agent（节点角色）") },
                    trailingIcon = {
                        IconButton(onClick = { menuExpanded = !menuExpanded }) {
                            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    agentOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onAgentChange(option)
                                menuExpanded = false
                            }
                        )
                    }
                }
            }

            // 标签（写入 GraphSpec.node.config["label"]）
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it; onLabelChange(it) },
                label = { Text("标签（保存进 config）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // 连线：选目标节点建边
            if (candidates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "连线（$node.id →）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    candidates.forEach { c ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.amPressBounce(onTap = { onAddEdge(c) })
                        ) {
                            Text(
                                c,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 连线面板：列出所有边，可单独删除。 */
@Composable
fun EdgePanel(edges: List<CanvasEdge>, onDelete: (CanvasEdge) -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "连线（${edges.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            edges.forEach { e ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        "${e.from} → ${e.to}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (e.condition != null) {
                        Text(
                            "[${e.condition}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onDelete(e) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除连线",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
