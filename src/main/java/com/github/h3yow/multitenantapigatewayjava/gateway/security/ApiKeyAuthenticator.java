package com.github.h3yow.multitenantapigatewayjava.gateway.security;

import com.github.h3yow.multitenantapigatewayjava.gateway.config.GatewayClock;
import com.github.h3yow.multitenantapigatewayjava.gateway.error.RejectReason;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.ApiKey;
import com.github.h3yow.multitenantapigatewayjava.gateway.model.CallerPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * API Key 认证。
 *
 * <p>拒绝原因严格区分：</p>
 * <ol>
 *   <li>请求未携带 X-Api-Key -> MISSING_API_KEY</li>
 *   <li>密钥在目录中不存在 -> UNKNOWN_API_KEY</li>
 *   <li>expiresAt 已过 -> API_KEY_EXPIRED</li>
 *   <li>被主动吊销 revoked=true -> API_KEY_REVOKED</li>
 * </ol>
 *
 * <p>过期先于吊销判断：吊销一把已过期密钥，调用方看到的仍是"过期"，
 * 这样密钥生命周期状态的解释保持单一、可复现。</p>
 */
@Component
public class ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    private final TenantDirectory directory;
    private final GatewayClock clock;

    public ApiKeyAuthenticator(TenantDirectory directory, GatewayClock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    public AuthResult authenticate(String apiKeyHeader) {
        if (apiKeyHeader == null || apiKeyHeader.isBlank()) {
            log.info("auth decision=REJECT reason=MISSING_API_KEY detail=\"X-Api-Key header absent\"");
            return new AuthResult.Reject(RejectReason.MISSING_API_KEY, "missing api key");
        }

        String presentedKey = apiKeyHeader.trim();
        ApiKey apiKey = directory.findApiKey(presentedKey);
        if (apiKey == null) {
            log.info("auth decision=REJECT reason=UNKNOWN_API_KEY keyTail={} detail=\"key not found\"",
                    tail(presentedKey));
            return new AuthResult.Reject(RejectReason.UNKNOWN_API_KEY, "unknown api key");
        }

        if (apiKey.expiresAt() != null && !clock.now().isBefore(apiKey.expiresAt())) {
            log.info("auth decision=REJECT reason=API_KEY_EXPIRED tenant={} keyTail={} expiredAt={} now={}",
                    apiKey.tenantId(), tail(presentedKey), apiKey.expiresAt(), clock.now());
            return new AuthResult.Reject(RejectReason.API_KEY_EXPIRED, "api key expired at " + apiKey.expiresAt());
        }

        if (apiKey.revoked()) {
            log.info("auth decision=REJECT reason=API_KEY_REVOKED tenant={} keyTail={}",
                    apiKey.tenantId(), tail(presentedKey));
            return new AuthResult.Reject(RejectReason.API_KEY_REVOKED, "api key revoked");
        }

        CallerPrincipal principal = new CallerPrincipal(apiKey.tenantId(), presentedKey, apiKey.role());
        log.info("auth decision=ALLOW tenant={} role={} keyTail={}",
                principal.tenantId(), principal.role(), tail(presentedKey));
        return new AuthResult.Ok(principal);
    }

    private static String tail(String key) {
        return key.length() <= 4 ? "****" : "****" + key.substring(key.length() - 4);
    }
}
