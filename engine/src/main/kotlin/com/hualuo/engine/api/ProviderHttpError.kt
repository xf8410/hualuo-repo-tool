package com.hualuo.engine.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把提供商返回的错误体翻成 [GenerationError.Api]。**搬自原版 Agora 的 `api/ProviderHttpError.kt`**，
 * 逻辑照用（那套"形状五花八门都要认"的取值顺序是对的），只加一件事：**所有从对方 body 里取出来的
 * 文本一律先过 [maskSecrets]**。
 *
 * 为什么加：错误体是**不可信输入**，而有些网关会把请求上下文原样回显在 message 里 ——
 * 里面可能带着 `api_key=...` 或整段 `Authorization: Bearer sk-...`。原版直接把这段文本送进界面与日志，
 * 等于把密钥抄了一遍。分类逻辑没错，出口没设防，这是搬代码时最容易漏的那一格。
 *
 * 认的形状（原版经验，全保留）：
 * - `{"error":{"message":...,"code":...,"type":...}}` —— OpenAI 标准
 * - `{"error":"纯字符串"}` —— 不少自建网关
 * - `{"message":...}` / `{"detail":...}` / `{"reason":...}` / `{"error_description":...}` —— FastAPI/OAuth/各家
 * - `type` 取不到时退到 `status`（gRPC 风网关把状态放这儿）
 * - 顶层 `{"error":{...}}` 里找不到 message 时，再回退查顶层
 * - 根本不是 JSON（HTML 错误页、纯文本）—— 原样当 message，但标记 structured=false
 */
data class ProviderHttpErrorBody(
    val code: String?,
    val type: String?,
    val message: String,
    /** true = 从 JSON 里按字段取出来的；false = 整坨原文（可能是 HTML）。 */
    val structured: Boolean,
)

private val lenientJson = Json { ignoreUnknownKeys = true }

/** 解析不出 JSON 时返回 null，让调用方决定"要不要显示原文"。 */
fun parseProviderHttpErrorBody(rawBody: String): ProviderHttpErrorBody? {
    val trimmed = rawBody.trim()
    if (trimmed.isEmpty()) return null

    val root = runCatching { lenientJson.parseToJsonElement(trimmed) }.getOrNull()
        ?: return unstructured(trimmed)

    return when (root) {
        is JsonObject -> parseErrorObject(root) ?: unstructured(trimmed)
        is JsonPrimitive -> nonBlankPrimitive(root)?.let { ProviderHttpErrorBody(null, null, mask(it), true) }
            ?: unstructured(trimmed)
        else -> unstructured(trimmed)
    }
}

/** HTTP 非 2xx 时统一入口：没有 body 也要给出一条能看的（带状态码）。 */
fun providerHttpError(statusCode: Int, rawBody: String?): GenerationError.Api {
    if (rawBody == null) return GenerationError.Api(null, null, "HTTP $statusCode（对方没给原因）")
    val parsed = parseProviderHttpErrorBody(rawBody)
        ?: return GenerationError.Api(null, null, "HTTP $statusCode（对方给的不是能读的形状）")
    return GenerationError.Api(parsed.code ?: statusCode.toString(), parsed.type, parsed.message)
}

/** 只有确认是 JSON 里取出来的 message 才用这个；HTML 之类的原文要走 providerHttpError。 */
fun structuredErrorMessage(rawBody: String): String? =
    parseProviderHttpErrorBody(rawBody)?.takeIf { it.structured }?.message

private fun parseErrorObject(root: JsonObject): ProviderHttpErrorBody? {
    val nested = root["error"] as? JsonObject
    val message = nested?.firstErrorMessage()
        ?: nonBlankPrimitive(root["error"])
        ?: root.firstErrorMessage()
        ?: return null
    val code = nested?.string("code") ?: root.string("code")
    val type = nested?.string("type") ?: nested?.string("status") ?: root.string("type") ?: root.string("status")
    return ProviderHttpErrorBody(code, type, mask(message), structured = true)
}

private fun JsonObject.firstErrorMessage(): String? =
    string("message") ?: string("detail") ?: string("reason") ?: string("error_description")

private fun JsonObject.string(key: String): String? = nonBlankPrimitive(this[key])

private fun nonBlankPrimitive(element: JsonElement?): String? =
    (element as? JsonPrimitive)
        ?.takeUnless { it === JsonNull }
        ?.let { runCatching { it.content }.getOrNull() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun unstructured(raw: String) = ProviderHttpErrorBody(null, null, mask(raw), structured = false)

private fun mask(text: String): String = maskSecrets(text)
