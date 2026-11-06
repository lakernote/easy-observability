package io.github.lakernote.meerkat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the public API boundary: anything outside an {@code internal} package (except auto-configuration classes)
 * is a compatibility promise, so additions must be deliberate and must not expose internal types.
 */
class PublicApiTest {
    private static final String BASE_PACKAGE = "io.github.lakernote.meerkat.";

    private static final Set<String> PUBLIC_API = Set.of(
            "config.MeerkatProperties",
            "integration.feign.TraceIdRequestInterceptor",
            "integration.kafka.TraceIdRecordInterceptor",
            "integration.observation.ObservationTraceIdResolver",
            "trace.SpanKind",
            "trace.TraceBlock",
            "trace.TraceContextTaskDecorator",
            "trace.TraceDisabled",
            "trace.TraceIdContext",
            "trace.TraceSpan",
            "web.ObservabilityAccessPolicy");

    @Test
    void publicTypesOutsideInternalPackagesAreExactlyTheDocumentedApi() throws Exception {
        Set<String> publicTypes = new TreeSet<>();
        for (Class<?> type : mainClasses()) {
            String name = type.getName().substring(BASE_PACKAGE.length());
            if (isInternal(type) || name.startsWith("autoconfigure.")) continue;
            if (Modifier.isPublic(type.getModifiers())) publicTypes.add(name);
        }

        assertThat(publicTypes).containsExactlyInAnyOrderElementsOf(PUBLIC_API);
    }

    @Test
    void publicApiSignaturesDoNotExposeInternalTypes() throws Exception {
        List<String> leaks = new ArrayList<>();
        for (Class<?> type : mainClasses()) {
            if (!PUBLIC_API.contains(type.getName().substring(BASE_PACKAGE.length()))) continue;
            collectLeaks(type, leaks);
        }

        assertThat(leaks).isEmpty();
    }

    private static void collectLeaks(Class<?> type, List<String> leaks) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!exposed(constructor.getModifiers())) continue;
            for (Class<?> parameter : constructor.getParameterTypes()) check(type, "constructor", parameter, leaks);
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!exposed(method.getModifiers()) || method.isSynthetic()) continue;
            check(type, method.getName(), method.getReturnType(), leaks);
            for (Class<?> parameter : method.getParameterTypes()) check(type, method.getName(), parameter, leaks);
        }
        for (Field field : type.getDeclaredFields()) {
            if (exposed(field.getModifiers())) check(type, field.getName(), field.getType(), leaks);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (exposed(nested.getModifiers())) collectLeaks(nested, leaks);
        }
    }

    private static void check(Class<?> owner, String member, Class<?> type, List<String> leaks) {
        Class<?> component = type;
        while (component.isArray()) component = component.getComponentType();
        if (isInternal(component)) leaks.add(owner.getName() + "#" + member + " -> " + component.getName());
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static boolean isInternal(Class<?> type) {
        return type.getName().startsWith(BASE_PACKAGE) && type.getPackageName().contains(".internal");
    }

    private static List<Class<?>> mainClasses() throws IOException, ClassNotFoundException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + BASE_PACKAGE.replace('.', '/') + "**/*.class");
        List<Class<?>> classes = new ArrayList<>();
        for (Resource resource : resources) {
            String url = resource.getURL().toString();
            if (url.contains("/test-classes/") || url.contains("$")) continue;
            String path = url.substring(url.indexOf(BASE_PACKAGE.replace('.', '/')));
            String className = path.substring(0, path.length() - ".class".length()).replace('/', '.');
            classes.add(Class.forName(className, false, PublicApiTest.class.getClassLoader()));
        }
        assertThat(classes).as("main classes found").hasSizeGreaterThan(PUBLIC_API.size());
        return classes;
    }
}
