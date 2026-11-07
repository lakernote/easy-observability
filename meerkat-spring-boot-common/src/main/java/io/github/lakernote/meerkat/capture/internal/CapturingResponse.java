package io.github.lakernote.meerkat.capture.internal;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * Copies the first bytes of the response body as the application writes it. Writes, flushes and commits go
 * straight to the real response, so streaming and server-sent events behave exactly as without capture.
 */
final class CapturingResponse extends HttpServletResponseWrapper {
    private final BodyTee body;
    private ServletOutputStream outputStream;
    private PrintWriter writer;

    CapturingResponse(HttpServletResponse response, BodyTee body) {
        super(response);
        this.body = body;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (outputStream == null) outputStream = new TeeOutputStream(super.getOutputStream(), body);
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            PrintWriter original = super.getWriter();
            writer = new PrintWriter(new TeeWriter(original, body), false) {
                @Override
                public boolean checkError() {
                    // The container's writer swallows client disconnects; surface them to the application.
                    return super.checkError() || original.checkError();
                }
            };
        }
        return writer;
    }

    private static final class TeeOutputStream extends ServletOutputStream {
        private final ServletOutputStream delegate;
        private final BodyTee body;

        private TeeOutputStream(ServletOutputStream delegate, BodyTee body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            body.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            delegate.write(buffer, offset, length);
            body.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }

    private static final class TeeWriter extends Writer {
        private final PrintWriter delegate;
        private final BodyTee body;

        private TeeWriter(PrintWriter delegate, BodyTee body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            delegate.write(buffer, offset, length);
            body.write(buffer, offset, length);
        }

        @Override
        public void write(String text, int offset, int length) {
            delegate.write(text, offset, length);
            body.write(text, offset, length);
        }

        @Override
        public void write(int value) {
            delegate.write(value);
            body.write(new char[] {(char) value}, 0, 1);
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
