package io.github.lakernote.meerkat.diagnostics.internal;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CallTreeTest {
    /** Frames listed outermost first, as read in a flame graph; the tree takes them innermost first. */
    static StackTraceElement[] stack(String... frames) {
        StackTraceElement[] stack = new StackTraceElement[frames.length];
        for (int index = 0; index < frames.length; index++) {
            String frame = frames[frames.length - 1 - index];
            int dot = frame.lastIndexOf('.');
            stack[index] = new StackTraceElement(frame.substring(0, dot), frame.substring(dot + 1), null, -1);
        }
        return stack;
    }

    @Test
    void mergesStacksIntoATreeOrderedBySamples() {
        CallTree tree = new CallTree(100, 10);
        tree.add(null, stack("app.Main.run", "app.Service.slow"));
        tree.add(null, stack("app.Main.run", "app.Service.slow"));
        tree.add(null, stack("app.Main.run", "app.Service.fast"));
        tree.add(null, stack("app.Main.run"));

        assertThat(tree.samples()).isEqualTo(4);
        assertThat(tree.nodes()).isEqualTo(4);
        assertThat(tree.truncatedSamples()).isZero();
        CpuProfiler.Frame root = tree.frames();
        assertThat(root.name()).isEqualTo(CallTree.ROOT);
        assertThat(root.total()).isEqualTo(4);
        CpuProfiler.Frame run = root.children().get(0);
        assertThat(run.name()).isEqualTo("app.Main.run");
        assertThat(run.total()).isEqualTo(4);
        assertThat(run.self()).isEqualTo(1);
        assertThat(run.children()).extracting(CpuProfiler.Frame::name).containsExactly("app.Service.slow", "app.Service.fast");
        assertThat(tree.hotMethods(2)).extracting(CpuProfiler.HotMethod::method)
                .containsExactly("app.Service.slow", "app.Main.run");
        assertThat(tree.hotMethods(10).get(0).percent()).isEqualTo(50.0);
        assertThat(tree.collapsed().lines().sorted()).containsExactly(
                "app.Main.run 1", "app.Main.run;app.Service.fast 1", "app.Main.run;app.Service.slow 2");
    }

    @Test
    void groupsByThreadAndEscapesCollapsedSeparators() {
        CallTree tree = new CallTree(100, 10);
        tree.add("http-nio 1", stack("app.Main.run"));
        tree.add("http-nio 1", stack("app.Main.run"));

        assertThat(tree.frames().children().get(0).name()).isEqualTo("[http-nio 1]");
        assertThat(tree.collapsed()).isEqualTo("[http-nio_1];app.Main.run 2\n");
        assertThat(tree.hotMethods(5)).singleElement().extracting(CpuProfiler.HotMethod::method).isEqualTo("app.Main.run");
        assertThat(new CallTree(10, 10).hotMethods(5)).isEmpty();
    }

    @Test
    void keepsTheInnermostFramesOfDeepStacks() {
        CallTree tree = new CallTree(100, 3);
        tree.add(null, stack("a.A.one", "a.A.two", "a.A.three", "a.A.four", "a.A.five"));

        CpuProfiler.Frame deep = tree.frames().children().get(0);
        assertThat(deep.name()).isEqualTo(CallTree.DEEP_STACK);
        assertThat(deep.children().get(0).name()).isEqualTo("a.A.three");
        assertThat(tree.collapsed()).isEqualTo("[deeper_frames_omitted];a.A.three;a.A.four;a.A.five 1\n");
    }

    @Test
    void countsSamplesBeyondTheNodeBudgetAtTheDeepestKnownFrame() {
        CallTree tree = new CallTree(3, 10);
        tree.add(null, stack("a.A.one", "a.A.two"));
        tree.add(null, stack("a.A.one", "a.A.other"));
        tree.add(null, stack("b.B.root"));
        tree.add("thread", stack("b.B.root"));

        assertThat(tree.nodes()).isEqualTo(3);
        assertThat(tree.samples()).isEqualTo(4);
        assertThat(tree.truncatedSamples()).isEqualTo(3);
        CpuProfiler.Frame root = tree.frames();
        assertThat(root.self()).isEqualTo(2);
        assertThat(root.children().get(0).self()).isEqualTo(1);
        assertThat(Arrays.stream(tree.collapsed().split("\n"))).contains("a.A.one;a.A.two 1", "a.A.one 1");
    }
}
