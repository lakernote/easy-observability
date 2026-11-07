package io.github.lakernote.meerkat.trace.internal.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Groups failed traces by what went wrong, so a page of identical failures reads as one problem with a count.
 * <p>Computed on request from the trace index that already holds every kept failure, so it costs nothing per request
 * and survives restarts. Two failures belong together when they have the same error type and the same message once
 * variable parts (numbers, IDs, quoted values) are masked; failures without an exception (a bare 5xx status) are
 * additionally split by entry.</p>
 */
public final class ErrorGroups {
    public static final int MAX_GROUPS = 200;
    private static final int MAX_ENTRIES = 5;
    private static final int MAX_TEMPLATE_CHARS = 200;
    private static final int BUCKETS = 24;
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{8,}\\b");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"|`[^`]*`");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern IDENTITY_HASH = Pattern.compile("@[0-9a-f]{4,}\\b");

    private ErrorGroups() {
    }

    /**
     * Groups failures that finished within {@code window} before {@code now}.
     *
     * @param source only this entry source, or all when blank
     */
    public static Report compute(TraceStore store, Instant now, Duration window, String source, int limit) {
        Instant since = now.minus(window);
        long bucketMillis = Math.max(1, window.toMillis() / BUCKETS);
        Map<String, Builder> groups = new HashMap<>();
        int[] failures = {0};
        store.forEachSummary(summary -> {
            if (!summary.error() || summary.finishedAt() == null) return;
            if (source != null && !source.isBlank() && !source.equals(summary.sourceType())) return;
            String type = blankToDash(summary.errorType());
            String template = template(summary.errorMessage());
            boolean bareStatus = type.startsWith("HTTP_");
            String key = type + '\n' + template + (bareStatus ? '\n' + summary.name() : "");
            Builder group = groups.get(key);
            boolean inWindow = !summary.finishedAt().isBefore(since);
            if (group == null) {
                group = new Builder(key, type, template);
                groups.put(key, group);
            }
            group.seenOverall(summary.finishedAt());
            if (!inWindow) return;
            failures[0]++;
            int bucket = (int) Math.min(BUCKETS - 1, Math.max(0,
                    (summary.finishedAt().toEpochMilli() - since.toEpochMilli()) / bucketMillis));
            group.add(summary, bucket);
        });
        List<Group> result = new ArrayList<>();
        for (Builder group : groups.values()) {
            if (group.count > 0) result.add(group.build(since));
        }
        result.sort(Comparator.comparingInt(Group::count).reversed().thenComparing(Group::lastSeen,
                Comparator.reverseOrder()));
        int max = Math.max(1, Math.min(limit, MAX_GROUPS));
        int total = result.size();
        return new Report(since, now, failures[0], total,
                List.copyOf(result.subList(0, Math.min(max, total))));
    }

    /** Masks the variable parts of an error message so occurrences with different IDs or values group together. */
    static String template(String message) {
        if (message == null || message.isBlank()) return "";
        String text = message.length() > 1_000 ? message.substring(0, 1_000) : message;
        int newline = text.indexOf('\n');
        if (newline >= 0) text = text.substring(0, newline);
        text = UUID.matcher(text).replaceAll("{id}");
        text = IDENTITY_HASH.matcher(text).replaceAll("@{hash}");
        text = QUOTED.matcher(text).replaceAll("'…'");
        text = HEX.matcher(text).replaceAll("{id}");
        text = NUMBER.matcher(text).replaceAll("{n}");
        text = text.strip();
        return text.length() > MAX_TEMPLATE_CHARS ? text.substring(0, MAX_TEMPLATE_CHARS) + "…" : text;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /**
     * @param newInWindow   no failure of this group was kept before the window started
     * @param trend         failures per twenty-fourth of the window, oldest first
     */
    public record Group(String fingerprint, String errorType, String message, String sampleMessage, int count,
                        Instant firstSeen, Instant lastSeen, boolean newInWindow, List<EntryCount> entries,
                        String latestTraceId, String latestSourceType, int[] trend) {
    }

    public record EntryCount(String name, String sourceType, int count) {
    }

    /**
     * @param failures failed traces in the window
     * @param total    groups found; only the largest are listed
     */
    public record Report(Instant since, Instant until, int failures, int total, List<Group> groups) {
    }

    private static final class Builder {
        private final String key;
        private final String type;
        private final String template;
        private final Map<String, EntryCount> entries = new LinkedHashMap<>();
        private final int[] trend = new int[BUCKETS];
        private Instant firstOverall;
        private Instant first;
        private Instant last;
        private String sampleMessage = "";
        private String latestTraceId = "";
        private String latestSource = "";
        private int count;

        Builder(String key, String type, String template) {
            this.key = key;
            this.type = type;
            this.template = template;
        }

        void seenOverall(Instant at) {
            if (firstOverall == null || at.isBefore(firstOverall)) firstOverall = at;
        }

        void add(TraceSummary summary, int bucket) {
            count++;
            trend[bucket]++;
            Instant at = summary.finishedAt();
            if (first == null || at.isBefore(first)) first = at;
            if (last == null || at.isAfter(last)) {
                last = at;
                latestTraceId = summary.traceId();
                latestSource = summary.sourceType();
                sampleMessage = summary.errorMessage() == null ? "" : summary.errorMessage();
            }
            String name = summary.name() == null ? "" : summary.name();
            entries.merge(summary.sourceType() + '\n' + name, new EntryCount(name, summary.sourceType(), 1),
                    (a, b) -> new EntryCount(a.name(), a.sourceType(), a.count() + 1));
        }

        Group build(Instant since) {
            List<EntryCount> top = new ArrayList<>(entries.values());
            top.sort(Comparator.comparingInt(EntryCount::count).reversed());
            return new Group(Integer.toHexString(key.hashCode()), type, template, sampleMessage, count, first, last,
                    !firstOverall.isBefore(since), List.copyOf(top.subList(0, Math.min(MAX_ENTRIES, top.size()))),
                    latestTraceId, latestSource, trend.clone());
        }
    }
}
