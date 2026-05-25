package com.example.hibernatemultithread;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Main Spring Boot application class.
 *
 * <p>{@link EnableAsync} activates Spring's asynchronous method execution
 * support, required for {@code @Async} annotations to take effect.
 * The custom thread pools are configured in
 * {@link com.example.hibernatemultithread.config.AsyncConfig}.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
