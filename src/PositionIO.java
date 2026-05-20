import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Load a position from a FEN-like file. Lines starting with '#' or anything
 * after a '#' on a line are ignored. The remaining non-empty content is joined
 * and parsed as a FEN string.
 *
 * Example position file:
 *   # Breakthrough starting position
 *   OOOOOOOO/OOOOOOOO/8/8/8/8/XXXXXXXX/XXXXXXXX W
 */
public final class PositionIO {
    private PositionIO() {}

    public static Board load(Path p) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(p)) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            String t = line.trim();
            if (!t.isEmpty()) { sb.append(t).append(' '); }
        }
        return Board.fromFen(sb.toString().trim());
    }
}
