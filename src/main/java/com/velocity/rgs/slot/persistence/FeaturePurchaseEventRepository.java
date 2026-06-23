package com.velocity.rgs.slot.persistence;

import com.velocity.rgs.slot.domain.FeaturePurchaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeaturePurchaseEventRepository extends JpaRepository<FeaturePurchaseEvent, Long> {

    List<FeaturePurchaseEvent> findByPlayerIdOrderByCreatedAtDesc(String playerId);

    List<FeaturePurchaseEvent> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
