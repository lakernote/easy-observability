package io.github.lakernote.meerkat.capture.internal;

import io.github.lakernote.meerkat.trace.internal.CaptureTarget;
import io.github.lakernote.meerkat.trace.internal.CaptureThreadState;
import io.github.lakernote.meerkat.trace.internal.TraceContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Records RestTemplate and RestClient calls made while their request is captured: request line, headers and the
 * first bytes of the body as it is written, then the response status, headers and the first bytes of the body as the
 * caller reads it.
 * <p>It decorates the underlying request factory instead of adding a {@code ClientHttpRequestInterceptor}, because
 * any interceptor makes Spring buffer every request body in memory, which would break large streaming uploads.
 * Requests outside a captured trace get the delegate's request unchanged.</p>
 */
public final class CaptureClientHttpRequestFactory implements ClientHttpRequestFactory {
    private final ClientHttpRequestFactory delegate;

    public CaptureClientHttpRequestFactory(ClientHttpRequestFactory delegate) {
        this.delegate = delegate;
    }

    public ClientHttpRequestFactory delegate() {
        return delegate;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        ClientHttpRequest request = delegate.createRequest(uri, httpMethod);
        // The remote span's scope opens after the request is created, so only check that this trace is captured.
        if (TraceContext.currentCaptureTarget() == null) return request;
        return request instanceof StreamingHttpOutputMessage
                ? new CapturingStreamingRequest(request) : new CapturingRequest(request);
    }

    /** Spring 7's HttpHeaders is no longer a Map; its own forEach exists in both Spring 6.2 and 7. */
    static String headers(HttpHeaders headers) {
        StringBuilder text = new StringBuilder();
        headers.forEach((name, values) -> {
            for (String value : values) text.append('\n').append(name).append(": ").append(value);
        });
        return text.toString();
    }

    private static String charsetName(HttpHeaders headers) {
        MediaType type = headers.getContentType();
        Charset charset = type == null ? null : type.getCharset();
        return (charset == null ? StandardCharsets.UTF_8 : charset).name();
    }

    private static class CapturingRequest implements ClientHttpRequest {
        final ClientHttpRequest delegate;
        private boolean claimed;
        private CaptureTarget target;
        private BodyTee body;

        CapturingRequest(ClientHttpRequest delegate) {
            this.delegate = delegate;
        }

        /** Claims the remote span on first use; the client has opened its scope by the time the body is written. */
        final CaptureTarget target() {
            if (claimed) return target;
            claimed = true;
            try {
                target = CaptureThreadState.claimRemote();
            } catch (RuntimeException | LinkageError ignored) {
                target = null;
            }
            return target;
        }

        private String requestLine() {
            return delegate.getMethod() + " " + delegate.getURI() + headers(delegate.getHeaders());
        }

        final BodyTee body() {
            if (body == null && target() != null) {
                try {
                    HttpHeaders headers = delegate.getHeaders();
                    body = new BodyTee(target.limits().maxBodyBytes(), BodyTee.UPLOAD,
                            () -> String.valueOf(headers.getContentType()), () -> charsetName(headers),
                            () -> headers.getFirst(HttpHeaders.CONTENT_DISPOSITION));
                } catch (RuntimeException | LinkageError ignored) {
                    body = null;
                }
            }
            return body;
        }

        @Override
        public HttpMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public URI getURI() {
            return delegate.getURI();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public OutputStream getBody() throws IOException {
            OutputStream out = delegate.getBody();
            BodyTee tee = body();
            return tee == null ? out : new TeeOutputStream(out, tee);
        }

        @Override
        public ClientHttpResponse execute() throws IOException {
            CaptureTarget capture = target();
            if (capture == null) return delegate.execute();
            try {
                capture.capture("request", requestLine());
                // Streaming bodies are written during execute(); the tee fills in as they go.
                if (body != null) capture.capture("request.body", body);
            } catch (RuntimeException | LinkageError ignored) {
                // 抓取失败不能影响调用。
            }
            ClientHttpResponse response;
            try {
                response = delegate.execute();
            } catch (IOException | RuntimeException failure) {
                capture.capture("error", failure.toString());
                throw failure;
            }
            try {
                HttpHeaders headers = response.getHeaders();
                capture.capture("response", response.getStatusCode().value() + headers(headers));
                BodyTee tee = new BodyTee(capture.limits().maxBodyBytes(), BodyTee.DOWNLOAD,
                        () -> String.valueOf(headers.getContentType()), () -> charsetName(headers),
                        () -> headers.getFirst(HttpHeaders.CONTENT_DISPOSITION));
                capture.capture("response.body", tee);
                return new CapturingResponse(response, tee);
            } catch (IOException | RuntimeException | LinkageError ignored) {
                return response;
            }
        }
    }

    /** Keeps streaming requests streaming: message converters write through {@link #setBody}, not a buffer. */
    private static final class CapturingStreamingRequest extends CapturingRequest implements StreamingHttpOutputMessage {
        CapturingStreamingRequest(ClientHttpRequest delegate) {
            super(delegate);
        }

        @Override
        public void setBody(Body body) {
            BodyTee tee = body();
            StreamingHttpOutputMessage streaming = (StreamingHttpOutputMessage) delegate;
            if (tee == null) {
                streaming.setBody(body);
                return;
            }
            streaming.setBody(new Body() {
                @Override
                public void writeTo(OutputStream outputStream) throws IOException {
                    body.writeTo(new TeeOutputStream(outputStream, tee));
                }

                @Override
                public boolean repeatable() {
                    return body.repeatable();
                }
            });
        }
    }

    private static final class TeeOutputStream extends FilterOutputStream {
        private final BodyTee tee;

        TeeOutputStream(OutputStream out, BodyTee tee) {
            super(out);
            this.tee = tee;
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            tee.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            tee.write(buffer, offset, length);
        }
    }

    private static final class CapturingResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final BodyTee tee;
        private InputStream body;

        private CapturingResponse(ClientHttpResponse delegate, BodyTee tee) {
            this.delegate = delegate;
            this.tee = tee;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) body = new TeeInputStream(delegate.getBody(), tee);
            return body;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
