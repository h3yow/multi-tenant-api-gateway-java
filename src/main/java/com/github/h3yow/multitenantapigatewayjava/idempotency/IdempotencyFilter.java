package com.github.h3yow.multitenantapigatewayjava.idempotency;

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
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Clock;

import static com.github.h3yow.multitenantapigatewayjava.web.RequestContext.principal;

/**
 * 仅对带 {@code Idempotency-Key} 的写请求生效：
 * <ul>
 *   <li>FIRST：真正执行业务，结束后缓存响应（成功与业务失败都缓存）；</li>
 *   <li>REPLAY：同键同体，原样重放首次响应（状态码与响应体一致）；</li>
 *   <li>CONFLICT：同键不同体，409 拒绝，不覆盖旧结果；</li>
 *   <li>IN_FLIGHT：首次仍在执行，等待其结束后重放。</li>
 * </ul>
 * 过滤器顺序排在认证、限流之后。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IdempotencyService idempotencyService;
    private final AuditLog auditLog;
    private final Clock clock;

    public IdempotencyFilter(IdempotencyService idempotencyService,
                             AuditLog auditLog,
                             Clock clock) {
        this.idempotencyService = idempotencyService;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/")
                || request.getHeader(IDEMPOTENCY_KEY_HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Principal principal = principal(request);
        String tenantId = principal.tenantId();
        String key = request.getHeader(IDEMPOTENCY_KEY_HEADER);

        byte[] body = request.getInputStream().readAllBytes();
        // 指纹同时包含 Content-Type，避免不同编码/类型下相同字节被误判为同体。
        String fingerprintSource = (request.getContentType() == null ? "" : request.getContentType())
                + "\n" + java.util.HexFormat.of().formatHex(body);
        String requestHash = IdempotencyService.hash(fingerprintSource.getBytes(
                java.nio.charset.StandardCharsets.UTF_8));

        AcquireOutcome outcome = idempotencyService.acquire(tenantId, key, requestHash);

        switch (outcome.status()) {
            case REPLAY -> {
                replay(response, outcome.existing(), tenantId, principal.keyId(), request, key);
                return;
            }
            case IN_FLIGHT -> {
                CachedResponse cached = idempotencyService.awaitCompletion(tenantId, key);
                replay(response, cached, tenantId, principal.keyId(), request, key);
                return;
            }
            case CONFLICT -> {
                auditLog.record(new AuditEvent(
                        clock.millis(), tenantId, principal.keyId(),
                        request.getMethod(), request.getRequestURI(),
                        AuditAction.IDEMPOTENCY_CONFLICT, 409,
                        "key=" + key + " presentedHash=" + requestHash));
                ErrorResponses.write(response, 409, "IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key was already used with a different request body",
                        request.getRequestURI());
                return;
            }
            case FIRST -> { /* 落到首次执行逻辑 */ }
        }

        // FIRST：缓存请求体供控制器重读，缓存响应以便后续重放。
        CachedBodyHttpServletRequest wrappedRequest =
                new CachedBodyHttpServletRequest(request, body);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        CachedResponse cached;
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
            cached = new CachedResponse(wrappedResponse.getStatus(), wrappedResponse.getContentAsByteArray());
        } catch (Exception ex) {
            // 未被控制器层处理的异常：首次也固化为可重放的 500，避免重复真正执行。
            wrappedResponse.resetBuffer();
            wrappedResponse.setStatus(500);
            ErrorResponses.write(wrappedResponse, 500, "INTERNAL_SERVER_ERROR",
                    ex.getMessage(), request.getRequestURI());
            cached = new CachedResponse(500, wrappedResponse.getContentAsByteArray());
        } finally {
            wrappedResponse.copyBodyToResponse();
        }

        idempotencyService.complete(tenantId, key, cached);
    }

    private void replay(HttpServletResponse response,
                        CachedResponse cached,
                        String tenantId,
                        String keyId,
                        HttpServletRequest request,
                        String key) throws IOException {
        response.setStatus(cached.status());
        response.setContentType("application/json;charset=UTF-8");
        if (cached.body() != null && cached.body().length > 0) {
            response.getOutputStream().write(cached.body());
        }
        auditLog.record(new AuditEvent(
                clock.millis(), tenantId, keyId,
                request.getMethod(), request.getRequestURI(),
                AuditAction.IDEMPOTENCY_REPLAY, cached.status(),
                "key=" + key + " replayed first response"));
    }
}
