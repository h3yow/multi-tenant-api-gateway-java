package com.github.h3yow.multitenantapigatewayjava.gateway.idempotency;

/**
 * 幂等检查结果：
 * PROCEED 首次请求，可执行业务；
 * REPLAY 同键同体，返回首次结果；
 * CONFLICT 同键不同体，必须拒绝且不能覆盖旧结果；
 * IN_FLIGHT 同键请求仍在执行中。
 */
public sealed interface IdempotencyOutcome
        permits IdempotencyOutcome.Proceed, IdempotencyOutcome.Replay,
        IdempotencyOutcome.Conflict, IdempotencyOutcome.InFlight {

    record Proceed() implements IdempotencyOutcome {
    }

    record Replay(StoredIdempotentResponse response) implements IdempotencyOutcome {
    }

    record Conflict() implements IdempotencyOutcome {
    }

    record InFlight() implements IdempotencyOutcome {
    }
}
