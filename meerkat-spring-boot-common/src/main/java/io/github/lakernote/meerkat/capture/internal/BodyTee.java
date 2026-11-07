package io.github.lakernote.meerkat.capture.internal;

import io.github.lakernote.meerkat.trace.internal.CaptureValue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Keeps the first bytes or characters of a body while it streams through, without buffering or delaying the
 * stream itself. Rendered when the captured trace completes, which may be after the body was fully written.
 * <p>File uploads and downloads (binary or {@code multipart/*} content, or {@code Content-Disposition: attachment})
 * are only counted and recorded as a label, never copied.</p>
 */
public final class BodyTee implements CaptureValue {
    private final int maxBytes;
    private final Supplier<String> contentType;
    private final Supplier<String> characterEncoding;
    private final String direction;
    private final Supplier<String> contentDisposition;
    /** Decided on the first write: 1 for a file, -1 otherwise, 0 before any write. */
    private int file;
    private byte[] bytes = new byte[0];
    private int byteCount;
    private StringBuilder chars;
    private long total;

    public BodyTee(int maxBytes, Supplier<String> contentType, Supplier<String> characterEncoding) {
        this(maxBytes, null, contentType, characterEncoding, null);
    }

    /**
     * @param direction {@link #UPLOAD} or {@link #DOWNLOAD}, used in the file label
     * @param contentDisposition the {@code Content-Disposition} header, or {@code null}
     */
    public BodyTee(int maxBytes, String direction, Supplier<String> contentType, Supplier<String> characterEncoding,
                   Supplier<String> contentDisposition) {
        this.maxBytes = Math.max(0, maxBytes);
        this.direction = direction;
        this.contentType = contentType == null ? () -> null : contentType;
        this.characterEncoding = characterEncoding == null ? () -> null : characterEncoding;
        this.contentDisposition = contentDisposition == null ? () -> null : contentDisposition;
    }

    public static final String UPLOAD = "upload";
    public static final String DOWNLOAD = "download";

    private boolean countOnly() {
        if (file == 0) file = isFile(safe(contentType), safe(contentDisposition)) ? 1 : -1;
        return file > 0;
    }

    public synchronized void write(int value) {
        total++;
        if (byteCount >= maxBytes || countOnly()) return;
        ensure(byteCount + 1);
        bytes[byteCount++] = (byte) value;
    }

    public synchronized void write(byte[] source, int offset, int length) {
        if (length <= 0) return;
        total += length;
        if (countOnly()) return;
        int copy = Math.min(length, maxBytes - byteCount);
        if (copy <= 0) return;
        ensure(byteCount + copy);
        System.arraycopy(source, offset, bytes, byteCount, copy);
        byteCount += copy;
    }

    public synchronized void write(char[] source, int offset, int length) {
        if (length <= 0) return;
        total += length;
        if (countOnly()) return;
        if (chars == null) chars = new StringBuilder();
        int copy = Math.min(length, maxBytes - chars.length());
        if (copy > 0) chars.append(source, offset, copy);
    }

    public synchronized void write(String source, int offset, int length) {
        if (source == null || length <= 0) return;
        total += length;
        if (countOnly()) return;
        if (chars == null) chars = new StringBuilder();
        int copy = Math.min(length, maxBytes - chars.length());
        if (copy > 0) chars.append(source, offset, offset + copy);
    }

    public synchronized boolean empty() {
        return total == 0;
    }

    private void ensure(int capacity) {
        if (capacity <= bytes.length) return;
        bytes = Arrays.copyOf(bytes, Math.min(maxBytes, Math.max(capacity, Math.max(256, bytes.length * 2))));
    }

    @Override
    public synchronized String render(int maxChars) {
        if (total == 0) return "";
        String type = safe(contentType);
        String disposition = safe(contentDisposition);
        if (file > 0 || isFile(type, disposition)) return fileLabel(direction, type, disposition, total);
        StringBuilder out = new StringBuilder();
        if (byteCount > 0) out.append(CaptureText.bytes(bytes, 0, byteCount, charset(), maxChars));
        if (chars != null) out.append(chars, 0, Math.min(chars.length(), Math.max(0, maxChars - out.length())));
        long kept = byteCount + (chars == null ? 0 : chars.length());
        if (total > kept) out.append("\n… [body truncated, ").append(total).append(" total]");
        return out.toString();
    }

    private Charset charset() {
        String encoding = safe(characterEncoding);
        try {
            return encoding == null || encoding.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (RuntimeException invalid) {
            return StandardCharsets.UTF_8;
        }
    }

    private static String safe(Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Whether a body is a file transfer, whose content is not recorded. */
    public static boolean isFile(String contentType, String contentDisposition) {
        if (contentDisposition != null
                && contentDisposition.trim().toLowerCase(Locale.ROOT).startsWith("attachment")) return true;
        return isBinaryType(contentType)
                || (contentType != null && contentType.trim().toLowerCase(Locale.ROOT).startsWith("multipart/"));
    }

    /** For example {@code [file upload, multipart/form-data, 10485760 bytes; content not recorded]}. */
    public static String fileLabel(String direction, String contentType, String contentDisposition, long bytes) {
        StringBuilder label = new StringBuilder("[file");
        if (direction != null) label.append(' ').append(direction);
        if (contentType != null && !contentType.isBlank() && !"null".equals(contentType)) {
            int parameters = contentType.indexOf(';');
            label.append(", ").append((parameters < 0 ? contentType : contentType.substring(0, parameters)).trim());
        }
        label.append(", ").append(bytes).append(" bytes");
        if (contentDisposition != null && !contentDisposition.isBlank()) {
            label.append(", ").append(contentDisposition.length() > 200
                    ? contentDisposition.substring(0, 200) + "…" : contentDisposition);
        }
        return label.append("; content not recorded]").toString();
    }

    static boolean isBinaryType(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("video/")
                || type.startsWith("application/octet-stream") || type.startsWith("application/zip")
                || type.startsWith("application/pdf") || type.startsWith("application/x-protobuf")
                || type.startsWith("application/grpc") || type.startsWith("font/");
    }
}
