package com.quizassist.questionbank

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Serializable
data class QuestionBankEntry(
    val id: Int = 0,
    val question: String = "",
    val answer: String = "",
    @SerialName("question_confidence") val questionConfidence: String = "",
    @SerialName("answer_confidence") val answerConfidence: String = "",
)

object QuestionBankParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(content: String, csv: Boolean): List<QuestionBankEntry> =
        if (csv) parseCsv(content) else parseJson(content)

    private fun parseJson(content: String): List<QuestionBankEntry> {
        val element = json.parseToJsonElement(content.trim().removePrefix("\uFEFF"))
        val objectElement = runCatching { element.jsonObject }.getOrNull()
        val array: JsonArray = when {
            element is JsonArray -> element
            objectElement?.get("questions") is JsonArray -> objectElement["questions"]!!.jsonArray
            objectElement?.get("data") is JsonArray -> objectElement["data"]!!.jsonArray
            else -> error("JSON \u9876\u5c42\u5fc5\u987b\u662f\u6570\u7ec4\uff0c\u6216\u5305\u542b questions/data \u6570\u7ec4")
        }
        return array.map { json.decodeFromJsonElement(QuestionBankEntry.serializer(), it) }
            .filter { it.question.isNotBlank() && it.answer.isNotBlank() }
    }

    private fun parseCsv(content: String): List<QuestionBankEntry> {
        val rows = readCsvRows(content)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().mapIndexed { index, value -> index to normalizeHeader(value) }.toMap()
        val questionColumn = header.entries.firstOrNull { it.value in QUESTION_HEADERS }?.key
            ?: error("CSV \u7f3a\u5c11 question \u5217")
        val answerColumn = header.entries.firstOrNull { it.value in ANSWER_HEADERS }?.key
            ?: error("CSV \u7f3a\u5c11 answer \u5217")
        return rows.drop(1).mapIndexedNotNull { index, row ->
            val question = row.getOrNull(questionColumn).orEmpty().trim()
            val answer = row.getOrNull(answerColumn).orEmpty().trim()
            if (question.isBlank() || answer.isBlank()) null else QuestionBankEntry(index + 1, question, answer)
        }
    }

    private fun readCsvRows(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                char == '"' && quoted && index + 1 < content.length && content[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row.add(field.toString())
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < content.length && content[index + 1] == '\n') index++
                    row.add(field.toString())
                    field.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }

    private fun normalizeHeader(value: String): String =
        value.removePrefix("\uFEFF").trim().lowercase()

    private val QUESTION_HEADERS = setOf("question", "question_text", "\u9898\u76ee", "\u95ee\u9898")
    private val ANSWER_HEADERS = setOf("answer", "answer_text", "\u7b54\u6848", "\u6b63\u786e\u7b54\u6848")
}
