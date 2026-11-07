package io.github.lakernote.meerkat.diagnostics.internal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigSourcesTest {
    private final StandardEnvironment environment = new StandardEnvironment();
    private final ConfigSources config = new ConfigSources(environment);

    ConfigSourcesTest() {
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.addLast(new MapPropertySource("commandLine", Map.of("server.port", "9090")));
        sources.addLast(new SystemEnvironmentPropertySource("environment", Map.of("SERVER_PORT", "8081", "APP_HOST", "db.local")));
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("server.port", OriginTrackedValue.of("8080",
                new TextResourceOrigin(new ByteArrayResource(new byte[0], "application.yml"), new TextResourceOrigin.Location(3, 8))));
        yaml.put("app.url", "http://${app.host:localhost}/api");
        yaml.put("app.broken", "${missing.value}");
        yaml.put("app.long", "x".repeat(ConfigSources.MAX_VALUE_CHARS + 10));
        yaml.put("app.list", new String[] {"a", "b"});
        sources.addLast(new OriginTrackedMapPropertySource("Config resource 'application.yml'", yaml));
        ConfigurationPropertySources.attach(environment);
    }

    @Test
    void listsEverySourceDefiningAPropertyInPrecedenceOrder() {
        ConfigSources.Lookup lookup = config.lookup(" server.port ");

        assertThat(lookup.name()).isEqualTo("server.port");
        assertThat(lookup.value()).isEqualTo("9090");
        assertThat(lookup.error()).isNull();
        assertThat(lookup.definitions()).extracting(ConfigSources.Definition::source)
                .containsExactly("commandLine", "environment", "Config resource 'application.yml'");
        assertThat(lookup.definitions()).extracting(ConfigSources.Definition::value).containsExactly("9090", "8081", "8080");
        assertThat(lookup.definitions()).extracting(ConfigSources.Definition::winner).containsExactly(true, false, false);
        assertThat(lookup.definitions().get(2).origin()).contains("application.yml").contains("4:9");
        assertThat(lookup.definitions().get(2).type()).isEqualTo("OriginTrackedMapPropertySource");
    }

    @Test
    void resolvesPlaceholdersAndReportsUnresolvableOnes() {
        ConfigSources.Lookup url = config.lookup("app.url");
        assertThat(url.value()).isEqualTo("http://db.local/api");
        assertThat(url.definitions()).singleElement().extracting(ConfigSources.Definition::value)
                .isEqualTo("http://${app.host:localhost}/api");

        ConfigSources.Lookup broken = config.lookup("app.broken");
        assertThat(broken.value()).isNull();
        assertThat(broken.error()).contains("missing.value");

        assertThat(config.lookup("app.long").value()).endsWith("chars)").hasSizeLessThan(ConfigSources.MAX_VALUE_CHARS + 30);
        assertThat(config.lookup("app.list").definitions().get(0).value()).isEqualTo("{a, b}");

        ConfigSources.Lookup missing = config.lookup("no.such.property");
        assertThat(missing.value()).isNull();
        assertThat(missing.definitions()).isEmpty();
        assertThat(config.lookup("Not A Relaxed Name!").definitions()).isEmpty();
    }

    @Test
    void searchesPropertyNames() {
        ConfigSources.Search search = config.search("APP.");
        assertThat(search.truncated()).isFalse();
        assertThat(search.matches()).extracting(ConfigSources.Match::name)
                .containsExactly("app.broken", "app.list", "app.long", "app.url");
        ConfigSources.Match url = search.matches().get(3);
        assertThat(url.value()).isEqualTo("http://db.local/api");
        assertThat(url.source()).isEqualTo("Config resource 'application.yml'");
        assertThat(url.definitions()).isEqualTo(1);
        assertThat(search.matches().get(0).error()).contains("missing.value");

        ConfigSources.Search port = config.search("server.port");
        assertThat(port.matches()).singleElement().satisfies(match -> {
            assertThat(match.source()).isEqualTo("commandLine");
            assertThat(match.definitions()).isEqualTo(2);
        });
        assertThat(config.search(null).total()).isEqualTo(7);
    }

    @Test
    void capsSearchResults() {
        Map<String, Object> many = new HashMap<>();
        for (int index = 0; index < ConfigSources.MAX_RESULTS + 5; index++) many.put("bulk.key" + index, index);
        environment.getPropertySources().addLast(new MapPropertySource("bulk", many));
        environment.getPropertySources().addLast(new PropertySource<>("opaque") {
            @Override
            public Object getProperty(String name) {
                return "bulk.key1".equals(name) ? "hidden" : null;
            }
        });

        ConfigSources.Search search = config.search("bulk");
        assertThat(search.total()).isEqualTo(ConfigSources.MAX_RESULTS + 5);
        assertThat(search.truncated()).isTrue();
        assertThat(search.matches()).hasSize(ConfigSources.MAX_RESULTS);
        assertThat(config.lookup("bulk.key1").definitions()).extracting(ConfigSources.Definition::source)
                .containsExactly("bulk", "opaque");
    }

    @Test
    void rejectsBlankOrOverlongNames() {
        assertThatThrownBy(() -> config.lookup(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.lookup(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.lookup("a".repeat(300))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.search("a".repeat(300))).isInstanceOf(IllegalArgumentException.class);
    }
}
