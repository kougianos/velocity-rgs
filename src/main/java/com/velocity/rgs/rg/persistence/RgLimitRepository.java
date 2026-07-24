package com.velocity.rgs.rg.persistence;

import com.velocity.rgs.rg.domain.RgLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RgLimitRepository extends JpaRepository<RgLimit, String> {
}
