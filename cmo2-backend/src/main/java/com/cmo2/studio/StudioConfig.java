package com.cmo2.studio;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;

/** Content Studio batches and idea searches run here, off the request thread, two at a time. */
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

    /**
     * Uploads (files and voice recordings) up to 25 MB, the most OpenAI accepts for transcription.
     * Set here rather than in application.properties, which is git-ignored.
     */
    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(25));
        factory.setMaxRequestSize(DataSize.ofMegabytes(26));
        return factory.createMultipartConfig();
    }
}
