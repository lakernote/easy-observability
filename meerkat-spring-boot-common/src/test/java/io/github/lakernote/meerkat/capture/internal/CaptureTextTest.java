package io.github.lakernote.meerkat.capture.internal;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureTextTest {

    @Test
    void rendersSimpleValues() {
        assertThat(CaptureText.of(null, 100)).isEqualTo("null");
        assertThat(CaptureText.of("abc", 100)).isEqualTo("abc");
        assertThat(CaptureText.of(42, 100)).isEqualTo("42");
        assertThat(CaptureText.of(new byte[] {'h', 'i'}, 100)).isEqualTo("hi");
    }

    @Test
    void truncatesLongText() {
        String rendered = CaptureText.of("x".repeat(50), 20);

        assertThat(rendered).isEqualTo("x".repeat(20) + "…");
        assertThat(CaptureText.of("y".repeat(50), 5)).isEqualTo("yyyyy…");
    }

    @Test
    void rendersBinaryAsHex() {
        assertThat(CaptureText.of(new byte[] {0, 1, (byte) 0xff}, 100)).isEqualTo("[binary 3 bytes] 0x0001ff");
    }

    @Test
    void rendersByteBufferWithoutConsumingIt() {
        ByteBuffer buffer = ByteBuffer.wrap("hey".getBytes(StandardCharsets.UTF_8));

        assertThat(CaptureText.of(buffer, 100)).isEqualTo("hey");
        assertThat(buffer.position()).isZero();
    }

    @Test
    void rendersMapsCollectionsAndArrays() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", List.of("x", "y"));

        assertThat(CaptureText.of(map, 100)).isEqualTo("{a=1, b=[x, y]}");
        assertThat(CaptureText.of(new Object[] {"a", 1, null}, 100)).isEqualTo("[a, 1, null]");
        assertThat(CaptureText.of(new int[] {1, 2, 3}, 100)).isEqualTo("[1, 2, 3]");
        assertThat(CaptureText.of(List.of(), 100)).isEqualTo("[]");
    }

    @Test
    void limitsElementCounts() {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            map.put(i, i);
            list.add(i);
        }

        assertThat(CaptureText.of(map, 100_000)).endsWith("… 150 entries}");
        assertThat(CaptureText.of(list, 100_000)).endsWith("… 150 items]");
        assertThat(CaptureText.of(new int[150], 100_000)).endsWith("… 150 items]");
    }

    @Test
    void stopsRenderingLargeStructuresAtLimit() {
        Map<Integer, String> map = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) map.put(i, "value-" + i);

        assertThat(CaptureText.of(map, 30)).hasSize(31).endsWith("…");
        assertThat(CaptureText.of(new ArrayList<>(map.values()), 30)).hasSize(31).endsWith("…");
        assertThat(CaptureText.of(new long[500], 30)).hasSize(31).endsWith("…");
    }

    @Test
    void limitsNestingDepth() {
        Object nested = List.of(List.of(List.of(List.of(List.of(1)))));

        assertThat(CaptureText.of(nested, 100)).startsWith("[[[[").doesNotContain("[[[[[").endsWith("]]]]");
    }

    @Test
    void neverThrowsForBrokenValues() {
        Object broken = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("broken");
            }
        };

        assertThat(CaptureText.of(broken, 100)).isEqualTo("[unreadable IllegalStateException]");
    }

    @Test
    void decodesBytesWithCharsetAndLimits() {
        byte[] latin = {(byte) 0xE9};
        byte[] text = "hello world".getBytes(StandardCharsets.UTF_8);

        assertThat(CaptureText.bytes(text, 0, 0, null, 100)).isEmpty();
        assertThat(CaptureText.bytes(text, 0, text.length, null, 100)).isEqualTo("hello world");
        assertThat(CaptureText.bytes(text, 6, 5, StandardCharsets.UTF_8, 100)).isEqualTo("world");
        assertThat(CaptureText.bytes(text, 0, text.length, StandardCharsets.UTF_8, 5)).isEqualTo("hello");
        assertThat(CaptureText.bytes(latin, 0, 1, StandardCharsets.ISO_8859_1, 10)).isEqualTo("é");
        assertThat(CaptureText.bytes("a\tb\r\nc".getBytes(StandardCharsets.UTF_8), 0, 6, null, 10))
                .isEqualTo("a\tb\r\nc");
    }

    @Test
    void decodesOnlyTheShownPrefixOfLargeValues() {
        byte[] large = new byte[10 * 1024 * 1024];
        java.util.Arrays.fill(large, (byte) 'a');
        byte[] chinese = "你好世界".getBytes(StandardCharsets.UTF_8);

        assertThat(CaptureText.bytes(large, 1, large.length - 1, null, 3)).isEqualTo("aaa");
        assertThat(CaptureText.bytes(chinese, 0, chinese.length, StandardCharsets.UTF_8, 2)).isEqualTo("你好");
    }

    @Test
    void treatsControlCharactersAsBinary() {
        byte[] control = new byte[10];
        java.util.Arrays.fill(control, (byte) 1);

        assertThat(CaptureText.bytes(control, 0, control.length, null, 4)).isEqualTo("[binary 10 bytes] 0x0101");
        assertThat(CaptureText.bytes(control, 0, control.length, null, 0)).isEqualTo("[binary 10 bytes] 0x01");
    }
}
