package io.github.lakernote.observability.example;

import io.github.lakernote.observability.trace.TraceIdContext;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class DemoHttpClientConfiguration {
    @Bean
    RestClient httpBingoRestClient(ObservationRegistry observationRegistry,
                                  @Value("${easy.observability.demo.httpbingo-base-url:https://httpbingo.org}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().observationRegistry(observationRegistry).baseUrl(baseUrl).requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    String traceId = TraceIdContext.currentTraceId();
                    if (TraceIdContext.isValidTraceId(traceId)) request.getHeaders().set("X-Trace-Id", traceId);
                    return execution.execute(request, body);
                }).build();
    }
}
