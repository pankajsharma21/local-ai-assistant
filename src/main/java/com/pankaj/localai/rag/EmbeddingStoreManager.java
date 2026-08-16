package com.pankaj.localai.rag;

import com.pankaj.localai.config.AssistantProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

/**
 * Holds the two vector stores ("docs" and "code") and persists them to plain JSON files under
 * assistant.rag.store-path, so ingested embeddings survive an app restart without needing a
 * separate database server (Chroma/Postgres/etc). Good enough for a single-user local assistant;
 * swap for a real vector DB if this ever needs to scale past a few thousand chunks.
 */
@Component
public class EmbeddingStoreManager {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStoreManager.class);

    private final Path docsStoreFile;
    private final Path codeStoreFile;

    private InMemoryEmbeddingStore<TextSegment> docsStore;
    private InMemoryEmbeddingStore<TextSegment> codeStore;

    public EmbeddingStoreManager(AssistantProperties props) {
        Path storeDir = Path.of(props.getRag().getStorePath());
        this.docsStoreFile = storeDir.resolve("docs-store.json");
        this.codeStoreFile = storeDir.resolve("code-store.json");
    }

    @PostConstruct
    void init() {
        docsStoreFile.getParent().toFile().mkdirs();
        docsStore = load(docsStoreFile, "docs");
        codeStore = load(codeStoreFile, "code");
    }

    private InMemoryEmbeddingStore<TextSegment> load(Path file, String label) {
        File f = file.toFile();
        if (f.exists()) {
            log.info("Loading persisted {} embedding store from {}", label, file);
            return InMemoryEmbeddingStore.fromFile(file);
        }
        log.info("No persisted {} embedding store found at {} — starting empty (run an ingest to populate it)", label, file);
        return new InMemoryEmbeddingStore<>();
    }

    public EmbeddingStore<TextSegment> docsStore() {
        return docsStore;
    }

    public EmbeddingStore<TextSegment> codeStore() {
        return codeStore;
    }

    public synchronized void saveDocsStore() {
        docsStore.serializeToFile(docsStoreFile);
        log.info("Persisted docs embedding store to {}", docsStoreFile);
    }

    public synchronized void saveCodeStore() {
        codeStore.serializeToFile(codeStoreFile);
        log.info("Persisted code embedding store to {}", codeStoreFile);
    }
}
