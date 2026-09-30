package com.github.h3yow.multitenantapigatewayjava.gateway.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 统一写 JSON 响应，保证过滤器里直接拒绝与控制器异常路径的响应结构一致：
 * {"error":{"code":...,"message":...}}
 */
public final class HttpResponses {

    private HttpResponses() {
    }

    public static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        String json = "{\"error\":{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}}";
        writeJson(response, status, json);
    }

    public static void writeJson(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
