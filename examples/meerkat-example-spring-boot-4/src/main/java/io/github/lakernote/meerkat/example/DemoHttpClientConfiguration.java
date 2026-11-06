package io.github.lakernote.meerkat.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class DemoHttpClientConfiguration {
    @Bean
    RestClient httpBingoRestClient(RestClient.Builder builder,
                                  @Value("${meerkat.demo.httpbingo-base-url:https://httpbingo.org}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
