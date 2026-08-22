package com.pankaj.localai.rag;

import com.pankaj.localai.config.AssistantProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Names of ingested documents with their chunk counts. Kept separately because
     * InMemoryEmbeddingStore has no API to enumerate what's in it - only search() - so without this
     * there is no way to answer "which documents do I have?" short of re-parsing the whole store
     * JSON on every request.
     */
    private final Map<String, Integer> docNames = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private Path docIndexFile;

    /**
     * The document added most recently. Single-valued rather than per-session on purpose: this is a
     * single-user local assistant, and it exists so "what does THIS document contain?" right after an
     * upload can be resolved without the user having to name the file again.
     */
    private volatile String lastIngested;

    /** First slice of each document's text, so "what does this document contain?" can be answered
     *  from a single tool call instead of relying on the model to chain list -> search. */
    private final Map<String, String> docPreviews = new ConcurrentHashMap<>();

    public EmbeddingStoreManager(AssistantProperties props) {
        Path storeDir = Path.of(props.getRag().getStorePath());
        this.docsStoreFile = storeDir.resolve("docs-store.json");
        this.codeStoreFile = storeDir.resolve("code-store.json");
        this.docIndexFile = storeDir.resolve("docs-index.json");
    }

    @PostConstruct
    void init() {
        docsStoreFile.getParent().toFile().mkdirs();
        docsStore = load(docsStoreFile, "docs");
        codeStore = load(codeStoreFile, "code");
        loadDocIndex();
    }

    /**
     * Restores the document registry. Bootstraps from the embedding store itself the first time
     * (or after the index is deleted) so documents ingested before this registry existed still
     * show up, instead of the assistant claiming it has no documents when it plainly does.
     */
    private void loadDocIndex() {
        try {
            if (Files.exists(docIndexFile)) {
                JsonNode root = objectMapper.readTree(Files.readString(docIndexFile));
                root.fields().forEachRemaining(e -> docNames.put(e.getKey(), e.getValue().asInt()));
                log.info("Loaded document index: {} document(s)", docNames.size());
                return;
            }
            if (Files.exists(docsStoreFile)) {
                log.info("No document index yet — rebuilding it from {}", docsStoreFile);
                rebuildDocIndexFromStore();
                saveDocIndex();
            }
        } catch (Exception e) {
            log.warn("Could not load document index: {}", e.getMessage());
        }
    }

    /** One-off scan of the persisted store JSON to recover which documents it contains. */
    private void rebuildDocIndexFromStore() throws Exception {
        JsonNode root = objectMapper.readTree(Files.readString(docsStoreFile));
        JsonNode entries = root.path("entries");
        if (!entries.isArray()) {
            return;
        }
        for (JsonNode entry : entries) {
            JsonNode meta = entry.path("embedded").path("metadata").path("metadata");
            String name = meta.path("file_name").asText(null);
            if (name != null && !name.isBlank()) {
                docNames.merge(name, 1, Integer::sum);
            }
        }
        log.info("Rebuilt document index from store: {} document(s)", docNames.size());
    }

    private synchronized void saveDocIndex() {
        try {
            objectMapper.writeValue(docIndexFile.toFile(), new LinkedHashMap<>(docNames));
        } catch (Exception e) {
            log.warn("Could not persist document index: {}", e.getMessage());
        }
    }

    /** Records that a document was ingested, so it can be listed later. */
    public void recordDocument(String fileName, int chunks) {
        recordDocument(fileName, chunks, null);
    }

    public void recordDocument(String fileName, int chunks, String preview) {
        if (fileName != null && !fileName.isBlank()) {
            docNames.merge(fileName, chunks, Integer::sum);
            lastIngested = fileName;
            if (preview != null && !preview.isBlank()) {
                docPreviews.put(fileName, preview);
            }
        }
    }

    /** Opening text of a document, if we captured it at ingest time. */
    public String previewOf(String fileName) {
        return fileName == null ? null : docPreviews.get(fileName);
    }

    /** Most recently ingested document name, or null if nothing has been added this run. */
    public String lastIngested() {
        return lastIngested;
    }

    /** Ingested document names with their chunk counts. */
    public Map<String, Integer> listDocuments() {
        return new LinkedHashMap<>(docNames);
    }

    public synchronized void clearDocuments() {
        docNames.clear();
        saveDocIndex();
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
        saveDocIndex();
        log.info("Persisted docs embedding store to {} ({} document(s) indexed)", docsStoreFile, docNames.size());
    }

    public synchronized void saveCodeStore() {
        codeStore.serializeToFile(codeStoreFile);
        log.info("Persisted code embedding store to {}", codeStoreFile);
    }
}
