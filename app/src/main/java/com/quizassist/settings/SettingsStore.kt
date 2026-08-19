package com.quizassist.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.quizassist.model.AppSettings
import com.quizassist.model.ProviderConfig
import com.quizassist.model.RoiBox
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("quiz_assist_settings_v2", Context.MODE_PRIVATE)
    private val keyCipher = ApiKeyCipher()

    init {
        migratePlaintextApiKeys()
        migrateLegacyFlashPreset()
    }

    private val state = MutableStateFlow(load())

    val settings: Flow<AppSettings> = state.asStateFlow()

    fun refresh() {
        state.value = load()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        save(transform(state.value))
    }

    suspend fun save(next: AppSettings) {
        validateBaseUrl(next.flashProvider.baseUrl)
        validateBaseUrl(next.deepProvider.baseUrl)
        val ok = prefs.edit()
            .putString(Keys.flashBaseUrl, next.flashProvider.baseUrl)
            .putString(Keys.flashApiKey, keyCipher.encrypt(next.flashProvider.apiKey))
            .putString(Keys.flashModel, next.flashProvider.modelName)
            .putString(Keys.flashTemperature, next.flashProvider.temperature.toString())
            .putString(Keys.flashSearch, next.flashProvider.enableSearchHint.toString())
            .putString(Keys.flashReasoning, next.flashProvider.reasoningEffort)
            .putString(Keys.flashApiKeyHeader, next.flashProvider.apiKeyHeader)
            .putString(Keys.deepBaseUrl, next.deepProvider.baseUrl)
            .putString(Keys.deepApiKey, keyCipher.encrypt(next.deepProvider.apiKey))
            .putString(Keys.deepModel, next.deepProvider.modelName)
            .putString(Keys.deepTemperature, next.deepProvider.temperature.toString())
            .putString(Keys.deepSearch, next.deepProvider.enableSearchHint.toString())
            .putString(Keys.deepReasoning, next.deepProvider.reasoningEffort)
            .putString(Keys.deepApiKeyHeader, next.deepProvider.apiKeyHeader)
            .putString(Keys.maxWait, next.maxWaitTimeoutSeconds.coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS).toString())
            .putString(Keys.useVision, next.useVisionWhenOcrWeak.toString())
            .putString(Keys.showPreview, next.showImagePreview.toString())
            .putString(Keys.overlayAlpha, next.overlayAlpha.coerceIn(0.35f, 1f).toString())
            .putString(Keys.clickThrough, next.clickThrough.toString())
            .putString(Keys.questionBankMode, next.questionBankMode.toString())
            .applyRoi(next.roi)
            .commit()
        if (!ok) error("SharedPreferences commit failed")
        state.value = load()
    }

    private fun load(): AppSettings {
        val default = AppSettings()
        val roi = if (prefs.contains(Keys.roiLeft)) {
            RoiBox(
                leftRatio = prefs.readFloat(Keys.roiLeft, 0.08f),
                topRatio = prefs.readFloat(Keys.roiTop, 0.22f),
                widthRatio = prefs.readFloat(Keys.roiWidth, 0.84f),
                heightRatio = prefs.readFloat(Keys.roiHeight, 0.38f),
            ).takeIf { it.isValid() }
        } else {
            null
        }

        return default.copy(
            flashProvider = ProviderConfig(
                baseUrl = prefs.readString(Keys.flashBaseUrl, default.flashProvider.baseUrl),
                apiKey = readApiKey(Keys.flashApiKey, default.flashProvider.apiKey),
                modelName = prefs.readString(Keys.flashModel, default.flashProvider.modelName),
                temperature = prefs.readDouble(Keys.flashTemperature, default.flashProvider.temperature),
                enableSearchHint = prefs.readBoolean(Keys.flashSearch, default.flashProvider.enableSearchHint),
                reasoningEffort = prefs.readString(Keys.flashReasoning, default.flashProvider.reasoningEffort),
                apiKeyHeader = prefs.readString(Keys.flashApiKeyHeader, default.flashProvider.apiKeyHeader),
            ),
            deepProvider = ProviderConfig(
                baseUrl = prefs.readString(Keys.deepBaseUrl, default.deepProvider.baseUrl),
                apiKey = readApiKey(Keys.deepApiKey, default.deepProvider.apiKey),
                modelName = prefs.readString(Keys.deepModel, default.deepProvider.modelName),
                temperature = prefs.readDouble(Keys.deepTemperature, default.deepProvider.temperature),
                enableSearchHint = prefs.readBoolean(Keys.deepSearch, default.deepProvider.enableSearchHint),
                reasoningEffort = prefs.readString(Keys.deepReasoning, default.deepProvider.reasoningEffort),
                apiKeyHeader = prefs.readString(Keys.deepApiKeyHeader, default.deepProvider.apiKeyHeader),
            ),
            maxWaitTimeoutSeconds = prefs.readInt(Keys.maxWait, default.maxWaitTimeoutSeconds)
                .coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS),
            useVisionWhenOcrWeak = prefs.readBoolean(Keys.useVision, default.useVisionWhenOcrWeak),
            showImagePreview = prefs.readBoolean(Keys.showPreview, default.showImagePreview),
            overlayAlpha = prefs.readFloat(Keys.overlayAlpha, default.overlayAlpha).coerceIn(0.35f, 1f),
            clickThrough = prefs.readBoolean(Keys.clickThrough, default.clickThrough),
            questionBankMode = prefs.readBoolean(Keys.questionBankMode, default.questionBankMode),
            roi = roi,
        )
    }

    private fun readApiKey(key: String, default: String): String =
        prefs.readString(key, default).let { stored ->
            if (stored.startsWith(ApiKeyCipher.PREFIX)) {
                keyCipher.decrypt(stored).orEmpty()
            } else {
                stored
            }
        }

    private fun migratePlaintextApiKeys() {
        listOf(Keys.flashApiKey, Keys.deepApiKey).forEach { key ->
            val stored = prefs.getString(key, null).orEmpty()
            if (stored.isNotBlank() && !stored.startsWith(ApiKeyCipher.PREFIX)) {
                prefs.edit().putString(key, keyCipher.encrypt(stored)).commit()
            }
        }
    }

    private fun migrateLegacyFlashPreset() {
        val oldModel = prefs.getString(Keys.flashModel, null)
        val oldBaseUrl = prefs.getString(Keys.flashBaseUrl, null)
        if (oldModel == "deepseek-v4-flash" && oldBaseUrl?.contains("api.deepseek.com") == true) {
            prefs.edit()
                .putString(Keys.flashBaseUrl, "https://note3-prev-api.askdiandian.com/v1/")
                .putString(Keys.flashModel, "dots3-note-prev")
                .putString(Keys.flashApiKeyHeader, "api-key")
                .commit()
        }
    }

    private fun validateBaseUrl(value: String) {
        val normalized = value.trim()
        if (normalized.isNotBlank() && !normalized.startsWith("https://", ignoreCase = true)) {
            error("接口地址必须使用 HTTPS")
        }
    }

    private fun android.content.SharedPreferences.Editor.applyRoi(roi: RoiBox?): android.content.SharedPreferences.Editor =
        if (roi == null) {
            remove(Keys.roiLeft)
                .remove(Keys.roiTop)
                .remove(Keys.roiWidth)
                .remove(Keys.roiHeight)
        } else {
            putString(Keys.roiLeft, roi.leftRatio.toString())
                .putString(Keys.roiTop, roi.topRatio.toString())
                .putString(Keys.roiWidth, roi.widthRatio.toString())
                .putString(Keys.roiHeight, roi.heightRatio.toString())
        }

    private fun android.content.SharedPreferences.readString(key: String, default: String): String =
        runCatching { getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() } ?: default

    private fun android.content.SharedPreferences.readBoolean(key: String, default: Boolean): Boolean =
        runCatching { getString(key, null)?.toBooleanStrictOrNull() }.getOrNull() ?: default

    private fun android.content.SharedPreferences.readInt(key: String, default: Int): Int =
        runCatching { getString(key, null)?.toIntOrNull() }.getOrNull() ?: default

    private fun android.content.SharedPreferences.readFloat(key: String, default: Float): Float =
        runCatching { getString(key, null)?.toFloatOrNull() }.getOrNull() ?: default

    private fun android.content.SharedPreferences.readDouble(key: String, default: Double): Double =
        runCatching { getString(key, null)?.toDoubleOrNull() }.getOrNull() ?: default

    private object Keys {
        const val flashBaseUrl = "flash_base_url"
        const val flashApiKey = "flash_api_key"
        const val flashModel = "flash_model"
        const val flashTemperature = "flash_temperature"
        const val flashSearch = "flash_search"
        const val flashReasoning = "flash_reasoning"
        const val flashApiKeyHeader = "flash_api_key_header"
        const val deepBaseUrl = "deep_base_url"
        const val deepApiKey = "deep_api_key"
        const val deepModel = "deep_model"
        const val deepTemperature = "deep_temperature"
        const val deepSearch = "deep_search"
        const val deepReasoning = "deep_reasoning"
        const val deepApiKeyHeader = "deep_api_key_header"
        const val maxWait = "max_wait"
        const val useVision = "use_vision"
        const val showPreview = "show_preview"
        const val overlayAlpha = "overlay_alpha"
        const val clickThrough = "click_through"
        const val questionBankMode = "question_bank_mode"
        const val roiLeft = "roi_left"
        const val roiTop = "roi_top"
        const val roiWidth = "roi_width"
        const val roiHeight = "roi_height"
    }

    private companion object {
        const val MIN_WAIT_SECONDS = 5
        const val MAX_WAIT_SECONDS = 180
    }
}

internal class ApiKeyCipher {
    fun encrypt(value: String): String {
        if (value.isBlank()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array()
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String? = runCatching {
        val payload = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_SIZE)
        val encrypted = payload.copyOfRange(IV_SIZE, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val PREFIX = "enc:v1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "quiz_assist_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
