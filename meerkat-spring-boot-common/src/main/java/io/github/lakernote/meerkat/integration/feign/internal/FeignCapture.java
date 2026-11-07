package io.github.lakernote.meerkat.integration.feign.internal;

import feign.Request;
import feign.Response;
import io.github.lakernote.meerkat.capture.internal.BodyTee;
import io.github.lakernote.meerkat.capture.internal.CaptureText;
import io.github.lakernote.meerkat.capture.internal.TeeInputStream;
import io.github.lakernote.meerkat.trace.internal.CaptureTarget;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

/** Records a Feign call of a captured request; failures leave the call untouched. */
final class FeignCapture {
    private FeignCapture() {
    }

    static void request(CaptureTarget target, Request request) {
        try {
            target.capture("request", request.httpMethod() + " " + request.url() + headers(request.headers()));
            byte[] body = request.body();
            String contentType = first(request.headers(), "content-type");
            String disposition = first(request.headers(), "content-disposition");
            if (body != null && body.length > 0 && BodyTee.isFile(contentType, disposition)) {
                target.capture("request.body", BodyTee.fileLabel(BodyTee.UPLOAD, contentType, disposition,
                        body.length));
            } else if (body != null && body.length > 0) {
                int maxBody = target.limits().maxBodyBytes();
                Charset charset = request.charset() == null ? StandardCharsets.UTF_8 : request.charset();
                target.capture("request.body", CaptureText.bytes(body, 0, Math.min(body.length, maxBody), charset,
                        target.limits().maxValueChars())
                        + (body.length > maxBody ? "\n… [body truncated, " + body.length + " total]" : ""));
            }
        } catch (RuntimeException ignored) {
            // 抓取失败不能影响调用。
        }
    }

    static Response response(CaptureTarget target, Response response) {
        try {
            target.capture("response", response.status() + headers(response.headers()));
            Response.Body body = response.body();
            if (body == null) return response;
            String contentType = first(response.headers(), "content-type");
            Charset charset = response.charset() == null ? StandardCharsets.UTF_8 : response.charset();
            String disposition = first(response.headers(), "content-disposition");
            BodyTee tee = new BodyTee(target.limits().maxBodyBytes(), BodyTee.DOWNLOAD, () -> contentType,
                    charset::name, () -> disposition);
            target.capture("response.body", tee);
            return response.toBuilder().body(new TeeInputStream(body.asInputStream(), tee), body.length()).build();
        } catch (IOException | RuntimeException ignored) {
            return response;
        }
    }

    private static String headers(Map<String, Collection<String>> headers) {
        StringBuilder text = new StringBuilder();
        if (headers == null) return "";
        headers.forEach((name, values) -> {
            if (values == null) return;
            for (String value : values) text.append('\n').append(name).append(": ").append(value);
        });
        return text.toString();
    }

    private static String first(Map<String, Collection<String>> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().iterator().next();
            }
        }
        return null;
    }
}
