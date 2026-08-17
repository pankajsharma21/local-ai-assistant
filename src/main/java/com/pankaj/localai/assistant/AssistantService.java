package com.pankaj.localai.assistant;

import com.pankaj.localai.tools.CodeSearchTool;
import com.pankaj.localai.tools.DocSearchTool;
import com.pankaj.localai.tools.FileTools;
import com.pankaj.localai.tools.WebSearchTool;
import com.pankaj.localai.tools.WikipediaSearchTool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Builds the single AiServices-backed Assistant and exposes a plain chat(sessionId, message) call
 * for the REST/voice controllers to use.
 *
 * chatMemoryProvider gives each sessionId its own rolling conversation window, so multiple people
 * (or multiple browser tabs) can talk to the same running assistant without mixing up context.
 */
@Service
public class AssistantService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy");

    private final Assistant assistant;

    public AssistantService(ChatModel chatModel,
                             DocSearchTool docSearchTool,
                             CodeSearchTool codeSearchTool,
                             FileTools fileTools,
                             WikipediaSearchTool wikipediaSearchTool,
                             WebSearchTool webSearchTool) {
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(sessionId -> MessageWindowChatMemory.withMaxMessages(20))
                .tools(docSearchTool, codeSearchTool, fileTools, wikipediaSearchTool, webSearchTool)
                .build();
    }

    public String chat(String sessionId, String message) {
        String today = LocalDate.now().format(DATE_FORMAT);
        return assistant.chat(sessionId, today, message);
    }
}
