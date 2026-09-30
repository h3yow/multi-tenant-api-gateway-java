package com.github.h3yow.multitenantapigatewayjava.web;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** 过滤器层统一的 JSON 错误响应写出工具（与全局异常处理器保持同构）。 */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static void write(HttpServletResponse response,
                             int status,
                             String error,
                             String message,
                             String path) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String safeMessage = message == null ? "" : message.replace("\"", "'");
        String safePath = path == null ? "" : path.replace("\"", "'");
        String safeError = error == null ? "" : error.replace("\"", "'");
        String body = """
                {"timestamp":%d,"status":%d,"error":"%s","message":"%s","path":"%s"}"""
                .formatted(System.currentTimeMillis(), status, safeError, safeMessage, safePath);
        response.getWriter().write(body);
    }
}
