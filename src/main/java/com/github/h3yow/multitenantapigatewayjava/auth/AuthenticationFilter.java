package com.github.h3yow.multitenantapigatewayjava.auth;

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

import static com.github.h3yow.multitenantapigatewayjava.web.RequestContext.setPrincipal;

/**
 * 识别 /api 请求中的租户密钥并认证。
 * 三种密钥问题在响应中有不同的 error 码：INVALID_KEY / EXPIRED_KEY / REVOKED_KEY。
 * 过滤器顺序：认证（最前）→ 限流 → 幂等 → 业务。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";

    private final ApiKeyAuthenticator authenticator;
    private final AuditLog auditLog;
    private final Clock clock;

    public AuthenticationFilter(ApiKeyAuthenticator authenticator,
                                AuditLog auditLog,
                                Clock clock) {
        this.authenticator = authenticator;
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
        String presented = request.getHeader(API_KEY_HEADER);
        try {
            Principal principal = authenticator.authenticate(presented, clock.millis());
            setPrincipal(request, principal);
            auditLog.record(new AuditEvent(
                    clock.millis(),
                    principal.tenantId(),
                    principal.keyId(),
                    request.getMethod(),
                    request.getRequestURI(),
                    AuditAction.AUTH_PASS,
                    200,
                    "role=" + principal.role()));
            filterChain.doFilter(request, response);
        } catch (AuthenticationException ex) {
            int status = 401;
            auditLog.record(new AuditEvent(
                    clock.millis(),
                    "-",
                    "-",
                    request.getMethod(),
                    request.getRequestURI(),
                    AuditAction.AUTH_DENY,
                    status,
                    "reason=" + ex.reason()));
            ErrorResponses.write(response, status, ex.reason().name(), ex.getMessage(),
                    request.getRequestURI());
        }
    }
}
