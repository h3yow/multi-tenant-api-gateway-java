package com.github.h3yow.multitenantapigatewayjava.gateway.ratelimit;

import com.github.h3yow.multitenantapigatewayjava.gateway.error.RejectReason;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.CallerPrincipal;
import com.github.h3yow.multitenantapigatewayjava.gateway.security.SecuredPaths;
import com.github.h3yow.multitenantapigatewayjava.gateway.web.GatewayAttributes;
import com.github.h3yow.multitenantapigatewayjava.gateway.web.HttpResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 每租户独立配额的限流过滤器。认证通过后才计数（拒绝认证的请求不消耗租户额度）。
 * 放行与拒绝都回写 X-RateLimit-* 头；拒绝路径同时打上审计属性。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";

    private final RateLimitService rateLimitService;
    private final com.github.h3yow.multitenantapigatewayjava.gateway.config.GatewayClock clock;

    public RateLimitFilter(RateLimitService rateLimitService,
                           com.github.h3yow.multitenantapigatewayjava.gateway.config.GatewayClock clock) {
        this.rateLimitService = rateLimitService;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!SecuredPaths.isProtected(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        Object principalAttr = request.getAttribute(GatewayAttributes.PRINCIPAL);
        if (!(principalAttr instanceof CallerPrincipal principal)) {
            // 认证阶段已拒绝，直接透传给审计过滤器收尾。
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = rateLimitService.checkAndConsume(principal.tenantId());
        request.setAttribute(GatewayAttributes.RATE_LIMIT_DECISION, decision);
        writeHeaders(response, decision);

        if (!decision.allowed()) {
            request.setAttribute(GatewayAttributes.DECISION, "REJECT");
            request.setAttribute(GatewayAttributes.REJECT_REASON, RejectReason.RATE_LIMIT_EXCEEDED);
            request.setAttribute(GatewayAttributes.REJECT_DETAIL,
                    "quota exhausted for current window, resets at epoch millis " + decision.resetEpochMillis());
            response.setHeader(HttpHeaders.RETRY_AFTER,
                    Long.toString(Math.max(1L,
                            (decision.resetEpochMillis() - clock.nowEpochMillis() + 999L) / 1000L)));
            HttpResponses.writeError(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    RejectReason.RATE_LIMIT_EXCEEDED.code(),
                    "rate limit exceeded; remaining=" + decision.remaining()
                            + "; resetAt=" + decision.resetEpochMillis());
            return;
        }

        filterChain.doFilter(request, response);
    }

    static void writeHeaders(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader(HEADER_LIMIT, Long.toString(decision.limit()));
        response.setHeader(HEADER_REMAINING, Long.toString(decision.remaining()));
        response.setHeader(HEADER_RESET, Long.toString(decision.resetEpochMillis()));
    }
}
