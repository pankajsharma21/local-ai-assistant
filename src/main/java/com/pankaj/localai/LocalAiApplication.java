package com.pankaj.localai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point.
 *
 * Everything this application talks to runs on the same machine:
 *  - the LLM is served locally by Ollama (http://localhost:11434)
 *  - the embedding model runs in-process inside this JVM (ONNX runtime, no network call)
 *  - the vector stores are plain JSON files on disk under ./data/store
 *
 * No request in this codebase ever leaves localhost.
 */
@SpringBootApplication
public class LocalAiApplication {
    public static void main(String[] args) {
        SpringApplication.run(LocalAiApplication.class, args);
    }
}
