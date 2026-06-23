package com.velocity.rgs.slot.persistence;

import com.velocity.rgs.slot.domain.PickCollectSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PickCollectSnapshotRepository extends JpaRepository<PickCollectSnapshot, Long> {

    Optional<PickCollectSnapshot> findFirstBySessionIdOrderByCreatedAtDesc(String sessionId);
}
