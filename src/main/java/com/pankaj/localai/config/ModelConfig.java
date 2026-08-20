package com.pankaj.localai.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the EmbeddingModel - the ChatModel ("the brain") is built dynamically instead, by
 * AssistantService, because it needs to be rebuildable at runtime for live model switching (see
 * AssistantService.switchModel). Swap assistant.ollama.chat-model in application.yml for the
 * startup default, or use the model picker in the UI / POST /api/model to switch without restarting.
 *
 * EmbeddingModel runs IN-PROCESS inside the JVM (ONNX Runtime, model bundled in the jar) - it never
 * calls Ollama or the network, which keeps document/code ingestion fast and removes one moving part.
 */
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class ModelConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }
}
