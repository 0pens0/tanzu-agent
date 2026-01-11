package org.tanzu.goosechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.GooseExecutionException;
import org.tanzu.goose.cf.GooseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * REST controller for managing conversational chat sessions with Goose CLI.
 * <p>
 * This controller provides endpoints for:
 * <ul>
 *   <li>Creating new conversation sessions</li>
 *   <li>Sending messages to existing sessions (with streaming responses)</li>
 *   <li>Closing conversation sessions</li>
 *   <li>Checking session status</li>
 * </ul>
 * </p>
 * 
 * <h3>Conversation Sessions</h3>
 * <p>
 * Each chat session maintains conversation context across multiple messages.
 * Sessions use Goose's native named session feature with {@code --name} and 
 * {@code --resume} flags to enable multi-turn conversations where Goose 
 * remembers previous exchanges. Session data is stored in Goose's SQLite 
 * database at {@code ~/.local/share/goose/sessions/sessions.db}.
 * </p>
 * 
 * @see <a href="https://block.github.io/goose/docs/guides/sessions/">Goose Session Management</a>
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "goose.enabled", havingValue = "true", matchIfMissing = true)
public class GooseChatController {

    private static final Logger logger = LoggerFactory.getLogger(GooseChatController.class);
    private static final String SESSION_PREFIX = "chat-";
    
    private final GooseExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    
    // Session metadata tracking (Goose handles actual session persistence)
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-cleanup");
        t.setDaemon(true);
        return t;
    });

    public GooseChatController(GooseExecutor executor) {
        this.executor = executor;
        logger.info("GooseChatController initialized with Goose native session support");
        
        // Schedule periodic session cleanup
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Create a new conversation session.
     * 
     * @param request optional session configuration
     * @return session ID and success status
     */
    @PostMapping("/sessions")
    public ResponseEntity<CreateSessionResponse> createSession(
            @RequestBody(required = false) CreateSessionRequest request) {
        logger.info("Creating new conversation session");
        
        try {
            if (!executor.isAvailable()) {
                logger.error("Goose CLI is not available");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new CreateSessionResponse(null, false, "Goose CLI is not available"));
            }

            // Generate session ID with prefix for Goose named sessions
            // This creates names like "chat-a1b2c3d4" which Goose stores in its SQLite DB
            String sessionId = SESSION_PREFIX + UUID.randomUUID().toString().substring(0, 8);
            
            // Apply custom configuration if provided
            long inactivityTimeoutMinutes = 30; // default
            String provider = null;
            String model = null;
            
            if (request != null) {
                if (request.sessionInactivityTimeoutMinutes() != null) {
                    inactivityTimeoutMinutes = request.sessionInactivityTimeoutMinutes();
                }
                provider = request.provider();
                model = request.model();
            }

            ConversationSession session = new ConversationSession(
                sessionId,
                provider,
                model,
                Duration.ofMinutes(inactivityTimeoutMinutes),
                System.currentTimeMillis()
            );
            
            sessions.put(sessionId, session);
            
            logger.info("Created conversation session: {} with provider: {}", sessionId, provider);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateSessionResponse(sessionId, true, null));
                
        } catch (Exception e) {
            logger.error("Failed to create conversation session", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CreateSessionResponse(null, false, 
                    "Failed to create session: " + e.getMessage()));
        }
    }

    /**
     * Send a message to an existing session and stream the response via SSE (POST version).
     * <p>
     * Uses Goose CLI's {@code --output-format stream-json} for true token-level streaming.
     * Each token is emitted as an SSE event as it arrives from the LLM.
     * </p>
     * <p>
     * SSE Events emitted:
     * <ul>
     *   <li>{@code status} - Initial processing status</li>
     *   <li>{@code token} - Individual tokens as they arrive</li>
     *   <li>{@code complete} - Completion event with token count</li>
     *   <li>{@code error} - Error events</li>
     * </ul>
     * </p>
     * 
     * @param sessionId the conversation session ID
     * @param request the message to send
     * @return SSE stream of response tokens
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessagePost(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request,
            HttpServletResponse response) {
        return streamMessage(sessionId, request.message(), response);
    }

    /**
     * Send a message to an existing session and stream the response via SSE (GET version).
     * <p>
     * This endpoint supports the native browser EventSource API which only works with GET requests.
     * EventSource handles proxy buffering and chunked encoding better than fetch-based SSE parsing.
     * </p>
     * 
     * @param sessionId the conversation session ID
     * @param message the message to send (URL-encoded)
     * @return SSE stream of response tokens
     */
    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageGet(
            @PathVariable String sessionId,
            @RequestParam String message,
            HttpServletResponse response) {
        return streamMessage(sessionId, message, response);
    }

    /**
     * Internal method to stream a message response via SSE.
     */
    private SseEmitter streamMessage(String sessionId, String message, HttpServletResponse response) {
        logger.info("Streaming message to session {}: {} chars", sessionId, message.length());
        
        // Disable buffering for SSE - critical for Cloud Foundry and reverse proxies
        // Note: Do NOT set Transfer-Encoding manually - Tomcat adds it automatically
        // and setting it twice causes "too many transfer encodings" error in Go Router
        response.setHeader("X-Accel-Buffering", "no");  // Nginx
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout
        
        executorService.execute(() -> {
            Stream<String> jsonStream = null;
            try {
                if (!executor.isAvailable()) {
                    logger.error("Goose CLI is not available");
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Goose CLI is not available"));
                    emitter.complete();
                    return;
                }

                ConversationSession session = sessions.get(sessionId);
                if (session == null || !isSessionActive(sessionId)) {
                    logger.error("Session {} is not active or does not exist", sessionId);
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Session not found or has expired"));
                    emitter.complete();
                    return;
                }

                // Update last activity
                session.updateLastActivity();

                // Send initial status event to confirm processing started
                emitter.send(SseEmitter.event()
                    .name("status")
                    .data("Processing your request..."));

                // Build options for this session
                GooseOptions.Builder optionsBuilder = GooseOptions.builder()
                    .timeout(Duration.ofMinutes(10));
                
                // Override provider/model if session specifies them
                if (session.provider() != null && !session.provider().isEmpty()) {
                    optionsBuilder.provider(session.provider());
                }
                if (session.model() != null && !session.model().isEmpty()) {
                    optionsBuilder.model(session.model());
                }

                GooseOptions options = optionsBuilder.build();

                // Execute Goose with streaming JSON output for token-level streaming
                String prompt = message;
                boolean isFirstMessage = session.messageCount() == 0;
                
                // Use streaming JSON for true real-time token streaming
                jsonStream = executor.executeInSessionStreamingJson(
                    sessionId, prompt, !isFirstMessage, options
                );
                
                // Process each JSON event as it arrives
                // Batch tokens to work around proxy buffering (e.g., Cloud Foundry Go Router)
                final int[] tokenCount = {0};
                final StringBuilder tokenBatch = new StringBuilder();
                final long[] lastSendTime = {System.currentTimeMillis()};
                final int BATCH_SIZE_THRESHOLD = 10; // Send after N tokens
                final long BATCH_TIME_THRESHOLD_MS = 100; // Or after 100ms
                
                jsonStream.forEach(jsonLine -> {
                    try {
                        String token = extractTokenFromJson(jsonLine, sessionId);
                        if (token != null) {
                            tokenBatch.append(token);
                            tokenCount[0]++;
                            
                            long now = System.currentTimeMillis();
                            boolean shouldFlush = tokenCount[0] % BATCH_SIZE_THRESHOLD == 0 
                                || (now - lastSendTime[0]) > BATCH_TIME_THRESHOLD_MS
                                || token.contains("\n");  // Flush on newlines for better UX
                            
                            if (shouldFlush && !tokenBatch.isEmpty()) {
                                emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(tokenBatch.toString()));
                                tokenBatch.setLength(0);
                                lastSendTime[0] = now;
                            }
                        } else if (jsonLine.contains("\"type\":\"complete\"")) {
                            // Flush any remaining tokens before complete
                            if (!tokenBatch.isEmpty()) {
                                emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(tokenBatch.toString()));
                                tokenBatch.setLength(0);
                            }
                            // Send complete event
                            processCompleteEvent(jsonLine, emitter, sessionId);
                        }
                    } catch (IOException e) {
                        logger.error("Error sending SSE event for session {}", sessionId, e);
                        throw new RuntimeException("SSE send failed", e);
                    }
                });
                
                // Flush any remaining tokens
                if (!tokenBatch.isEmpty()) {
                    try {
                        emitter.send(SseEmitter.event()
                            .name("token")
                            .data(tokenBatch.toString()));
                    } catch (IOException e) {
                        logger.error("Error flushing final tokens for session {}", sessionId, e);
                    }
                }
                
                logger.info("Session {} sent {} tokens in batches", sessionId, tokenCount[0]);
                
                // Increment message count
                session.incrementMessageCount();
                
                emitter.complete();
                logger.info("Streaming message completed for session {}", sessionId);
                    
            } catch (GooseExecutionException e) {
                logger.error("Goose execution failed for session {}", sessionId, e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Execution failed: " + e.getMessage()));
                } catch (IOException ex) {
                    logger.error("Failed to send error event", ex);
                }
                emitter.completeWithError(e);
            } catch (Exception e) {
                logger.error("Unexpected error during message send to session {}", sessionId, e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("An unexpected error occurred"));
                } catch (IOException ex) {
                    logger.error("Failed to send error event", ex);
                }
                emitter.completeWithError(e);
            } finally {
                // Ensure stream is closed to clean up subprocess
                if (jsonStream != null) {
                    jsonStream.close();
                }
            }
        });

        emitter.onTimeout(() -> {
            logger.warn("SSE connection timed out for session {}", sessionId);
            emitter.complete();
        });

        emitter.onError((e) -> {
            logger.error("SSE error for session {}", sessionId, e);
        });

        return emitter;
    }

    /**
     * Process a streaming JSON event from Goose CLI and emit corresponding SSE events.
     * <p>
     * JSON event formats:
     * <ul>
     *   <li>Message: {@code {"type":"message","message":{"content":[{"type":"text","text":"token"}],...}}}</li>
     *   <li>Complete: {@code {"type":"complete","total_tokens":1234}}</li>
     * </ul>
     * </p>
     */
    /**
     * Extract token text from a streaming JSON event.
     * 
     * @return the token text, or null if this is not a message event with text
     */
    private String extractTokenFromJson(String jsonLine, String sessionId) {
        try {
            JsonNode event = objectMapper.readTree(jsonLine);
            String type = event.has("type") ? event.get("type").asText() : "";
            
            if ("message".equals(type)) {
                JsonNode content = event.at("/message/content");
                if (content.isArray() && !content.isEmpty()) {
                    JsonNode firstContent = content.get(0);
                    if (firstContent.has("text")) {
                        String text = firstContent.get("text").asText();
                        if (!text.isEmpty()) {
                            return text;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to extract token from JSON for session {}: {}", sessionId, jsonLine, e);
        }
        return null;
    }

    /**
     * Process a complete event from streaming JSON.
     */
    private void processCompleteEvent(String jsonLine, SseEmitter emitter, String sessionId) throws IOException {
        try {
            JsonNode event = objectMapper.readTree(jsonLine);
            int totalTokens = event.has("total_tokens") ? event.get("total_tokens").asInt() : 0;
            emitter.send(SseEmitter.event()
                .name("complete")
                .data(String.valueOf(totalTokens)));
            logger.info("Session {} completed streaming with {} total LLM tokens", sessionId, totalTokens);
        } catch (Exception e) {
            logger.warn("Failed to parse complete event for session {}: {}", sessionId, jsonLine, e);
        }
    }

    /**
     * Close a conversation session.
     * 
     * @param sessionId the session to close
     * @return success status
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<CloseSessionResponse> closeSession(@PathVariable String sessionId) {
        logger.info("Closing conversation session: {}", sessionId);
        
        try {
            ConversationSession removed = sessions.remove(sessionId);
            if (removed != null) {
                logger.info("Session {} closed successfully", sessionId);
            }
            return ResponseEntity.ok(
                new CloseSessionResponse(true, "Session closed successfully")
            );
        } catch (Exception e) {
            logger.error("Failed to close session {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CloseSessionResponse(false, 
                    "Failed to close session: " + e.getMessage()));
        }
    }

    /**
     * Check if a session is active.
     * 
     * @param sessionId the session to check
     * @return session status
     */
    @GetMapping("/sessions/{sessionId}/status")
    public ResponseEntity<SessionStatusResponse> getSessionStatus(@PathVariable String sessionId) {
        logger.debug("Checking status for session: {}", sessionId);
        
        try {
            boolean active = isSessionActive(sessionId);
            return ResponseEntity.ok(new SessionStatusResponse(sessionId, active));
        } catch (Exception e) {
            logger.error("Failed to check session status for {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new SessionStatusResponse(sessionId, false));
        }
    }

    /**
     * Check if a session is active (exists and hasn't timed out).
     */
    private boolean isSessionActive(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        
        long timeSinceLastActivity = System.currentTimeMillis() - session.lastActivity();
        return timeSinceLastActivity < session.inactivityTimeout().toMillis();
    }

    /**
     * Clean up expired sessions.
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            ConversationSession session = entry.getValue();
            boolean expired = (now - session.lastActivity()) >= session.inactivityTimeout().toMillis();
            if (expired) {
                logger.info("Cleaning up expired session: {}", entry.getKey());
            }
            return expired;
        });
    }

    // Request/Response records
    
    public record CreateSessionRequest(
        String provider,      // anthropic, openai, google, databricks, ollama
        String model,
        Integer sessionInactivityTimeoutMinutes
    ) {}

    public record CreateSessionResponse(
        String sessionId,
        boolean success,
        String message
    ) {}

    public record SendMessageRequest(String message) {}

    public record CloseSessionResponse(
        boolean success,
        String message
    ) {}

    public record SessionStatusResponse(
        String sessionId,
        boolean active
    ) {}

    /**
     * Internal session tracking record.
     */
    private static class ConversationSession {
        private final String id;
        private final String provider;
        private final String model;
        private final Duration inactivityTimeout;
        private volatile long lastActivity;
        private volatile int messageCount;

        ConversationSession(String id, String provider, String model, 
                           Duration inactivityTimeout, long createdAt) {
            this.id = id;
            this.provider = provider;
            this.model = model;
            this.inactivityTimeout = inactivityTimeout;
            this.lastActivity = createdAt;
            this.messageCount = 0;
        }

        String id() { return id; }
        String provider() { return provider; }
        String model() { return model; }
        Duration inactivityTimeout() { return inactivityTimeout; }
        long lastActivity() { return lastActivity; }
        int messageCount() { return messageCount; }
        
        void updateLastActivity() { 
            this.lastActivity = System.currentTimeMillis(); 
        }
        
        void incrementMessageCount() {
            this.messageCount++;
        }
    }
}

