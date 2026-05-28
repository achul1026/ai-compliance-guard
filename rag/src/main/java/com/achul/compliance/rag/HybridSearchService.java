package com.achul.compliance.rag;

import com.achul.compliance.rag.dto.HybridScore;
import com.achul.compliance.rag.dto.RerankedResult;
import com.achul.compliance.rag.port.EmbeddingPort;
import com.achul.compliance.rag.port.KeywordSearchPort;
import com.achul.compliance.rag.port.RerankerPort;
import com.achul.compliance.rag.port.VectorSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG-004: 하이브리드 결합 + Re-ranking 서비스.
 *
 * <p>벡터 검색(코사인 유사도)과 BM25 키워드 검색 결과를 점수 정규화 후 가중합으로 결합한다.
 * 기본 가중치는 ADR-004 가이드에 따라 벡터 0.6 / 키워드 0.4.</p>
 *
 * <ul>
 *   <li>벡터 점수: pgvector 코사인 유사도 (이미 0~1 범위로 정규화됨)</li>
 *   <li>BM25 점수: 결과 집합 내 max 값으로 나눠 0~1로 정규화</li>
 *   <li>최종 점수 = vectorScore × 0.6 + normalizedBm25 × 0.4</li>
 * </ul>
 *
 * <p>결합 결과 상위 K개(기본 20개)를 Upstage Reranker로 재정렬해 최종 Top-N을 반환한다.</p>
 *
 * <p>RRF 기반 결합이 필요한 경우는 기존 {@link com.achul.compliance.rag.adapter.HybridSearchAdapter}를
 * 사용한다. 본 서비스는 ADR-004에서 명시한 가중합 정규화 방식의 진입점이다.</p>
 */
@Slf4j
@Service
public class HybridSearchService {

    private static final float DEFAULT_VECTOR_WEIGHT = 0.6f;
    private static final float DEFAULT_KEYWORD_WEIGHT = 0.4f;
    private static final int DEFAULT_CANDIDATE_TOP_K = 20;
    private static final int DEFAULT_RERANK_TOP_K = 10;

    private final VectorSearchPort vectorSearch;
    private final KeywordSearchPort keywordSearch;
    private final RerankerPort reranker;
    private final EmbeddingPort embedding;

    @Value("${rag.hybrid.vector-weight:0.6}")
    private float vectorWeight;

    @Value("${rag.hybrid.keyword-weight:0.4}")
    private float keywordWeight;

    @Value("${rag.hybrid.candidate-top-k:20}")
    private int candidateTopK;

    @Value("${rag.hybrid.rerank-top-k:10}")
    private int rerankTopK;

    public HybridSearchService(
        VectorSearchPort vectorSearch,
        KeywordSearchPort keywordSearch,
        RerankerPort reranker,
        EmbeddingPort embedding
    ) {
        this.vectorSearch = vectorSearch;
        this.keywordSearch = keywordSearch;
        this.reranker = reranker;
        this.embedding = embedding;
        this.vectorWeight = DEFAULT_VECTOR_WEIGHT;
        this.keywordWeight = DEFAULT_KEYWORD_WEIGHT;
        this.candidateTopK = DEFAULT_CANDIDATE_TOP_K;
        this.rerankTopK = DEFAULT_RERANK_TOP_K;
    }

    /**
     * 하이브리드 검색 + Re-ranking (기본 옵션 사용).
     */
    public List<RerankedResult> search(String query) {
        return search(query, rerankTopK);
    }

    /**
     * 하이브리드 검색 + Re-ranking.
     *
     * @param query 사용자 질의
     * @param topN  최종 반환 개수
     * @return Reranker 점수 순으로 정렬된 상위 N개
     */
    public List<RerankedResult> search(String query, int topN) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        long startMs = System.currentTimeMillis();

        // 1) 벡터·키워드 검색 (각 상위 candidateTopK)
        List<VectorSearchPort.VectorSearchResult> vectorResults =
            vectorSearch.searchByText(query, candidateTopK, embedding);
        List<KeywordSearchPort.KeywordSearchResult> keywordResults =
            keywordSearch.search(query, candidateTopK);

        log.debug("Hybrid candidates fetched: vector={}, keyword={}",
            vectorResults.size(), keywordResults.size());

        // 2) 결합
        List<HybridScore> combined = combine(vectorResults, keywordResults);

        // 3) 상위 candidateTopK개로 잘라 Reranker 입력 구성
        List<HybridScore> candidates = combined.stream()
            .sorted(Comparator.comparing(HybridScore::totalScore).reversed())
            .limit(candidateTopK)
            .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            log.info("Hybrid search returned no candidates. query='{}'", query);
            return List.of();
        }

        // 4) Re-ranking
        List<RerankedResult> reranked = rerank(query, candidates, topN);

        long elapsed = System.currentTimeMillis() - startMs;
        log.info("Hybrid search completed. query='{}', candidates={}, returned={}, elapsedMs={}",
            query, candidates.size(), reranked.size(), elapsed);

        return reranked;
    }

    /**
     * 벡터·키워드 결과를 정규화 + 가중합으로 결합.
     * 패키지 가시성: 단위 테스트가 외부 의존성 없이 결합 로직만 검증 가능하도록.
     */
    List<HybridScore> combine(
        List<VectorSearchPort.VectorSearchResult> vectorResults,
        List<KeywordSearchPort.KeywordSearchResult> keywordResults
    ) {
        Map<Long, Builder> byId = new HashMap<>();

        // 벡터 점수는 이미 0~1 범위 (pgvector 1 - cosine_distance)
        for (VectorSearchPort.VectorSearchResult v : vectorResults) {
            float weighted = clamp01(v.similarity()) * vectorWeight;
            byId.computeIfAbsent(v.regulationId(), Builder::new)
                .withVector(weighted, v);
        }

        // BM25 점수는 결과 집합 내 max로 정규화
        float maxBm25 = (float) keywordResults.stream()
            .mapToDouble(k -> k.bm25Score() == null ? 0.0 : k.bm25Score())
            .max()
            .orElse(0.0);

        for (KeywordSearchPort.KeywordSearchResult k : keywordResults) {
            float raw = k.bm25Score() == null ? 0f : k.bm25Score();
            float normalized = maxBm25 > 0 ? Math.min(raw / maxBm25, 1.0f) : 0f;
            float weighted = normalized * keywordWeight;
            byId.computeIfAbsent(k.regulationId(), Builder::new)
                .withKeyword(weighted, k);
        }

        return byId.values().stream()
            .map(Builder::build)
            .collect(Collectors.toList());
    }

    /**
     * Reranker 호출 및 결과 매핑.
     */
    private List<RerankedResult> rerank(String query, List<HybridScore> candidates, int topN) {
        List<RerankerPort.RerankCandidate> rerankInputs = candidates.stream()
            .map(c -> new RerankerPort.RerankCandidate(c.regulationId(), c.chunkText()))
            .collect(Collectors.toList());

        List<RerankerPort.RerankedResult> rerankerOutputs;
        try {
            rerankerOutputs = reranker.rerank(query, rerankInputs, topN);
        } catch (Exception e) {
            log.warn("Reranker failed, falling back to hybrid score order", e);
            return fallbackByHybridScore(candidates, topN);
        }

        if (rerankerOutputs == null || rerankerOutputs.isEmpty()) {
            return fallbackByHybridScore(candidates, topN);
        }

        Map<Long, HybridScore> byId = candidates.stream()
            .collect(Collectors.toMap(HybridScore::regulationId, c -> c, (a, b) -> a));

        return rerankerOutputs.stream()
            .filter(r -> byId.containsKey(r.documentId()))
            .map(r -> new RerankedResult(
                byId.get(r.documentId()),
                r.relevanceScore() == null ? 0f : r.relevanceScore(),
                r.newRank() == null ? 0 : r.newRank()
            ))
            .sorted(Comparator.comparingInt(RerankedResult::rank))
            .limit(topN)
            .collect(Collectors.toList());
    }

    private List<RerankedResult> fallbackByHybridScore(List<HybridScore> candidates, int topN) {
        List<HybridScore> sorted = candidates.stream()
            .sorted(Comparator.comparing(HybridScore::totalScore).reversed())
            .limit(topN)
            .collect(Collectors.toList());

        List<RerankedResult> out = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            out.add(new RerankedResult(sorted.get(i), sorted.get(i).totalScore(), i + 1));
        }
        return out;
    }

    private static float clamp01(Float v) {
        if (v == null) return 0f;
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /**
     * 단일 regulationId에 대한 양쪽 점수 누적용 빌더.
     */
    private static final class Builder {
        private final Long regulationId;
        private float vectorScore = 0f;
        private float keywordScore = 0f;
        private VectorSearchPort.VectorSearchResult vectorResult;
        private KeywordSearchPort.KeywordSearchResult keywordResult;

        Builder(Long regulationId) {
            this.regulationId = regulationId;
        }

        void withVector(float weighted, VectorSearchPort.VectorSearchResult v) {
            this.vectorScore = weighted;
            this.vectorResult = v;
        }

        void withKeyword(float weighted, KeywordSearchPort.KeywordSearchResult k) {
            this.keywordScore = weighted;
            this.keywordResult = k;
        }

        HybridScore build() {
            return new HybridScore(regulationId, vectorScore, keywordScore, vectorResult, keywordResult);
        }
    }
}
