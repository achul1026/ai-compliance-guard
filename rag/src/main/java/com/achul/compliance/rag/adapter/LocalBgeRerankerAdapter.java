package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.RerankerPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * D4(비용 0): BGE-reranker-v2-m3 로컬 어댑터.
 *
 * <p>HuggingFace TEI(text-embeddings-inference) 컨테이너의 {@code /rerank}를 호출한다.
 * {@code rag.reranker.provider=local} 일 때만 활성화. Upstage Solar-reranker(유료, ADR-004)의
 * 비용 0 대안.</p>
 *
 * <p>TEI /rerank 계약:
 * <ul>
 *   <li>요청 {@code {"query": "...", "texts": ["..."], "raw_scores": false, "return_text": false}}</li>
 *   <li>응답 {@code [{"index": 0, "score": 0.99}, ...]} (score 내림차순)</li>
 * </ul></p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.reranker.provider", havingValue = "local")
public class LocalBgeRerankerAdapter implements RerankerPort {

    private final RestTemplate restTemplate;

    @Value("${rag.reranker.local.url:http://localhost:8081}")
    private String baseUrl;

    public LocalBgeRerankerAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public List<RerankedResult> rerank(String query, List<RerankCandidate> candidates, int topK) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        log.debug("LocalBgeReranker: query='{}', candidates={}, topK={}", query, candidates.size(), topK);
        try {
            List<String> texts = candidates.stream()
                .map(RerankCandidate::content)
                .collect(Collectors.toList());

            RerankRequest request = new RerankRequest(query, texts, false, false);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<RerankRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<RerankResult[]> response = restTemplate.postForEntity(
                baseUrl + "/rerank", entity, RerankResult[].class);

            RerankResult[] body = response.getBody();
            if (body == null || body.length == 0) {
                log.warn("Empty rerank response, falling back to original order");
                return fallback(candidates, topK);
            }

            List<RerankResult> sorted = Arrays.stream(body)
                .sorted(Comparator.comparingDouble((RerankResult r) -> r.score).reversed())
                .limit(topK)
                .collect(Collectors.toList());

            List<RerankedResult> results = new ArrayList<>(sorted.size());
            for (int rank = 0; rank < sorted.size(); rank++) {
                RerankResult r = sorted.get(rank);
                results.add(new RerankedResult(
                    candidates.get(r.index).documentId(), (float) r.score, rank + 1));
            }
            return results;

        } catch (Exception e) {
            log.error("Local rerank 실패 (TEI 컨테이너 기동 여부 확인), 원래 순서로 폴백", e);
            return fallback(candidates, topK);
        }
    }

    private List<RerankedResult> fallback(List<RerankCandidate> candidates, int topK) {
        int limit = Math.min(topK, candidates.size());
        List<RerankedResult> results = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            results.add(new RerankedResult(candidates.get(i).documentId(), 1.0f / (i + 1), i + 1));
        }
        return results;
    }

    static class RerankRequest {
        @JsonProperty("query") public String query;
        @JsonProperty("texts") public List<String> texts;
        @JsonProperty("raw_scores") public boolean rawScores;
        @JsonProperty("return_text") public boolean returnText;

        RerankRequest(String query, List<String> texts, boolean rawScores, boolean returnText) {
            this.query = query;
            this.texts = texts;
            this.rawScores = rawScores;
            this.returnText = returnText;
        }
    }

    static class RerankResult {
        @JsonProperty("index") public int index;
        @JsonProperty("score") public double score;
    }
}
