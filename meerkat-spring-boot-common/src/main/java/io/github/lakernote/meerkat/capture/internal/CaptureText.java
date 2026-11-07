package io.github.lakernote.meerkat.capture.internal;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/** Renders captured values as bounded readable text; never throws. */
public final class CaptureText {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MAX_ELEMENTS = 100;

    private CaptureText() {
    }

    public static String of(Object value, int maxChars) {
        StringBuilder out = new StringBuilder(Math.min(Math.max(16, maxChars), 256));
        try {
            append(out, value, Math.max(16, maxChars), 0);
        } catch (RuntimeException | LinkageError failure) {
            out.append("[unreadable ").append(failure.getClass().getSimpleName()).append(']');
        }
        return out.length() > maxChars ? out.substring(0, Math.max(0, maxChars)) + "…" : out.toString();
    }

    private static void append(StringBuilder out, Object value, int maxChars, int depth) {
        if (out.length() > maxChars) return;
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence text) {
            out.append(text, 0, Math.min(text.length(), maxChars - out.length() + 1));
        } else if (value instanceof byte[] bytes) {
            out.append(bytes(bytes, 0, bytes.length, StandardCharsets.UTF_8, maxChars - out.length() + 1));
        } else if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.duplicate();
            byte[] bytes = new byte[Math.min(copy.remaining(), maxChars + 1)];
            copy.get(bytes);
            out.append(bytes(bytes, 0, bytes.length, StandardCharsets.UTF_8, maxChars - out.length() + 1));
        } else if (depth >= 4) {
            out.append(value.getClass().getSimpleName());
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (out.length() > maxChars) return;
                if (count > 0) out.append(", ");
                if (count++ >= MAX_ELEMENTS) {
                    out.append("… ").append(map.size()).append(" entries");
                    break;
                }
                append(out, entry.getKey(), maxChars, depth + 1);
                out.append('=');
                append(out, entry.getValue(), maxChars, depth + 1);
            }
            out.append('}');
        } else if (value instanceof Collection<?> collection) {
            appendElements(out, collection.iterator(), collection.size(), maxChars, depth);
        } else if (value instanceof Object[] array) {
            appendElements(out, java.util.Arrays.asList(array).iterator(), array.length, maxChars, depth);
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            out.append('[');
            for (int index = 0; index < length && out.length() <= maxChars; index++) {
                if (index > 0) out.append(", ");
                if (index >= MAX_ELEMENTS) {
                    out.append("… ").append(length).append(" items");
                    break;
                }
                out.append(java.lang.reflect.Array.get(value, index));
            }
            out.append(']');
        } else {
            out.append(value);
        }
    }

    private static void appendElements(StringBuilder out, Iterator<?> iterator, int size, int maxChars, int depth) {
        out.append('[');
        int count = 0;
        while (iterator.hasNext() && out.length() <= maxChars) {
            Object element = iterator.next();
            if (count > 0) out.append(", ");
            if (count++ >= MAX_ELEMENTS) {
                out.append("… ").append(size).append(" items");
                break;
            }
            append(out, element, maxChars, depth + 1);
        }
        out.append(']');
    }

    /** Decodes text bytes, or shows binary content as hex. */
    public static String bytes(byte[] bytes, int offset, int length, Charset charset, int maxChars) {
        if (length <= 0) return "";
        if (looksLikeText(bytes, offset, length)) {
            // Decode only what can be shown (at most 4 bytes per char), not a multi-megabyte value.
            int decoded = (int) Math.min(length, Math.max(1L, maxChars) * 4L);
            String text = new String(bytes, offset, decoded, charset == null ? StandardCharsets.UTF_8 : charset);
            return text.length() > maxChars ? text.substring(0, Math.max(0, maxChars)) : text;
        }
        int shown = Math.min(length, Math.max(1, maxChars / 2));
        StringBuilder hex = new StringBuilder(shown * 2 + 32).append("[binary ").append(length).append(" bytes] 0x");
        for (int index = offset; index < offset + shown; index++) {
            hex.append(HEX[(bytes[index] >> 4) & 0xF]).append(HEX[bytes[index] & 0xF]);
        }
        return hex.toString();
    }

    private static boolean looksLikeText(byte[] bytes, int offset, int length) {
        int control = 0;
        int sample = Math.min(length, 512);
        for (int index = offset; index < offset + sample; index++) {
            int value = bytes[index] & 0xFF;
            if (value == 0) return false;
            if (value < 0x20 && value != '\n' && value != '\r' && value != '\t') control++;
        }
        return control * 20 <= sample;
    }
}
