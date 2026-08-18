package com.pankaj.localai.web;

import com.pankaj.localai.rag.CodeIngestionService;
import com.pankaj.localai.rag.DocumentIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Triggers the "chunk -> embed -> store" pipeline for docs and code (see DocumentIngestionService /
 * CodeIngestionService). Three ways to bring documents in:
 *  - /api/ingest/docs    bulk re-scan of the configured assistant.rag.docs-path folder
 *  - /api/ingest/path    ingest a file or folder from anywhere on disk, given its path
 *  - /api/ingest/upload  ingest a file the browser's native file picker handed us directly
 */
@RestController
public class IngestController {

    private final DocumentIngestionService documentIngestionService;
    private final CodeIngestionService codeIngestionService;

    public IngestController(DocumentIngestionService documentIngestionService, CodeIngestionService codeIngestionService) {
        this.documentIngestionService = documentIngestionService;
        this.codeIngestionService = codeIngestionService;
    }

    @PostMapping("/api/ingest/docs")
    public IngestResponse ingestDocs() {
        return new IngestResponse("docs", documentIngestionService.ingestAll());
    }

    @PostMapping("/api/ingest/code")
    public IngestResponse ingestCode() {
        return new IngestResponse("code", codeIngestionService.ingestAll());
    }

    /** Ingest a file or directory from anywhere on the local filesystem, given its path. */
    @PostMapping("/api/ingest/path")
    public ResponseEntity<IngestResponse> ingestPath(@Valid @RequestBody IngestPathRequest request) {
        Path resolved = expandTilde(request.path()).toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            return ResponseEntity.badRequest().body(new IngestResponse("docs", 0, "Path does not exist: " + resolved));
        }
        int count = documentIngestionService.ingestPath(resolved);
        if (count == 0) {
            return ResponseEntity.badRequest().body(new IngestResponse("docs", 0,
                    "No supported files found at " + resolved + " (supported: .pdf, .txt, .md, .markdown, .csv, .json, .yml, .yaml)"));
        }
        return ResponseEntity.ok(new IngestResponse("docs", count, "Ingested from " + resolved));
    }

    /** Ingest a file uploaded directly through the browser's file picker (like an AI chat attachment). */
    @PostMapping("/api/ingest/upload")
    public ResponseEntity<IngestResponse> ingestUpload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(new IngestResponse("docs", 0, "Empty file"));
        }
        try {
            boolean ok = documentIngestionService.ingestUploadedFile(file.getInputStream(), file.getOriginalFilename());
            if (!ok) {
                return ResponseEntity.badRequest().body(new IngestResponse("docs", 0,
                        "Unsupported file type or failed to parse: " + file.getOriginalFilename()));
            }
            return ResponseEntity.ok(new IngestResponse("docs", 1, "Ingested " + file.getOriginalFilename()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(new IngestResponse("docs", 0, "Upload failed: " + e.getMessage()));
        }
    }

    private Path expandTilde(String rawPath) {
        if (rawPath.startsWith("~")) {
            return Path.of(System.getProperty("user.home"), rawPath.substring(1));
        }
        return Path.of(rawPath);
    }
}
