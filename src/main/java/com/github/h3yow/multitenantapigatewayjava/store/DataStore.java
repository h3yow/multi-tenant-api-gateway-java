package com.github.h3yow.multitenantapigatewayjava.store;

import com.github.h3yow.multitenantapigatewayjava.domain.ApiKeyInfo;
import com.github.h3yow.multitenantapigatewayjava.domain.Order;
import com.github.h3yow.multitenantapigatewayjava.domain.TenantRole;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存数据存储（演示用）：API Key、租户配额、租户资源。
 * 所有数据按 tenantId 物理隔离；reset() 可恢复到固定种子，便于测试。
 */
@Component
public class DataStore {

    /** 固定窗口配额：演示窗口内租户最多可发起的请求数。 */
    private static final Map<String, Integer> DEFAULT_TENANT_QUOTAS = Map.of(
            "tenant-a", 5,
            "tenant-b", 3
    );

    private final Map<String, ApiKeyInfo> apiKeysBySecret = new ConcurrentHashMap<>();
    private final Map<String, Integer> tenantQuotas = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Order>> ordersByTenant = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> orderSequenceByTenant = new ConcurrentHashMap<>();

    public DataStore() {
        reset();
    }

    public ApiKeyInfo findApiKey(String secret) {
        return apiKeysBySecret.get(secret);
    }

    public int quotaFor(String tenantId) {
        return tenantQuotas.getOrDefault(tenantId, 0);
    }

    /** 覆盖某租户的固定窗口配额（测试/运维配置用）。 */
    public void setQuota(String tenantId, int quota) {
        tenantQuotas.put(tenantId, quota);
    }

    public List<Order> listOrders(String tenantId) {
        return List.copyOf(ordersByTenant.getOrDefault(tenantId, new CopyOnWriteArrayList<>()));
    }

    public Order findOrder(String tenantId, String orderId) {
        return ordersByTenant.getOrDefault(tenantId, new CopyOnWriteArrayList<>()).stream()
                .filter(o -> o.id().equals(orderId))
                .findFirst()
                .orElse(null);
    }

    public Order addOrder(String tenantId, Order order) {
        ordersByTenant.computeIfAbsent(tenantId, k -> new CopyOnWriteArrayList<>()).add(order);
        return order;
    }

    public int orderCount(String tenantId) {
        return ordersByTenant.getOrDefault(tenantId, new CopyOnWriteArrayList<>()).size();
    }

    /** 生成新订单 ID（序列按租户独立；reset 后归零，保证测试可复现）。 */
    public String nextOrderId(String tenantId) {
        AtomicLong sequence =
                orderSequenceByTenant.computeIfAbsent(tenantId, k -> new AtomicLong(0));
        long value = sequence.incrementAndGet();
        // 种子订单使用 o-seed-* 前缀，新建订单从 o-1 起编号，与种子不冲突。
        return "o-" + value;
    }

    /** 清空运行期数据并恢复初始种子（测试隔离用）。 */
    public void reset() {
        apiKeysBySecret.clear();
        tenantQuotas.clear();
        ordersByTenant.clear();
        orderSequenceByTenant.clear();
        tenantQuotas.putAll(DEFAULT_TENANT_QUOTAS);

        Instant now = Instant.now();
        Instant farFuture = now.plus(365, ChronoUnit.DAYS);
        Instant oneHourAgo = now.minus(1, ChronoUnit.HOURS);

        register(new ApiKeyInfo("key-a-admin", "secret-tenant-a-admin",
                "tenant-a", TenantRole.ADMIN, farFuture, false));
        register(new ApiKeyInfo("key-a-viewer", "secret-tenant-a-viewer",
                "tenant-a", TenantRole.VIEWER, farFuture, false));
        register(new ApiKeyInfo("key-a-admin-expired", "secret-tenant-a-expired",
                "tenant-a", TenantRole.ADMIN, oneHourAgo, false));
        register(new ApiKeyInfo("key-a-admin-revoked", "secret-tenant-a-revoked",
                "tenant-a", TenantRole.ADMIN, farFuture, true));
        register(new ApiKeyInfo("key-b-admin", "secret-tenant-b-admin",
                "tenant-b", TenantRole.ADMIN, farFuture, false));
        register(new ApiKeyInfo("key-b-viewer", "secret-tenant-b-viewer",
                "tenant-b", TenantRole.VIEWER, farFuture, false));

        ordersByTenant.put("tenant-a", new CopyOnWriteArrayList<>(List.of(
                new Order("o-seed-1", "tenant-a", "order-1", "public content", false),
                new Order("o-seed-2", "tenant-a", "order-secret", "admin only content", true)
        )));
        ordersByTenant.put("tenant-b", new CopyOnWriteArrayList<>(List.of(
                new Order("o-seed-3", "tenant-b", "order-b-1", "tenant b content", false)
        )));
    }

    private void register(ApiKeyInfo info) {
        apiKeysBySecret.put(info.secret(), info);
    }
}
