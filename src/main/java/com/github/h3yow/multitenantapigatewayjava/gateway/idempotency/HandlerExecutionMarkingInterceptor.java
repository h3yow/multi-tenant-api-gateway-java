package com.github.h3yow.multitenantapigatewayjava.gateway.idempotency;

import com.github.h3yow.multitenantapigatewayjava.gateway.web.GatewayAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 标记"请求确实进入了控制器（处理器）"。
 *
 * <p>幂等过滤器据此区分两种非 2xx 结果：</p>
 * <ul>
 *   <li>控制器执行后产生的 4xx（如鉴权 403/404、业务校验失败）：这是确定性的业务结果，
 *       应当固化，重放返回一致结果；</li>
 *   <li>前置过滤器直接拒绝（如限流 429）：业务根本没执行，必须释放幂等占位，
 *       让客户端稍后用同一键+同一体重试。</li>
 * </ul>
 */
public class HandlerExecutionMarkingInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(GatewayAttributes.HANDLER_EXECUTED, Boolean.TRUE);
        return true;
    }
}
