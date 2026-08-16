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
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Ingests your personal documents (PDFs, notes, markdown) from assistant.rag.docs-path into the
 * "docs" vector store, so DocSearchTool can retrieve them later. This is step 1-3 of the RAG
 * pipeline: chunk -> embed -> store. Retrieval + generation happens later, at question time,
 * inside DocSearchTool.
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

    /**
     * Re-scans the docs folder and (re-)ingests every supported file found.
     * Returns how many files were processed.
     */
    public synchronized int ingestAll() {
        Path root = Path.of(props.getRag().getDocsPath());
        if (!Files.isDirectory(root)) {
            log.warn("Docs path {} does not exist — nothing to ingest", root);
            return 0;
        }

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(props.getRag().getChunkSize(), props.getRag().getChunkOverlap()))
                .embeddingModel(embeddingModel)
                .embeddingStore(storeManager.docsStore())
                .build();

        int[] count = {0};
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isSupported)
                 .forEach(path -> {
                     try {
                         Document document = loadDocument(path, root);
                         ingestor.ingest(document);
                         count[0]++;
                         log.info("Ingested doc: {}", root.relativize(path));
                     } catch (Exception e) {
                         log.error("Failed to ingest {}: {}", path, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            throw new RuntimeException("Failed walking docs directory " + root, e);
        }

        storeManager.saveDocsStore();
        log.info("Document ingestion complete: {} file(s) processed", count[0]);
        return count[0];
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

    private Document loadDocument(Path path, Path root) throws IOException {
        DocumentParser parser = PDF_EXT.contains(extensionOf(path))
                ? new ApachePdfBoxDocumentParser()
                : new TextDocumentParser();

        try (InputStream in = Files.newInputStream(path)) {
            Document document = parser.parse(in);
            document.metadata().put("file_name", root.relativize(path).toString().replace(FileSystems.getDefault().getSeparator(), "/"));
            document.metadata().put("source", "docs");
            return document;
        }
    }
}
