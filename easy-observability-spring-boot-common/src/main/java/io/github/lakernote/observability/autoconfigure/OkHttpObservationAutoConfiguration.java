package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.micrometer.core.instrument.binder.okhttp3.OkHttpObservationInterceptor;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.regex.Pattern;

/** Adds Micrometer observations and trace-id propagation to application OkHttpClient beans. */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass({OkHttpClient.class, OkHttpObservationInterceptor.class})
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OkHttpObservationAutoConfiguration {
    private static final Pattern IDENTIFIER_PATH_SEGMENT = Pattern.compile(
            "(?i)(?<=/)([0-9]{2,}|[0-9a-f]{8}-[0-9a-f-]{27,}|[0-9a-f]{16,})(?=/|$)");
    @Bean
    static BeanPostProcessor okHttpObservationBeanPostProcessor(ObjectProvider<ObservationRegistry> registryProvider,
                                                                 ObjectProvider<EasyObservabilityProperties> propertiesProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof OkHttpClient client)) return bean;
                ObservationRegistry registry = registryProvider.getIfAvailable();
                EasyObservabilityProperties properties = propertiesProvider.getIfAvailable();
                if (registry == null || properties == null) return bean;
                OkHttpClient.Builder builder = client.newBuilder();
                boolean hasTracePropagation = client.interceptors().stream()
                        .anyMatch(interceptor -> interceptor instanceof TraceIdPropagationInterceptor);
                if (!hasTracePropagation) builder.addInterceptor(new TraceIdPropagationInterceptor(properties));
                // Micrometer owns timing, status/error tags and Observation lifecycle. The bounded URI mapper
                // prevents path identifiers from creating a new time series per request.
                if (!hasObservationInterceptor(client)) {
                    builder.addInterceptor(OkHttpObservationInterceptor.builder(registry, "http.client.requests")
                            .uriMapper(OkHttpObservationAutoConfiguration::boundedUri)
                            .build());
                }
                return builder.build();
            }
        };
    }

    private static boolean hasObservationInterceptor(OkHttpClient client) {
        return client.interceptors().stream().anyMatch(interceptor ->
                interceptor instanceof OkHttpObservationInterceptor);
    }

    private static String boundedUri(Request request) {
        String path = request.url().encodedPath();
        if (path == null || path.isBlank()) return "UNKNOWN";
        return IDENTIFIER_PATH_SEGMENT.matcher(path).replaceAll("{id}");
    }

    private static final class TraceIdPropagationInterceptor implements okhttp3.Interceptor {
        private final EasyObservabilityProperties properties;

        private TraceIdPropagationInterceptor(EasyObservabilityProperties properties) {
            this.properties = properties;
        }

        @Override
        public okhttp3.Response intercept(Chain chain) throws java.io.IOException {
            Request request = chain.request();
            String headerName = properties.getTrace().getHeaderName();
            String traceId = TraceIdContext.currentTraceId();
            if (TraceIdContext.isValidTraceId(traceId) && request.header(headerName) == null) {
                request = request.newBuilder().header(headerName, traceId).build();
            }
            return chain.proceed(request);
        }
    }
}
