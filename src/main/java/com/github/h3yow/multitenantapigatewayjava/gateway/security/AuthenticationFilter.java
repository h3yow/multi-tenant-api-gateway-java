package com.github.h3yow.multitenantapigatewayjava.gateway.security;

import com.github.h3yow.multitenantapigatewayjava.gateway.error.RejectReason;
import com.github.h3yow.multitenantapigatewayjava.gateway.web.GatewayAttributes;
import com.github.h3yow.multitenantapigatewayjava.gateway.web.HttpResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 认证过滤器（链路最前置的业务过滤器）。
 * 无效 / 过期 / 吊销分别给出不同 reason；拒绝即在此直接返回，不再进入后续业务过滤器。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";

    private final ApiKeyAuthenticator authenticator;

    public AuthenticationFilter(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!SecuredPaths.isProtected(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        AuthResult result = authenticator.authenticate(request.getHeader(API_KEY_HEADER));
        if (result instanceof AuthResult.Ok ok) {
            request.setAttribute(GatewayAttributes.PRINCIPAL, ok.principal());
            filterChain.doFilter(request, response);
            return;
        }

        AuthResult.Reject rejected = (AuthResult.Reject) result;
        request.setAttribute(GatewayAttributes.DECISION, "REJECT");
        request.setAttribute(GatewayAttributes.REJECT_REASON, rejected.reason());
        request.setAttribute(GatewayAttributes.REJECT_DETAIL, rejected.detail());
        HttpResponses.writeError(response, HttpStatus.UNAUTHORIZED.value(),
                rejected.reason().code(), rejected.detail());
    }
}
