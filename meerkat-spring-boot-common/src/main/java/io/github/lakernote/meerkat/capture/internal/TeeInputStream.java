package io.github.lakernote.meerkat.capture.internal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Copies bytes into a {@link BodyTee} as the wrapped stream is read. */
public final class TeeInputStream extends FilterInputStream {
    private final BodyTee tee;

    public TeeInputStream(InputStream delegate, BodyTee tee) {
        super(delegate);
        this.tee = tee;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) tee.write(value);
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count > 0) tee.write(buffer, offset, count);
        return count;
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
