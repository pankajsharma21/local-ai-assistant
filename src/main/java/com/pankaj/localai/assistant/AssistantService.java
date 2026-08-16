package com.pankaj.localai.assistant;

import com.pankaj.localai.tools.CodeSearchTool;
import com.pankaj.localai.tools.DocSearchTool;
import com.pankaj.localai.tools.FileTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Service;

/**
 * Builds the single AiServices-backed Assistant and exposes a plain chat(sessionId, message) call
 * for the REST/voice controllers to use.
 *
 * chatMemoryProvider gives each sessionId its own rolling conversation window, so multiple people
 * (or multiple browser tabs) can talk to the same running assistant without mixing up context.
 */
@Service
public class AssistantService {

    private final Assistant assistant;

    public AssistantService(ChatModel chatModel, DocSearchTool docSearchTool, CodeSearchTool codeSearchTool, FileTools fileTools) {
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(sessionId -> MessageWindowChatMemory.withMaxMessages(20))
                .tools(docSearchTool, codeSearchTool, fileTools)
                .build();
    }

    public String chat(String sessionId, String message) {
        return assistant.chat(sessionId, message);
    }
}
