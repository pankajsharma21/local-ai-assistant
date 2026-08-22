package com.pankaj.localai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pankaj.localai.config.AssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Keyless general web search via DuckDuckGo — the "no API key, no account, no credit card" answer to
 * live web search, as opposed to Tavily (WebSearchTool) which needs a paid-ish account.
 *
 * Unlike WikidataSearchTool/WikipediaSearchTool, which only cover encyclopedic entities, this
 * returns ordinary web results (docs sites, GitHub releases, blog posts, news) — verified returning
 * the real kubernetes.io and github.com/kubernetes/kubernetes/releases pages.
 *
 * Runs through a small Python bridge (scripts/ddg_search.py) in a dedicated venv rather than being
 * implemented in Java, because DuckDuckGo actively blocks naive HTML scraping — raw requests come
 * back as an anti-bot challenge page with zero parseable results (verified). The `ddgs` library
 * maintains those workarounds; reimplementing them in Java would mean owning that cat-and-mouse
 * game. Shelling out to an external tool is already the established pattern here (whisper.cpp,
 * Piper), so this is consistent rather than a new kind of dependency.
 *
 * Not registered as an @Tool of its own: models proved unreliable at chaining a second tool call
 * after a first one fails (see WebSearchTool's comment), so this is wired into WebSearchTool's
 * deterministic in-code fallback chain instead.
 */
@Component
public class DuckDuckGoSearchTool {

    private static final Logger log = LoggerFactory.getLogger(DuckDuckGoSearchTool.class);
    private static final int TIMEOUT_SECONDS = 45;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path pythonBin;
    private final Path scriptPath;

    public DuckDuckGoSearchTool(AssistantProperties props) {
        this.pythonBin = Path.of(props.getWebSearch().getDuckduckgoPython()).toAbsolutePath().normalize();
        this.scriptPath = Path.of(props.getWebSearch().getDuckduckgoScript()).toAbsolutePath().normalize();
    }

    /** True when the venv interpreter and bridge script both exist, i.e. setup_websearch.sh has run. */
    public boolean isAvailable() {
        return Files.isExecutable(pythonBin) && Files.isRegularFile(scriptPath);
    }

    /** Returns formatted results, or null if unavailable/failed so callers can fall through. */
    public String search(String query) {
        if (!isAvailable()) {
            log.debug("DuckDuckGo bridge not installed (looked for {} and {})", pythonBin, scriptPath);
            return null;
        }
        log.info("DuckDuckGo search for: {}", query);
        try {
            ProcessBuilder pb = new ProcessBuilder(pythonBin.toString(), scriptPath.toString(), query);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            String stdout = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("DuckDuckGo search timed out for: {}", query);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("DuckDuckGo search failed for '{}': {}", query, stdout.strip());
                return null;
            }
            return format(objectMapper.readTree(stdout));
        } catch (Exception e) {
            log.warn("DuckDuckGo search errored for '{}'", query, e);
            return null;
        }
    }

    private String format(JsonNode results) {
        if (results == null || !results.isArray() || results.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode r : results) {
            sb.append('[').append(r.path("title").asText("untitled")).append("]\n")
              .append(r.path("url").asText("")).append('\n')
              .append(r.path("snippet").asText("")).append("\n\n");
        }
        return sb.toString().strip();
    }
}
