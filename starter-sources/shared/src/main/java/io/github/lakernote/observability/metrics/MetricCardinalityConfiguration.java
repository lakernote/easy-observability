package io.github.lakernote.observability.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Limits the number of metric series and bounds selected tag values. */
@Configuration(proxyBeanMethods = false)
public class MetricCardinalityConfiguration {

    private static final String SQL_QUERY_TIMER = "easy.sql.queries";

    /**
     * Single-tag limits do not bound the cartesian product, ignore a meter that omits the guarded tag, and can deny
     * the reserved {@code unknown} overflow series. Diagnosis metrics therefore use one complete-series guard.
     */
    @Bean
    MeterFilter metricSeriesCardinalityLimit() {
        return new RequiredTagsSeriesLimitFilter(Map.ofEntries(
                Map.entry(SQL_QUERY_TIMER, new SeriesRule(1_000, Map.of("mapper", 500),
                        "mapper", "command", "result")),
                Map.entry("easy.transactions", new SeriesRule(4, "result", "read_only")),
                Map.entry("easy.operations.duration", operationSeriesRule()),
                Map.entry("easy.operations.cpu.time", operationSeriesRule()),
                Map.entry("easy.operations.allocated", operationSeriesRule())
        ));
    }

    private static SeriesRule operationSeriesRule() {
        return new SeriesRule(512, Map.of("operation", 100), "source", "operation", "outcome");
    }

    private static final class RequiredTagsSeriesLimitFilter implements MeterFilter {
        private final Map<String, SeriesRule> rules;

        private RequiredTagsSeriesLimitFilter(Map<String, SeriesRule> rules) {
            this.rules = rules;
        }

        @Override
        public MeterFilterReply accept(Meter.Id id) {
            SeriesRule rule = rules.get(id.getName());
            return rule == null ? MeterFilterReply.NEUTRAL : rule.accept(id);
        }
    }

    private static final class SeriesRule {
        private final int maximumSeries;
        private final List<String> requiredTags;
        private final Map<String, Integer> tagValueLimits;
        private final Map<String, Set<String>> admittedTagValues;
        private final Set<List<TagValue>> admittedSeries = new HashSet<>();

        private SeriesRule(int maximumSeries, String... requiredTags) {
            this(maximumSeries, Map.of(), requiredTags);
        }

        private SeriesRule(int maximumSeries, Map<String, Integer> tagValueLimits, String... requiredTags) {
            this.maximumSeries = maximumSeries;
            this.requiredTags = List.of(requiredTags);
            this.tagValueLimits = Map.copyOf(tagValueLimits);
            this.admittedTagValues = new HashMap<>();
            tagValueLimits.keySet().forEach(tag -> admittedTagValues.put(tag, new HashSet<>()));
        }

        private synchronized MeterFilterReply accept(Meter.Id id) {
            List<String> values = new ArrayList<>(requiredTags.size());
            for (String requiredTag : requiredTags) {
                String value = id.getTag(requiredTag);
                if (value == null || value.isBlank()) {
                    return MeterFilterReply.DENY;
                }
                values.add(value);
            }
            List<TagValue> series = id.getTags().stream()
                    .map(tag -> new TagValue(tag.getKey(), tag.getValue()))
                    .toList();
            if (admittedSeries.contains(series)) {
                return MeterFilterReply.NEUTRAL;
            }
            if (admittedSeries.size() >= maximumSeries) {
                return MeterFilterReply.DENY;
            }
            for (int i = 0; i < requiredTags.size(); i++) {
                String tag = requiredTags.get(i);
                Integer limit = tagValueLimits.get(tag);
                Set<String> admittedValues = admittedTagValues.get(tag);
                if (limit != null && !admittedValues.contains(values.get(i)) && admittedValues.size() >= limit) {
                    return MeterFilterReply.DENY;
                }
            }
            admittedSeries.add(series);
            for (int i = 0; i < requiredTags.size(); i++) {
                Set<String> admittedValues = admittedTagValues.get(requiredTags.get(i));
                if (admittedValues != null) {
                    admittedValues.add(values.get(i));
                }
            }
            return MeterFilterReply.NEUTRAL;
        }
    }

    private record TagValue(String key, String value) {
    }
}
