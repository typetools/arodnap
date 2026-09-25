package org.arodnap.engine.patch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A port of Python's {@code difflib.SequenceMatcher} (no junk function, autojunk on), so that
 * patches match the ones Arodnap's Python version made, hunk for hunk.
 */
final class SequenceMatcher {
    /** One opcode: how to turn {@code a[i1:i2]} into {@code b[j1:j2]}. */
    record Opcode(String tag, int i1, int i2, int j1, int j2) {}

    private record Match(int a, int b, int size) {}

    private final List<String> a;
    private final List<String> b;
    private final Map<String, List<Integer>> b2j = new HashMap<>();

    SequenceMatcher(List<String> a, List<String> b) {
        this.a = a;
        this.b = b;
        for (int j = 0; j < b.size(); j++) {
            b2j.computeIfAbsent(b.get(j), key -> new ArrayList<>()).add(j);
        }
        // difflib's autojunk: in sequences of 200+ items, items making up over 1% are "popular"
        // and are not used to start matches (they can still extend one).
        int n = b.size();
        if (n >= 200) {
            int ntest = n / 100 + 1;
            b2j.values().removeIf(indices -> indices.size() > ntest);
        }
    }

    private Match findLongestMatch(int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            for (int j : b2j.getOrDefault(a.get(i), List.of())) {
                if (j < blo) {
                    continue;
                }
                if (j >= bhi) {
                    break;
                }
                int k = j2len.getOrDefault(j - 1, 0) + 1;
                newj2len.put(j, k);
                if (k > bestsize) {
                    besti = i - k + 1;
                    bestj = j - k + 1;
                    bestsize = k;
                }
            }
            j2len = newj2len;
        }
        // No junk function, so only the non-junk extensions apply; popular items extend too.
        while (besti > alo && bestj > blo && a.get(besti - 1).equals(b.get(bestj - 1))) {
            besti--;
            bestj--;
            bestsize++;
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi && a.get(besti + bestsize).equals(b.get(bestj + bestsize))) {
            bestsize++;
        }
        return new Match(besti, bestj, bestsize);
    }

    private List<Match> matchingBlocks() {
        List<Match> blocks = new ArrayList<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.push(new int[] {0, a.size(), 0, b.size()});
        while (!queue.isEmpty()) {
            int[] range = queue.pop();
            int alo = range[0], ahi = range[1], blo = range[2], bhi = range[3];
            Match match = findLongestMatch(alo, ahi, blo, bhi);
            if (match.size > 0) {
                blocks.add(match);
                if (alo < match.a && blo < match.b) {
                    queue.push(new int[] {alo, match.a, blo, match.b});
                }
                if (match.a + match.size < ahi && match.b + match.size < bhi) {
                    queue.push(new int[] {match.a + match.size, ahi, match.b + match.size, bhi});
                }
            }
        }
        blocks.sort(Comparator.comparingInt(Match::a).thenComparingInt(Match::b).thenComparingInt(Match::size));
        List<Match> collapsed = new ArrayList<>();
        int i1 = 0, j1 = 0, k1 = 0;
        for (Match block : blocks) {
            if (i1 + k1 == block.a && j1 + k1 == block.b) {
                k1 += block.size;
            } else {
                if (k1 > 0) {
                    collapsed.add(new Match(i1, j1, k1));
                }
                i1 = block.a;
                j1 = block.b;
                k1 = block.size;
            }
        }
        if (k1 > 0) {
            collapsed.add(new Match(i1, j1, k1));
        }
        collapsed.add(new Match(a.size(), b.size(), 0));
        return collapsed;
    }

    List<Opcode> opcodes() {
        List<Opcode> answer = new ArrayList<>();
        int i = 0, j = 0;
        for (Match block : matchingBlocks()) {
            String tag = i < block.a && j < block.b ? "replace" : i < block.a ? "delete" : j < block.b ? "insert" : "";
            if (!tag.isEmpty()) {
                answer.add(new Opcode(tag, i, block.a, j, block.b));
            }
            i = block.a + block.size;
            j = block.b + block.size;
            if (block.size > 0) {
                answer.add(new Opcode("equal", block.a, i, block.b, j));
            }
        }
        return answer;
    }

    /** difflib's get_grouped_opcodes: changes with up to {@code n} lines of context, split into hunks. */
    List<List<Opcode>> groupedOpcodes(int n) {
        List<Opcode> codes = new ArrayList<>(opcodes());
        if (codes.isEmpty()) {
            codes.add(new Opcode("equal", 0, 1, 0, 1));
        }
        Opcode first = codes.get(0);
        if (first.tag.equals("equal")) {
            codes.set(0, new Opcode("equal", Math.max(first.i1, first.i2 - n), first.i2, Math.max(first.j1, first.j2 - n), first.j2));
        }
        Opcode last = codes.get(codes.size() - 1);
        if (last.tag.equals("equal")) {
            codes.set(codes.size() - 1, new Opcode("equal", last.i1, Math.min(last.i2, last.i1 + n), last.j1, Math.min(last.j2, last.j1 + n)));
        }
        List<List<Opcode>> groups = new ArrayList<>();
        List<Opcode> group = new ArrayList<>();
        for (Opcode code : codes) {
            int i1 = code.i1, j1 = code.j1;
            if (code.tag.equals("equal") && code.i2 - i1 > 2 * n) {
                group.add(new Opcode("equal", i1, Math.min(code.i2, i1 + n), j1, Math.min(code.j2, j1 + n)));
                groups.add(group);
                group = new ArrayList<>();
                i1 = Math.max(i1, code.i2 - n);
                j1 = Math.max(j1, code.j2 - n);
            }
            group.add(new Opcode(code.tag, i1, code.i2, j1, code.j2));
        }
        if (!group.isEmpty() && !(group.size() == 1 && group.get(0).tag.equals("equal"))) {
            groups.add(group);
        }
        return groups;
    }
}
