package com.pankaj.localai.web;

import java.util.List;

public record ModelsResponse(String current, List<ModelInfo> installed) {}
