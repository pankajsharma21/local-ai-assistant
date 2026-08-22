package com.pankaj.localai.assistant;

import com.pankaj.localai.config.AssistantProperties;
import com.pankaj.localai.tools.CodeSearchTool;
import com.pankaj.localai.tools.DocSearchTool;
import com.pankaj.localai.tools.FileTools;
import com.pankaj.localai.tools.WeatherTool;
import com.pankaj.localai.tools.WebSearchTool;
import com.pankaj.localai.tools.WikidataSearchTool;
import com.pankaj.localai.tools.WikipediaSearchTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds the AiServices-backed Assistant and exposes a plain chat(sessionId, message) call for the
 * REST/voice controllers to use. Also owns live model-switching (see switchModel) so the UI's model
 * picker doesn't need an app restart.
 *
 * chatMemoryProvider gives each sessionId its own rolling conversation window, so multiple people
 * (or multiple browser tabs) can talk to the same running assistant without mixing up context.
 *
 * Model switching rebuilds the LangChain4j AiServices proxy (its internals bake in the chosen
 * ChatModel at build time, so there's no "just swap the model" call) — but conversation memory is
 * kept in OUR OWN map, handed to every rebuilt instance via the same chatMemoryProvider lambda, so
 * switching models mid-conversation does not lose chat history.
 */
@Service
public class AssistantService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy");

    private final AssistantProperties props;
    private final DocSearchTool docSearchTool;
    private final CodeSearchTool codeSearchTool;
    private final FileTools fileTools;
    private final WikidataSearchTool wikidataSearchTool;
    private final WikipediaSearchTool wikipediaSearchTool;
    private final WebSearchTool webSearchTool;
    private final WeatherTool weatherTool;

    private final Map<Object, ChatMemory> memories = new ConcurrentHashMap<>();

    private volatile Assistant assistant;
    private volatile String currentModel;

    public AssistantService(AssistantProperties props,
                             DocSearchTool docSearchTool,
                             CodeSearchTool codeSearchTool,
                             FileTools fileTools,
                             WikidataSearchTool wikidataSearchTool,
                             WikipediaSearchTool wikipediaSearchTool,
                             WebSearchTool webSearchTool,
                             WeatherTool weatherTool) {
        this.props = props;
        this.docSearchTool = docSearchTool;
        this.codeSearchTool = codeSearchTool;
        this.fileTools = fileTools;
        this.wikidataSearchTool = wikidataSearchTool;
        this.wikipediaSearchTool = wikipediaSearchTool;
        this.webSearchTool = webSearchTool;
        this.weatherTool = weatherTool;
        switchModel(props.getOllama().getChatModel());
    }

    public String chat(String sessionId, String message) {
        String today = LocalDate.now().format(DATE_FORMAT);
        return assistant.chat(sessionId, today, message);
    }

    public String getCurrentModel() {
        return currentModel;
    }

    /** Rebuilds the assistant against a different Ollama model. Existing chat memory carries over. */
    public synchronized void switchModel(String modelName) {
        ChatModel chatModel = OllamaChatModel.builder()
                .baseUrl(props.getOllama().getBaseUrl())
                .modelName(modelName)
                .temperature(props.getOllama().getTemperature())
                .timeout(Duration.ofSeconds(props.getOllama().getTimeoutSeconds()))
                .build();

        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(sessionId -> memories.computeIfAbsent(sessionId, id -> MessageWindowChatMemory.withMaxMessages(20)))
                .tools(docSearchTool, codeSearchTool, fileTools, wikidataSearchTool, wikipediaSearchTool,
                        webSearchTool, weatherTool)
                .build();
        this.currentModel = modelName;
    }
}
