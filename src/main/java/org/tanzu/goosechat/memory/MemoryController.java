package org.tanzu.goosechat.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import java.util.List;

/**
 * REST endpoints for the conversation history sidebar.
 *
 * GET  /api/memory/conversations          — list of past sessions for the current user
 * GET  /api/memory/conversations/{id}     — full message history for one session
 */
@Profile("memory")
@RestController
@RequestMapping("/api/memory")
@CrossOrigin(origins = "*")
public class MemoryController {

    private static final Logger logger = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryService memoryService;
    private final FactRepository factRepository;
    private final MemoryTokenRegistry tokenRegistry;

    public MemoryController(MemoryService memoryService,
                            FactRepository factRepository,
                            MemoryTokenRegistry tokenRegistry) {
        this.memoryService = memoryService;
        this.factRepository = factRepository;
        this.tokenRegistry = tokenRegistry;
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<MemoryService.ConversationSummaryDto>> listConversations() {
        String userId = resolveUserId();
        logger.debug("Listing conversations for user: {}", userId);
        return ResponseEntity.ok(memoryService.getRecentConversations(userId));
    }

    @GetMapping("/conversations/{sessionId}")
    public ResponseEntity<List<MemoryService.MessageDto>> getConversation(
            @PathVariable String sessionId) {
        return memoryService.findConversation(sessionId)
            .map(c -> ResponseEntity.ok(memoryService.getConversationMessages(sessionId)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Facts (Option B: agent-driven memory) ────────────────────────────────

    @GetMapping("/facts")
    @Transactional(readOnly = true)
    public ResponseEntity<List<FactDto>> getFacts() {
        String userId = resolveUserId();
        List<FactDto> facts = factRepository.findByUserIdOrderByUpdatedAtDesc(userId)
            .stream()
            .map(f -> new FactDto(f.getKey(), f.getValue(), f.getUpdatedAt()))
            .toList();
        return ResponseEntity.ok(facts);
    }

    @PostMapping("/facts")
    @Transactional
    public ResponseEntity<FactDto> saveFact(@RequestBody SaveFactRequest request) {
        if (request.key() == null || request.key().isBlank()
                || request.value() == null || request.value().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String userId = resolveUserId();
        Fact fact = factRepository.findByUserIdAndKey(userId, request.key())
            .orElseGet(() -> new Fact(userId, request.key(), request.value()));
        fact.setValue(request.value());
        factRepository.save(fact);
        logger.info("Saved fact '{}' for user {}", request.key(), userId);
        return ResponseEntity.ok(new FactDto(fact.getKey(), fact.getValue(), fact.getUpdatedAt()));
    }

    @DeleteMapping("/facts/{key}")
    @Transactional
    public ResponseEntity<Void> deleteFact(@PathVariable String key) {
        String userId = resolveUserId();
        factRepository.findByUserIdAndKey(userId, key).ifPresent(factRepository::delete);
        return ResponseEntity.noContent().build();
    }

    public record SaveFactRequest(String key, String value) {}
    public record FactDto(String key, String value, java.time.Instant updatedAt) {}

    /**
     * Resolve user identity from the request.
     * Priority:
     * 1. Authorization: Bearer <MEMORY_TOKEN> header — used by Goose subprocesses
     *    that call this endpoint via curl without SSO session cookies.
     * 2. Spring Security OAuth2 context — used by the Angular frontend (SSO session).
     * 3. Falls back to "anonymous" if neither is present.
     */
    private String resolveUserId() {
        // Check for Bearer token from Goose subprocess
        jakarta.servlet.http.HttpServletRequest request =
            ((jakarta.servlet.http.HttpServletRequest)
                org.springframework.web.context.request.RequestContextHolder
                    .getRequestAttributes() instanceof
                org.springframework.web.context.request.ServletRequestAttributes sra
                ? sra.getRequest() : null);

        if (request != null) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                String userId = tokenRegistry.resolve(token);
                if (userId != null) {
                    return userId;
                }
            }
        }

        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof OAuth2AuthenticationToken oauthToken) {
                return oauthToken.getName();
            }
            if (auth != null && auth.getName() != null) {
                return auth.getName();
            }
        } catch (Exception e) {
            logger.debug("Could not resolve user ID: {}", e.getMessage());
        }
        return "anonymous";
    }
}
