package com.github.h3yow.multitenantapigatewayjava.gateway.error;

/**
 * 业务/鉴权层抛出的网关异常，由全局异常处理器统一翻译成错误响应。
 */
public class GatewayException extends RuntimeException {

    private final int httpStatus;
    private final RejectReason reason;

    public GatewayException(int httpStatus, RejectReason reason, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.reason = reason;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public RejectReason reason() {
        return reason;
    }
}
