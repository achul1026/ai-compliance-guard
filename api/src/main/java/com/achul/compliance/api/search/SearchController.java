package com.achul.compliance.api.search;

import com.achul.compliance.rag.port.ComplianceSearchPort;
import com.achul.compliance.rag.port.HybridSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * P1-8: 컴플라이언스 검색 API.
 * 광고 카피 입력 → 관련 규정 조항 검색.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final HybridSearchPort hybridSearch;

    public SearchController(HybridSearchPort hybridSearch) {
        this.hybridSearch = hybridSearch;
    }

    /**
     * 광고 카피 컴플라이언스 검색.
     * POST /api/v1/search
     *
     * 요청 본문 예시:
     * {
     *   "advertisementCopy": "이 상품은 당뇨병을 예방합니다",
     *   "topK": 10,
     *   "useReranker": true
     * }
     *
     * 응답 예시:
     * {
     *   "query": "이 상품은 당뇨병을 예방합니다",
     *   "relevantRegulations": [
     *     {
     *       "regulationId": 123,
     *       "lawName": "식품표시광고법",
     *       "articleNumber": "제8조",
     *       "chunkText": "...",
     *       "violationType": "질병_예방_치료_표방",
     *       "relevanceScore": 0.95
     *     }
     *   ],
     *   "totalHits": 45,
     *   "searchTimeMs": 250
     * }
     */
    @PostMapping
    public ResponseEntity<ComplianceSearchPort.ComplianceSearchResponse> search(
        @RequestBody SearchRequest request
    ) {
        if (request.advertisementCopy() == null || request.advertisementCopy().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("Search request received. Copy length: {}, TopK: {}",
            request.advertisementCopy().length(), request.topK());

        long startTime = System.currentTimeMillis();

        try {
            // 하이브리드 검색 옵션 생성
            HybridSearchPort.HybridSearchOptions options = new HybridSearchPort.HybridSearchOptions(
                request.topK(),
                request.bm25TopK() != null ? request.bm25TopK() : 20,
                request.vectorTopK() != null ? request.vectorTopK() : 20,
                null,  // lawNameFilter
                null,  // articleNumberFilter
                0.5f,  // bm25Weight
                0.5f,  // vectorWeight
                request.useReranker() != null ? request.useReranker() : true
            );

            // 하이브리드 검색 실행
            var hybridResults = hybridSearch.search(request.advertisementCopy(), options);

            // 응답 생성
            var regulationContexts = hybridResults.stream()
                .map(r -> new ComplianceSearchPort.RegulationContext(
                    r.regulationId(),
                    r.lawName(),
                    r.articleNumber() + (r.paragraphNumber() != null ? " " + r.paragraphNumber() : "") +
                        (r.itemNumber() != null ? " " + r.itemNumber() : ""),
                    r.chunkText(),
                    r.violationType(),
                    r.version(),
                    r.finalScore(),
                    r.searchMethod()
                ))
                .toList();

            long searchTimeMs = System.currentTimeMillis() - startTime;

            var response = new ComplianceSearchPort.ComplianceSearchResponse(
                request.advertisementCopy(),
                regulationContexts,
                regulationContexts.size(),
                searchTimeMs
            );

            log.info("Search completed in {}ms. Results: {}", searchTimeMs, regulationContexts.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Search failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 검색 요청 모델.
     */
    record SearchRequest(
        String advertisementCopy,    // 검사할 광고 문구
        Integer topK,                 // 반환 결과 수 (기본: 10)
        Integer bm25TopK,             // BM25 top-k (기본: 20)
        Integer vectorTopK,           // Vector top-k (기본: 20)
        Boolean useReranker           // Re-ranker 사용 여부 (기본: true)
    ) {}
}
