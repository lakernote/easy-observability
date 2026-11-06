package io.github.lakernote.meerkat.example;

import io.github.lakernote.meerkat.web.ObservabilityAccessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local demo only: lets anyone view and clear the sample trace history. Never expose it to untrusted networks. */
@Configuration(proxyBeanMethods = false)
class DemoAccessConfiguration {
    @Bean
    ObservabilityAccessPolicy observabilityAccessPolicy() {
        return new ObservabilityAccessPolicy() {
            @Override
            public boolean canView() {
                return true;
            }

            @Override
            public boolean canClear() {
                return true;
            }
        };
    }
}
