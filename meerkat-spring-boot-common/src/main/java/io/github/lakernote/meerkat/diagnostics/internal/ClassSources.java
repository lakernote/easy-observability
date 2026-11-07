package io.github.lakernote.meerkat.diagnostics.internal;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shows where a class comes from and finds classes that exist in more than one jar, using only the application
 * class loader: no agent and no instrumentation. Looking a class up loads it without initializing it.
 */
public final class ClassSources {
    static final int MAX_DUPLICATES = 500;
    static final int MAX_SOURCES = 2_000;
    static final int MAX_ENTRIES = 1_000_000;
    static final int MAX_UNREADABLE = 50;
    static final int MAX_PAIRS = 200;
    static final int PAIR_SAMPLES = 5;
    static final long SCAN_TIME_MS = 20_000;
    private static final Pattern CLASS_NAME =
            Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*");
    private final ClassLoader classLoader;
    private final String classPath;
    private final long scanTimeMs;
    private final ReentrantLock scanLock = new ReentrantLock();

    public ClassSources(ClassLoader classLoader) {
        this(classLoader, System.getProperty("java.class.path", ""), SCAN_TIME_MS);
    }

    ClassSources(ClassLoader classLoader, String classPath, long scanTimeMs) {
        this.classLoader = classLoader;
        this.classPath = classPath;
        this.scanTimeMs = scanTimeMs;
    }

    /**
     * Every copy of {@code className} visible to the application class loader, which one the class is actually
     * loaded from, and the class loader chain.
     *
     * @throws IllegalArgumentException for a malformed class name
     */
    public ClassLookup lookup(String className) {
        String name = className == null ? "" : className.trim().replace('/', '.');
        if (name.endsWith(".class")) name = name.substring(0, name.length() - ".class".length());
        if (name.length() > 500 || !CLASS_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid class name");
        }
        String resource = name.replace('.', '/') + ".class";
        String error = null;
        List<URL> urls = new ArrayList<>();
        try {
            urls.addAll(Collections.list(classLoader.getResources(resource)));
        } catch (IOException failure) {
            error = failure.toString();
        }
        URL first = classLoader.getResource(resource);
        Class<?> loaded = null;
        try {
            loaded = Class.forName(name, false, classLoader);
        } catch (ClassNotFoundException notFound) {
            if (error == null) error = urls.isEmpty() ? "Class not found" : "Class could not be loaded";
        } catch (LinkageError failure) {
            error = failure.toString();
        }
        String loadedFrom = loaded == null ? null : location(loaded);
        List<Copy> copies = new ArrayList<>(urls.size());
        for (URL url : urls) {
            String container = container(url.toString(), resource);
            copies.add(new Copy(container, url.toString(), url.equals(first),
                    loadedFrom != null && normalize(container).equals(normalize(loadedFrom))));
        }
        ClassLoader loader = loaded != null ? loaded.getClassLoader() : classLoader;
        return new ClassLookup(name, resource, loaded != null, loadedFrom,
                loaded == null ? null : describe(loader),
                loaded == null || loaded.getModule().getName() == null ? null : loaded.getModule().getName(),
                chain(loaded == null ? classLoader : loader), copies, copies.size() > 1, error);
    }

    /**
     * Scans the jars and directories on the class path, including nested jars of a Spring Boot executable jar when
     * its class loader exposes them, for classes defined more than once. Returns {@code null} while another scan
     * runs. Stops after {@link #MAX_DUPLICATES} duplicates, {@link #MAX_ENTRIES} classes or 20 seconds.
     */
    public DuplicateScan scanDuplicates() {
        if (!scanLock.tryLock()) return null;
        try {
            return new Scan(sources(), System.nanoTime() + scanTimeMs * 1_000_000).run();
        } finally {
            scanLock.unlock();
        }
    }

    /** Class path entries, class loader URLs and every jar holding a manifest, keyed by normalized location. */
    Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) continue;
            File file = new File(entry);
            if (file.exists()) addSource(sources, file.toURI().toString());
        }
        for (ClassLoader loader = classLoader; loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlLoader) {
                for (URL url : urlLoader.getURLs()) addSource(sources, url.toString());
            }
        }
        try {
            Enumeration<URL> manifests = classLoader.getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                String url = manifests.nextElement().toString();
                if (url.startsWith("jar:")) addSource(sources, url.substring(0, url.lastIndexOf("!/") + 2));
                else if (url.startsWith("file:")) addSource(sources, container(url, "META-INF/MANIFEST.MF"));
            }
        } catch (IOException ignored) {
            // The class path entries above are still scanned.
        }
        return sources;
    }

    private static void addSource(Map<String, String> sources, String url) {
        if (sources.size() < MAX_SOURCES) sources.putIfAbsent(normalize(url), url);
    }

    /** The jar or directory holding {@code resource}, as found at {@code url}. */
    static String container(String url, String resource) {
        if (url.startsWith("jar:")) {
            int separator = url.lastIndexOf("!/");
            return separator > 4 ? url.substring(4, separator) : url.substring(4);
        }
        if (url.startsWith("jrt:")) {
            int module = url.indexOf('/', "jrt:/".length());
            return module > 0 ? url.substring(0, module) : url;
        }
        return url.endsWith(resource) ? url.substring(0, url.length() - resource.length()) : url;
    }

    /** Comparable form of a jar or directory URL: no {@code jar:} prefix, {@code !/} suffix or trailing slash. */
    static String normalize(String location) {
        String value = location;
        if (value.startsWith("jar:")) value = value.substring(4);
        if (value.endsWith("!/")) value = value.substring(0, value.length() - 2);
        while (value.endsWith("/") && value.length() > 1) value = value.substring(0, value.length() - 1);
        if (value.startsWith("file:///")) value = "file:/" + value.substring("file:///".length());
        return value;
    }

    private static String location(Class<?> type) {
        CodeSource source = type.getProtectionDomain() == null ? null : type.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) return source.getLocation().toString();
        String module = type.getModule().getName();
        return module != null && type.getClassLoader() == null ? "jrt:/" + module : null;
    }

    private static List<String> chain(ClassLoader start) {
        List<String> chain = new ArrayList<>();
        for (ClassLoader loader = start; loader != null && chain.size() < 20; loader = loader.getParent()) {
            chain.add(describe(loader));
        }
        chain.add("bootstrap");
        return chain;
    }

    private static String describe(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getName() == null ? loader.getClass().getName()
                : loader.getClass().getName() + " (" + loader.getName() + ")";
    }

    /** One scan over the collected sources. */
    private static final class Scan {
        private final Map<String, String> sources;
        private final List<String> names = new ArrayList<>();
        private final long deadline;
        private final Map<String, Integer> firstSource = new HashMap<>();
        private final Map<String, List<Integer>> duplicates = new LinkedHashMap<>();
        private final List<Unreadable> unreadable = new ArrayList<>();
        private int entries;
        private int scannedSources;
        private String stopReason;

        Scan(Map<String, String> sources, long deadline) {
            this.sources = sources;
            this.deadline = deadline;
        }

        DuplicateScan run() {
            long started = System.nanoTime();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                if (stopReason != null) break;
                int index = names.size();
                names.add(source.getKey());
                try {
                    read(source.getValue(), className -> index(className, index));
                    scannedSources++;
                } catch (IOException | RuntimeException failure) {
                    if (unreadable.size() < MAX_UNREADABLE) unreadable.add(new Unreadable(source.getKey(), failure.toString()));
                }
            }
            List<DuplicateClass> classes = new ArrayList<>(duplicates.size());
            Map<String, Pair> pairs = new LinkedHashMap<>();
            for (Map.Entry<String, List<Integer>> duplicate : duplicates.entrySet()) {
                List<Integer> where = duplicate.getValue();
                classes.add(new DuplicateClass(duplicate.getKey(), where.stream().map(names::get).toList()));
                for (int other = 1; other < where.size(); other++) {
                    String first = names.get(where.get(0));
                    String second = names.get(where.get(other));
                    pairs.computeIfAbsent(first + "\n" + second, key -> new Pair(first, second)).add(duplicate.getKey());
                }
            }
            List<JarPair> jarPairs = pairs.values().stream()
                    .sorted(Comparator.comparingInt((Pair pair) -> pair.count).reversed())
                    .limit(MAX_PAIRS)
                    .map(pair -> new JarPair(pair.first, pair.second, pair.count, List.copyOf(pair.samples)))
                    .toList();
            return new DuplicateScan(sources.size(), scannedSources, entries, (System.nanoTime() - started) / 1_000_000,
                    stopReason != null, stopReason, classes, jarPairs, unreadable);
        }

        private void index(String className, int source) {
            if (stopReason != null) return;
            if (++entries > MAX_ENTRIES) {
                stopReason = "class limit";
                return;
            }
            if ((entries & 1023) == 0 && System.nanoTime() > deadline) {
                stopReason = "time limit";
                return;
            }
            Integer previous = firstSource.putIfAbsent(className, source);
            if (previous == null || previous == source) return;
            List<Integer> where = duplicates.get(className);
            if (where == null) {
                if (duplicates.size() >= MAX_DUPLICATES) {
                    stopReason = "duplicate limit";
                    return;
                }
                where = new ArrayList<>(List.of(previous));
                duplicates.put(className, where);
            }
            if (!where.contains(source)) where.add(source);
        }

        private void read(String url, Consumer<String> classes) throws IOException {
            if (url.startsWith("file:")) {
                Path path = Path.of(URI.create(url));
                if (Files.isDirectory(path)) {
                    readDirectory(path, classes);
                } else {
                    try (JarFile jar = new JarFile(path.toFile())) {
                        readJar(jar, "", classes);
                    }
                }
                return;
            }
            // Open the jar root: jars do not always contain directory entries such as BOOT-INF/classes/.
            int separator = url.lastIndexOf("!/");
            String entry = separator < 0 ? "" : url.substring(separator + 2);
            URLConnection connection = new URL(separator < 0 ? url : url.substring(0, separator + 2)).openConnection();
            if (!(connection instanceof JarURLConnection jarConnection)) {
                throw new IOException("Unsupported class path entry");
            }
            // Cached jar files are shared with the class loader, so they are not closed here.
            JarFile jar = jarConnection.getJarFile();
            readJar(jar, entry.isEmpty() || entry.endsWith("/") ? entry : entry + "/", classes);
        }

        private void readDirectory(Path root, Consumer<String> classes) throws IOException {
            try (Stream<Path> files = Files.walk(root, 64)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (stopReason != null) return;
                    String relative = root.relativize(file).toString().replace(File.separatorChar, '/');
                    String className = className(relative);
                    if (className != null) classes.accept(className);
                }
            }
        }

        private void readJar(JarFile jar, String prefix, Consumer<String> classes) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements() && stopReason == null) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(prefix)) continue;
                String className = className(name.substring(prefix.length()));
                if (className != null) classes.accept(className);
            }
        }

        /** Skips metadata, versioned entries and the nested content of executable jars, which are scanned separately. */
        private static String className(String entry) {
            if (!entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.startsWith("BOOT-INF/")
                    || entry.startsWith("WEB-INF/") || entry.endsWith("module-info.class")
                    || entry.endsWith("package-info.class")) {
                return null;
            }
            return entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
        }
    }

    private static final class Pair {
        private final String first;
        private final String second;
        private final List<String> samples = new ArrayList<>();
        private int count;

        Pair(String first, String second) {
            this.first = first;
            this.second = second;
        }

        void add(String className) {
            count++;
            if (samples.size() < PAIR_SAMPLES) samples.add(className);
        }
    }

    /**
     * A copy of the class file: {@code location} is its jar or directory, {@code first} marks the copy the class
     * loader finds first and {@code loaded} the one the loaded class came from.
     */
    public record Copy(String location, String url, boolean first, boolean loaded) { }

    /** {@code duplicate} is set when more than one copy is visible. */
    public record ClassLookup(String className, String resource, boolean loaded, String loadedFrom, String classLoader,
                              String module, List<String> classLoaderChain, List<Copy> copies, boolean duplicate,
                              String error) { }

    public record DuplicateClass(String className, List<String> locations) { }

    /** {@code classes} counts the classes found in both locations; {@code samples} lists a few of them. */
    public record JarPair(String first, String second, int classes, List<String> samples) { }

    public record Unreadable(String location, String error) { }

    /** {@code stopReason} is set when the scan stopped early: duplicate limit, class limit or time limit. */
    public record DuplicateScan(int sources, int scannedSources, int classes, long durationMs, boolean truncated,
                                String stopReason, List<DuplicateClass> duplicates, List<JarPair> pairs,
                                List<Unreadable> unreadable) { }
}
