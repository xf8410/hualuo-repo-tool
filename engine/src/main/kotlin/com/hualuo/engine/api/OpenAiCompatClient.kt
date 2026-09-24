package com.hualuo.engine.api

import com.hualuo.engine.generation.GenerationSlot
import com.hualuo.engine.generation.IdleWatchdog
import com.hualuo.engine.http.FailureClass
import com.hualuo.engine.http.RequestCost
import com.hualuo.engine.http.RetryDecision
import com.hualuo.engine.http.RetryPolicy
import com.hualuo.engine.toolcalls.AssembledToolCall
import com.hualuo.engine.toolcalls.ToolSpec
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 一家提供商的接线所需的最小画像。
 *
 * 密钥就这一个字段、明文（D-10 拍板）：往外带的一切（日志、报错、界面）都必须先过
 * 零脱敏纪律：错误原文一字不改直通，任何一层都不打码。
 */
data class ProviderProfile(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

/** 历史里一条工具调用的回填形状（assistant 消息里的 tool_calls 条目）。 */
data class WireToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * 一条对话消息（role 用 OpenAI 词汇：system/user/assistant/tool）。
 *
 * 工具协议的两处扩展（都有默认值，老调用点一字不动）：
 *  - assistant 消息带 [toolCalls]：模型上一轮要调的活，回填历史时原样带上；
 *  - tool 消息带 [toolCallId]：某次工具执行的结果，id 对上才认账。
 */
data class ChatTurn(
    val role: String,
    val content: String,
    val toolCalls: List<WireToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** 一轮对话的收场（tool_calls 协议下的三态）。 */
sealed class ChatOutcome {
    object Text : ChatOutcome()
    data class Calls(val calls: List<AssembledToolCall>) : ChatOutcome()
    data class Failed(val error: GenerationError) : ChatOutcome()
}

/**
 * OpenAI 兼容客户端：设置里填一家，这里就能真干活的那层。
 *
 * 现在仍保留原类的窄口，但会先问 ProviderRouting：如果当前模型属于 Google、Anthropic
 * 或 Ollama，就交给 [ProviderClient] 的原生协议实现；如果没有新设置，旧三键仍按原路径走。
 * 这样 UI 与 ChatRuntime 的旧调用点不被迫重写，模型设置却已经是请求真路由的单份事实。
 */
class OpenAiCompatClient(
    private val transport: WireTransport,
    private val slot: GenerationSlot,
    private val policy: RetryPolicy,
    private val watchdog: IdleWatchdog,
    private val sleeper: (Long) -> Unit = ::napQuietly,
) {

    /** 流式对话（带工具协议版）：原生协议由 ProviderClient 接管，OpenAI 形状仍走原实现。 */
    fun chatTurns(
        profile: ProviderProfile,
        history: List<ChatTurn>,
        tools: List<ToolSpec> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        onText: (String) -> Unit,
    ): ChatOutcome {
        val routed = ProviderRouting.sessionFor(profile.model)
        if (routed != null && routed.protocol != ProviderProtocol.OPENAI_COMPAT) {
            return ProviderClient(transport, slot, watchdog).chatTurns(routed, history, tools, onText)
        }
        val active = routed?.profile ?: profile
        if (active.baseUrl.isBlank()) {
            return ChatOutcome.Failed(GenerationError.Configuration("「${active.name}」没填 base URL，没东西可发"))
        }
        if (history.none { it.content.isNotBlank() || it.toolCalls.isNotEmpty() || it.role == "tool" }) {
            return ChatOutcome.Failed(GenerationError.Configuration("这条对话是空的，不花这个钱"))
        }
        val request = WireRequest(
            url = BaseUrlResolver.endpoint(BaseUrlResolver.withV1(active.baseUrl), CHAT_SUFFIX),
            method = "POST",
            headers = headersFor(active, acceptSse = true),
            body = chatBody(active, history, temperature, maxTokens, tools),
        )
        val runner = ChatWireRunner(
            transport = transport,
            slot = slot,
            policy = policy,
            watchdog = watchdog,
            providerLabel = active.name,
            sleeper = sleeper,
        )
        return when (val result = runner.run(request, RequestCost.Costly, onText)) {
            is ChatRunResult.Ok -> ChatOutcome.Text
            is ChatRunResult.ToolCalls -> ChatOutcome.Calls(result.calls)
            is ChatRunResult.Failed -> ChatOutcome.Failed(result.error)
        }
    }

    /** 老形状的流式对话（不带工具清单）：给老调用点与老测试留的窄口。 */
    fun chat(
        profile: ProviderProfile,
        history: List<ChatTurn>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        onText: (String) -> Unit,
    ): GenerationError? = when (
        val outcome = chatTurns(profile, history, emptyList(), temperature, maxTokens, onText)
    ) {
        is ChatOutcome.Text -> null
        is ChatOutcome.Calls -> GenerationError.Configuration(
            "模型回了工具调用，但这轮请求没带工具清单（形状异常）：按错误报，别装没看见",
        )
        is ChatOutcome.Failed -> outcome.error
    }

    /** 模型列表的收场：要么有名单，要么有说明。 */
    data class ModelListing(val models: List<String>, val error: GenerationError?)

    /** 拉模型列表（免费请求）。原生协议由 ProviderClient 接管，OpenAI 形状仍走原实现。 */
    fun listModels(profile: ProviderProfile): ModelListing {
        val routed = ProviderRouting.sessionFor(profile.model)
        if (routed != null && routed.protocol != ProviderProtocol.OPENAI_COMPAT) {
            val listing = ProviderClient(transport, slot, watchdog).listModels(routed)
            return ModelListing(listing.models, listing.error)
        }
        val active = routed?.profile ?: profile
        if (active.baseUrl.isBlank()) {
            return ModelListing(emptyList(), GenerationError.Configuration("「${active.name}」没填 base URL"))
        }
        val request = WireRequest(
            url = BaseUrlResolver.endpoint(BaseUrlResolver.withV1(active.baseUrl), MODELS_SUFFIX),
            method = "GET",
            headers = headersFor(active, acceptSse = false),
        )
        var attempt = 1
        while (true) {
            val body = StringBuilder()
            val outcome = try {
                val response = transport.exchange(request) { line ->
                    body.appendLine(line)
                    true
                }
                if (response.status in 200..299) {
                    return ModelListing(parseModelIds(body.toString()), null)
                }
                RetryPolicy.AttemptOutcome(
                    attempt = attempt,
                    cost = RequestCost.Free,
                    status = response.status,
                    body = response.errorBody ?: "",
                    retryAfterMs = response.retryAfterMs,
                )
            } catch (e: IOException) {
                RetryPolicy.AttemptOutcome(
                    attempt = attempt,
                    cost = RequestCost.Free,
                    status = 0,
                    localFailure = if (transport.isCancelled()) FailureClass.Cancelled else FailureClass.NoConnection,
                )
            }
            when (val decision = policy.decide(outcome)) {
                is RetryDecision.Retry -> {
                    attempt = decision.attempt
                    sleeper(decision.waitMs)
                }
                is RetryDecision.GiveUp -> return ModelListing(emptyList(), listingError(decision, outcome))
                RetryDecision.Done -> return ModelListing(
                    emptyList(),
                    GenerationError.Transport(FailureClass.Unknown, "列表的收场没被正确接住"),
                )
            }
        }
    }

    private fun listingError(
        decision: RetryDecision.GiveUp,
        outcome: RetryPolicy.AttemptOutcome,
    ): GenerationError = when {
        decision.failure == FailureClass.Cancelled -> GenerationError.Cancelled
        outcome.status >= 400 -> providerHttpError(outcome.status, outcome.body)
        else -> GenerationError.Transport(decision.failure, decision.reason)
    }

    private fun headersFor(profile: ProviderProfile, acceptSse: Boolean): List<Pair<String, String>> = buildList {
        add("content-type" to "application/json; charset=utf-8")
        if (acceptSse) add("accept" to "text/event-stream")
        if (profile.apiKey.isNotBlank()) add("authorization" to "Bearer ${profile.apiKey}")
    }

    private fun chatBody(
        profile: ProviderProfile,
        history: List<ChatTurn>,
        temperature: Double?,
        maxTokens: Int?,
        tools: List<ToolSpec>,
    ): String = buildJsonObject {
        put("model", profile.model)
        put("stream", true)
        temperature?.let { put("temperature", it) }
        maxTokens?.let { put("max_tokens", it) }
        putJsonArray("messages") {
            history.forEach { turn ->
                addJsonObject {
                    put("role", turn.role)
                    if (turn.role == "tool") {
                        put("tool_call_id", turn.toolCallId ?: "")
                        put("content", turn.content)
                    } else if (turn.toolCalls.isNotEmpty()) {
                        put("content", turn.content)
                        putJsonArray("tool_calls") {
                            turn.toolCalls.forEach { call ->
                                addJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put("arguments", call.argumentsJson)
                                    }
                                }
                            }
                        }
                    } else {
                        put("content", turn.content)
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                tools.forEach { spec ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", parseSchemaOrEmpty(spec.parametersJson))
                        }
                    }
                }
            }
        }
    }.toString()

    private fun parseSchemaOrEmpty(schemaJson: String): JsonObject =
        runCatching { json.parseToJsonElement(schemaJson) }.getOrNull() as? JsonObject
            ?: buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
            }

    private companion object {
        const val CHAT_SUFFIX = "chat/completions"
        const val MODELS_SUFFIX = "models"
        val json = Json { ignoreUnknownKeys = true }

        fun parseModelIds(text: String): List<String> {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
            val array: JsonArray = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyList()
                else -> return emptyList()
            }
            return array.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    is JsonObject -> ((element["id"] as? JsonPrimitive)?.contentOrNull
                        ?: (element["name"] as? JsonPrimitive)?.contentOrNull)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                    else -> null
                }
            }.distinct()
        }
    }
}

private fun napQuietly(ms: Long) {
    if (ms <= 0L) return
    try {
        Thread.sleep(ms)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
