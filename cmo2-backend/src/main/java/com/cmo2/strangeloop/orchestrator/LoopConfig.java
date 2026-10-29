package com.cmo2.strangeloop.orchestrator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Threading for the Strange Loop. A small executor runs cycles off the request
 * thread (so "Run Cycle" returns immediately and the UI watches via WebSocket).
 * When cycles start is each customer's own Quartz schedule ({@code LoopSchedules});
 * {@link LoopResumeRunner} resumes cycles a crash interrupted.
 */
@Configuration
public class LoopConfig {

    @Bean(name = "loopTaskExecutor")
    public ThreadPoolTaskExecutor loopTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("loop-");
        executor.initialize();
        return executor;
    }
}
