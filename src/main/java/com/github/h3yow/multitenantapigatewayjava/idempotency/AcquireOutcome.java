package com.github.h3yow.multitenantapigatewayjava.idempotency;

/**
 * 获取幂等槽位的结果。
 *
 * @param status   判定状态
 * @param existing 命中且已完成时的历史响应
 */
public record AcquireOutcome(Status status, CachedResponse existing) {

    public enum Status {
        /** 首次获取，本次请求负责真正执行业务。 */
        FIRST,
        /** 同 key 的首次请求仍在执行中，等待后重试。 */
        IN_FLIGHT,
        /** 同 key 已完成且请求体一致，直接重放。 */
        REPLAY,
        /** 同 key 已存在但请求体不同，必须拒绝。 */
        CONFLICT
    }

    public static AcquireOutcome first() {
        return new AcquireOutcome(Status.FIRST, null);
    }

    public static AcquireOutcome inFlight() {
        return new AcquireOutcome(Status.IN_FLIGHT, null);
    }

    public static AcquireOutcome replay(CachedResponse response) {
        return new AcquireOutcome(Status.REPLAY, response);
    }

    public static AcquireOutcome conflict() {
        return new AcquireOutcome(Status.CONFLICT, null);
    }
}
