# Goose Agent Chat - Technical Design

## Overview

A full-stack web application providing a chat interface for interacting with Goose AI agent, patterned after [claude-agent-chat](https://github.com/cpage-pivotal/claude-agent-chat). Built with Spring Boot and Angular, featuring real-time streaming responses and Material Design UI.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Cloud Foundry Container                                                   │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐ │
│  │  Spring Boot Application (JAR)                                        │ │
│  │                                                                        │ │
│  │  ┌─────────────────────┐      ┌────────────────────────────────────┐ │ │
│  │  │  Angular SPA        │      │  REST Controllers                  │ │ │
│  │  │  /static/*          │─────▶│  GooseChatController               │ │ │
│  │  │  Material Design 3  │ HTTP │  ChatHealthController              │ │ │
│  │  │                     │      │  DiagnosticsController             │ │ │
│  │  └─────────────────────┘      └────────────────────────────────────┘ │ │
│  │                                          │                            │ │
│  │                                          ▼                            │ │
│  │                               ┌────────────────────────┐              │ │
│  │                               │  GooseExecutor         │              │ │
│  │                               │  (goose-cf-wrapper)    │              │ │
│  │                               │  - Session management  │              │ │
│  │                               │  - ProcessBuilder      │              │ │
│  │                               └────────────────────────┘              │ │
│  │                                          │                            │ │
│  └──────────────────────────────────────────│────────────────────────────┘ │
│                                             │                              │
│  ┌──────────────────────────────────────────│────────────────────────────┐ │
│  │  Goose Buildpack (Supply)                ▼                            │ │
│  │  /home/vcap/deps/{idx}/bin/goose ◄───────────────────────────────────│ │
│  │  Environment: GOOSE_CLI_PATH, provider config                         │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
                        ┌─────────────────────────┐
                        │   LLM Provider API      │
                        │   (Anthropic, OpenAI,   │
                        │    Google, Databricks)  │
                        └─────────────────────────┘
```

---

## Project Structure

```
goose-agent-chat/
├── src/main/
│   ├── java/org/tanzu/goosechat/
│   │   ├── GooseAgentChatApplication.java     # Main application
│   │   ├── GooseChatController.java           # Chat streaming endpoint
│   │   ├── ChatHealthController.java          # Health check endpoint
│   │   └── DiagnosticsController.java         # Diagnostics
│   ├── frontend/                              # Angular application
│   │   ├── src/app/
│   │   │   ├── components/
│   │   │   │   ├── chat/                      # Chat component
│   │   │   │   └── settings/                  # Provider settings
│   │   │   ├── services/
│   │   │   │   ├── chat.service.ts            # SSE streaming
│   │   │   │   └── settings.service.ts        # Provider config
│   │   │   └── pipes/
│   │   │       └── markdown.pipe.ts           # Markdown rendering
│   │   ├── angular.json
│   │   ├── package.json
│   │   └── proxy.conf.mjs                     # Dev proxy config
│   └── resources/
│       ├── application.properties
│       └── .goose-config.yml                  # Goose configuration
├── pom.xml
├── manifest.yml
└── README.md
```

---

## Component Design

### 1. Backend: Spring Boot

#### GooseChatController.java

```java
package org.tanzu.goosechat;

import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.GooseExecutionException;
import org.tanzu.goose.cf.GooseOptions;
import org.tanzu.goose.cf.ConversationSessionManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * REST controller for managing conversational chat sessions with Goose CLI.
 * 
 * Provides endpoints for:
 * - Creating new conversation sessions
 * - Sending messages to existing sessions (with streaming responses)
 * - Closing conversation sessions
 * - Checking session status
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "goose.enabled", havingValue = "true", matchIfMissing = true)
public class GooseChatController {

    private static final Logger logger = LoggerFactory.getLogger(GooseChatController.class);
    private final GooseExecutor executor;
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();

    public GooseChatController(GooseExecutor executor) {
        this.executor = executor;
        logger.info("GooseChatController initialized with conversational session support");
    }

    /**
     * Create a new conversation session.
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

            GooseOptions.Builder optionsBuilder = GooseOptions.builder();

            // Apply custom configuration if provided
            if (request != null) {
                if (request.sessionInactivityTimeoutMinutes() != null) {
                    optionsBuilder.sessionInactivityTimeout(
                        Duration.ofMinutes(request.sessionInactivityTimeoutMinutes())
                    );
                }
                if (request.provider() != null) {
                    optionsBuilder.provider(request.provider());
                }
                if (request.model() != null) {
                    optionsBuilder.model(request.model());
                }
            }

            GooseOptions options = optionsBuilder.build();
            String sessionId = executor.createConversationSession(options);
            
            logger.info("Created conversation session: {}", sessionId);
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
     * Send a message to an existing session and stream the response via SSE.
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request) {
        logger.info("Sending message to session {}: {} chars", sessionId, request.message().length());
        
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout
        
        executorService.execute(() -> {
            try {
                if (!executor.isAvailable()) {
                    logger.error("Goose CLI is not available");
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Goose CLI is not available"));
                    emitter.complete();
                    return;
                }

                if (!executor.isSessionActive(sessionId)) {
                    logger.error("Session {} is not active or does not exist", sessionId);
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Session not found or has expired"));
                    emitter.complete();
                    return;
                }

                // Send initial status event
                emitter.send(SseEmitter.event()
                    .name("status")
                    .data("Processing your request..."));

                // Execute Goose with heartbeat support
                try {
                    Future<String> future = executorService.submit(() -> 
                        executor.sendMessage(sessionId, request.message())
                    );

                    // Send heartbeat every 30 seconds to keep connection alive
                    long heartbeatIntervalMs = 30_000L;
                    String response;
                    
                    while (true) {
                        try {
                            response = future.get(heartbeatIntervalMs, TimeUnit.MILLISECONDS);
                            break;
                        } catch (TimeoutException e) {
                            emitter.send(SseEmitter.event()
                                .name("heartbeat")
                                .data("Still processing..."));
                            logger.info("Sent heartbeat for session {}", sessionId);
                        }
                    }
                    
                    // Stream response line by line
                    String[] lines = response.split("\n");
                    for (String line : lines) {
                        emitter.send(SseEmitter.event()
                            .name("message")
                            .data(line));
                    }
                    
                    emitter.complete();
                    logger.info("Message sent successfully to session {}", sessionId);
                    
                } catch (ConversationSessionManager.SessionNotFoundException e) {
                    logger.error("Session {} not found", sessionId, e);
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Session not found or has expired"));
                    emitter.completeWithError(e);
                } catch (GooseExecutionException e) {
                    logger.error("Goose execution failed for session {}", sessionId, e);
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Execution failed: " + e.getMessage()));
                    emitter.completeWithError(e);
                }
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
     * Close a conversation session.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<CloseSessionResponse> closeSession(@PathVariable String sessionId) {
        logger.info("Closing conversation session: {}", sessionId);
        
        try {
            executor.closeConversationSession(sessionId);
            logger.info("Session {} closed successfully", sessionId);
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
     */
    @GetMapping("/sessions/{sessionId}/status")
    public ResponseEntity<SessionStatusResponse> getSessionStatus(@PathVariable String sessionId) {
        logger.debug("Checking status for session: {}", sessionId);
        
        try {
            boolean active = executor.isSessionActive(sessionId);
            return ResponseEntity.ok(new SessionStatusResponse(sessionId, active));
        } catch (Exception e) {
            logger.error("Failed to check session status for {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new SessionStatusResponse(sessionId, false));
        }
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
}
```

#### ChatHealthController.java

```java
package org.tanzu.goosechat;

import org.tanzu.goose.cf.GooseExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "goose.enabled", havingValue = "true", matchIfMissing = true)
public class ChatHealthController {

    private final GooseExecutor executor;

    public ChatHealthController(GooseExecutor executor) {
        this.executor = executor;
    }

    @GetMapping("/health")
    public HealthResponse getHealth() {
        boolean available = executor.isAvailable();
        String version = available ? executor.getVersion() : "unknown";
        String provider = available ? executor.getConfiguredProvider() : "unknown";
        String model = available ? executor.getConfiguredModel() : "unknown";
        
        return new HealthResponse(
            available,
            version,
            provider,
            model,
            available ? null : "Goose CLI is not available or not configured"
        );
    }

    public record HealthResponse(
        boolean available,
        String version,
        String provider,
        String model,
        String message
    ) {}
}
```

---

### 2. Java Wrapper Library: goose-cf-wrapper

This library needs to be created separately (similar to claude-code-cf-wrapper).

#### GooseExecutor.java (Interface)

```java
package org.tanzu.goose.cf;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Executor for Goose CLI operations.
 */
public interface GooseExecutor {

    /**
     * Check if Goose CLI is available and configured.
     */
    boolean isAvailable();

    /**
     * Get the Goose CLI version.
     */
    String getVersion();

    /**
     * Get the configured LLM provider.
     */
    String getConfiguredProvider();

    /**
     * Get the configured model.
     */
    String getConfiguredModel();

    /**
     * Execute a single prompt (stateless).
     */
    String execute(String prompt) throws GooseExecutionException;

    /**
     * Execute with streaming output.
     */
    void executeStreaming(String prompt, Consumer<String> outputHandler) 
        throws GooseExecutionException;

    /**
     * Create a new conversation session.
     */
    String createConversationSession(GooseOptions options) throws GooseExecutionException;

    /**
     * Send a message to an existing session.
     */
    String sendMessage(String sessionId, String message) throws GooseExecutionException;

    /**
     * Check if a session is active.
     */
    boolean isSessionActive(String sessionId);

    /**
     * Close a conversation session.
     */
    void closeConversationSession(String sessionId) throws GooseExecutionException;
}
```

#### GooseExecutorImpl.java

```java
package org.tanzu.goose.cf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class GooseExecutorImpl implements GooseExecutor {

    private static final Logger logger = LoggerFactory.getLogger(GooseExecutorImpl.class);
    
    private final String goosePath;
    private final long timeoutMinutes;
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    public GooseExecutorImpl() {
        this.goosePath = System.getenv().getOrDefault("GOOSE_CLI_PATH", 
            "/home/vcap/deps/0/bin/goose");
        this.timeoutMinutes = Long.parseLong(
            System.getenv().getOrDefault("GOOSE_TIMEOUT_MINUTES", "10"));
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(goosePath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            logger.error("Goose CLI not available", e);
            return false;
        }
    }

    @Override
    public String getVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder(goosePath, "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                return reader.readLine();
            }
        } catch (Exception e) {
            logger.error("Failed to get Goose version", e);
            return "unknown";
        }
    }

    @Override
    public String getConfiguredProvider() {
        // Read from environment or config file
        return System.getenv().getOrDefault("GOOSE_PROVIDER__TYPE", "anthropic");
    }

    @Override
    public String getConfiguredModel() {
        return System.getenv().getOrDefault("GOOSE_PROVIDER__MODEL", "claude-sonnet-4-20250514");
    }

    @Override
    public String execute(String prompt) throws GooseExecutionException {
        StringBuilder output = new StringBuilder();
        executeStreaming(prompt, line -> output.append(line).append("\n"));
        return output.toString();
    }

    @Override
    public void executeStreaming(String prompt, Consumer<String> outputHandler) 
            throws GooseExecutionException {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                goosePath,
                "session",
                "--text", prompt,
                "--max-turns", "100"
            );

            configureEnvironment(pb);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            process.getOutputStream().close();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputHandler.accept(line);
                }
            }

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new GooseExecutionException("Process timed out");
            }

            if (process.exitValue() != 0) {
                throw new GooseExecutionException("Goose exited with code: " + process.exitValue());
            }

        } catch (IOException | InterruptedException e) {
            throw new GooseExecutionException("Failed to execute Goose", e);
        }
    }

    @Override
    public String createConversationSession(GooseOptions options) throws GooseExecutionException {
        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        
        ConversationSession session = new ConversationSession(
            sessionId,
            options,
            System.currentTimeMillis()
        );
        
        sessions.put(sessionId, session);
        logger.info("Created session: {} with provider: {}", sessionId, options.provider());
        
        return sessionId;
    }

    @Override
    public String sendMessage(String sessionId, String message) throws GooseExecutionException {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) {
            throw new ConversationSessionManager.SessionNotFoundException(sessionId);
        }

        // Update last activity
        session.updateLastActivity();

        // Build command with session context
        // Goose maintains conversation via --resume or session name
        ProcessBuilder pb = new ProcessBuilder(
            goosePath,
            "session",
            "--name", sessionId,
            "--resume",
            "--text", message,
            "--max-turns", "100"
        );

        // Apply session options
        if (session.options().provider() != null) {
            pb.environment().put("GOOSE_PROVIDER__TYPE", session.options().provider());
        }
        if (session.options().model() != null) {
            pb.environment().put("GOOSE_PROVIDER__MODEL", session.options().model());
        }

        configureEnvironment(pb);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            process.getOutputStream().close();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new GooseExecutionException("Process timed out");
            }

            if (process.exitValue() != 0) {
                throw new GooseExecutionException("Goose exited with code: " + process.exitValue());
            }

            return output.toString();

        } catch (IOException | InterruptedException e) {
            throw new GooseExecutionException("Failed to send message", e);
        }
    }

    @Override
    public boolean isSessionActive(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        
        // Check if session has timed out
        long inactivityTimeout = session.options().sessionInactivityTimeout() != null
            ? session.options().sessionInactivityTimeout().toMillis()
            : 30 * 60 * 1000; // 30 minutes default
            
        return (System.currentTimeMillis() - session.lastActivity()) < inactivityTimeout;
    }

    @Override
    public void closeConversationSession(String sessionId) throws GooseExecutionException {
        ConversationSession session = sessions.remove(sessionId);
        if (session != null) {
            logger.info("Closed session: {}", sessionId);
        }
    }

    private void configureEnvironment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        
        // Pass through API keys for various providers
        copyEnvIfPresent(env, "ANTHROPIC_API_KEY");
        copyEnvIfPresent(env, "OPENAI_API_KEY");
        copyEnvIfPresent(env, "GOOGLE_API_KEY");
        copyEnvIfPresent(env, "DATABRICKS_HOST");
        copyEnvIfPresent(env, "DATABRICKS_TOKEN");
        copyEnvIfPresent(env, "OLLAMA_HOST");
        
        // Goose-specific config
        copyEnvIfPresent(env, "GOOSE_PROVIDER__TYPE");
        copyEnvIfPresent(env, "GOOSE_PROVIDER__MODEL");
        copyEnvIfPresent(env, "GOOSE_CONFIG_DIR");
        
        env.put("HOME", System.getenv("HOME"));
    }

    private void copyEnvIfPresent(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            env.put(key, value);
        }
    }

    // Inner classes
    
    private static class ConversationSession {
        private final String id;
        private final GooseOptions options;
        private long lastActivity;

        ConversationSession(String id, GooseOptions options, long createdAt) {
            this.id = id;
            this.options = options;
            this.lastActivity = createdAt;
        }

        GooseOptions options() { return options; }
        long lastActivity() { return lastActivity; }
        void updateLastActivity() { this.lastActivity = System.currentTimeMillis(); }
    }
}
```

#### GooseOptions.java

```java
package org.tanzu.goose.cf;

import java.time.Duration;

public record GooseOptions(
    String provider,
    String model,
    Duration sessionInactivityTimeout,
    Integer maxTurns,
    boolean debug
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String provider;
        private String model;
        private Duration sessionInactivityTimeout;
        private Integer maxTurns = 100;
        private boolean debug = false;

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder sessionInactivityTimeout(Duration timeout) {
            this.sessionInactivityTimeout = timeout;
            return this;
        }

        public Builder maxTurns(Integer maxTurns) {
            this.maxTurns = maxTurns;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public GooseOptions build() {
            return new GooseOptions(provider, model, sessionInactivityTimeout, maxTurns, debug);
        }
    }
}
```

---

### 3. Frontend: Angular

#### chat.service.ts

```typescript
import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  streaming?: boolean;
}

export interface SessionInfo {
  sessionId: string;
  createdAt: Date;
  provider?: string;
  model?: string;
}

export interface HealthInfo {
  available: boolean;
  version: string;
  provider: string;
  model: string;
  message?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatService {
  private readonly apiUrl = '/api/chat';
  private currentSession = signal<SessionInfo | null>(null);

  getCurrentSession(): SessionInfo | null {
    return this.currentSession();
  }

  /**
   * Create a new conversation session
   */
  async createSession(
    provider?: string, 
    model?: string,
    timeoutMinutes?: number
  ): Promise<string> {
    const body: any = {};
    if (provider) body.provider = provider;
    if (model) body.model = model;
    if (timeoutMinutes) body.sessionInactivityTimeoutMinutes = timeoutMinutes;

    const response = await fetch(`${this.apiUrl}/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: Object.keys(body).length > 0 ? JSON.stringify(body) : undefined
    });

    if (!response.ok) {
      throw new Error(`Failed to create session: ${response.status}`);
    }

    const result = await response.json();
    if (!result.success || !result.sessionId) {
      throw new Error(result.message || 'Failed to create session');
    }

    this.currentSession.set({
      sessionId: result.sessionId,
      createdAt: new Date(),
      provider,
      model
    });

    console.log('Created new Goose session:', result.sessionId);
    return result.sessionId;
  }

  /**
   * Send a message to the current session with SSE streaming
   */
  sendMessage(message: string, sessionId: string): Observable<string> {
    return new Observable(observer => {
      let reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
      let buffer = '';

      fetch(`${this.apiUrl}/sessions/${sessionId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message })
      })
      .then(response => {
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        return response.body;
      })
      .then(body => {
        if (!body) {
          throw new Error('Response body is empty');
        }
        
        reader = body.getReader();
        const decoder = new TextDecoder();

        const processStream = (): Promise<void> => {
          return reader!.read().then(({ done, value }) => {
            if (done) {
              observer.complete();
              return;
            }

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';
            
            let currentEvent = '';
            for (const line of lines) {
              if (line.startsWith('event:')) {
                currentEvent = line.substring(6).trim();
              } else if (line.startsWith('data:')) {
                let data = line.substring(5);
                if (data.startsWith(' ')) {
                  data = data.substring(1);
                }
                
                if (currentEvent === 'error') {
                  observer.error(new Error(data));
                  return;
                } else if (currentEvent === 'heartbeat' || currentEvent === 'status') {
                  console.log(`[SSE ${currentEvent}]`, data);
                  currentEvent = '';
                  continue;
                } else if (currentEvent === 'message' || currentEvent === '') {
                  observer.next(data);
                }
                
                currentEvent = '';
              }
            }

            return processStream();
          });
        };

        return processStream();
      })
      .catch(error => {
        console.error('Stream error:', error);
        observer.error(error);
      });

      return () => {
        if (reader) {
          reader.cancel().catch(err => console.error('Error canceling stream:', err));
        }
      };
    });
  }

  async closeSession(sessionId: string): Promise<void> {
    try {
      const response = await fetch(`${this.apiUrl}/sessions/${sessionId}`, {
        method: 'DELETE'
      });

      if (!response.ok) {
        console.warn(`Failed to close session ${sessionId}: ${response.status}`);
      }

      const currentSessionInfo = this.currentSession();
      if (currentSessionInfo && currentSessionInfo.sessionId === sessionId) {
        this.currentSession.set(null);
      }

      console.log('Closed Goose session:', sessionId);
    } catch (error) {
      console.error('Error closing session:', error);
      throw error;
    }
  }

  async checkSessionStatus(sessionId: string): Promise<boolean> {
    try {
      const response = await fetch(`${this.apiUrl}/sessions/${sessionId}/status`);
      if (!response.ok) return false;
      const result = await response.json();
      return result.active;
    } catch (error) {
      console.error('Error checking session status:', error);
      return false;
    }
  }

  checkHealth(): Promise<HealthInfo> {
    return fetch(`${this.apiUrl}/health`)
      .then(response => response.json())
      .catch(error => {
        console.error('Health check failed:', error);
        return { 
          available: false, 
          version: 'unknown',
          provider: 'unknown',
          model: 'unknown',
          message: 'Health check endpoint not reachable' 
        };
      });
  }
}
```

#### chat.component.ts

```typescript
import { Component, signal, effect, ViewChild, ElementRef } from '@angular/core';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatMenuModule } from '@angular/material/menu';
import { ChatService, ChatMessage, HealthInfo } from '../../services/chat.service';
import { MarkdownPipe } from '../../pipes/markdown.pipe';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatTooltipModule,
    MatSnackBarModule,
    MatMenuModule,
    MarkdownPipe
  ],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss'
})
export class ChatComponent {
  @ViewChild('messagesContainer') private messagesContainer!: ElementRef;
  
  protected messages = signal<ChatMessage[]>([]);
  protected userInput = signal('');
  protected isStreaming = signal(false);
  protected healthInfo = signal<HealthInfo | null>(null);
  protected sessionId = signal<string | null>(null);
  protected isCreatingSession = signal(false);

  constructor(
    private chatService: ChatService,
    private snackBar: MatSnackBar
  ) {
    // Check Goose availability on init
    this.chatService.checkHealth().then(health => {
      this.healthInfo.set(health);
    });

    // Auto-scroll when messages update
    effect(() => {
      this.messages();
      requestAnimationFrame(() => {
        setTimeout(() => this.scrollToBottom(), 50);
      });
    });

    // Auto-create session when Goose is available
    effect(() => {
      const health = this.healthInfo();
      if (health?.available && !this.sessionId() && !this.isCreatingSession()) {
        this.startNewConversation();
      }
    });
  }

  protected get gooseAvailable(): boolean {
    return this.healthInfo()?.available ?? false;
  }

  protected get gooseVersion(): string {
    return this.healthInfo()?.version ?? 'unknown';
  }

  protected get gooseProvider(): string {
    return this.healthInfo()?.provider ?? 'unknown';
  }

  protected get gooseModel(): string {
    return this.healthInfo()?.model ?? 'unknown';
  }

  protected async startNewConversation(): Promise<void> {
    if (this.isCreatingSession()) return;

    this.isCreatingSession.set(true);

    try {
      const currentSessionId = this.sessionId();
      if (currentSessionId) {
        await this.chatService.closeSession(currentSessionId);
      }

      const newSessionId = await this.chatService.createSession();
      this.sessionId.set(newSessionId);
      this.messages.set([]);
      
      this.snackBar.open('New Goose conversation started', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
      
      console.log('Started new conversation with session:', newSessionId);
    } catch (error) {
      console.error('Failed to start new conversation:', error);
      this.snackBar.open('Failed to start conversation', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
      this.sessionId.set(null);
    } finally {
      this.isCreatingSession.set(false);
    }
  }

  protected async endConversation(): Promise<void> {
    const currentSessionId = this.sessionId();
    if (!currentSessionId) return;

    try {
      await this.chatService.closeSession(currentSessionId);
      this.sessionId.set(null);
      this.messages.set([]);
      
      this.snackBar.open('Conversation ended', 'Close', {
        duration: 2000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    } catch (error) {
      console.error('Failed to end conversation:', error);
      this.snackBar.open('Failed to end conversation', 'Close', {
        duration: 3000,
        horizontalPosition: 'center',
        verticalPosition: 'bottom'
      });
    }
  }

  protected sendMessage(): void {
    const prompt = this.userInput().trim();
    const currentSessionId = this.sessionId();
    
    if (!prompt || this.isStreaming() || !currentSessionId) return;

    // Add user message
    const userMessage: ChatMessage = {
      role: 'user',
      content: prompt,
      timestamp: new Date()
    };
    this.messages.update(msgs => [...msgs, userMessage]);
    this.userInput.set('');
    this.isStreaming.set(true);

    // Add assistant placeholder
    const assistantMessage: ChatMessage = {
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      streaming: true
    };
    this.messages.update(msgs => [...msgs, assistantMessage]);

    // Stream the response
    this.chatService.sendMessage(prompt, currentSessionId).subscribe({
      next: (chunk: string) => {
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [
              ...msgs.slice(0, -1),
              { ...lastMsg, content: lastMsg.content + chunk + '\n' }
            ];
          }
          return msgs;
        });
      },
      error: (error) => {
        console.error('Chat error:', error);
        const errorMessage = error.message || 'Failed to get response from Goose.';
        
        if (errorMessage.includes('Session not found') || errorMessage.includes('expired')) {
          this.snackBar.open('Session expired. Starting new conversation...', 'Close', {
            duration: 3000
          });
          
          this.messages.update(msgs => msgs.slice(0, -1));
          this.startNewConversation().then(() => {
            if (this.sessionId()) {
              this.userInput.set(prompt);
              setTimeout(() => this.sendMessage(), 500);
            }
          });
        } else {
          this.messages.update(msgs => {
            const lastMsg = msgs[msgs.length - 1];
            if (lastMsg.role === 'assistant' && lastMsg.streaming) {
              return [
                ...msgs.slice(0, -1),
                { ...lastMsg, content: lastMsg.content || `Error: ${errorMessage}`, streaming: false }
              ];
            }
            return msgs;
          });
        }
        
        this.isStreaming.set(false);
      },
      complete: () => {
        this.messages.update(msgs => {
          const lastMsg = msgs[msgs.length - 1];
          if (lastMsg.role === 'assistant') {
            return [...msgs.slice(0, -1), { ...lastMsg, streaming: false }];
          }
          return msgs;
        });
        this.isStreaming.set(false);
      }
    });
  }

  protected onKeyPress(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  protected onInputChange(event: Event): void {
    const target = event.target as HTMLTextAreaElement;
    this.userInput.set(target.value);
  }

  private scrollToBottom(): void {
    if (this.messagesContainer) {
      const element = this.messagesContainer.nativeElement;
      element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' });
    }
  }
}
```

---

### 4. Configuration Files

#### manifest.yml

```yaml
---
applications:
  - name: goose-agent-chat
    path: target/goose-agent-chat-1.0.0.jar
    memory: 2G
    buildpacks:
      - nodejs_buildpack
      - https://github.com/cpage-pivotal/goose-buildpack
      - java_buildpack_offline
    env:
      JBP_CONFIG_OPEN_JDK_JRE: '{ jre: { version: 21.+ } }'
      GOOSE_ENABLED: true
      # Provider configuration - choose one:
      ANTHROPIC_API_KEY: ((ANTHROPIC_API_KEY))
      # OPENAI_API_KEY: ((OPENAI_API_KEY))
      # GOOGLE_API_KEY: ((GOOGLE_API_KEY))
      # DATABRICKS_HOST: ((DATABRICKS_HOST))
      # DATABRICKS_TOKEN: ((DATABRICKS_TOKEN))
```

#### .goose-config.yml

```yaml
goose:
  enabled: true
  version: "latest"
  
  provider:
    type: anthropic
    model: claude-sonnet-4-20250514
  
  extensions:
    developer:
      enabled: true
  
  # Optional: MCP server integration
  mcpServers:
    - name: github
      type: sse
      url: "https://github-mcp-server.apps.example.com/sse"
```

#### pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.9</version>
        <relativePath/>
    </parent>
    
    <groupId>org.tanzu</groupId>
    <artifactId>goose-agent-chat</artifactId>
    <version>1.0.0</version>
    <name>goose-agent-chat</name>
    <description>Web chat interface for Goose AI agent</description>

    <properties>
        <java.version>21</java.version>
        <node.version>v22.12.0</node.version>
    </properties>

    <repositories>
        <repository>
            <id>gcp-maven-public</id>
            <name>GCP Artifact Registry - Public Maven Repository</name>
            <url>https://us-central1-maven.pkg.dev/cf-mcp/maven-public</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        
        <!-- Goose CF Wrapper - to be published -->
        <dependency>
            <groupId>org.tanzu.goose</groupId>
            <artifactId>goose-cf-wrapper</artifactId>
            <version>1.0.0</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>

            <!-- Frontend Maven Plugin for Angular build -->
            <plugin>
                <groupId>com.github.eirslett</groupId>
                <artifactId>frontend-maven-plugin</artifactId>
                <version>1.15.1</version>
                <configuration>
                    <workingDirectory>src/main/frontend</workingDirectory>
                    <installDirectory>target</installDirectory>
                </configuration>
                <executions>
                    <execution>
                        <id>install node and npm</id>
                        <goals>
                            <goal>install-node-and-npm</goal>
                        </goals>
                        <configuration>
                            <nodeVersion>${node.version}</nodeVersion>
                        </configuration>
                    </execution>
                    <execution>
                        <id>npm install</id>
                        <goals>
                            <goal>npm</goal>
                        </goals>
                        <configuration>
                            <arguments>ci</arguments>
                        </configuration>
                    </execution>
                    <execution>
                        <id>npm run build</id>
                        <goals>
                            <goal>npm</goal>
                        </goals>
                        <configuration>
                            <arguments>run build</arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- Copy Angular build to Spring Boot static resources -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-resources-plugin</artifactId>
                <executions>
                    <execution>
                        <id>copy-frontend-build</id>
                        <phase>process-resources</phase>
                        <goals>
                            <goal>copy-resources</goal>
                        </goals>
                        <configuration>
                            <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
                            <resources>
                                <resource>
                                    <directory>src/main/frontend/dist/frontend/browser</directory>
                                    <filtering>false</filtering>
                                </resource>
                            </resources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## Differences from Claude Agent Chat

| Aspect | Claude Agent Chat | Goose Agent Chat |
|--------|------------------|------------------|
| **CLI** | Claude Code (Node.js) | Goose (Rust binary) |
| **Buildpack** | claude-code-buildpack | goose-buildpack |
| **Wrapper** | claude-code-cf-wrapper | goose-cf-wrapper |
| **Providers** | Anthropic only | Multi-provider (Anthropic, OpenAI, Google, etc.) |
| **Config file** | `.claude-code-config.yml` | `.goose-config.yml` |
| **Session flag** | `--resume` | `--name` + `--resume` |
| **Health endpoint** | Version only | Version + provider + model |

---

## Implementation Roadmap

### Phase 1: Buildpack (Week 1-2)
- [ ] Create goose-buildpack repository
- [ ] Implement detection and supply scripts
- [ ] Test binary installation on CF

### Phase 2: Java Wrapper (Week 2-3)
- [ ] Create goose-cf-wrapper library
- [ ] Implement GooseExecutor interface
- [ ] Session management
- [ ] Publish to Maven repository

### Phase 3: Web Application (Week 3-5)
- [ ] Create goose-agent-chat repository
- [ ] Port Angular components from claude-agent-chat
- [ ] Implement Spring Boot controllers
- [ ] Integration testing

### Phase 4: Documentation & Polish (Week 5-6)
- [ ] README and quickstart guide
- [ ] Provider configuration examples
- [ ] MCP server integration guide
- [ ] Performance tuning

---

## Open Questions

1. **Goose session persistence** - Does `goose session --name X --resume` work as expected for multi-turn conversations?
2. **Provider switching** - Can provider be changed per-session or is it global?
3. **Extension configuration** - How are Goose extensions enabled/disabled at runtime?
4. **Output format** - Does Goose have a JSON output mode for easier parsing?
