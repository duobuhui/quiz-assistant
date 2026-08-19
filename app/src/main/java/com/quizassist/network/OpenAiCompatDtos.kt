package com.quizassist.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1,
    val stream: Boolean = true,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val thinking: ThinkingConfig? = null,
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: ChatTemplateKwargs? = null,
)

@Serializable
data class ThinkingConfig(
    val type: String,
)

@Serializable
data class ChatTemplateKwargs(
    @SerialName("enable_thinking") val enableThinking: Boolean,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class ResponsesRequest(
    val model: String,
    val input: List<ResponseInputMessage>,
    val tools: List<ResponseTool> = emptyList(),
    val reasoning: ResponseReasoning? = null,
    val stream: Boolean = true,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
)

@Serializable
data class ResponseInputMessage(
    val role: String,
    val content: List<ResponseInputContent>,
)

@Serializable
data class ResponseInputContent(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class ResponseTool(
    val type: String,
)

@Serializable
data class ResponseReasoning(
    val effort: String,
)

@Serializable
data class StreamChunk(
    val choices: List<Choice> = emptyList(),
)

@Serializable
data class Choice(
    val delta: Delta? = null,
    val message: Delta? = null,
    val text: String? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Delta(
    val content: String? = null,
)

@Serializable
data class ResponseStreamEvent(
    val type: String? = null,
    val delta: String? = null,
    val text: String? = null,
    val response: ResponseCompleted? = null,
    val item: ResponseOutputItem? = null,
    val part: ResponseOutputContent? = null,
)

@Serializable
data class ResponseCompleted(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<ResponseOutputItem> = emptyList(),
)

@Serializable
data class ResponseOutputItem(
    val type: String? = null,
    val content: List<ResponseOutputContent> = emptyList(),
)

@Serializable
data class ResponseOutputContent(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class ErrorEnvelope(
    val error: ErrorBody? = null,
)

@Serializable
data class ErrorBody(
    val message: String? = null,
    val type: String? = null,
)
