package com.pankaj.localai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The ONE always-on, no-setup way this assistant reaches outside localhost: Wikipedia's public
 * REST API (no API key, no signup — official, documented, free). This directly fixes the
 * "LLM confidently states an outdated fact because its training data has a cutoff" problem for
 * well-established facts (software version history, definitions, historical events) — see
 * WebSearchTool for the broader (opt-in, API-key-gated) live web search option.
 *
 * Response bodies are parsed manually with our own (classic, com.fasterxml) Jackson ObjectMapper
 * rather than RestClient's auto message conversion: Spring Boot 4 moved its own auto-configured
 * converters to the new Jackson 3 ("tools.jackson.*"), while LangChain4j (and this class) still use
 * classic Jackson 2 — asking RestClient to deserialize straight into a Jackson-2 JsonNode fails with
 * "Type definition error" because Spring's Jackson-3 converter doesn't recognize that type at all.
 * Fetching the raw String and parsing it ourselves sidesteps the version mismatch entirely.
 */
@Component
public class WikipediaSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WikipediaSearchTool.class);
    private static final String API_BASE = "https://en.wikipedia.org";
    private static final int MAX_EXTRACT_CHARS = 2500;

    private final RestClient restClient = RestClient.builder()
            .baseUrl(API_BASE)
            .defaultHeader("User-Agent", "LocalAiAssistant/1.0 (https://github.com/pankajsharma21/local-ai-assistant)")
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("""
        Look up encyclopedic facts on Wikipedia — definitions, history, background. No setup needed. Prefer over memory for anything involving "latest", "current" or "as of".
        """)
    public String searchWikipedia(String query) {
        log.info("searchWikipedia called with query: {}", query);
        try {
            String title = findBestTitle(query);
            if (title == null) {
                return "No matching Wikipedia article found for '" + query + "'.";
            }
            String extract = fetchExtract(title);
            if (extract == null || extract.isBlank()) {
                return "Found a Wikipedia article ('" + title + "') but couldn't extract its text.";
            }
            return "[Wikipedia: " + title + "]\n" + extract;
        } catch (Exception e) {
            log.warn("Wikipedia lookup failed for '{}'", query, e);
            return "Wikipedia lookup failed (" + e.getMessage() + "). Answer from your own knowledge " +
                    "if you can, but tell the user this fact might be outdated.";
        }
    }

    private JsonNode getJson(java.util.function.Function<RestClient.RequestHeadersUriSpec<?>, RestClient.RequestHeadersSpec<?>> uriFn)
            throws Exception {
        String raw = uriFn.apply(restClient.get()).retrieve().body(String.class);
        return raw == null ? null : objectMapper.readTree(raw);
    }

    /**
     * Resolves a free-text query to the best-matching page title using Wikipedia's full-text
     * search (action=query&list=search) — NOT the "opensearch" endpoint, which only does
     * title-PREFIX matching and returns empty for anything phrased as a natural question.
     * Verified: "latest Java version" (a real query an LLM generates) returns zero results from
     * opensearch but correctly finds "Java version history" as the #1 hit from full-text search.
     */
    private String findBestTitle(String query) throws Exception {
        JsonNode result = getJson(spec -> spec.uri(uriBuilder -> uriBuilder.path("/w/api.php")
                .queryParam("action", "query")
                .queryParam("list", "search")
                .queryParam("srsearch", query)
                .queryParam("srlimit", 1)
                .queryParam("format", "json")
                .build()));
        if (result == null) {
            return null;
        }
        JsonNode hits = result.path("query").path("search");
        if (!hits.isArray() || hits.isEmpty()) {
            return null;
        }
        return hits.get(0).path("title").asText(null);
    }

    /**
     * Fetches the full plain-text article extract (not just the intro paragraph) — the short
     * REST "summary" endpoint frequently omits the actual current fact (e.g. a "latest version"
     * number lives further down the article, in a section the summary endpoint never includes).
     * Truncated to keep the tool result small enough for a local model's limited context window.
     */
    private String fetchExtract(String title) throws Exception {
        JsonNode result = getJson(spec -> spec.uri(uriBuilder -> uriBuilder.path("/w/api.php")
                .queryParam("action", "query")
                .queryParam("prop", "extracts")
                .queryParam("explaintext", "1")
                .queryParam("titles", title)
                .queryParam("format", "json")
                .build()));
        if (result == null) {
            return null;
        }
        JsonNode pages = result.path("query").path("pages");
        for (JsonNode page : pages) {
            String extract = page.path("extract").asText(null);
            if (extract != null && !extract.isBlank()) {
                return extract.length() > MAX_EXTRACT_CHARS ? extract.substring(0, MAX_EXTRACT_CHARS) + "…" : extract;
            }
        }
        return null;
    }
}
