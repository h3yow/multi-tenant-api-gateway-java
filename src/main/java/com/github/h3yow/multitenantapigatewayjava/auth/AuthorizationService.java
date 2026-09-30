package com.github.h3yow.multitenantapigatewayjava.auth;

import com.github.h3yow.multitenantapigatewayjava.domain.Order;
import com.github.h3yow.multitenantapigatewayjava.domain.Principal;
import com.github.h3yow.multitenantapigatewayjava.domain.TenantRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 角色鉴权：同一租户内不同角色的可访问范围分开。
 * 越权访问资源与资源不存在返回完全相同的判定，避免响应差异泄漏资源是否存在。
 */
@Component
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    public AuthorizationService() {
    }

    /** 是否允许写（创建订单）。 */
    public boolean canCreate(Principal principal) {
        boolean allowed = principal.role() == TenantRole.ADMIN;
        log.info("authz decision tenant={} keyId={} role={} action=CREATE basis=role==ADMIN allowed={}",
                principal.tenantId(), principal.keyId(), principal.role(), allowed);
        return allowed;
    }

    /**
     * 是否可读该资源。
     * 关键不变式：resource 为 null（不存在）与存在但越权（scoped 且非 ADMIN）
     * 都返回 false，控制器对两者生成字节级一致的 404 响应。
     */
    public boolean canRead(Principal principal, Order resource) {
        if (resource == null) {
            log.info("authz decision tenant={} keyId={} role={} action=READ resource=absent basis=not found allowed=false",
                    principal.tenantId(), principal.keyId(), principal.role());
            return false;
        }
        boolean allowed = !resource.scoped() || principal.role() == TenantRole.ADMIN;
        log.info("authz decision tenant={} keyId={} role={} action=READ resource={} scoped={} basis=public-or-admin allowed={}",
                principal.tenantId(), principal.keyId(), principal.role(),
                resource.id(), resource.scoped(), allowed);
        return allowed;
    }
}
