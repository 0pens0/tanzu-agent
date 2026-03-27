package org.tanzu.goosechat.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    @Query("""
        SELECT m FROM Message m
        WHERE m.conversation.id = :conversationId
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """)
    List<Message> findLatestByConversationId(
        @Param("conversationId") String conversationId,
        @Param("limit") int limit
    );

    @Query("""
        SELECT m FROM Message m
        WHERE m.conversation.userId = :userId
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """)
    List<Message> findLatestByUserId(
        @Param("userId") String userId,
        @Param("limit") int limit
    );
}
