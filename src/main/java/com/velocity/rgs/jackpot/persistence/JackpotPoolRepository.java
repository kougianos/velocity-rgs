package com.velocity.rgs.jackpot.persistence;

import com.velocity.rgs.jackpot.domain.JackpotPool;
import com.velocity.rgs.jackpot.domain.JackpotPoolId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JackpotPoolRepository extends JpaRepository<JackpotPool, JackpotPoolId> {

    /** Every tier in one currency, which is the only read the lobby ever does. */
    List<JackpotPool> findByIdCurrency(String currency);
}
