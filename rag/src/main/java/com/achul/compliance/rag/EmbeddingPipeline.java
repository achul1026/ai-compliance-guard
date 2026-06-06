package com.achul.compliance.rag;

import com.achul.compliance.infra.persistence.entity.RegulationEntity;
import com.achul.compliance.infra.persistence.repository.RegulationRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * P1-5 (RAG-002): JSONL 청크 → DB 적재 → Upstage 임베딩 → 벡터 저장 파이프라인.
 *
 * 처리 단계:
 * <ol>
 *   <li>{@code _workspace/regulations_chunks.jsonl} 라인별 파싱</li>
 *   <li>중복(id 기준) 스킵 + 신규 청크만 regulations 테이블 INSERT (멱등)</li>
 *   <li>임베딩 컬럼이 NULL 인 청크를 배치(100~200)로 Upstage 호출</li>
 *   <li>UPDATE regulations SET embedding = ? WHERE id = ?</li>
 *   <li>완료 후 통계 + 샘플 코사인 유사도 검증 결과 반환</li>
 * </ol>
 *
 * 재실행 멱등성:
 *   - JSONL 적재: 동일 (law_name, article_number, paragraph_number, item_number, chunk_text) 조합이 이미 있으면 스킵
 *   - 임베딩: embedding IS NULL 청크만 대상으로 하여 재실행해도 중복 호출 없음
 *
 * 에러 처리:
 *   - 배치 단위 try/catch: 한 배치 실패해도 다음 배치 계속
 *   - Upstage 호출은 {@link UpstageEmbeddingClient}의 RestClientException 을 전파받아 재시도 (지수 백오프)
 */
@Slf4j
@Component
public class EmbeddingPipeline {

    private final RegulationRepository regulationRepository;
    private final UpstageEmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${pipeline.chunks-jsonl-path:_workspace/regulations_chunks.jsonl}")
    private String chunksJsonlPath;

    @Value("${pipeline.embedding-batch-size:100}")
    private int embeddingBatchSize;

    @Value("${pipeline.max-retries:3}")
    private int maxRetries;

    @Value("${pipeline.source-tag:DATA-002}")
    private String sourceTag;

    public EmbeddingPipeline(
        RegulationRepository regulationRepository,
        UpstageEmbeddingClient embeddingClient
    ) {
        this.regulationRepository = regulationRepository;
        this.embeddingClient = embeddingClient;
    }

    /**
     * 전체 파이프라인 실행: JSONL 적재 → 임베딩 생성 → DB 저장.
     */
    @Transactional
    public PipelineResult run() {
        return run(Path.of(chunksJsonlPath));
    }

    /**
     * 외부에서 경로를 직접 지정할 때 사용 (테스트 또는 임시 파일).
     */
    @Transactional
    public PipelineResult run(Path jsonlPath) {
        long startMs = System.currentTimeMillis();

        // 1) JSONL 로드 + 적재
        IngestResult ingest = ingestJsonl(jsonlPath);
        log.info("JSONL ingest done. parsed={}, inserted={}, skipped={}",
            ingest.parsed, ingest.inserted, ingest.skipped);

        // 2) 임베딩 생성
        EmbeddingRunResult embedRun = embedMissing();
        log.info("Embedding run done. embedded={}, failed={}, batches={}",
            embedRun.embedded, embedRun.failed, embedRun.batches);

        long elapsedMs = System.currentTimeMillis() - startMs;
        long total = regulationRepository.count();
        long embedded = regulationRepository.countByEmbeddingIsNotNull();

        return new PipelineResult(
            ingest.parsed,
            ingest.inserted,
            ingest.skipped,
            embedRun.embedded,
            embedRun.failed,
            embedRun.batches,
            total,
            embedded,
            elapsedMs,
            embeddingClient.dimension(),
            embeddingClient.modelName()
        );
    }

    // -----------------------------------------------------------------
    //  단계 1: JSONL → DB INSERT
    // -----------------------------------------------------------------

    @Transactional
    public IngestResult ingestJsonl(Path jsonlPath) {
        if (!Files.exists(jsonlPath)) {
            throw new IllegalStateException("JSONL 파일을 찾을 수 없습니다: " + jsonlPath.toAbsolutePath());
        }

        int parsed = 0;
        int inserted = 0;
        int skipped = 0;

        try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                parsed++;

                ChunkRecord rec;
                try {
                    rec = objectMapper.readValue(line, ChunkRecord.class);
                } catch (IOException parseEx) {
                    log.warn("JSONL 라인 파싱 실패 (line {}): {}", parsed, parseEx.getMessage());
                    continue;
                }

                if (rec.text == null || rec.text.isBlank()) {
                    log.debug("빈 텍스트 청크 스킵: id={}", rec.id);
                    continue;
                }

                String lawName = nullSafe(rec.lawName, "(미상)");
                String articleNumber = nullSafe(rec.article, "STUB");
                String paragraphNumber = rec.section;
                String itemNumber = rec.subsection;

                // 멱등성: 동일 청크(law_name + article + paragraph + item + text) 이미 존재 시 스킵
                boolean exists = regulationRepository
                    .findByLawNameAndArticleNumber(lawName, articleNumber).stream()
                    .anyMatch(r -> sameText(r.getChunkText(), rec.text)
                        && eq(r.getParagraphNumber(), paragraphNumber)
                        && eq(r.getItemNumber(), itemNumber));
                if (exists) {
                    skipped++;
                    continue;
                }

                RegulationEntity entity = new RegulationEntity();
                entity.setSource(sourceTag + ":" + nullSafe(rec.parser, "unknown"));
                entity.setLawName(lawName);
                entity.setArticleNumber(articleNumber);
                entity.setParagraphNumber(paragraphNumber);
                entity.setItemNumber(itemNumber);
                entity.setChunkText(rec.text);
                entity.setEffectiveDate(parseDate(rec.lastRevised, rec.enactmentDate));
                entity.setVersion(nullSafe(rec.lastRevised, nullSafe(rec.enactmentDate, "unversioned")));
                entity.setMetadata(buildMetadataJson(rec));
                regulationRepository.save(entity);
                inserted++;
            }
        } catch (IOException e) {
            throw new RuntimeException("JSONL 읽기 실패: " + jsonlPath, e);
        }

        return new IngestResult(parsed, inserted, skipped);
    }

    // -----------------------------------------------------------------
    //  단계 2: 임베딩 생성 + UPDATE
    // -----------------------------------------------------------------

    @Transactional
    public EmbeddingRunResult embedMissing() {
        List<RegulationEntity> targets = regulationRepository.findNotEmbeddedRegulations();
        if (targets.isEmpty()) {
            log.info("임베딩 대상 없음 (모두 채워짐).");
            return new EmbeddingRunResult(0, 0, 0);
        }
        log.info("임베딩 대상 {} 건, 배치 크기 {}", targets.size(), embeddingBatchSize);

        int embedded = 0;
        int failed = 0;
        int batches = 0;

        for (int i = 0; i < targets.size(); i += embeddingBatchSize) {
            int end = Math.min(i + embeddingBatchSize, targets.size());
            List<RegulationEntity> batch = targets.subList(i, end);
            batches++;

            try {
                List<float[]> vectors = callWithRetry(
                    batch.stream().map(RegulationEntity::getChunkText).toList());

                if (vectors.size() != batch.size()) {
                    log.warn("배치 {}: 벡터 개수 불일치 (요청 {}, 응답 {}). 스킵.",
                        batches, batch.size(), vectors.size());
                    failed += batch.size();
                    continue;
                }

                for (int j = 0; j < batch.size(); j++) {
                    batch.get(j).setEmbedding(vectors.get(j));
                }
                regulationRepository.saveAll(batch);
                embedded += batch.size();
                log.info("배치 {} 완료: {} 건 임베딩 저장", batches, batch.size());

            } catch (Exception e) {
                log.error("배치 {} 실패 (range {}~{}): {}", batches, i, end - 1, e.getMessage(), e);
                failed += batch.size();
            }
        }
        return new EmbeddingRunResult(embedded, failed, batches);
    }

    private List<float[]> callWithRetry(List<String> texts) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return embeddingClient.embedBatch(texts);
            } catch (RestClientException e) {
                last = e;
                long backoffMs = (long) Math.pow(2, attempt) * 500L;
                log.warn("Upstage 호출 실패 (시도 {}/{}). {}ms 후 재시도. msg={}",
                    attempt, maxRetries, backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("임베딩 재시도 중 인터럽트", ie);
                }
            }
        }
        throw new RuntimeException("Upstage 호출 " + maxRetries + "회 실패", last);
    }

    // -----------------------------------------------------------------
    //  단계 3: 샘플 유사도 검증
    // -----------------------------------------------------------------

    /**
     * 임베딩 적재 직후 샘플 검증.
     * 무작위 시드 청크 N개에 대해 코사인 유사도 상위 5개를 계산하여 반환.
     * 외부 DB 함수에 의존하지 않고 메모리에서 brute-force 계산 (127 × 4096 → 수십 ms).
     */
    public List<SampleSimilarity> verifySimilarity(int sampleCount, int topK) {
        List<RegulationEntity> all = regulationRepository.findAll().stream()
            .filter(r -> r.getEmbedding() != null && r.getEmbedding().length > 0)
            .toList();
        if (all.isEmpty()) {
            log.warn("검증 대상 임베딩 없음.");
            return List.of();
        }

        int step = Math.max(1, all.size() / Math.max(1, sampleCount));
        List<SampleSimilarity> results = new ArrayList<>();
        for (int i = 0; i < all.size() && results.size() < sampleCount; i += step) {
            RegulationEntity seed = all.get(i);
            float[] seedVec = seed.getEmbedding();

            List<SimilarHit> hits = all.stream()
                .filter(r -> !r.getId().equals(seed.getId()))
                .map(r -> new SimilarHit(
                    r.getId(),
                    r.getLawName(),
                    r.getArticleNumber(),
                    truncate(r.getChunkText(), 80),
                    cosine(seedVec, r.getEmbedding())))
                .sorted(Comparator.comparingDouble(SimilarHit::score).reversed())
                .limit(topK)
                .toList();

            results.add(new SampleSimilarity(
                seed.getId(),
                seed.getLawName(),
                seed.getArticleNumber(),
                truncate(seed.getChunkText(), 80),
                hits));
        }
        return results;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // -----------------------------------------------------------------
    //  유틸
    // -----------------------------------------------------------------

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String nullSafe(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static boolean sameText(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static LocalDate parseDate(String primary, String fallback) {
        for (String s : List.of(nullSafe(primary, ""), nullSafe(fallback, ""))) {
            if (s.isBlank()) {
                continue;
            }
            try {
                return LocalDate.parse(s);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    private String buildMetadataJson(ChunkRecord rec) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (rec.id != null) meta.put("chunk_id", rec.id);
        if (rec.sourceFile != null) meta.put("source_file", rec.sourceFile);
        if (rec.parser != null) meta.put("parser", rec.parser);
        if (rec.tokens != null) meta.put("tokens", rec.tokens);
        if (rec.order != null) meta.put("order", rec.order);
        if (rec.item != null) meta.put("item", rec.item);
        if (rec.notes != null) meta.put("notes", rec.notes);
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return "{}";
        }
    }

    // -----------------------------------------------------------------
    //  결과/입력 DTO
    // -----------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChunkRecord {
        @JsonProperty("id")           public String id;
        @JsonProperty("law_name")     public String lawName;
        @JsonProperty("article")      public String article;
        @JsonProperty("section")      public String section;
        @JsonProperty("subsection")   public String subsection;
        @JsonProperty("item")         public String item;
        @JsonProperty("text")         public String text;
        @JsonProperty("source_file")  public String sourceFile;
        @JsonProperty("enactment_date") public String enactmentDate;
        @JsonProperty("last_revised") public String lastRevised;
        @JsonProperty("tokens")       public Integer tokens;
        @JsonProperty("order")        public Integer order;
        @JsonProperty("parser")       public String parser;
        @JsonProperty("notes")        public String notes;
    }

    public record IngestResult(int parsed, int inserted, int skipped) {}

    public record EmbeddingRunResult(int embedded, int failed, int batches) {}

    public record PipelineResult(
        int jsonlParsed,
        int dbInserted,
        int dbSkipped,
        int embedded,
        int embedFailed,
        int batches,
        long totalRegulationsInDb,
        long embeddedRegulationsInDb,
        long elapsedMs,
        int vectorDimension,
        String modelName
    ) {
        public Map<String, Object> toReport() {
            Map<String, Object> m = new HashMap<>();
            m.put("jsonl_parsed", jsonlParsed);
            m.put("db_inserted", dbInserted);
            m.put("db_skipped", dbSkipped);
            m.put("embedded", embedded);
            m.put("embed_failed", embedFailed);
            m.put("batches", batches);
            m.put("total_in_db", totalRegulationsInDb);
            m.put("embedded_in_db", embeddedRegulationsInDb);
            m.put("elapsed_ms", elapsedMs);
            m.put("vector_dimension", vectorDimension);
            m.put("model_name", modelName);
            return m;
        }
    }

    public record SampleSimilarity(
        Long seedId,
        String seedLaw,
        String seedArticle,
        String seedTextPreview,
        List<SimilarHit> top
    ) {
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("### seed#").append(seedId).append(" — ")
              .append(seedLaw).append(" / ").append(seedArticle).append("\n");
            sb.append("> ").append(seedTextPreview).append("\n\n");
            sb.append("| rank | id | law | article | cosine | preview |\n");
            sb.append("|---:|---:|---|---|---:|---|\n");
            int rank = 1;
            for (SimilarHit h : top) {
                sb.append("| ").append(rank++).append(" | ")
                  .append(h.id).append(" | ")
                  .append(h.lawName).append(" | ")
                  .append(h.articleNumber).append(" | ")
                  .append(String.format("%.4f", h.score)).append(" | ")
                  .append(h.preview.replace("\n", " ").replace("|", "\\|")).append(" |\n");
            }
            return sb.toString();
        }
    }

    public record SimilarHit(
        Long id,
        String lawName,
        String articleNumber,
        String preview,
        double score
    ) {}

    /**
     * 최근 N건의 청크에 대한 도메인별 통계 산출.
     */
    public Map<String, Object> buildStats() {
        long total = regulationRepository.count();
        long embedded = regulationRepository.countByEmbeddingIsNotNull();
        long notEmbedded = total - embedded;

        Map<String, Long> byLaw = regulationRepository.findAll().stream()
            .collect(Collectors.groupingBy(
                r -> r.getLawName() == null ? "(미상)" : r.getLawName(),
                Collectors.counting()));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", total);
        stats.put("embedded", embedded);
        stats.put("not_embedded", notEmbedded);
        stats.put("embedding_rate",
            total == 0 ? "N/A" : String.format("%.1f%%", 100.0 * embedded / total));
        stats.put("dimension", embeddingClient.dimension());
        stats.put("model_name", embeddingClient.modelName());
        stats.put("by_law", byLaw);
        return stats;
    }
}
