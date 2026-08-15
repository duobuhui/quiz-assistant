package com.quizassist.model

data class ModelPreset(
    val label: String,
    val provider: ProviderConfig,
)

object ModelPresets {
    val flash: List<ModelPreset> = listOf(
        ModelPreset(
            label = "dsv4flash",
            provider = ProviderConfig(
                baseUrl = "https://api.deepseek.com/v1/",
                modelName = "dsv4flash",
                temperature = 0.0,
            ),
        ),
        ModelPreset(
            label = "\u81ea\u5b9a\u4e49",
            provider = ProviderConfig(baseUrl = "", modelName = ""),
        ),
    )

    val deep: List<ModelPreset> = listOf(
        ModelPreset(
            label = "gpt5.6luna",
            provider = ProviderConfig(
                baseUrl = "https://your-api-proxy.com/v1/",
                modelName = "gpt5.6luna",
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
