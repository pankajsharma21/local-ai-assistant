package com.pankaj.localai.tools;

import com.pankaj.localai.config.AssistantProperties;
import com.pankaj.localai.rag.EmbeddingStoreManager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Same retrieval mechanism as DocSearchTool, pointed at the "code" vector store instead of "docs".
 * This is the concrete proof that "chat / doc-RAG / code-assistant" really are the same engine
 * with a different data source plugged in — this class is nearly line-for-line identical to
 * DocSearchTool.
 */
@Component
public class CodeSearchTool {

    private static final Logger log = LoggerFactory.getLogger(CodeSearchTool.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStoreManager storeManager;
    private final AssistantProperties props;

    public CodeSearchTool(EmbeddingModel embeddingModel, EmbeddingStoreManager storeManager, AssistantProperties props) {
        this.embeddingModel = embeddingModel;
        this.storeManager = storeManager;
        this.props = props;
    }

    @Tool("""
        Search the ingested source code (from the configured code folder) for functions, classes or
        logic relevant to the given query. Use this when the user asks about "this codebase", how
        something is implemented, where a function lives, or wants an explanation of existing code.
        Returns the most relevant code snippets along with their file paths. Combine with readFile
        if you need the full file after finding it here.
        """)
    public String searchCode(String query) {
        List<EmbeddingMatch<TextSegment>> matches = search(query);
        if (matches.isEmpty()) {
            return "No relevant code found for this query. The code store may be empty — " +
                    "tell the user to point assistant.rag.code-path at their repo and run code ingestion.";
        }
        return format(matches);
    }

    /**
     * Same approach as DocSearchTool: fetch candidates without the score cutoff, apply it here, and
     * log when everything was rejected. A silent minScore makes "it can't find code that is clearly
     * in the repo" impossible to diagnose from the outside.
     */
    private List<EmbeddingMatch<TextSegment>> search(String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        double minScore = props.getRag().getMinScore();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(props.getRag().getMaxResults())
                .minScore(0.0)
                .build();
        List<EmbeddingMatch<TextSegment>> all = storeManager.codeStore().search(request).matches();

        List<EmbeddingMatch<TextSegment>> kept = all.stream().filter(m -> m.score() >= minScore).toList();
        if (kept.isEmpty() && !all.isEmpty()) {
            log.info("Code search '{}': {} candidate(s) but all below min-score {} (best was {}). "
                            + "Lower assistant.rag.min-score if this code should have matched.",
                    query, all.size(), minScore, String.format("%.2f", all.get(0).score()));
        } else {
            log.debug("Code search '{}': kept {}/{} candidates at min-score {}",
                    query, kept.size(), all.size(), minScore);
        }
        return kept;
    }

    private String format(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : matches) {
            String file = match.embedded().metadata().getString("file_name");
            sb.append("[file: ").append(file != null ? file : "unknown")
              .append(", relevance: ").append(String.format("%.2f", match.score())).append("]\n")
              .append(match.embedded().text())
              .append("\n\n");
        }
        return sb.toString().strip();
    }
}
