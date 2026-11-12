package com.cmo2.studio;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Content Studio batches run here, off the request thread, two at a time. */
@Configuration
public class StudioConfig {

    @Bean(name = "studioTaskExecutor")
    public ThreadPoolTaskExecutor studioTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("studio-");
        executor.initialize();
        return executor;
    }
}
