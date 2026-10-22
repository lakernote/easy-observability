package io.github.lakernote.observability.autoconfigure;

import io.github.lakernote.observability.config.EasyObservabilityProperties;
import io.github.lakernote.observability.integration.mybatis.MybatisTraceInterceptor;
import io.github.lakernote.observability.trace.TraceOperationRecorder;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Adds mapper-level trace spans when MyBatis is present. */
@AutoConfiguration(after = ObservabilityAutoConfiguration.class)
@ConditionalOnClass(Interceptor.class)
@ConditionalOnProperty(prefix = "easy.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.mybatis", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easy.observability.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(TraceOperationRecorder.class)
public class MybatisTraceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "easyObservabilityMybatisTraceInterceptor")
    MybatisTraceInterceptor easyObservabilityMybatisTraceInterceptor(
            TraceOperationRecorder recorder, EasyObservabilityProperties properties) {
        return new MybatisTraceInterceptor(recorder, properties);
    }
}
