/**
 * Bitboard constants and helpers for an 8x8 board.
 *
 * Square indexing: sq = row * 8 + col, with row 0 = White's home row,
 * row 7 = Black's home row, col 0 = file 'a', col 7 = file 'h'.
 *
 * Direction conventions (for shifts on a "white on bottom" mental model):
 *   - shift by 8           ⇒ one row forward for White ("north"), back for Black.
 *   - shift by 7  with FILE_A mask ⇒ White's diagonal-left   (north-west).
 *   - shift by 9  with FILE_H mask ⇒ White's diagonal-right  (north-east).
 *   - opposite (right-shift) for Black.
 *
 * The FILE masks are needed because the bitboard is a flat 64-bit integer; a
 * naive shift of, say, 9 would wrap a piece on the h-file up onto the a-file
 * of the next row. The mask removes those source squares first.
 */
public final class Bitboards {

    private Bitboards() {}

    public static final long FILE_A = 0x0101010101010101L;
    public static final long FILE_H = 0x8080808080808080L;
    public static final long RANK_1 = 0x00000000000000FFL;  // White's home (row 0)
    public static final long RANK_8 = 0xFF00000000000000L;  // Black's home (row 7)

    /** Convert (row, col) to a square index 0..63. */
    public static int sq(int row, int col) { return (row << 3) | col; }

    /** Convert (row, col) to a single-bit mask. */
    public static long bit(int row, int col) { return 1L << ((row << 3) | col); }

    /** Single-bit mask for a square index. */
    public static long bitSq(int sq) { return 1L << sq; }
}
