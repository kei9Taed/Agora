package com.newoether.agora.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Failover policy for `web_search`: which provider runs first, which ones are tried after a
 * failure, and what the model is told about the providers that did not answer.
 *
 * Kept in its own file because `WebSearchToolProvider.kt` is already close to the repo's
 * 800-line handwritten Kotlin limit, and because the policy is pure and unit-testable.
 */

/**
 * Providers that may be tried after the primary one fails.
 *
 * Keyless/local backends come first (no billing surprise), then keyed providers the user has
 * already configured. `duckduckgo_cffi` is only offered when the on-device sandbox can run it.
 */
internal val WEB_SEARCH_FALLBACK_ORDER = listOf("searxng", "brave", "serper", "tavily", "exa", "kagi")

/**
 * Full attempt order for one `web_search` call.
 *
 * Pure function so the policy stays unit-testable: the executor, not the model, decides whether
 * a failed provider is retried elsewhere.
 */
internal fun webSearchAttemptOrder(
    primary: String,
    apiKeys: Map<String, String>,
    searxngBaseUrl: String,
    cffiAvailable: Boolean,
): List<String> = buildList {
    add(primary)
    if (primary == "duckduckgo" && cffiAvailable) add("duckduckgo_cffi")
    for (provider in WEB_SEARCH_FALLBACK_ORDER) {
        if (provider in this) continue
        when {
            provider == "searxng" -> if (searxngBaseUrl.isNotBlank()) add(provider)
            !apiKeys[provider].isNullOrBlank() -> add(provider)
        }
    }
}

/** Returns the machine-readable `error` code of a web_search JSON payload, if any. */
internal fun webSearchErrorCode(resultJson: String): String? = runCatching {
    val obj = Json.parseToJsonElement(resultJson).jsonObject
    (obj["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * One failed provider, as reported back to the model.
 *
 * Without this the caller only sees the *last* error, which hides the real cause: a dead
 * `curl_cffi` bridge followed by an unreachable SearXNG instance looks exactly like "searxng is
 * broken", even though the primary provider failed first.
 */
internal fun webSearchFailureAttempt(provider: String, resultJson: String): JsonObject {
    val obj = runCatching { Json.parseToJsonElement(resultJson).jsonObject }.getOrNull()
    return buildJsonObject {
        put("provider", provider)
        put("error", (obj?.get("error") as? JsonPrimitive)?.content ?: "unknown")
        (obj?.get("message") as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { put("message", it) }
    }
}

/** Records which providers failed before the answer that was actually returned. */
internal fun annotateSearchAttempts(resultJson: String, attempts: List<JsonObject>): String =
    if (attempts.isEmpty()) resultJson else runCatching {
        val element = Json.parseToJsonElement(resultJson) as? JsonObject ?: return resultJson
        buildJsonObject {
            element.forEach { (key, value) -> put(key, value) }
            put("attempts", JsonArray(attempts))
        }.toString()
    }.getOrDefault(resultJson)
