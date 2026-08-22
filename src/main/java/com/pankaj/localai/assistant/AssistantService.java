package com.pankaj.localai.assistant;

import com.pankaj.localai.config.AssistantProperties;
import com.pankaj.localai.tools.CodeSearchTool;
import com.pankaj.localai.tools.DocSearchTool;
import com.pankaj.localai.tools.FileTools;
import com.pankaj.localai.tools.ListDocumentsTool;
import com.pankaj.localai.tools.WeatherTool;
import com.pankaj.localai.tools.WebSearchTool;
import com.pankaj.localai.tools.WikidataSearchTool;
import com.pankaj.localai.tools.WikipediaSearchTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy");

    /**
     * A JSON object carrying a tool-call shape: {"name": ..., "parameters"|"arguments": {...}}.
     * The name value is deliberately loose (quoted string, null, whatever) - observed variants
     * include both {"name": "listDocuments", ...} and {"name": null, ...}. The distinguishing
     * feature is the parameters/arguments key, which ordinary JSON in an answer will not have.
     */
    private static final Pattern TOOL_CALL_ARTIFACT = Pattern.compile(
            "(?:```(?:json)?\\s*)?"
                    + "\\{\\s*\"name\"\\s*:\\s*[^,{}]+,\\s*"
                    + "\"(?:parameters|arguments)\"\\s*:\\s*\\{[^}]*\\}\\s*\\}"
                    + "(?:\\s*```)?",
            Pattern.DOTALL);

    private final AssistantProperties props;
    private final DocSearchTool docSearchTool;
    private final CodeSearchTool codeSearchTool;
    private final FileTools fileTools;
    private final ListDocumentsTool listDocumentsTool;
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
                             ListDocumentsTool listDocumentsTool,
                             WikidataSearchTool wikidataSearchTool,
                             WikipediaSearchTool wikipediaSearchTool,
                             WebSearchTool webSearchTool,
                             WeatherTool weatherTool) {
        this.props = props;
        this.docSearchTool = docSearchTool;
        this.codeSearchTool = codeSearchTool;
        this.fileTools = fileTools;
        this.listDocumentsTool = listDocumentsTool;
        this.wikidataSearchTool = wikidataSearchTool;
        this.wikipediaSearchTool = wikipediaSearchTool;
        this.webSearchTool = webSearchTool;
        this.weatherTool = weatherTool;
        switchModel(props.getOllama().getChatModel());
    }

    public String chat(String sessionId, String message) {
        String today = LocalDate.now().format(DATE_FORMAT);
        String reply = assistant.chat(sessionId, today, message);

        String cleaned = stripToolCallArtifacts(reply);
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        // The whole reply was a leaked tool-call blob and nothing else. Ask once more, explicitly.
        log.warn("Model emitted only a tool-call artifact for '{}': {}", message, reply.strip());
        String retry = assistant.chat(sessionId, today,
                message + "\n\n(Reply in plain language. Do not output JSON or a function call.)");
        String retryCleaned = stripToolCallArtifacts(retry);
        return retryCleaned.isBlank() ? "Sorry — I garbled that. Could you ask again?" : retryCleaned;
    }

    /**
     * Removes tool-call JSON that the model printed as ordinary text instead of actually invoking.
     *
     * Smaller models do this regularly: asked "hi", llama3.2 replied with
     * {"name": "listDocuments", "parameters": {}} as visible prose. It is not a real tool call - the
     * tool never runs - so it reaches the user as raw JSON. Prompt instructions telling it not to
     * emit function calls for trivial input did not stop it, so this is filtered in code instead.
     */
    private String stripToolCallArtifacts(String reply) {
        if (reply == null) {
            return "";
        }
        String cleaned = TOOL_CALL_ARTIFACT.matcher(reply).replaceAll("");
        // Leftover scaffolding the model wraps around such blobs, now pointing at nothing.
        cleaned = cleaned.replaceAll("(?i)\\b(since|as)\\b[^.]*\\bfunction call\\b[^.]*[.:]", "");
        cleaned = cleaned.replaceAll("(?i)I\\s*(?:'ll|will)\\s*provide a general JSON response\\s*[.:]?", "");
        cleaned = cleaned.replaceAll("```(?:json)?\\s*```", "");
        return cleaned.replaceAll("\\n{3,}", "\n\n").strip();
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
                .tools(docSearchTool, codeSearchTool, fileTools, listDocumentsTool, wikidataSearchTool,
                        wikipediaSearchTool, webSearchTool, weatherTool)
                .build();
        this.currentModel = modelName;
    }
}
