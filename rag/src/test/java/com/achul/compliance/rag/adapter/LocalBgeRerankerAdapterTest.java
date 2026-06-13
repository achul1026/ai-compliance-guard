package com.achul.compliance.rag.adapter;

import com.achul.compliance.rag.port.RerankerPort.RerankCandidate;
import com.achul.compliance.rag.port.RerankerPort.RerankedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * LocalBgeRerankerAdapter 단위 테스트 — TEI /rerank 응답을 MockRestServiceServer로 흉내.
 * index→documentId 매핑, score 내림차순, topK, 폴백을 검증한다.
 */
class LocalBgeRerankerAdapterTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private LocalBgeRerankerAdapter adapter;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        adapter = new LocalBgeRerankerAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "baseUrl", "http://localhost:8081");
    }

    @Test
    void rerank_mapsIndexToDocumentId_sortsByScoreDesc() {
        List<RerankCandidate> candidates = List.of(
            new RerankCandidate(100L, "질병 예방 표시 광고"),
            new RerankCandidate(200L, "허위 과장 광고"),
            new RerankCandidate(300L, "의약품 오인 표시")
        );
        String teiResponse = """
            [ {"index": 2, "score": 0.95}, {"index": 0, "score": 0.80}, {"index": 1, "score": 0.10} ]
            """;
        server.expect(requestTo("http://localhost:8081/rerank"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.query").value("당뇨에 좋은 광고"))
            .andExpect(jsonPath("$.texts").isArray())
            .andRespond(withSuccess(teiResponse, MediaType.APPLICATION_JSON));

        List<RerankedResult> results = adapter.rerank("당뇨에 좋은 광고", candidates, 3);

        server.verify();
        assertEquals(3, results.size());
        assertEquals(300L, results.get(0).documentId());
        assertEquals(1, results.get(0).newRank());
        assertEquals(0.95f, results.get(0).relevanceScore(), 0.0001f);
        assertEquals(100L, results.get(1).documentId());
        assertEquals(200L, results.get(2).documentId());
    }

    @Test
    void rerank_respectsTopK() {
        List<RerankCandidate> candidates = List.of(
            new RerankCandidate(1L, "a"), new RerankCandidate(2L, "b"), new RerankCandidate(3L, "c"));
        server.expect(requestTo("http://localhost:8081/rerank"))
            .andRespond(withSuccess(
                "[{\"index\":0,\"score\":0.9},{\"index\":1,\"score\":0.5},{\"index\":2,\"score\":0.1}]",
                MediaType.APPLICATION_JSON));

        List<RerankedResult> results = adapter.rerank("q", candidates, 2);

        assertEquals(2, results.size());
        assertEquals(1L, results.get(0).documentId());
        assertEquals(2L, results.get(1).documentId());
    }

    @Test
    void rerank_fallsBackToOriginalOrder_onError() {
        List<RerankCandidate> candidates = List.of(
            new RerankCandidate(10L, "a"), new RerankCandidate(20L, "b"));
        server.expect(requestTo("http://localhost:8081/rerank")).andRespond(withServerError());

        List<RerankedResult> results = adapter.rerank("q", candidates, 5);

        assertEquals(2, results.size());
        assertEquals(10L, results.get(0).documentId());
        assertEquals(20L, results.get(1).documentId());
    }

    @Test
    void rerank_returnsEmpty_forBlankQueryOrNoCandidates() {
        assertTrue(adapter.rerank("", List.of(new RerankCandidate(1L, "a")), 5).isEmpty());
        assertTrue(adapter.rerank("q", List.of(), 5).isEmpty());
        assertTrue(adapter.rerank(null, List.of(new RerankCandidate(1L, "a")), 5).isEmpty());
    }
}
