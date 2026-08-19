package com.quizassist.settings

import android.content.Context
import com.quizassist.model.ProviderConfig
import com.quizassist.model.SavedModel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class SavedModelStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = ApiKeyCipher()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun list(slot: String): List<SavedModel> = records()
        .filter { it.slot == slot }
        .map { it.toModel(cipher) }

    fun save(slot: String, name: String, provider: ProviderConfig) {
        val normalizedName = name.trim().ifBlank { provider.modelName.trim() }
        require(normalizedName.isNotBlank()) { "模型名称不能为空" }
        val updated = records()
            .filterNot { it.slot == slot && it.name == normalizedName }
            .toMutableList()
            .apply { add(SavedModelRecord.from(slot, normalizedName, provider, cipher)) }
        prefs.edit().putString(KEY_MODELS, json.encodeToString(updated)).commit()
    }

    fun delete(slot: String, name: String) {
        val updated = records().filterNot { it.slot == slot && it.name == name }
        prefs.edit().putString(KEY_MODELS, json.encodeToString(updated)).commit()
    }

    private fun records(): List<SavedModelRecord> = runCatching {
        json.decodeFromString<List<SavedModelRecord>>(prefs.getString(KEY_MODELS, "[]").orEmpty())
    }.getOrDefault(emptyList())

    @Serializable
    private data class SavedModelRecord(
        val slot: String,
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val modelName: String,
        val temperature: Double,
        val enableSearchHint: Boolean,
        val reasoningEffort: String,
        @SerialName("api_key_header") val apiKeyHeader: String,
    ) {
        fun toModel(cipher: ApiKeyCipher): SavedModel = SavedModel(
            name = name,
            provider = ProviderConfig(
                baseUrl = baseUrl,
                apiKey = cipher.decrypt(apiKey).orEmpty(),
                modelName = modelName,
                temperature = temperature,
                enableSearchHint = enableSearchHint,
                reasoningEffort = reasoningEffort,
                apiKeyHeader = apiKeyHeader,
            ),
        )

        companion object {
            fun from(slot: String, name: String, provider: ProviderConfig, cipher: ApiKeyCipher) = SavedModelRecord(
                slot = slot,
                name = name,
                baseUrl = provider.baseUrl,
                apiKey = cipher.encrypt(provider.apiKey),
                modelName = provider.modelName,
                temperature = provider.temperature,
                enableSearchHint = provider.enableSearchHint,
                reasoningEffort = provider.reasoningEffort,
                apiKeyHeader = provider.apiKeyHeader,
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "quiz_assist_saved_models_v1"
        const val KEY_MODELS = "models"
    }
}
