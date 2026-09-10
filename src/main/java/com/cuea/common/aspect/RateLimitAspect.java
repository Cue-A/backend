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
import org.springframework.dao.DataAccessException;
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
        String caller = callerId();

        if (exceeded(rateLimit, caller)) {
            log.warn("호출 제한 초과 key={} caller={}", rateLimit.key(), caller);
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return joinPoint.proceed();
    }

    /**
     * Redis 가 죽으면 <b>통과시킵니다(fail-open).</b>
     *
     * <p>막는 쪽을 고르면 Redis 장애가 곧바로 500 이 되어 호출 제한이라는 부가
     * 기능이 본 기능을 멈춥니다. 게다가 이 서비스는 세션 상태와 회사 캐시도 Redis 를
     * 쓰기 때문에, 여기서 500 을 내면 진짜 원인이 가려집니다. 대신 WARN 을 남겨
     * 장애 중에 제한이 풀려 있었다는 사실이 로그에 남게 합니다.
     */
    private boolean exceeded(RateLimit rateLimit, String caller) {
        String redisKey = "ratelimit:%s:%s".formatted(rateLimit.key(), caller);
        try {
            Long remaining = stringRedisTemplate.execute(
                    rateLimitScript,
                    List.of(redisKey),
                    String.valueOf(rateLimit.limit()),
                    String.valueOf(rateLimit.windowSeconds()));
            return remaining != null && remaining < 0;

        } catch (DataAccessException e) {
            log.warn("호출 제한을 확인하지 못해 통과시킵니다 key={} reason={}",
                    rateLimit.key(), e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 로그인했으면 userId, 아니면 IP 로 셉니다.
     *
     * <p>접두({@code u:} / {@code ip:})를 붙여 둘을 같은 이름 공간에 섞지 않습니다.
     * userId 는 자유 문자열이라 접두가 없으면 IP 형태의 ID 와 충돌할 수 있고,
     * 로그만 보고 사용자인지 IP 인지 구분할 수도 없습니다.
     *
     * <p>IP 는 {@code getRemoteAddr()} 하나만 봅니다. {@code X-Forwarded-For} 를
     * 직접 파싱하지 않는 이유는 <b>클라이언트가 위조할 수 있기 때문</b>입니다.
     * 프록시 뒤에 놓을 때만 {@code server.forward-headers-strategy} 를 켜면
     * {@code ForwardedHeaderFilter} 가 {@code getRemoteAddr()} 을 대신 바꿔줍니다.
     * application.yml 의 해당 항목 주석을 보세요.
     */
    private String callerId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return "anonymous";
        }
        Object userId = servlet.getRequest().getAttribute(JwtAuthFilter.USER_ID_ATTRIBUTE);
        if (userId != null) {
            return "u:" + userId;
        }
        return "ip:" + servlet.getRequest().getRemoteAddr();
    }
}
