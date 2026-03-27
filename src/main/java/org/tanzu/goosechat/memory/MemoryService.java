package org.tanzu.goosechat.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Manages short-term and long-term memory for Goose agent conversations.
 *
 * <p>Short-term: Spring Cache (@Cacheable) — in-memory by default, swap to Valkey
 * by adding spring-data-redis and a RedisConnectionFactory bean.
 *
 * <p>Long-term: PostgreSQL via JPA (Conversation + Message entities).
 *
 * <p>Context injection: buildContextSummary() produces a compact text block that
 * is prepended to the first user message of every new session so the agent has
 * continuity with previous conversations.
 */
@Profile("memory")
@Service
@Transactional
public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);

    /** Maximum number of previous turns to include in the context summary. */
    private static final int CONTEXT_TURNS = 10;

    /** Maximum character length for a single message snippet in the context. */
    private static final int SNIPPET_MAX_CHARS = 300;

    /** Number of recent conversations to show in the sidebar. */
    private static final int RECENT_CONVERSATIONS_LIMIT = 20;

    private final ConversationRepository conversationRepo;
    private final MessageRepository messageRepo;

    public MemoryService(ConversationRepository conversationRepo, MessageRepository messageRepo) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
    }

    // ── Conversation lifecycle ────────────────────────────────────────────────

    /**
     * Create or retrieve the Conversation record for a session.
     * Called when a new Goose session is created.
     */
    public Conversation ensureConversation(String sessionId, String userId) {
        return conversationRepo.findById(sessionId)
            .orElseGet(() -> {
                Conversation c = new Conversation(sessionId, userId);
                conversationRepo.save(c);
                logger.info("Created conversation record: {} for user: {}", sessionId, userId);
                return c;
            });
    }

    // ── Message persistence ───────────────────────────────────────────────────

    /**
     * Persist a single turn (user prompt + assistant response) to PostgreSQL.
     * Evicts the user's conversation list cache so the sidebar stays fresh.
     */
    @Caching(evict = {
        @CacheEvict(value = "user-conversations", key = "#userId"),
        @CacheEvict(value = "user-context",       key = "#userId")
    })
    public void saveTurn(String sessionId, String userId, String userMessage, String assistantResponse) {
        Conversation conversation = ensureConversation(sessionId, userId);

        Message userMsg = new Message(conversation, "user", userMessage);
        Message assistantMsg = new Message(conversation, "assistant", assistantResponse);

        messageRepo.save(userMsg);
        messageRepo.save(assistantMsg);

        // Auto-generate title from the first user message
        if (conversation.getTitle() == null) {
            String title = userMessage.length() > 80
                ? userMessage.substring(0, 77) + "…"
                : userMessage;
            conversation.setTitle(title);
        }
        conversation.touch();
        conversationRepo.save(conversation);

        logger.debug("Saved turn for session {} (user: {})", sessionId, userId);
    }

    // ── Context summary for new sessions ─────────────────────────────────────

    /**
     * Build a compact context block from the user's most recent messages.
     * This is prepended to the first message of a new session so the agent
     * has continuity without the user having to re-explain everything.
     *
     * Returns null if the user has no history (first-ever session).
     */
    @Cacheable(value = "user-context", key = "#userId", unless = "#result == null")
    @Transactional(readOnly = true)
    public String buildContextSummary(String userId) {
        List<Message> recent = messageRepo.findLatestByUserId(userId, CONTEXT_TURNS);
        if (recent.isEmpty()) {
            return null;
        }

        // Messages come back newest-first; reverse for chronological display
        List<Message> chronological = recent.reversed();

        StringBuilder sb = new StringBuilder();
        sb.append("--- Previous conversation context (most recent ").append(chronological.size())
          .append(" turns) ---\n");

        for (Message m : chronological) {
            String snippet = m.getContent().length() > SNIPPET_MAX_CHARS
                ? m.getContent().substring(0, SNIPPET_MAX_CHARS) + "…"
                : m.getContent();
            sb.append("[").append(m.getRole().toUpperCase()).append("]: ").append(snippet).append("\n");
        }

        sb.append("--- End of context ---\n\n");
        return sb.toString();
    }

    // ── History queries for sidebar ───────────────────────────────────────────

    /**
     * Return the user's recent conversations (for the history sidebar).
     * Cached per-user; evicted when a new turn is saved.
     */
    @Cacheable(value = "user-conversations", key = "#userId")
    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getRecentConversations(String userId) {
        return conversationRepo.findRecentByUserId(userId, RECENT_CONVERSATIONS_LIMIT)
            .stream()
            .map(c -> new ConversationSummaryDto(
                c.getId(),
                c.getTitle() != null ? c.getTitle() : "Untitled",
                c.getCreatedAt(),
                c.getUpdatedAt()
            ))
            .collect(Collectors.toList());
    }

    /**
     * Return all messages for a specific conversation (for "resume" view).
     */
    @Transactional(readOnly = true)
    public List<MessageDto> getConversationMessages(String sessionId) {
        return messageRepo.findByConversationIdOrderByCreatedAtAsc(sessionId)
            .stream()
            .map(m -> new MessageDto(m.getRole(), m.getContent(), m.getCreatedAt()))
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Optional<Conversation> findConversation(String sessionId) {
        return conversationRepo.findById(sessionId);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ConversationSummaryDto(
        String id,
        String title,
        java.time.Instant createdAt,
        java.time.Instant updatedAt
    ) {}

    public record MessageDto(
        String role,
        String content,
        java.time.Instant createdAt
    ) {}
}
