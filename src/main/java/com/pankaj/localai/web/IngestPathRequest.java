package com.pankaj.localai.web;

import jakarta.validation.constraints.NotBlank;

/** A filesystem path (file or directory) typed or pasted by the user, from anywhere on disk. */
public record IngestPathRequest(@NotBlank String path) {}
