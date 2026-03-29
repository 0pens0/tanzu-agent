package org.tanzu.goosechat.memory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry mapping per-session MEMORY_TOKEN values to user IDs.
 *
 * Tokens are injected into each Goose subprocess as the MEMORY_TOKEN env var
 * so the agent can call /api/memory/** via curl without SSO cookies, while still
 * resolving the correct authenticated user identity.
 *
 * GooseChatController registers/revokes tokens; MemoryController resolves them.
 */
@Profile("memory")
@Component
public class MemoryTokenRegistry {

    private final ConcurrentHashMap<String, String> tokenToUserId = new ConcurrentHashMap<>();

    public void register(String token, String userId) {
        tokenToUserId.put(token, userId);
    }

    public void revoke(String token) {
        tokenToUserId.remove(token);
    }

    /** Returns the userId for this token, or null if unknown/expired. */
    public String resolve(String token) {
        return token != null ? tokenToUserId.get(token) : null;
    }
}
