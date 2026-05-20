/**
 * A move in algebraic notation: from-square to to-square, e.g. "b2-c3".
 * Whether the move is a capture is determined by the board state at apply time.
 */
public record Move(int fromRow, int fromCol, int toRow, int toCol) {

    public static Move parse(String s) {
        s = s.trim();
        if (s.length() != 4) {
            throw new IllegalArgumentException("Invalid move syntax: '" + s + "' (expected like b2c3)");
        }
        int fc = s.charAt(0) - 'a';
        int fr = s.charAt(1) - '1';
        int tc = s.charAt(2) - 'a';
        int tr = s.charAt(3) - '1';
        if (fc < 0 || fc > 7 || fr < 0 || fr > 7 ||
            tc < 0 || tc > 7 || tr < 0 || tr > 7) {
            throw new IllegalArgumentException("Square out of range: '" + s + "'");
        }
        return new Move(fr, fc, tr, tc);
    }

    public String toAlgebraic() {
        return "" + (char)('a' + fromCol) + (char)('1' + fromRow)
                  + (char)('a' + toCol)   + (char)('1' + toRow);
    }

    @Override public String toString() { return toAlgebraic(); }

    /* ----- Packed-int representation -----
     *
     * For internal use by Search/MoveGenerator, a Move is an int of the form:
     *
     *     (from_square << 6) | to_square
     *
     * where from/to are square indices 0..63 (row*8 + col). 12 bits used.
     *
     * Conversion to/from the public Move record is cheap; the int form lets
     * MoveGenerator fill pre-allocated buffers with zero allocation per move,
     * which is the hot path.
     */

    public static final int NONE = -1;

    public static int pack(int fromSq, int toSq)  { return (fromSq << 6) | toSq; }
    public static int packRC(int fromRow, int fromCol, int toRow, int toCol) {
        return ((fromRow << 3 | fromCol) << 6) | (toRow << 3 | toCol);
    }
    public static int fromSq(int packed) { return packed >>> 6; }
    public static int toSq(int packed)   { return packed & 0x3F; }

    /** Convert this Move record to its packed-int form. */
    public int packed() { return packRC(fromRow, fromCol, toRow, toCol); }

    /** Build a Move record from a packed int. */
    public static Move unpack(int packed) {
        int fSq = packed >>> 6;
        int tSq = packed & 0x3F;
        return new Move(fSq >>> 3, fSq & 7, tSq >>> 3, tSq & 7);
    }

    /** Algebraic notation directly from a packed int, no allocation of a Move. */
    public static String toAlgebraic(int packed) {
        int fSq = packed >>> 6;
        int tSq = packed & 0x3F;
        int fr = fSq >>> 3, fc = fSq & 7, tr = tSq >>> 3, tc = tSq & 7;
        return "" + (char)('a' + fc) + (char)('1' + fr)
                  + (char)('a' + tc) + (char)('1' + tr);
    }
}
