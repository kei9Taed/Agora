package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchFallbackPolicyTest {

    @Test
    fun attemptOrder_primaryOnly_whenNothingElseConfigured() {
        assertEquals(
            listOf("duckduckgo"),
            webSearchAttemptOrder(
                primary = "duckduckgo",
                apiKeys = emptyMap(),
                searxngBaseUrl = "",
                cffiAvailable = false,
            ),
        )
    }

    @Test
    fun attemptOrder_addsCffiBridge_forDuckDuckGoWhenSandboxAvailable() {
        val order = webSearchAttemptOrder(
            primary = "duckduckgo",
            apiKeys = emptyMap(),
            searxngBaseUrl = "",
            cffiAvailable = true,
        )
        assertEquals(listOf("duckduckgo", "duckduckgo_cffi"), order)
    }

    @Test
    fun attemptOrder_neverUsesCffiBridgeForOtherPrimaries() {
        val order = webSearchAttemptOrder(
            primary = "brave",
            apiKeys = mapOf("brave" to "key"),
            searxngBaseUrl = "",
            cffiAvailable = true,
        )
        // Brave answers first, DuckDuckGo is the keyless safety net, but the sandbox bridge is
        // only offered for a DuckDuckGo primary run.
        assertEquals(listOf("brave", "duckduckgo"), order)
        assertTrue("duckduckgo_cffi" !in order)
    }

    @Test
    fun attemptOrder_exaDefaultFallsBackToKeylessDuckDuckGo() {
        assertEquals(
            listOf("exa", "duckduckgo"),
            webSearchAttemptOrder("exa", emptyMap(), "", false),
        )
        assertEquals(
            listOf("exa", "duckduckgo", "duckduckgo_cffi"),
            webSearchAttemptOrder("exa", emptyMap(), "", true),
        )
    }

    @Test
    fun attemptOrder_includesConfiguredKeyedProviders_only() {
        val order = webSearchAttemptOrder(
            primary = "duckduckgo",
            apiKeys = mapOf("serper" to "s3cr3t", "tavily" to "  "),
            searxngBaseUrl = "",
            cffiAvailable = false,
        )
        assertEquals(listOf("duckduckgo", "serper"), order)
    }

    @Test
    fun attemptOrder_includesSearxngOnlyWhenBaseUrlConfigured() {
        assertEquals(
            listOf("duckduckgo", "searxng"),
            webSearchAttemptOrder("duckduckgo", emptyMap(), "https://searx.example", false),
        )
        assertTrue("searxng" !in webSearchAttemptOrder("duckduckgo", emptyMap(), "", false))
    }

    @Test
    fun attemptOrder_keepsPrimaryFirst_andNeverDuplicates() {
        val order = webSearchAttemptOrder(
            primary = "searxng",
            apiKeys = mapOf("kagi" to "k"),
            searxngBaseUrl = "https://searx.example",
            cffiAvailable = false,
        )
        assertEquals(listOf("searxng", "duckduckgo", "kagi"), order)
        assertEquals(order.distinct(), order)
    }

    @Test
    fun errorCode_readsMachineReadableField() {
        assertEquals("captcha", webSearchErrorCode("""{"type":"web_search","error":"captcha"}"""))
        assertNull(webSearchErrorCode("""{"type":"web_search","results":[]}"""))
        assertNull(webSearchErrorCode("not json at all"))
    }

    @Test
    fun displayName_coversCffiBridge() {
        assertEquals("DuckDuckGo (curl_cffi)", webSearchProviderDisplayName("duckduckgo_cffi"))
    }

    @Test
    fun failureAttempt_recordsProviderErrorAndMessage() {
        val attempt = webSearchFailureAttempt(
            "duckduckgo_cffi",
            """{"type":"web_search","error":"network_error","message":"curl_cffi is being installed"}""",
        )
        assertEquals("duckduckgo_cffi", attempt["provider"]?.toString()?.trim('"'))
        assertEquals("network_error", attempt["error"]?.toString()?.trim('"'))
        assertEquals("curl_cffi is being installed", attempt["message"]?.toString()?.trim('"'))
    }

    @Test
    fun failureAttempt_survivesNonJsonPayload() {
        val attempt = webSearchFailureAttempt("searxng", "connection closed")
        assertEquals("searxng", attempt["provider"]?.toString()?.trim('"'))
        assertEquals("unknown", attempt["error"]?.toString()?.trim('"'))
        assertNull(attempt["message"])
    }

    @Test
    fun attemptsAreAttachedOnlyWhenSomethingFailed() {
        val payload = """{"type":"web_search","results":[]}"""
        assertEquals(payload, annotateSearchAttempts(payload, emptyList()))

        val annotated = annotateSearchAttempts(
            payload,
            listOf(webSearchFailureAttempt("duckduckgo", """{"error":"captcha"}""")),
        )
        assertTrue(annotated.contains("\"attempts\""))
        assertTrue(annotated.contains("\"provider\":\"duckduckgo\""))
    }
}
