package com.velocity.rgs.wallet.persistence;

import com.velocity.rgs.wallet.domain.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Optional<WalletTransaction> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);

    boolean existsByOriginalTransactionId(String originalTransactionId);
}
