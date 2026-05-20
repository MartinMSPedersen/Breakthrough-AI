import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Persist a played game to a file under saves/.
 *
 * Filenames are generated from the local timestamp at save time:
 *   saves/breakthrough-YYYY-MM-DD_HH-MM-SS.game
 *
 * The body is plain text, compatible with GameReplay: a header of '#' comments
 * (timestamp, move count, result, final FEN) followed by numbered move pairs.
 */
public final class GameWriter {

    private GameWriter() {}

    public static final Path DEFAULT_DIR = Paths.get("saves");

    private static final DateTimeFormatter FNAME_FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter HEADER_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static Path save(List<Move> moves, String resultLine, String finalFen) throws IOException {
        return save(moves, resultLine, finalFen, DEFAULT_DIR);
    }

    public static Path save(List<Move> moves, String resultLine, String finalFen, Path dir) throws IOException {
        Files.createDirectories(dir);
        LocalDateTime now = LocalDateTime.now();
        Path out = dir.resolve("breakthrough-" + now.format(FNAME_FMT) + ".game");

        StringBuilder sb = new StringBuilder();
        sb.append("# Breakthrough game (8x8)\n");
        sb.append("# Saved:     ").append(now.format(HEADER_FMT)).append('\n');
        sb.append("# Plies:     ").append(moves.size()).append('\n');
        sb.append("# Result:    ").append(resultLine).append('\n');
        if (finalFen != null) {
            sb.append("# Final FEN: ").append(finalFen).append('\n');
        }
        sb.append('\n');

        // Numbered move pairs: " 1. b2b3 g7g6"
        for (int i = 0; i < moves.size(); i += 2) {
            int moveNum = i / 2 + 1;
            sb.append(String.format("%3d. %s", moveNum, moves.get(i).toAlgebraic()));
            if (i + 1 < moves.size()) {
                sb.append(' ').append(moves.get(i + 1).toAlgebraic());
            }
            sb.append('\n');
        }

        Files.writeString(out, sb.toString());
        return out;
    }
}
