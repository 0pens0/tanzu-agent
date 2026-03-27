package org.tanzu.goosechat.memory;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Cache configuration for the memory layer.
 *
 * Currently uses an in-memory ConcurrentMapCacheManager.
 * To switch to Valkey (Redis-compatible) when it becomes available on the platform:
 *   1. Add spring-boot-starter-data-redis to pom.xml
 *   2. Bind a Valkey service instance in manifest.yml
 *   3. Remove this bean — Spring Boot auto-configures RedisCacheManager automatically
 */
@Profile("memory")
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("user-conversations", "user-context");
    }
}
