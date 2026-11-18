package com.newoether.agora.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scraper reads `html.duckduckgo.com`, whose markup differs from the legacy
 * `lite.duckduckgo.com` table layout:
 * - title anchor: `class="result__a"` (lite: `class='result-link'`)
 * - snippet anchor: `class="result__snippet"` (lite: `td class='result-snippet'`)
 */
class DuckDuckGoScraperHtmlEndpointTest {

    private val scraper = DuckDuckGoScraper()

    private fun page(vararg results: Triple<String, String, String>): String = buildString {
        append("<html><body><div id=\"links\">")
        results.forEach { (href, title, snippet) ->
            append(
                """
                <div class="result results_links results_links_deep web-result ">
                  <div class="links_main links_deep result__body">
                    <h2 class="result__title">
                      <a rel="nofollow" class="result__a" href="$href">$title</a>
                    </h2>
                    <a class="result__snippet" href="$href">$snippet</a>
                  </div>
                </div>
                """.trimIndent()
            )
        }
        append("</div></body></html>")
    }

    @Test
    fun parseResults_currentMarkup_parsesTitleUrlAndSnippet() {
        val html = page(
            Triple(
                "//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines-overview.html&amp;rut=abc",
                "Coroutines | Kotlin Documentation",
                "Kotlin provides several ways for coroutines to communicate."
            ),
            Triple(
                "https://example.com/plain",
                "Plain result",
                "Direct link, no redirect wrapper."
            ),
        )

        val results = scraper.parseResults(html, maxResults = 5)

        assertEquals(2, results.size)
        assertEquals("Coroutines | Kotlin Documentation", results[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-overview.html", results[0].url)
        assertEquals("Kotlin provides several ways for coroutines to communicate.", results[0].snippet)
        assertEquals("https://example.com/plain", results[1].url)
        assertEquals("Direct link, no redirect wrapper.", results[1].snippet)
    }

    @Test
    fun parseResults_currentMarkup_keepsEntitiesDecoded() {
        val html = page(
            Triple(
                "https://example.com/a",
                "Rock &amp; Roll",
                "&quot;Quoted&quot; snippet with &lt;code&gt; tags &amp; more"
            )
        )

        val results = scraper.parseResults(html, maxResults = 5)

        assertEquals(1, results.size)
        assertEquals("Rock & Roll", results[0].title)
        assertEquals("\"Quoted\" snippet with <code> tags & more", results[0].snippet)
    }

    @Test
    fun parseResults_currentMarkup_respectsMaxResults() {
        val html = page(
            Triple("https://a.example", "A", "a"),
            Triple("https://b.example", "B", "b"),
            Triple("https://c.example", "C", "c"),
        )

        assertEquals(2, scraper.parseResults(html, maxResults = 2).size)
    }

    @Test
    fun parseResults_legacyLiteMarkup_stillParsed() {
        val html = """
            <table>
              <tr><td>1.&nbsp;</td><td><a rel="nofollow" href="https://example.com/x" class='result-link'>Legacy Title</a></td></tr>
              <tr><td>&nbsp;</td><td class='result-snippet'>Legacy snippet.</td></tr>
            </table>
        """.trimIndent()

        val results = scraper.parseResults(html, maxResults = 5)

        assertEquals(1, results.size)
        assertEquals("Legacy Title", results[0].title)
        assertEquals("Legacy snippet.", results[0].snippet)
    }

    @Test
    fun parseResults_captchaPage_returnsNothing() {
        val html = """
            <html><body>
              <div class="anomaly-modal__mask">Unfortunately, bots use DuckDuckGo too.</div>
            </body></html>
        """.trimIndent()

        assertTrue(scraper.parseResults(html, maxResults = 5).isEmpty())
    }
}
