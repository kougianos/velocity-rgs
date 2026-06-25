package com.velocity.rgs.slot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persisted Pick &amp; Collect feature artifact (A.9 / A.11 / Section 5 Implementation Notes).
 * The {@code board} JSONB is the resolved tile set frozen at feature start and is the canonical
 * replay artifact for the feature (no seed replay - see A.11).
 */
@Entity
@Table(name = "pick_collect_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickCollectSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "round_id", length = 64)
    private String roundId;

    @Column(name = "board_seed", length = 64)
    private String boardSeed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "board", nullable = false, columnDefinition = "jsonb")
    private String board;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "opened_positions", nullable = false, columnDefinition = "jsonb")
    private String openedPositions;

    @Column(name = "final_win", precision = 19, scale = 4)
    private BigDecimal finalWin;

    @Column(name = "final_win_minor")
    private Long finalWinMinor;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
