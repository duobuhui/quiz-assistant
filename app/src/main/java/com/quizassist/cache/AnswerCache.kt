package com.quizassist.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.quizassist.model.StructuredAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

class AnswerCache(context: Context) {
    private val db = CacheDb(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(cacheKey: String): StructuredAnswer? = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "answers",
            arrayOf("answer_json"),
            "cache_key = ?",
            arrayOf(cacheKey),
            null,
            null,
            "updated_at DESC",
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            runCatching {
                json.decodeFromString(AnswerRecord.serializer(), cursor.getString(0)).toModel()
            }.getOrNull()
        }
    }

    suspend fun save(cacheKey: String, questionText: String, answer: StructuredAnswer) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val record = AnswerRecord.from(answer)
            val values = ContentValues().apply {
                put("cache_key", cacheKey)
                put("question_text", questionText.take(4000))
                put("answer_json", json.encodeToString(record))
                put("updated_at", now)
            }
            db.writableDatabase.insertWithOnConflict(
                "answers",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    suspend fun recent(limit: Int = 50): List<HistoryItem> = withContext(Dispatchers.IO) {
        db.readableDatabase.query(
            "answers",
            arrayOf("cache_key", "question_text", "answer_json", "updated_at"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
            limit.coerceIn(1, 200).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val answer = runCatching {
                        json.decodeFromString(
                            AnswerRecord.serializer(),
                            cursor.getStringOrNull(2).orEmpty(),
                        ).toModel()
                    }.getOrDefault(StructuredAnswer())
                    add(
                        HistoryItem(
                            cacheKey = cursor.getStringOrNull(0).orEmpty(),
                            questionText = cursor.getStringOrNull(1).orEmpty(),
                            answer = answer,
                            updatedAt = cursor.getLongOrNull(3) ?: 0L,
                        ),
                    )
                }
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("answers", null, null)
    }

    private class CacheDb(context: Context) : SQLiteOpenHelper(context, "quiz_answers.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE answers(
                    cache_key TEXT PRIMARY KEY,
                    question_text TEXT NOT NULL,
                    answer_json TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_answers_updated ON answers(updated_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}

data class HistoryItem(
    val cacheKey: String,
    val questionText: String,
    val answer: StructuredAnswer,
    val updatedAt: Long,
)

@Serializable
private data class AnswerRecord(
    val answer: String,
    val confidence: String,
    val reasoning: String,
    val sources: List<String>,
    val raw: String,
    val localCorrect: Boolean? = null,
) {
    fun toModel(): StructuredAnswer = StructuredAnswer(answer, confidence, reasoning, sources, raw, localCorrect)

    companion object {
        fun from(answer: StructuredAnswer): AnswerRecord =
            AnswerRecord(answer.answer, answer.confidence, answer.reasoning, answer.sources, answer.raw, answer.localCorrect)
    }
}
