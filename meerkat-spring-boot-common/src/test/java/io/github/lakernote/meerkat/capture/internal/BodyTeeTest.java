package io.github.lakernote.meerkat.capture.internal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BodyTeeTest {

    @Test
    void rendersNothingForEmptyBody() {
        BodyTee tee = new BodyTee(10, null, null);

        assertThat(tee.empty()).isTrue();
        assertThat(tee.render(100)).isEmpty();
        tee.write(new byte[0], 0, 0);
        tee.write((String) null, 0, 3);
        tee.write("abc", 0, 0);
        tee.write(new char[0], 0, 0);
        assertThat(tee.empty()).isTrue();
    }

    @Test
    void keepsFirstBytesAndNotesTruncation() {
        BodyTee tee = new BodyTee(5, () -> "text/plain", () -> "UTF-8");
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);

        tee.write(body, 0, body.length);
        tee.write(body, 0, body.length);

        assertThat(tee.empty()).isFalse();
        assertThat(tee.render(100)).isEqualTo("hello\n… [body truncated, 22 total]");
    }

    @Test
    void keepsSingleBytesAndGrowsBuffer() {
        BodyTee tee = new BodyTee(600, () -> "application/json", () -> null);
        for (int i = 0; i < 700; i++) tee.write('a');

        assertThat(tee.render(1000)).startsWith("a".repeat(600)).endsWith("[body truncated, 700 total]");
    }

    @Test
    void keepsCharactersFromWriters() {
        BodyTee tee = new BodyTee(6, () -> null, () -> null);

        tee.write("abcd".toCharArray(), 0, 4);
        tee.write("xyz-123", 1, 4);

        assertThat(tee.render(100)).isEqualTo("abcdyz\n… [body truncated, 8 total]");
        assertThat(tee.render(3)).startsWith("abc\n");
    }

    @Test
    void hidesBinaryContentTypes() {
        BodyTee tee = new BodyTee(10, () -> "image/png", () -> null);
        tee.write(new byte[] {1, 2, 3}, 0, 3);

        assertThat(tee.render(100)).isEqualTo("[file, image/png, 3 bytes; content not recorded]");
    }

    @Test
    void countsFileTransfersWithoutCopyingThem() {
        BodyTee upload = new BodyTee(100, BodyTee.UPLOAD, () -> "multipart/form-data; boundary=x", () -> null, null);
        BodyTee download = new BodyTee(100, BodyTee.DOWNLOAD, () -> "text/csv", () -> null,
                () -> "attachment; filename=\"a.csv\"");
        byte[] chunk = "a,b\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 1000; i++) {
            upload.write(chunk, 0, chunk.length);
            download.write(chunk, 0, chunk.length);
        }
        upload.write('x');
        upload.write("yz", 0, 2);
        upload.write(new char[] {'!'}, 0, 1);

        assertThat(upload.render(100)).isEqualTo("[file upload, multipart/form-data, 4004 bytes; content not recorded]");
        assertThat(download.render(100))
                .isEqualTo("[file download, text/csv, 4000 bytes, attachment; filename=\"a.csv\"; content not recorded]");
        assertThat(upload).extracting("bytes").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.BYTE_ARRAY)
                .isEmpty();
        assertThat(upload).extracting("chars").isNull();
    }

    @Test
    void detectsFilesAndBoundsTheirLabel() {
        assertThat(BodyTee.isFile(null, null)).isFalse();
        assertThat(BodyTee.isFile("application/json", "inline")).isFalse();
        assertThat(BodyTee.isFile(" Multipart/mixed", null)).isTrue();
        assertThat(BodyTee.isFile("text/plain", " ATTACHMENT")).isTrue();
        assertThat(BodyTee.fileLabel(null, "null", "", 1)).isEqualTo("[file, 1 bytes; content not recorded]");
        assertThat(BodyTee.fileLabel(null, null, "a".repeat(300), 1)).hasSize(240).contains("…; content not recorded]");
    }

    @Test
    void fallsBackToUtf8ForInvalidOrFailingCharsets() {
        byte[] body = "é".getBytes(StandardCharsets.UTF_8);
        BodyTee invalid = new BodyTee(10, () -> "text/plain", () -> "no-such-charset");
        BodyTee failing = new BodyTee(10, () -> {
            throw new IllegalStateException("closed");
        }, () -> {
            throw new IllegalStateException("closed");
        });
        BodyTee latin = new BodyTee(10, () -> "text/plain", () -> "ISO-8859-1");
        invalid.write(body, 0, body.length);
        failing.write(body, 0, body.length);
        latin.write(body, 0, body.length);

        assertThat(invalid.render(100)).isEqualTo("é");
        assertThat(failing.render(100)).isEqualTo("é");
        assertThat(latin.render(100)).isEqualTo("Ã©");
    }

    @Test
    void keepsNothingWithNegativeLimit() {
        BodyTee tee = new BodyTee(-1, null, null);
        tee.write('a');
        tee.write(new byte[] {'b'}, 0, 1);
        tee.write("c", 0, 1);

        assertThat(tee.render(100)).isEqualTo("\n… [body truncated, 3 total]");
    }

    @Test
    void detectsBinaryContentTypes() {
        assertThat(BodyTee.isBinaryType(null)).isFalse();
        assertThat(BodyTee.isBinaryType("application/json")).isFalse();
        assertThat(BodyTee.isBinaryType("text/html; charset=UTF-8")).isFalse();
        for (String type : new String[] {"IMAGE/PNG", "audio/mpeg", "video/mp4", "application/octet-stream",
                "application/zip", "application/pdf", "application/x-protobuf", "application/grpc+proto",
                "font/woff2"}) {
            assertThat(BodyTee.isBinaryType(type)).as(type).isTrue();
        }
    }

    @Test
    void teeInputStreamCopiesBytesAsTheyAreRead() throws Exception {
        BodyTee tee = new BodyTee(100, () -> "text/plain", () -> "UTF-8");
        InputStream in = new TeeInputStream(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), tee);

        assertThat(in.markSupported()).isFalse();
        assertThat(in.read()).isEqualTo('h');
        byte[] buffer = new byte[10];
        assertThat(in.read(buffer, 0, buffer.length)).isEqualTo(4);
        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.read(buffer, 0, buffer.length)).isEqualTo(-1);

        assertThat(tee.render(100)).isEqualTo("hello");
    }
}
