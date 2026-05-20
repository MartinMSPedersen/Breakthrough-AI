import java.io.PrintStream;

/**
 * 8x8 Breakthrough board, backed by two 64-bit bitboards (one per side).
 *
 * Coordinate convention is unchanged from the previous array-backed version:
 *   row 0 = rank 1 (White's home), row 7 = rank 8 (Black's home).
 *   col 0 = 'a', col 7 = 'h'.
 *   White moves toward higher rows (+1) and wins by reaching row 7.
 *   Black moves toward lower rows (-1) and wins by reaching row 0.
 *
 * Square index: sq = row * 8 + col. The bit at position `sq` in the `white`
 * bitboard is set iff a White piece sits on that square; same for `black`.
 * A square is empty iff its bit is clear in both.
 *
 * Mutable; apply()/undo() are used by the search and keep the Zobrist hash
 * up to date incrementally.
 */
public class Board {

    public static final int  SIZE  = 8;
    public static final byte EMPTY = 0;
    public static final byte WHITE = 1;
    public static final byte BLACK = 2;

    private long white;     // bitboard of White pieces
    private long black;     // bitboard of Black pieces
    private byte side = WHITE;
    private long hash;

    /** Zobrist hash of the current position. */
    public long hash()           { return hash; }

    /** Bitboards (for fast access from MoveGenerator and Evaluator). */
    public long whiteBits()      { return white; }
    public long blackBits()      { return black; }

    public static Board initial() {
        Board b = new Board();
        // Rows 0 and 1 are White; rows 6 and 7 are Black.
        b.white = 0x000000000000FFFFL;  // bits 0..15
        b.black = 0xFFFF000000000000L;  // bits 48..63
        b.side  = WHITE;
        b.hash  = Zobrist.compute(b);
        return b;
    }

    public byte get(int r, int c) {
        long m = Bitboards.bit(r, c);
        if ((white & m) != 0) return WHITE;
        if ((black & m) != 0) return BLACK;
        return EMPTY;
    }

    public byte side()               { return side; }
    public void setSide(byte s)      {
        if (side != s) hash ^= Zobrist.SIDE_BLACK;
        side = s;
    }
    public static byte other(byte s) { return s == WHITE ? BLACK : WHITE; }

    /**
     * Apply a move in place. Returns the captured piece (or EMPTY) so undo()
     * can restore. Updates the Zobrist hash incrementally.
     */
    public byte apply(Move m) {
        return applyPacked(m.packed());
    }

    /** Reverse a previous apply(). */
    public void undo(Move m, byte captured) {
        undoPacked(m.packed(), captured);
    }

    /**
     * Packed-int variants of apply/undo, used by Search's hot loop to avoid
     * boxing into Move records.
     */
    public byte applyPacked(int move) {
        int  fromSq  = move >>> 6;
        int  toSq    = move & 0x3F;
        long fromBit = 1L << fromSq;
        long toBit   = 1L << toSq;
        long bothBit = fromBit | toBit;

        byte captured;
        if ((white & fromBit) != 0) {
            white ^= bothBit;
            if ((black & toBit) != 0) {
                captured = BLACK;
                black ^= toBit;
                hash ^= Zobrist.PIECE_SQ[BLACK][toSq];
            } else {
                captured = EMPTY;
            }
            hash ^= Zobrist.PIECE_SQ[WHITE][fromSq];
            hash ^= Zobrist.PIECE_SQ[WHITE][toSq];
        } else {
            black ^= bothBit;
            if ((white & toBit) != 0) {
                captured = WHITE;
                white ^= toBit;
                hash ^= Zobrist.PIECE_SQ[WHITE][toSq];
            } else {
                captured = EMPTY;
            }
            hash ^= Zobrist.PIECE_SQ[BLACK][fromSq];
            hash ^= Zobrist.PIECE_SQ[BLACK][toSq];
        }
        hash ^= Zobrist.SIDE_BLACK;
        side = other(side);
        return captured;
    }

    public void undoPacked(int move, byte captured) {
        int  fromSq  = move >>> 6;
        int  toSq    = move & 0x3F;
        long fromBit = 1L << fromSq;
        long toBit   = 1L << toSq;
        long bothBit = fromBit | toBit;

        if ((white & toBit) != 0) {
            white ^= bothBit;
            hash ^= Zobrist.PIECE_SQ[WHITE][toSq];
            hash ^= Zobrist.PIECE_SQ[WHITE][fromSq];
            if (captured != EMPTY) {
                black ^= toBit;
                hash ^= Zobrist.PIECE_SQ[BLACK][toSq];
            }
        } else {
            black ^= bothBit;
            hash ^= Zobrist.PIECE_SQ[BLACK][toSq];
            hash ^= Zobrist.PIECE_SQ[BLACK][fromSq];
            if (captured != EMPTY) {
                white ^= toBit;
                hash ^= Zobrist.PIECE_SQ[WHITE][toSq];
            }
        }
        hash ^= Zobrist.SIDE_BLACK;
        side = other(side);
    }

    /**
     * Returns WHITE or BLACK if that side has won, else EMPTY.
     * Win conditions:
     *   - any White piece on row 7 (the Black home row)
     *   - any Black piece on row 0 (the White home row)
     *   - the opposing side has zero pieces
     */
    public byte winner() {
        if ((white & Bitboards.RANK_8) != 0) return WHITE;
        if ((black & Bitboards.RANK_1) != 0) return BLACK;
        if (white == 0L) return BLACK;
        if (black == 0L) return WHITE;
        return EMPTY;
    }

    public void print(PrintStream out) {
        out.println();
        out.println("     a b c d e f g h");
        out.println("   +-----------------+");
        for (int r = SIZE - 1; r >= 0; r--) {
            out.print(" " + (r + 1) + " | ");
            for (int c = 0; c < SIZE; c++) {
                long m = Bitboards.bit(r, c);
                char ch = '.';
                if      ((white & m) != 0) ch = 'X';
                else if ((black & m) != 0) ch = 'O';
                out.print(ch + " ");
            }
            out.println("| " + (r + 1));
        }
        out.println("   +-----------------+");
        out.println("     a b c d e f g h");
        out.println("Side to move: " + (side == WHITE ? "White (X)" : "Black (O)"));
        out.println();
    }

    /** FEN-like: ranks listed from 8 down to 1, '/'-separated, X=white, O=black, digits=empties. */
    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int r = SIZE - 1; r >= 0; r--) {
            int empties = 0;
            for (int c = 0; c < SIZE; c++) {
                long m = Bitboards.bit(r, c);
                if      ((white & m) != 0) {
                    if (empties > 0) { sb.append(empties); empties = 0; }
                    sb.append('X');
                } else if ((black & m) != 0) {
                    if (empties > 0) { sb.append(empties); empties = 0; }
                    sb.append('O');
                } else {
                    empties++;
                }
            }
            if (empties > 0) sb.append(empties);
            if (r > 0) sb.append('/');
        }
        sb.append(' ').append(side == WHITE ? 'W' : 'B');
        return sb.toString();
    }

    public static Board fromFen(String fen) {
        Board b = new Board();
        String[] parts = fen.trim().split("\\s+");
        String[] ranks = parts[0].split("/");
        if (ranks.length != SIZE)
            throw new IllegalArgumentException("Bad FEN (need " + SIZE + " ranks): " + fen);
        for (int i = 0; i < SIZE; i++) {
            int r = SIZE - 1 - i;
            int c = 0;
            for (char ch : ranks[i].toCharArray()) {
                if (Character.isDigit(ch)) {
                    c += ch - '0';
                } else if (ch == 'X') {
                    b.white |= Bitboards.bit(r, c++);
                } else if (ch == 'O') {
                    b.black |= Bitboards.bit(r, c++);
                } else {
                    throw new IllegalArgumentException("Bad FEN char '" + ch + "' in: " + fen);
                }
            }
            if (c != SIZE)
                throw new IllegalArgumentException("Rank " + (8 - i) + " has " + c + " cols (need 8): " + fen);
        }
        b.side = (parts.length > 1 && parts[1].equalsIgnoreCase("B")) ? BLACK : WHITE;
        b.hash = Zobrist.compute(b);
        return b;
    }
}
