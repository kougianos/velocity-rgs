package com.velocity.rgs.slot.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocity.rgs.common.error.ErrorCode;
import com.velocity.rgs.common.error.RgsException;
import com.velocity.rgs.slot.math.engine.CascadeStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the {@code game_round.matrix} / {@code stop_positions} JSONB columns, which hold the
 * round's <em>whole</em> drop sequence rather than a single grid.
 *
 * <h2>Two shapes, one column</h2>
 *
 * A round that settles in one drop writes the flat shape it always has - {@code [[1,2],[3,4]]} for the
 * matrix, {@code [7,11]} for the stops. A round that tumbles writes one entry per drop:
 * {@code [[[1,2],[3,4]], [[5,6],[7,8]]]} and {@code [[7,11],[3]]}.
 *
 * <p>Keeping the flat shape for single-drop rounds is deliberate. Every round written before cascades
 * existed is in that shape, as is every round the four non-cascading games will ever write, and those
 * rows have to keep replaying without a backfill. Reads therefore sniff the nesting depth rather than
 * trusting a version flag, and both shapes decode to the same {@code List}: callers see a sequence and
 * never branch.
 */
@Component
@RequiredArgsConstructor
public class RoundGridCodec {

    private final ObjectMapper objectMapper;

    /** Serialises the sequence of grids: flat {@code int[][]} for one drop, {@code int[][][]} beyond. */
    public String writeMatrices(List<CascadeStep> steps) {
        if (steps.size() == 1) {
            return write(steps.get(0).grid());
        }
        int[][][] sequence = new int[steps.size()][][];
        for (int i = 0; i < steps.size(); i++) {
            sequence[i] = steps.get(i).grid();
        }
        return write(sequence);
    }

    /** Serialises the sequence of draws: flat {@code int[]} for one drop, {@code int[][]} beyond. */
    public String writeStopPositions(List<CascadeStep> steps) {
        if (steps.size() == 1) {
            return write(steps.get(0).stopPositions());
        }
        int[][] sequence = new int[steps.size()][];
        for (int i = 0; i < steps.size(); i++) {
            sequence[i] = steps.get(i).stopPositions();
        }
        return write(sequence);
    }

    /**
     * Reads the grid sequence, accepting either shape. A flat {@code int[][]} decodes to a
     * single-element list, which is exactly what a one-drop round means.
     */
    public List<int[][]> readMatrices(String json) {
        JsonNode root = parse(json, "matrix");
        if (!root.isArray() || root.isEmpty()) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR, "matrix is not a non-empty JSON array");
        }
        if (depth(root) >= 3) {
            List<int[][]> out = new ArrayList<>(root.size());
            for (JsonNode grid : root) {
                out.add(convert(grid, int[][].class, "matrix"));
            }
            return out;
        }
        // Explicit witness: an int[][] is an Object[], so bare List.of would spread its rows into
        // separate elements instead of holding the one grid.
        return List.<int[][]>of(convert(root, int[][].class, "matrix"));
    }

    /** Reads the draw sequence, accepting either shape. Mirrors {@link #readMatrices}. */
    public List<int[]> readStopPositions(String json) {
        JsonNode root = parse(json, "stop_positions");
        if (!root.isArray()) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR, "stop_positions is not a JSON array");
        }
        if (depth(root) >= 2) {
            List<int[]> out = new ArrayList<>(root.size());
            for (JsonNode stops : root) {
                out.add(convert(stops, int[].class, "stop_positions"));
            }
            return out;
        }
        return List.<int[]>of(convert(root, int[].class, "stop_positions"));
    }

    /**
     * Nesting depth of an array node, following its first element. Sufficient here because both columns
     * are strictly rectangular per level: a matrix is always {@code int[][]} or a list of them, never a
     * ragged mix, so the first element settles the shape.
     */
    private static int depth(JsonNode node) {
        int depth = 0;
        JsonNode cursor = node;
        while (cursor != null && cursor.isArray() && !cursor.isEmpty()) {
            depth++;
            cursor = cursor.get(0);
        }
        return depth;
    }

    private JsonNode parse(String json, String column) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize " + column + ": " + ex.getMessage(), ex);
        }
    }

    private <T> T convert(JsonNode node, Class<T> type, String column) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot deserialize " + column + ": " + ex.getMessage(), ex);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RgsException(ErrorCode.INTERNAL_ERROR,
                    "Cannot serialize round grid: " + ex.getMessage(), ex);
        }
    }
}
