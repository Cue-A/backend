package com.cuea.common.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * {@code @Async} 실행기. 면접 세션 시작의 AI 폴링(최대 90초)을 HTTP 요청 스레드에서
 * 떼어내 백그라운드로 돌리기 위해 씁니다.
 *
 * <p>폴링은 대부분 블로킹 대기라 가상 스레드가 적합합니다. 플랫폼 스레드 풀을
 * 쓰면 동시 세션 수만큼 스레드가 묶여 금방 고갈됩니다. {@code spring.threads.virtual}
 * 이 켜져 있어도 {@code @Async} 는 별도 실행기를 쓰므로 여기서 명시적으로
 * 가상 스레드 실행기를 등록합니다.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** 면접 세션 시작 폴링 전용 실행기 빈 이름. */
    public static final String INTERVIEW_EXECUTOR = "interviewTaskExecutor";

    @Bean(INTERVIEW_EXECUTOR)
    public AsyncTaskExecutor interviewTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // 반환값 없는 @Async(void) 에서 던진 예외의 최종 안전망.
        // 세션 시작 폴링 흐름은 예외를 자체적으로 잡아 WebSocket 으로 알리지만,
        // 예기치 못한 예외가 새어나가도 조용히 사라지지 않게 로깅합니다.
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
