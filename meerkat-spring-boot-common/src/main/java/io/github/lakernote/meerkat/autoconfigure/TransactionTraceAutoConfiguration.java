package io.github.lakernote.meerkat.autoconfigure;

import io.github.lakernote.meerkat.config.MeerkatProperties;
import io.github.lakernote.meerkat.integration.transaction.internal.TransactionTraceBeanPostProcessor;
import io.github.lakernote.meerkat.integration.transaction.internal.TransactionTraceListener;
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
@ConditionalOnProperty(prefix = "meerkat", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionTraceAutoConfiguration {
    @Bean
    TransactionTraceListener transactionTraceListener(MeerkatProperties properties) {
        return new TransactionTraceListener(properties);
    }

    @Bean
    static TransactionTraceBeanPostProcessor transactionTraceBeanPostProcessor(
            ObjectProvider<TransactionTraceListener> listenerProvider) {
        return new TransactionTraceBeanPostProcessor(listenerProvider);
    }
}
