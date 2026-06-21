package com.velocity.rgs.wallet.persistence;

import com.velocity.rgs.wallet.domain.WalletBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletBalanceRepository extends JpaRepository<WalletBalance, String> {
}
