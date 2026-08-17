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
 * same way voice is — missing config degrades to a clear message instead of a stack trace, and the
 * agent is told (via the tool's own description + Assistant's system prompt) to fall back to
 * searchWikipedia or its own knowledge when this isn't configured.
 *
 * Response body is parsed manually with our own (classic, com.fasterxml) Jackson ObjectMapper —
 * see WikipediaSearchTool's class comment for why: Spring Boot 4's auto-configured RestClient
 * converters use the new Jackson 3, which can't deserialize into a classic Jackson-2 JsonNode.
 */
@Component
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final AssistantProperties.WebSearch config;
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.tavily.com")
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebSearchTool(AssistantProperties props) {
        this.config = props.getWebSearch();
    }

    public boolean isAvailable() {
        return config.isEnabled() && config.getTavilyApiKey() != null && !config.getTavilyApiKey().isBlank();
    }

    @Tool("""
        Search the live web for current, up-to-date information - anything that could have changed
        since your training cutoff: current software/library versions, recent events, prices,
        "latest"/"newest" anything. Do NOT answer these from your own memory, it may be stale or
        simply wrong - call this tool instead. If it reports it isn't configured, fall back to
        searchWikipedia, and tell the user their answer might be outdated.
        """)
    public String searchWeb(String query) {
        if (!isAvailable()) {
            return "Live web search is not configured. Tell the user: set assistant.web-search.enabled=true " +
                    "and add a free Tavily API key (https://app.tavily.com) under assistant.web-search.tavily-api-key " +
                    "in application.yml, then restart. Use searchWikipedia instead for now.";
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
            log.warn("Web search failed for '{}'", query, e);
            return "Web search failed (" + e.getMessage() + "). Fall back to searchWikipedia or say you're unsure.";
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
