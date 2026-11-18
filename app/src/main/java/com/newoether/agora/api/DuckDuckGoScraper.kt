package com.newoether.agora.api

import com.newoether.agora.util.Constants
import com.newoether.agora.util.executeWithNetworkRetry

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * DuckDuckGo HTML search scraper with auto-pagination.
 *
 * Scrapes [https://html.duckduckgo.com/html/](https://html.duckduckgo.com/html/) — DDG's
 * no-JavaScript results page — and returns structured [WebResult]s. Subsequent pages are
 * fetched with DDG's own pagination form (`q` + `s` + `vqd`, plus `dc`/`v`/`o`/`api`).
 *
 * **Why this endpoint and not `lite.duckduckgo.com`**
 * The `/lite/` POST endpoint sends an `anomaly-modal` CAPTCHA challenge to almost any
 * non-browser client, including clients that only spoof the User-Agent (measured: same IP,
 * same query, `lite/` POST → HTTP 202 + challenge, `html/` GET → HTTP 200 + 10 results).
 * The browser-like [Constants.WEB_FETCH_USER_AGENT] is therefore required here; the old
 * `Agora/1.0` token triggered bot protection on both endpoints.
 *
 * **Design**
 * - Zero coupling to ToolProvider, GenerationContext, or UI layers.
 * - OkHttpClient is injected; defaults to the shared [HttpClient.client] singleton.
 * - Regex-only HTML parsing — no Jsoup or other third-party dependency.
 * - DDG redirect URLs (`//duckduckgo.com/l/?uddg=...`) are transparently decoded.
 * - Both the current `result__a` / `result__snippet` markup and the legacy
 *   `result-link` / `result-snippet` markup are accepted.
 * - Errors are reported via [SearchResponse] with machine-readable [SearchErrorType] codes,
 *   so callers (e.g. [com.newoether.agora.tool.WebSearchToolProvider]) can fail over to
 *   another provider instead of surfacing a dead end to the model.
 *
 * **Stability note**
 * DuckDuckGo may still rate-limit or CAPTCHA aggressive clients. This provider is best used
 * as a free / zero-config fallback, not as the only search backend.
 */
class DuckDuckGoScraper(
    private val client: OkHttpClient = HttpClient.client,
) {
    /** A single web search result. */
    data class WebResult(
        val title: String,
        val url: String,
        val snippet: String,
    )

    /** Machine-readable error category for [SearchResponse.Error]. */
    enum class SearchErrorType {
        /** DDG returned a CAPTCHA / bot-detection challenge. Temporary; retry later. */
        CAPTCHA,
        /** HTTP-level failure (network error, timeout, non-2xx status). */
        NETWORK_ERROR,
        /** DDG returned a valid response but contained zero search-result rows. */
        NO_RESULTS,
    }

    /** Result of a [search] call. */
    sealed class SearchResponse {
        data class Success(val results: List<WebResult>) : SearchResponse()
        data class Error(val type: SearchErrorType, val message: String) : SearchResponse()
    }

    companion object {
        private const val BASE_URL = "https://html.duckduckgo.com/html/"
        private const val DEFAULT_MAX_RESULTS = 5
        private const val PAGE_SIZE = 10
        private const val MAX_PAGES = 5

        /** Browser-like UA; a custom `Agora/1.0` token is treated as a bot by DDG. */
        private val USER_AGENT = Constants.WEB_FETCH_USER_AGENT
        private const val ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

        // -- parsing -----------------------------------------------------------------

        private const val RESULT_CLASS = "(?:result__a|result-link)"

        private val LINK_TAG_REGEX = Regex("""<a\s[^>]*class=['"]$RESULT_CLASS['"][^>]*>""")
        private val LINK_TEXT_REGEX = Regex("""<a\s[^>]*class=['"]$RESULT_CLASS['"][^>]*>([\s\S]*?)</a>""")
        private val SNIPPET_ANCHOR_REGEX = Regex("""<a\s[^>]*class=['"]result__snippet['"][^>]*>([\s\S]*?)</a>""")
        private val SNIPPET_CELL_REGEX = Regex("""<t[dh][^>]*class=['"]result-snippet['"][^>]*>([\s\S]*?)</t[dh]>""")
        private val HREF_REGEX = Regex("""href=['"]([^'"]*?)['"]""")
        private val UDDG_REGEX = Regex("""uddg=([^&]+)""")
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
        private val CAPTCHA_REGEX = Regex("""anomaly-modal|challenge-form|Unfortunately.*bots""")

        /** Extracts the vqd session token from the pagination form. */
        private val VQD_REGEX = Regex("""name="vqd"\s+value="([^"]*)"""")
        /** Offset value from the pagination form (e.g. `name="s" value="10"`). */
        private val OFFSET_REGEX = Regex("""name="s"\s+value="(\d+)"""")
        /** `dc` value from the pagination form (e.g. `name="dc" value="11"`). */
        private val DC_REGEX = Regex("""name="dc"\s+value="(\d+)"""")
    }

    // -- public API ----------------------------------------------------------------

    /**
     * Execute a web search and return up to [maxResults] unique results.
     *
     * Automatically fetches additional pages when [maxResults] exceeds the
     * per-page count (~10). Results are deduplicated by URL. Pagination stops
     * when the target count is reached, no further pages are available, or a
     * page yields zero new unique results.
     */
    fun search(query: String, maxResults: Int = DEFAULT_MAX_RESULTS): SearchResponse {
        val allResults = mutableListOf<WebResult>()
        val seenUrls = mutableSetOf<String>()
        var offset = 0
        var vqd = ""
        var dc = ""

        for (page in 0 until MAX_PAGES) {
            if (allResults.size >= maxResults) break

            val pageResult = fetchPage(query, offset, vqd, dc)
            when (pageResult) {
                is PageResult.Success -> {
                    val html = pageResult.html

                    if (CAPTCHA_REGEX.containsMatchIn(html)) {
                        return if (allResults.isEmpty()) {
                            SearchResponse.Error(
                                SearchErrorType.CAPTCHA,
                                "DuckDuckGo bot detection triggered. Try again later or use a different search provider."
                            )
                        } else {
                            // Partial results are better than nothing — return what we have.
                            break
                        }
                    }

                    if (page == 0) {
                        vqd = VQD_REGEX.find(html)?.groupValues?.get(1).orEmpty()
                        dc = DC_REGEX.find(html)?.groupValues?.get(1).orEmpty()
                    }

                    val pageResults = parseResults(html, maxResults = PAGE_SIZE)
                    var newInThisPage = 0
                    for (r in pageResults) {
                        if (allResults.size >= maxResults) break
                        if (seenUrls.add(r.url)) {
                            allResults.add(r)
                            newInThisPage++
                        }
                    }

                    if (newInThisPage == 0) break      // No new unique results.
                    if (vqd.isEmpty()) break            // No Next Page form.

                    val nextOffset = OFFSET_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull()
                    if (nextOffset == null || nextOffset <= offset) break
                    offset = nextOffset
                }

                is PageResult.Error -> {
                    return if (allResults.isEmpty()) {
                        SearchResponse.Error(
                            SearchErrorType.NETWORK_ERROR,
                            pageResult.message
                        )
                    } else {
                        // Partial results — return what we have rather than failing.
                        break
                    }
                }
            }
        }

        return if (allResults.isEmpty()) {
            SearchResponse.Error(
                SearchErrorType.NO_RESULTS,
                "DuckDuckGo returned no results for this query."
            )
        } else {
            SearchResponse.Success(allResults)
        }
    }

    // -- internals ----------------------------------------------------------------

    /** Outcome of a single page fetch. */
    private sealed class PageResult {
        data class Success(val html: String) : PageResult()
        data class Error(val message: String) : PageResult()
    }

    /** Fetch a single page of search results (GET for the first page, POST for the rest). */
    private fun fetchPage(query: String, offset: Int, vqd: String, dc: String): PageResult {
        val builder = if (offset == 0) {
            Request.Builder()
                .url("$BASE_URL?q=" + URLEncoder.encode(query, Charsets.UTF_8.name()))
                .get()
        } else {
            val formBuilder = FormBody.Builder()
                .add("q", query)
                .add("s", offset.toString())
                .add("nextParams", "")
                .add("v", "l")
                .add("o", "json")
                .add("api", "d.js")
                .add("vqd", vqd)
                .add("kl", "wt-wt")
            if (dc.isNotEmpty()) formBuilder.add("dc", dc)
            Request.Builder()
                .url(BASE_URL)
                .post(formBuilder.build())
        }

        val request = builder
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .header("Referer", BASE_URL)
            .build()

        return try {
            client.executeWithNetworkRetry(request) { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (body != null) PageResult.Success(body)
                    else PageResult.Error("Empty response body from DuckDuckGo.")
                } else {
                    PageResult.Error("DuckDuckGo returned HTTP ${resp.code}.")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: IOException) {
            PageResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            PageResult.Error("Unexpected error: ${e.message}")
        }
    }

    internal fun parseResults(html: String, maxResults: Int): List<WebResult> {
        val results = mutableListOf<WebResult>()

        val linkTags = LINK_TAG_REGEX.findAll(html).toList()
        val linkTexts = LINK_TEXT_REGEX.findAll(html).toList()
        val anchorSnippets = SNIPPET_ANCHOR_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        val snippets = anchorSnippets.ifEmpty {
            SNIPPET_CELL_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        }

        for (i in linkTexts.indices) {
            if (results.size >= maxResults) break

            val linkTag = linkTags.getOrNull(i)?.value ?: continue
            val href = HREF_REGEX.find(linkTag)?.groupValues?.get(1) ?: continue
            val title = linkTexts[i].groupValues[1].stripHtml().trim()
            val snippet = snippets.getOrNull(i)?.stripHtml()?.trim().orEmpty()

            val url = extractUrl(href)

            if (url.isNotBlank() && title.isNotBlank()) {
                results.add(WebResult(title, url, snippet))
            }
        }

        return results
    }

    /**
     * Resolve the destination URL from an `<a href>` value.
     *
     * DDG wraps results in its own redirect:
     *   `//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com&rut=...`
     *
     * Some results (especially POST searches) link directly to the destination.
     */
    private fun extractUrl(href: String): String {
        val uddgParam = UDDG_REGEX.find(href)?.groupValues?.get(1)
        if (uddgParam != null) {
            return URLDecoder.decode(uddgParam, "UTF-8")
        }
        return if (href.startsWith("//")) "https:$href" else href
    }

    /** Strip HTML tags and decode common entities. */
    private fun String.stripHtml(): String = replace(HTML_TAG_REGEX, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&hellip;", "…")
}
