package com.github.h3yow.multitenantapigatewayjava.ratelimit;

import com.github.h3yow.multitenantapigatewayjava.audit.AuditAction;
import com.github.h3yow.multitenantapigatewayjava.audit.AuditEvent;
import com.github.h3yow.multitenantapigatewayjava.audit.AuditLog;
import com.github.h3yow.multitenantapigatewayjava.domain.Principal;
import com.github.h3yow.multitenantapigatewayjava.web.ErrorResponses;
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
 * 对 /api 请求按租户执行限流（排在认证之后）。
 * 拒绝时返回 429，并通过标准头告知调用方：总配额、剩余额度、窗口刷新时刻。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    public static final String RESET_HEADER = "X-RateLimit-Reset";
    public static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RateLimitService rateLimitService;
    private final AuditLog auditLog;
    private final Clock clock;

    public RateLimitFilter(RateLimitService rateLimitService,
                           AuditLog auditLog,
                           Clock clock) {
        this.rateLimitService = rateLimitService;
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
        Principal principal = principal(request);
        String tenantId = principal.tenantId();

        RateLimitWindow decision = rateLimitService.checkAndConsume(tenantId);

        // 放行与拒绝都回写可观察的额度头，调用方总能看到窗口状态。
        response.setIntHeader(LIMIT_HEADER, decision.limit());
        response.setIntHeader(REMAINING_HEADER, decision.remaining());
        response.setDateHeader(RESET_HEADER, decision.windowEnd());

        if (!decision.allowed()) {
            long retryAfterSeconds = Math.max(
                    1L, (decision.windowEnd() - clock.millis() + 999L) / 1000L);
            response.setHeader(RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds));
            auditLog.record(new AuditEvent(
                    clock.millis(),
                    tenantId,
                    principal.keyId(),
                    request.getMethod(),
                    request.getRequestURI(),
                    AuditAction.RATE_LIMIT_DENY,
                    429,
                    "limit=" + decision.limit() + " remaining=0 resetAt=" + decision.windowEnd()));
            ErrorResponses.write(response, 429, "RATE_LIMITED",
                    "Quota exhausted for current fixed window; 0 remaining; retry after window reset",
                    request.getRequestURI());
            return;
        }

        auditLog.record(new AuditEvent(
                clock.millis(),
                tenantId,
                principal.keyId(),
                request.getMethod(),
                request.getRequestURI(),
                AuditAction.RATE_LIMIT_PASS,
                200,
                "limit=" + decision.limit() + " remaining=" + decision.remaining()
                        + " resetAt=" + decision.windowEnd()));
        filterChain.doFilter(request, response);
    }
}
