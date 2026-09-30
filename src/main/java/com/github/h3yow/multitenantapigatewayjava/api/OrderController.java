package com.github.h3yow.multitenantapigatewayjava.api;

import com.github.h3yow.multitenantapigatewayjava.api.dto.CreateOrderRequest;
import com.github.h3yow.multitenantapigatewayjava.api.dto.OrderResponse;
import com.github.h3yow.multitenantapigatewayjava.auth.AuthorizationService;
import com.github.h3yow.multitenantapigatewayjava.domain.Order;
import com.github.h3yow.multitenantapigatewayjava.domain.Principal;
import com.github.h3yow.multitenantapigatewayjava.store.DataStore;
import com.github.h3yow.multitenantapigatewayjava.web.ForbiddenException;
import com.github.h3yow.multitenantapigatewayjava.web.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.github.h3yow.multitenantapigatewayjava.web.RequestContext.principal;

/**
 * 演示业务端点：
 * GET  /api/orders        列出当前角色可见的订单（越权资源不可见，且不暴露其存在性）
 * GET  /api/orders/{id}   读取单个订单（越权与不存在同一响应）
 * POST /api/orders        创建订单（仅 ADMIN；写请求支持 Idempotency-Key）
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final DataStore dataStore;
    private final AuthorizationService authorizationService;

    public OrderController(DataStore dataStore, AuthorizationService authorizationService) {
        this.dataStore = dataStore;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public List<OrderResponse> list(HttpServletRequest request) {
        Principal principal = principal(request);
        // 受限资源直接不出现在列表里，调用方无从得知其存在。
        return dataStore.listOrders(principal.tenantId()).stream()
                .filter(order -> authorizationService.canRead(principal, order))
                .map(OrderController::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable String id, HttpServletRequest request) {
        Principal principal = principal(request);
        Order order = dataStore.findOrder(principal.tenantId(), id);
        // 不存在 与 存在但越权 走完全相同的异常路径与响应。
        if (order == null || !authorizationService.canRead(principal, order)) {
            throw new NotFoundException("order not found or not visible: " + id);
        }
        return toResponse(order);
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestBody CreateOrderRequest body,
                                                HttpServletRequest request) {
        Principal principal = principal(request);
        if (!authorizationService.canCreate(principal)) {
            throw new ForbiddenException("Caller role is not allowed to create orders");
        }
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("Field 'name' is required");
        }

        boolean scoped = Boolean.TRUE.equals(body.scoped());
        Order order = new Order(
                dataStore.nextOrderId(principal.tenantId()),
                principal.tenantId(),
                body.name(),
                body.content() == null ? "" : body.content(),
                scoped);
        dataStore.addOrder(principal.tenantId(), order);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(order));
    }

    private static OrderResponse toResponse(Order order) {
        return new OrderResponse(order.id(), order.name(), order.content(), order.scoped());
    }
}
