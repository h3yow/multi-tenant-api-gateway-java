package com.github.h3yow.multitenantapigatewayjava.gateway.resource;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存资源库。资源按 id 存储，ownerTenant 标记归属租户。
 */
@Repository
public class ResourceRepository {

    private final ConcurrentHashMap<String, ResourceRecord> resources = new ConcurrentHashMap<>();

    public Optional<ResourceRecord> findById(String id) {
        return Optional.ofNullable(resources.get(id));
    }

    public ResourceRecord save(ResourceRecord record) {
        resources.put(record.id(), record);
        return record;
    }

    public void deleteById(String id) {
        resources.remove(id);
    }
}
