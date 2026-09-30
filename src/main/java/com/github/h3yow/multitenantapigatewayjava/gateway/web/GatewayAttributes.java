package com.github.h3yow.multitenantapigatewayjava.gateway.web;

/**
 * 过滤器之间通过 request attribute 传递判定依据的键名。
 * 审计过滤器据此还原"是谁、依据什么被允许/拒绝"。
 */
public final class GatewayAttributes {

    public static final String PRINCIPAL = "gateway.principal";
    public static final String REJECT_REASON = "gateway.rejectReason";
    public static final String REJECT_DETAIL = "gateway.rejectDetail";
    public static final String DECISION = "gateway.decision";
    public static final String RATE_LIMIT_DECISION = "gateway.rateLimitDecision";
    public static final String IDEMPOTENCY_KEY = "gateway.idempotencyKey";
    public static final String IDEMPOTENCY_REPLAY = "gateway.idempotencyReplay";
    public static final String HANDLER_EXECUTED = "gateway.handlerExecuted";

    private GatewayAttributes() {
    }
}
