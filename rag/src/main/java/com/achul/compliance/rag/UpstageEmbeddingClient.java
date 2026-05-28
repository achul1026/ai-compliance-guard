package com.achul.compliance.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * P1-5 (RAG-002): Upstage Embedding REST 클라이언트.
 *
 * 책임:
 * - Upstage solar-embedding-1-large API 단일 호출(POST /v1/embeddings)
 * - Bearer 인증 헤더 부착
 * - 응답 파싱 후 float[][] 반환
 *
 * 본 클래스는 단일 배치 호출만 책임진다. 재시도, 큰 배치 분할, DB 적재는
 * {@link EmbeddingPipeline} 와 {@link com.achul.compliance.rag.adapter.UpstageSolarEmbeddingAdapter} 가 담당한다.
 *
 * Upstage API 응답 예시:
 * <pre>
 * {
 *   "data": [
 *     {"object": "embedding", "embedding": [0.1, 0.2, ...], "index": 0},
 *     ...
 *   ],
 *   "model": "solar-embedding-1-large",
 *   "usage": {"prompt_tokens": 123, "total_tokens": 123}
 * }
 * </pre>
 */
@Slf4j
@Component
public class UpstageEmbeddingClient {

    private final RestTemplate restTemplate;

    @Value("${upstage.api-key}")
    private String apiKey;

    @Value("${upstage.embedding-model:solar-embedding-1-large}")
    private String modelName;

    @Value("${upstage.api-url:https://api.upstage.ai/v1/embeddings}")
    private String apiUrl;

    @Value("${upstage.embedding-dimension:4096}")
    private int dimension;

    public UpstageEmbeddingClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 텍스트 배치 1회를 Upstage API로 임베딩.
     *
     * @param texts 빈/null 아닌 텍스트 리스트 (호출자가 사전 필터링 책임)
     * @return 텍스트 순서가 보존된 float[] 리스트. 차원은 {@link #dimension()}.
     * @throws RestClientException API 호출 실패 (호출자가 재시도 처리)
     * @throws IllegalStateException 응답이 비정상 (차원 불일치, 개수 불일치 등)
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        EmbeddingRequest body = new EmbeddingRequest(modelName, texts);
        HttpEntity<EmbeddingRequest> entity = new HttpEntity<>(body, headers);

        log.debug("POST {} with {} input(s), model={}", apiUrl, texts.size(), modelName);

        ResponseEntity<EmbeddingResponse> response =
            restTemplate.postForEntity(apiUrl, entity, EmbeddingResponse.class);

        EmbeddingResponse payload = response.getBody();
        if (payload == null || payload.data == null) {
            throw new IllegalStateException("Upstage 응답 본문이 비어 있습니다. status=" + response.getStatusCode());
        }
        if (payload.data.size() != texts.size()) {
            throw new IllegalStateException(
                "Upstage 응답 개수 불일치: 요청 " + texts.size() + " 건, 응답 " + payload.data.size() + " 건");
        }

        // index 필드 기준 정렬 (응답이 순서를 보장하지 않을 가능성 방어)
        List<float[]> ordered = payload.data.stream()
            .sorted((a, b) -> Integer.compare(
                a.index == null ? 0 : a.index,
                b.index == null ? 0 : b.index))
            .map(item -> item.embedding)
            .collect(Collectors.toList());

        for (int i = 0; i < ordered.size(); i++) {
            float[] vec = ordered.get(i);
            if (vec == null || vec.length != dimension) {
                throw new IllegalStateException(
                    "임베딩 차원 불일치(index=" + i + "): 기대 " + dimension
                        + ", 실제 " + (vec == null ? "null" : vec.length));
            }
        }
        return ordered;
    }

    public int dimension() {
        return dimension;
    }

    public String modelName() {
        return modelName;
    }

    public String apiUrl() {
        return apiUrl;
    }

    // ---------- 요청/응답 DTO ----------

    /**
     * Upstage embeddings 요청 본문.
     * {@code encoding_format}은 기본 "float". bit quantization은 Phase 1.5+에서 검토.
     */
    @SuppressWarnings("unused")
    static class EmbeddingRequest {
        @JsonProperty("model")
        public final String model;
        @JsonProperty("input")
        public final List<String> input;

        EmbeddingRequest(String model, List<String> input) {
            this.model = model;
            this.input = input;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @SuppressWarnings("unused")
    static class EmbeddingResponse {
        @JsonProperty("data")
        public List<EmbeddingItem> data;
        @JsonProperty("model")
        public String model;
        @JsonProperty("usage")
        public Map<String, Object> usage;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @SuppressWarnings("unused")
    static class EmbeddingItem {
        @JsonProperty("object")
        public String object;
        @JsonProperty("embedding")
        public float[] embedding;
        @JsonProperty("index")
        public Integer index;
    }
}
