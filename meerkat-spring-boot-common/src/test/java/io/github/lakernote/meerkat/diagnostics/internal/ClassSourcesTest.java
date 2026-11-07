package io.github.lakernote.meerkat.diagnostics.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassSourcesTest {
    private static final String SAMPLE = Sample.class.getName();
    private static final String SAMPLE_RESOURCE = SAMPLE.replace('.', '/') + ".class";

    @TempDir
    Path directory;

    @Test
    void findsTheLoadedCopyOfAJdkAndAnApplicationClass() {
        ClassSources sources = new ClassSources(getClass().getClassLoader());

        ClassSources.ClassLookup string = sources.lookup("java.lang.String");
        assertThat(string.loaded()).isTrue();
        assertThat(string.loadedFrom()).isEqualTo("jrt:/java.base");
        assertThat(string.classLoader()).isEqualTo("bootstrap");
        assertThat(string.module()).isEqualTo("java.base");
        assertThat(string.copies()).isNotEmpty().allSatisfy(copy -> assertThat(copy.location()).startsWith("jrt:/"));
        assertThat(string.classLoaderChain()).containsExactly("bootstrap");

        ClassSources.ClassLookup own = sources.lookup(" " + getClass().getName().replace('.', '/') + ".class ");
        assertThat(own.className()).isEqualTo(getClass().getName());
        assertThat(own.loaded()).isTrue();
        assertThat(own.module()).isNull();
        assertThat(own.duplicate()).isFalse();
        assertThat(own.copies()).singleElement().satisfies(copy -> {
            assertThat(copy.first()).isTrue();
            assertThat(copy.loaded()).isTrue();
        });
        assertThat(own.classLoaderChain()).last().isEqualTo("bootstrap");
        assertThat(own.error()).isNull();

        ClassSources.ClassLookup missing = sources.lookup("com.example.DoesNotExist");
        assertThat(missing.loaded()).isFalse();
        assertThat(missing.copies()).isEmpty();
        assertThat(missing.error()).isEqualTo("Class not found");
        assertThat(missing.classLoader()).isNull();
    }

    @Test
    void rejectsMalformedClassNames() {
        ClassSources sources = new ClassSources(getClass().getClassLoader());
        assertThatThrownBy(() -> sources.lookup(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sources.lookup("not a class")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sources.lookup("a." + "b".repeat(600))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsDuplicateCopiesAndTheOneActuallyLoaded() throws Exception {
        Path first = jar("first.jar", Map.of(SAMPLE_RESOURCE, sampleBytes()));
        Path second = jar("second.jar", Map.of(SAMPLE_RESOURCE, sampleBytes()));
        try (URLClassLoader loader = new URLClassLoader("isolated",
                new URL[] {first.toUri().toURL(), second.toUri().toURL()}, null)) {
            ClassSources.ClassLookup lookup = new ClassSources(loader, "", 1_000).lookup(SAMPLE);

            assertThat(lookup.loaded()).isTrue();
            assertThat(lookup.duplicate()).isTrue();
            assertThat(lookup.classLoader()).isEqualTo(URLClassLoader.class.getName() + " (isolated)");
            assertThat(lookup.copies()).hasSize(2);
            assertThat(lookup.copies().get(0).location()).endsWith("first.jar");
            assertThat(lookup.copies().get(0).first()).isTrue();
            assertThat(lookup.copies().get(0).loaded()).isTrue();
            assertThat(lookup.copies().get(1).location()).endsWith("second.jar");
            assertThat(lookup.copies().get(1).first()).isFalse();
            assertThat(lookup.copies().get(1).loaded()).isFalse();
        }
    }

    @Test
    void scansJarsDirectoriesAndNestedEntriesForDuplicates() throws Exception {
        byte[] bytes = {1, 2, 3};
        Path first = jar("first.jar", Map.of("com/example/A.class", bytes, "com/example/B.class", bytes,
                "META-INF/versions/17/com/example/A.class", bytes, "module-info.class", bytes,
                "com/example/package-info.class", bytes, "com/example/readme.txt", bytes));
        Path second = jar("second.jar", Map.of("com/example/A.class", bytes, "com/example/B.class", bytes,
                "com/example/C.class", bytes));
        Path fat = jar("fat.jar", Map.of("BOOT-INF/classes/com/example/A.class", bytes,
                "BOOT-INF/classes/com/example/Only.class", bytes, "org/springframework/boot/loader/Launcher.class", bytes));
        Path classes = directory.resolve("classes/com/example");
        Files.createDirectories(classes);
        Files.write(classes.resolve("C.class"), bytes);
        Path missing = directory.resolve("missing.jar");
        URL nested = new URL("jar:" + fat.toUri() + "!/BOOT-INF/classes/");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {first.toUri().toURL(), second.toUri().toURL(),
                nested, missing.toUri().toURL()}, null)) {
            ClassSources sources = new ClassSources(loader, directory.resolve("classes") + java.io.File.pathSeparator
                    + " " + java.io.File.pathSeparator + directory.resolve("absent"), 10_000);

            ClassSources.DuplicateScan scan = sources.scanDuplicates();

            assertThat(scan.sources()).isEqualTo(5);
            assertThat(scan.scannedSources()).as(scan.unreadable().toString()).isEqualTo(4);
            assertThat(scan.truncated()).isFalse();
            assertThat(scan.stopReason()).isNull();
            assertThat(scan.classes()).isEqualTo(8);
            assertThat(scan.duplicates()).extracting(ClassSources.DuplicateClass::className)
                    .containsExactlyInAnyOrder("com.example.A", "com.example.B", "com.example.C");
            ClassSources.DuplicateClass a = scan.duplicates().stream()
                    .filter(duplicate -> duplicate.className().equals("com.example.A")).findFirst().orElseThrow();
            assertThat(a.locations()).hasSize(3);
            assertThat(a.locations().get(2)).endsWith("fat.jar!/BOOT-INF/classes");
            assertThat(scan.pairs()).first().satisfies(pair -> {
                assertThat(pair.first()).endsWith("first.jar");
                assertThat(pair.second()).endsWith("second.jar");
                assertThat(pair.classes()).isEqualTo(2);
                assertThat(pair.samples()).containsExactly("com.example.A", "com.example.B");
            });
            assertThat(scan.pairs()).hasSize(3);
            assertThat(scan.unreadable()).singleElement()
                    .satisfies(unreadable -> assertThat(unreadable.location()).endsWith("missing.jar"));
        }
    }

    @Test
    void stopsAtTheTimeLimitAndReportsUnsupportedSources() throws Exception {
        Map<String, byte[]> many = new java.util.HashMap<>();
        for (int index = 0; index < 1_100; index++) many.put("com/example/K" + index + ".class", new byte[0]);
        Path big = jar("big.jar", many);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {big.toUri().toURL(),
                new URL("http://localhost/unsupported.jar")}, null)) {
            ClassSources.DuplicateScan scan = new ClassSources(loader, "", 0).scanDuplicates();

            assertThat(scan.truncated()).isTrue();
            assertThat(scan.stopReason()).isEqualTo("time limit");
            assertThat(scan.classes()).isLessThan(1_100);
        }
    }

    @Test
    void findsSourcesThroughManifestsAndRejectsConcurrentScans() throws Exception {
        Path withManifest = jar("manifest.jar", Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes()));
        Path dir = directory.resolve("exploded");
        Files.createDirectories(dir.resolve("META-INF"));
        Files.writeString(dir.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\n");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClassLoader blocking = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Collections.enumeration(List.of(new URL("jar:" + withManifest.toUri() + "!/META-INF/MANIFEST.MF"),
                        dir.resolve("META-INF/MANIFEST.MF").toUri().toURL(), new URL("http://localhost/META-INF/MANIFEST.MF")));
            }
        };
        ClassSources sources = new ClassSources(blocking, "", 10_000);
        CompletableFuture<ClassSources.DuplicateScan> running = CompletableFuture.supplyAsync(sources::scanDuplicates);
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(sources.scanDuplicates()).isNull();
        release.countDown();

        ClassSources.DuplicateScan scan = running.get(10, TimeUnit.SECONDS);
        assertThat(scan.sources()).isEqualTo(2);
        assertThat(scan.scannedSources()).isEqualTo(2);
        assertThat(scan.unreadable()).isEmpty();
    }

    @Test
    void ignoresManifestLookupFailures() {
        ClassLoader failing = new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("boom");
            }
        };
        ClassSources sources = new ClassSources(failing, "", 1_000);
        assertThat(sources.sources()).isEmpty();
        assertThat(sources.lookup("com.example.Missing").error()).contains("boom");
    }

    @Test
    void derivesContainersAndNormalizesLocations() {
        assertThat(ClassSources.container("jar:file:/app/lib/a.jar!/com/x/A.class", "com/x/A.class")).isEqualTo("file:/app/lib/a.jar");
        assertThat(ClassSources.container("jar:nested:/app.jar/!BOOT-INF/lib/a.jar!/com/x/A.class", "com/x/A.class"))
                .isEqualTo("nested:/app.jar/!BOOT-INF/lib/a.jar");
        assertThat(ClassSources.container("jar:odd", "x")).isEqualTo("odd");
        assertThat(ClassSources.container("jrt:/java.base/java/lang/String.class", "java/lang/String.class")).isEqualTo("jrt:/java.base");
        assertThat(ClassSources.container("jrt:/java.base", "x")).isEqualTo("jrt:/java.base");
        assertThat(ClassSources.container("file:/app/classes/com/x/A.class", "com/x/A.class")).isEqualTo("file:/app/classes/");
        assertThat(ClassSources.container("file:/elsewhere", "com/x/A.class")).isEqualTo("file:/elsewhere");

        assertThat(ClassSources.normalize("jar:file:/app/a.jar!/")).isEqualTo("file:/app/a.jar");
        assertThat(ClassSources.normalize("file:///app/classes/")).isEqualTo("file:/app/classes");
        assertThat(ClassSources.normalize("/")).isEqualTo("/");
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path jar = directory.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : new java.util.TreeMap<>(entries).entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] sampleBytes() throws IOException {
        try (InputStream input = ClassSourcesTest.class.getClassLoader().getResourceAsStream(SAMPLE_RESOURCE)) {
            return input.readAllBytes();
        }
    }

    static final class Sample { }
}
