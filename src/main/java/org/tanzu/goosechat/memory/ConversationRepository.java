package org.tanzu.goosechat.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findByUserIdOrderByUpdatedAtDesc(String userId);

    @Query("""
        SELECT c FROM Conversation c
        WHERE c.userId = :userId
        ORDER BY c.updatedAt DESC
        LIMIT :limit
        """)
    List<Conversation> findRecentByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /** Delete all conversations that were created but never used (no messages saved). */
    int deleteByTitleIsNull();
}
