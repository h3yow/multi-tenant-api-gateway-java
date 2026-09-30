package com.github.h3yow.multitenantapigatewayjava.gateway.ratelimit;

import com.github.h3yow.multitenantapigatewayjava.gateway.config.GatewayClock;
import com.github.h3yow.multitenantapigatewayjava.gateway.config.GatewayProperties;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.Tenant;
import com.github.h3yow.multitenantapigatewayjava.gateway.security.TenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 固定时间窗口、按租户独立配额的限流器。
 *
 * <p><b>窗口对齐语义（可复现）：</b>窗口长度 W 下，任意时刻 t 所属窗口起点为
 * {@code floor(t / W) * W}（按 epoch 毫秒对齐），刷新点为下一个对齐边界。
 * 因此无论进程何时启动、先后来多少请求，同一物理时刻必落在同一窗口。</p>
 *
 * <p><b>并发正确性：</b>每个租户持有一个 {@link AtomicReference}，窗口翻转与计数
 * 都通过 CAS 完成；关键在于"判断是否超限"与"计数 +1"在同一个 CAS 重试循环里，
 * 杜绝多个线程同时越过限额检查后各自自增。CAS 失败则自旋重读，保证高并发下
 * 既不少算（丢失计数）也不多算（超限请求被放行）。被拒绝的请求不占用额度。</p>
 */
@Service
public class FixedWindowRateLimitService implements RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(FixedWindowRateLimitService.class);

    private final TenantDirectory tenantDirectory;
    private final GatewayClock clock;
    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, AtomicReference<Window>> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimitService(TenantDirectory tenantDirectory,
                                       GatewayClock clock,
                                       GatewayProperties properties) {
        this.tenantDirectory = tenantDirectory;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public RateLimitDecision checkAndConsume(String tenantId) {
        Tenant tenant = tenantDirectory.findTenant(tenantId);
        long limit = tenant != null ? tenant.quotaPerWindow() : 0L;
        long windowLength = properties.getRateLimitWindowMillis();

        long now = clock.nowEpochMillis();
        long windowStart = Math.floorDiv(now, windowLength) * windowLength;
        long resetAt = windowStart + windowLength;

        AtomicReference<Window> ref =
                windows.computeIfAbsent(tenantId, k -> new AtomicReference<>(new Window(windowStart)));

        while (true) {
            Window current = ref.get();

            // 跨窗口：CAS 翻转到新窗口；失败说明被别的线程翻转，自旋重读即可。
            if (current.start() != windowStart) {
                if (ref.compareAndSet(current, new Window(windowStart))) {
                    log.info("ratelimit tenant={} event=WINDOW_ROLL previousStart={} newStart={} resetAt={}",
                            tenantId, current.start(), windowStart, resetAt);
                }
                continue;
            }

            // 限额判断与计数自增必须在同一 CAS 循环内，否则并发下会超发。
            long used = current.count();
            if (used >= limit) {
                log.info("ratelimit tenant={} decision=REJECT limit={} used={} remaining=0 windowStart={} resetAt={}",
                        tenantId, limit, used, windowStart, resetAt);
                return new RateLimitDecision(false, limit, 0L, resetAt, windowStart);
            }
            if (current.reserve(limit)) {
                long newUsed = current.count();
                long remaining = Math.max(0L, limit - newUsed);
                log.info("ratelimit tenant={} decision=ALLOW limit={} used={} remaining={} windowStart={} resetAt={}",
                        tenantId, limit, newUsed, remaining, windowStart, resetAt);
                return new RateLimitDecision(true, limit, remaining, resetAt, windowStart);
            }
            // CAS 输给并发请求，重读最新计数后重试。
        }
    }

    /**
     * 清空全部窗口计数。仅供测试在每个用例前复位，避免配额在测试间相互污染。
     */
    public void resetAllForTesting() {
        windows.clear();
    }

    /**
     * 单个固定窗口。{@link #reserve} 以 CAS 保证：仅当计数小于限额时才 +1，
     * 任意时刻成功放行的请求总数不会超过 limit。
     */
    private static final class Window {
        private final long start;
        private final AtomicLong counter = new AtomicLong(0L);

        Window(long start) {
            this.start = start;
        }

        long start() {
            return start;
        }

        long count() {
            return counter.get();
        }

        boolean reserve(long limit) {
            long snapshot;
            do {
                snapshot = counter.get();
                if (snapshot >= limit) {
                    return false;
                }
            } while (!counter.compareAndSet(snapshot, snapshot + 1));
            return true;
        }
    }
}
