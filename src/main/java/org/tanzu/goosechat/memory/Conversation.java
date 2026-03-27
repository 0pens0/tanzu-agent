package org.tanzu.goosechat.memory;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted record of a single chat session (one Goose CLI invocation chain).
 * Linked to the authenticated user via {@code userId} (SSO subject claim).
 */
@Entity
@Table(name = "conversations", indexes = {
    @Index(name = "idx_conversation_user_id", columnList = "userId"),
    @Index(name = "idx_conversation_created_at", columnList = "createdAt")
})
public class Conversation {

    @Id
    @Column(nullable = false, length = 64)
    private String id; // matches the Goose session ID (e.g. "chat-a1b2c3d4")

    @Column(nullable = false, length = 256)
    private String userId;

    @Column(length = 512)
    private String title; // auto-generated from first user message

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<Message> messages = new ArrayList<>();

    protected Conversation() {}

    public Conversation(String id, String userId) {
        this.id = id;
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<Message> getMessages() { return messages; }

    public void setTitle(String title) { this.title = title; }
    public void touch() { this.updatedAt = Instant.now(); }
}
