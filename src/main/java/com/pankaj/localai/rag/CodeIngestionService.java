package com.pankaj.localai.rag;

import com.pankaj.localai.config.AssistantProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.TextDocumentParser;
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
 * Ingests source code from assistant.rag.code-path into the "code" vector store, so CodeSearchTool
 * can retrieve relevant snippets later. Same pipeline shape as DocumentIngestionService — chunk,
 * embed, store — just pointed at a different folder with different file extensions and a
 * separate (namespaced) vector store, which is the whole point of "it's the same thing, different data".
 */
@Service
public class CodeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CodeIngestionService.class);

    private static final Set<String> CODE_EXT = Set.of(
            "java", "py", "js", "ts", "jsx", "tsx", "go", "rb", "rs", "kt", "c", "cpp", "h", "hpp",
            "cs", "php", "sql", "sh", "yml", "yaml", "xml", "properties", "md", "json"
    );

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "target", "build", "dist", "out", "node_modules", ".idea", ".vscode",
            ".codesight", "venv", ".venv", "__pycache__", "data"
    );

    private final AssistantProperties props;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStoreManager storeManager;

    public CodeIngestionService(AssistantProperties props, EmbeddingModel embeddingModel, EmbeddingStoreManager storeManager) {
        this.props = props;
        this.embeddingModel = embeddingModel;
        this.storeManager = storeManager;
    }

    public synchronized int ingestAll() {
        Path root = Path.of(props.getRag().getCodePath());
        if (!Files.isDirectory(root)) {
            log.warn("Code path {} does not exist — nothing to ingest", root);
            return 0;
        }

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(props.getRag().getChunkSize(), props.getRag().getChunkOverlap()))
                .embeddingModel(embeddingModel)
                .embeddingStore(storeManager.codeStore())
                .build();

        int[] count = {0};
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isSupported)
                 .filter(path -> isNotIgnored(root.relativize(path)))
                 .forEach(path -> {
                     try {
                         Document document = loadDocument(path, root);
                         if (!document.text().isBlank()) {
                             ingestor.ingest(document);
                             count[0]++;
                             log.info("Ingested code file: {}", root.relativize(path));
                         }
                     } catch (Exception e) {
                         log.error("Failed to ingest {}: {}", path, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            throw new RuntimeException("Failed walking code directory " + root, e);
        }

        storeManager.saveCodeStore();
        log.info("Code ingestion complete: {} file(s) processed", count[0]);
        return count[0];
    }

    private boolean isSupported(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        return CODE_EXT.contains(ext);
    }

    private boolean isNotIgnored(Path path) {
        for (Path part : path) {
            if (IGNORED_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private Document loadDocument(Path path, Path root) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Document document = new TextDocumentParser().parse(in);
            document.metadata().put("file_name", root.relativize(path).toString().replace(FileSystems.getDefault().getSeparator(), "/"));
            document.metadata().put("source", "code");
            return document;
        }
    }
}
