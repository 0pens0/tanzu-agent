package org.tanzu.goosechat.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FactRepository extends JpaRepository<Fact, Long> {

    List<Fact> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<Fact> findByUserIdAndKey(String userId, String key);
}
