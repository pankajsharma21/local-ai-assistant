package com.pankaj.localai.web;

import com.pankaj.localai.rag.CodeIngestionService;
import com.pankaj.localai.rag.DocumentIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Triggers the "chunk -> embed -> store" pipeline for docs and code (see DocumentIngestionService /
 * CodeIngestionService). Call these after dropping new files into ./data/docs or pointing
 * assistant.rag.code-path at a repo, then ask questions about them via /api/chat.
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
}
