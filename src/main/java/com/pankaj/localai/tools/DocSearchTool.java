package com.pankaj.localai.tools;

import com.pankaj.localai.config.AssistantProperties;
import com.pankaj.localai.rag.EmbeddingStoreManager;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The "open book" for the LLM: given a natural-language query, embeds it and does a vector
 * similarity search over whatever has been ingested from assistant.rag.docs-path (PDFs, notes,
 * markdown). The LLM decides on its own, based on the tool description below, when a question
 * needs this vs. when it can answer from general knowledge.
 */
@Component
public class DocSearchTool {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStoreManager storeManager;
    private final AssistantProperties props;

    public DocSearchTool(EmbeddingModel embeddingModel, EmbeddingStoreManager storeManager, AssistantProperties props) {
        this.embeddingModel = embeddingModel;
        this.storeManager = storeManager;
        this.props = props;
    }

    @Tool("""
        Search the user's own documents (PDFs, notes, markdown files that were ingested from their
        local documents folder) for information relevant to the given query. Use this whenever the
        user asks something that could be answered from their personal/uploaded documents rather
        than general world knowledge - e.g. "what does my contract say about X", "summarize chapter 2",
        "what did I write about Y". Returns the most relevant excerpts along with their source file names.
        """)
    public String searchDocs(String query) {
        List<EmbeddingMatch<TextSegment>> matches = search(query);
        if (matches.isEmpty()) {
            return "No relevant content found in the user's documents for this query. " +
                    "The docs store may be empty — tell the user to add files to the docs folder and run ingestion.";
        }
        return format(matches);
    }

    private List<EmbeddingMatch<TextSegment>> search(String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(props.getRag().getMaxResults())
                .minScore(props.getRag().getMinScore())
                .build();
        EmbeddingSearchResult<TextSegment> result = storeManager.docsStore().search(request);
        return result.matches();
    }

    private String format(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder sb = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : matches) {
            String file = match.embedded().metadata().getString("file_name");
            sb.append("[source: ").append(file != null ? file : "unknown")
              .append(", relevance: ").append(String.format("%.2f", match.score())).append("]\n")
              .append(match.embedded().text())
              .append("\n\n");
        }
        return sb.toString().strip();
    }
}
