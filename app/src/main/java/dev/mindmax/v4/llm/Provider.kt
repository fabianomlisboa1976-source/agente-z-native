package dev.mindmax.v4.llm

/**
 * Multi-provider abstraction. Built-ins cover the free tier entries the user
 * asked for (Groq, OpenRouter, Cloudflare Workers AI, GitHub Models). `Custom`
 * lets the user point at any OpenAI-compatible endpoint.
 *
 * All built-ins speak the OpenAI Chat Completions wire format, so [LlmClient]
 * only needs two real implementations under the hood:
 *   - [OpenAiCompatibleProvider] for the OpenAI-shape providers (everything
 *     except Cloudflare, which uses a different path due to its account-id
 *     route parameter), and
 *   - the [CloudflareProvider] subclass that adds `ai/<model>` routing.
 *
 * `extraHeaders` are the headers each provider requires on top of `Authorization`
 * — OpenRouter asks for `HTTP-Referer`/`X-Title`; others don't care.
 */
sealed class Provider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val requiresAccountId: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap(),
) {

    /** Groq OpenAI-compatible API. Free tier with generous limits. */
    object Groq : Provider(
        id = "groq",
        displayName = "Groq",
        baseUrl = "https://api.groq.com/openai/v1/",
        defaultModel = "llama-3.3-70b-versatile",
    )

    /** OpenRouter.ai. Free tier requires `HTTP-Referer` + `X-Title`. */
    object OpenRouter : Provider(
        id = "openrouter",
        displayName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/",
        defaultModel = "meta-llama/llama-3.1-8b-instruct:free",
        extraHeaders = mapOf(
            "HTTP-Referer" to "https://mindmax.local",
            "X-Title" to "MindMax V4",
        ),
    )

    /** Cloudflare Workers AI. Account-id goes in the URL path, not as a header. */
    object Cloudflare : Provider(
        id = "cloudflare",
        displayName = "Cloudflare Workers AI",
        baseUrl = "https://api.cloudflare.com/client/v4/accounts/{accountId}/ai/v1/",
        defaultModel = "@cf/meta/llama-3.1-8b-instruct",
        requiresAccountId = true,
    )

    /** GitHub Models (free for personal use). */
    object GitHubModels : Provider(
        id = "github",
        displayName = "GitHub Models",
        baseUrl = "https://models.inference.ai.azure.com/",
        defaultModel = "Meta-Llama-3.1-8B-Instruct",
    )

    /** OpenAI itself (paid; included for completeness). */
    object OpenAi : Provider(
        id = "openai",
        displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1/",
        defaultModel = "gpt-4o-mini",
    )

    /** User-supplied OpenAI-compatible endpoint (LM Studio, Together, etc.). */
    data class Custom(
        val customBaseUrl: String,
        val customModel: String,
    ) : Provider(
        id = "custom",
        displayName = "Custom endpoint",
        baseUrl = customBaseUrl,
        defaultModel = customModel,
    )

    /**
     * Resolves the actual base URL with placeholders filled in. Currently only
     * Cloudflare uses placeholders. Returned string never ends with "/".
     */
    fun resolvedBaseUrl(accountId: String? = null): String = when (this) {
        is Cloudflare -> baseUrl.replace("{accountId}", accountId.orEmpty())
        else -> baseUrl
    }.trimEnd('/')

    companion object {
        /**
         * All built-in providers in display order. Custom is appended at call
         * sites that need it.
         */
        val builtIns: List<Provider> = listOf(Groq, OpenRouter, Cloudflare, GitHubModels, OpenAi)

        /**
         * Parses the provider id (stored in Settings). Returns Groq as a safe
         * default when the stored id is unknown (e.g. installed across versions).
         */
        fun fromId(id: String?): Provider = builtIns.firstOrNull { it.id == id } ?: Groq
    }
}
