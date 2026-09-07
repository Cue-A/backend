package com.cuea.common.aspect;

import com.cuea.common.annotation.RateLimit;
import com.cuea.common.exception.BusinessException;
import com.cuea.common.exception.ErrorCode;
import com.cuea.common.security.JwtAuthFilter;
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
        return servlet.getRequest().getRemoteAddr();
    }
}
