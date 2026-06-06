package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.EmbeddingPort;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * ADR-005: BGE-m3 임베딩 구현체.
 * FastAPI 기반 로컬 임베딩 서버({@code embedding-server/main.py})를 HTTP로 호출.
 *
 * 활성화 조건: {@code embedding.provider=bge-m3} (application.yml).
 * 미설정 시 기본값 bge-m3로 동작하도록 matchIfMissing=true.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "embedding.provider", havingValue = "bge-m3", matchIfMissing = true)
public class BgeM3EmbeddingAdapter implements EmbeddingPort {

    private final RestTemplate restTemplate;

    @Value("${embedding.bge-m3.url:http://localhost:8001}")
    private String baseUrl;

    @Value("${embedding.bge-m3.model-name:BAAI/bge-m3}")
    private String modelName;

    @Value("${embedding.bge-m3.dimension:1024}")
    private int dimension;

    @Value("${embedding.bge-m3.batch-size:32}")
    private int batchSize;

    @Value("${embedding.bge-m3.normalize:true}")
    private boolean normalize;

    public BgeM3EmbeddingAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public float[] embedQuery(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text to embed cannot be null or empty");
        }
        List<float[]> result = embedDocuments(List.of(text));
        return result.isEmpty() ? new float[dimension] : result.get(0);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> nonEmpty = texts.stream()
            .filter(t -> t != null && !t.isBlank())
            .toList();
        if (nonEmpty.isEmpty()) {
            return List.of();
        }

        log.info("BGE-m3 batch embedding: {} texts via {}", nonEmpty.size(), baseUrl);
        List<float[]> all = new ArrayList<>(nonEmpty.size());

        for (int i = 0; i < nonEmpty.size(); i += batchSize) {
            int end = Math.min(i + batchSize, nonEmpty.size());
            List<String> chunk = nonEmpty.subList(i, end);
            all.addAll(callEmbed(chunk));
        }
        return all;
    }

    private List<float[]> callEmbed(List<String> texts) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        EmbedRequest body = new EmbedRequest(texts, normalize);
        HttpEntity<EmbedRequest> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<EmbedResponse> response = restTemplate.postForEntity(
                baseUrl + "/embed", entity, EmbedResponse.class);
            EmbedResponse payload = response.getBody();

            if (payload == null || payload.embeddings == null) {
                throw new IllegalStateException("BGE-m3 응답 본문이 비어 있습니다. status=" + response.getStatusCode());
            }
            if (payload.embeddings.size() != texts.size()) {
                throw new IllegalStateException(
                    "BGE-m3 응답 개수 불일치: 요청 " + texts.size() + ", 응답 " + payload.embeddings.size());
            }
            if (payload.dim != null && payload.dim != dimension) {
                throw new IllegalStateException(
                    "BGE-m3 차원 불일치: 기대 " + dimension + ", 실제 " + payload.dim);
            }

            List<float[]> out = new ArrayList<>(payload.embeddings.size());
            for (List<Float> vec : payload.embeddings) {
                float[] arr = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    arr[i] = vec.get(i);
                }
                out.add(arr);
            }
            return out;
        } catch (RestClientException e) {
            log.error("BGE-m3 호출 실패: {}", e.getMessage(), e);
            throw e;
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

    @SuppressWarnings("unused")
    static class EmbedRequest {
        @JsonProperty("texts")     public final List<String> texts;
        @JsonProperty("normalize") public final boolean normalize;

        EmbedRequest(List<String> texts, boolean normalize) {
            this.texts = texts;
            this.normalize = normalize;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @SuppressWarnings("unused")
    static class EmbedResponse {
        @JsonProperty("embeddings") public List<List<Float>> embeddings;
        @JsonProperty("dim")        public Integer dim;
        @JsonProperty("model")      public String model;
        @JsonProperty("count")      public Integer count;
        @JsonProperty("elapsed_ms") public Integer elapsedMs;
    }
}
