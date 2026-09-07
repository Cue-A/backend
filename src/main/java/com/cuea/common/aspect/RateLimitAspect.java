package com.cuea.common.aspect;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> rateLimitScript;

    @Around("@annotation(rateLimit)")
    public Object limit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String redisKey = "ratelimit:%s:%s".formatted(rateLimit.key(), callerId());

        Long remaining = stringRedisTemplate.execute(
                rateLimitScript,
                List.of(redisKey),
                String.valueOf(rateLimit.limit()),
                String.valueOf(rateLimit.windowSeconds()));

        if (remaining != null && remaining < 0) {
            log.warn("호출 제한 초과 key={} caller={}", rateLimit.key(), callerId());
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return joinPoint.proceed();
    }

    /** 로그인했으면 userId, 아니면 IP 로 셉니다. */
    private String callerId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return "anonymous";
        }
        Object userId = servlet.getRequest().getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE);
        if (userId != null) {
            return userId.toString();
        }
        return clientIp(servlet.getRequest());
    }

    /**
     * 로드밸런서 뒤에서는 {@code getRemoteAddr()} 이 LB 주소를 돌려줍니다.
     *
     * <p>그대로 두면 <b>로그인하지 않은 사용자 전원이 한 IP 로 뭉쳐</b> 한 명이
     * 한도를 채우면 나머지가 전부 막힙니다. 로그인·회원가입처럼 미인증 상태에서
     * 제한을 거는 곳에서 특히 문제가 됩니다.
     *
     * <p>{@code X-Forwarded-For} 는 클라이언트가 위조할 수 있습니다. 신뢰할 수
     * 있는 프록시가 앞에 있을 때만 의미가 있습니다.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        return forwarded.split(",")[0].trim();
    }
}
