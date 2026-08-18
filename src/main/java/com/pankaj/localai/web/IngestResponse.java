package com.pankaj.localai.web;

public record IngestResponse(String source, int filesIngested, String message) {
    public IngestResponse(String source, int filesIngested) {
        this(source, filesIngested, null);
    }
}
