package com.velocity.rgs.audit.pickaudit;

import com.velocity.rgs.game.feature.pickcollect.PickCollectState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic SHA-256 fingerprint of a {@link PickCollectState} snapshot. Independent of map
 * iteration order — fields are emitted in a fixed canonical sequence so the same logical state always
 * yields the same hash.
 */
public final class PickCollectStateHasher {

    private PickCollectStateHasher() {}

    public static String hash(PickCollectState state) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("boardSize=").append(state.boardSize()).append(';');
        sb.append("opened=").append(state.openedPositions()).append(';');
        sb.append("currentCollected=").append(state.currentCollected()).append(';');
        sb.append("totalFeatureWin=").append(state.totalFeatureWin()).append(';');
        sb.append("remainingPicks=").append(state.remainingPicks()).append(';');
        sb.append("status=").append(state.status().name()).append(';');
        sb.append("tiles=");
        for (int i = 0; i < state.tiles().size(); i++) {
            var tile = state.tiles().get(i);
            sb.append(i).append(':').append(tile.type().name()).append('/').append(tile.value()).append(',');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
