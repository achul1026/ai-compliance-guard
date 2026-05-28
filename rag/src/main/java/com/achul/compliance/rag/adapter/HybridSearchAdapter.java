package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * P1-7: Hybrid RAG 검색 (BM25 + Vector + Re-ranking).
 * RRF(Reciprocal Rank Fusion) 알고리즘으로 결합.
 */
@Slf4j
@Component
public class HybridSearchAdapter implements HybridSearchPort {

    private final KeywordSearchPort keywordSearch;
    private final VectorSearchPort vectorSearch;
    private final RerankerPort reranker;

    public HybridSearchAdapter(
        KeywordSearchPort keywordSearch,
        VectorSearchPort vectorSearch,
        RerankerPort reranker
    ) {
        this.keywordSearch = keywordSearch;
        this.vectorSearch = vectorSearch;
        this.reranker = reranker;
    }

    @Override
    public List<HybridSearchResult> search(String query, HybridSearchOptions options) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        HybridSearchOptions opts = options != null ? options : HybridSearchOptions.defaults();

        log.info("Starting hybrid search. Query: '{}', TopK: {}, UseReranker: {}",
            query, opts.topK(), opts.useReranker());

        long startTime = System.currentTimeMillis();

        try {
            // Step 1: BM25 키워드 검색
            log.debug("Step 1: BM25 키워드 검색 (top-k: {})", opts.bm25TopK());
            List<BM25Result> bm25Results = performBM25Search(query, opts);

            // Step 2: Vector 의미 검색
            log.debug("Step 2: Vector 의미 검색 (top-k: {})", opts.vectorTopK());
            List<VectorSearchPort.VectorSearchResult> vectorResults = performVectorSearch(query, opts);

            // Step 3: RRF로 결합
            log.debug("Step 3: RRF 결합");
            List<HybridResult> hybridResults = combineWithRRF(
                bm25Results, vectorResults, opts
            );

            // Step 4: Re-ranking (선택사항)
            List<HybridResult> finalResults = hybridResults;
            if (opts.useReranker() != null && opts.useReranker()) {
                log.debug("Step 4: Re-ranking 적용");
                finalResults = applyReranking(query, hybridResults, opts.topK());
            }

            // Step 5: 최종 정렬 및 제한
            List<HybridSearchResult> results = finalResults.stream()
                .sorted(Comparator.comparing(HybridResult::finalScore).reversed())
                .limit(opts.topK())
                .map(this::toHybridSearchResult)
                .collect(Collectors.toList());

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Hybrid search completed in {}ms. Returned {} results", elapsed, results.size());

            return results;

        } catch (Exception e) {
            log.error("Hybrid search failed", e);
            throw new RuntimeException("Hybrid search failed", e);
        }
    }

    /**
     * BM25 검색 실행.
     */
    private List<BM25Result> performBM25Search(String query, HybridSearchOptions opts) {
        try {
            List<KeywordSearchPort.KeywordSearchResult> results = keywordSearch.searchWithFilters(
                query,
                opts.lawNameFilter(),
                opts.articleNumberFilter(),
                opts.bm25TopK()
            );

            return results.stream()
                .map(r -> new BM25Result(
                    r.regulationId(),
                    r.lawName(),
                    r.articleNumber(),
                    r.paragraphNumber(),
                    r.itemNumber(),
                    r.chunkText(),
                    r.bm25Score(),
                    r.version()
                ))
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("BM25 search failed, continuing with vector search only", e);
            return List.of();
        }
    }

    /**
     * Vector 검색 실행.
     */
    private List<VectorSearchPort.VectorSearchResult> performVectorSearch(String query, HybridSearchOptions opts) {
        try {
            return vectorSearch.searchByText(query, opts.vectorTopK(), null);
        } catch (Exception e) {
            log.warn("Vector search failed, continuing with BM25 results only", e);
            return List.of();
        }
    }

    /**
     * RRF(Reciprocal Rank Fusion) 알고리즘.
     */
    private List<HybridResult> combineWithRRF(
        List<BM25Result> bm25Results,
        List<VectorSearchPort.VectorSearchResult> vectorResults,
        HybridSearchOptions opts
    ) {
        Map<Long, HybridResult> combined = new HashMap<>();

        // BM25 결과 처리
        for (int i = 0; i < bm25Results.size(); i++) {
            BM25Result r = bm25Results.get(i);
            float rrfScore = opts.bm25Weight() / (i + 1);

            combined.put(r.regulationId(), new HybridResult(
                r.regulationId(),
                r.lawName(),
                r.articleNumber(),
                r.paragraphNumber(),
                r.itemNumber(),
                r.chunkText(),
                r.bm25Score(),
                null,
                null,
                rrfScore,
                "bm25",
                r.version()
            ));
        }

        // Vector 결과 병합
        for (int i = 0; i < vectorResults.size(); i++) {
            VectorSearchPort.VectorSearchResult r = vectorResults.get(i);
            float vectorRrf = opts.vectorWeight() / (i + 1);

            combined.compute(r.regulationId(), (id, existing) -> {
                if (existing == null) {
                    return new HybridResult(
                        r.regulationId(),
                        r.lawName(),
                        r.articleNumber(),
                        r.paragraphNumber(),
                        r.itemNumber(),
                        r.chunkText(),
                        null,
                        r.similarity(),
                        null,
                        vectorRrf,
                        "vector",
                        r.version()
                    );
                } else {
                    float newFinalScore = existing.finalScore() + vectorRrf;
                    return new HybridResult(
                        existing.regulationId(),
                        existing.lawName(),
                        existing.articleNumber(),
                        existing.paragraphNumber(),
                        existing.itemNumber(),
                        existing.chunkText(),
                        existing.bm25Score(),
                        r.similarity(),
                        null,
                        newFinalScore,
                        "hybrid",
                        existing.version()
                    );
                }
            });
        }

        return new ArrayList<>(combined.values());
    }

    /**
     * Re-ranking 적용.
     */
    private List<HybridResult> applyReranking(String query, List<HybridResult> candidates, int topK) {
        try {
            List<RerankerPort.RerankCandidate> rerankCandidates = candidates.stream()
                .map(r -> new RerankerPort.RerankCandidate(r.regulationId(), r.chunkText()))
                .collect(Collectors.toList());

            List<RerankerPort.RerankedResult> rerankedResults = reranker.rerank(
                query, rerankCandidates, topK
            );

            // Reranker 결과를 HybridResult에 매핑
            Map<Long, RerankerPort.RerankedResult> rerankerScores = rerankedResults.stream()
                .collect(Collectors.toMap(
                    RerankerPort.RerankedResult::documentId,
                    r -> r
                ));

            return candidates.stream()
                .map(r -> {
                    RerankerPort.RerankedResult reranked = rerankerScores.get(r.regulationId());
                    if (reranked != null) {
                        return new HybridResult(
                            r.regulationId(),
                            r.lawName(),
                            r.articleNumber(),
                            r.paragraphNumber(),
                            r.itemNumber(),
                            r.chunkText(),
                            r.bm25Score(),
                            r.vectorScore(),
                            reranked.relevanceScore(),
                            reranked.relevanceScore(),
                            "reranked",
                            r.version()
                        );
                    }
                    return r;
                })
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("Reranking failed, using RRF scores only", e);
            return candidates;
        }
    }

    /**
     * 내부 결과 모델.
     */
    record BM25Result(
        Long regulationId,
        String lawName,
        String articleNumber,
        String paragraphNumber,
        String itemNumber,
        String chunkText,
        Float bm25Score,
        String version
    ) {}

    record HybridResult(
        Long regulationId,
        String lawName,
        String articleNumber,
        String paragraphNumber,
        String itemNumber,
        String chunkText,
        Float bm25Score,
        Float vectorScore,
        Float rerankerScore,
        Float finalScore,
        String searchMethod,
        String version
    ) {}

    /**
     * HybridSearchResult로 변환.
     */
    private HybridSearchResult toHybridSearchResult(HybridResult r) {
        return new HybridSearchResult(
            r.regulationId(),
            r.lawName(),
            r.articleNumber(),
            r.paragraphNumber(),
            r.itemNumber(),
            r.chunkText(),
            null,
            r.version(),
            r.bm25Score(),
            r.vectorScore(),
            r.rerankerScore(),
            r.finalScore(),
            r.searchMethod()
        );
    }
}
