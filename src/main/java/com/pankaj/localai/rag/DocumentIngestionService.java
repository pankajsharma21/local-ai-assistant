package com.pankaj.localai.rag;

import com.pankaj.localai.config.AssistantProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Ingests your personal documents (PDFs, notes, markdown) into the "docs" vector store, so
 * DocSearchTool can retrieve them later. This is step 1-3 of the RAG pipeline: chunk -> embed ->
 * store. Retrieval + generation happens later, at question time, inside DocSearchTool.
 *
 * Three entry points, all sharing the same chunk/embed/store pipeline:
 *  - ingestAll()         bulk re-scan of the configured assistant.rag.docs-path folder
 *  - ingestPath(path)    ingest an arbitrary file OR directory from anywhere on disk
 *  - ingestUploadedFile  ingest bytes handed to us directly (browser file-picker upload)
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final Set<String> PDF_EXT = Set.of("pdf");
    private static final Set<String> TEXT_EXT = Set.of("txt", "md", "markdown", "csv", "json", "yml", "yaml");

    private final AssistantProperties props;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStoreManager storeManager;

    public DocumentIngestionService(AssistantProperties props, EmbeddingModel embeddingModel, EmbeddingStoreManager storeManager) {
        this.props = props;
        this.embeddingModel = embeddingModel;
        this.storeManager = storeManager;
    }

    /** Re-scans the configured docs folder and (re-)ingests every supported file found. */
    public synchronized int ingestAll() {
        Path root = Path.of(props.getRag().getDocsPath());
        if (!Files.isDirectory(root)) {
            log.warn("Docs path {} does not exist — nothing to ingest", root);
            return 0;
        }
        return ingestPath(root);
    }

    /**
     * Ingests an arbitrary file or directory from anywhere on the local filesystem — this is what
     * powers "give me a path" ingestion from the UI. Not sandboxed to the project directory on
     * purpose: this is a human typing a path into their own locally-running app, not an LLM tool
     * call (compare FileTools, which IS sandboxed, because that's driven by model output).
     */
    public synchronized int ingestPath(Path path) {
        if (Files.isRegularFile(path)) {
            boolean ok = ingestSingleFile(path, path.getParent() == null ? path : path.getParent(), fileLabel(path));
            if (ok) {
                storeManager.saveDocsStore();
            }
            return ok ? 1 : 0;
        }
        if (!Files.isDirectory(path)) {
            log.warn("Path {} does not exist or is not readable", path);
            return 0;
        }

        int[] count = {0};
        try (Stream<Path> paths = Files.walk(path)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isSupported)
                 .forEach(p -> {
                     if (ingestSingleFile(p, path, relativeLabel(p, path))) {
                         count[0]++;
                     }
                 });
        } catch (IOException e) {
            throw new RuntimeException("Failed walking directory " + path, e);
        }
        storeManager.saveDocsStore();
        log.info("Document ingestion complete: {} file(s) processed from {}", count[0], path);
        return count[0];
    }

    /**
     * Ingests file content handed to us directly (e.g. a browser upload) without it needing to
     * exist as a file on disk under the app's own folders first.
     */
    public synchronized boolean ingestUploadedFile(InputStream content, String originalFileName) {
        String ext = extensionOf(Path.of(originalFileName));
        if (!PDF_EXT.contains(ext) && !TEXT_EXT.contains(ext)) {
            log.warn("Unsupported file type for upload: {}", originalFileName);
            return false;
        }
        try {
            DocumentParser parser = PDF_EXT.contains(ext) ? new ApachePdfBoxDocumentParser() : new TextDocumentParser();
            Document document = parser.parse(content);
            document.metadata().put("file_name", originalFileName);
            document.metadata().put("source", "docs");
            ingestor().ingest(document);
            storeManager.saveDocsStore();
            log.info("Ingested uploaded file: {}", originalFileName);
            return true;
        } catch (Exception e) {
            log.error("Failed to ingest uploaded file {}: {}", originalFileName, e.getMessage());
            return false;
        }
    }

    private boolean ingestSingleFile(Path path, Path labelRoot, String label) {
        if (!isSupported(path)) {
            return false;
        }
        try {
            Document document = loadDocument(path, label);
            ingestor().ingest(document);
            log.info("Ingested doc: {}", label);
            return true;
        } catch (Exception e) {
            log.error("Failed to ingest {}: {}", path, e.getMessage());
            return false;
        }
    }

    private EmbeddingStoreIngestor ingestor() {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(props.getRag().getChunkSize(), props.getRag().getChunkOverlap()))
                .embeddingModel(embeddingModel)
                .embeddingStore(storeManager.docsStore())
                .build();
    }

    private boolean isSupported(Path path) {
        String ext = extensionOf(path);
        return PDF_EXT.contains(ext) || TEXT_EXT.contains(ext);
    }

    private String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private String fileLabel(Path path) {
        return path.getFileName().toString();
    }

    private String relativeLabel(Path path, Path root) {
        return root.relativize(path).toString().replace(FileSystems.getDefault().getSeparator(), "/");
    }

    private Document loadDocument(Path path, String label) throws IOException {
        DocumentParser parser = PDF_EXT.contains(extensionOf(path))
                ? new ApachePdfBoxDocumentParser()
                : new TextDocumentParser();

        try (InputStream in = Files.newInputStream(path)) {
            Document document = parser.parse(in);
            document.metadata().put("file_name", label);
            document.metadata().put("source", "docs");
            return document;
        }
    }
}
