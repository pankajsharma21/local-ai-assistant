package com.pankaj.localai.web;

import com.pankaj.localai.assistant.AssistantService;
import com.pankaj.localai.config.AssistantProperties;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Lets the UI's model picker list what's actually pulled in Ollama and switch the live model
 * without an app restart (see AssistantService.switchModel). Response bodies are read as plain
 * Map/List (JDK types both Jackson 2 and Jackson 3 handle identically) rather than a JsonNode -
 * see WikipediaSearchTool's class comment for why a "foreign" Jackson-2 type breaks under Spring
 * Boot 4's Jackson-3-based RestClient auto-conversion.
 */
@RestController
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);

    private final AssistantService assistantService;
    private final AssistantProperties props;
    private final RestClient restClient = RestClient.create();

    public ModelController(AssistantService assistantService, AssistantProperties props) {
        this.assistantService = assistantService;
        this.props = props;
    }

    @GetMapping("/api/models")
    public ModelsResponse listModels() {
        List<ModelInfo> installed = List.of();
        try {
            Map<String, Object> response = restClient.get()
                    .uri(props.getOllama().getBaseUrl() + "/api/tags")
                    .retrieve()
                    .body(Map.class);
            Object rawModels = response == null ? null : response.get("models");
            if (rawModels instanceof List<?> list) {
                installed = list.stream()
                        .filter(Map.class::isInstance)
                        .map(o -> (Map<?, ?>) o)
                        .map(m -> new ModelInfo(String.valueOf(m.get("name")), toLong(m.get("size"))))
                        .toList();
            }
        } catch (Exception e) {
            log.warn("Could not list Ollama models: {}", e.getMessage());
        }
        return new ModelsResponse(assistantService.getCurrentModel(), installed);
    }

    @PostMapping("/api/model")
    public ResponseEntity<Map<String, String>> switchModel(@Valid @RequestBody SwitchModelRequest request) {
        try {
            assistantService.switchModel(request.model());
            return ResponseEntity.ok(Map.of("current", assistantService.getCurrentModel()));
        } catch (Exception e) {
            log.error("Failed to switch model to '{}'", request.model(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
