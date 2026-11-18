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
    fun attemptOrder_neverAddsCffiBridge_asAPrimaryFallbackForOtherProviders() {
        val order = webSearchAttemptOrder(
            primary = "brave",
            apiKeys = mapOf("brave" to "key"),
            searxngBaseUrl = "",
            cffiAvailable = true,
        )
        assertEquals(listOf("brave"), order)
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
        val withUrl = webSearchAttemptOrder("duckduckgo", emptyMap(), "https://searx.example", false)
        assertEquals(listOf("duckduckgo", "searxng"), withUrl)

        val withoutUrl = webSearchAttemptOrder("duckduckgo", emptyMap(), "", false)
        assertTrue("searxng" !in withoutUrl)
    }

    @Test
    fun attemptOrder_keepsPrimaryFirst_andNeverDuplicates() {
        val order = webSearchAttemptOrder(
            primary = "searxng",
            apiKeys = mapOf("kagi" to "k"),
            searxngBaseUrl = "https://searx.example",
            cffiAvailable = false,
        )
        assertEquals("searxng", order.first())
        assertEquals(order.distinct(), order)
        assertEquals(listOf("searxng", "kagi"), order)
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
}
