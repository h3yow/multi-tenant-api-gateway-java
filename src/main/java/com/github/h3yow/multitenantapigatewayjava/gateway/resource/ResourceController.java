package com.github.h3yow.multitenantapigatewayjava.gateway.resource;

import com.github.h3yow.multitenantapigatewayjava.gateway.model.CallerPrincipal;
import com.github.h3yow.multitenantapigatewayjava.gateway.security.AuthorizationService;
import com.github.h3yow.multitenantapigatewayjava.gateway.security.AuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 受保护的租户资源接口。所有租户隔离与角色判定都委托给 {@link AuthorizationService}：
 * 跨租户与不存在统一 404；角色不足 403。
 */
@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private final ResourceRepository repository;
    private final AuthorizationService authorization;

    public ResourceController(ResourceRepository repository, AuthorizationService authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    /**
     * 创建资源：WRITER / ADMIN 可用。新建资源归属当前调用方租户。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) CreateResourceRequest body,
                                                      HttpServletRequest request) {
        CallerPrincipal principal = (CallerPrincipal) request.getAttribute(
                com.github.h3yow.multitenantapigatewayjava.gateway.web.GatewayAttributes.PRINCIPAL);
        authorization.authorizeCollectionWrite(principal);

        String id = UUID.randomUUID().toString();
        String name = body != null && body.name() != null ? body.name() : "";
        String content = body != null && body.content() != null ? body.content() : "";
        ResourceRecord saved = repository.save(new ResourceRecord(id, principal.tenantId(), name, content));
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(saved));
    }

    /**
     * 查看资源：已认证即可，但只能看到本租户资源；越权访问他租户资源得到 404。
     */
    @GetMapping("/{id}")
    public Map<String, Object> getOne(@PathVariable String id, HttpServletRequest request) {
        CallerPrincipal principal = (CallerPrincipal) request.getAttribute(
                com.github.h3yow.multitenantapigatewayjava.gateway.web.GatewayAttributes.PRINCIPAL);
        ResourceRecord record = authorization.authorizeItemRead(principal, id);
        return toView(record);
    }

    /**
     * 删除资源：仅 ADMIN。对非本租户或不存在资源同样先得到 404，不暴露存在性。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest request) {
        CallerPrincipal principal = (CallerPrincipal) request.getAttribute(
                com.github.h3yow.multitenantapigatewayjava.gateway.web.GatewayAttributes.PRINCIPAL);
        ResourceRecord record = authorization.authorizeItemDelete(principal, id);
        repository.deleteById(record.id());
        return Map.of("id", record.id(), "deleted", true);
    }

    private Map<String, Object> toView(ResourceRecord record) {
        return Map.of(
                "id", record.id(),
                "ownerTenant", record.ownerTenant(),
                "name", record.name(),
                "content", record.content());
    }

    public record CreateResourceRequest(String name, String content) {
    }
}
