package com.newoether.agora.api

import android.util.Base64
import com.newoether.agora.sandbox.SandboxManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * DuckDuckGo search through a `curl_cffi` helper running inside the Local Sandbox.
 *
 * DuckDuckGo's bot protection is not only a User-Agent check: it looks at the TLS/JA3 and
 * HTTP/2 fingerprint of the handshake, which OkHttp cannot mask. `curl_cffi` impersonates a
 * real browser handshake, so it survives networks where plain OkHttp gets a CAPTCHA page.
 *
 * Trade-offs, deliberately accepted:
 * - **F-Droid / sandbox builds only.** Play builds have no exec backend, so the caller must
 *   fall back to another provider.
 * - **The helper must exist.** `curl_cffi` ships aarch64 wheels for both glibc and musl
 *   (`curl_cffi-0.17.0-cp310-abi3-manylinux2014_aarch64` / `...-musllinux_1_2_aarch64`), so a
 *   plain `pip install` works in the Alpine/Debian/Ubuntu rootfs. The first call kicks off
 *   that install as a background job and reports `dependency_installing` instead of blocking
 *   the whole tool round.
 * - **Cold start.** Importing `curl_cffi` plus the proot hop costs a few hundred ms; this is
 *   why the bridge is a fallback, not the default path.
 */
internal class CffiSearchBridge(private val sandbox: SandboxManager) {

    suspend fun search(query: String, maxResults: Int): DuckDuckGoScraper.SearchResponse {
        if (!sandbox.isAvailable()) {
            return DuckDuckGoScraper.SearchResponse.Error(
                DuckDuckGoScraper.SearchErrorType.NETWORK_ERROR,
                "The Local Sandbox is not installed, so the curl_cffi search bridge is unavailable.",
            )
        }

        val writeFailure = runCatching {
            sandbox.fileWrite(SCRIPT_PATH, SCRIPT, "")
                ?: sandbox.fileWrite(BOOTSTRAP_PATH, BOOTSTRAP, "")
        }.getOrNull()
        if (writeFailure != null) {
            return DuckDuckGoScraper.SearchResponse.Error(
                DuckDuckGoScraper.SearchErrorType.NETWORK_ERROR,
                "Could not write the search bridge script: $writeFailure",
            )
        }

        val probe = runCatching {
            sandbox.executeCommand(DEPENDENCY_PROBE, "", PROBE_TIMEOUT_MS)
        }.getOrNull()
        if (probe == null || probe.exitCode != 0) {
            // Bootstrapping needs an index update plus a wheel download — longer than one tool
            // round should block, so hand it to the sandbox job runner and let the next call win.
            runCatching {
                sandbox.startJob("sh $BOOTSTRAP_PATH", "", INSTALL_TIMEOUT_MS, "Install curl_cffi")
            }
            return DuckDuckGoScraper.SearchResponse.Error(
                DuckDuckGoScraper.SearchErrorType.NETWORK_ERROR,
                "curl_cffi is being installed in the Local Sandbox. Retry this search in about a minute.",
            )
        }

        val encodedQuery = Base64.encodeToString(query.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val command = "python3 $SCRIPT_PATH $encodedQuery ${maxResults.coerceIn(1, 10)}"
        val result = runBridgeWithRetry(command)
            ?: return DuckDuckGoScraper.SearchResponse.Error(
                DuckDuckGoScraper.SearchErrorType.NETWORK_ERROR,
                "The curl_cffi search bridge did not run.",
            )

        val payload = result.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") && it.endsWith("}") }
            ?: return DuckDuckGoScraper.SearchResponse.Error(
                DuckDuckGoScraper.SearchErrorType.NETWORK_ERROR,
                "The curl_cffi search bridge returned no JSON (exit ${result.exitCode}): ${
                    (result.stderr.ifBlank { result.stdout }).take(300)
                }",
            )

        return parsePayload(payload)
    }

    private suspend fun runBridgeWithRetry(command: String): SandboxManager.SandboxResult? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = runCatching {
                sandbox.executeCommand(command, "", SEARCH_TIMEOUT_MS)
            }.getOrNull() ?: return null
            val transient = TRANSIENT_FAILURES.any { result.stdout.contains(it) }
            if (!transient || attempt == MAX_ATTEMPTS - 1) return result
            delay(RETRY_DELAY_MS)
        }
        return null
    }

    private fun parsePayload(payload: String): DuckDuckGoScraper.SearchResponse {
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return DuckDuckGoScraper.SearchResponse.Error(
                DuckDuckGoScraper.SearchErrorType.NETWORK_ERROR,
                "Malformed curl_cffi search bridge response.",
            )

        val error = (root["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (error != null) {
            val type = when (error) {
                "captcha" -> DuckDuckGoScraper.SearchErrorType.CAPTCHA
                "no_results" -> DuckDuckGoScraper.SearchErrorType.NO_RESULTS
                else -> DuckDuckGoScraper.SearchErrorType.NETWORK_ERROR
            }
            val message = (root["message"] as? JsonPrimitive)?.content
                ?: "curl_cffi bridge error: $error"
            return DuckDuckGoScraper.SearchResponse.Error(type, message)
        }

        val results = root["results"]?.jsonArray?.mapNotNull { element ->
            val obj: JsonObject = element as? JsonObject ?: return@mapNotNull null
            val url = (obj["url"] as? JsonPrimitive)?.content.orEmpty()
            val title = (obj["title"] as? JsonPrimitive)?.content.orEmpty()
            if (url.isBlank() || title.isBlank()) return@mapNotNull null
            DuckDuckGoScraper.WebResult(
                title = title,
                url = url,
                snippet = (obj["snippet"] as? JsonPrimitive)?.content.orEmpty(),
            )
        }.orEmpty()

        return if (results.isEmpty()) {
            DuckDuckGoScraper.SearchResponse.Error(
                DuckDuckGoScraper.SearchErrorType.NO_RESULTS,
                "DuckDuckGo (curl_cffi) returned no results for this query.",
            )
        } else {
            DuckDuckGoScraper.SearchResponse.Success(results)
        }
    }

    private companion object {
        const val SCRIPT_PATH = "/home/agora/.agora/search_bridge.py"
        const val BOOTSTRAP_PATH = "/home/agora/.agora/bootstrap_search.sh"
        const val PROBE_TIMEOUT_MS = 20_000
        const val SEARCH_TIMEOUT_MS = 45_000
        const val INSTALL_TIMEOUT_MS = 600_000
        const val RETRY_DELAY_MS = 1_500L
        const val MAX_ATTEMPTS = 2

        /**
         * Handshake resets happen on flaky mobile networks and right after the first
         * `curl_cffi` import; one retry turns those into a slower answer instead of a failure.
         */
        val TRANSIENT_FAILURES = listOf("SSL_connect", "Connection closed abruptly", "BoringSSL")
        const val DEPENDENCY_PROBE = "python3 -c 'import curl_cffi'"

        /**
         * Installs `curl_cffi` into the sandbox userland, surviving a freshly installed rootfs.
         *
         * Alpine ships `py3-pip` in `community`, but the rootfs is created with `main` only, so
         * a plain `pip install` fails with "no such package: py3-pip". The community line is
         * appended here as well, because existing installations never get the installer fix.
         */
        val BOOTSTRAP = """
            set -u
            if python3 -m pip --version >/dev/null 2>&1; then
              python3 -m pip install --quiet --disable-pip-version-check \
                --break-system-packages curl_cffi && exit 0
            fi
            if command -v apk >/dev/null 2>&1; then
              ver=${'$'}(cut -d. -f1,2 /etc/alpine-release 2>/dev/null || echo 3.21)
              repo="/v${'$'}{ver}/community"
              grep -q "${'$'}repo" /etc/apk/repositories 2>/dev/null || \
                echo "https://dl-cdn.alpinelinux.org/alpine${'$'}repo" >> /etc/apk/repositories
              apk update >/dev/null 2>&1 || true
              apk add --no-cache py3-pip >/dev/null 2>&1 || true
            fi
            python3 -m pip --version >/dev/null 2>&1 || python3 -m ensurepip --upgrade >/dev/null 2>&1 || true
            python3 -m pip install --quiet --disable-pip-version-check \
              --break-system-packages curl_cffi
        """.trimIndent()

        val SCRIPT = """
            import base64
            import json
            import re
            import sys
            import urllib.parse

            BASE = "https://html.duckduckgo.com/html/"
            HEADERS = {
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.9",
            }
            CAPTCHA_RE = re.compile(r"anomaly-modal|challenge-form|Unfortunately.*bots")
            Q = "[\x22\x27]"
            LINK_TAG = re.compile(r'<a\s[^>]*class=' + Q + r'(?:result__a|result-link)' + Q + r'[^>]*>')
            LINK_TEXT = re.compile(r'<a\s[^>]*class=' + Q + r'(?:result__a|result-link)' + Q + r'[^>]*>([\s\S]*?)</a>')
            SNIP_A = re.compile(r'<a\s[^>]*class=' + Q + r'result__snippet' + Q + r'[^>]*>([\s\S]*?)</a>')
            SNIP_TD = re.compile(r'<t[dh][^>]*class=' + Q + r'result-snippet' + Q + r'[^>]*>([\s\S]*?)</t[dh]>')
            HREF = re.compile(r'href=' + Q + r'([^\x22\x27]*?)' + Q)
            UDDG = re.compile(r"uddg=([^&]+)")
            VQD = re.compile(r'name="vqd"\s+value="([^"]*)"')
            OFFSET = re.compile(r'name="s"\s+value="(\d+)"')
            DC = re.compile(r'name="dc"\s+value="(\d+)"')
            TAGS = re.compile(r"<[^>]*>")


            def text_of(raw):
                value = TAGS.sub("", raw)
                for needle, replacement in (
                    ("&amp;", "&"), ("&quot;", '"'), ("&#x27;", "'"), ("&#39;", "'"),
                    ("&lt;", "<"), ("&gt;", ">"), ("&nbsp;", " "), ("&hellip;", "..."),
                ):
                    value = value.replace(needle, replacement)
                return value.strip()


            def parse(html, limit):
                tags = LINK_TAG.findall(html)
                texts = LINK_TEXT.findall(html)
                snippets = [m.group(1) for m in SNIP_A.finditer(html)]
                if not snippets:
                    snippets = [m.group(1) for m in SNIP_TD.finditer(html)]
                results = []
                for index, raw_title in enumerate(texts):
                    if len(results) >= limit:
                        break
                    if index >= len(tags):
                        break
                    match = HREF.search(tags[index])
                    if not match:
                        continue
                    url = match.group(1)
                    redirect = UDDG.search(url)
                    if redirect:
                        url = urllib.parse.unquote(redirect.group(1))
                    elif url.startswith("//"):
                        url = "https:" + url
                    title = text_of(raw_title)
                    if not title or not url:
                        continue
                    snippet = text_of(snippets[index]) if index < len(snippets) else ""
                    results.append({"title": title, "url": url, "snippet": snippet})
                return results


            def main():
                try:
                    query = base64.b64decode(sys.argv[1]).decode("utf-8")
                except Exception:
                    print(json.dumps({"error": "bad_query"}))
                    return
                try:
                    limit = int(sys.argv[2])
                except Exception:
                    limit = 5

                try:
                    from curl_cffi import requests
                except Exception as exc:
                    print(json.dumps({"error": "dependency_missing", "message": str(exc)}))
                    return

                try:
                    session = requests.Session(impersonate="chrome")
                    response = session.get(BASE, params={"q": query}, headers=HEADERS, timeout=30)
                    if CAPTCHA_RE.search(response.text):
                        print(json.dumps({"error": "captcha"}))
                        return
                    results = parse(response.text, 10)
                    seen = {item["url"] for item in results}
                    offset = OFFSET.search(response.text)
                    vqd = VQD.search(response.text)
                    dc = DC.search(response.text)
                    pages = 1
                    while len(results) < limit and pages < 5 and offset and vqd:
                        fields = {
                            "q": query,
                            "s": offset.group(1),
                            "nextParams": "",
                            "v": "l",
                            "o": "json",
                            "api": "d.js",
                            "vqd": vqd.group(1),
                            "kl": "wt-wt",
                        }
                        if dc:
                            fields["dc"] = dc.group(1)
                        page = session.post(BASE, data=fields, headers=HEADERS, timeout=30)
                        if CAPTCHA_RE.search(page.text):
                            break
                        fresh = [item for item in parse(page.text, 10) if item["url"] not in seen]
                        if not fresh:
                            break
                        for item in fresh:
                            seen.add(item["url"])
                            results.append(item)
                        offset = OFFSET.search(page.text)
                        vqd = VQD.search(page.text)
                        dc = DC.search(page.text)
                        pages += 1
                    print(json.dumps({"results": results[:limit]}))
                except Exception as exc:
                    print(json.dumps({"error": "bridge_error", "message": str(exc)}))


            if __name__ == "__main__":
                main()
        """.trimIndent()
    }
}
