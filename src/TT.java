/**
 * Transposition table indexed by Zobrist hash.
 *
 * Fixed-size, power-of-two, single-slot ("always replace"). Hash collisions on
 * the index are tolerated: the full 64-bit key is stored and checked, so a
 * collision just looks like a miss. Replacement is unconditional, which is
 * simple and works well enough for a clean reference implementation; a
 * depth-preferred or two-tier scheme would be the next refinement.
 *
 * Entry semantics:
 *   EXACT  - score is the exact minimax value of this node
 *   LOWER  - score is a lower bound (a beta cutoff occurred; true value >= score)
 *   UPPER  - score is an upper bound (no move improved alpha; true value <= score)
 */
public final class TT {

    public static final byte EXACT = 0;
    public static final byte LOWER = 1;
    public static final byte UPPER = 2;

    public static final class Entry {
        public long  key;
        public int   bestMove;   // packed Move (see Move.pack); Move.NONE if no move stored
        public int   score;
        public short depth;
        public byte  flag;
    }

    private final Entry[] table;
    private final int     mask;
    private int  filled;
    private long probes;
    private long hits;

    /** @param sizeBits log2 of the number of slots. 20 => 2^20 ≈ 1M slots. */
    public TT(int sizeBits) {
        int size = 1 << sizeBits;
        this.table = new Entry[size];
        this.mask  = size - 1;
    }

    /** Returns the matching entry, or null if there is no hit for this key. */
    public Entry probe(long hash) {
        probes++;
        Entry e = table[(int)(hash & mask)];
        if (e != null && e.key == hash) {
            hits++;
            return e;
        }
        return null;
    }

    public void store(long hash, int depth, int score, byte flag, int bestMove) {
        int idx = (int)(hash & mask);
        Entry e = table[idx];
        if (e == null) { e = new Entry(); table[idx] = e; filled++; }
        e.key      = hash;
        e.depth    = (short) depth;
        e.score    = score;
        e.flag     = flag;
        e.bestMove = bestMove;
    }

    public void clear() {
        for (int i = 0; i < table.length; i++) table[i] = null;
        filled = 0; probes = 0; hits = 0;
    }

    public int  size()   { return table.length; }
    public int  filled() { return filled; }
    public long probes() { return probes; }
    public long hits()   { return hits; }

    public String statsLine() {
        double hitPct  = probes == 0 ? 0.0 : (100.0 * hits) / probes;
        double fillPct = (100.0 * filled) / table.length;
        return String.format("TT: %d/%d slots used (%.1f%%), %d probes, %d hits (%.1f%%)",
                             filled, table.length, fillPct, probes, hits, hitPct);
    }
}
