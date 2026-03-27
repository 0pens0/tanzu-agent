package org.tanzu.goosechat.memory;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A single turn in a conversation — either a user prompt or the assistant's reply.
 */
@Entity
@Table(name = "messages", indexes = {
    @Index(name = "idx_message_conversation_id", columnList = "conversation_id"),
    @Index(name = "idx_message_created_at", columnList = "createdAt")
})
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(nullable = false, length = 16)
    private String role; // "user" or "assistant"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    protected Message() {}

    public Message(Conversation conversation, String role, String content) {
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Conversation getConversation() { return conversation; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
