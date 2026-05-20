/**
 * Position evaluation, from the perspective of the side to move.
 *
 * Two terms:
 *
 * 1. **Advancement (material × position).** Each piece contributes a per-row
 *    weight to the side that owns it. Weights grow with row distance from
 *    the piece's home, since advanced pieces are more dangerous (closer to
 *    winning, or closer to being captured because they're unsupported).
 *
 * 2. **Defender bonus.** A piece is "defended" if a friendly piece sits one
 *    square diagonally behind it (relative to its own direction of travel),
 *    because that piece could recapture if an enemy captured here. Pieces
 *    with defenders are battering rams; pieces without are exposed. The
 *    bonus per defender is `defenderScale * advancement_weight[row]`, so
 *    defenders on advanced pieces matter more in absolute terms — which
 *    matches Breakthrough strategy.
 *
 *    `defenderScale = 0` disables the term (legacy behavior). A typical
 *    tuned value will be somewhere in [0.1, 0.6].
 *
 * Both terms are computed symmetrically: own pieces add, enemy pieces
 * subtract. The result is from the side-to-move's perspective.
 */
public final class Evaluator {

    public static final int WIN_SCORE = 100_000;
    public static final int MAX_SCORE = WIN_SCORE + 1000;

    /** Default advancement weights, indexed by rows-from-home (0..7).
     *  Found by the SPSA tuner (iter 50 of a 1000-iteration run) and verified
     *  at 76.5% win rate over 200 games at depth 6 against the pre-tuning
     *  anchor {5, 10, 16, 26, 42, 70, 120, 1000}. */
    public static final int[]    DEFAULT_WEIGHTS        = { 25, 22, 23, 27, 41, 58, 127, 1000 };

    /** Default defender-scale. 0 = ignore defenders (matches behavior before
     *  the term was added). Tune this with the new `--defender-scale` arg or
     *  via the Tuner. */
    public static final double   DEFAULT_DEFENDER_SCALE = 0.0;

    private static final Evaluator DEFAULT = new Evaluator(DEFAULT_WEIGHTS, DEFAULT_DEFENDER_SCALE);

    private final int[]  w;
    private final double defenderScale;

    public Evaluator(int[] weights) { this(weights, DEFAULT_DEFENDER_SCALE); }

    /**
     * @param weights        per-row advancement weights, length Board.SIZE.
     * @param defenderScale  multiplier on advancement_weight[row] for each
     *                       defender of a piece at that row. 0 disables.
     */
    public Evaluator(int[] weights, double defenderScale) {
        if (weights == null || weights.length != Board.SIZE) {
            throw new IllegalArgumentException("Need " + Board.SIZE + " advancement weights");
        }
        this.w = weights.clone();
        this.defenderScale = defenderScale;
    }

    /** Parse a comma-separated weights spec, e.g. "25,22,23,27,41,58,127,1000". */
    public static Evaluator parse(String spec) {
        return parse(spec, DEFAULT_DEFENDER_SCALE);
    }

    public static Evaluator parse(String spec, double defenderScale) {
        String[] parts = spec.split("\\s*,\\s*");
        if (parts.length != Board.SIZE) {
            throw new IllegalArgumentException(
                "Need " + Board.SIZE + " comma-separated weights, got " + parts.length + ": " + spec);
        }
        int[] arr = new int[Board.SIZE];
        for (int i = 0; i < Board.SIZE; i++) arr[i] = Integer.parseInt(parts[i].trim());
        return new Evaluator(arr, defenderScale);
    }

    public static Evaluator defaults() { return DEFAULT; }

    public int[]  weights()       { return w.clone(); }
    public double defenderScale() { return defenderScale; }

    public String spec() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(w[i]);
        }
        return sb.toString();
    }

    public int evaluate(Board b) {
        byte winner = b.winner();
        if (winner != Board.EMPTY) {
            return (winner == b.side()) ? WIN_SCORE : -WIN_SCORE;
        }

        final byte stm = b.side();
        int score = 0;

        for (int r = 0; r < Board.SIZE; r++) {
            for (int c = 0; c < Board.SIZE; c++) {
                byte p = b.get(r, c);
                if (p == Board.EMPTY) continue;

                int adv = (p == Board.WHITE) ? r : (Board.SIZE - 1 - r);
                int v   = w[adv];

                if (defenderScale != 0.0) {
                    int defenders = countDefenders(b, r, c, p);
                    if (defenders != 0) {
                        v += (int) Math.round(defenderScale * w[adv] * defenders);
                    }
                }

                if (p == stm) score += v;
                else           score -= v;
            }
        }
        return score;
    }

    /**
     * Count the friendly pieces that could recapture if the piece at (r, c) is
     * captured. A defender sits one square diagonally behind the piece, where
     * "behind" is the opposite of the piece's direction of travel.
     *
     *   - White piece at (r, c): defenders are White pieces at (r-1, c±1).
     *   - Black piece at (r, c): defenders are Black pieces at (r+1, c±1).
     *
     * Pieces on their own home row cannot have defenders (no row behind them).
     */
    private static int countDefenders(Board b, int r, int c, byte piece) {
        int defenderRow = (piece == Board.WHITE) ? r - 1 : r + 1;
        if (defenderRow < 0 || defenderRow >= Board.SIZE) return 0;

        int count = 0;
        if (c - 1 >= 0          && b.get(defenderRow, c - 1) == piece) count++;
        if (c + 1 <  Board.SIZE && b.get(defenderRow, c + 1) == piece) count++;
        return count;
    }

    /** Convenience: evaluate with default weights. */
    public static int evaluateDefault(Board b) { return DEFAULT.evaluate(b); }
}
