package com.velocity.rgs.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyRecord.Pk> {

    default Optional<IdempotencyRecord> findByScopeAndKey(String scope, String key) {
        return findById(new IdempotencyRecord.Pk(scope, key));
    }
}
