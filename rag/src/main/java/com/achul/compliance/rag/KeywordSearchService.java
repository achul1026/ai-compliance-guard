package com.achul.compliance.rag;

import com.achul.compliance.rag.adapter.ParadedbBm25Adapter;
import com.achul.compliance.rag.dto.SearchRequest;
import com.achul.compliance.rag.dto.SearchResponse;
import com.achul.compliance.rag.port.KeywordSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG-003: BM25 키워드 검색 비즈니스 로직.
 *
 * <p>책임:
 * <ol>
 *   <li>쿼리에서 조항/법령 패턴(예: "제8조", "식품표시광고법")을 감지해
 *       메타 부스팅용 보조 키를 추출한다 (ADR-002 메타 부스팅 룰).</li>
 *   <li>ParadeDB BM25에 본문 검색을 위임한다.</li>
 *   <li>메타가 정확히 일치하는 결과에 부스팅 점수를 가산한다.</li>
 *   <li>결과를 {@link SearchResponse} DTO로 정규화한다.</li>
 * </ol>
 */
@Slf4j
@Service
public class KeywordSearchService {

    /** "제8조", "제 8조" 등 조항 번호 패턴. */
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("제\\s*(\\d+)\\s*조");
    /** "식품표시광고법", "건강기능식품법" 등 한국어 법령 명칭 패턴(끝이 "법" 또는 "법률"). */
    private static final Pattern LAW_PATTERN = Pattern.compile("([가-힣]+(?:법률|법))");

    /** 조항 정확 매칭 부스트. */
    private static final float ARTICLE_MATCH_BOOST = 2.0f;
    /** 법령명 정확 매칭 부스트. */
    private static final float LAW_MATCH_BOOST = 1.5f;

    private final ParadedbBm25Adapter bm25Adapter;

    public KeywordSearchService(ParadedbBm25Adapter bm25Adapter) {
        this.bm25Adapter = bm25Adapter;
    }

    /**
     * BM25 검색 + 메타 부스팅.
     */
    public SearchResponse search(SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return new SearchResponse("", 0, 0L, List.of());
        }

        String query = request.query().trim();
        long start = System.nanoTime();

        // (1) 쿼리에서 메타 추출 → 명시 필터가 없을 때만 보조 적용
        String detectedArticle = request.articleFilter() != null
            ? request.articleFilter()
            : detectArticle(query);
        String detectedLaw = request.lawNameFilter();
        String detectedLawHint = detectLawHint(query);

        // (2) BM25 본문 검색 위임
        //   ※ 명시 필터(법령/조항)는 SQL WHERE에 그대로 적용해 검색 폭을 좁힌다.
        //     암시적으로 감지된 부분은 SQL 필터로는 쓰지 않고 부스팅 단계에서만 사용한다.
        List<KeywordSearchPort.KeywordSearchResult> rawHits = bm25Adapter.searchWithFilters(
            query,
            request.lawNameFilter(),
            request.articleFilter(),
            // 부스팅으로 순서가 바뀔 수 있으므로 후보를 넉넉히(2배) 받은 뒤 잘라낸다.
            request.effectiveLimit() * 2
        );

        // (3) 메타 부스팅 적용 후 재정렬
        List<SearchResponse.Hit> boosted = new ArrayList<>(rawHits.size());
        for (KeywordSearchPort.KeywordSearchResult r : rawHits) {
            float base = r.bm25Score() != null ? r.bm25Score() : 0f;
            float boost = 0f;
            if (detectedArticle != null && detectedArticle.equals(r.articleNumber())) {
                boost += ARTICLE_MATCH_BOOST;
            }
            if (detectedLaw != null && detectedLaw.equals(r.lawName())) {
                boost += LAW_MATCH_BOOST;
            } else if (detectedLawHint != null
                && r.lawName() != null
                && r.lawName().contains(detectedLawHint)) {
                boost += LAW_MATCH_BOOST;
            }

            boosted.add(new SearchResponse.Hit(
                r.regulationId(),
                r.lawName(),
                r.articleNumber(),
                r.paragraphNumber(),
                r.itemNumber(),
                r.chunkText(),
                base,
                base + boost,
                r.version()
            ));
        }

        boosted.sort((a, b) -> Float.compare(
            b.boostedScore() == null ? 0f : b.boostedScore(),
            a.boostedScore() == null ? 0f : a.boostedScore()
        ));

        List<SearchResponse.Hit> top = boosted.size() > request.effectiveLimit()
            ? boosted.subList(0, request.effectiveLimit())
            : boosted;

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        log.info("KeywordSearchService.search query='{}' hits={} elapsedMs={} boostedArticle={} boostedLaw={}",
            query, top.size(), elapsedMs, detectedArticle, detectedLaw != null ? detectedLaw : detectedLawHint);

        return new SearchResponse(query, top.size(), elapsedMs, top);
    }

    private static String detectArticle(String query) {
        Matcher m = ARTICLE_PATTERN.matcher(query);
        return m.find() ? "제" + m.group(1) + "조" : null;
    }

    private static String detectLawHint(String query) {
        Matcher m = LAW_PATTERN.matcher(query);
        return m.find() ? m.group(1) : null;
    }
}
