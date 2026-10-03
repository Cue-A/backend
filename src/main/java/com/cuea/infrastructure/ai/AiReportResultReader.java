package com.cuea.infrastructure.ai;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.infrastructure.ai.dto.AiReportResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 저장해 둔 리포트 원본({@code report.report_data})을 {@link AiReportResult} 로 읽습니다.
 *
 * <p>AI 리포트 JSON 의 snake_case 키를 해석하는 곳을 {@code infrastructure/ai} 안에
 * 두려고 따로 뺐습니다. 도메인은 원본 {@code Map} 을 넘기고 레코드를 받습니다.
 *
 * <p>저장 전에도 한 번 읽어 봅니다({@link #validate}). 점수만 맞고 나머지 모양이 어긋난
 * 결과를 COMPLETED 로 저장하면, 상세 조회는 영영 실패하는데 재요청은 FAILED 만 받으므로
 * 사용자가 빠져나올 길이 없습니다. 저장 전에 걸러 FAILED 로 남깁니다.
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
        return convert(reportData);
    }

    /**
     * 폴링으로 받은 결과가 상세 조회에서 읽을 수 있는 모양인지 저장 전에 확인합니다.
     *
     * @throws BusinessException 계약 모양이 아니면 {@code UNEXPECTED_AI_RESPONSE}
     */
    public void validate(JsonNode result) {
        convert(result);
    }

    private AiReportResult convert(Object source) {
        try {
            return objectMapper.convertValue(source, AiReportResult.class);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNEXPECTED_AI_RESPONSE, "리포트 결과를 해석할 수 없습니다", e);
        }
    }
}
