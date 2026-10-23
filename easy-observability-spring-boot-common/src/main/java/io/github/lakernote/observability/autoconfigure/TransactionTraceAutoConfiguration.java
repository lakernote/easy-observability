package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.transaction.TransactionTraceBeanPostProcessor;
import io.github.lakernote.observability.integration.transaction.TransactionTraceListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Installs transaction tracing through Spring's transaction execution listener extension point.
 */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(TransactionExecutionListener.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionTraceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    TransactionTraceListener transactionTraceListener(EasyObservabilityProperties properties) {
        return new TransactionTraceListener(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    static TransactionTraceBeanPostProcessor transactionTraceBeanPostProcessor(
            ObjectProvider<TransactionTraceListener> listenerProvider) {
        return new TransactionTraceBeanPostProcessor(listenerProvider);
    }
}
