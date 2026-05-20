import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * CLI entry point.
 *
 *   breakthrough play    [--depth N] [--ai-side W|B|both|none]
 *   breakthrough analyse [--file <position.fen>] [--depth N]
 *   breakthrough annotate --file <game.txt>     [--depth N]
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "play"                     -> playCmd(args);
            case "analyse", "analyze"       -> analyseCmd(args);
            case "annotate"                 -> annotateCmd(args);
            case "match"                    -> matchCmd(args);
            case "benchmark", "bench"       -> benchmarkCmd(args);
            case "help", "-h", "--help"     -> usage();
            default                         -> {
                System.err.println("Unknown command: " + args[0]);
                usage();
                System.exit(1);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                Breakthrough analyser (8x8)

                Usage:
                  breakthrough play    [--depth N] [--ai-side W|B|both|none]
                                       [--weights w0,..,w7]
                                       [--weights-w ...] [--weights-b ...]
                                       [--defender-scale F]
                                       [--defender-scale-w F] [--defender-scale-b F]
                  breakthrough analyse [--file <position.fen>] [--depth N]
                  breakthrough annotate --file <game.txt>      [--depth N] [--start-move N]
                  breakthrough match   --weights-a w0,..,w7 --weights-b w0,..,w7
                                       [--depth N] [--games N] [--quiet]
                                       [--noise N] [--seed N]
                                       [--defender-scale F]
                                       [--defender-scale-a F] [--defender-scale-b F]
                  breakthrough benchmark [--games N] [--depth N] [--seed N]
                  breakthrough help

                Moves use algebraic notation, e.g. b2c3.

                Position file (FEN-like, ranks 8..1, X=white, O=black):
                  OOOOOOOO/OOOOOOOO/8/8/8/8/XXXXXXXX/XXXXXXXX W

                Game file: free text with move tokens like b2b3.
                Lines starting with '#' (and anything after '#') are comments.

                Evaluator weights: 8 comma-separated integers, one per row of
                advancement from a piece's home row (row 0 .. row 7). The
                default is 25,22,23,27,41,58,127,1000. In `play` you can give
                --weights for both sides, or --weights-w / --weights-b to set
                them independently.

                Defender scale: a floating-point multiplier. Each piece gets a
                bonus of (defender_scale * advancement_weight[row] * defenders).
                Default 0 disables the term. Reasonable values are 0.1 - 0.6.

                Defaults:
                  play     depth=5    ai-side=B   (you play White)
                  analyse  depth=6    file=start  position
                  annotate depth=4
                  match    depth=4    games=10    noise=±4    seed=now
                """);
    }

    /* ----- arg helpers ----- */

    private static int intArg(String[] args, String name, int def) {
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].equals(name)) return Integer.parseInt(args[i + 1]);
        return def;
    }

    private static long longArg(String[] args, String name, long def) {
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].equals(name)) return Long.parseLong(args[i + 1]);
        return def;
    }

    private static double doubleArg(String[] args, String name, double def) {
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].equals(name)) return Double.parseDouble(args[i + 1]);
        return def;
    }

    private static String strArg(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].equals(name)) return args[i + 1];
        return def;
    }

    /* ----- commands ----- */

    private static void playCmd(String[] args) {
        int    depth  = intArg(args, "--depth",   5);
        String aiSide = strArg(args, "--ai-side", "B");

        // Resolve evaluator(s). --weights sets both sides; --weights-w/-b override per side.
        String wBoth = strArg(args, "--weights",   null);
        String wW    = strArg(args, "--weights-w", wBoth);
        String wB    = strArg(args, "--weights-b", wBoth);

        // Defender scale: --defender-scale sets both; -w/-b versions override per side.
        // Default: the Evaluator's compiled-in default (currently 0 = disabled).
        double dBoth = doubleArg(args, "--defender-scale",   Evaluator.DEFAULT_DEFENDER_SCALE);
        double dW    = doubleArg(args, "--defender-scale-w", dBoth);
        double dB    = doubleArg(args, "--defender-scale-b", dBoth);

        Evaluator evW = (wW == null) ? new Evaluator(Evaluator.DEFAULT_WEIGHTS, dW)
                                     : Evaluator.parse(wW, dW);
        Evaluator evB = (wB == null) ? new Evaluator(Evaluator.DEFAULT_WEIGHTS, dB)
                                     : Evaluator.parse(wB, dB);

        Board  b         = Board.initial();
        Search searchW   = new Search(evW);
        Search searchB   = new Search(evB);
        Scanner sc       = new Scanner(System.in);
        List<Move> played = new ArrayList<>();

        if (wW != null || wB != null || dW != Evaluator.DEFAULT_DEFENDER_SCALE
                                     || dB != Evaluator.DEFAULT_DEFENDER_SCALE) {
            System.out.println("White: weights=" + evW.spec() + "  defenderScale=" + evW.defenderScale());
            System.out.println("Black: weights=" + evB.spec() + "  defenderScale=" + evB.defenderScale());
        }

        while (true) {
            b.print(System.out);

            byte w = b.winner();
            if (w != Board.EMPTY) {
                String winner = (w == Board.WHITE) ? "White" : "Black";
                String result = winner + " wins on move " + ((played.size() + 1) / 2);
                System.out.println("*** " + result + " ***");
                autosave(played, result, b);
                return;
            }

            List<Move> legal = MoveGenerator.legalMoves(b);
            if (legal.isEmpty()) {
                String loser  = (b.side() == Board.WHITE) ? "White" : "Black";
                String winner = (b.side() == Board.WHITE) ? "Black" : "White";
                String result = winner + " wins by elimination on move "
                              + ((played.size() + 1) / 2) + " (" + loser + " has no legal moves)";
                System.out.println("*** " + result + " ***");
                autosave(played, result, b);
                return;
            }

            boolean aiTurn =
                  "both".equalsIgnoreCase(aiSide)
               || (b.side() == Board.WHITE && "W".equalsIgnoreCase(aiSide))
               || (b.side() == Board.BLACK && "B".equalsIgnoreCase(aiSide));

            Move m;
            if (aiTurn) {
                Search search = (b.side() == Board.WHITE) ? searchW : searchB;
                long t0 = System.currentTimeMillis();
                Search.Result r = search.findBest(b, depth);
                long ms = System.currentTimeMillis() - t0;
                m = r.bestMove;
                System.out.printf("AI plays %s   score=%+d  depth=%d  nodes=%d  %d ms%n",
                                  m, r.score, r.depth, r.nodes, ms);
            } else {
                System.out.print("Your move (e.g. b2c3; 's' save, 'l' list, 'q' quit): ");
                if (!sc.hasNextLine()) { saveOnQuit(played, b); return; }
                String line = sc.nextLine().trim();
                if (line.equals("q")) { saveOnQuit(played, b); return; }
                if (line.equals("s")) {
                    try {
                        Path p = GameWriter.save(played, "in progress", b.toFen());
                        System.out.println("Saved: " + p);
                    } catch (IOException e) {
                        System.out.println("Save failed: " + e.getMessage());
                    }
                    continue;
                }
                if (line.equals("l")) {
                    legal.forEach(mv -> System.out.print(mv + " "));
                    System.out.println();
                    continue;
                }
                try {
                    m = Move.parse(line);
                } catch (Exception e) {
                    System.out.println("Bad input: " + e.getMessage());
                    continue;
                }
                final Move target = m;
                if (legal.stream().noneMatch(x -> x.equals(target))) {
                    System.out.println("Illegal move.");
                    continue;
                }
            }
            b.apply(m);
            played.add(m);
        }
    }

    private static void autosave(List<Move> moves, String resultLine, Board b) {
        if (moves.isEmpty()) return;
        try {
            Path p = GameWriter.save(moves, resultLine, b.toFen());
            System.out.println("Auto-saved game: " + p);
        } catch (IOException e) {
            System.err.println("Autosave failed: " + e.getMessage());
        }
    }

    private static void saveOnQuit(List<Move> moves, Board b) {
        if (moves.isEmpty()) return;
        try {
            Path p = GameWriter.save(moves, "quit by user", b.toFen());
            System.out.println("Saved partial game: " + p);
        } catch (IOException e) {
            System.err.println("Save failed: " + e.getMessage());
        }
    }

    private static void analyseCmd(String[] args) throws IOException {
        String file  = strArg(args, "--file",  null);
        int    depth = intArg(args, "--depth", 6);

        Board b = (file != null) ? PositionIO.load(Paths.get(file)) : Board.initial();
        b.print(System.out);
        System.out.println("FEN: " + b.toFen());

        List<Move> legal = MoveGenerator.legalMoves(b);
        System.out.println("Legal moves (" + legal.size() + "):");
        StringBuilder sb = new StringBuilder("  ");
        int col = 0;
        for (Move m : legal) {
            sb.append(m).append(" ");
            if (++col % 12 == 0) sb.append("\n  ");
        }
        System.out.println(sb);

        System.out.println("Static eval (side to move): " + Evaluator.evaluateDefault(b));

        if (legal.isEmpty() || b.winner() != Board.EMPTY) return;

        Search s   = new Search();
        long   t0  = System.currentTimeMillis();
        Search.Result r = s.findBest(b, depth);
        long ms    = System.currentTimeMillis() - t0;
        System.out.printf("Best move at depth %d: %s   score=%+d  nodes=%d  %d ms%n",
                          r.depth, r.bestMove, r.score, r.nodes, ms);
        System.out.println(s.tt().statsLine());
    }

    private static void annotateCmd(String[] args) throws IOException {
        String file      = strArg(args, "--file",       null);
        int    depth     = intArg(args, "--depth",      4);
        int    startMove = intArg(args, "--start-move", 1);
        if (file == null) { System.err.println("--file is required for annotate"); System.exit(2); }
        if (startMove < 1) { System.err.println("--start-move must be >= 1"); System.exit(2); }

        List<Move> moves = GameReplay.loadMoves(Paths.get(file));
        int totalPairs = (moves.size() + 1) / 2;
        if (startMove > totalPairs) {
            System.err.println("--start-move " + startMove + " is past the end (game has "
                               + totalPairs + " moves)");
            System.exit(2);
        }

        Board b = Board.initial();
        Search engine = new Search();

        // Fast-forward: silently apply plies for pairs 1 .. (startMove-1).
        // Each pair is up to 2 plies; (startMove - 1) full pairs == 2 * (startMove - 1) plies.
        int ffPlies = Math.min(2 * (startMove - 1), moves.size());
        for (int i = 0; i < ffPlies; i++) {
            Move m   = moves.get(i);
            int  ply = i + 1;
            int  pair = (ply + 1) / 2;
            String side = (b.side() == Board.WHITE) ? "White" : "Black";
            List<Move> legal = MoveGenerator.legalMoves(b);
            if (legal.stream().noneMatch(x -> x.equals(m))) {
                b.print(System.out);
                System.out.println("Move " + pair + " (" + side + " " + m + ") is illegal here. Aborting annotation.");
                return;
            }
            b.apply(m);
            if (b.winner() != Board.EMPTY) {
                b.print(System.out);
                System.out.println("Game already ended at move " + pair
                                   + ", before --start-move " + startMove + ".");
                return;
            }
        }
        if (startMove > 1) {
            System.out.println("=== Resuming annotation from move " + startMove + " ===");
            b.print(System.out);
        }

        // Annotation loop from the position right before White's startMove'th move.
        for (int i = ffPlies; i < moves.size(); i++) {
            Move userMove = moves.get(i);
            int  ply      = i + 1;
            int  pair     = (ply + 1) / 2;
            boolean white = (b.side() == Board.WHITE);
            String side   = white ? "White" : "Black";

            List<Move> legal = MoveGenerator.legalMoves(b);
            if (legal.stream().noneMatch(x -> x.equals(userMove))) {
                b.print(System.out);
                System.out.println("Move " + pair + " (" + side + " " + userMove + ") is illegal here. Aborting annotation.");
                return;
            }

            // Engine's preferred move and score from current position
            Search.Result engineRec = engine.findBest(b, depth);

            // Score of the user's actual move (at one ply less, from same player's view).
            // Reuse the same Search instance so TT carries over (the position after
            // the user's move shares subtree positions with the engine's search).
            byte cap = b.apply(userMove);
            int userMoveScore = -engine.findBest(b, Math.max(1, depth - 1)).score;
            b.undo(userMove, cap);

            int delta = userMoveScore - engineRec.score;
            String tag = "";
            if (delta <= -200)      tag = "  ?? blunder";
            else if (delta <= -80)  tag = "  ?  mistake";
            else if (delta <= -30)  tag = "  ?! dubious";

            // Pair number prints on White's move; Black's response is indented
            // under the same number, like a chess scoresheet.
            String prefix = white ? String.format("%3d.", pair) : "    ";
            System.out.printf("%s %-5s %-7s  engine=%-7s  engine=%+d  played=%+d  delta=%+d%s%n",
                              prefix, side, userMove.toAlgebraic(),
                              engineRec.bestMove.toAlgebraic(),
                              engineRec.score, userMoveScore, delta, tag);

            b.apply(userMove);

            byte w = b.winner();
            if (w != Board.EMPTY) {
                b.print(System.out);
                System.out.println("Game ended: " + (w == Board.WHITE ? "White" : "Black")
                                   + " wins on move " + pair);
                return;
            }
        }
        b.print(System.out);
        System.out.println("Annotation complete (" + totalPairs + " moves). No winner yet.");
    }

    /* ----- match: A vs B over N games, alternating colors ----- */

    private static void matchCmd(String[] args) {
        String specA = strArg(args, "--weights-a", null);
        String specB = strArg(args, "--weights-b", null);
        if (specA == null || specB == null) {
            System.err.println("match requires --weights-a and --weights-b");
            System.exit(2);
        }
        int    depth    = intArg(args, "--depth", 4);
        int    games    = intArg(args, "--games", 10);
        int    noise    = intArg(args, "--noise", 4);
        long   baseSeed = longArg(args, "--seed", System.nanoTime());
        boolean quiet   = hasFlag(args, "--quiet");

        double dBoth = doubleArg(args, "--defender-scale",   Evaluator.DEFAULT_DEFENDER_SCALE);
        double dA    = doubleArg(args, "--defender-scale-a", dBoth);
        double dB    = doubleArg(args, "--defender-scale-b", dBoth);

        Evaluator evA = Evaluator.parse(specA, dA);
        Evaluator evB = Evaluator.parse(specB, dB);

        System.out.println("Match: A vs B  depth=" + depth + "  games=" + games
                           + "  noise=±" + noise + "  seed=" + baseSeed);
        System.out.println("  A weights: " + evA.spec() + "  defenderScale=" + evA.defenderScale());
        System.out.println("  B weights: " + evB.spec() + "  defenderScale=" + evB.defenderScale());
        System.out.println();

        int aWins = 0, bWins = 0;
        int aAsWhiteWins = 0, aAsBlackWins = 0;
        long t0 = System.currentTimeMillis();

        for (int g = 1; g <= games; g++) {
            // Alternate which engine plays White to remove first-move-advantage bias.
            boolean aIsWhite = (g % 2 == 1);
            Evaluator evW  = aIsWhite ? evA : evB;
            Evaluator evB_ = aIsWhite ? evB : evA;
            // Per-game derived seeds: different for White and Black, different per game,
            // so two games with the same color assignment still diverge.
            long seedW = baseSeed ^ ((long)g << 1)       ^ 0xA1B2C3D4E5F60718L;
            long seedB = baseSeed ^ (((long)g << 1) | 1) ^ 0x123456789ABCDEF0L;
            Search   sw = new Search(20, evW,  noise, seedW);
            Search   sb = new Search(20, evB_, noise, seedB);

            Board       board  = Board.initial();
            List<Move>  played = new ArrayList<>();
            byte        winner;

            while (true) {
                winner = board.winner();
                if (winner != Board.EMPTY) break;
                List<Move> legal = MoveGenerator.legalMoves(board);
                if (legal.isEmpty()) {
                    // side to move loses by exhaustion / total block
                    winner = Board.other(board.side());
                    break;
                }
                Search s = (board.side() == Board.WHITE) ? sw : sb;
                Move m = s.findBest(board, depth).bestMove;
                board.apply(m);
                played.add(m);
            }

            boolean aWon = (aIsWhite && winner == Board.WHITE)
                        || (!aIsWhite && winner == Board.BLACK);
            if (aWon) {
                aWins++;
                if (aIsWhite) aAsWhiteWins++; else aAsBlackWins++;
            } else {
                bWins++;
            }

            String whoIsWhite  = aIsWhite ? "A" : "B";
            String whoIsBlack  = aIsWhite ? "B" : "A";
            String winnerLabel = (winner == Board.WHITE) ? whoIsWhite : whoIsBlack;
            String resultLine  = "Match game " + g + "/" + games
                               + ", White=" + whoIsWhite + " Black=" + whoIsBlack
                               + ", winner=" + winnerLabel
                               + " (" + (winner == Board.WHITE ? "White" : "Black")
                               + ") after " + ((played.size() + 1) / 2) + " moves";

            if (!quiet) System.out.println(resultLine);

            try {
                GameWriter.save(played, resultLine, board.toFen());
            } catch (IOException e) {
                System.err.println("save failed: " + e.getMessage());
            }
        }

        long ms = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("=== Result ===");
        System.out.printf("A: %d wins  (%d as White, %d as Black)%n", aWins, aAsWhiteWins, aAsBlackWins);
        System.out.printf("B: %d wins  (%d as White, %d as Black)%n",
                          bWins, (games / 2 + games % 2) - aAsWhiteWins, (games / 2) - aAsBlackWins);
        System.out.printf("A win rate: %.1f%%%n", 100.0 * aWins / games);
        System.out.printf("Time: %.1f s  (%.1f s/game)%n", ms / 1000.0, ms / 1000.0 / games);
    }

    /* ----- benchmark: measure raw engine throughput -----
     *
     * Four micro-benchmarks, each timed independently:
     *
     *   1. legalMoves/sec      - just move generation from a snapshot of
     *                            ~64 representative positions, looped.
     *   2. apply+undo/sec      - move generation + apply + undo, the inner
     *                            loop alpha-beta actually executes.
     *   3. random games/sec    - full random self-play games to termination.
     *   4. perft(depth=N)      - count leaves of the full game tree to a
     *                            fixed depth, deterministic (no RNG). The
     *                            'gold standard' that detects move-gen
     *                            correctness bugs across implementations.
     *
     * Each loop runs for a fixed wall-clock budget (default 2 s after a
     * 500 ms warm-up). Numbers are throughput, so bigger = faster.
     */
    private static void benchmarkCmd(String[] args) {
        int  games      = intArg(args,  "--games",       1000);  // for the random-games benchmark
        int  perftDepth = intArg(args,  "--depth",       3);     // for perft
        long seed       = longArg(args, "--seed",        42L);
        long budgetMs   = longArg(args, "--budget-ms",   2000L);
        long warmupMs   = longArg(args, "--warmup-ms",   500L);

        System.out.println("Breakthrough benchmark");
        System.out.println("  warmup       = " + warmupMs + " ms (discarded)");
        System.out.println("  per-bench    = " + budgetMs + " ms");
        System.out.println("  random games = " + games);
        System.out.println("  perft depth  = " + perftDepth);
        System.out.println("  seed         = " + seed);
        System.out.println();

        // Build a small bank of representative positions: the start, plus the
        // result of 4 random plies, 8 random plies, ... up to 32. This covers
        // opening, midgame, and endgame densities. With one bank we measure
        // the *average* generation cost rather than just the opening's.
        Board[] bank = buildPositionBank(seed);
        System.out.println("Position bank: " + bank.length + " positions");

        /* 1. legalMoves/sec ------------------------------------------------ */
        // Warm up
        long t0 = System.currentTimeMillis();
        long warmupOps = 0;
        while (System.currentTimeMillis() - t0 < warmupMs) {
            for (Board b : bank) MoveGenerator.legalMoves(b);
            warmupOps += bank.length;
        }
        // Measure
        long ops = 0;
        t0 = System.nanoTime();
        long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            for (Board b : bank) MoveGenerator.legalMoves(b);
            ops += bank.length;
        }
        double elapsedS = (System.nanoTime() - t0) / 1e9;
        double rateLM   = ops / elapsedS;
        System.out.printf("  legalMoves/sec      : %s  (%d ops in %.2f s)%n",
                          fmt(rateLM), ops, elapsedS);

        /* 1b. generate(int[])/sec — no allocation per call ----------------- */
        int[] buf = new int[MoveGenerator.MAX_MOVES];
        t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < warmupMs) {
            for (Board b : bank) MoveGenerator.generate(b, buf);
        }
        long opsGen = 0;
        t0 = System.nanoTime();
        deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            for (Board b : bank) MoveGenerator.generate(b, buf);
            opsGen += bank.length;
        }
        elapsedS = (System.nanoTime() - t0) / 1e9;
        double rateGen = opsGen / elapsedS;
        System.out.printf("  generate/sec        : %s  (%d ops in %.2f s) <- search uses this%n",
                          fmt(rateGen), opsGen, elapsedS);

        /* 2. apply+undo/sec ----------------------------------------------- */
        // Per position: generate moves, apply each, undo. This is what search
        // does on every interior node.
        warmupOps = 0;
        t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < warmupMs) {
            for (Board b : bank) {
                List<Move> moves = MoveGenerator.legalMoves(b);
                for (Move m : moves) {
                    byte cap = b.apply(m);
                    b.undo(m, cap);
                    warmupOps++;
                }
            }
        }
        long applyOps = 0;
        t0 = System.nanoTime();
        deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            for (Board b : bank) {
                List<Move> moves = MoveGenerator.legalMoves(b);
                for (Move m : moves) {
                    byte cap = b.apply(m);
                    b.undo(m, cap);
                    applyOps++;
                }
            }
        }
        elapsedS = (System.nanoTime() - t0) / 1e9;
        double rateAU = applyOps / elapsedS;
        System.out.printf("  apply+undo/sec      : %s  (%d ops in %.2f s)%n",
                          fmt(rateAU), applyOps, elapsedS);

        /* 3. random games/sec --------------------------------------------- */
        java.util.Random rng = new java.util.Random(seed ^ 0xDEADBEEFCAFEBABEL);
        // Warm up: play a handful of games
        for (int i = 0; i < 20; i++) playRandomGame(rng);
        long gamesPlayed = 0, totalPlies = 0;
        t0 = System.nanoTime();
        deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline && gamesPlayed < games) {
            totalPlies += playRandomGame(rng);
            gamesPlayed++;
        }
        elapsedS = (System.nanoTime() - t0) / 1e9;
        double rateGames = gamesPlayed / elapsedS;
        double avgPlies  = gamesPlayed == 0 ? 0 : (double) totalPlies / gamesPlayed;
        System.out.printf("  random games/sec    : %s  (%d games, avg %.1f plies, %.2f s)%n",
                          fmt(rateGames), gamesPlayed, avgPlies, elapsedS);

        /* 4. perft(depth=N) ------------------------------------------------ */
        // Deterministic — no RNG, no time budget. We run it once and report
        // both the node count and the rate.
        Board p = Board.initial();
        long perftT0 = System.nanoTime();
        long leaves  = perft(p, perftDepth);
        double perftS = (System.nanoTime() - perftT0) / 1e9;
        System.out.printf("  perft(%d)            : %d leaves in %.2f s (%s leaves/sec)%n",
                          perftDepth, leaves, perftS, fmt(leaves / perftS));

        System.out.println();
        System.out.println("Compact score line (for diffing across builds):");
        System.out.printf("BENCH lm=%.0f gen=%.0f au=%.0f rg=%.1f perft%d=%d%n",
                          rateLM, rateGen, rateAU, rateGames, perftDepth, leaves);
    }

    /** Random self-play game; returns ply count. No engine, just uniform random moves. */
    private static int playRandomGame(java.util.Random rng) {
        Board b = Board.initial();
        int plies = 0;
        while (true) {
            if (b.winner() != Board.EMPTY) return plies;
            List<Move> legal = MoveGenerator.legalMoves(b);
            if (legal.isEmpty()) return plies;
            b.apply(legal.get(rng.nextInt(legal.size())));
            plies++;
            if (plies > 500) return plies; // pathological safety
        }
    }

    /** Standard perft: count leaves at given depth. No TT, no pruning. */
    private static long perft(Board b, int depth) {
        if (depth == 0) return 1L;
        if (b.winner() != Board.EMPTY) return 1L; // terminal counts as a leaf
        long total = 0;
        for (Move m : MoveGenerator.legalMoves(b)) {
            byte cap = b.apply(m);
            total += perft(b, depth - 1);
            b.undo(m, cap);
        }
        return total;
    }

    /** Build representative positions for the throughput benchmarks. */
    private static Board[] buildPositionBank(long seed) {
        java.util.Random r = new java.util.Random(seed);
        int[] depths = {0, 4, 8, 12, 16, 20, 24, 28, 32};
        List<Board> bank = new ArrayList<>();
        // Take several samples at each depth to average out tactical-density variance.
        for (int d : depths) {
            for (int rep = 0; rep < 3; rep++) {
                Board b = Board.initial();
                for (int i = 0; i < d; i++) {
                    if (b.winner() != Board.EMPTY) break;
                    List<Move> moves = MoveGenerator.legalMoves(b);
                    if (moves.isEmpty()) break;
                    b.apply(moves.get(r.nextInt(moves.size())));
                }
                if (b.winner() == Board.EMPTY && !MoveGenerator.legalMoves(b).isEmpty()) {
                    bank.add(b);
                }
            }
        }
        return bank.toArray(new Board[0]);
    }

    /** Format a large number with grouping; for compact human display. */
    private static String fmt(double v) {
        if (v >= 1e9) return String.format("%.2fG", v / 1e9);
        if (v >= 1e6) return String.format("%.2fM", v / 1e6);
        if (v >= 1e3) return String.format("%.2fK", v / 1e3);
        return String.format("%.0f", v);
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String a : args) if (a.equals(name)) return true;
        return false;
    }
}
