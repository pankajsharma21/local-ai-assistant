package com.pankaj.localai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Structured-fact lookup via Wikidata — the keyless alternative to Tavily web search.
 *
 * Why this exists alongside WikipediaSearchTool: Wikipedia gives you prose you have to hope
 * mentions the fact you want, while Wikidata stores facts as actual data. For "what's the latest
 * version of X" that difference matters a lot — Wikidata returns version strings with release
 * dates, already sortable, instead of a paragraph that may or may not have been updated. Verified
 * against Java/Python/PostgreSQL/Kubernetes; the Kubernetes answer was a release from the same week,
 * i.e. far fresher than any model's training data.
 *
 * Free forever, no API key, no account, no rate-limit signup — same Wikimedia infrastructure
 * Wikipedia runs on.
 *
 * Two-step lookup: resolve the free-text query to a Wikidata entity (wbsearchentities), then SPARQL
 * that entity's "software version identifier" (P348) statements with their "publication date" (P577)
 * qualifiers. Falls back to the entity's own label/description when it has no version data, which is
 * still useful grounding for non-software questions.
 *
 * Response bodies are parsed with our own classic-Jackson ObjectMapper rather than RestClient's
 * auto-conversion — see WikipediaSearchTool's class comment for why (Spring Boot 4 ships Jackson 3,
 * LangChain4j uses Jackson 2).
 */
@Component
public class WikidataSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WikidataSearchTool.class);
    private static final int MAX_VERSIONS = 6;

    private final RestClient restClient = RestClient.builder()
            .defaultHeader("User-Agent", "LocalAiAssistant/1.0 (https://github.com/pankajsharma21/local-ai-assistant)")
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("""
        Look up structured facts from Wikidata - best tool for "what is the latest/current version of
        X" questions about software, languages, databases or tools, because it returns actual version
        numbers with their release dates rather than prose. Always available, no setup or API key.
        Prefer this over your own memory for anything version-related: your training data has a cutoff
        and will be wrong. Note the results may include pre-releases (beta/rc) alongside stable
        versions - use the release dates and version strings to tell them apart, and say which is
        which. Returns nothing useful for non-entity questions (news, opinions, blog posts).
        """)
    public String searchWikidata(String query) {
        log.info("searchWikidata called with query: {}", query);
        try {
            JsonNode entity = resolveEntity(query);
            if (entity == null) {
                return "No matching Wikidata entity found for '" + query + "'.";
            }
            String qid = entity.path("id").asText();
            String label = entity.path("label").asText(qid);
            String description = entity.path("description").asText("");

            String versions = fetchVersions(qid);
            log.debug("Wikidata entity={} ({}), versions={}", qid, label, versions == null ? "NULL" : versions.replace('\n', ';'));
            if (versions != null && !versions.isBlank()) {
                return "[Wikidata: " + label + (description.isBlank() ? "" : " — " + description) + "]\n"
                        + "Known versions (newest release date first):\n" + versions;
            }
            return "[Wikidata: " + label + "]\n"
                    + (description.isBlank() ? "No description available." : description)
                    + "\n(No version data recorded in Wikidata for this entity.)";
        } catch (Exception e) {
            log.warn("Wikidata lookup failed for '{}'", query, e);
            return "Wikidata lookup failed (" + e.getMessage() + "). Try searchWikipedia instead, "
                    + "or tell the user you couldn't verify a current answer.";
        }
    }

    /**
     * Resolves free text to the best-matching Wikidata entity.
     *
     * wbsearchentities matches entity NAMES, not question phrases - "latest version of Kubernetes"
     * returns zero hits while "Kubernetes" resolves fine (verified against the live API). Since the
     * LLM naturally passes through a phrase like the user's question, strip the question/version
     * boilerplate and try progressively simpler candidates until one resolves.
     */
    private JsonNode resolveEntity(String query) throws Exception {
        for (String candidate : candidateTerms(query)) {
            JsonNode hit = searchEntities(candidate);
            if (hit != null) {
                log.debug("Wikidata resolved '{}' via candidate '{}'", query, candidate);
                return hit;
            }
        }
        return null;
    }

    /** Progressively simpler search terms: the raw query, then boilerplate-stripped, then longest word. */
    private List<String> candidateTerms(String query) {
        List<String> candidates = new ArrayList<>();
        candidates.add(query.strip());

        String stripped = query.replaceAll("(?i)\\b(what|which|who|is|are|was|the|latest|newest|"
                + "current|stable|recent|version|versions|release|released|of|for|in|a|an|tell|me|about)\\b", " ")
                .replaceAll("[^\\w\\s.+#-]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        if (!stripped.isBlank()) {
            candidates.add(stripped);
            String longest = Arrays.stream(stripped.split("\\s+"))
                    .filter(w -> w.length() > 2)
                    .max(Comparator.comparingInt(String::length))
                    .orElse("");
            if (!longest.isBlank()) {
                candidates.add(longest);
            }
        }
        // de-duplicate, preserving order
        return candidates.stream().distinct().filter(s -> !s.isBlank()).toList();
    }

    private JsonNode searchEntities(String term) throws Exception {
        String raw = restClient.get()
                .uri(b -> b.scheme("https").host("www.wikidata.org").path("/w/api.php")
                        .queryParam("action", "wbsearchentities")
                        .queryParam("search", term)
                        .queryParam("language", "en")
                        .queryParam("format", "json")
                        .queryParam("limit", 1)
                        .queryParam("type", "item")
                        .build())
                .retrieve()
                .body(String.class);
        if (raw == null) {
            return null;
        }
        JsonNode hits = objectMapper.readTree(raw).path("search");
        return hits.isArray() && !hits.isEmpty() ? hits.get(0) : null;
    }

    /** SPARQL for the entity's version statements (P348) with release-date qualifiers (P577). */
    private String fetchVersions(String qid) throws Exception {
        String sparql = """
                SELECT ?ver ?date WHERE {
                  wd:%s p:P348 ?st .
                  ?st ps:P348 ?ver .
                  OPTIONAL { ?st pq:P577 ?date . }
                } ORDER BY DESC(?date) LIMIT %d
                """.formatted(qid, MAX_VERSIONS);

        // Build this URI by hand rather than via uriBuilder.queryParam: Spring's UriBuilder treats
        // "{...}" in a query value as a URI template placeholder, and SPARQL is full of braces, so
        // it throws IllegalArgumentException("Not enough variable values available to expand ...")
        // trying to interpret the query body as a variable name. Passing a pre-encoded java.net.URI
        // skips template expansion entirely.
        URI uri = URI.create("https://query.wikidata.org/sparql?query="
                + URLEncoder.encode(sparql, StandardCharsets.UTF_8) + "&format=json");

        String raw = restClient.get().uri(uri).retrieve().body(String.class);
        if (raw == null) {
            return null;
        }

        JsonNode bindings = objectMapper.readTree(raw).path("results").path("bindings");
        if (!bindings.isArray() || bindings.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : bindings) {
            String version = b.path("ver").path("value").asText("");
            String date = b.path("date").path("value").asText("");
            sb.append("  - ").append(version);
            if (!date.isBlank()) {
                sb.append("  (released ").append(date.length() >= 10 ? date.substring(0, 10) : date).append(')');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
