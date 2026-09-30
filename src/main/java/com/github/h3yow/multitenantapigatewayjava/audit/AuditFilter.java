package com.github.h3yow.multitenantapigatewayjava.audit;

import com.github.h3yow.multitenantapigatewayjava.domain.Principal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

import static com.github.h3yow.multitenantapigatewayjava.web.RequestContext.principal;

/**
 * 最外层过滤器：对每个 /api 请求记录一条终态审计事件。
 * 2xx → REQUEST_OK；4xx → REQUEST_DENIED（业务拒绝/越权/限流等）；5xx 或异常 → REQUEST_FAILED。
 * 与内层的判定级事件互补，保证任何拒绝路径都有迹可循。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditFilter extends OncePerRequestFilter {

    private final AuditLog auditLog;
    private final Clock clock;

    public AuditFilter(AuditLog auditLog, Clock clock) {
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            Principal principal = principal(request);
            String tenantId = principal != null ? principal.tenantId() : "-";
            String keyId = principal != null ? principal.keyId() : "-";

            int status = response.getStatus();
            AuditAction action;
            String detail;
            if (failure != null) {
                action = AuditAction.REQUEST_FAILED;
                detail = "exception=" + failure.getClass().getSimpleName()
                        + " message=" + failure.getMessage();
            } else if (status >= 200 && status < 300) {
                action = AuditAction.REQUEST_OK;
                detail = "completed";
            } else if (status >= 400 && status < 500) {
                action = AuditAction.REQUEST_DENIED;
                detail = "rejected with status " + status;
            } else {
                action = AuditAction.REQUEST_FAILED;
                detail = "failed with status " + status;
            }

            auditLog.record(new AuditEvent(
                    clock.millis(),
                    tenantId,
                    keyId,
                    request.getMethod(),
                    request.getRequestURI(),
                    action,
                    status,
                    detail));
        }
    }
}
