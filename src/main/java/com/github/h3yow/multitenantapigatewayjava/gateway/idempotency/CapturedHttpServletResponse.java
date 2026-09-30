package com.github.h3yow.multitenantapigatewayjava.gateway.idempotency;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 捕获下游写出的状态码、Content-Type 与响应体字节，执行结束后固化为
 * {@link StoredIdempotentResponse}；内容同时透传给真实响应。
 *
 * <p>统一在一个"分流"的 {@link ServletOutputStream} 上工作，writer 只是包在它外面，
 * 避免 getOutputStream/getWriter 两条路径写出位置不一致。</p>
 */
public class CapturedHttpServletResponse extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream capture = new ByteArrayOutputStream();
    private ServletOutputStream teeStream;
    private PrintWriter writer;

    public CapturedHttpServletResponse(HttpServletResponse response) {
        super(response);
    }

    private ServletOutputStream tee() throws IOException {
        if (teeStream == null) {
            ServletOutputStream original = super.getOutputStream();
            teeStream = new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return original.isReady();
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                    original.setWriteListener(writeListener);
                }

                @Override
                public void write(int b) throws IOException {
                    capture.write(b);
                    original.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    capture.write(b, off, len);
                    original.write(b, off, len);
                }

                @Override
                public void flush() throws IOException {
                    original.flush();
                }
            };
        }
        return teeStream;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() already called");
        }
        return tee();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (teeStream != null) {
            throw new IllegalStateException("getOutputStream() already called");
        }
        if (writer == null) {
            Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            writer = new PrintWriter(new OutputStreamWriter(tee(), charset));
        }
        return writer;
    }

    public StoredIdempotentResponse toStored() {
        if (writer != null) {
            writer.flush();
        }
        return new StoredIdempotentResponse(getStatus(), getContentType(), capture.toByteArray());
    }
}
