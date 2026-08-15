package com.quizassist.questionbank

import android.content.Context
import java.text.Normalizer
import kotlin.math.max

class QuestionBankMatcher(context: Context) {
    private val store = QuestionBankStore(context)

    val entryCount: Int get() = store.entries().size

    fun find(ocrText: String): Match? {
        val entries = store.entries()
        if (entries.isEmpty()) return null
        val queries = queryVariants(ocrText)
        if (queries.isEmpty()) return null

        return entries.asSequence()
            .mapNotNull { entry ->
                queries.maxOfOrNull { query -> score(query, entry) ?: 0f }
                    ?.takeIf { it > 0f }
                    ?.let { Match(entry, it) }
            }
            .maxByOrNull { it.score }
            ?.takeIf { it.score >= MIN_SCORE }
    }

    private fun score(query: String, entry: QuestionBankEntry): Float? {
        val question = normalize(entry.question)
        val answer = normalize(entry.answer)
        if (question.length < MIN_QUERY_LENGTH || entry.answer.isBlank()) return null

        if (query.contains(question) || question.contains(query)) {
            return 0.99f
        }

        val queryGrams = grams(query)
        val questionGrams = grams(question)
        if (queryGrams.isEmpty() || questionGrams.isEmpty()) return null

        val common = queryGrams.count { it in questionGrams }
        if (common < MIN_COMMON_GRAMS) return null
        val longest = longestCommonSubstring(query, question)
        val queryCoverage = common.toFloat() / queryGrams.size
        val bankCoverage = common.toFloat() / questionGrams.size
        val longestCoverage = longest.toFloat() / minOf(query.length, question.length).coerceAtLeast(1)
        val answerMentioned = answer.length >= 2 && answer in query
        val dice = (2f * common) / (queryGrams.size + questionGrams.size).coerceAtLeast(1)

        // A truncated OCR fragment can cover only part of the bank question,
        // so coverage of the shorter text matters more than full-string equality.
        val score = (
            queryCoverage * 0.40f +
                bankCoverage * 0.20f +
                longestCoverage * 0.25f +
                dice * 0.15f +
                if (answerMentioned) 0.08f else 0f
            ).coerceAtMost(1f)

        val strongEnough = longest >= MIN_LONGEST_MATCH && common >= MIN_COMMON_GRAMS &&
            (queryCoverage >= 0.42f || bankCoverage >= 0.16f)
        return score.takeIf { strongEnough }
    }

    private fun queryVariants(raw: String): List<String> = buildSet {
        add(normalize(raw))
        raw.lineSequence().map(::normalize).forEach(::add)

        // OCR may return the whole screen as one line. Short windows let the
        // matcher isolate a question from status text and answer choices.
        val whole = normalize(raw)
        WINDOW_SIZES.forEach { size ->
            if (whole.length > size) {
                val step = size / 2
                var start = 0
                while (start + MIN_QUERY_LENGTH <= whole.length) {
                    add(whole.substring(start, minOf(start + size, whole.length)))
                    start += step
                }
            }
        }
    }.filter { it.length >= MIN_QUERY_LENGTH }

    private fun grams(text: String): Set<String> = buildSet {
        val sizes = if (text.any { it in '\u4e00'..'\u9fff' }) listOf(2, 3, 4) else listOf(3, 4)
        sizes.forEach { size ->
            if (text.length >= size) {
                for (index in 0..text.length - size) add(text.substring(index, index + size))
            }
        }
    }

    private fun longestCommonSubstring(first: String, second: String): Int {
        if (first.isEmpty() || second.isEmpty()) return 0
        var previous = IntArray(second.length + 1)
        var longest = 0
        first.forEach { firstChar ->
            val current = IntArray(second.length + 1)
            second.forEachIndexed { index, secondChar ->
                if (firstChar == secondChar) {
                    current[index + 1] = previous[index] + 1
                    longest = max(longest, current[index + 1])
                }
            }
            previous = current
        }
        return longest
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }

    data class Match(
        val entry: QuestionBankEntry,
        val score: Float,
    )

    private companion object {
        const val MIN_QUERY_LENGTH = 8
        const val MIN_LONGEST_MATCH = 5
        const val MIN_COMMON_GRAMS = 4
        const val MIN_SCORE = 0.50f
        val WINDOW_SIZES = intArrayOf(16, 24, 32, 48)
    }
}
