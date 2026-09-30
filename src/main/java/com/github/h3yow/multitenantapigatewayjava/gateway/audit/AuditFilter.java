package com.github.h3yow.multitenantapigatewayjava.gateway.audit;

import com.github.h3yow.multitenantapigatewayjava.gateway.config.GatewayClock;
import com.github.h3yow.multitenantapigatewayjava.gateway.error.RejectReason;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.CallerPrincipal;
import com.github.h3yow.multitenantapigatewayjava.gateway.security.SecuredPaths;
import com.github.h3yow.multitenantapigatewayjava.gateway.web.GatewayAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 审计过滤器，包在整条网关链路最外层。链内任何一层直接拒绝（认证/限流/幂等）
 * 或控制器抛错后，都在这里依据 request 属性与最终状态码统一补一条审计，
 * 保证拒绝路径不漏记。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditFilter extends OncePerRequestFilter {

    private final AuditService auditService;
    private final GatewayClock clock;

    public AuditFilter(AuditService auditService, GatewayClock clock) {
        this.auditService = auditService;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (SecuredPaths.isProtected(request.getRequestURI())) {
                recordOne(request, response);
            }
        }
    }

    private void recordOne(HttpServletRequest request, HttpServletResponse response) {
        CallerPrincipal principal = null;
        if (request.getAttribute(GatewayAttributes.PRINCIPAL) instanceof CallerPrincipal p) {
            principal = p;
        }

        Object reasonAttr = request.getAttribute(GatewayAttributes.REJECT_REASON);
        String reason = reasonAttr instanceof RejectReason rr ? rr.code()
                : (response.getStatus() >= 400 ? "error_" + response.getStatus() : "none");

        String decisionAttr = (String) request.getAttribute(GatewayAttributes.DECISION);
        String decision;
        if ("REJECT".equals(decisionAttr) || response.getStatus() >= 400) {
            decision = "REJECT";
        } else if (Boolean.TRUE.equals(request.getAttribute(GatewayAttributes.IDEMPOTENCY_REPLAY))) {
            decision = "REPLAY";
        } else {
            decision = "ALLOW";
        }

        AuditEvent event = new AuditEvent(
                clock.now(),
                principal != null ? principal.tenantId() : "-",
                principal != null ? principal.apiKey() : request.getHeader("X-Api-Key"),
                principal != null ? principal.role().name() : "-",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                decision,
                reason,
                stringAttr(request, GatewayAttributes.IDEMPOTENCY_KEY),
                Boolean.TRUE.equals(request.getAttribute(GatewayAttributes.IDEMPOTENCY_REPLAY)));
        auditService.record(event);
    }

    private static String stringAttr(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value != null ? value.toString() : null;
    }
}
