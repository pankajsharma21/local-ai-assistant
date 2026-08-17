package com.pankaj.localai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pankaj.localai.config.AssistantProperties;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real-time web search via Tavily (https://tavily.com), an API built specifically for AI agents —
 * results come back as clean title/url/content triples instead of raw HTML to scrape.
 *
 * This is the one tool in the whole app that is opt-in AND requires an external account: it needs
 * assistant.web-search.enabled=true plus a free API key in application.yml. Deliberately gated the
 * same way voice is — missing config degrades gracefully instead of a stack trace.
 *
 * IMPORTANT: when Tavily isn't configured (or the call fails), this tool falls back to
 * WikipediaSearchTool ITSELF, in code — it does not just tell the model "go call searchWikipedia
 * instead" and hope it does. Verified in testing: mid-size local models will call searchWeb, read a
 * "not configured" message, and simply give up instead of making a second tool call — models are
 * far more reliable at using one tool's result than at chaining two tool calls on their own
 * initiative. Handling the fallback deterministically in code removes that failure mode entirely.
 *
 * Response body is parsed manually with our own (classic, com.fasterxml) Jackson ObjectMapper —
 * see WikipediaSearchTool's class comment for why: Spring Boot 4's auto-configured RestClient
 * converters use the new Jackson 3, which can't deserialize into a classic Jackson-2 JsonNode.
 */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final AssistantProperties.WebSearch config;
    private final WikipediaSearchTool wikipediaFallback;
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.tavily.com")
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSearchTool(AssistantProperties props, WikipediaSearchTool wikipediaFallback) {
        this.config = props.getWebSearch();
        this.wikipediaFallback = wikipediaFallback;
    }

    public boolean isAvailable() {
        return config.isEnabled() && config.getTavilyApiKey() != null && !config.getTavilyApiKey().isBlank();
    }

    @Tool("""
        Search the live web for current, up-to-date information - anything that could have changed
        since your training cutoff: current software/library versions, recent events, prices,
        "latest"/"newest" anything. Do NOT answer these from your own memory, it may be stale or
        simply wrong - call this tool instead. If live search isn't configured on this machine, this
        tool automatically returns a Wikipedia-sourced answer instead - mention to the user that the
        answer came from Wikipedia (not live search) if that happens.
        """)
    public String searchWeb(String query) {
        log.info("searchWeb called with query: {}", query);
        if (!isAvailable()) {
            log.info("Tavily not configured, falling back to Wikipedia for: {}", query);
            return "[Live web search not configured — falling back to Wikipedia]\n" + wikipediaFallback.searchWikipedia(query);
        }
        try {
            Map<String, Object> requestBody = Map.of(
                    "api_key", config.getTavilyApiKey(),
                    "query", query,
                    "max_results", config.getMaxResults(),
                    "search_depth", "basic",
                    "include_answer", true
            );

            String raw = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            JsonNode response = raw == null ? null : objectMapper.readTree(raw);

            return format(response);
        } catch (Exception e) {
            log.warn("Web search failed for '{}', falling back to Wikipedia", query, e);
            return "[Live web search failed — falling back to Wikipedia]\n" + wikipediaFallback.searchWikipedia(query);
        }
    }

    private String format(JsonNode response) {
        if (response == null) {
            return "Web search returned no data.";
        }
        StringBuilder sb = new StringBuilder();

        String directAnswer = response.path("answer").asText(null);
        if (directAnswer != null && !directAnswer.isBlank()) {
            sb.append("Quick answer: ").append(directAnswer).append("\n\n");
        }

        JsonNode results = response.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return sb.isEmpty() ? "No web results found." : sb.toString().strip();
        }
        for (JsonNode result : results) {
            sb.append("[").append(result.path("title").asText("untitled")).append("]\n")
              .append(result.path("url").asText("")).append("\n")
              .append(result.path("content").asText("")).append("\n\n");
        }
        return sb.toString().strip();
    }
}
