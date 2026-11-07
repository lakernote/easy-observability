package io.github.lakernote.meerkat.diagnostics.internal;

import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Explains where a configuration property comes from: every property source that defines it, in precedence order,
 * with its raw value and origin, and the resolved value the application sees. Values are not redacted.
 */
public final class ConfigSources {
    static final int MAX_RESULTS = 200;
    static final int MAX_VALUE_CHARS = 4_096;
    static final int MAX_NAME_LENGTH = 256;
    /** Bounds a search over very large environments. */
    static final int MAX_SCANNED_NAMES = 200_000;
    private final ConfigurableEnvironment environment;

    public ConfigSources(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /**
     * Looks {@code name} up in every property source. Sources are matched with Spring Boot's relaxed binding, so
     * {@code server.port} also finds {@code SERVER_PORT}; the first source that defines the property wins.
     */
    public Lookup lookup(String name) {
        String key = name(name);
        ConfigurationPropertyName relaxed = relaxedName(key);
        List<Definition> definitions = new ArrayList<>();
        Object winnerValue = null;
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) continue;
            Object value;
            Origin origin;
            if (source.containsProperty(key)) {
                value = source.getProperty(key);
                origin = OriginLookup.getOrigin(source, key);
            } else {
                ConfigurationProperty property = relaxed == null ? null : relaxedProperty(source, relaxed);
                if (property == null) continue;
                value = property.getValue();
                origin = property.getOrigin();
            }
            if (definitions.isEmpty()) winnerValue = value;
            definitions.add(new Definition(source.getName(), source.getClass().getSimpleName(), text(value),
                    origin == null ? null : origin.toString(), definitions.isEmpty()));
        }
        String effective = null;
        String error = null;
        try {
            effective = environment.getProperty(key);
            if (effective == null && winnerValue != null) {
                effective = environment.resolveRequiredPlaceholders(String.valueOf(winnerValue));
            }
        } catch (IllegalArgumentException unresolvable) {
            error = unresolvable.getMessage();
        }
        return new Lookup(key, truncate(effective), error, definitions);
    }

    /** Property names containing {@code query} (case-insensitive) with their resolved values, at most 200. */
    public Search search(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() > MAX_NAME_LENGTH) throw new IllegalArgumentException("Query is too long");
        Map<String, String> winners = new TreeMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        int scanned = 0;
        boolean truncated = false;
        scan:
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)
                    || !(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                if (++scanned > MAX_SCANNED_NAMES) {
                    truncated = true;
                    break scan;
                }
                if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
                winners.putIfAbsent(name, source.getName());
                counts.merge(name, 1, Integer::sum);
            }
        }
        List<Match> matches = new ArrayList<>();
        for (Map.Entry<String, String> entry : winners.entrySet()) {
            if (matches.size() >= MAX_RESULTS) {
                truncated = true;
                break;
            }
            String value = null;
            String error = null;
            try {
                value = truncate(environment.getProperty(entry.getKey()));
            } catch (IllegalArgumentException unresolvable) {
                error = unresolvable.getMessage();
            }
            matches.add(new Match(entry.getKey(), value, error, entry.getValue(), counts.get(entry.getKey())));
        }
        return new Search(winners.size(), truncated, matches);
    }

    private static String name(String name) {
        String key = name == null ? "" : name.trim();
        if (key.isEmpty() || key.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Property name must be 1 to " + MAX_NAME_LENGTH + " characters");
        }
        return key;
    }

    private static ConfigurationPropertyName relaxedName(String name) {
        try {
            ConfigurationPropertyName adapted = ConfigurationPropertyName.adapt(name, '.');
            return adapted.isEmpty() ? null : adapted;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static ConfigurationProperty relaxedProperty(PropertySource<?> source, ConfigurationPropertyName name) {
        try {
            ConfigurationPropertySource adapted = ConfigurationPropertySource.from(source);
            return adapted == null ? null : adapted.getConfigurationProperty(name);
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    private static String text(Object value) {
        return truncate(value instanceof String string ? string : ObjectUtils.nullSafeToString(value));
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_VALUE_CHARS) return value;
        return value.substring(0, MAX_VALUE_CHARS) + "…(" + value.length() + " chars)";
    }

    /** A property source that defines the property; {@code winner} marks the one whose value applies. */
    public record Definition(String source, String type, String value, String origin, boolean winner) { }

    /** {@code value} is resolved, with placeholders replaced; {@code error} explains an unresolvable placeholder. */
    public record Lookup(String name, String value, String error, List<Definition> definitions) { }

    public record Match(String name, String value, String error, String source, int definitions) { }

    /** {@code total} counts matching names; {@code truncated} is set when not all of them are listed. */
    public record Search(int total, boolean truncated, List<Match> matches) { }
}
