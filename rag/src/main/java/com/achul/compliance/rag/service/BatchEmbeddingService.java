package com.achul.compliance.rag.service;

import com.achul.compliance.infra.persistence.entity.RegulationEntity;
import com.achul.compliance.infra.persistence.repository.RegulationRepository;
import com.achul.compliance.rag.port.EmbeddingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * P1-5: 배치 임베딩 서비스.
 * 미임베딩 규정 청크를 Upstage API로 임베딩하고 DB에 저장.
 */
@Slf4j
@Service
public class BatchEmbeddingService {

    private final RegulationRepository regulationRepository;
    private final EmbeddingPort embeddingPort;

    @Value("${upstage.batch-size:20}")
    private int batchSize;

    public BatchEmbeddingService(
        RegulationRepository regulationRepository,
        EmbeddingPort embeddingPort
    ) {
        this.regulationRepository = regulationRepository;
        this.embeddingPort = embeddingPort;
    }

    /**
     * 미임베딩 규정 청크를 배치로 임베딩.
     *
     * @return 임베딩된 건수
     */
    @Transactional
    public int embedMissingRegulations() {
        log.info("Starting batch embedding for missing regulations...");
        long startTime = System.currentTimeMillis();

        try {
            List<RegulationEntity> notEmbedded = regulationRepository.findNotEmbeddedRegulations();
            log.info("Found {} regulations without embeddings", notEmbedded.size());

            if (notEmbedded.isEmpty()) {
                log.info("No regulations to embed");
                return 0;
            }

            int totalEmbedded = 0;
            int totalFailed = 0;

            // 배치 처리
            for (int i = 0; i < notEmbedded.size(); i += batchSize) {
                int end = Math.min(i + batchSize, notEmbedded.size());
                List<RegulationEntity> batch = notEmbedded.subList(i, end);

                log.debug("Processing batch {}/{} (size: {})",
                    i / batchSize + 1,
                    (notEmbedded.size() + batchSize - 1) / batchSize,
                    batch.size());

                try {
                    List<String> texts = batch.stream()
                        .map(RegulationEntity::getChunkText)
                        .toList();

                    List<float[]> embeddings = embeddingPort.embedDocuments(texts);

                    if (embeddings.size() == batch.size()) {
                        for (int j = 0; j < batch.size(); j++) {
                            batch.get(j).setEmbedding(embeddings.get(j));
                        }
                        regulationRepository.saveAll(batch);
                        totalEmbedded += batch.size();
                        log.debug("Successfully embedded batch of {} regulations", batch.size());
                    } else {
                        log.warn("Embedding count mismatch. Expected: {}, Got: {}",
                            batch.size(), embeddings.size());
                        totalFailed += batch.size();
                    }

                } catch (Exception e) {
                    log.error("Failed to embed batch starting at index {}", i, e);
                    totalFailed += batch.size();
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Batch embedding completed in {}ms. Embedded: {}, Failed: {}",
                elapsed, totalEmbedded, totalFailed);

            return totalEmbedded;

        } catch (Exception e) {
            log.error("Batch embedding failed", e);
            throw new RuntimeException("Batch embedding failed", e);
        }
    }

    /**
     * 특정 규정들을 선택적으로 임베딩.
     */
    @Transactional
    public int embedRegulations(List<Long> regulationIds) {
        if (regulationIds == null || regulationIds.isEmpty()) {
            return 0;
        }

        log.info("Starting selective embedding for {} regulations", regulationIds.size());

        try {
            List<RegulationEntity> toEmbed = regulationRepository.findAllById(regulationIds);
            if (toEmbed.isEmpty()) {
                log.warn("No regulations found with given IDs");
                return 0;
            }

            int totalEmbedded = 0;

            for (int i = 0; i < toEmbed.size(); i += batchSize) {
                int end = Math.min(i + batchSize, toEmbed.size());
                List<RegulationEntity> batch = toEmbed.subList(i, end);

                try {
                    List<String> texts = batch.stream()
                        .map(RegulationEntity::getChunkText)
                        .toList();

                    List<float[]> embeddings = embeddingPort.embedDocuments(texts);

                    for (int j = 0; j < batch.size(); j++) {
                        batch.get(j).setEmbedding(embeddings.get(j));
                    }
                    regulationRepository.saveAll(batch);
                    totalEmbedded += batch.size();

                } catch (Exception e) {
                    log.error("Failed to embed batch starting at index {}", i, e);
                }
            }

            log.info("Selective embedding completed. Embedded: {}", totalEmbedded);
            return totalEmbedded;

        } catch (Exception e) {
            log.error("Selective embedding failed", e);
            throw new RuntimeException("Selective embedding failed", e);
        }
    }

    /**
     * 임베딩 상태 조회.
     */
    public EmbeddingStats getEmbeddingStats() {
        long total = regulationRepository.count();
        long embedded = regulationRepository.countByEmbeddingIsNotNull();
        long notEmbedded = total - embedded;

        return new EmbeddingStats(total, embedded, notEmbedded);
    }

    public record EmbeddingStats(long total, long embedded, long notEmbedded) {}
}
