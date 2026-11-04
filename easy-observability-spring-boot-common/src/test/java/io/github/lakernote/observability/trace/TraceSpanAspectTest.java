package io.github.lakernote.observability.trace;

import io.github.lakernote.observability.trace.TraceSpan;
import io.github.lakernote.observability.trace.TraceIdContext;
import io.github.lakernote.observability.trace.SpanKind;
import io.github.lakernote.observability.trace.TraceBlock;
import io.github.lakernote.observability.trace.TraceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceSpanAspectTest {

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void shouldReuseResolvedDescriptorAndKeepConfiguredMetadata() throws Throwable {
        TraceSpanAspect aspect = new TraceSpanAspect(new io.github.lakernote.observability.config.EasyObservabilityProperties());
        NamedService target = new NamedService();
        Method method = target.getClass().getMethod("load");

        assertThat(traceOnce(aspect, joinPoint(target, method, "first")))
                .contains("[Remote] user-directory tag=\"primary\"");
        assertThat(traceOnce(aspect, joinPoint(target, method, "second")))
                .contains("[Remote] user-directory tag=\"primary\"");
    }

    @Test
    void shouldResolveTraceMetadataDeclaredOnInterface() throws Throwable {
        TraceSpanAspect aspect = new TraceSpanAspect(new io.github.lakernote.observability.config.EasyObservabilityProperties());
        InterfaceService target = new InterfaceService();
        Method signatureMethod = TracedContract.class.getMethod("load");

        String tree = traceOnce(aspect, joinPoint(target, signatureMethod, "loaded"));

        assertThat(tree).contains("[Service] contract-load tag=\"interface\"");
    }

    @Test
    void monitoringSetupErrorMustNotPreventTheAnnotatedCall() throws Throwable {
        TraceSpanAspect aspect = new TraceSpanAspect(new io.github.lakernote.observability.config.EasyObservabilityProperties());
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenThrow(new AssertionError("trace metadata unavailable"));
        when(joinPoint.proceed()).thenReturn("loaded");
        TraceContext.startRoot("GET /api/users", SpanKind.HTTP, "", 8, 0);

        assertThat(aspect.around(joinPoint)).isEqualTo("loaded");

        verify(joinPoint).proceed();
        assertThat(TraceContext.active()).isFalse();
    }

    @Test
    void annotatedFailureMarksTheExactSpanAndTraceBeforeRethrowing() throws Throwable {
        TraceSpanAspect aspect = new TraceSpanAspect(new io.github.lakernote.observability.config.EasyObservabilityProperties());
        NamedService target = new NamedService();
        Method method = target.getClass().getMethod("load");
        ProceedingJoinPoint joinPoint = joinPoint(target, method, "unused");
        IOException failure = new IOException("directory unavailable");
        when(joinPoint.proceed()).thenThrow(failure);
        AtomicReference<TraceContext.DeferredCompletion> completion = new AtomicReference<>();
        TraceContext.startRoot("GET /api/users", SpanKind.HTTP, "", 8, 0);
        String rootSpanId = TraceContext.currentSpanId();

        Throwable thrown = catchThrowable(() -> aspect.around(joinPoint));

        assertThat(thrown).isSameAs(failure);
        assertThat(TraceContext.currentSpanId()).isEqualTo(rootSpanId);
        TraceContext.stopRootDeferred(null, completion::set);
        TraceContext.DeferredCompletion result = completion.get();
        assertThat(result.error()).isTrue();
        assertThat(result.reasons()).containsExactly(TraceContext.CODE_ERROR_REASON);
        assertThat(result.errorType()).isEqualTo(IOException.class.getName());
        assertThat(result.errorMessage()).isEqualTo("directory unavailable");
        assertThat(result.stackTrace()).contains("directory unavailable");
        assertThat(result.nodes().get(0).error()).isTrue();
        assertThat(result.nodes().get(0).children()).singleElement().satisfies(span -> {
            assertThat(span.name()).isEqualTo("user-directory");
            assertThat(span.error()).isTrue();
            assertThat(span.errorType()).isEqualTo(IOException.class.getName());
            assertThat(span.unfinishedAtTraceEnd()).isFalse();
        });
        assertThat(result.tree()).contains("user-directory [ERROR " + IOException.class.getName() + "]");
    }

    private static String traceOnce(TraceSpanAspect aspect, ProceedingJoinPoint joinPoint) throws Throwable {
        TraceContext.startRoot("GET /api/users", SpanKind.HTTP, "", 8, 0);
        aspect.around(joinPoint);
        AtomicReference<TraceContext.DeferredCompletion> completion = new AtomicReference<>();
        TraceContext.stopRootDeferred(null, completion::set);
        return completion.get().tree();
    }

    private static ProceedingJoinPoint joinPoint(Object target, Method method, Object result) throws Throwable {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }

    @TraceSpan(name = "user-directory", kind = SpanKind.REMOTE, tag = "primary")
    static class NamedService {
        public String load() {
            return "loaded";
        }
    }

    @TraceSpan(name = "contract-load", kind = SpanKind.SERVICE, tag = "interface")
    interface TracedContract {
        String load();
    }

    static class InterfaceService implements TracedContract {
        @Override
        public String load() {
            return "loaded";
        }
    }
}
