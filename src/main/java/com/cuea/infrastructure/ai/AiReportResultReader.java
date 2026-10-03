package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 저장해 둔 리포트 원본({@code report.report_data})을 {@link AiReportResult} 로 읽습니다.
 *
 * <p>AI 리포트 JSON 의 snake_case 키를 해석하는 곳을 {@code infrastructure/ai} 안에
 * 두려고 따로 뺐습니다. 도메인은 원본 {@code Map} 을 넘기고 레코드를 받습니다.
 */
@Component
public class AiReportResultReader {

    private final ObjectMapper objectMapper;

    /** AI 가 키를 늘려도 상세 조회가 깨지지 않도록 모르는 키는 무시합니다. */
    public AiReportResultReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * @throws BusinessException 원본이 계약 모양이 아니면 {@code UNEXPECTED_AI_RESPONSE}
     */
    public AiReportResult read(Map<String, Object> reportData) {
        try {
            return objectMapper.convertValue(reportData, AiReportResult.class);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "저장된 리포트를 해석할 수 없습니다", e);
        }
    }
}
