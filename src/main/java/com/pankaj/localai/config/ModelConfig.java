package com.pankaj.localai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the two models the whole app is built on:
 *
 *  1. ChatModel      - the actual LLM ("the brain"), served locally by Ollama.
 *                      Swap assistant.ollama.chat-model in application.yml to try a different model
 *                      (llama3.2, qwen2.5:7b, llama3.1:8b, mistral, ...) - no code change needed.
 *
 *  2. EmbeddingModel - turns text into vectors for RAG. This one runs IN-PROCESS inside the JVM
 *                      (ONNX Runtime, model bundled in the jar) - it never calls Ollama or the network,
 *                      which keeps document/code ingestion fast and removes one moving part.
 */
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class ModelConfig {

    @Bean
    public ChatModel chatModel(AssistantProperties props) {
        return OllamaChatModel.builder()
                .baseUrl(props.getOllama().getBaseUrl())
                .modelName(props.getOllama().getChatModel())
                .temperature(props.getOllama().getTemperature())
                .timeout(Duration.ofSeconds(props.getOllama().getTimeoutSeconds()))
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }
}
