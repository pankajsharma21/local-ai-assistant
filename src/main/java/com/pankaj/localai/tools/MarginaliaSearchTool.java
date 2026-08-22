package com.pankaj.localai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pankaj.localai.config.AssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Keyless general web search via Marginalia's public API — the pure-Java alternative to scraping.
 *
 * Why this and not DuckDuckGo/Bing/Google: those actively block programmatic access. Verified by
 * probing each with a plain browser-style GET (i.e. exactly what a Java HttpClient does):
 * DuckDuckGo returned an anti-bot challenge page with zero result links, Mojeek returned an empty
 * page, Brave returned HTTP 429. Getting real results from them needs a maintained multi-engine
 * scraper with rotation and anti-bot workarounds, and no such library exists for Java (checked
 * Maven Central) — so it would mean writing and owning that ourselves.
 *
 * Marginalia instead offers a documented public JSON endpoint with no key, no account and no
 * signup, which a plain RestClient call can consume. Measured ~2-4s per query.
 *
 * The honest trade-off: Marginalia deliberately favours independent, non-commercial web content and
 * down-ranks large commercial sites. For "latest version of X" questions its results are weaker
 * than DuckDuckGo's (which surfaced the canonical kubernetes.io / GitHub releases pages). That's
 * why it sits BELOW Tavily and ABOVE Wikidata in the fallback chain rather than being the only
 * source — WikidataSearchTool remains the reliable answer for version numbers specifically.
 */
@Component
public class MarginaliaSearchTool {

    private static final Logger log = LoggerFactory.getLogger(MarginaliaSearchTool.class);
    private static final int MAX_RESULTS = 5;
    private static final int SNIPPET_CHARS = 400;

    private final RestClient restClient = RestClient.builder()
            .defaultHeader("User-Agent", "LocalAiAssistant/1.0 (https://github.com/pankajsharma21/local-ai-assistant)")
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;

    public MarginaliaSearchTool(AssistantProperties props) {
        this.enabled = props.getWebSearch().isMarginaliaEnabled();
    }

    public boolean isAvailable() {
        return enabled;
    }

    /** Returns formatted results, or null when disabled/empty/failed so callers can fall through. */
    public String search(String query) {
        if (!enabled) {
            return null;
        }
        log.info("Marginalia search for: {}", query);
        try {
            // Path-segment API: /public/search/{query} — encode the query into the path, and turn
            // URLEncoder's form-style '+' back into %20 since this is a path segment, not a form field.
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
            URI uri = URI.create("https://api.marginalia.nu/public/search/" + encoded);

            String raw = restClient.get().uri(uri).retrieve().body(String.class);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return format(objectMapper.readTree(raw).path("results"));
        } catch (Exception e) {
            log.warn("Marginalia search failed for '{}': {}", query, e.getMessage());
            return null;
        }
    }

    private String format(JsonNode results) {
        if (results == null || !results.isArray() || results.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (JsonNode r : results) {
            if (count++ >= MAX_RESULTS) {
                break;
            }
            String description = r.path("description").asText("");
            sb.append('[').append(r.path("title").asText("untitled")).append("]\n")
              .append(r.path("url").asText("")).append('\n')
              .append(description.length() > SNIPPET_CHARS ? description.substring(0, SNIPPET_CHARS) : description)
              .append("\n\n");
        }
        String out = sb.toString().strip();
        return out.isBlank() ? null : out;
    }
}
