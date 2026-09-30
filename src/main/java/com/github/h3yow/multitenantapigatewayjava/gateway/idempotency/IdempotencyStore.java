package com.github.h3yow.multitenantapigatewayjava.gateway.idempotency;

public interface IdempotencyStore {

    IdempotencyOutcome prepare(String tenantId, String idempotencyKey, String requestHash);

    void complete(String tenantId, String idempotencyKey, StoredIdempotentResponse response);

    void abort(String tenantId, String idempotencyKey);
}
