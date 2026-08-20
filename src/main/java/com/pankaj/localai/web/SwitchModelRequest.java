package com.pankaj.localai.web;

import jakarta.validation.constraints.NotBlank;

public record SwitchModelRequest(@NotBlank String model) {}
