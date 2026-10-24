package com.agentm.core

import okio.IOException

/**
 * LLM 调用/网关层的统一异常（继承 [IOException]，便于上层按 IO 错误统一处理）。
 *
 * 用 [Kind] 区分可恢复性，gateway fallback 与 orchestrator 重试策略据此决策：
 * - [Kind.UNREACHABLE] / [Kind.SERVER] / [Kind.AUTH] → 可切另一通道
 * - [Kind.BAD_REQUEST] → 参数问题，换通道无效
 * - [Kind.TOOL_EXECUTION] → 工具自身失败，属 agent 循环层错误
 */
class LlmException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Kind {
        /** 连接建立失败（host 不可达、超时）。 */
        UNREACHABLE,
        /** 认证/鉴权失败（401/403）。 */
        AUTH,
        /** 请求体/协议错误（400）。 */
        BAD_REQUEST,
        /** 服务端 5xx / 过载 429。 */
        SERVER,
        /** 工具执行阶段异常。 */
        TOOL_EXECUTION,
        /** 无法归类。 */
        UNKNOWN,
    }

    companion object {
        fun unreachable(msg: String, cause: Throwable? = null) =
            LlmException(Kind.UNREACHABLE, msg, cause)

        fun auth(msg: String, cause: Throwable? = null) =
            LlmException(Kind.AUTH, msg, cause)

        fun badRequest(msg: String, cause: Throwable? = null) =
            LlmException(Kind.BAD_REQUEST, msg, cause)

        fun server(msg: String, cause: Throwable? = null) =
            LlmException(Kind.SERVER, msg, cause)

        fun tool(msg: String, cause: Throwable? = null) =
            LlmException(Kind.TOOL_EXECUTION, msg, cause)

        fun unknown(msg: String, cause: Throwable? = null) =
            LlmException(Kind.UNKNOWN, msg, cause)

        /** 把原始异常映射到最贴切的 [Kind]。 */
        fun mapCause(cause: Throwable): Kind = when {
            cause is LlmException -> cause.kind
            cause is java.net.UnknownHostException -> Kind.UNREACHABLE
            cause is java.net.ConnectException -> Kind.UNREACHABLE
            cause is java.net.SocketTimeoutException -> Kind.UNREACHABLE
            else -> Kind.UNKNOWN
        }
    }
}
