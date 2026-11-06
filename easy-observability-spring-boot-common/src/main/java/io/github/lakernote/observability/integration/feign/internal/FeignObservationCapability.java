package io.github.lakernote.observability.integration.feign.internal;

import feign.Capability;
import feign.Client;
import feign.MethodMetadata;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.io.IOException;
import java.net.URI;

/**
 * 为 Feign 调用发布 {@code http.client.requests} Observation，使其作为远程调用节点进入 Trace 与调用统计。
 * <p>仅在未引入 feign-micrometer 时使用；URI 取接口声明的模板，避免路径参数和 query 造成高基数。</p>
 */
public final class FeignObservationCapability implements Capability {
    static final String OBSERVATION_NAME = "http.client.requests";
    private final ObservationRegistry registry;

    public FeignObservationCapability(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Client enrich(Client client) {
        if (client instanceof ObservedClient) return client;
        return new ObservedClient(client, registry);
    }

    private record ObservedClient(Client delegate, ObservationRegistry registry) implements Client {
        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            Observation observation = Observation.createNotStarted(OBSERVATION_NAME, registry)
                    .lowCardinalityKeyValue("method", request.httpMethod().name())
                    .lowCardinalityKeyValue("uri", uriTemplate(request))
                    .lowCardinalityKeyValue("client.name", clientName(request))
                    .start();
            try (Observation.Scope ignored = observation.openScope()) {
                Response response = delegate.execute(request, options);
                observation.lowCardinalityKeyValue("status", String.valueOf(response.status()));
                return response;
            } catch (IOException | RuntimeException | Error failure) {
                observation.error(failure);
                throw failure;
            } finally {
                observation.stop();
            }
        }
    }

    static String uriTemplate(Request request) {
        RequestTemplate template = request.requestTemplate();
        MethodMetadata metadata = template == null ? null : template.methodMetadata();
        String uri = metadata != null && metadata.template() != null ? metadata.template().url() : null;
        if (uri == null || uri.isBlank()) uri = template != null ? template.path() : pathOf(request.url());
        int query = uri == null ? -1 : uri.indexOf('?');
        if (query >= 0) uri = uri.substring(0, query);
        return uri == null || uri.isBlank() ? "/" : uri;
    }

    static String clientName(Request request) {
        RequestTemplate template = request.requestTemplate();
        Target<?> target = template == null ? null : template.feignTarget();
        if (target != null && target.name() != null && !target.name().isBlank()) return target.name();
        try {
            String host = URI.create(request.url()).getHost();
            return host == null ? "none" : host;
        } catch (IllegalArgumentException invalid) {
            return "none";
        }
    }

    private static String pathOf(String url) {
        try {
            return URI.create(url).getRawPath();
        } catch (IllegalArgumentException invalid) {
            return "/";
        }
    }
}
