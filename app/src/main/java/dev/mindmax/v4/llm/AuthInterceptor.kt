package dev.mindmax.v4.llm

import dev.mindmax.v4.core.prefs.SecureKeyStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects `Authorization: Bearer <key>` and provider-specific extra headers on
 * every LLM request, reading the key from [SecureKeyStore] on the calling
 * thread. The Cloudflare provider doesn't need Account-Id in headers (it goes
 * in the URL via [Provider.resolvedBaseUrl]), so this interceptor doesn't have
 * to know about it.
 *
 * If the store has no key, the call still proceeds — the upstream provider
 * will return a 401/403, which the LLM layer surfaces as an error. The point
 * of this interceptor is to centralise auth and never have to scatter keys
 * across call sites.
 *
 * @param cloudflareAccountId currently unused at this layer (Cloudflare is
 *   keyed on the URL path which [LlmClient] builds via `resolvedBaseUrl`).
 *   Retained on the signature so adding new providers that need a header-level
 *   identifier later doesn't require rebuilding every call site.
 */
class AuthInterceptor(
    private val secureKeyStore: SecureKeyStore,
    private val provider: Provider,
    @Suppress("unused") private val cloudflareAccountId: () -> String? = { null },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
            .header("Authorization", "Bearer ${secureKeyStore.getApiKey().orEmpty()}")

        provider.extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        return chain.proceed(builder.build())
    }
}
