package com.achul.compliance.api.search;

import com.achul.compliance.rag.HybridSearchService;
import com.achul.compliance.rag.dto.HybridScore;
import com.achul.compliance.rag.dto.RerankedResult;
import com.achul.compliance.rag.port.KeywordSearchPort;
import com.achul.compliance.rag.port.VectorSearchPort;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG-005: 컴플라이언스 검색 API.
 *
 * <p>처리 흐름:
 * <ol>
 *   <li>요청 검증 ({@link SearchRequest})</li>
 *   <li>{@link HybridSearchService}가 내부적으로 쿼리 임베딩 → 벡터 검색 + BM25 검색 → 가중합 정규화 → Re-ranking 수행</li>
 *   <li>{@link RerankedResult}를 {@link SearchResultDto}로 매핑하여 응답</li>
 * </ol>
 *
 * <p>성능 목표: P95 응답 시간 &lt; 500ms.
 */
@Slf4j
@RestController
@RequestMapping("/search")
public class SearchController {

    private final HybridSearchService hybridSearchService;

    public SearchController(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    /**
     * 광고 카피 또는 자유 질의에 대한 관련 규정 조항 검색.
     *
     * <p>엔드포인트: {@code POST /api/v1/search} (context-path {@code /api/v1} + 매핑 {@code /search}).
     *
     * @param request 검색 요청 {@code {"query": "...", "top_k": 10}}
     * @return 관련도 상위 결과 목록
     */
    @PostMapping
    public ResponseEntity<SearchResponse> search(@Valid @RequestBody SearchRequest request) {
        long startTime = System.currentTimeMillis();
        int topK = request.effectiveTopK();

        log.info("Search request received. queryLength={}, topK={}", request.query().length(), topK);

        List<RerankedResult> reranked = hybridSearchService.search(request.query(), topK);

        List<SearchResultDto> results = reranked.stream()
            .map(SearchController::toResultDto)
            .toList();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Search completed. results={}, elapsedMs={}", results.size(), elapsed);

        return ResponseEntity.ok(new SearchResponse(request.query(), results, elapsed));
    }

    /**
     * 하이브리드 + Re-ranking 결과를 응답 DTO로 매핑한다.
     *
     * <p>벡터 결과가 있으면 그쪽 메타데이터(법령명/조항)를 우선 사용하고,
     * 없으면 키워드 결과의 메타데이터를 사용한다.
     */
    private static SearchResultDto toResultDto(RerankedResult r) {
        HybridScore source = r.source();
        VectorSearchPort.VectorSearchResult v = source.vectorResult();
        KeywordSearchPort.KeywordSearchResult k = source.keywordResult();

        String law;
        String article;
        if (v != null) {
            law = v.lawName();
            article = composeArticle(v.articleNumber(), v.paragraphNumber(), v.itemNumber());
        } else if (k != null) {
            law = k.lawName();
            article = composeArticle(k.articleNumber(), k.paragraphNumber(), k.itemNumber());
        } else {
            law = null;
            article = null;
        }

        return new SearchResultDto(law, article, source.chunkText(), r.rerankScore());
    }

    /**
     * 조·항·호를 공백으로 결합한다. null 값은 생략한다.
     */
    private static String composeArticle(String articleNumber, String paragraphNumber, String itemNumber) {
        StringBuilder sb = new StringBuilder();
        if (articleNumber != null && !articleNumber.isBlank()) {
            sb.append(articleNumber);
        }
        if (paragraphNumber != null && !paragraphNumber.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(paragraphNumber);
        }
        if (itemNumber != null && !itemNumber.isBlank()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(itemNumber);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
