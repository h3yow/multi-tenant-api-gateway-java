package com.github.h3yow.multitenantapigatewayjava.gateway.security;

import com.github.h3yow.multitenantapigatewayjava.gateway.error.GatewayException;
import com.github.h3yow.multitenantapigatewayjava.gateway.error.RejectReason;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.CallerPrincipal;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.Role;
import com.github.h3yow.multitenantapigatewayjava.gateway.resource.ResourceRecord;
import com.github.h3yow.multitenantapigatewayjava.gateway.resource.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 角色授权与租户隔离。
 *
 * <p>两类越权的返回形态刻意不同，但都不泄漏目标资源是否存在：</p>
 * <ul>
 *   <li><b>角色不足</b>（如 READER 调 POST/DELETE）：资源还没被定位，
 *       直接 403 FORBIDDEN_ROLE，这是权限问题，与资源无关。</li>
 *   <li><b>跨租户访问单项资源</b>：对调用方而言，"别的租户的资源"和"不存在的资源"
 *       必须无法区分，统一走资源查找返回 404，响应体一致，不暴露存在性。</li>
 * </ul>
 */
@Service
public class AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final ResourceRepository resourceRepository;

    public AuthorizationService(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    /**
     * 集合级写操作（POST /api/resources）：WRITER 与 ADMIN 可写，READER 禁止。
     */
    public void authorizeCollectionWrite(CallerPrincipal principal) {
        if (principal.role() == Role.READER) {
            log.info("authz decision=DENY tenant={} role={} scope=collection action=WRITE reason=FORBIDDEN_ROLE",
                    principal.tenantId(), principal.role());
            throw new GatewayException(HttpStatus.FORBIDDEN.value(), RejectReason.FORBIDDEN_ROLE,
                    "reader role cannot write resources");
        }
        log.info("authz decision=ALLOW tenant={} role={} scope=collection action=WRITE",
                principal.tenantId(), principal.role());
    }

    /**
     * 单项读操作。先过租户隔离这一关（与不存在合并），再返回资源。
     */
    public ResourceRecord authorizeItemRead(CallerPrincipal principal, String resourceId) {
        return resolveWithinTenant(principal, resourceId, "READ");
    }

    /**
     * 单项删除：仅 ADMIN 可删。角色判断在资源定位之后，因此 READER/WRITER
     * 删除一个"不存在或属于他人"的资源时同样得到 404，而不会因 403 暴露资源存在。
     */
    public ResourceRecord authorizeItemDelete(CallerPrincipal principal, String resourceId) {
        ResourceRecord record = resolveWithinTenant(principal, resourceId, "DELETE");
        if (principal.role() != Role.ADMIN) {
            log.info("authz decision=DENY tenant={} role={} scope=item resourceId={} action=DELETE reason=FORBIDDEN_ROLE",
                    principal.tenantId(), principal.role(), resourceId);
            throw new GatewayException(HttpStatus.FORBIDDEN.value(), RejectReason.FORBIDDEN_ROLE,
                    "only admin can delete resources");
        }
        return record;
    }

    /**
     * 定位资源并做租户隔离。资源不存在 与 资源属于其它租户 走完全相同的 404，
     * 响应不携带"资源属于谁"的任何信息。
     */
    private ResourceRecord resolveWithinTenant(CallerPrincipal principal, String resourceId, String action) {
        Optional<ResourceRecord> found = resourceRepository.findById(resourceId);
        if (found.isEmpty() || !found.get().ownerTenant().equals(principal.tenantId())) {
            if (found.isPresent()) {
                log.info("authz decision=DENY tenant={} role={} resourceId={} action={} reason=CROSS_TENANT_HIDDEN_AS_404",
                        principal.tenantId(), principal.role(), resourceId, action);
            } else {
                log.info("authz decision=DENY tenant={} role={} resourceId={} action={} reason=NOT_FOUND_404",
                        principal.tenantId(), principal.role(), resourceId, action);
            }
            throw new GatewayException(HttpStatus.NOT_FOUND.value(), null, "resource not found");
        }
        log.info("authz decision=ALLOW tenant={} role={} resourceId={} action={}",
                principal.tenantId(), principal.role(), resourceId, action);
        return found.get();
    }
}
