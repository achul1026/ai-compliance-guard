package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.KeywordSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ADR-002: ParadeDB (pg_search 0.23.x) BM25 키워드 검색 구현체.
 *
 * 사용 SQL 예:
 * <pre>
 * SELECT id, law_name, article_number, ...,
 *        paradedb.score(id) AS bm25_score
 * FROM regulations
 * WHERE chunk_text @@@ ?      -- BM25 매칭(다중 토큰 지원)
 *   AND law_name = ?          -- (선택) 메타 필터
 *   AND article_number = ?    -- (선택) 메타 필터
 * ORDER BY bm25_score DESC
 * LIMIT ?;
 * </pre>
 *
 * pg_search 0.23.x의 BM25 파라미터는 인덱스 빌드 시점에 결정되며
 * 본 마이그레이션은 기본값(k1=1.2, b=0.75)을 사용한다. EVAL-002에서 A/B 검증한다.
 */
@Slf4j
@Component
public class ParadeDbKeywordSearchAdapter implements KeywordSearchPort {

    private final JdbcTemplate jdbcTemplate;

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

        StringBuilder sql = new StringBuilder(
            "SELECT r.id, r.law_name, r.article_number, r.paragraph_number, r.item_number, " +
            "       r.chunk_text, r.version, paradedb.score(r.id) AS bm25_score " +
            "FROM regulations r " +
            "WHERE r.chunk_text @@@ ? "
        );

        List<Object> params = new ArrayList<>();
        params.add(query);

        if (lawName != null && !lawName.isBlank()) {
            sql.append("AND r.law_name = ? ");
            params.add(lawName);
        }

        if (articleNumber != null && !articleNumber.isBlank()) {
            sql.append("AND r.article_number = ? ");
            params.add(articleNumber);
        }

        sql.append("ORDER BY bm25_score DESC LIMIT ?");
        params.add(limit);

        long startNanos = System.nanoTime();
        try {
            List<KeywordSearchResult> results = jdbcTemplate.query(
                sql.toString(),
                params.toArray(),
                rowMapper()
            );
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.info("BM25 search '{}' (law={}, article={}): {} hits, {} ms",
                query, lawName, articleNumber, results.size(), elapsedMs);
            return results;
        } catch (Exception e) {
            log.error("BM25 search failed. query='{}'", query, e);
            throw new RuntimeException("BM25 search failed", e);
        }
    }

    private RowMapper<KeywordSearchResult> rowMapper() {
        return (rs, rowNum) -> new KeywordSearchResult(
            rs.getLong("id"),
            rs.getString("law_name"),
            rs.getString("article_number"),
            rs.getString("paragraph_number"),
            rs.getString("item_number"),
            rs.getString("chunk_text"),
            rs.getFloat("bm25_score"),
            rs.getString("version")
        );
    }
}
