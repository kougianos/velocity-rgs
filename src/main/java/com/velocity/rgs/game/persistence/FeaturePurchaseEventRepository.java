package com.velocity.rgs.game.persistence;

import com.velocity.rgs.game.domain.FeaturePurchaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeaturePurchaseEventRepository extends JpaRepository<FeaturePurchaseEvent, Long> {

    List<FeaturePurchaseEvent> findByPlayerIdOrderByCreatedAtDesc(String playerId);

    List<FeaturePurchaseEvent> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
