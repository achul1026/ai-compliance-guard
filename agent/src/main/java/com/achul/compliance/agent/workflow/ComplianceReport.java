package com.achul.compliance.agent.workflow;

import com.achul.compliance.agent.domain.AuditReport;
import com.achul.compliance.agent.domain.RegulationSnippet;
import com.achul.compliance.agent.domain.RemediationResult;

import java.util.List;

/**
 * P2-5: 워크플로우 최종 산출물 — 컴플라이언스 리포트.
 *
 * @param adCopy            검사한 광고 카피 원문
 * @param audit             비판관 검증을 거친 최종 위반 보고서
 * @param remediation       위반 구절별 대체 문구
 * @param regulations       검사에 사용된 근거 규정 (감사 추적용)
 * @param iterations        검사관 실행 횟수 (1 = 루프 없이 통과)
 * @param critiquePassed    비판관 최종 통과 여부 (false = 반복 상한 도달로 마지막 보고서 채택)
 * @param model             사용 LLM 식별자
 */
public record ComplianceReport(
    String adCopy,
    AuditReport audit,
    RemediationResult remediation,
    List<RegulationSnippet> regulations,
    int iterations,
    boolean critiquePassed,
    String model
) {}
