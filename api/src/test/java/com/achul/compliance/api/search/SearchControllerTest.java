package com.achul.compliance.api.search;

import com.achul.compliance.rag.HybridSearchService;
import com.achul.compliance.rag.dto.HybridScore;
import com.achul.compliance.rag.dto.RerankedResult;
import com.achul.compliance.rag.port.KeywordSearchPort;
import com.achul.compliance.rag.port.VectorSearchPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG-005: 검색 API 통합 테스트.
 *
 * <p>{@link SearchController}만 슬라이스 로드하고, 외부 의존성({@link HybridSearchService})은 모킹한다.
 * 임베딩/검색/리랭킹 파이프라인 단위 테스트는 별도 모듈({@code rag})의 책임이다.
 *
 * <p>검증 범위:
 * <ul>
 *   <li>요청 JSON({@code query}, {@code top_k}) 파싱</li>
 *   <li>응답 JSON 형식: {@code query}, {@code results[]}, {@code execution_time_ms}</li>
 *   <li>각 결과 항목 필드: {@code law}, {@code article}, {@code text}, {@code relevance_score}</li>
 *   <li>3개 샘플 쿼리에 대한 정상 응답</li>
 *   <li>응답 시간 &lt; 500ms (mock 환경 기준)</li>
 *   <li>유효성: 빈 쿼리/범위 초과 top_k → 400</li>
 * </ul>
 */
@WebMvcTest(SearchController.class)
class SearchControllerTest {

    private static final long RESPONSE_TIME_BUDGET_MS = 500L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HybridSearchService hybridSearchService;

    @Test
    @DisplayName("샘플 쿼리 1: 식품 광고 컴플라이언스 - 정상 응답 & 형식 검증 & 500ms 미만")
    void search_foodAdvertisementQuery_returnsExpectedJsonShape() throws Exception {
        String query = "이 상품은 당뇨병을 예방합니다";
        given(hybridSearchService.search(eq(query), anyInt()))
            .willReturn(List.of(
                rerankedResult(101L, "식품 등의 표시·광고에 관한 법률",
                    "제8조", "제1항", "제1호",
                    "질병의 예방·치료에 효능이 있는 것으로 인식할 우려가 있는 표시·광고",
                    0.93f, 1),
                rerankedResult(102L, "건강기능식품에 관한 법률",
                    "제18조", "제1항", null,
                    "허위·과대광고 금지 조항",
                    0.81f, 2)
            ));

        MvcResult result = mockMvc.perform(post("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "top_k", 10
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.query").value(query))
            .andExpect(jsonPath("$.results").isArray())
            .andExpect(jsonPath("$.results.length()").value(2))
            .andExpect(jsonPath("$.results[0].law").value("식품 등의 표시·광고에 관한 법률"))
            .andExpect(jsonPath("$.results[0].article").value("제8조 제1항 제1호"))
            .andExpect(jsonPath("$.results[0].text").exists())
            .andExpect(jsonPath("$.results[0].relevance_score").value(0.93))
            .andExpect(jsonPath("$.results[1].article").value("제18조 제1항"))
            .andExpect(jsonPath("$.execution_time_ms").isNumber())
            .andReturn();

        long elapsed = elapsedMillis(result);
        assertThat(elapsed)
            .as("응답 시간이 %dms 이하여야 한다", RESPONSE_TIME_BUDGET_MS)
            .isLessThan(RESPONSE_TIME_BUDGET_MS);
    }

    @Test
    @DisplayName("샘플 쿼리 2: 화장품 광고 - 키워드 매칭 결과만 있는 경우")
    void search_cosmeticAdvertisementQuery_returnsKeywordOnlyResults() throws Exception {
        String query = "주름이 사라지는 기적의 크림";
        given(hybridSearchService.search(eq(query), anyInt()))
            .willReturn(List.of(
                rerankedResultKeywordOnly(201L, "화장품법",
                    "제13조", null, null,
                    "의약품으로 잘못 인식할 우려가 있는 표시·광고 금지",
                    0.87f, 1)
            ));

        MvcResult result = mockMvc.perform(post("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "top_k", 5
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.query").value(query))
            .andExpect(jsonPath("$.results.length()").value(1))
            .andExpect(jsonPath("$.results[0].law").value("화장품법"))
            .andExpect(jsonPath("$.results[0].article").value("제13조"))
            .andExpect(jsonPath("$.results[0].relevance_score").value(0.87))
            .andReturn();

        assertThat(elapsedMillis(result)).isLessThan(RESPONSE_TIME_BUDGET_MS);
    }

    @Test
    @DisplayName("샘플 쿼리 3: 의료기기 광고 - top_k 미지정 시 기본값 10 사용")
    void search_medicalDeviceQuery_usesDefaultTopK() throws Exception {
        String query = "이 마사지기는 모든 통증을 완치시킵니다";
        given(hybridSearchService.search(eq(query), eq(SearchRequest.DEFAULT_TOP_K)))
            .willReturn(List.of(
                rerankedResult(301L, "의료기기법",
                    "제24조", null, null,
                    "거짓이나 과대 광고의 금지",
                    0.95f, 1),
                rerankedResult(302L, "표시·광고의 공정화에 관한 법률",
                    "제3조", "제1항", "제1호",
                    "부당한 표시·광고 행위의 금지",
                    0.78f, 2),
                rerankedResult(303L, "의료기기법 시행규칙",
                    "별표8", null, null,
                    "광고 심의기준",
                    0.62f, 3)
            ));

        MvcResult result = mockMvc.perform(post("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "query", query
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.query").value(query))
            .andExpect(jsonPath("$.results.length()").value(3))
            .andExpect(jsonPath("$.results[0].law").value("의료기기법"))
            .andExpect(jsonPath("$.results[2].article").value("별표8"))
            .andReturn();

        assertThat(elapsedMillis(result)).isLessThan(RESPONSE_TIME_BUDGET_MS);
    }

    @Test
    @DisplayName("빈 쿼리는 400 Bad Request")
    void search_blankQuery_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "query", "",
                    "top_k", 10
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("top_k 범위 초과(>20)는 400 Bad Request")
    void search_topKOutOfRange_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "query", "테스트 질의",
                    "top_k", 999
                ))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("결과가 없어도 정상 응답(빈 배열)")
    void search_noResults_returnsEmptyArray() throws Exception {
        String query = "관련 규정이 없는 질의";
        given(hybridSearchService.search(eq(query), anyInt())).willReturn(List.of());

        mockMvc.perform(post("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "top_k", 10
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results").isArray())
            .andExpect(jsonPath("$.results.length()").value(0))
            .andExpect(jsonPath("$.execution_time_ms").isNumber());
    }

    /* ------------------------------------------------------------------
     * Test fixtures
     * ------------------------------------------------------------------ */

    /** 벡터 결과 메타데이터를 가진 {@link RerankedResult} 생성. */
    private static RerankedResult rerankedResult(
        long regulationId,
        String lawName,
        String articleNumber,
        String paragraphNumber,
        String itemNumber,
        String chunkText,
        float rerankScore,
        int rank
    ) {
        VectorSearchPort.VectorSearchResult vector = new VectorSearchPort.VectorSearchResult(
            regulationId,
            lawName,
            articleNumber,
            paragraphNumber,
            itemNumber,
            chunkText,
            0.85f,
            "v1.0"
        );
        HybridScore source = new HybridScore(regulationId, 0.6f * 0.85f, 0.0f, vector, null);
        return new RerankedResult(source, rerankScore, rank);
    }

    /** 키워드 결과 메타데이터만 가진 {@link RerankedResult} 생성 (벡터 미매칭 케이스). */
    private static RerankedResult rerankedResultKeywordOnly(
        long regulationId,
        String lawName,
        String articleNumber,
        String paragraphNumber,
        String itemNumber,
        String chunkText,
        float rerankScore,
        int rank
    ) {
        KeywordSearchPort.KeywordSearchResult keyword = new KeywordSearchPort.KeywordSearchResult(
            regulationId,
            lawName,
            articleNumber,
            paragraphNumber,
            itemNumber,
            chunkText,
            12.34f,
            "v1.0"
        );
        HybridScore source = new HybridScore(regulationId, 0.0f, 0.4f, null, keyword);
        return new RerankedResult(source, rerankScore, rank);
    }

    private long elapsedMillis(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
            .path("execution_time_ms")
            .asLong();
    }
}
