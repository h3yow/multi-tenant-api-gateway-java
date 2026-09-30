package com.github.h3yow.multitenantapigatewayjava.gateway.idempotency;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等过滤器：仅对携带 Idempotency-Key 的写请求（POST/PUT/PATCH/DELETE）生效。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>同键同体：第一次真正执行，之后一律重放首次的状态码与响应体（成功/失败都固化）；</li>
 *   <li>同键不同体：409 IDEMPOTENCY_KEY_CONFLICT，旧结果保留不被覆盖；</li>
 *   <li>同键请求并发进行中：409 IDEMPOTENCY_KEY_IN_USE，不并发执行业务；</li>
 *   <li>首次请求执行异常时清占位，允许客户端用同键同体重试。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAY_HEADER = "Idempotent-Replay";

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!SecuredPaths.isProtected(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!(request.getAttribute(GatewayAttributes.PRINCIPAL) instanceof CallerPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);
        boolean writeMethod = isWrite(request.getMethod());
        if (idempotencyKey == null || idempotencyKey.isBlank() || !writeMethod) {
            filterChain.doFilter(request, response);
            return;
        }

        request.setAttribute(GatewayAttributes.IDEMPOTENCY_KEY, idempotencyKey);

        // 必须先缓存 body：既用于指纹计算，也保证控制器还能读到同一请求体。
        byte[] body = request.getInputStream().readAllBytes();
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request, body);
        String requestHash = fingerprint(request.getMethod(), request.getRequestURI(), body);

        IdempotencyOutcome outcome = store.prepare(principal.tenantId(), idempotencyKey, requestHash);
        switch (outcome) {
            case IdempotencyOutcome.Proceed ignored -> {
                CapturedHttpServletResponse captured = new CapturedHttpServletResponse(response);
                boolean handlerRan;
                try {
                    filterChain.doFilter(cached, captured);
                    captured.flushBuffer();
                } catch (IOException | ServletException | RuntimeException ex) {
                    // 响应尚未确定地产生：释放占位，允许同键同体重试。
                    store.abort(principal.tenantId(), idempotencyKey);
                    throw ex;
                }
                handlerRan = Boolean.TRUE.equals(request.getAttribute(GatewayAttributes.HANDLER_EXECUTED));
                if (handlerRan) {
                    // 控制器确实执行了，无论成功或业务失败，结果都是确定性的，固化后供一致重放。
                    store.complete(principal.tenantId(), idempotencyKey, captured.toStored());
                } else {
                    // 被前置过滤器短路（典型：限流 429），业务未执行，释放占位以便稍后重试。
                    store.abort(principal.tenantId(), idempotencyKey);
                }
            }
            case IdempotencyOutcome.Replay replay -> {
                request.setAttribute(GatewayAttributes.IDEMPOTENCY_REPLAY, Boolean.TRUE);
                request.setAttribute(GatewayAttributes.DECISION, "ALLOW");
                replay(response, replay.response());
            }
            case IdempotencyOutcome.Conflict ignored -> {
                request.setAttribute(GatewayAttributes.DECISION, "REJECT");
                request.setAttribute(GatewayAttributes.REJECT_REASON, RejectReason.IDEMPOTENCY_KEY_CONFLICT);
                request.setAttribute(GatewayAttributes.REJECT_DETAIL,
                        "idempotency key was already used with a different request body");
                HttpResponses.writeError(response, HttpStatus.CONFLICT.value(),
                        RejectReason.IDEMPOTENCY_KEY_CONFLICT.code(),
                        "idempotency key reused with a different request payload");
            }
            case IdempotencyOutcome.InFlight ignored -> {
                request.setAttribute(GatewayAttributes.DECISION, "REJECT");
                request.setAttribute(GatewayAttributes.REJECT_REASON, RejectReason.IDEMPOTENCY_KEY_IN_USE);
                request.setAttribute(GatewayAttributes.REJECT_DETAIL,
                        "a request with the same idempotency key is still being processed");
                HttpResponses.writeError(response, HttpStatus.CONFLICT.value(),
                        RejectReason.IDEMPOTENCY_KEY_IN_USE.code(),
                        "request with same idempotency key is in progress");
            }
        }
    }

    private static void replay(HttpServletResponse response, StoredIdempotentResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null && !stored.contentType().isBlank()) {
            response.setContentType(stored.contentType());
        } else {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        }
        response.setHeader(IDEMPOTENT_REPLAY_HEADER, "true");
        response.getOutputStream().write(stored.body());
    }

    private static boolean isWrite(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    /**
     * 请求指纹：方法 + 路径 + 原始请求体。租户与幂等键已在存储键里，不混入指纹。
     */
    static String fingerprint(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            byte[] hash = digest.digest(body);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
