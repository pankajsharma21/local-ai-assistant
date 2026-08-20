package com.pankaj.localai.web;

import com.pankaj.localai.assistant.AssistantService;
import com.pankaj.localai.config.AssistantProperties;
import com.pankaj.localai.voice.VoiceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Quick "is everything actually running locally" check — handy to hit first when demoing, since
 * every failure mode in this app traces back to either Ollama not running or nothing ingested yet.
 */
@RestController
public class HealthController {

    private final RestClient restClient;
    private final AssistantProperties props;
    private final VoiceService voiceService;
    private final AssistantService assistantService;

    public HealthController(AssistantProperties props, VoiceService voiceService, AssistantService assistantService) {
        this.props = props;
        this.voiceService = voiceService;
        this.assistantService = assistantService;
        this.restClient = RestClient.create();
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        boolean ollamaUp = pingOllama();
        return Map.of(
                "status", "ok",
                "ollamaReachable", ollamaUp,
                "chatModel", assistantService.getCurrentModel(),
                "voice", voiceService.statusMessage(),
                "webSearch", webSearchStatus()
        );
    }

    private String webSearchStatus() {
        var config = props.getWebSearch();
        if (!config.isEnabled() || config.getTavilyApiKey().isBlank()) {
            return "Live web search disabled — searchWikipedia still works. " +
                    "Set assistant.web-search.enabled=true + a Tavily key to enable searchWeb.";
        }
        return "Live web search enabled (Tavily).";
    }

    private boolean pingOllama() {
        try {
            restClient.get()
                    .uri(props.getOllama().getBaseUrl() + "/api/tags")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
