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
 * The "open book" for the LLM: given a natural-language query, embeds it and does a vector
 * similarity search over whatever has been ingested from assistant.rag.docs-path (PDFs, notes,
 * markdown). The LLM decides on its own, based on the tool description below, when a question
 * needs this vs. when it can answer from general knowledge.
 */
@Component
public class DocSearchTool {

    private static final Logger log = LoggerFactory.getLogger(DocSearchTool.class);

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

    /**
     * Retrieves candidates WITHOUT the score cutoff, then applies the cutoff ourselves so we can log
     * what got rejected. A silently-applied minScore is genuinely hard to debug: the user asks about
     * something that is definitely in their documents, every chunk scores just under the threshold,
     * and the tool reports "nothing found" with no hint that near-misses existed. Logging the best
     * rejected score turns that into an obvious signal that the threshold needs lowering.
     */
    private List<EmbeddingMatch<TextSegment>> search(String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        double minScore = props.getRag().getMinScore();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(props.getRag().getMaxResults())
                .minScore(0.0)
                .build();
        List<EmbeddingMatch<TextSegment>> all = storeManager.docsStore().search(request).matches();

        List<EmbeddingMatch<TextSegment>> kept = all.stream().filter(m -> m.score() >= minScore).toList();
        if (kept.isEmpty() && !all.isEmpty()) {
            log.info("Doc search '{}': {} candidate(s) but all below min-score {} (best was {}). "
                            + "Lower assistant.rag.min-score if this content should have matched.",
                    query, all.size(), minScore, String.format("%.2f", all.get(0).score()));
        } else {
            log.debug("Doc search '{}': kept {}/{} candidates at min-score {}",
                    query, kept.size(), all.size(), minScore);
        }
        return kept;
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
