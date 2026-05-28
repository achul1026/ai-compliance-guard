package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.RerankerPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * D4: Upstage Solar-reranker 어댑터.
 * https://api.upstage.ai/v1/ranking
 */
@Slf4j
@Component
public class UpstageSolarRerankerAdapter implements RerankerPort {

    private final RestTemplate restTemplate;

    @Value("${upstage.api-key:}")
    private String apiKey;

    @Value("${upstage.reranker-url:https://api.upstage.ai/v1/ranking}")
    private String rerankerUrl;

    @Value("${upstage.reranker-model:solar-1-reranker}")
    private String modelName;

    @Value("${upstage.timeout-seconds:30}")
    private int timeoutSeconds;

    public UpstageSolarRerankerAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public List<RerankedResult> rerank(String query, List<RerankCandidate> candidates, int topK) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        log.debug("Reranking {} candidates for query: '{}'", candidates.size(), query);

        try {
            RankingRequest request = new RankingRequest(
                modelName,
                query,
                candidates.stream()
                    .map(c -> c.content())
                    .collect(Collectors.toList())
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<RankingRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<RankingResponse> response = restTemplate.postForEntity(
                rerankerUrl,
                entity,
                RankingResponse.class
            );

            if (response.getBody() == null || response.getBody().results == null) {
                log.warn("Empty reranking response");
                return convertCandidatesToResults(candidates, topK);
            }

            List<RerankedResult> results = response.getBody().results.stream()
                .map(r -> {
                    Long docId = candidates.get(r.index).documentId();
                    return new RerankedResult(docId, (float) r.score, (int) r.rank);
                })
                .sorted(Comparator.comparing(RerankedResult::newRank))
                .limit(topK)
                .collect(Collectors.toList());

            log.debug("Reranking completed. Top {} results returned", results.size());
            return results;

        } catch (Exception e) {
            log.error("Reranking failed", e);
            return convertCandidatesToResults(candidates, topK);
        }
    }

    private List<RerankedResult> convertCandidatesToResults(List<RerankCandidate> candidates, int topK) {
        return candidates.stream()
            .limit(topK)
            .mapToInt(c -> candidates.indexOf(c))
            .mapToObj(i -> new RerankedResult(
                candidates.get(i).documentId(),
                1.0f - (i * 0.1f),
                i + 1
            ))
            .collect(Collectors.toList());
    }

    static class RankingRequest {
        @JsonProperty("model")
        public String model;

        @JsonProperty("query")
        public String query;

        @JsonProperty("passages")
        public List<String> passages;

        public RankingRequest(String model, String query, List<String> passages) {
            this.model = model;
            this.query = query;
            this.passages = passages;
        }
    }

    static class RankingResponse {
        @JsonProperty("results")
        public List<RankingResult> results;
    }

    static class RankingResult {
        @JsonProperty("index")
        public int index;

        @JsonProperty("score")
        public double score;

        @JsonProperty("rank")
        public int rank;
    }
}
