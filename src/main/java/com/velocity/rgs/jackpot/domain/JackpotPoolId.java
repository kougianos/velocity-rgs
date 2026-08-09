package com.velocity.rgs.jackpot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** Composite key for {@link JackpotPool}: a pool is a tier in a currency, and neither half identifies it alone. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class JackpotPoolId implements Serializable {

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 16)
    private JackpotTier tier;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
}
