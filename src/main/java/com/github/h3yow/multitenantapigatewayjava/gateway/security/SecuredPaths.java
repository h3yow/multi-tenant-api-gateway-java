package com.github.h3yow.multitenantapigatewayjava.gateway.security;

/**
 * 受网关保护的路径规则，集中定义，避免鉴权判定散落在过滤器与控制器里。
 */
public final class SecuredPaths {

    public static final String API_PREFIX = "/api/";
    public static final String RESOURCE_COLLECTION = "/api/resources";
    public static final String RESOURCE_ITEM_PREFIX = "/api/resources/";

    private SecuredPaths() {
    }

    /**
     * 只有 /api 下的请求需要认证鉴权；其它路径（如错误页）不拦截。
     */
    public static boolean isProtected(String path) {
        return path != null && path.startsWith(API_PREFIX);
    }

    /**
     * 资源集合路径：POST 创建。
     */
    public static boolean isResourceCollection(String method, String path) {
        return "POST".equalsIgnoreCase(method) && RESOURCE_COLLECTION.equals(path);
    }

    /**
     * 资源单项路径：GET 查看 / DELETE 删除。返回资源 id，不匹配返回 null。
     */
    public static String matchResourceItem(String method, String path) {
        if (path == null || !path.startsWith(RESOURCE_ITEM_PREFIX)) {
            return null;
        }
        if (!"GET".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            return null;
        }
        String id = path.substring(RESOURCE_ITEM_PREFIX.length());
        int slash = id.indexOf('/');
        if (slash >= 0) {
            return null;
        }
        return id.isBlank() ? null : id;
    }
}
