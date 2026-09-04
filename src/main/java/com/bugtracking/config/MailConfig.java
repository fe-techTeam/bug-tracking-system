package com.bugtracking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The thread email is sent on.
 *
 * <p>Its own pool rather than Spring's shared {@code applicationTaskExecutor}:
 * an SMTP connection can take seconds to time out, and a mail server having a
 * bad afternoon should not be able to fill the queue everything else in the app
 * is waiting in.
 *
 * <p>Small and bounded on purpose. Two threads is more than a tracker this size
 * needs, and {@code CallerRunsPolicy} means that if even that backs up the
 * request thread sends one itself and slows down — which is a great deal better
 * than a queue growing until the heap gives out. Nothing here retries: a
 * message that could not be sent is logged and dropped, because the bell has
 * already rung and the notification is the record.
 */
@Configuration
public class MailConfig {

    @Bean("mailExecutor")
    Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mail-");
        // Shutdown waits, so a message handed over a moment before the app
        // stops is still sent rather than vanishing with the pool.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
