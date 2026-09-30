package com.github.h3yow.multitenantapigatewayjava.gateway.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存幂等存储，键为"租户 + 幂等标识"，标识只在租户内唯一。
 *
 * <p>生命周期：IN_FLIGHT（首次请求执行中）-> DONE（响应已固化）。
 * 判定全部在 {@link ConcurrentHashMap#compute} 的原子 lambda 内完成，
 * 因此同一标识的并发请求里恰好一个拿到 PROCEED，其余按是否同体分流为
 * REPLAY / CONFLICT / IN_FLIGHT。CONFLICT 不会改动旧条目，旧结果永不被覆盖。</p>
 */
@Service
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private enum State { IN_FLIGHT, DONE }

    private record Entry(State state, String requestHash, StoredIdempotentResponse response) {
    }

    @Override
    public IdempotencyOutcome prepare(String tenantId, String idempotencyKey, String requestHash) {
        String storageKey = storageKey(tenantId, idempotencyKey);
        // 标记本次 compute 是否是"首次占位"（existing==null），用于区分 PROCEED 与命中既有 IN_FLIGHT。
        boolean[] firstOccupy = {false};
        // compute 的重映射函数对同一 key 串行且原子执行，判定 + 占位一步完成。
        Entry outcome = entries.compute(storageKey, (key, existing) -> switch (existing) {
            case null -> {
                firstOccupy[0] = true;
                log.info("idempotency tenant={} key={} decision=PROCEED hash={}", tenantId, idempotencyKey, requestHash);
                yield new Entry(State.IN_FLIGHT, requestHash, null);
            }
            case Entry e when e.requestHash().equals(requestHash) -> {
                if (e.state() == State.DONE) {
                    log.info("idempotency tenant={} key={} decision=REPLAY hash={}", tenantId, idempotencyKey, requestHash);
                } else {
                    log.info("idempotency tenant={} key={} decision=IN_FLIGHT hash={}", tenantId, idempotencyKey, requestHash);
                }
                yield e;
            }
            // 同键不同体：无论对方是否仍在执行，都拒绝，且原样返回 existing 不覆盖。
            case Entry e -> {
                log.info("idempotency tenant={} key={} decision=CONFLICT storedHash={} incomingHash={}",
                        tenantId, idempotencyKey, e.requestHash(), requestHash);
                yield e;
            }
        });

        if (firstOccupy[0]) {
            return new IdempotencyOutcome.Proceed();
        }
        if (!outcome.requestHash().equals(requestHash)) {
            return new IdempotencyOutcome.Conflict();
        }
        if (outcome.state() == State.DONE) {
            return new IdempotencyOutcome.Replay(outcome.response());
        }
        return new IdempotencyOutcome.InFlight();
    }

    @Override
    public void complete(String tenantId, String idempotencyKey, StoredIdempotentResponse response) {
        String storageKey = storageKey(tenantId, idempotencyKey);
        entries.computeIfPresent(storageKey, (key, existing) -> {
            log.info("idempotency tenant={} key={} event=COMPLETE status={}",
                    tenantId, idempotencyKey, response.status());
            return new Entry(State.DONE, existing.requestHash(), response);
        });
    }

    @Override
    public void abort(String tenantId, String idempotencyKey) {
        String storageKey = storageKey(tenantId, idempotencyKey);
        // 仅移除仍在执行中的占位；若已完成则保留，绝不误删可重放结果。
        entries.computeIfPresent(storageKey, (key, existing) -> {
            if (existing.state() == State.IN_FLIGHT) {
                log.info("idempotency tenant={} key={} event=ABORT removedInFlight", tenantId, idempotencyKey);
                return null;
            }
            return existing;
        });
    }

    private static String storageKey(String tenantId, String idempotencyKey) {
        return tenantId + "::" + idempotencyKey;
    }
}
