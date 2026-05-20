import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load a sequence of moves from a free-form game file.
 *
 * Any text after '#' on a line is treated as a comment. The remaining text is
 * scanned for tokens matching [a-h][1-8]-[a-h][1-8]. Move numbers, dots,
 * annotations etc. are ignored.
 *
 * Example file:
 *   # Sample game
 *   1. b2-b3 g7-g6
 *   2. c2-c3 f7-f6
 */
public final class GameReplay {
    private GameReplay() {}

    private static final Pattern MOVE_RE = Pattern.compile("[a-h][1-8][a-h][1-8]");

    public static List<Move> loadMoves(Path p) throws IOException {
        List<Move> moves = new ArrayList<>();
        for (String line : Files.readAllLines(p)) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            Matcher m = MOVE_RE.matcher(line);
            while (m.find()) moves.add(Move.parse(m.group()));
        }
        return moves;
    }
}
