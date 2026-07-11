# Breakthrough CodinGame bot — build helpers.
#
# The bot is a single std-only file (player.rs), so plain rustc is enough;
# no cargo project needed. `make` builds an optimized binary.

RUSTC ?= rustc
RUSTFLAGS ?= -O

BIN := player
SRC := player.rs

.PHONY: all run debug test clean help

# Optimized build (what you want for local strength/timing tests).
all: $(BIN)

$(BIN): $(SRC)
	$(RUSTC) $(RUSTFLAGS) $(SRC) -o $(BIN)

# Build with debug assertions and overflow checks — slower, catches mistakes.
debug: $(SRC)
	$(RUSTC) -g $(SRC) -o $(BIN)-debug

# Run the bot reading turns from stdin (type or pipe the contest input).
run: $(BIN)
	./$(BIN)

# Quick smoke test: first turn as the starting player. Sends "None" plus a
# legal-move list; expects a move + message on stdout within the time budget.
test: $(BIN)
	@printf 'None\n2\na2a3\nb2b3\n' | ./$(BIN) | head -1
	@echo "smoke test done (a single 'move message' line above = OK)"

clean:
	rm -f $(BIN) $(BIN)-debug

help:
	@echo "Targets:"
	@echo "  make         optimized build (rustc -O player.rs -o player)"
	@echo "  make debug   debug build with assertions -> player-debug"
	@echo "  make run     build and run, reading turns from stdin"
	@echo "  make test    build and run a first-turn smoke test"
	@echo "  make clean   remove binaries"
