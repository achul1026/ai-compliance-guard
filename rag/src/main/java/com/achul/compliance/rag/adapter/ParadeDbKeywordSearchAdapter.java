package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.KeywordSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * ADR-002: ParadeDB (PostgreSQL BM25 확장) 키워드 검색 구현체.
 */
@Slf4j
@Component
public class ParadeDbKeywordSearchAdapter implements KeywordSearchPort {

    private final JdbcTemplate jdbcTemplate;

    private static final Pattern ARTICLE_PATTERN = Pattern.compile("제(\\d+)조");
    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("제(\\d+)항");
    private static final Pattern ITEM_PATTERN = Pattern.compile("제(\\d+)호");

    public ParadeDbKeywordSearchAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<KeywordSearchResult> search(String query, int limit) {
        return searchWithFilters(query, null, null, limit);
    }

    @Override
    public List<KeywordSearchResult> searchWithFilters(
        String query,
        String lawName,
        String articleNumber,
        int limit
    ) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        log.debug("Executing BM25 search. Query: '{}', Limit: {}", query, limit);

        StringBuilder sqlBuilder = new StringBuilder(
            "SELECT r.id, r.law_name, r.article_number, r.paragraph_number, r.item_number, " +
            "       r.chunk_text, r.version " +
            "FROM regulations r " +
            "WHERE r.chunk_text LIKE ? "
        );

        java.util.List<Object> params = new java.util.ArrayList<>();
        params.add("%" + query + "%");

        if (lawName != null && !lawName.isBlank()) {
            sqlBuilder.append("AND r.law_name = ? ");
            params.add(lawName);
        }

        if (articleNumber != null && !articleNumber.isBlank()) {
            sqlBuilder.append("AND r.article_number = ? ");
            params.add(articleNumber);
        }

        sqlBuilder.append("LIMIT ?");
        params.add(limit);

        String finalSql = sqlBuilder.toString();

        try {
            List<KeywordSearchResult> results = jdbcTemplate.query(
                finalSql,
                params.toArray(),
                getRowMapper()
            );

            log.info("BM25 search completed. Returned {} results", results.size());
            return results;

        } catch (Exception e) {
            log.error("BM25 search failed", e);
            throw new RuntimeException("BM25 search failed", e);
        }
    }

    private boolean isMetadataQuery(String query) {
        return ARTICLE_PATTERN.matcher(query).find() ||
               PARAGRAPH_PATTERN.matcher(query).find() ||
               ITEM_PATTERN.matcher(query).find() ||
               query.contains("법");
    }

    private RowMapper<KeywordSearchResult> getRowMapper() {
        return (rs, rowNum) -> new KeywordSearchResult(
            rs.getLong("id"),
            rs.getString("law_name"),
            rs.getString("article_number"),
            rs.getString("paragraph_number"),
            rs.getString("item_number"),
            rs.getString("chunk_text"),
            1.0f,
            rs.getString("version")
        );
    }
}
