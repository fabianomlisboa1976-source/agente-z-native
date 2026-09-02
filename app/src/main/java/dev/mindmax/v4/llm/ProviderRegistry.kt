package dev.mindmax.v4.llm

import dev.mindmax.v4.data.entity.SettingsEntity

/**
 * Resolves a [Provider] from persisted settings (provider id + custom URL/model).
 * Centralising this keeps the rest of the app ignorant of how the provider choice
 * is stored.
 */
object ProviderRegistry {

    fun provider(settings: SettingsEntity?): Provider {
        val id = settings?.apiProvider
        val custom = id == "custom"
        return if (custom) {
            val base = settings?.apiBaseUrl?.takeIf { it.isNotBlank() }
                ?: Provider.Groq.baseUrl
            val model = settings?.apiModel?.takeIf { it.isNotBlank() }
                ?: Provider.Groq.defaultModel
            Provider.Custom(customBaseUrl = base, customModel = model)
        } else {
            Provider.fromId(id)
        }
    }

    /**
     * Returns the model name to use for a given provider + settings. The model
     * stored in Settings always wins — users can pin a model even if the provider
     * has its own default. If the stored model is blank we fall back to the
     * provider's [Provider.defaultModel].
     */
    fun modelFor(provider: Provider, settings: SettingsEntity?): String {
        val stored = settings?.apiModel?.takeIf { it.isNotBlank() }
        return stored ?: provider.defaultModel
    }
}
