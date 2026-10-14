package com.cuea.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인증이 필요한 API 에 {@code @CurrentUser} 를 빠뜨리면 그 API 는 조용히 열립니다.
 * {@code JwtAuthFilter} 가 토큰 없는 요청을 통과시키기 때문입니다.
 *
 * <p>그래서 모든 핸들러는 <b>공개({@code @PublicApi})인지 인증({@code @CurrentUser})인지</b>
 * 둘 중 하나를 명시해야 합니다. 새 엔드포인트를 추가하고 아무것도 고르지 않으면
 * 여기서 빌드가 깨집니다. 사람이 잊는 것을 팀 규칙으로 막을 수는 없습니다.
 *
 * <p><b>이 테스트가 잡지 못하는 것:</b> {@code @CurrentUser} 가 붙어 있어도 남의
 * 리소스를 보는지는 검사하지 않습니다. {@code GET /api/sessions/{sessionId}} 에
 * {@code @CurrentUser} 만 있고 소유자 확인이 없으면 여기는 통과합니다.
 * 소유자 검사는 서비스 레이어의 책임입니다.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 classpath 를 직접 스캔합니다.
 * 이 저장소의 테스트는 DB·Redis 없이 돌아야 합니다.
 */
class EndpointAuthGuardTest {

    private static final String BASE_PACKAGE = "com.cuea";

    @Test
    void 모든_핸들러는_공개이거나_CurrentUser_를_받는다() throws ClassNotFoundException {
        List<String> 미분류 = handlerMethods().stream()
                .filter(method -> !isPublic(method))
                .filter(method -> !hasCurrentUser(method))
                .map(method -> method.getDeclaringClass().getSimpleName() + "#" + method.getName())
                .toList();

        assertThat(미분류)
                .as("""
                        인증 여부를 정하지 않은 핸들러입니다.
                        로그인이 필요하면 @CurrentUser 를 받고,
                        열어둘 것이면 @PublicApi("이유") 를 붙이세요.""")
                .isEmpty();
    }

    private boolean isPublic(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, PublicApi.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), PublicApi.class);
    }

    private boolean hasCurrentUser(Method method) {
        return Arrays.stream(method.getParameters())
                .anyMatch(parameter -> parameter.isAnnotationPresent(CurrentUser.class));
    }

    /** {@code @GetMapping} 등은 {@code @RequestMapping} 메타애노테이션이라 함께 잡힙니다. */
    private List<Method> handlerMethods() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Method> methods = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    @Test
    void 컨트롤러를_실제로_찾아낸다() throws ClassNotFoundException {
        // 스캔이 조용히 0개를 돌려주면 위 테스트가 아무것도 지키지 않게 됩니다.
        assertThat(handlerMethods()).isNotEmpty();
    }

    @Test
    void 판정_규칙_자체를_확인한다() throws NoSuchMethodException {
        // 샘플에 @RestController 를 붙이면 위 스캔에 잡혀 가드가 스스로 깨집니다.
        // 그래서 판정 메서드만 직접 호출합니다.
        Method 열린_핸들러 = 샘플.class.getDeclaredMethod("인증없음", String.class);
        Method 닫힌_핸들러 = 샘플.class.getDeclaredMethod("인증있음", String.class);
        Method 공개_핸들러 = 공개샘플.class.getDeclaredMethod("공개", String.class);

        assertThat(hasCurrentUser(열린_핸들러)).isFalse();
        assertThat(isPublic(열린_핸들러)).isFalse();

        assertThat(hasCurrentUser(닫힌_핸들러)).isTrue();
        assertThat(isPublic(공개_핸들러)).isTrue();
    }

    static class 샘플 {
        String 인증없음(String userId) {
            return userId;
        }

        String 인증있음(@CurrentUser String userId) {
            return userId;
        }
    }

    @PublicApi("테스트용")
    static class 공개샘플 {
        String 공개(String value) {
            return value;
        }
    }
}
