package com.github.h3yow.multitenantapigatewayjava.gateway.web;

import com.github.h3yow.multitenantapigatewayjava.gateway.error.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 控制器层异常统一出口。这里同时回写拒绝属性，让最外层 AuditFilter
 * 能以与过滤器拒绝相同的方式记录拒绝原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public void handleGateway(GatewayException ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        request.setAttribute(GatewayAttributes.DECISION, "REJECT");
        request.setAttribute(GatewayAttributes.REJECT_REASON, ex.reason());
        String code = ex.reason() != null ? ex.reason().code() : ("error_" + ex.httpStatus());
        HttpResponses.writeError(response, ex.httpStatus(), code, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public void handleUnexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        request.setAttribute(GatewayAttributes.DECISION, "REJECT");
        HttpResponses.writeError(response, HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "internal_error", "internal server error");
    }
}
