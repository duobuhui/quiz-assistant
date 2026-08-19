package com.quizassist.network

import com.quizassist.model.ProviderConfig
import com.quizassist.model.QuizInput
import com.quizassist.model.SolveMode
import com.quizassist.model.StructuredAnswer
import com.quizassist.prompt.PromptFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LLMRepository(
    private val client: OkHttpClient = defaultClient(),
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    fun streamAnswer(
        provider: ProviderConfig,
        input: QuizInput,
        deep: Boolean,
        localAnswer: String? = null,
    ): Flow<String> = flow {
        require(provider.apiKey.isNotBlank()) { "API Key is empty. Configure it in Settings first." }
        val useResponsesSearch = deep && provider.enableSearchHint
        val requests = if (useResponsesSearch) {
            listOf(buildResponsesRequest(provider, input, localAnswer))
        } else if (provider.modelName.isDeepSeekModel()) {
            listOf(
                buildChatRequest(provider, input, deep, disableThinking = true, localAnswer = localAnswer),
                buildChatRequest(provider, input, deep, disableThinking = false, localAnswer = localAnswer),
            )
        } else {
            listOf(buildChatRequest(provider, input, deep, localAnswer = localAnswer))
        }
        var requestIndex = 0
        var upstreamAttempt = 0
        while (requestIndex < requests.size) {
            val request = requests[requestIndex]
            try {
                if (!deep) {
                    val full = executeBlockingRequest(request, timeoutSeconds = FLASH_TIMEOUT_SECONDS)
                    if (full.isNotBlank()) emit(full)
                } else {
                    val emitted = executeStreamingRequest(request, responsesMode = useResponsesSearch) { token -> emit(token) }
                    if (!emitted && requestIndex < requests.lastIndex) {
                        requestIndex++
                        continue
                    }
                }
                return@flow
            } catch (t: Throwable) {
                if (requestIndex == 0 && requests.size > 1 && t.isUnsupportedThinkingParameter()) {
                    requestIndex++
                    continue
                }
                if (!deep || !t.isRetryableUpstreamError() || upstreamAttempt >= 1) throw t
                upstreamAttempt++
                delay(900L)
            }
        }
    }.flowOn(Dispatchers.IO)

    fun parseStructuredAnswer(raw: String): StructuredAnswer {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return StructuredAnswer(raw = raw.trim())
        }
        val jsonText = trimmed.substring(start, end + 1)
        return runCatching {
            val element = json.parseToJsonElement(jsonText)
            val obj = element.jsonObject
            StructuredAnswer(
                answer = obj["answer"]?.toText().orEmpty(),
                confidence = obj["confidence"]?.toText().orEmpty(),
                reasoning = obj["reasoning"]?.toText().orEmpty(),
                sources = obj["sources"]?.jsonArray?.map { it.toText() }.orEmpty(),
                raw = raw,
                localCorrect = obj["local_correct"]?.toBooleanValue()
                    ?: obj["is_correct"]?.toBooleanValue(),
            )
        }.getOrElse {
            StructuredAnswer(raw = raw.trim())
        }
    }

    private inline fun executeStreamingRequest(
        request: Request,
        responsesMode: Boolean,
        onToken: (String) -> Unit,
    ): Boolean {
        client.newCall(request).execute().use { response ->
            val body = response.body ?: error("LLM response body is empty.")
            if (!response.isSuccessful) {
                val raw = body.string()
                val message = runCatching {
                    json.decodeFromString(ErrorEnvelope.serializer(), raw).error?.message
                }.getOrNull()
                throw LlmHttpException(response.code, message ?: "LLM request failed: HTTP ${response.code}")
            }
            body.source().use { source ->
                var emittedResponsesTextLength = 0
                val plainBody = StringBuilder()
                val hiddenReasoning = StringBuilder()
                var emittedAnswerText = false
                var sawSseData = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: continue
                    if (!line.startsWith("data:")) {
                        if (line.isNotBlank()) plainBody.appendLine(line)
                        continue
                    }
                    sawSseData = true
                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank()) continue
                    if (data == "[DONE]") break
                    if (responsesMode) {
                        hiddenReasoning.append(parseResponsesReasoning(data))
                        val token = parseResponsesToken(data, emittedResponsesTextLength)
                        if (token.isNotEmpty()) {
                            emittedResponsesTextLength += token.length
                            emittedAnswerText = true
                            onToken(token)
                        }
                    } else {
                        val parts = parseChatParts(data)
                        hiddenReasoning.append(parts.reasoning)
                        if (parts.content.isNotEmpty()) {
                            emittedAnswerText = true
                            onToken(parts.content)
                        }
                    }
                }
                if (!sawSseData) {
                    if (responsesMode) {
                        hiddenReasoning.append(parseResponsesReasoning(plainBody.toString()))
                        val token = parseResponsesToken(plainBody.toString(), emittedResponsesTextLength)
                        if (token.isNotEmpty()) {
                            emittedAnswerText = true
                            onToken(token)
                        }
                    } else {
                        val parts = parseChatParts(plainBody.toString())
                        hiddenReasoning.append(parts.reasoning)
                        if (parts.content.isNotEmpty()) {
                            emittedAnswerText = true
                            onToken(parts.content)
                        }
                    }
                }
                if (!emittedAnswerText) {
                    extractAnswerJson(hiddenReasoning.toString())
                        .takeIf { it.isNotBlank() }
                        ?.let(onToken)
                }
                return emittedAnswerText || extractAnswerJson(hiddenReasoning.toString()).isNotBlank()
            }
        }
    }

    private fun executeBlockingRequest(request: Request, timeoutSeconds: Long): String {
        val call = client.newCall(request)
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS)
        call.execute().use { response ->
            val body = response.body ?: error("LLM response body is empty.")
            val raw = body.string()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.decodeFromString(ErrorEnvelope.serializer(), raw).error?.message
                }.getOrNull()
                throw LlmHttpException(response.code, message ?: "LLM request failed: HTTP ${response.code}")
            }
            return parseChatContent(raw)
                .ifBlank { parseChatToken(raw) }
                .ifBlank { raw.trim() }
        }
    }

    private fun buildChatRequest(
        provider: ProviderConfig,
        input: QuizInput,
        deep: Boolean,
        disableThinking: Boolean = false,
        localAnswer: String? = null,
    ): Request {
        val endpoint = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val system = if (deep) {
            PromptFactory.deepSystem(provider.enableSearchHint, localAnswer)
        } else {
            PromptFactory.flashSystem()
        }
        val messages = listOf(
            ChatMessage("system", JsonPrimitive(system)),
            ChatMessage(
                "user",
                if (provider.modelName.prefersTextOnly()) {
                    textOnlyUserContent(input, localAnswer)
                } else {
                    userContent(input, localAnswer)
                },
            ),
        )
        val payload = ChatCompletionRequest(
            model = provider.modelName,
            messages = messages,
            temperature = provider.temperature,
            stream = deep,
            reasoningEffort = if (deep && !disableThinking) provider.reasoningEffort.trim().takeIf { it.isNotBlank() } else null,
            maxTokens = if (deep) 2048 else 64,
            thinking = if (disableThinking) {
                ThinkingConfig(type = "disabled")
            } else {
                null
            },
            chatTemplateKwargs = if (provider.modelName.isDotsModel()) {
                ChatTemplateKwargs(enableThinking = false)
            } else {
                null
            },
        )
        return jsonPost(endpoint, provider.apiKey, provider.apiKeyHeader, json.encodeToString(payload))
    }

    private fun buildResponsesRequest(provider: ProviderConfig, input: QuizInput, localAnswer: String?): Request {
        val endpoint = provider.baseUrl.trimEnd('/') + "/responses"
        val reasoningEffort = provider.reasoningEffort.trim().takeIf { it.isNotBlank() }
        val payload = ResponsesRequest(
            model = provider.modelName,
            input = listOf(
                ResponseInputMessage(
                    role = "system",
                    content = listOf(ResponseInputContent(type = "input_text", text = PromptFactory.deepSystem(true, localAnswer))),
                ),
                ResponseInputMessage(
                    role = "user",
                    content = listOf(ResponseInputContent(type = "input_text", text = searchUserText(input, localAnswer))),
                ),
            ),
            tools = listOf(ResponseTool(type = "web_search")),
            reasoning = reasoningEffort?.let { ResponseReasoning(effort = it) },
            stream = true,
            maxOutputTokens = 2048,
        )
        return jsonPost(endpoint, provider.apiKey, provider.apiKeyHeader, json.encodeToString(payload))
    }

    private fun jsonPost(endpoint: String, apiKey: String, apiKeyHeader: String, payload: String): Request =
        Request.Builder()
            .url(endpoint)
            .apply {
                // Dots uses api-key; other OpenAI-compatible providers use Bearer auth.
                if (apiKeyHeader.equals("api-key", ignoreCase = true)) {
                    header("api-key", apiKey)
                } else {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

    private fun userContent(input: QuizInput, localAnswer: String? = null) =
        if (input.mode == SolveMode.Vision && input.imageBase64Jpeg != null) {
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", PromptFactory.userPrompt(input, localAnswer))
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:image/jpeg;base64,${input.imageBase64Jpeg}")
                        }
                    },
                )
            }
        } else {
            JsonPrimitive(PromptFactory.userPrompt(input, localAnswer))
        }

    private fun textOnlyUserContent(input: QuizInput, localAnswer: String? = null): JsonPrimitive =
        JsonPrimitive(PromptFactory.userPrompt(input, localAnswer))

    private fun searchUserText(input: QuizInput, localAnswer: String? = null): String =
        buildString {
            appendLine(PromptFactory.userPrompt(input, localAnswer))
            appendLine()
            appendLine("当前深度模型已启用 Responses API 联网搜索。请优先用题干 OCR 文本检索官方资料、百科、攻略站、社区资料或活动说明。")
            appendLine("注意：本次联网请求只发送文本，不附带截图；如果 OCR 不完整，请基于可识别文字进行保守判断，并降低置信度。")
        }.trim()

    private fun parseChatToken(data: String): String = parseChatParts(data).content

    private fun parseChatParts(data: String): ChatParts =
        runCatching {
            extractChatParts(json.parseToJsonElement(data))
        }.getOrDefault(ChatParts())

    private fun parseChatContent(data: String): String =
        runCatching {
            val element = json.parseToJsonElement(data)
            extractChatParts(element).content
        }.getOrDefault("")

    private fun extractChatParts(element: JsonElement): ChatParts {
        val obj = element.jsonObjectOrNull()
        if (obj != null) {
            val choices = obj["choices"]?.jsonArrayOrNull()
            if (!choices.isNullOrEmpty()) {
                choices.forEach { choice ->
                    val choiceObj = choice.jsonObjectOrNull() ?: return@forEach
                    val delta = choiceObj["delta"]?.extractAssistantParts()
                    if (delta != null && (delta.content.isNotBlank() || delta.reasoning.isNotBlank())) return delta
                    val message = choiceObj["message"]?.extractAssistantParts()
                    if (message != null && (message.content.isNotBlank() || message.reasoning.isNotBlank())) return message
                    val text = choiceObj["text"]?.jsonPrimitiveOrNull()
                    if (!text.isNullOrBlank()) return ChatParts(content = text)
                }
            }
            return ChatParts(
                content = element.extractTopLevelText().orEmpty(),
                reasoning = obj["reasoning_content"]?.jsonPrimitiveOrNull().orEmpty(),
            )
        }
        return ChatParts()
    }

    private fun parseResponsesToken(data: String, emittedLength: Int): String =
        runCatching {
            val event = json.decodeFromString(ResponseStreamEvent.serializer(), data)
            when (event.type) {
                "response.output_text.delta", "response.refusal.delta" ->
                    event.delta.orEmpty()
                "response.output_text.done", "response.refusal.done" ->
                    event.text.orEmpty().missingSuffixAfter(emittedLength)
                "response.content_part.done" ->
                    event.part
                        ?.takeIf { it.type == "output_text" || it.type == "refusal" }
                        ?.text
                        .orEmpty()
                        .missingSuffixAfter(emittedLength)
                "response.output_item.done" ->
                    event.item?.outputText().orEmpty().missingSuffixAfter(emittedLength)
                "response.completed" ->
                    event.response?.outputText().orEmpty().missingSuffixAfter(emittedLength)
                else -> ""
            }
        }.getOrDefault("")

    private fun parseResponsesReasoning(data: String): String =
        runCatching {
            val event = json.decodeFromString(ResponseStreamEvent.serializer(), data)
            when (event.type) {
                "response.reasoning_summary_text.delta",
                "response.reasoning_text.delta",
                "response.reasoning_content.delta" -> event.delta.orEmpty()
                else -> ""
            }
        }.getOrDefault("")

    private fun extractAnswerJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return ""
        val candidate = raw.substring(start, end + 1)
        return runCatching {
            val jsonObject = json.parseToJsonElement(candidate).jsonObject
            candidate.takeIf { jsonObject["answer"] != null }.orEmpty()
        }.getOrDefault("")
    }

    private fun ResponseOutputItem.outputText(): String =
        if (type == null || type == "message") {
            content
                .filter { it.type == "output_text" || it.type == "refusal" }
                .mapNotNull { it.text }
                .joinToString("")
        } else {
            ""
        }

    private fun ResponseCompleted.outputText(): String =
        outputText ?: output.joinToString("") { it.outputText() }

    private fun String.missingSuffixAfter(emittedLength: Int): String =
        if (length > emittedLength) substring(emittedLength) else ""

    private fun JsonElement.toText(): String =
        jsonPrimitive.contentOrNull ?: toString()

    private fun JsonElement.toBooleanValue(): Boolean? =
        jsonPrimitive.booleanOrNull ?: toText().toBooleanStrictOrNull()

    private fun JsonElement.extractAssistantParts(): ChatParts {
        val obj = jsonObjectOrNull() ?: return ChatParts(content = jsonPrimitiveOrNull().orEmpty())
        val keys = listOf(
            "content",
            "text",
            "output_text",
            "answer",
            "delta",
        )
        val content = keys.firstNotNullOfOrNull { obj[it]?.jsonPrimitiveOrNull()?.takeIf(String::isNotBlank) }.orEmpty()
        val reasoning = listOf("reasoning_content", "reasoning", "thinking")
            .firstNotNullOfOrNull { obj[it]?.jsonPrimitiveOrNull()?.takeIf(String::isNotBlank) }
            .orEmpty()
        return ChatParts(content = content, reasoning = reasoning)
    }

    private data class ChatParts(
        val content: String = "",
        val reasoning: String = "",
    )

    private fun JsonElement.extractTopLevelText(): String? {
        val obj = jsonObjectOrNull() ?: return null
        listOf("content", "text", "output_text", "answer").forEach { key ->
            val text = obj[key]?.jsonPrimitiveOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun JsonElement.jsonObjectOrNull() =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.jsonArrayOrNull() =
        runCatching { jsonArray }.getOrNull()

    private fun JsonElement.jsonPrimitiveOrNull() =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private fun String.prefersTextOnly(): Boolean {
        val normalized = lowercase()
        return "deepseek" in normalized ||
            "v4-flash" in normalized ||
            "reasoner" in normalized
    }

    private fun String.isDeepSeekModel(): Boolean = lowercase().contains("deepseek")

    private fun String.isDotsModel(): Boolean = lowercase().contains("dots3-note")

    private fun Throwable.isRetryableUpstreamError(): Boolean =
        (this is LlmHttpException && code in setOf(429, 500, 502, 503, 504)) ||
            message?.contains("temporarily unavailable", ignoreCase = true) == true ||
            message?.contains("upstream", ignoreCase = true) == true

    private fun Throwable.isUnsupportedThinkingParameter(): Boolean =
        this is LlmHttpException && code == 400 && (
            message.contains("thinking", ignoreCase = true) ||
                message.contains("unknown parameter", ignoreCase = true) ||
                message.contains("unsupported", ignoreCase = true)
            )

    private class LlmHttpException(
        val code: Int,
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        private const val FLASH_TIMEOUT_SECONDS = 15L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(210, TimeUnit.SECONDS)
            .build()
    }
}
