package org.tanzu.goosechat.memory;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * A named key-value fact associated with a user.
 * Used by the memory skill for agent-driven long-term fact storage.
 */
@Entity
@Table(name = "facts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "factKey"}),
    indexes = @Index(name = "idx_fact_user_id", columnList = "userId")
)
public class Fact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 256)
    private String userId;

    @Column(name = "factKey", nullable = false, length = 128)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Fact() {}

    public Fact(String userId, String key, String value) {
        this.userId = userId;
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}
