package io.github.lakernote.observability.example;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class DemoOkHttpConfiguration {
    @Bean
    OkHttpClient demoOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .callTimeout(Duration.ofSeconds(8))
                .build();
    }
}
