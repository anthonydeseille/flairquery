package io.github.anthonydeseille.flairquery.analyzer.fix;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified diffs in the {@code git diff} format ({@code a/} and {@code b/} prefixes, 3 lines of context), so a
 * generated fix applies with {@code git apply} exactly like a hand-written one. Line-based LCS: meant for
 * source files (a few thousand lines at most), not for data.
 */
public final class UnifiedDiff {

    static final int CONTEXT = 3;

    private UnifiedDiff() { }

    /** Diff of one file; {@code before} null for a new file. Empty when nothing changed. */
    public static String of(String path, String before, String after) {
        if (before != null && before.equals(after)) return "";
        StringBuilder out = new StringBuilder("diff --git a/" + path + " b/" + path + "\n");
        if (before == null) {
            out.append("new file mode 100644\n--- /dev/null\n");
        } else {
            out.append("--- a/").append(path).append('\n');
        }
        out.append("+++ b/").append(path).append('\n');
        hunks(lines(before == null ? "" : before), lines(after), out);
        return out.toString();
    }

    /** Lines with their terminator: a last line without newline differs from the same text with one. */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }

    private record Op(char kind, int a, int b) { }

    private static void hunks(List<String> a, List<String> b, StringBuilder out) {
        List<Op> ops = script(a, b);
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind() == ' ') {
                i++;
                continue;
            }
            // a hunk: from CONTEXT lines before the first change to CONTEXT lines after the last change that
            // is less than 2 * CONTEXT lines away from the next one
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            int lastChange = i;
            while (end < ops.size()) {
                if (ops.get(end).kind() != ' ') lastChange = end;
                else if (end - lastChange > 2 * CONTEXT) break;
                end++;
            }
            end = Math.min(ops.size(), lastChange + CONTEXT + 1);
            int aCount = 0;
            int bCount = 0;
            for (int k = start; k < end; k++) {
                if (ops.get(k).kind() != '+') aCount++;
                if (ops.get(k).kind() != '-') bCount++;
            }
            // a '+' does not move the position in a, a '-' not in b: the first op holds both positions
            out.append("@@ -").append(range(ops.get(start).a(), aCount)).append(" +")
                    .append(range(ops.get(start).b(), bCount)).append(" @@\n");
            for (int k = start; k < end; k++) {
                Op op = ops.get(k);
                String line = op.kind() == '+' ? b.get(op.b()) : a.get(op.a());
                out.append(op.kind()).append(line);
                if (!line.endsWith("\n")) out.append("\n\\ No newline at end of file\n");
            }
            i = end;
        }
    }

    /** {@code start,count} (1-based); an empty range names the line before it ({@code 0,0} at the top). */
    private static String range(int linesBefore, int count) {
        if (count == 0) return linesBefore + ",0";
        return (linesBefore + 1) + (count == 1 ? "" : "," + count);
    }

    /** Edit script from the LCS table: ' ' kept, '-' removed from a, '+' added from b. */
    private static List<Op> script(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int x = n - 1; x >= 0; x--) {
            for (int y = m - 1; y >= 0; y--) {
                lcs[x][y] = a.get(x).equals(b.get(y)) ? lcs[x + 1][y + 1] + 1 : Math.max(lcs[x + 1][y], lcs[x][y + 1]);
            }
        }
        List<Op> ops = new ArrayList<>();
        int x = 0;
        int y = 0;
        while (x < n && y < m) {
            if (a.get(x).equals(b.get(y))) {
                ops.add(new Op(' ', x++, y++));
            } else if (lcs[x + 1][y] >= lcs[x][y + 1]) {
                ops.add(new Op('-', x++, y));
            } else {
                ops.add(new Op('+', x, y++));
            }
        }
        while (x < n) ops.add(new Op('-', x++, y));
        while (y < m) ops.add(new Op('+', x, y++));
        return ops;
    }
}
