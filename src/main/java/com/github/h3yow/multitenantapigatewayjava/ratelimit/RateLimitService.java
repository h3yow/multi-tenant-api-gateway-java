package com.github.h3yow.multitenantapigatewayjava.ratelimit;

import com.github.h3yow.multitenantapigatewayjava.store.DataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每租户独立的固定时间窗口限流器。
 *
 * <p>语义：把时间切成长度 {@link #WINDOW_MILLIS} 的固定窗口，窗口内每放行一个请求
 * 计数加 1，达到配额即拒绝；窗口切换后计数归零。窗口边界为
 * {@code floor(now / WINDOW_MILLIS) * WINDOW_MILLIS}，给定时间即可复现。
 *
 * <p>并发正确性：窗口状态保存在 {@link AtomicReference} 中，扣减通过 CAS 自旋完成，
 * 每次成功 CAS 恰好对应一次真实放行，因此高并发下不会少算（丢失扣减）也不会多算
 * （同一份额度被多个线程并发超发）。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /** 窗口长度（毫秒），固定窗口语义。 */
    public static final long WINDOW_MILLIS = 60_000L;

    private final DataStore dataStore;
    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    public RateLimitService(DataStore dataStore, Clock clock) {
        this.dataStore = dataStore;
        this.clock = clock;
    }

    /** 对一次请求做配额判定；放行即扣减一个额度。 */
    public RateLimitWindow checkAndConsume(String tenantId) {
        int limit = dataStore.quotaFor(tenantId);
        long now = clock.millis();
        long windowStart = (now / WINDOW_MILLIS) * WINDOW_MILLIS;
        long windowEnd = windowStart + WINDOW_MILLIS;

        if (limit <= 0) {
            log.info("rate-limit tenant={} decision=DENY basis=no quota configured limit=0 windowEnd={}",
                    tenantId, windowEnd);
            return new RateLimitWindow(false, 0, 0, windowEnd);
        }

        AtomicReference<Window> ref =
                windows.computeIfAbsent(tenantId, k -> new AtomicReference<>());

        while (true) {
            Window current = ref.get();

            // 首次进入，或已跨到新窗口：尝试用一个空的新窗口替换旧状态。
            if (current == null || current.startEpoch != windowStart) {
                Window fresh = new Window(windowStart, 0);
                if (ref.compareAndSet(current, fresh)) {
                    log.info("rate-limit tenant={} window-open start={} end={} limit={}",
                            tenantId, windowStart, windowEnd, limit);
                    current = fresh;
                } else {
                    // 其他线程已先切换窗口，重读后重新判定。
                    continue;
                }
            }

            int usedBefore = current.used;
            if (usedBefore >= limit) {
                log.info("rate-limit tenant={} decision=DENY basis=usedBefore={}>=limit={} count={}->{} remaining=0 windowEnd={}",
                        tenantId, usedBefore, limit, usedBefore, usedBefore, windowEnd);
                return new RateLimitWindow(false, limit, 0, windowEnd);
            }

            Window next = new Window(windowStart, usedBefore + 1);
            if (ref.compareAndSet(current, next)) {
                int remaining = limit - next.used;
                log.info("rate-limit tenant={} decision=ALLOW basis=usedBefore={}<limit={} count={}->{} remaining={} windowEnd={}",
                        tenantId, usedBefore, limit, usedBefore, next.used, remaining, windowEnd);
                return new RateLimitWindow(true, limit, remaining, windowEnd);
            }
            // CAS 失败说明并发线程刚完成扣减，自旋重读再判，绝不重复扣减。
        }
    }

    /** 清空所有窗口计数（测试隔离用）。 */
    public void reset() {
        windows.clear();
    }

    /** 固定窗口状态：起始时刻 + 窗口内已用计数（不可变，配合 CAS 使用）。 */
    private record Window(long startEpoch, int used) {
    }
}
