package com.pankaj.localai.web;

import com.pankaj.localai.assistant.AssistantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint that drives chat, doc-RAG, and code-assistant alike — the model itself decides
 * (via tool-calling) which capability a given message needs. Voice sits on top of this same
 * endpoint; see VoiceController.
 */
@RestController
public class ChatController {

    private final AssistantService assistantService;

    public ChatController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/api/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String reply = assistantService.chat(request.sessionId(), request.message());
        return new ChatResponse(reply);
    }
}
