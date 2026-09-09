package com.cuea.common.security;

import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentUser} 파라미터에 {@code userId} 를 채웁니다.
 *
 * <p><b>지원 여부를 애노테이션만으로 판단하고, 타입은 여기서 막습니다.</b>
 * 타입 조건을 {@code supportsParameter} 에 두면 타입이 안 맞을 때 이 리졸버가
 * 조용히 빠지고 Spring 이 기본 리졸버 체인으로 넘어갑니다. 그 끝에 있는
 * {@code RequestParamMethodArgumentResolver} 가 {@code Long} 같은 단순 타입을
 * 쿼리 파라미터로 채우기 때문에, {@code @CurrentUser Long userId} 는
 * <b>{@code ?userId=999} 로 아무나 남의 ID 를 넣을 수 있는 인증 우회</b>가 됩니다.
 * 예외도 401 도 나지 않아 눈에 띄지 않습니다.
 *
 * <p>나중에 role 이나 추가 정보가 필요해 {@code UserPrincipal} 로 넘어갈 때는
 * 이 분기에 타입을 하나 더 지원시키면 됩니다. 기존 컨트롤러는 고치지 않습니다.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        if (!String.class.equals(parameter.getParameterType())) {
            throw new IllegalStateException(
                    "@CurrentUser 는 String 파라미터에만 붙일 수 있습니다: " + parameter);
        }

        Object userId = webRequest.getAttribute(
                JwtAuthFilter.USER_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);

        CurrentUser annotation = parameter.getParameterAnnotation(CurrentUser.class);
        if (userId == null && annotation != null && annotation.required()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
