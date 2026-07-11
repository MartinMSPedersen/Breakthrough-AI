# Breakthrough — CodinGame Rust bot

Single-file Rust bot for the CodinGame "Breakthrough" style contest
("Rush to the opponent's row"). Paste `player.rs` into the CodinGame editor
(language: Rust) and submit — everything is in the one file, no dependencies
beyond the standard library.

## Why Rust

The turn limit is tight (1000 ms first turn, 100 ms after). Rust gives real
`u64` bitboards (no bignum overhead) and no GC pauses, so the search goes
several plies deep well within budget.

## How it plays

- **Bitboards** — two `u64`s, one per colour. Move = `(from << 6) | to`.
- **Search** — negamax + alpha-beta + iterative deepening, with a
  transposition table (kept across turns), killer-move ordering, and
  principal-variation search.
- **Time management** — iterative deepening runs until a wall-clock deadline
  and returns the best move from the last fully-completed depth. Budgets are
  deliberately conservative to avoid ever losing on time:
  **800 ms first turn, 50 ms after** (limits are 1000 / 100). The clock is
  checked every 256 nodes, and the loop won't *start* a new depth once ~45%
  of the budget is gone (the next iteration costs several times the previous,
  so it wouldn't finish anyway).
- **Transposition table** — fixed-size array (2^20 entries), direct-mapped,
  full-key checked, persisted across turns. No allocation or rehashing during
  search, so timing is predictable (a `HashMap` caused latency spikes).
- **Board sync** — the bot tracks its own board: it applies the opponent's
  move (given each turn), searches, then applies its own reply.
- **Safety net** — the contest supplies the legal-move list each turn; the bot
  confirms its chosen move is in that list and falls back to the first legal
  move if not (they should always agree — see below).

## Output format

The contest wants `move message`. The bot prints e.g. `c2c3 go` — the move
plus a short word. Change `"go"` in the final `println!` to whatever you like.

## Correctness

The move generation, make/unmake, and terminal detection use the same bitboard
operations as a separate engine that's validated with **perft**: the number of
leaf nodes in the game tree from the start position matches the reference at
every depth (perft(5) = 6,182,818). Those exact operations were re-run through
the perft oracle and reproduce the reference counts, so the move logic here is
correct.

## Building / testing locally (optional)

CodinGame compiles the file for you, but to iterate locally:

```sh
rustc -O player.rs -o player      # optimized build
./player                          # then type the turn input on stdin
```

Example first-turn input (you start, so opponent move is "None"):

```
None
22
a2a3
b2b3
...
```

The bot replies with a line like `a2a3 go`.

## Tuning

- **Search time** — the `800` / `50` millisecond budgets in `main()`. These
  are conservative to guarantee no timeouts. If you're consistently well under
  the 100 ms limit and want more strength, raise the `50` (say to 70); if you
  ever see a timeout, lower it. The `* 45` in `search()` is the "don't start a
  new depth past this fraction of the budget" guard — lower it to be even
  safer, raise it to squeeze out more depth.
- **Evaluation** — `WEIGHTS` is the per-row advancement table. Bigger values
  toward the end (index 6–7) make the bot value near-promotion runners more.
- **Move ordering** — captures and killers are prioritised; tweak the scores
  in `negamax` if you want different behaviour.
