package com.quizassist.questionbank

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class QuestionBankStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val metadata = appContext.getSharedPreferences(METADATA_NAME, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true }

    fun import(uri: Uri): ImportResult {
        val displayName = resolveName(uri)
        val content = appContext.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("\u65e0\u6cd5\u8bfb\u53d6\u9898\u5e93\u6587\u4ef6")
        val isCsv = displayName.endsWith(".csv", ignoreCase = true) ||
            appContext.contentResolver.getType(uri).equals("text/csv", ignoreCase = true)
        val entries = QuestionBankParser.parse(content, isCsv)
        if (entries.isEmpty()) error("\u9898\u5e93\u6ca1\u6709\u53ef\u7528\u9898\u76ee")
        file.writeText(json.encodeToString(entries), Charsets.UTF_8)
        metadata.edit().putString(KEY_NAME, displayName).apply()
        return ImportResult(displayName, entries.size)
    }

    fun clear() {
        file.delete()
        metadata.edit().remove(KEY_NAME).apply()
    }

    fun entries(): List<QuestionBankEntry> =
        runCatching {
            if (!file.exists()) emptyList()
            else QuestionBankParser.parse(file.readText(Charsets.UTF_8), csv = false)
        }.getOrDefault(emptyList())

    fun info(): ImportInfo = ImportInfo(entries().size, metadata.getString(KEY_NAME, "").orEmpty())

    private fun resolveName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("").ifBlank { uri.lastPathSegment.orEmpty().ifBlank { "question-bank" } }

    data class ImportResult(val name: String, val count: Int)
    data class ImportInfo(val count: Int, val name: String)

    private companion object {
        const val FILE_NAME = "question_bank.json"
        const val METADATA_NAME = "question_bank_metadata"
        const val KEY_NAME = "name"
    }
}
