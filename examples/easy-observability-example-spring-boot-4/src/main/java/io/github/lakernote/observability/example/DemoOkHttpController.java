package io.github.lakernote.observability.example;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
public class DemoOkHttpController {
    private final OkHttpClient client;
    private final String baseUrl;

    public DemoOkHttpController(OkHttpClient client,
                                @Value("${easy.observability.demo.httpbingo-base-url:https://httpbingo.org}") String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @GetMapping("/api/demo/okhttp")
    public Map<String, Object> callOkHttp() throws IOException {
        Request request = new Request.Builder().url(baseUrl + "/anything/easy-observability-okhttp").get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            return Map.of("status", response.code(), "successful", response.isSuccessful(),
                    "body", body.substring(0, Math.min(240, body.length())));
        }
    }
}
