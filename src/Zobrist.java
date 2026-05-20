import java.util.SplittableRandom;

/**
 * Zobrist hashing keys for Breakthrough.
 *
 * Layout:
 *   PIECE_SQ[piece][sq] for piece in {WHITE, BLACK} and sq in 0..63 (row*8 + col)
 *   SIDE_BLACK is XOR'd into the hash when it is Black to move.
 *
 * The seed is fixed so hashes are reproducible between runs (handy for tests).
 */
public final class Zobrist {

    public static final long[][] PIECE_SQ = new long[3][64]; // index 0 (EMPTY) unused
    public static final long    SIDE_BLACK;

    static {
        // Fixed seed: reproducible across runs.
        SplittableRandom r = new SplittableRandom(0xB12A4711D02CAFEBL);
        for (int p = 1; p <= 2; p++) {
            for (int sq = 0; sq < 64; sq++) {
                PIECE_SQ[p][sq] = r.nextLong();
            }
        }
        SIDE_BLACK = r.nextLong();
    }

    private Zobrist() {}

    /** Compute the full Zobrist hash of a board from scratch. */
    public static long compute(Board b) {
        long h = 0L;
        long bb;
        bb = b.whiteBits();
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            h ^= PIECE_SQ[Board.WHITE][sq];
            bb &= bb - 1L;
        }
        bb = b.blackBits();
        while (bb != 0L) {
            int sq = Long.numberOfTrailingZeros(bb);
            h ^= PIECE_SQ[Board.BLACK][sq];
            bb &= bb - 1L;
        }
        if (b.side() == Board.BLACK) h ^= SIDE_BLACK;
        return h;
    }
}
