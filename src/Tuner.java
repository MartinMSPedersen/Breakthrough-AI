import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Long-running tuner that optimises the evaluator's advancement weights using
 * SPSA (Simultaneous Perturbation Stochastic Approximation).
 *
 * SPSA in one paragraph: at each iteration, generate a random ±1 perturbation
 * vector Δ (one entry per weight). Evaluate w + cΔ in a self-play mini-match
 * against w - cΔ. The resulting (winRate - 0.5) is a noisy estimate of the
 * gradient along the Δ direction. Step the weights along this direction by a
 * learning-rate-controlled amount. Each iteration costs exactly two
 * mini-matches' worth of self-play games, regardless of how many parameters
 * you're tuning. For 8 dimensions this converges much faster than testing
 * coordinates one at a time.
 *
 * To prevent drift, every N iterations we play a "gauntlet" of games between
 * the current candidate and a fixed anchor (the original default weights).
 * That win rate is the headline metric — the only thing that tells you whether
 * the tuner is genuinely making the engine stronger.
 *
 * State is persisted to disk after every iteration so the run survives crashes
 * and can be resumed by passing --resume. Per-iteration data goes to a CSV
 * for later plotting.
 *
 * Usage:
 *   java -cp build Tuner [--state-dir tuner-state] [--depth 4]
 *                        [--games-spsa 12] [--games-gauntlet 30]
 *                        [--gauntlet-every 20]
 *                        [--learning-rate 8] [--perturb-size 4]
 *                        [--noise 4] [--seed N]
 *                        [--anchor 5,10,16,26,42,70,120,1000]
 *                        [--max-iterations N]
 *                        [--resume]
 *
 * Stop with Ctrl-C; state is flushed after every iteration.
 */
public final class Tuner {

    /* ---------- defaults ---------- */
    static final int    DEFAULT_DEPTH           = 4;
    static final int    DEFAULT_GAMES_SPSA      = 12;   // per side of SPSA pair
    static final int    DEFAULT_GAMES_GAUNTLET  = 30;
    static final int    DEFAULT_GAUNTLET_EVERY  = 20;
    static final double DEFAULT_LEARNING_RATE   = 8.0;  // step size in weight units
    static final int    DEFAULT_PERTURB_SIZE    = 4;    // c in SPSA: magnitude of ±Δ
    static final int    DEFAULT_NOISE_AMP       = 4;
    static final int    DEFAULT_TT_BITS         = 18;   // smaller TT during tuning saves RAM
    static final int[]  DEFAULT_ANCHOR          = { 25, 22, 23, 27, 41, 58, 127, 1000 };
    static final double DEFAULT_ANCHOR_DSCALE   = 0.0;
    /** The last weight (terminal row) is fixed; mutating it has no effect since
     *  reaching the home row is already a terminal win. So we tune indices 0..6. */
    static final int    TUNED_DIMS              = 7;
    /** Clamp tuned weights to this range to avoid runaway. */
    static final int    MIN_WEIGHT              = 1;
    static final int    MAX_WEIGHT              = 5000;
    /** Clamp defender scale to a reasonable range. */
    static final double MIN_DSCALE              = 0.0;
    static final double MAX_DSCALE              = 2.0;
    /** Perturbation size for defender scale (much smaller than weight perturb,
     *  since dscale is on a much smaller numeric scale). */
    static final double DSCALE_PERTURB          = 0.02;
    /** Learning rate for defender scale (analogously scaled down). */
    static final double DSCALE_LR               = 0.05;

    /* ---------- state, persisted across runs ---------- */
    static final class State {
        double[] w        = new double[Board.SIZE];   // current weights (real-valued internally)
        double   dscale   = 0.0;                       // current defender scale
        int      iter     = 0;
        long     baseSeed = 0L;                       // base RNG seed; iteration mixes in for variety
        double   bestGauntletWinRate = Double.NaN;    // best gauntlet win rate seen
        int[]    bestGauntletWeights = null;          // weights at that best
        double   bestGauntletDscale  = 0.0;           // defender scale at that best
    }

    /* ---------- entry point ---------- */
    public static void main(String[] args) throws IOException {
        String stateDir       = strArg(args, "--state-dir",       "tuner-state");
        int    depth          = intArg(args, "--depth",           DEFAULT_DEPTH);
        int    gamesSpsa      = intArg(args, "--games-spsa",      DEFAULT_GAMES_SPSA);
        int    gamesGauntlet  = intArg(args, "--games-gauntlet",  DEFAULT_GAMES_GAUNTLET);
        int    gauntletEvery  = intArg(args, "--gauntlet-every",  DEFAULT_GAUNTLET_EVERY);
        double lr             = doubleArg(args, "--learning-rate", DEFAULT_LEARNING_RATE);
        int    perturbSize    = intArg(args, "--perturb-size",    DEFAULT_PERTURB_SIZE);
        int    noiseAmp       = intArg(args, "--noise",           DEFAULT_NOISE_AMP);
        int    maxIters       = intArg(args, "--max-iterations",  Integer.MAX_VALUE);
        long   seed           = longArg(args, "--seed",           System.nanoTime());
        String anchorSpec     = strArg(args, "--anchor",          intArrToSpec(DEFAULT_ANCHOR));
        double anchorDscale   = doubleArg(args, "--anchor-dscale", DEFAULT_ANCHOR_DSCALE);
        double initDscale     = doubleArg(args, "--init-dscale",   anchorDscale);
        boolean noDscale      = hasFlag(args, "--no-tune-dscale");
        boolean resume        = hasFlag(args, "--resume");

        Path dir = Paths.get(stateDir);
        Files.createDirectories(dir);
        Path stateFile = dir.resolve("state.txt");
        Path csvFile   = dir.resolve("history.csv");
        Path logFile   = dir.resolve("log.txt");

        int[] anchor = specToIntArr(anchorSpec);

        State st;
        if (resume && Files.exists(stateFile)) {
            st = loadState(stateFile);
            log(logFile, "Resumed from iteration " + st.iter);
        } else {
            st = new State();
            st.baseSeed = seed;
            for (int i = 0; i < Board.SIZE; i++) st.w[i] = anchor[i];
            st.dscale = initDscale;
            saveState(stateFile, st);
            if (!Files.exists(csvFile)) {
                Files.writeString(csvFile,
                    "iter,timestamp,w0,w1,w2,w3,w4,w5,w6,w7,dscale,"
                  + "spsa_plus_wins,spsa_minus_wins,gauntlet_played,gauntlet_wins,gauntlet_winrate\n");
            }
            log(logFile, "=== Tuner started ===");
            log(logFile, "  state dir         = " + dir.toAbsolutePath());
            log(logFile, "  depth             = " + depth);
            log(logFile, "  games (spsa pair) = " + gamesSpsa + " per side");
            log(logFile, "  games (gauntlet)  = " + gamesGauntlet);
            log(logFile, "  gauntlet-every    = " + gauntletEvery);
            log(logFile, "  learning rate     = " + lr);
            log(logFile, "  perturb size      = " + perturbSize);
            log(logFile, "  noise             = ±" + noiseAmp);
            log(logFile, "  seed              = " + seed);
            log(logFile, "  anchor weights    = " + intArrToSpec(anchor));
            log(logFile, "  anchor dscale     = " + anchorDscale);
            log(logFile, "  init   dscale     = " + initDscale);
            log(logFile, "  tune  dscale      = " + (!noDscale));
        }

        Random rng = new Random(st.baseSeed);
        // Advance rng to where this iteration would be — keeps determinism across resumes.
        for (int i = 0; i < st.iter; i++) rng.nextLong();

        long t0 = System.currentTimeMillis();
        while (st.iter < maxIters) {
            st.iter++;

            /* ----- SPSA: build random ±1 perturbations for weights and dscale ----- */
            int[] delta = new int[Board.SIZE];
            for (int i = 0; i < TUNED_DIMS; i++) delta[i] = rng.nextBoolean() ? +1 : -1;
            // delta[TUNED_DIMS..] = 0 (terminal weight not tuned)
            int dDscale = noDscale ? 0 : (rng.nextBoolean() ? +1 : -1);

            int[]  wPlus  = projectAndRound(addPerturb(st.w, delta, +perturbSize));
            int[]  wMinus = projectAndRound(addPerturb(st.w, delta, -perturbSize));
            double dsPlus  = clampDscale(st.dscale + DSCALE_PERTURB * dDscale);
            double dsMinus = clampDscale(st.dscale - DSCALE_PERTURB * dDscale);

            /* ----- play the SPSA pair: (w+, ds+) vs (w-, ds-) ----- */
            long matchSeed = st.baseSeed ^ ((long) st.iter * 0xC2B2AE3D27D4EB4FL);
            MatchResult mr = playMatch(wPlus, wMinus, dsPlus, dsMinus, gamesSpsa, depth, noiseAmp, matchSeed);

            // Win rate of the "+" side over the pair, in [0,1]
            double winratePlus = mr.aWins / (double) mr.total;
            // Gradient estimate; we step UPHILL (toward better win rate).
            double gradMag = (2.0 * winratePlus - 1.0); // [-1, +1]
            for (int i = 0; i < TUNED_DIMS; i++) {
                st.w[i] += lr * gradMag * delta[i];
                if (st.w[i] < MIN_WEIGHT) st.w[i] = MIN_WEIGHT;
                if (st.w[i] > MAX_WEIGHT) st.w[i] = MAX_WEIGHT;
            }
            if (!noDscale) {
                st.dscale = clampDscale(st.dscale + DSCALE_LR * gradMag * dDscale);
            }

            /* ----- periodic gauntlet vs the anchor ----- */
            int    gPlayed = 0, gWins = 0;
            double gWinRate = Double.NaN;
            if (st.iter % gauntletEvery == 0) {
                int[] candidate = projectAndRound(st.w);
                long  ganSeed   = st.baseSeed ^ ((long) st.iter * 0xD1B54A32D192ED03L);
                MatchResult gr  = playMatch(candidate, anchor, st.dscale, anchorDscale,
                                            gamesGauntlet, depth, noiseAmp, ganSeed);
                gPlayed = gr.total; gWins = gr.aWins;
                gWinRate = gWins / (double) gPlayed;

                if (Double.isNaN(st.bestGauntletWinRate) || gWinRate > st.bestGauntletWinRate) {
                    st.bestGauntletWinRate = gWinRate;
                    st.bestGauntletWeights = candidate.clone();
                    st.bestGauntletDscale  = st.dscale;
                    log(logFile, String.format("iter %d: new best vs anchor: %.1f%% (%d/%d)  weights=%s  dscale=%.4f",
                                               st.iter, 100*gWinRate, gWins, gPlayed,
                                               intArrToSpec(candidate), st.dscale));
                    Files.writeString(dir.resolve("best.txt"),
                                      intArrToSpec(candidate) + "\n"
                                    + String.format("%.4f%n", st.dscale),
                                      java.nio.charset.StandardCharsets.UTF_8);
                }
            }

            /* ----- log + checkpoint ----- */
            long elapsed = (System.currentTimeMillis() - t0) / 1000;
            int[] rounded = projectAndRound(st.w);
            log(logFile, String.format("iter %d  t=%ds  w=%s  ds=%.4f  spsa+ wins=%d/%d%s",
                                       st.iter, elapsed,
                                       intArrToSpec(rounded), st.dscale,
                                       mr.aWins, mr.total,
                                       gPlayed > 0 ? String.format("  gauntlet=%d/%d (%.1f%%)",
                                                                   gWins, gPlayed, 100*gWinRate) : ""));

            try (BufferedWriter bw = Files.newBufferedWriter(csvFile,
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
                bw.write(String.format("%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%.4f,%d,%d,%d,%d,%s%n",
                    st.iter,
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    rounded[0], rounded[1], rounded[2], rounded[3],
                    rounded[4], rounded[5], rounded[6], rounded[7],
                    st.dscale,
                    mr.aWins, (mr.total - mr.aWins),
                    gPlayed, gWins,
                    Double.isNaN(gWinRate) ? "" : String.format("%.4f", gWinRate)));
            }
            saveState(stateFile, st);
        }
    }

    private static double clampDscale(double v) {
        if (v < MIN_DSCALE) return MIN_DSCALE;
        if (v > MAX_DSCALE) return MAX_DSCALE;
        return v;
    }

    /* ---------- helpers ---------- */

    /** Run a self-play match between two evaluators, alternating colours. */
    private static MatchResult playMatch(int[] wA, int[] wB,
                                         double dsA, double dsB,
                                         int games, int depth, int noiseAmp, long baseSeed) {
        Evaluator evA = new Evaluator(wA, dsA);
        Evaluator evB = new Evaluator(wB, dsB);
        int aWins = 0;
        for (int g = 1; g <= games; g++) {
            boolean aIsWhite = (g % 2 == 1);
            Evaluator evW = aIsWhite ? evA : evB;
            Evaluator evBl = aIsWhite ? evB : evA;
            long seedW = baseSeed ^ ((long) g << 1)       ^ 0xA1B2C3D4E5F60718L;
            long seedB = baseSeed ^ (((long) g << 1) | 1) ^ 0x123456789ABCDEF0L;
            Search sW = new Search(DEFAULT_TT_BITS, evW,  noiseAmp, seedW);
            Search sB = new Search(DEFAULT_TT_BITS, evBl, noiseAmp, seedB);

            Board board = Board.initial();
            byte winner;
            while (true) {
                winner = board.winner();
                if (winner != Board.EMPTY) break;
                List<Move> legal = MoveGenerator.legalMoves(board);
                if (legal.isEmpty()) { winner = Board.other(board.side()); break; }
                Search s = (board.side() == Board.WHITE) ? sW : sB;
                board.apply(s.findBest(board, depth).bestMove);
            }
            boolean aWon = (aIsWhite && winner == Board.WHITE)
                        || (!aIsWhite && winner == Board.BLACK);
            if (aWon) aWins++;
        }
        return new MatchResult(games, aWins);
    }

    private record MatchResult(int total, int aWins) {}

    private static double[] addPerturb(double[] w, int[] delta, int c) {
        double[] out = w.clone();
        for (int i = 0; i < Board.SIZE; i++) out[i] += (double) c * delta[i];
        return out;
    }

    private static int[] projectAndRound(double[] w) {
        int[] out = new int[Board.SIZE];
        for (int i = 0; i < TUNED_DIMS; i++) {
            int v = (int) Math.round(w[i]);
            if (v < MIN_WEIGHT) v = MIN_WEIGHT;
            if (v > MAX_WEIGHT) v = MAX_WEIGHT;
            out[i] = v;
        }
        out[Board.SIZE - 1] = DEFAULT_ANCHOR[Board.SIZE - 1]; // terminal weight fixed
        return out;
    }

    private static String intArrToSpec(int[] w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length; i++) { if (i > 0) sb.append(','); sb.append(w[i]); }
        return sb.toString();
    }

    private static int[] specToIntArr(String s) {
        String[] parts = s.split(",");
        if (parts.length != Board.SIZE) throw new IllegalArgumentException("need " + Board.SIZE + " values: " + s);
        int[] a = new int[Board.SIZE];
        for (int i = 0; i < a.length; i++) a[i] = Integer.parseInt(parts[i].trim());
        return a;
    }

    /* ----- state file: tiny key=value text, easy to inspect by hand ----- */

    private static void saveState(Path f, State s) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("iter=").append(s.iter).append('\n');
        sb.append("baseSeed=").append(s.baseSeed).append('\n');
        sb.append("w=");
        for (int i = 0; i < s.w.length; i++) { if (i > 0) sb.append(','); sb.append(s.w[i]); }
        sb.append('\n');
        sb.append("dscale=").append(s.dscale).append('\n');
        sb.append("bestGauntletWinRate=").append(s.bestGauntletWinRate).append('\n');
        if (s.bestGauntletWeights != null) {
            sb.append("bestGauntletWeights=").append(intArrToSpec(s.bestGauntletWeights)).append('\n');
            sb.append("bestGauntletDscale=").append(s.bestGauntletDscale).append('\n');
        }
        Files.writeString(f, sb.toString());
    }

    private static State loadState(Path f) throws IOException {
        State s = new State();
        for (String line : Files.readAllLines(f)) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1);
            switch (k) {
                case "iter"                  -> s.iter = Integer.parseInt(v);
                case "baseSeed"              -> s.baseSeed = Long.parseLong(v);
                case "w" -> {
                    String[] parts = v.split(",");
                    for (int i = 0; i < parts.length && i < s.w.length; i++) s.w[i] = Double.parseDouble(parts[i]);
                }
                case "dscale"                -> s.dscale = Double.parseDouble(v);
                case "bestGauntletWinRate"   -> s.bestGauntletWinRate = Double.parseDouble(v);
                case "bestGauntletWeights"   -> s.bestGauntletWeights = specToIntArr(v);
                case "bestGauntletDscale"    -> s.bestGauntletDscale = Double.parseDouble(v);
            }
        }
        return s;
    }

    private static void log(Path f, String msg) throws IOException {
        String stamped = "[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "] " + msg;
        System.out.println(stamped);
        Files.writeString(f, stamped + "\n",
                          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /* ----- arg parsing (same conventions as Main) ----- */

    private static int    intArg(String[] a, String k, int def)    {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(k)) return Integer.parseInt(a[i+1]);
        return def;
    }
    private static long   longArg(String[] a, String k, long def)  {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(k)) return Long.parseLong(a[i+1]);
        return def;
    }
    private static double doubleArg(String[] a, String k, double def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(k)) return Double.parseDouble(a[i+1]);
        return def;
    }
    private static String strArg(String[] a, String k, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(k)) return a[i+1];
        return def;
    }
    private static boolean hasFlag(String[] a, String k) {
        for (String s : a) if (s.equals(k)) return true;
        return false;
    }
}
