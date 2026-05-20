# Breakthrough analyser

Analyser and engine for the abstract strategy game
**Breakthrough** (Dan Troyka, 2000), on an 8×8 board.

## Build

```sh
make            # compiles to build/
make jar        # builds breakthrough.jar
```

Needs `javac`/`java` 17 or newer (uses `record` and switch expressions).

## Run

```sh
# Play White vs the AI (depth 5)
java -cp build Main play

# Stronger AI, you play Black
java -cp build Main play --depth 7 --ai-side W

# Two-AI game (engine vs engine)
java -cp build Main play --depth 5 --ai-side both

# Analyse a position from a file
java -cp build Main analyse --file games/midgame.fen --depth 6

# Analyse the starting position
java -cp build Main analyse --depth 6

# Annotate a recorded game with engine commentary
java -cp build Main annotate --file games/sample.game --depth 4

# Self-play tournament: two evaluators, alternating colors
java -cp build Main match \
    --weights-a 5,10,16,26,42,70,120,1000 \
    --weights-b 10,20,30,45,65,90,130,1000 \
    --games 10 --depth 4
```

Or via make: `make play ARGS="--depth 6"`, `make analyse ARGS="--file games/midgame.fen"`, etc.

## Evaluator weights

The static evaluation has two terms.

**1. Advancement (material × position).** Each piece contributes a per-row
weight to the side that owns it. The weight vector is 8 integers, one per
row of advancement from a piece's home row (`w0` = piece on its home row,
`w7` = piece reaching the opponent's home row, which is a win). The
built-in default (tuned by SPSA, verified at 76.5% vs the original
pre-tuning weights over 200 games at depth 6) is:

```
25, 22, 23, 27, 41, 58, 127, 1000
```

**2. Defender bonus.** A piece is "defended" if a friendly piece sits
one square diagonally behind it (relative to its direction of travel),
because that piece could recapture. The bonus per defender is
`defender_scale * advancement_weight[row] * defenders`, so defenders
on advanced pieces matter more in absolute terms. Default
`defender_scale = 0` disables the term (legacy behavior). The intuition
this captures is "pieces with defenders are battering rams; pieces
without are exposed" — the core Breakthrough strategic idea.

Provide a different vector with `--weights w0,w1,...,w7`. In `play` you
can also set them per side with `--weights-w` / `--weights-b`, useful
for quick A/B testing while you watch the moves. Same applies to
`--defender-scale` / `--defender-scale-w` / `--defender-scale-b`.

In `match`, the corresponding options are `--defender-scale`,
`--defender-scale-a`, and `--defender-scale-b`.

For systematic experimentation, use `match`:

```sh
java -cp build Main match \
    --weights-a 5,10,16,26,42,70,120,1000 \
    --weights-b 10,20,30,45,65,90,130,1000 \
    --games 20 --depth 4
```

`match` plays `--games` games at fixed `--depth`, alternating which
evaluator plays White each game so the first-move advantage cancels
out. It prints the per-game result and a final W/L summary, and saves
every game to `saves/` (you can `annotate` any of them for move-by-move
analysis). Add `--quiet` to suppress per-game lines.

Because the search is deterministic, two games with the same color
assignment would otherwise play out identically. `match` adds a small
deterministic jitter to leaf evaluations: `±4` by default, controlled
by `--noise N`. The jitter is a pure function of `(seed, position
hash)`, so the TT and alpha-beta stay consistent within a game.
Different per-game seeds produce different tiebreak preferences, which
diversifies openings and downstream play. Pin a base seed with
`--seed N` for reproducibility (otherwise `System.nanoTime()` is used);
the printed seed at the top of the run can be passed back in to repeat
a previous match exactly.

Tips:
- Low depths can mask differences if both weight schemes find the same
  forced sequence; rerun at `--depth 4` or higher to make the
  evaluator's preferences actually matter.
- Run enough games (≥30, ideally ≥100) before drawing conclusions —
  with noise, a 5–5 result and a 15–15 result are both noise, but a
  20–10 over 30 games is starting to look real.

## Move notation

Algebraic: `b2c3` (from-square immediately followed by to-square, no
separator — captures look the same). Files a–h (left to right from
White's side), ranks 1–8
(White's home row is 1, Black's is 8).

In `play`, at your turn you can enter:

- a move like `b2c3`
- `s` — save the game so far to `saves/` and continue playing
- `l` — list all legal moves
- `q` — quit (also saves the partial game to `saves/`)

Finished games are autosaved when a winner is declared (or a side runs
out of moves). Saved filenames are timestamped:
`saves/breakthrough-YYYY-MM-DD_HH-MM-SS.game`. The format is the same
free-text move list `annotate` reads, with a header noting the time, ply
count, result, and final FEN — so any saved game can be fed straight
back into `breakthrough annotate`.

## Position file format (FEN-like)

```
OOOOOOOO/OOOOOOOO/8/8/8/8/XXXXXXXX/XXXXXXXX W
```

- Ranks listed from 8 down to 1, separated by `/`.
- `X` = white piece, `O` = black piece, digit = N empty squares.
- Trailing `W` or `B` = side to move.
- Lines starting with `#` (and anything after `#`) are comments.

## Game file format

Free text, one or many moves per line. Move numbers (`1.`) and dots are
ignored; the parser extracts tokens like `b2c3`. Comments use
`#`. See `games/sample.game`.

## How the engine works

- **Move generation** (`MoveGenerator`): for each of your pieces, try
  forward (only into empty) and the two diagonal-forwards (empty or
  opponent capture).
- **Evaluation** (`Evaluator`): material weighted by advancement.
  Weights `{5, 10, 16, 26, 42, 70, 120, 1000}` for rows 0..7 from the
  piece's home row. The exponential growth means an advanced piece
  threatens to win and is valued accordingly. A piece on the opposite
  home row is a terminal win.
- **Search** (`Search`): negamax with alpha-beta pruning, iterative
  deepening, transposition table, and move ordering by TT move →
  captures → killer moves → advancement. Scoring is ply-indexed and
  mate scores are adjusted on TT store/probe so cached mate distances
  remain correct at any ply. Two killer-move slots per ply hold the
  most recent quiet moves that produced beta cutoffs, sharply
  improving ordering quality at deeper plies.
- **Quiescence search**: at the leaf (depth 0) the search continues
  along captures only until the position is "quiet" (no captures
  available), then evaluates statically. Uses stand-pat (since
  captures in Breakthrough are optional) and a Breakthrough-specific
  capture ordering by destination advancement. Solves the *horizon
  problem*: the static evaluator is only reliable in quiet positions,
  so without quiescence the engine would systematically misvalue any
  leaf caught mid-trade. Most visible payoff is far more stable scores
  across iterative-deepening iterations.
- **Hashing** (`Zobrist`): 64-bit Zobrist keys for `(piece, square)`
  pairs and side-to-move. The hash is updated incrementally in
  `Board.apply`/`undo` (XOR is its own inverse), never recomputed.
- **Transposition table** (`TT`): fixed-size power-of-two table, single
  slot per index, always-replace. Stores `EXACT` / `LOWER` / `UPPER`
  bound entries plus a best move for ordering. Default size is `2^20`
  slots (~1M entries).

## Possible extensions

- History heuristic / counter-move heuristic for move ordering of
  quiets that aren't killers.
- Depth-preferred or two-tier TT replacement.
- Better evaluation: defender count, mobility, threats, phalanx structure.
- Bitboard representation for faster move generation (biggest single
  performance speedup left).

## Benchmark

The `benchmark` subcommand measures engine throughput. Useful for
comparing implementations (e.g. before/after a bitboard rewrite) or
for sanity-checking that a change hasn't regressed performance.

```sh
java -cp build Main benchmark              # defaults
java -cp build Main benchmark --depth 5    # perft to depth 5
make bench ARGS="--budget-ms 5000"         # longer per-bench budget
```

Four metrics are reported:

- **`legalMoves/sec`** — pure move generation cost. The most direct
  measure of "how fast is `MoveGenerator.legalMoves(b)`".
- **`apply+undo/sec`** — generate moves + apply each + undo each. This
  is the inner loop of alpha-beta and tracks what search actually does
  on every internal node.
- **`random games/sec`** — full random games to termination. Includes
  generation, apply, winner detection. A rough "games of random play
  per second" number useful for tuner throughput estimation.
- **`perft(N)`** — count of leaves in the full game tree to depth N
  from the starting position. Deterministic — the *count* must match
  across implementations, so it doubles as a correctness check. If a
  bitboard rewrite changes perft, the new code is wrong.

Reference numbers for the current Java implementation (one core,
JDK 21, no GC tuning):

```
perft(3) =    11,132
perft(4) =   256,036
perft(5) = 6,182,818
```

The compact `BENCH ...` line is intended for diffing across builds.

## Long-running weight tuner

A standalone `Tuner` program optimises the advancement weights and
the defender-scale parameter using SPSA (Simultaneous Perturbation
Stochastic Approximation), which is well-suited to noisy expensive
low-dimensional objectives like "how strong does this engine play
against another engine".

```sh
# Start a fresh run (default settings, writes to tuner-state/)
make tune

# Or with options:
java -cp build Tuner --depth 4 --games-spsa 12 --games-gauntlet 30 \
                     --gauntlet-every 20 --learning-rate 8 --perturb-size 4

# Resume after Ctrl-C (or crash, or reboot):
java -cp build Tuner --resume
```

The tuner writes to `tuner-state/`:

- `state.txt` — current iteration, current weights, base seed, best
  gauntlet result. Resumed from on `--resume`. Human-readable, you can
  inspect or hand-edit between runs.
- `history.csv` — one row per iteration: weights, SPSA wins/losses,
  gauntlet wins/losses, win rate. Plot this in R or anywhere else to
  see whether tuning is making progress.
- `log.txt` — same as stdout output, append-only.
- `best.txt` — the weights that achieved the highest gauntlet win rate
  so far. This is what you'd actually use in `play`/`match` after the
  run.

The headline metric is **gauntlet win rate** — every `--gauntlet-every`
iterations the current candidate plays a fixed-size match against the
anchor weights (default: the original `5,10,16,26,42,70,120,1000`).
Expect the curve to be noisy for the first 20–50 iterations and start
trending up after that.

Notes:
- The terminal weight (`w7`) is held fixed at 1000. Reaching the
  opponent's home row is already a terminal win, so the value there
  has no effect on play.
- Weights are clamped to `[1, 5000]` to keep the search well-behaved.
- Defender-scale is clamped to `[0, 2]`.
- The tuner perturbs both weights and dscale on each iteration. Pass
  `--no-tune-dscale` to keep dscale fixed (useful if you only want to
  optimize weights with a chosen dscale).
- Starting dscale comes from `--init-dscale` (defaults to the anchor's
  dscale, which itself defaults to 0). Use `--anchor-dscale F` to set
  the dscale used by the gauntlet anchor.
- One iteration costs `2 × games-spsa` self-play games (plus an
  occasional gauntlet). At depth 4 and the default 12 games per side,
  one iteration is roughly 5-10 seconds; days of runtime get you
  thousands of iterations.
- The Search uses a smaller TT (256k entries) during tuning to keep
  memory low when many instances are being created.

### Verifying the tuner's output

`best.txt` only tracks the *highest single gauntlet score*, which is
biased by small-sample noise — an iteration that played 50 lucky
games can show up as 88% even if the underlying weights are worse
than another candidate at 80%. Use the included script to find the
*robustly* best weights:

```sh
./verify-finalists.sh -n 5 -d 6 -g 200
```

This picks the top 5 unique-weight candidates by gauntlet score from
`tuner-state/history.csv`, runs a 200-game depth-6 verification match
for each, and prints a ranked summary. With these defaults plan on
2–3 hours. Adopt whichever candidate wins the verification match —
that's the one most likely to generalize.

Options: `-n N` candidates, `-d N` depth, `-g N` games, `-s DIR`
state directory, `-a WEIGHTS` anchor weights, `-o FILE` output log.
Run `./verify-finalists.sh -h` for the inline help.

Game saves go to `saves/` (created on first save).
