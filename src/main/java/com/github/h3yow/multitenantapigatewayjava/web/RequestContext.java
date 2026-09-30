package com.github.h3yow.multitenantapigatewayjava.web;

import com.github.h3yow.multitenantapigatewayjava.domain.Principal;
import jakarta.servlet.http.HttpServletRequest;

/** 在过滤器链与控制器之间传递调用方身份。 */
public final class RequestContext {

    public static final String PRINCIPAL_ATTRIBUTE =
            "com.github.h3yow.multitenantapigatewayjava.PRINCIPAL";

    private RequestContext() {
    }

    public static void setPrincipal(HttpServletRequest request, Principal principal) {
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
    }

    public static Principal principal(HttpServletRequest request) {
        return (Principal) request.getAttribute(PRINCIPAL_ATTRIBUTE);
    }
}
