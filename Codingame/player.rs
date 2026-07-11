use std::io;
use std::time::Instant;

// ============================================================================
// Breakthrough bot — single file for CodinGame.
//
// Board: 8x8, two u64 bitboards. Square index = row*8 + col, row 0 = rank 1.
// White (side 0) moves toward higher rows, wins on row 7.
// Black (side 1) moves toward lower rows, wins on row 0.
// A move packs into a u16: (from << 6) | to.
//
// Search: negamax + alpha-beta + iterative deepening, bounded by a wall-clock
// deadline so we always answer within the turn's time limit. A small
// transposition table (persisted across turns) and killer moves speed it up.
// ============================================================================

const WHITE: u8 = 0;
const BLACK: u8 = 1;

const RANK_1: u64 = 0x0000_0000_0000_00ff;
const RANK_8: u64 = 0xff00_0000_0000_0000;
const NOT_FILE_A: u64 = !0x0101_0101_0101_0101;
const NOT_FILE_H: u64 = !0x8080_8080_8080_8080;

const WIN_SCORE: i32 = 100_000;
const MATE_THRESHOLD: i32 = WIN_SCORE - 1000;
const MAX_SCORE: i32 = WIN_SCORE + 1000;
const INF: i32 = 1_000_000;

// Advancement weights per row-from-home (index 0 = home row). Same values the
// other engines use; tuned so the last ranks dominate.
const WEIGHTS: [i32; 8] = [25, 22, 23, 27, 41, 58, 127, 1000];

#[derive(Clone)]
struct Board {
    white: u64,
    black: u64,
    side: u8,
}

impl Board {
    fn initial() -> Board {
        Board { white: 0x0000_0000_0000_ffff, black: 0xffff_0000_0000_0000, side: WHITE }
    }

    #[inline]
    fn own(&self) -> u64 { if self.side == WHITE { self.white } else { self.black } }
    #[inline]
    fn opp(&self) -> u64 { if self.side == WHITE { self.black } else { self.white } }

    /// Apply a packed move, returning captured-flag for undo.
    #[inline]
    fn apply(&mut self, m: u16) -> bool {
        let from = (m >> 6) as u32;
        let to = (m & 0x3f) as u32;
        let from_bit = 1u64 << from;
        let to_bit = 1u64 << to;
        let both = from_bit | to_bit;
        let captured;
        if self.white & from_bit != 0 {
            self.white ^= both;
            captured = self.black & to_bit != 0;
            if captured { self.black ^= to_bit; }
        } else {
            self.black ^= both;
            captured = self.white & to_bit != 0;
            if captured { self.white ^= to_bit; }
        }
        self.side ^= 1;
        captured
    }

    #[inline]
    fn undo(&mut self, m: u16, captured: bool) {
        let from = (m >> 6) as u32;
        let to = (m & 0x3f) as u32;
        let from_bit = 1u64 << from;
        let to_bit = 1u64 << to;
        let both = from_bit | to_bit;
        self.side ^= 1;
        if self.white & to_bit != 0 {
            self.white ^= both;
            if captured { self.black ^= to_bit; }
        } else {
            self.black ^= both;
            if captured { self.white ^= to_bit; }
        }
    }

    #[inline]
    fn winner(&self) -> u8 {
        if self.white & RANK_8 != 0 { return WHITE; }
        if self.black & RANK_1 != 0 { return BLACK; }
        if self.white == 0 { return BLACK; }
        if self.black == 0 { return WHITE; }
        2 // none
    }

    /// A simple polynomial hash of the position (bitboards + side). Good
    /// enough for a transposition table; collisions are checked by storing
    /// the full key.
    #[inline]
    fn key(&self) -> u64 {
        // Mix the two boards and side with splitmix64-style finalizers.
        let mut h = self.white.wrapping_mul(0x9E37_79B9_7F4A_7C15);
        h ^= self.black.wrapping_add(0xBF58_476D_1CE4_E5B9).rotate_left(31);
        h = h.wrapping_mul(0x94D0_49BB_1331_11EB);
        h ^= (self.side as u64).wrapping_mul(0xD6E8_FEB8_6659_FD93);
        h ^= h >> 29;
        h
    }
}

// ---- Move generation --------------------------------------------------------

#[inline]
fn emit(dst: &mut [u16; 64], mut idx: usize, mut targets: u64, from_offset: i32) -> usize {
    while targets != 0 {
        let to = targets.trailing_zeros() as i32;
        let from = (to + from_offset) as u16;
        dst[idx] = ((from as u16) << 6) | (to as u16);
        idx += 1;
        targets &= targets - 1;
    }
    idx
}

/// All legal moves for the side to move, written into `dst`. Returns count.
/// Max possible is well under 64 (16 pieces x 3 directions = 48).
fn gen_moves(b: &Board, dst: &mut [u16; 64]) -> usize {
    let own = b.own();
    let opp = b.opp();
    let empty = !(own | opp);
    let mut n = 0usize;
    if b.side == WHITE {
        let fwd = (own << 8) & empty;
        let dl = ((own & NOT_FILE_A) << 7) & (empty | opp);
        let dr = ((own & NOT_FILE_H) << 9) & (empty | opp);
        n = emit(dst, n, fwd, -8);
        n = emit(dst, n, dl, -7);
        n = emit(dst, n, dr, -9);
    } else {
        let fwd = (own >> 8) & empty;
        let dl = ((own & NOT_FILE_H) >> 7) & (empty | opp);
        let dr = ((own & NOT_FILE_A) >> 9) & (empty | opp);
        n = emit(dst, n, fwd, 8);
        n = emit(dst, n, dl, 7);
        n = emit(dst, n, dr, 9);
    }
    n
}

// ---- Evaluation -------------------------------------------------------------

/// Evaluate from the side-to-move's perspective.
fn evaluate(b: &Board) -> i32 {
    let w = b.winner();
    if w != 2 {
        return if w == b.side { WIN_SCORE } else { -WIN_SCORE };
    }
    let mut white_score = 0i32;
    let mut bb = b.white;
    while bb != 0 {
        let sq = bb.trailing_zeros() as usize;
        let row = sq / 8;
        white_score += WEIGHTS[row];
        bb &= bb - 1;
    }
    let mut black_score = 0i32;
    bb = b.black;
    while bb != 0 {
        let sq = bb.trailing_zeros() as usize;
        let row = sq / 8;
        black_score += WEIGHTS[7 - row];
        bb &= bb - 1;
    }
    let diff = white_score - black_score;
    if b.side == WHITE { diff } else { -diff }
}

/// Advancement bonus for move ordering: prefer moves closer to the goal row.
#[inline]
fn advance_bonus(side: u8, m: u16) -> i32 {
    let to_row = ((m & 0x3f) >> 3) as i32;
    let home = if side == WHITE { 7 } else { 0 };
    100 - (home - to_row).abs() * 10
}

// ---- Transposition table ----------------------------------------------------

const TT_EXACT: u8 = 0;
const TT_LOWER: u8 = 1;
const TT_UPPER: u8 = 2;

#[derive(Clone, Copy)]
struct TtEntry {
    key: u64,
    depth: i32,
    score: i32,
    flag: u8,
    best: u16,
}

// Fixed-size, power-of-two transposition table. Direct-mapped (always
// replace), full-key checked. No allocation or rehashing during search, so
// timing is predictable — important for staying under the turn limit.
const TT_BITS: usize = 20; // 2^20 = ~1M entries
const TT_SIZE: usize = 1 << TT_BITS;
const TT_MASK: u64 = (TT_SIZE as u64) - 1;

struct Searcher {
    tt: Vec<TtEntry>,
    killers: [[u16; 2]; 128],
    deadline: Instant,
    timed_out: bool,
    nodes: u64,
}

impl Searcher {
    fn new() -> Searcher {
        Searcher {
            tt: vec![TtEntry { key: 0, depth: 0, score: 0, flag: 0, best: 0 }; TT_SIZE],
            killers: [[0u16; 2]; 128],
            deadline: Instant::now(),
            timed_out: false,
            nodes: 0,
        }
    }

    #[inline]
    fn check_time(&mut self) {
        // Check often (every 256 nodes) so we can't overshoot the deadline by
        // much even in expensive subtrees.
        if self.nodes & 0xff == 0 && Instant::now() >= self.deadline {
            self.timed_out = true;
        }
    }

    fn negamax(&mut self, b: &mut Board, depth: i32, ply: usize, mut alpha: i32, mut beta: i32) -> i32 {
        self.nodes += 1;
        self.check_time();
        if self.timed_out { return 0; }

        let alpha_orig = alpha;
        let key = b.key();

        // TT probe
        let mut tt_move = 0u16;
        {
            let e = &self.tt[(key & TT_MASK) as usize];
            if e.key == key {
                tt_move = e.best;
                if e.depth >= depth {
                    let s = adjust_from_tt(e.score, ply);
                    match e.flag {
                        TT_EXACT => return s,
                        TT_LOWER => if s > alpha { alpha = s; },
                        TT_UPPER => if s < beta { beta = s; },
                        _ => {}
                    }
                    if alpha >= beta { return s; }
                }
            }
        }

        let w = b.winner();
        if w != 2 {
            return if w == b.side { WIN_SCORE - ply as i32 } else { -(WIN_SCORE - ply as i32) };
        }
        if depth == 0 {
            return evaluate(b);
        }

        // Stack-allocated move list: no heap allocation in the search.
        let mut moves = [0u16; 64];
        let n = gen_moves(b, &mut moves);
        if n == 0 {
            return -(WIN_SCORE - ply as i32);
        }

        // Ordering scores, computed once into a stack array.
        let side = b.side;
        let opp = b.opp();
        let k0 = self.killers[ply][0];
        let k1 = self.killers[ply][1];
        let mut scores = [0i32; 64];
        for i in 0..n {
            let m = moves[i];
            let to = (m & 0x3f) as u32;
            let is_cap = opp & (1u64 << to) != 0;
            scores[i] = if m == tt_move { 1_000_000 }
                        else if is_cap { 10_000 + advance_bonus(side, m) }
                        else if m == k0 { 900 }
                        else if m == k1 { 800 }
                        else { advance_bonus(side, m) };
        }

        let mut best_score = -MAX_SCORE;
        let mut best_move = moves[0];
        let mut first = true;
        for i in 0..n {
            // Lazy selection sort: swap the best-scored remaining move into
            // position i just before searching it. On an early beta cutoff the
            // rest of the list is never sorted at all.
            let mut max_idx = i;
            let mut max_val = scores[i];
            for j in (i + 1)..n {
                if scores[j] > max_val { max_val = scores[j]; max_idx = j; }
            }
            if max_idx != i {
                moves.swap(i, max_idx);
                scores.swap(i, max_idx);
            }
            let m = moves[i];

            let cap = b.apply(m);
            let s;
            if first {
                s = -self.negamax(b, depth - 1, ply + 1, -beta, -alpha);
                first = false;
            } else {
                let mut t = -self.negamax(b, depth - 1, ply + 1, -alpha - 1, -alpha);
                if t > alpha && t < beta {
                    t = -self.negamax(b, depth - 1, ply + 1, -beta, -alpha);
                }
                s = t;
            }
            b.undo(m, cap);

            if self.timed_out { return best_score; }

            if s > best_score {
                best_score = s;
                best_move = m;
            }
            if s > alpha { alpha = s; }
            if alpha >= beta {
                // Non-capture beta cutoff -> remember as killer.
                if opp & (1u64 << ((m & 0x3f) as u32)) == 0 {
                    if self.killers[ply][0] != m {
                        self.killers[ply][1] = self.killers[ply][0];
                        self.killers[ply][0] = m;
                    }
                }
                break;
            }
        }

        // TT store. Empty slots have key==0; a real position's key is
        // effectively never 0, and we also verify key on probe, so an empty
        // slot never matches.
        let flag = if best_score <= alpha_orig { TT_UPPER }
                   else if best_score >= beta { TT_LOWER }
                   else { TT_EXACT };
        self.tt[(key & TT_MASK) as usize] = TtEntry {
            key, depth, score: adjust_to_tt(best_score, ply), flag, best: best_move,
        };

        best_score
    }

    /// Iterative deepening with a wall-clock deadline. Returns the best move
    /// found from a fully-completed iteration (or the best-so-far root move if
    /// time ran out mid-iteration).
    fn search(&mut self, b: &mut Board, start: Instant, budget: std::time::Duration) -> u16 {
        self.deadline = start + budget;
        self.timed_out = false;
        self.nodes = 0;

        let mut root_moves = [0u16; 64];
        let n = gen_moves(b, &mut root_moves);
        if n == 0 {
            return 0;
        }
        let mut best = root_moves[0];

        let mut depth = 1;
        while depth <= 64 {
            // Don't start a new iteration unless a good chunk of the budget
            // remains. Each iteration costs several times the previous, so if
            // we're already past ~45% of the budget the next one almost
            // certainly won't finish — better to keep the margin and not risk
            // a timeout.
            let elapsed = start.elapsed();
            if elapsed.as_micros() * 100 > budget.as_micros() * 45 {
                break;
            }

            let mut alpha = -INF;
            let beta = INF;
            let mut local_best = best;
            let mut best_score = -MAX_SCORE;

            // Move the previous-best move to the front for better pruning.
            for i in 0..n {
                if root_moves[i] == best {
                    root_moves.swap(0, i);
                    break;
                }
            }

            let mut aborted = false;
            for i in 0..n {
                let m = root_moves[i];
                let cap = b.apply(m);
                let s = -self.negamax(b, depth - 1, 1, -beta, -alpha);
                b.undo(m, cap);
                if self.timed_out { aborted = true; break; }
                if s > best_score {
                    best_score = s;
                    local_best = m;
                }
                if s > alpha { alpha = s; }
            }

            if !aborted {
                best = local_best;
                if best_score.abs() >= MATE_THRESHOLD { break; }
            } else {
                break;
            }
            depth += 1;
        }
        best
    }
}

#[inline]
fn adjust_to_tt(score: i32, ply: usize) -> i32 {
    if score >= MATE_THRESHOLD { score + ply as i32 }
    else if score <= -MATE_THRESHOLD { score - ply as i32 }
    else { score }
}

#[inline]
fn adjust_from_tt(score: i32, ply: usize) -> i32 {
    if score >= MATE_THRESHOLD { score - ply as i32 }
    else if score <= -MATE_THRESHOLD { score + ply as i32 }
    else { score }
}

// ---- Move <-> string --------------------------------------------------------

fn parse_move(s: &str) -> Option<u16> {
    let b = s.as_bytes();
    if b.len() < 4 { return None; }
    let fc = b[0].wrapping_sub(b'a');
    let fr = b[1].wrapping_sub(b'1');
    let tc = b[2].wrapping_sub(b'a');
    let tr = b[3].wrapping_sub(b'1');
    if fc > 7 || fr > 7 || tc > 7 || tr > 7 { return None; }
    let from = (fr as u16) * 8 + fc as u16;
    let to = (tr as u16) * 8 + tc as u16;
    Some((from << 6) | to)
}

fn move_to_string(m: u16) -> String {
    let from = (m >> 6) as u32;
    let to = (m & 0x3f) as u32;
    let fc = (from % 8) as u8;
    let fr = (from / 8) as u8;
    let tc = (to % 8) as u8;
    let tr = (to / 8) as u8;
    format!("{}{}{}{}",
        (b'a' + fc) as char, (b'1' + fr) as char,
        (b'a' + tc) as char, (b'1' + tr) as char)
}

// ---- Game loop --------------------------------------------------------------

fn main() {
    let mut board = Board::initial();
    let mut searcher = Searcher::new();
    let mut first_turn = true;

    loop {
        let mut input_line = String::new();
        if io::stdin().read_line(&mut input_line).unwrap() == 0 { break; }
        let opponent_move = input_line.trim().to_string();

        // Apply the opponent's move to keep our board in sync.
        if opponent_move != "None" && !opponent_move.is_empty() {
            if let Some(m) = parse_move(&opponent_move) {
                board.apply(m);
            }
        }

        let mut input_line = String::new();
        io::stdin().read_line(&mut input_line).unwrap();
        let legal_moves: i32 = input_line.trim().parse().unwrap_or(0);

        let mut legal: Vec<u16> = Vec::with_capacity(legal_moves as usize);
        for _ in 0..legal_moves {
            let mut ml = String::new();
            io::stdin().read_line(&mut ml).unwrap();
            let ms = ml.trim();
            if let Some(m) = parse_move(ms) {
                legal.push(m);
            }
        }

        // Start the clock now (input has been read). Budgets are deliberately
        // well under the limits (1000 / 100 ms) to leave margin for the
        // deadline-check granularity, output flushing, and platform jitter:
        //   first turn: 800 ms   (limit 1000)
        //   later:       50 ms   (limit 100)
        let start = Instant::now();
        let budget = if first_turn {
            std::time::Duration::from_millis(800)
        } else {
            std::time::Duration::from_millis(75)
        };

        let mut chosen = searcher.search(&mut board, start, budget);

        // Safety: ensure our move is in the contest's legal list. If our
        // generator and theirs ever disagree, fall back to their first move.
        if !legal.is_empty() && !legal.contains(&chosen) {
            eprintln!("chosen move not in legal list; falling back");
            chosen = legal[0];
        }
        if chosen == 0 && !legal.is_empty() {
            chosen = legal[0];
        }

        // Apply our own move to keep the board in sync for next turn.
        board.apply(chosen);

        println!("{} go", move_to_string(chosen));
        first_turn = false;
    }
}
