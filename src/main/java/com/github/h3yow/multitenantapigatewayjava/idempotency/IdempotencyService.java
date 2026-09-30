package com.github.h3yow.multitenantapigatewayjava.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 (租户, 幂等键) 维度保证写请求只真正生效一次。
 *
 * <ul>
 *   <li>同 key + 同请求体指纹：只首次真正执行，之后重放首次结果（含首次失败结果）；</li>
 *   <li>同 key + 不同请求体：直接判定 CONFLICT，绝不覆盖旧结果；</li>
 *   <li>同 key 的并发请求（首次仍在执行）：等待首次结束后重放。</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    public IdempotencyService() {
    }

    /**
     * 尝试占用幂等槽位。
     *
     * @param tenantId    租户（键按租户隔离）
     * @param key         幂等键
     * @param requestHash 请求体指纹
     */
    public AcquireOutcome acquire(String tenantId, String key, String requestHash) {
        Slot slot = slots.computeIfAbsent(composite(tenantId, key), k -> new Slot());

        synchronized (slot) {
            if (slot.state == Slot.STATE_EMPTY) {
                slot.state = Slot.STATE_RUNNING;
                slot.requestHash = requestHash;
                log.info("idempotency tenant={} key={} decision=FIRST basis=no slot exists hash={}",
                        tenantId, key, requestHash);
                return AcquireOutcome.first();
            }

            if (!requestHash.equals(slot.requestHash)) {
                log.info("idempotency tenant={} key={} decision=CONFLICT basis=body hash differs presented={} stored={}",
                        tenantId, key, requestHash, slot.requestHash);
                return AcquireOutcome.conflict();
            }

            if (slot.state == Slot.STATE_RUNNING) {
                log.info("idempotency tenant={} key={} decision=IN_FLIGHT basis=first request still executing hash={}",
                        tenantId, key, requestHash);
                return AcquireOutcome.inFlight();
            }

            log.info("idempotency tenant={} key={} decision=REPLAY basis=slot completed same hash={} status={}",
                    tenantId, key, requestHash, slot.response.status());
            return AcquireOutcome.replay(slot.response);
        }
    }

    /**
     * 供过滤器在 IN_FLIGHT 状态下等待首次请求完成。
     *
     * @return 首次请求的响应（完成后一定存在）
     */
    public CachedResponse awaitCompletion(String tenantId, String key) {
        Slot slot = slots.get(composite(tenantId, key));
        synchronized (slot) {
            while (slot.state == Slot.STATE_RUNNING) {
                try {
                    slot.wait(10_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting idempotent first request", e);
                }
            }
            return slot.response;
        }
    }

    /** 首次请求执行结束后落盘响应并唤醒等待者。 */
    public void complete(String tenantId, String key, CachedResponse response) {
        Slot slot = slots.get(composite(tenantId, key));
        synchronized (slot) {
            slot.response = response;
            slot.state = Slot.STATE_DONE;
            slot.notifyAll();
        }
        log.info("idempotency tenant={} key={} decision=COMPLETE status={} replay-from-now-on",
                tenantId, key, response.status());
    }

    /** 清空幂等记录（测试隔离用）。 */
    public void reset() {
        slots.clear();
    }

    /** 计算请求体指纹（SHA-256，hex）。 */
    public static String hash(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = digest.digest(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(digestBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String composite(String tenantId, String key) {
        return tenantId + '\u0000' + key;
    }

    /** 幂等槽位：三态 + 请求体指纹 + 完成后的响应。 */
    private static final class Slot {
        static final int STATE_EMPTY = 0;
        static final int STATE_RUNNING = 1;
        static final int STATE_DONE = 2;

        int state = STATE_EMPTY;
        String requestHash;
        CachedResponse response;
    }
}
