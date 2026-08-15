package com.quizassist.settings

import android.content.Context
import com.quizassist.model.AppSettings
import com.quizassist.model.ProviderConfig
import com.quizassist.model.RoiBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("quiz_assist_settings_v2", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val settings: Flow<AppSettings> = state.asStateFlow()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        save(transform(state.value))
    }

    suspend fun save(next: AppSettings) {
        val ok = prefs.edit()
            .putString(Keys.flashBaseUrl, next.flashProvider.baseUrl)
            .putString(Keys.flashApiKey, next.flashProvider.apiKey)
            .putString(Keys.flashModel, next.flashProvider.modelName)
            .putString(Keys.flashTemperature, next.flashProvider.temperature.toString())
            .putString(Keys.flashSearch, next.flashProvider.enableSearchHint.toString())
            .putString(Keys.flashReasoning, next.flashProvider.reasoningEffort)
            .putString(Keys.deepBaseUrl, next.deepProvider.baseUrl)
            .putString(Keys.deepApiKey, next.deepProvider.apiKey)
            .putString(Keys.deepModel, next.deepProvider.modelName)
            .putString(Keys.deepTemperature, next.deepProvider.temperature.toString())
            .putString(Keys.deepSearch, next.deepProvider.enableSearchHint.toString())
            .putString(Keys.deepReasoning, next.deepProvider.reasoningEffort)
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
                apiKey = prefs.readString(Keys.flashApiKey, default.flashProvider.apiKey),
                modelName = prefs.readString(Keys.flashModel, default.flashProvider.modelName),
                temperature = prefs.readDouble(Keys.flashTemperature, default.flashProvider.temperature),
                enableSearchHint = prefs.readBoolean(Keys.flashSearch, default.flashProvider.enableSearchHint),
                reasoningEffort = prefs.readString(Keys.flashReasoning, default.flashProvider.reasoningEffort),
            ),
            deepProvider = ProviderConfig(
                baseUrl = prefs.readString(Keys.deepBaseUrl, default.deepProvider.baseUrl),
                apiKey = prefs.readString(Keys.deepApiKey, default.deepProvider.apiKey),
                modelName = prefs.readString(Keys.deepModel, default.deepProvider.modelName),
                temperature = prefs.readDouble(Keys.deepTemperature, default.deepProvider.temperature),
                enableSearchHint = prefs.readBoolean(Keys.deepSearch, default.deepProvider.enableSearchHint),
                reasoningEffort = prefs.readString(Keys.deepReasoning, default.deepProvider.reasoningEffort),
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
        const val deepBaseUrl = "deep_base_url"
        const val deepApiKey = "deep_api_key"
        const val deepModel = "deep_model"
        const val deepTemperature = "deep_temperature"
        const val deepSearch = "deep_search"
        const val deepReasoning = "deep_reasoning"
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
