package com.achul.compliance.agent.workflow;

import com.achul.compliance.agent.domain.RegulationSnippet;
import com.achul.compliance.rag.HybridSearchService;
import com.achul.compliance.rag.dto.HybridScore;
import com.achul.compliance.rag.dto.RerankedResult;
import com.achul.compliance.rag.port.KeywordSearchPort;
import com.achul.compliance.rag.port.VectorSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * P2-6: 검색(Hybrid RAG) → 에이전트 워크플로우 연결 서비스.
 *
 * <p>광고 카피로 근거 규정을 검색해 {@link RegulationSnippet}으로 매핑한 뒤
 * {@link AuditWorkflow}를 실행한다. README의 "Orchestrator → Hybrid RAG → 3-Agent" 흐름의 진입점.</p>
 */
@Slf4j
@Service
public class ComplianceAuditService {

    private final HybridSearchService searchService;
    private final AuditWorkflow workflow;
    private final int searchTopK;

    public ComplianceAuditService(
        HybridSearchService searchService,
        AuditWorkflow workflow,
        @Value("${agent.workflow.search-top-k:8}") int searchTopK
    ) {
        this.searchService = searchService;
        this.workflow = workflow;
        this.searchTopK = searchTopK;
    }

    public ComplianceReport audit(String adCopy) {
        long start = System.currentTimeMillis();

        List<RerankedResult> searchResults = searchService.search(adCopy, searchTopK);
        List<RegulationSnippet> snippets = searchResults.stream()
            .map(ComplianceAuditService::toSnippet)
            .toList();
        log.info("규정 검색 완료. hits={}, elapsedMs={}", snippets.size(), System.currentTimeMillis() - start);

        ComplianceReport report = workflow.run(adCopy, snippets);
        log.info("컴플라이언스 검사 완료. violations={}, iterations={}, critiquePassed={}, totalElapsedMs={}",
            report.audit().clean() ? 0 : report.audit().violations().size(),
            report.iterations(), report.critiquePassed(), System.currentTimeMillis() - start);
        return report;
    }

    /** 검색 DTO → 에이전트 계약 매핑. 벡터 결과 메타데이터 우선, 없으면 키워드 결과. */
    private static RegulationSnippet toSnippet(RerankedResult r) {
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
        return new RegulationSnippet(law, article, source.chunkText());
    }

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
