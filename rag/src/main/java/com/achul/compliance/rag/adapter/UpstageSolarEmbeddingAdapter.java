package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.EmbeddingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ADR-003: Upstage Solar-embedding 구현체.
 * REST API 기반 임베딩 생성.
 * 1차 배포 선택 (Phase 1.5+에서 BGE-m3 로컬 구현체로 교체 가능).
 */
@Slf4j
@Component
public class UpstageSolarEmbeddingAdapter implements EmbeddingPort {

    private final RestTemplate restTemplate;

    @Value("${upstage.api-key}")
    private String apiKey;

    @Value("${upstage.embedding-model:solar-embedding-1-large}")
    private String modelName;

    @Value("${upstage.api-url:https://api.upstage.ai/v1/embeddings}")
    private String apiUrl;

    @Value("${upstage.embedding-dimension:4096}")
    private int dimension;

    @Value("${upstage.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${upstage.batch-size:20}")
    private int batchSize;

    @Value("${upstage.max-retries:3}")
    private int maxRetries;

    public UpstageSolarEmbeddingAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public float[] embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed cannot be null or empty");
        }

        List<float[]> results = embedDocuments(List.of(text));
        return results.isEmpty() ? new float[dimension] : results.get(0);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        log.info("Starting batch embedding for {} texts using {}", texts.size(), modelName);

        List<float[]> allEmbeddings = new ArrayList<>();
        List<String> nonEmptyTexts = texts.stream()
            .filter(t -> t != null && !t.isBlank())
            .collect(Collectors.toList());

        // 배치 처리 (API rate limit 고려)
        for (int i = 0; i < nonEmptyTexts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, nonEmptyTexts.size());
            List<String> batch = nonEmptyTexts.subList(i, end);

            log.debug("Processing batch {}/{}", i / batchSize + 1, (nonEmptyTexts.size() + batchSize - 1) / batchSize);

            List<float[]> batchEmbeddings = embeddBatchWithRetry(batch);
            allEmbeddings.addAll(batchEmbeddings);

            // Rate limit 회피용 지연 (필요시 조정)
            if (end < nonEmptyTexts.size()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Embedding batch interrupted", e);
                }
            }
        }

        log.info("Batch embedding completed. Total embeddings: {}", allEmbeddings.size());
        return allEmbeddings;
    }

    /**
     * 재시도 로직 포함 배치 임베딩.
     */
    private List<float[]> embeddBatchWithRetry(List<String> texts) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return embedBatchDirect(texts);
            } catch (RestClientException e) {
                if (attempt < maxRetries) {
                    long backoffMs = (long) Math.pow(2, attempt) * 1000; // 지수 백오프
                    log.warn("Embedding API call failed (attempt {}/{}). Retrying after {}ms",
                        attempt, maxRetries, backoffMs, e);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Embedding retry interrupted", ie);
                    }
                } else {
                    log.error("Embedding API call failed after {} attempts", maxRetries, e);
                    throw e;
                }
            }
        }
        return List.of();
    }

    /**
     * Upstage REST API 직접 호출.
     * 응답 형식: { "data": [{"embedding": [0.1, 0.2, ...]}, ...] }
     */
    private List<float[]> embedBatchDirect(List<String> texts) {
        try {
            UpstageBatchRequest request = new UpstageBatchRequest(modelName, texts);

            log.debug("Calling Upstage API with {} texts", texts.size());

            UpstageBatchResponse response = restTemplate.postForObject(apiUrl, request, UpstageBatchResponse.class);

            if (response == null || response.data == null || response.data.isEmpty()) {
                log.warn("Empty response from Upstage API");
                return List.of();
            }

            return response.data.stream()
                .map(item -> item.embedding)
                .collect(Collectors.toList());

        } catch (RestClientException e) {
            throw new RuntimeException("Failed to call Upstage embedding API", e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    /**
     * Upstage API 요청 모델.
     */
    @SuppressWarnings("unused")
    static class UpstageBatchRequest {
        public String model;
        public List<String> input;

        UpstageBatchRequest(String model, List<String> input) {
            this.model = model;
            this.input = input;
        }
    }

    /**
     * Upstage API 응답 모델.
     */
    @SuppressWarnings("unused")
    static class UpstageBatchResponse {
        public List<EmbeddingItem> data;
        public Map<String, Object> usage;

        static class EmbeddingItem {
            public float[] embedding;
            public Integer index;
            public String object;
        }
    }
}
