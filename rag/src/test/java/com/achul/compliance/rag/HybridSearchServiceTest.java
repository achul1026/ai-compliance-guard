package com.achul.compliance.rag;

import com.achul.compliance.rag.dto.HybridScore;
import com.achul.compliance.rag.dto.RerankedResult;
import com.achul.compliance.rag.port.EmbeddingPort;
import com.achul.compliance.rag.port.KeywordSearchPort;
import com.achul.compliance.rag.port.RerankerPort;
import com.achul.compliance.rag.port.VectorSearchPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG-004 결합 로직 단위 테스트.
 *
 * <p>외부 의존(벡터DB·BM25·Reranker)을 인메모리 가짜 구현으로 대체해
 * 가중합 정규화 결과를 결정적으로 검증한다.</p>
 */
class HybridSearchServiceTest {

    @Test
    void combine_appliesVectorWeight06AndKeywordWeight04() {
        FakeVector vector = new FakeVector();
        FakeKeyword keyword = new FakeKeyword();
        FakeReranker reranker = new FakeReranker();
        FakeEmbedding embedding = new FakeEmbedding();

        // doc1: 벡터에만 매칭 (similarity 1.0) → 점수 = 1.0 * 0.6 = 0.6
        vector.add(new VectorSearchPort.VectorSearchResult(
            1L, "식품표시광고법", "제8조", "1", null, "질병 예방 표시 광고 금지", 1.0f, "v1"));
        // doc2: BM25에만 매칭 (raw 10.0, max=10.0 → 정규화 1.0) → 점수 = 1.0 * 0.4 = 0.4
        keyword.add(new KeywordSearchPort.KeywordSearchResult(
            2L, "건강기능식품법", "제18조", null, null, "허위 광고 금지", 10.0f, "v1"));
        // doc3: 양쪽 모두 매칭 (vec 0.5, bm25 5.0 → 정규화 0.5) → 0.5*0.6 + 0.5*0.4 = 0.5
        vector.add(new VectorSearchPort.VectorSearchResult(
            3L, "약사법", "제68조", "1", null, "의약품 오인 표시 금지", 0.5f, "v1"));
        keyword.add(new KeywordSearchPort.KeywordSearchResult(
            3L, "약사법", "제68조", "1", null, "의약품 오인 표시 금지", 5.0f, "v1"));

        HybridSearchService svc = new HybridSearchService(vector, keyword, reranker, embedding);
        List<HybridScore> combined = svc.combine(vector.results, keyword.results);

        // 결과 검증
        HybridScore doc1 = findById(combined, 1L);
        HybridScore doc2 = findById(combined, 2L);
        HybridScore doc3 = findById(combined, 3L);

        assertEquals(0.6f, doc1.totalScore(), 0.0001f, "vector-only weight");
        assertEquals(0.4f, doc2.totalScore(), 0.0001f, "keyword-only weight");
        assertEquals(0.5f, doc3.totalScore(), 0.0001f, "both paths summed");
        assertEquals("both", doc3.matchedBy());
        assertEquals("vector", doc1.matchedBy());
        assertEquals("keyword", doc2.matchedBy());
    }

    @Test
    void search_combinesAndReranks_returnsTopN() {
        FakeVector vector = new FakeVector();
        FakeKeyword keyword = new FakeKeyword();
        FakeReranker reranker = new FakeReranker();
        FakeEmbedding embedding = new FakeEmbedding();

        // 3개 후보를 모두 양쪽에 등록
        for (long id = 1; id <= 3; id++) {
            vector.add(new VectorSearchPort.VectorSearchResult(
                id, "법" + id, "제" + id + "조", null, null, "내용 " + id, 0.9f - id * 0.1f, "v1"));
            keyword.add(new KeywordSearchPort.KeywordSearchResult(
                id, "법" + id, "제" + id + "조", null, null, "내용 " + id, 10f - id, "v1"));
        }
        // Reranker는 입력 순서를 뒤집어 점수 부여 (id=3을 최상위로)
        reranker.reverseRank = true;

        HybridSearchService svc = new HybridSearchService(vector, keyword, reranker, embedding);
        List<RerankedResult> top = svc.search("test", 2);

        assertEquals(2, top.size(), "topN respected");
        assertEquals(1, top.get(0).rank());
        assertEquals(2, top.get(1).rank());
        // Reranker가 역순 부여 → 첫번째 결과의 regulationId가 후순위였던 것
        assertTrue(top.get(0).regulationId() >= top.get(1).regulationId());
    }

    @Test
    void search_fallbackToHybridOrder_whenRerankerReturnsEmpty() {
        FakeVector vector = new FakeVector();
        FakeKeyword keyword = new FakeKeyword();
        FakeReranker reranker = new FakeReranker();
        FakeEmbedding embedding = new FakeEmbedding();
        reranker.returnEmpty = true;

        vector.add(new VectorSearchPort.VectorSearchResult(
            1L, "법1", "제1조", null, null, "낮은", 0.3f, "v1"));
        vector.add(new VectorSearchPort.VectorSearchResult(
            2L, "법2", "제2조", null, null, "높은", 0.9f, "v1"));

        HybridSearchService svc = new HybridSearchService(vector, keyword, reranker, embedding);
        List<RerankedResult> top = svc.search("test", 5);

        assertFalse(top.isEmpty());
        // 폴백 시 totalScore 내림차순 → doc2(0.54)가 doc1(0.18)보다 앞
        assertEquals(2L, top.get(0).regulationId());
        assertEquals(1L, top.get(1).regulationId());
    }

    @Test
    void search_blankQueryReturnsEmpty() {
        HybridSearchService svc = new HybridSearchService(
            new FakeVector(), new FakeKeyword(), new FakeReranker(), new FakeEmbedding());
        assertTrue(svc.search("").isEmpty());
        assertTrue(svc.search("   ").isEmpty());
        assertTrue(svc.search(null).isEmpty());
    }

    private static HybridScore findById(List<HybridScore> list, long id) {
        return list.stream()
            .filter(s -> s.regulationId() == id)
            .findFirst()
            .orElseThrow();
    }

    // === Fakes ===

    static class FakeVector implements VectorSearchPort {
        final List<VectorSearchResult> results = new ArrayList<>();

        void add(VectorSearchResult r) { results.add(r); }

        @Override
        public List<VectorSearchResult> search(float[] queryEmbedding, int limit) {
            return results.stream().limit(limit).toList();
        }

        @Override
        public List<VectorSearchResult> searchByText(String query, int limit, EmbeddingPort embeddingPort) {
            return results.stream().limit(limit).toList();
        }
    }

    static class FakeKeyword implements KeywordSearchPort {
        final List<KeywordSearchResult> results = new ArrayList<>();

        void add(KeywordSearchResult r) { results.add(r); }

        @Override
        public List<KeywordSearchResult> search(String query, int limit) {
            return results.stream().limit(limit).toList();
        }

        @Override
        public List<KeywordSearchResult> searchWithFilters(String query, String lawName, String articleNumber, int limit) {
            return search(query, limit);
        }
    }

    static class FakeReranker implements RerankerPort {
        boolean reverseRank = false;
        boolean returnEmpty = false;

        @Override
        public List<RerankedResult> rerank(String query, List<RerankCandidate> candidates, int topK) {
            if (returnEmpty) return List.of();
            List<RerankCandidate> ordered = new ArrayList<>(candidates);
            if (reverseRank) {
                java.util.Collections.reverse(ordered);
            }
            List<RerankedResult> out = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, ordered.size()); i++) {
                out.add(new RerankedResult(
                    ordered.get(i).documentId(),
                    1.0f - i * 0.1f,
                    i + 1));
            }
            return out;
        }
    }

    static class FakeEmbedding implements EmbeddingPort {
        @Override public float[] embedQuery(String text) { return new float[] {0.1f}; }
        @Override public List<float[]> embedDocuments(List<String> texts) { return List.of(); }
        @Override public int dimension() { return 1; }
        @Override public String modelName() { return "fake"; }
    }
}
