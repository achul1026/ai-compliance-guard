package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.KeywordSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG-003 산출물: ParadeDB BM25 어댑터의 명시 명칭 진입점.
 *
 * <p>실 구현은 {@link ParadeDbKeywordSearchAdapter}(pg_search BM25)에 위임한다.
 * 작업 명세상의 '클래스 명칭'을 만족하기 위한 thin wrapper이며,
 * 향후 ParadeDB REST API로 전환할 때 본 클래스만 교체해 다른 호출 지점에 영향을 주지 않게 한다.</p>
 */
@Slf4j
@Component
public class ParadedbBm25Adapter {

    private final KeywordSearchPort delegate;

    public ParadedbBm25Adapter(ParadeDbKeywordSearchAdapter delegate) {
        this.delegate = delegate;
    }

    /**
     * BM25 검색 (필터 없음).
     */
    public List<KeywordSearchPort.KeywordSearchResult> search(String query, int limit) {
        return delegate.search(query, limit);
    }

    /**
     * 메타데이터 필터(법령명/조항)와 함께 BM25 검색.
     */
    public List<KeywordSearchPort.KeywordSearchResult> searchWithFilters(
        String query,
        String lawName,
        String articleNumber,
        int limit
    ) {
        return delegate.searchWithFilters(query, lawName, articleNumber, limit);
    }
}
