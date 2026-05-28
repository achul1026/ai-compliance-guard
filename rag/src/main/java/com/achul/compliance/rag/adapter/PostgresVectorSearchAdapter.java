package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.EmbeddingPort;
import com.achul.compliance.rag.port.VectorSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * P1-7: PostgreSQL pgvector 벡터 검색 구현.
 */
@Slf4j
@Component
public class PostgresVectorSearchAdapter implements VectorSearchPort {

    private final JdbcTemplate jdbcTemplate;

    public PostgresVectorSearchAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<VectorSearchResult> search(float[] queryEmbedding, int limit) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }

        log.debug("Vector search: limit={}, dimension={}", limit, queryEmbedding.length);

        try {
            String vectorStr = embeddingToString(queryEmbedding);
            String sql = "SELECT r.id, r.law_name, r.article_number, r.paragraph_number, r.item_number, " +
                        "r.chunk_text, r.version, (1 - (r.embedding <=> ?::vector)) AS similarity " +
                        "FROM regulations r WHERE r.embedding IS NOT NULL " +
                        "ORDER BY r.embedding <=> ?::vector LIMIT ?";

            List<VectorSearchResult> results = jdbcTemplate.query(
                sql,
                new Object[]{vectorStr, vectorStr, limit},
                getRowMapper()
            );

            log.info("Vector search completed. Returned {} results", results.size());
            return results;

        } catch (Exception e) {
            log.error("Vector search failed", e);
            return List.of();
        }
    }

    @Override
    public List<VectorSearchResult> searchByText(String query, int limit, EmbeddingPort embeddingPort) {
        if (query == null || query.isBlank() || embeddingPort == null) {
            return List.of();
        }

        log.debug("Vector search by text: limit={}", limit);

        try {
            float[] queryEmbedding = embeddingPort.embedQuery(query);
            return search(queryEmbedding, limit);
        } catch (Exception e) {
            log.error("Vector search by text failed", e);
            return List.of();
        }
    }

    private String embeddingToString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private RowMapper<VectorSearchResult> getRowMapper() {
        return (rs, rowNum) -> new VectorSearchResult(
            rs.getLong("id"),
            rs.getString("law_name"),
            rs.getString("article_number"),
            rs.getString("paragraph_number"),
            rs.getString("item_number"),
            rs.getString("chunk_text"),
            rs.getFloat("similarity"),
            rs.getString("version")
        );
    }
}
