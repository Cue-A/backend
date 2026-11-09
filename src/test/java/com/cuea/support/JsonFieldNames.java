package com.cuea.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** 프론트로 나가는 JSON 에 snake_case 키가 섞이지 않았는지 볼 때 씁니다. */
public final class JsonFieldNames {

    private JsonFieldNames() {
    }

    /** 안쪽 객체 · 배열까지 키 이름만 모읍니다. 값(GAZE_FAILED 등)은 빼서 밑줄이 있어도 걸리지 않습니다. */
    public static List<String> of(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                names.add(entry.getKey());
                names.addAll(of(entry.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(child -> names.addAll(of(child)));
        }
        return names;
    }
}
