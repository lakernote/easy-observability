package io.github.lakernote.meerkat.capture.internal;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Copies the first bytes of the request body as the application reads it; nothing is read ahead. */
final class CapturingRequest extends HttpServletRequestWrapper {
    private final BodyTee body;
    private ServletInputStream inputStream;
    private BufferedReader reader;

    CapturingRequest(HttpServletRequest request, BodyTee body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (inputStream == null) inputStream = new TeeInputStream(super.getInputStream(), body);
        return inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) reader = new BufferedReader(new TeeReader(super.getReader(), body));
        return reader;
    }

    private static final class TeeInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final BodyTee body;

        private TeeInputStream(ServletInputStream delegate, BodyTee body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) body.write(value);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) body.write(buffer, offset, count);
            return count;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }

    private static final class TeeReader extends Reader {
        private final Reader delegate;
        private final BodyTee body;

        private TeeReader(Reader delegate, BodyTee body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) body.write(buffer, offset, count);
            return count;
        }

        @Override
        public boolean ready() throws IOException {
            return delegate.ready();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
