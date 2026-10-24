# AgentM 开源参考调研：核心模式与可借鉴抽象

> task-8 交付物之一。本文件逐项目给出：GitHub 链接、核心目录/文件、关键抽象、对 AgentM 哪个模块的借鉴点、注意事项。
> 源码已在本机 `AgentM/x-*` 与 `AgentM/x-crewai2`、`x-agents2`、`x-autogen-py`、`osrc-ollama/` 解压/拉取，下文路径相对这些目录。
> 配套落地映射见 [integration-notes.md](./integration-notes.md)。

## 一、多 Agent 编排

### 1. LangGraph — graph/state/channel 模型

- **仓库**：https://github.com/langchain-ai/langgraph
- **核心目录/文件**：
  - `libs/langgraph/langgraph/graph/state.py` — `StateGraph`：声明状态 schema（TypedDict/Pydantic）与节点、边
  - `libs/langgraph/langgraph/graph/_branch.py`、`_node.py` — 条件分支（conditional edges）与节点包装
  - `libs/langgraph/langgraph/pregel/`（`_loop.py`、`_algo.py`、`_checkpoint.py`）— Pregel 执行引擎：BSP 超步循环、`__pregel_*` 内部消息、checkpoint 持久化
  - `libs/langgraph/langgraph/channels/`（`last_value.py`、`topic.py`、`ephemeral_value.py`、`binop.py`）— 通道（channel）：节点间数据传递的抽象
  - `libs/langgraph/langgraph/types.py` — `Send`（动态分支 fan-out）、`Interrupt`（human-in-the-loop）、`Command`
- **关键抽象**：
  1. **State = 强类型共享字典**：整张图共享一个状态对象（TypedDict），节点读、节点写、按 `Annotated` reducer（如 `operator.add`）合并
  2. **节点（node）= 状态 → 状态更新的纯函数**；边（edge）= 节点间的流转；条件边（conditional edge）= 返回下一个节点名或 `Send` 列表的函数
  3. **Channel**：每个状态字段是一条 channel；`LastValue`（谁最后写谁生效）、`Topic`（发布订阅）、`Binop`（带 reducer 合并）
  4. **Checkpoint**：每个超步（super-step）后可把 state 序列化到 saver（Memory/Postgres/SQLite），支持时间旅行与恢复
  5. **终止**：边显式指向 `END`；无出边的节点隐式终止；`__pregel_end` 信号
- **对 AgentM 的借鉴点**：
  - `orchestrator` 模块：主 agent 路由 + 子 agent 派发的「图」模型。`GraphSpec`（节点=AgentNode、边=路由函数、状态=`AgentState`）直接对应 StateGraph 的三件套
  - 条件路由（按意图选 UI 域/代码域/搜索域子 agent）= conditional edge
  - `AgentEvent` 流 = Pregel 的 `stream`/`astream` 事件流（node start/end、state update、checkpoint）
  - 循环终止：`max_rounds` + 显式 `END` 节点 + 条件边返回 `END`
  - 可观测性：每超步 dump state diff 到 `AgentEvent`（对应 LangSmith 的 trace 思路）
- **注意事项**：
  - LangGraph 本身重（Pregel 引擎、checkpoint 序列化、LangChain 依赖）。**不要整库搬运**，只借鉴「State 字典 + 节点纯函数 + 条件边 + 超步循环 + END 终止」五个概念
  - Android 端 checkpoint 不必用 Postgres：用 `AgentState` 序列化到文件/Room 即可
  - `Send` 动态 fan-out 在多 agent 场景少用，先做静态 DAG + 条件边，够用

### 2. AutoGen — agent/team/conversation

- **仓库**：https://github.com/microsoft/autogen（v0.4+，本调研拉取 `main` 分支 agentchat 包）
- **核心目录/文件**：
  - `python/packages/autogen-agentchat/src/autogen_agentchat/base/_team.py` — `Team` ABC：`name`/`description`/`reset`/`pause`/`resume`/`save_state`/`load_state`
  - `.../base/_task.py` — `TaskRunner` Protocol：`run(task, cancellation_token)` → `TaskResult(messages, stop_reason)`；`run_stream` 返回 `AsyncGenerator<BaseAgentEvent | BaseChatMessage | TaskResult>`
  - `.../base/_chat_agent.py` — `BaseChatAgent`：agent 最小接口（name/description/system_message + `on_messages` 处理）
  - `.../base/_handoff.py` — `Handoff`（target/description/message）+ `handoff_tool`：把「转交」变成一个 FunctionTool
  - `.../conditions/_terminations.py` — 终止条件族：`StopMessageTermination`、`MaxMessageTermination`、`TextMentionTermination`、`FunctionalTermination`（自定义谓词）、`TokenUsageTermination`、`TimeoutTermination`、`ExternalTermination`
  - `.../teams/_group_chat/` — 群聊团队：speaker_selection_method（auto/round_robin/manual）、termination_conditions 列表、message_channel
- **关键抽象**：
  1. **Team = 一群 Agent + 消息通道 + 终止条件列表**；`run` 是状态机（可暂停/恢复）
  2. **消息 = 数据类**（`TextMessage`/`HandoffMessage`/`StopMessage`/`ToolCallExecutionEvent` 等 `BaseAgentEvent | BaseChatMessage` 分层）
  3. **终止 = 可组合谓词列表**，每个谓词收消息流返回 `StopMessage` 或 None；命中即停并给 `stop_reason`
  4. **Handoff = 工具化**：LLM 看到 `transfer_to_<agent>` 工具，调用即完成转交
- **对 AgentM 的借鉴点**：
  - `orchestrator`：主 agent（router）+ 子 agent 群 + 终止条件组合器（`max_rounds` + `stop_keyword` + 超时 + 自定义谓词 四选一或合取）
  - `AgentEvent` 设计对齐 `BaseAgentEvent | BaseChatMessage` 分层（事件 vs 消息二分）
  - `TaskResult.stop_reason` 作为 agent 轮次结束的结构化字段（比裸字符串好）
- **注意事项**：
  - AutoGen 的事件模型偏复杂（`ToolCallExecutionEvent`、`HandoffMessage` 等十几种）；Android 端裁剪到 4–6 种事件即可
  - 终止条件做成 **可插拔列表** 是亮点，直接抄；谓词签名 `(Sequence<AgentEvent>) -> String?`（返回 stop_reason）

### 3. OpenAI Agents SDK — agent/handoff/tool

- **仓库**：https://github.com/openai/openai-agents-python
- **核心目录/文件**：
  - `src/agents/agent.py` — `Agent`（dataclass）：`model`/`instructions`/`tools`/`handoffs`/`input_guardrails`/`output_guardrails`/`output_type`
  - `src/agents/run.py` — `Runner.run(agent, input, max_turns)`；循环：turn 内 LLM 调用 → 工具执行 → 若 handoff 则换 agent 重跑 → `current_turn > max_turns` 抛 `MaxTurnsExceeded`
  - `src/agents/result.py` — `RunResult`：`final_output`/`last_agent`/`new_items`/`raw_items`；`final_output_as(cls)` 强转
  - `src/agents/guardrail.py` — `InputGuardrail`/`OutputGuardrail`：每轮调用前后各跑一遍，命中 `tripwire` 抛 `GuardrailException`
- **关键抽象**：
  1. **Agent = model + instructions + tools + handoffs + guardrails 的轻量 dataclass**（不是类层次，是配置对象）
  2. **Handoff 也是配置对象**（指向另一个 Agent，`to_handoff` 自动派生成工具）
  3. **Runner 循环 = max_turns 硬上限**，turn 语义清晰（一次 LLM 调用 + 其后全部工具执行 = 1 turn）
  4. **Guardrail = 输入/输出双闸**，不侵入 LLM 调用本身
- **对 AgentM 的借鉴点**：
  - `orchestrator`：子 agent（UI 域/代码域/搜索域）定义成 `AgentSpec(model, systemPrompt, tools, handoffs)` 配置对象，而非 Kotlin 类继承
  - `max_turns` 语义（一次 LLM 调用 + 工具执行 = 1 turn）直接搬到 `AgentEvent` 流的轮次计数
  - `RunResult(finalOutput, lastAgent, items)` 作为 orchestrator 对 chat 模块的返回契约
- **注意事项**：
  - OpenAI 协议强耦合（Responses API）；借鉴的是**结构**（配置对象 + Runner 循环 + guardrail），不是协议
  - `max_turns` 超限要**可控降级**（交回 router 重新规划 or 直接 STOP 给用户看 stop_reason），不是静默吞掉

### 4. CrewAI — role/task/process

- **仓库**：https://github.com/crewAIInc/crewAI（monorepo，核心在 `lib/crewai/src/crewai/`）
- **核心目录/文件**：
  - `crew.py` — `Crew(agents, tasks, process)` + `kickoff()`：`process=sequential`（按 task 顺序执行）vs `process=hierarchical`（有 `manager_llm` 派活）
  - `task.py` — `Task(description, expected_output, agent, guardrail, async_execution)`：任务 = 描述 + 期望输出 + 指派 agent + 质检函数
  - `agent/core.py` — `Agent(role, goal, backstory, llm, tools, allow_delegation)`：角色 + 目标 + 人设三件套
  - `process.py` — `Process(sequential | hierarchical)` 枚举
- **关键抽象**：
  1. **Agent = role + goal + backstory 的文本配置**（注入 system prompt 的三要素）
  2. **Task = 描述 + expected_output**（期望输出是质检基准）
  3. **Process = 执行拓扑**（顺序 vs 层级管理）；`allow_delegation` 控制能否把任务转给别的 agent
  4. **Guardrail = 任务级质检函数**：跑完 task 调 `guardrail(output, task)`，不过则重试
- **对 AgentM 的借鉴点**：
  - `orchestrator` 里「子 agent 注册表」的描述方式：`AgentSpec(role, goal, backstory)` 比裸 systemPrompt 更可维护
  - Task 级 `guardrail`（质检函数 + 自动重试）可作为 orchestrator 的失败恢复机制
  - `expected_output` 字段是 agent 自评的锚点
- **注意事项**：
  - CrewAI 的 `allow_delegation` 容易死循环（A 转 B、B 转 A）；借鉴时加 **depth 上限** 或 **禁止转回来源**
  - 层级 process（manager 派活）在小规模（≤4 个 agent）里开销大于收益，先用 sequential + 条件路由

## 二、GUI Agent

### 5. Mobile-Agent — 截图→视觉→动作循环

- **仓库**：https://github.com/X-PLUG/MobileAgent（家族：v1/v2/v3/v3.5 + MobileAgent-E + GUI-Critic-R1）
- **核心目录/文件**（本调研取 v2 与 v3）：
  - `Mobile-Agent-v2/MobileAgent/`：`chat.py`（循环）、`prompt.py`（六动作空间）、`api.py`（OpenAI 兼容 VLM 调用）、`controller.py`（adb 执行）、`text_localization.py`/`icon_localization.py`（系统文件信息增强）
  - `Mobile-Agent-v3/mobile_v3/utils/mobile_agent_e.py` — `Manager`（规划）/`Executor`（单步动作）/`ActionReflector`（反思）/`Notetaker`（记忆）四角色 + `InfoPool` 共享状态
- **关键抽象**：
  1. **单循环**（v2）：`while round < MAX_ROUNDS`：截图 + 读 uiautomator XML → 组 prompt（截图 + 可点击信息 + 键盘状态 + 历史 + 记忆）→ VLM 输出 6 动作之一（`Open App` / `Tap(x,y)` / `Swipe(x1,y1,x2,y2)` / `Type(text)` / `System Button` / `Answer`）→ adb 执行 → 验证
  2. **多角色分工**（v3 / MobileAgent-E）：Manager 维护 plan（子目标列表）与「哪些已完成」、Executor 只负责当前页的单步动作、ActionReflector 判断「上一步是否符合预期」、Notetaker 写记忆；`InfoPool` 共享，`err_to_manager_thresh`（连续 2 次错误交还 Manager 重新规划）
  3. **动作空间封闭**（6 种），LLM 只许输出其中之一；坐标是**绝对像素**（v2）或带 9-grid 网格兜底（AppAgent 式）
  4. **失败恢复**：`error_flag` 进 prompt（「上一步没达到预期，请反思修正」）；连续 N 次错误升级给 Manager
- **对 AgentM 的借鉴点**：
  - `guiagent` 模块：整个循环（截图→VLM→动作→执行→验证→反思）照搬；`max_step` 硬上限
  - `InfoPool`（instruction/plan/completed_plan/last_action/error_flag/important_notes）= `GuiAgentState` 数据类
  - `err_to_manager_thresh` = 连续失败 N 次 → 触发重新规划的阈值（借鉴到 orchestrator 的失败回退）
  - 动作空间用封闭枚举（7 种：`TAP/LONG_PRESS/TYPE/SWIPE/SYSTEM_BUTTON/OPEN_APP/ANSWER`），对应 Android 的 `AccessibilityService` 动作
- **注意事项**：
  - Mobile-Agent 靠 adb 执行；**AgentM 在手机上跑，动作执行走 AccessibilityService + `input` 命令双通道**（见 DroidBot 节）
  - 截图分辨率与坐标系的归一化：VLM 看到的图被缩放后，坐标要按比例映射回原始分辨率
  - 键盘状态（能否 type）要进 prompt，否则模型会在没聚焦输入框时硬 type

### 6. AppAgent — 树基探索（tree-based exploration）

- **仓库**：https://github.com/TencentQQGYLab/AppAgent
- **核心目录/文件**：
  - `scripts/task_executor.py` — 主循环（`while round_count < MAX_ROUNDS`：截图 + uiautomator XML → LLM → 动作）
  - `scripts/and_controller.py` — `traverse_tree`（`uiautomator dump` XML 解析，过滤 `clickable=true`，元素 ID = `父ID_resourceId`）+ `AndroidController`（adb 执行 tap/swipe/text/long_press）
  - `scripts/prompts.py` — 两档动作空间：常规（带编号元素 `tap(element)`）+ grid 兜底（`tap(area, subarea)` 九宫格 3×3 子区）
  - `scripts/self_explorer.py` — 探索模式：自动点 + 前后截图对比生成 UI 文档（用于后续任务注入）
- **关键抽象**：
  1. **元素编号化**：XML 里 `clickable=true` 的元素按出现顺序编号（1,2,3…），画进截图上（数字标在元素中心）；LLM 只看编号不碰坐标
  2. **grid 兜底**：当目标元素没被编号（如 canvas/游戏）时，`grid()` 动作把屏幕切成 R×C 网格，`tap(area, subarea)` 用「格子 + 九子区」定位
  3. **文档复用**：`self_explorer` 先跑一遍自动探索生成「UI 元素功能描述」，任务执行时作为 `<ui_document>` 注入 prompt（相当于对 App 做预训练的 RAG）
- **对 AgentM 的借鉴点**：
  - `guiagent`：无障碍树（`AccessibilityService` 拿 `AccessibilityNodeInfo`）解析出 `clickable` 元素 → 编号 → 编号画进截图 → LLM 选编号。比纯坐标 VLM 准且便宜（本地小模型也能做）
  - grid 兜底作为「无障碍树拿不到目标元素」时的降级（canvas/地图/游戏）
  - 「预探索文档」思路：对常用 App 离线跑一遍探索，存 UI 图谱，任务时注入（**可选**，先不做）
- **注意事项**：
  - AppAgent 是 PC 侧驱动 adb；**AgentM 在设备上**，`uiautomator dump` 改由 AccessibilityService 的节点树替代（`AccessibilityNodeInfo` 比 XML dump 更实时）
  - 编号要**稳定**（同一元素跨轮不换号），否则模型会反复点错；AppAgent 用 `resource-id` 哈希做稳定 ID
  - grid 的 subarea（九子区）坐标系与 `area_to_xy` 换算容易写反，建议用 `row*cols + col` 行优先

### 7. CogAgent — 通用 GUI VLM

- **仓库**：https://github.com/THUDM/CogAgent（本调研 tarball 拉取异常未完整解压，以下基于 README 与已知的结构）
- **核心抽象**：
  1. **CogVLM 改的 7B VLM**，固定看 **1120×1120** 输入（分块 32×32 patch + 重叠拼接保留细节）
  2. 动作空间是**相对动作**（`MoveRelative`/`ClickRelative`，基于 64×64 网格的相对坐标），再映射回像素
  3. 面向**通用 GUI**（桌面/网页/手机混合），不针对 Android 无障碍树
- **对 AgentM 的借鉴点**：
  - 作为**纯视觉路径**的参考（当无障碍树拿不到时）；其「相对坐标 + 网格」设计对低分辨率输入友好
  - **不建议在 Android 端本地跑 7B VLM**（内存/功耗）；认知其架构即可，实际用云端 VLM 或 1B 级端侧模型
- **注意事项**：
  - 纯视觉方案延迟高、坐标精度依赖模型质量；**Android 上首选无障碍树（结构化）+ 截图（视觉）双通道**，纯视觉是兜底

### 8. DroidBot — 无障碍树驱动的状态机

- **仓库**：https://github.com/honeynet/droidbot
- **核心目录/文件**：
  - `droidbot/droidbot.py` — `DroidBot.start()`：connect → install_app → deploy env → `input_manager.start()` → stop
  - `droidbot/input_manager.py` — `InputManager`：事件队列 + 策略（`InputPolicy` 的 `start()` 里循环 `generate_event()` → `add_event()` → 执行）
  - `droidbot/input_policy.py` — `InputPolicy` ABC + `UtgBasedInputPolicy`（UI 覆盖树驱动）+ `RandomPolicy`
  - `droidbot/input_event.py` — 事件族：`KeyEvent`/`UIEvent`/`TouchEvent`/`LongTouchEvent`/`SwipeEvent`/`ScrollEvent`/`SetTextEvent`/`IntentEvent`/`KillAppEvent`
  - `droidbot/device_state.py` — `DeviceState`：`views`（节点树）+ `foreground_activity` + `activity_stack` + `background_services` + `screenshot`；`to_dict()`/`to_json()` 可序列化
  - `droidbot/adapter/` — 各通道适配器：`adb.py`/`cv.py`（OpenCV 截图）/`droidbot_app.py`（**无障碍**拿 views）/`droidbot_ime.py`（自定义 IME 输入）/`minicap.py`
- **关键抽象**：
  1. **状态 = 可序列化的 `DeviceState`**（节点树 + activity + 截图路径）；每步执行前 `get_current_state()`
  2. **策略可插拔**：`InputPolicy`（UTG 覆盖树、随机、RL）；Agent 化时把 policy 换成 LLM
  3. **事件是数据对象**（`InputEvent` 族），可回放、可存盘（`EventLog`）
  4. **多通道取 view**：`droidbot_app`（无障碍）优先、`minicap`/`cv`（OpenCV 视觉）兜底
- **对 AgentM 的借鉴点**：
  - `guiagent` 的状态模型：`GuiAgentState = 节点树 + 前台 activity + 截图路径`，每轮执行前刷新、可序列化到盘
  - 事件对象化（`TapEvent(x,y)`/`TypeEvent(text)`/`SystemKeyEvent(code)`）→ 便于 orchestrator 作为 tool 消费、便于回放
  - **双通道取视图**：无障碍（结构化、快、准）优先；视觉（截图 + VLM）兜底（无障碍拿不到的 canvas）
  - IME 通道：`droidbot_ime` 自定义输入法可稳定 type 任意字符（adb `input text` 不支持特殊字符/中文）
- **注意事项**：
  - DroidBot 的 view 抓取走 app 内无障碍服务 + adb pull；**AgentM 全在设备上**，无障碍树直接从 `AccessibilityService.onAccessibilityEvent` 拿，不需要 adb
  - 中文输入/特殊字符：走 ADB IME（`am broadcast -a ADB_INPUT_TEXT`）或自研 IME，`input text` 只能 ASCII

## 三、端侧 LLM

### 9. Ollama — 本地模型服务 API

- **仓库**：https://github.com/ollama/ollama（API 文档 `docs/api.md`，已拉到 `osrc-ollama/api.md`）
- **关键端点**（全部本地 `http://host:11434`）：
  | 端点 | 用途 | 流式 |
  |---|---|---|
  | `POST /api/chat` | 多轮对话 + `tools`（JSON）+ `think`（推理模型）+ `images`（多模态） | 是（NDJSON，末帧含统计） |
  | `POST /api/generate` | 补全 + `suffix` + `format`（JSON schema 约束） | 是 |
  | `GET /api/tags` | 本地模型列表（名称/大小/量化信息） | 否 |
  | `POST /api/pull` / `/api/push` | 拉取/推送模型（流式进度） | 是 |
  | `POST /api/show` | 模型元数据（Modelfile、参数） | 否 |
  | `POST /api/create` / `/api/copy` / `/api/delete`（`/api/tags` 外） | 模型管理 | — |
  | `POST /api/embed` / `/api/embeddings` | 向量 | 是/否 |
  | `GET /api/ps` | 当前已加载进内存的模型 | 否 |
  | `GET /api/version` | 服务端版本 | 否 |
  - 公共参数：`keep_alive`（默认 5min，控制模型驻留内存）、`options`（temperature/num_ctx/…）、`stream`
  - **`/api/chat` 的 `tools`** 与 OpenAI 完全同构（`{"type":"function","function":{...}}`），模型返回 `message.tool_calls`
- **关键抽象**：
  1. **NDJSON 流式**：每行一个 JSON 对象（`message.content` 增量 + `done` 标志），末行带 `done=true` + 统计（`eval_count`/`total_duration`）
  2. **模型即字符串**（`llama3.2:8b-q5_0`），`/api/tags` 返回量化信息
  3. **OpenAI 兼容层**：Ollama 另起 `/v1/chat/completions` 提供 OpenAI 协议（见 OpenAI Agents 节的双通道）
- **对 AgentM 的借鉴点**：
  - `localllm` 模块：`OllamaRepository` 直接按上表封装（`listModels`/`pullModel`/`chat`/`chatStream`/`isLoaded`）
  - 流式用 `Flow<LlmChunk>`：每行 NDJSON 解析一个 chunk，`done=true` 时结束
  - `keep_alive` 在设置页暴露（默认 5m，长会话可调高避免反复加载）
  - 模型元数据（量化/大小/是否多模态）存本地 Room/文件，`/api/tags` 拉一次缓存
- **注意事项**：
  - Ollama 跑在**手机本机**（Termux/proot）或**局域网另一台机**；`localllm` 要支持 `host` 可配（默认 `127.0.0.1:11434`）
  - `/api/chat` 流式里 `message.content` 可能为空字符串（思考型模型先输出 `thinking`），客户端要容忍
  - 拉模型（`/api/pull`）可能几 GB，走后台 + 进度流；失败要能断点续传（Ollama 层处理）

### 10. open-webui — 模型管理/流式 UI

- **仓库**：https://github.com/open-webui/open-webui
- **核心目录**（`backend/open_webui/`）：
  - `routers/`（`chat.py`、`models.py`、`connections.py`、`functions.py`）— REST 路由
  - `models/`（`model_models.py`、`chat_models.py`、`message_models.py`）— 数据模型（模型来源、对话、消息）
  - `functions.py` — 模型注册/管理（支持 **Ollama / OpenAI / 自定义 OpenAI 兼容** 三种来源）
  - `socket/`、`storage/` — 事件 socket + 本地文件存储
- **关键抽象**：
  1. **模型来源抽象**：一个 model 记录 = `{name, source(ollama/openai/custom), base_url, api_key, quant}`；UI 只管名字，后端路由到对应 provider
  2. **流式 UI**：`/chat` 走 NDJSON/SSE 增量渲染；前端维护「当前消息块」逐 token 追加
  3. **模型卡片**：`/models` 列表 + 拉取（ollama）+ 元信息（量化/上下文）
- **对 AgentM 的借鉴点**：
  - `app` 设置页：模型选择列表（本地 Ollama 模型 + 云端 OpenAI 兼容模型 两套），每张卡片显示来源/量化/可用工具
  - 流式渲染契约：`chat` 模块收 `Flow<LlmChunk>`，块 = `{deltaContent, isDone, stats?}`，UI 逐块追加
  - 模型管理（拉取/删除/keep_alive）做成 `OllamaRepository` 的方法，设置页直调
- **注意事项**：
  - open-webui 是 Python FastAPI + React，**不直接搬**；搬的是「模型来源抽象 + 卡片 + 流式块」三件套
  - 云端模型要支持 **OpenAI 兼容 base_url**（LiteLLM/OneAPI/任意网关），不只是 OpenAI 官方

### 11. LiteRT LLM / MediaPipe LLM — 端侧推理概念

- **仓库**：https://github.com/google/ai-edge-lite（LiteRT）；MediaPipe 在 https://github.com/google-ai-edge/mediapipe
- **核心抽象**（概念层，本调研未拉源码，基于公开 API）：
  1. **LiteRT LLM**：`.litl` 模型文件（GGUF 转），`LiteRtLlmInferenceManager` 加载；`generate(prompt, callback)` 流式 token 回调；**单进程内存驻留**
  2. **与 Ollama 的分工**：Ollama = 完整服务（多模型/拉取/管理，HTTP API）；LiteRT = 极简单模型嵌入（无 HTTP、无管理面，直接调）
- **对 AgentM 的借鉴点**：
  - 端侧模型**双形态**：主路径走 Ollama（功能全）；极致轻量场景（离线、小模型 <3B）可加载 LiteRT `.litl`（**二期**，一期不做）
  - 概念对齐：`LlmBackend` 接口统一 `chat/stream/pull/list`，Ollama 实现 + LiteRT 实现（二期）+ OpenAI 兼容实现（云端）三实现
- **注意事项**：
  - LiteRT 在 Android 上要 native 库 + 模型文件（几十 MB 起），分发成本高；**一期明确不做，只留接口位**
  - 端侧模型能力弱（小模型 tool call 经常出错），**GUI 动作决策优先用云端 VLM，端侧模型做摘要/路由**

## 四、双通道路由

### 12. LiteLLM — 统一 chat 接口 + fallback

- **仓库**：https://github.com/BerriAI/litellm
- **核心抽象**（`litellm/router.py` + `litellm/router_utils/`）：
  1. **`Router`**：`model_list`（部署组，`model → [deployments...]`）+ `routing_strategy`（least-busy/usage-based/simple-shuffle/prompt-based-routing 等）
  2. **`fallbacks`**：主模型组失败 → 按序试备用模型组（`fallbacks: ["openai/gpt-4", "anthropic/claude"]`）；`num_retries` + `cooldown_time`（失败部署冷却）
  3. **统一 OpenAI 协议**：所有 provider 收 OpenAI 格式请求，响应也转成 OpenAI 格式；客户端无感切换
  4. **`CooldownCache`**：失败部署进冷却，冷却期内不再路由给它
- **对 AgentM 的借鉴点**：
  - `core` 的 `LlmGateway`：`model → [primary, fallback...]` 的**有序通道链**；调用时按序尝试，失败（超时/4xx/5xx/连接失败）切下一通道，记 `cooldown`（一段时间内跳过坏通道）
  - 统一 `LlmRequest/LlmResponse` 数据类（对齐 OpenAI 协议），每个后端实现 `chat`/`chatStream` 方法
  - **本地 Ollama = 一条通道，云端 OpenAI 兼容 = 另一条通道**；gateway 按配置的主/备顺序走
  - 可观测：每次切换发 `AgentEvent`（`LlmFallbackOccurred(from, to, reason)`）
- **注意事项**：
  - **不是所有错误都 fallback**：4xx（如 400 参数错、401 鉴权错）**不切通道**（切了也错），只有 5xx/超时/连接失败才切
  - 冷却时间不能太短（否则坏通道反复被打）；建议 60s 起
  - 本地 Ollama 的「不可达」= 连接拒绝/超时，要快速失败（connect timeout 2s）避免卡死

### 13. OneAPI — 模型网关

- **仓库**：https://github.com/songqianzhi521/one-api
- **核心抽象**：
  1. **OpenAI 兼容的网关**：`base_url + model` 形式，一个域名代理多家模型（OpenAI/Claude/Gemini/本地 Ollama）；`model` 字段写 `provider/model`
  2. **渠道（channel）管理**：每渠道一后端 + 权重 + 优先级；请求按权重/轮询分发；渠道故障自动下线
  3. **统一鉴权**：一个 API key 访问所有渠道
- **对 AgentM 的借鉴点**：
  - 云端通道的配置形态：`{base_url, api_key, model}`（OpenAI 兼容三件套）——**AgentM 云端通道就用这个三件套**，不绑死 OpenAI 官方
  - 模型命名 `provider/model`（如 `openai/gpt-4o-mini`、`ollama/llama3.2`、`oneapi/claude-3`）作为 `LlmGateway` 的 model key
- **注意事项**：
  - OneAPI/LiteLLM 是**服务端网关**；AgentM 不内置网关，只实现**OpenAI 兼容客户端**（能连 LiteLLM/OneAPI/任意兼容端点即可）
  - 鉴权 key 存 `EncryptedFile`（Android Keystore），**不存明文**

## 跨项目共性提炼（给实现的「模式清单」）

| 模式 | 来源 | 落到 AgentM |
|---|---|---|
| 状态 = 强类型共享字典，节点纯函数更新 | LangGraph | `AgentState`（dataclass），节点 = `(AgentState) -> AgentEvent 流 + 更新后的 state` |
| 条件边（按输出选下一节点） | LangGraph | 主 router 按意图返回下一 agent 名 |
| 终止条件 = 可插拔谓词列表 | AutoGen | `Termination` 接口 + `MaxRounds/StopKeyword/Timeout/Custom` 组合 |
| Handoff = 工具化转交 | AutoGen/OpenAI | `transfer_to_<agent>` 注册为 tool，LLM 调用即转交 |
| 事件/消息二分 | AutoGen | `AgentEvent`（过程）vs `AgentMessage`（结果） |
| Guardrail = 输入/输出双闸 | OpenAI | 子 agent 前后各一道质检，命中抛异常 |
| max_turns 硬上限 + 超限可控 | OpenAI | 轮次计数，超限发 `AgentStopped(reason=MaxTurns)` |
| 封闭动作枚举 + 坐标 | Mobile-Agent/AppAgent | 7 动作 enum，坐标绝对像素 + 编号双定位 |
| 状态快照 = 节点树 + activity + 截图 | DroidBot | `GuiAgentState`，每轮刷新可序列化 |
| 双通道取视图（无障碍 + 视觉） | DroidBot/CogAgent | AccessibilityService 主，VLM 截图兜底 |
| 连续失败 N 次升级重规划 | Mobile-Agent-E | `err_threshold` 触发 Manager 重新出 plan |
| NDJSON 流式块 | Ollama/open-webui | `Flow<LlmChunk>`，`done` 标志结束 |
| 模型来源抽象（本地/云端/自定义） | open-webui | 模型卡片 + `LlmBackend` 三实现 |
| 通道链 + 快速失败 + 冷却 | LiteLLM | `LlmGateway` 主/备有序，4xx 不切、5xx 切 + 冷却 |
| OpenAI 兼容三件套 | OneAPI | 云端配置 `{base_url, api_key, model}` |

## 调研限制说明

- **CrewAI**：tarball 截断导致 `lib/crewai/src/crewai/agent.py` 未完整落盘（0 行），核心类信息取自 `task.py`/`crew.py`/`process.py`/`agent/core.py`（已单独 raw 拉取）；`Agent` 类的字段（role/goal/backstory/allow_delegation）取自 `agent/core.py` 的文档串与 LLM 调用上下文
- **CogAgent**：tarball 拉取异常未完整解压，以上基于 README 与公开架构描述（7B VLM、1120×1120、相对坐标网格）；未读源码
- **LiteRT/MediaPipe**：未拉源码（概念层，一期不做）；基于公开 API 文档
- **AutoGen**：tarball 截断缺 python 包，`x-autogen-py` 目录是 raw 补拉的关键 7 个文件（`_team/_task/_chat_agent/_handoff/_termination/_terminations/messages`）
- **OpenAI Agents**：`x-agents2` 是 raw 补拉的 `run.py/result.py/guardrail.py/handoffs.py/run_state.py`
