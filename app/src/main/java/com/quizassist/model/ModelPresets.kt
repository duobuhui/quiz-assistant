package com.quizassist.model

data class ModelPreset(
    val label: String,
    val provider: ProviderConfig,
)

object ModelPresets {
    val flash: List<ModelPreset> = listOf(
        ModelPreset(
            label = "dots3-note-prev",
            provider = ProviderConfig(
                baseUrl = "https://note3-prev-api.askdiandian.com/v1/",
                modelName = "dots3-note-prev",
                temperature = 0.0,
                apiKeyHeader = "api-key",
            ),
        ),
        ModelPreset(
            label = "\u81ea\u5b9a\u4e49",
            provider = ProviderConfig(baseUrl = "", modelName = ""),
        ),
    )

    val deep: List<ModelPreset> = listOf(
        ModelPreset(
            label = "gpt-5.6-luna",
            provider = ProviderConfig(
                baseUrl = "https://your-api-proxy.com/v1/",
                modelName = "gpt-5.6-luna",
                temperature = 0.1,
                enableSearchHint = true,
                reasoningEffort = "max",
            ),
        ),
        ModelPreset(
            label = "\u81ea\u5b9a\u4e49",
            provider = ProviderConfig(baseUrl = "", modelName = ""),
        ),
    )
}
