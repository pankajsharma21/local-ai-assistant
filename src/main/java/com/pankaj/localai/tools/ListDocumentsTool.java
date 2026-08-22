package com.pankaj.localai.tools;

import com.pankaj.localai.rag.EmbeddingStoreManager;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Lets the assistant answer "which documents do I have?" and, crucially, resolve vague references
 * like "this document" or "the file I just uploaded".
 *
 * Why this is needed: uploading a file confirms success in the browser, but that confirmation is
 * rendered client-side and never passes through the model, so the model has no idea an upload
 * happened. Asking "what does this document contain?" straight after an upload therefore got
 * "which document do you mean?" - the assistant genuinely had no way to know. This tool closes that
 * gap by letting it look up what is actually indexed.
 */
@Component
public class ListDocumentsTool {

    private static final Logger log = LoggerFactory.getLogger(ListDocumentsTool.class);

    private final EmbeddingStoreManager storeManager;

    public ListDocumentsTool(EmbeddingStoreManager storeManager) {
        this.storeManager = storeManager;
    }

    @Tool("""
        List the documents the user has ingested, with how much content each holds. Call this
        whenever the user refers to their documents without naming one - "what does this document
        contain", "the file I just uploaded", "what documents do I have", "summarise my document" -
        so you know what actually exists before answering. If exactly one document is listed, that
        is almost certainly the one they mean. Follow up with searchDocs to read its contents.
        """)
    public String listDocuments() {
        Map<String, Integer> docs = storeManager.listDocuments();
        log.info("listDocuments called — {} document(s) indexed", docs.size());
        if (docs.isEmpty()) {
            return "No documents have been ingested yet. Tell the user to add one with the paperclip "
                    + "button in the chat box, or by ingesting a folder/path.";
        }
        String latest = storeManager.lastIngested();
        StringBuilder sb = new StringBuilder();
        if (latest != null) {
            sb.append("MOST RECENTLY ADDED: ").append(latest).append('\n');
            // Include the opening text directly. Relying on the model to chain listDocuments ->
            // searchDocs did not work: mid-size local models call one tool, stop, and ask the user
            // what to do next. Returning content here makes a single call sufficient to answer
            // "what does this document contain?".
            String preview = storeManager.previewOf(latest);
            if (preview != null) {
                sb.append("Opening content of ").append(latest).append(":\n\"\"\"\n")
                  .append(preview).append("\n\"\"\"\n")
                  .append("If the user asked what \"this document\" contains, summarise the above now. ")
                  .append("Use searchDocs only if they want detail beyond this excerpt.\n");
            }
            sb.append('\n');
        }
        sb.append("Ingested documents (").append(docs.size()).append("):\n");
        docs.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> sb.append("  - ").append(e.getKey())
                        .append("  (~").append(e.getValue()).append(" chunks)\n"));
        sb.append("\nUse searchDocs with a query to read the actual contents of any of these.");
        return sb.toString().strip();
    }
}
