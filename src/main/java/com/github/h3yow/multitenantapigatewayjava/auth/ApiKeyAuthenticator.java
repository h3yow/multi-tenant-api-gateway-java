package com.github.h3yow.multitenantapigatewayjava.auth;

import com.github.h3yow.multitenantapigatewayjava.domain.ApiKeyInfo;
import com.github.h3yow.multitenantapigatewayjava.domain.Principal;
import com.github.h3yow.multitenantapigatewayjava.store.DataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 根据请求携带的 API Key 认证并解析调用方身份。 */
@Component
public class ApiKeyAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);

    private final DataStore dataStore;

    public ApiKeyAuthenticator(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    /**
     * @param presentedSecret 请求方出示的密钥（可能为 null/空）
     * @param nowMillis       当前时间（注入，便于测试）
     */
    public Principal authenticate(String presentedSecret, long nowMillis) {
        if (presentedSecret == null || presentedSecret.isBlank()) {
            log.info("auth decision tenant=? keyId=? reason=MISSING_KEY basis=header '{}' absent or blank",
                    AuthenticationFilter.API_KEY_HEADER);
            throw new AuthenticationException(AuthReason.MISSING_KEY, "API key is missing");
        }

        ApiKeyInfo info = dataStore.findApiKey(presentedSecret);
        if (info == null) {
            log.info("auth decision tenant=? keyId=? reason=INVALID_KEY basis=no registered key matches presented secret");
            throw new AuthenticationException(AuthReason.INVALID_KEY, "API key is invalid");
        }

        // 主动吊销是显式的安全动作，优先于过期判定，吊销即立即失效。
        if (info.revoked()) {
            log.info("auth decision tenant={} keyId={} reason=REVOKED_KEY basis=revoked flag is true",
                    info.tenantId(), info.keyId());
            throw new AuthenticationException(AuthReason.REVOKED_KEY, "API key has been revoked");
        }

        if (nowMillis >= info.expiresAt().toEpochMilli()) {
            log.info("auth decision tenant={} keyId={} reason=EXPIRED_KEY basis=now>={} expiresAt={}",
                    info.tenantId(), info.keyId(), nowMillis, info.expiresAt().toEpochMilli());
            throw new AuthenticationException(AuthReason.EXPIRED_KEY, "API key has expired");
        }

        Principal principal = new Principal(info.tenantId(), info.keyId(), info.role());
        log.info("auth decision tenant={} keyId={} reason=PASS basis=registered, not revoked, not expired role={}",
                info.tenantId(), info.keyId(), info.role());
        return principal;
    }
}
