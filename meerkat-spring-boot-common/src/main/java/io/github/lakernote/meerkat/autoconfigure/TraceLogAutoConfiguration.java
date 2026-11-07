package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.integration.logging.internal.TraceLogInstaller;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Attaches application log lines to kept traces when the application logs through Logback or Log4j2. */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "meerkat.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "meerkat.trace.logs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TraceLogAutoConfiguration {
    @Bean
    TraceLogInstaller meerkatTraceLogInstaller(MeerkatProperties properties) {
        MeerkatProperties.Logs logs = properties.getTrace().getLogs();
        return new TraceLogInstaller(logs.getMaxPerTrace(), logs.getMaxMessageChars(),
                logs.getMaxBufferedSize().toBytes());
    }
}
