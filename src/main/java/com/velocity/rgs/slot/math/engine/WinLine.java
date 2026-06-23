package com.velocity.rgs.slot.math.engine;

import java.math.BigDecimal;

/**
 * Single winning line per A.7. Field names match the wire format exactly.
 */
public record WinLine(int lineId, int symbolId, int count, BigDecimal payout) {
}
