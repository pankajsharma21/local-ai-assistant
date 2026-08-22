package com.pankaj.localai.web;

import com.pankaj.localai.assistant.AssistantService;
import com.pankaj.localai.config.AssistantProperties;
import com.pankaj.localai.tools.MarginaliaSearchTool;
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
    private final MarginaliaSearchTool marginalia;

    public HealthController(AssistantProperties props, VoiceService voiceService,
                            AssistantService assistantService, MarginaliaSearchTool marginalia) {
        this.props = props;
        this.voiceService = voiceService;
        this.assistantService = assistantService;
        this.marginalia = marginalia;
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
        boolean tavily = config.isEnabled() && !config.getTavilyApiKey().isBlank();
        if (tavily) {
            return "Live web search enabled (Tavily).";
        }
        if (marginalia.isAvailable()) {
            return "Live web search enabled (Marginalia, no API key needed).";
        }
        return "Disabled — set assistant.web-search.marginalia-enabled=true, or add a Tavily key. "
                + "Current-fact questions still work via Wikidata + Wikipedia.";
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
