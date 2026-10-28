package logsift.core.diff;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which column of the left file is compared with which of the right: by name (case and spaces
 * around don't count), changed by hand if needed. Columns in no pair are only in one file.
 */
public record ColumnMap(List<String> leftNames, List<String> rightNames, List<Pair> pairs) {

    /** Left column {@code left} is compared with right column {@code right}. */
    public record Pair(int left, int right) {}

    public ColumnMap {
        leftNames = List.copyOf(leftNames);
        rightNames = List.copyOf(rightNames);
        pairs = List.copyOf(pairs);
    }

    /** How names are matched. */
    public static String normalize(String name) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        return n.startsWith("﻿") ? n.substring(1).strip() : n;
    }

    /** Columns paired by name, in the left file's order. */
    public static ColumnMap byName(List<String> left, List<String> right) {
        List<Pair> pairs = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int l = 0; l < left.size(); l++) {
            String n = normalize(left.get(l));
            for (int r = 0; r < right.size(); r++) {
                if (!used.contains(r) && normalize(right.get(r)).equals(n)) {
                    pairs.add(new Pair(l, r));
                    used.add(r);
                    break;
                }
            }
        }
        return new ColumnMap(left, right, pairs);
    }

    /** This map with left column {@code left} compared with right column {@code right} instead. */
    public ColumnMap with(int left, int right) {
        List<Pair> out = new ArrayList<>();
        boolean added = false;
        for (Pair p : pairs) {
            if (p.left() == left || p.right() == right) {
                if (!added && p.left() == left) {
                    out.add(new Pair(left, right)); // keeps its place
                    added = true;
                }
                continue;
            }
            out.add(p);
        }
        if (!added) {
            int at = 0;
            while (at < out.size() && out.get(at).left() < left) {
                at++;
            }
            out.add(at, new Pair(left, right));
        }
        return new ColumnMap(leftNames, rightNames, out);
    }

    /** This map without the pair for left column {@code left}. */
    public ColumnMap withoutLeft(int left) {
        return new ColumnMap(leftNames, rightNames, pairs.stream().filter(p -> p.left() != left).toList());
    }

    /** The pair's name: the left one's, or "left / right" when they are not the same name. */
    public String name(int pair) {
        Pair p = pairs.get(pair);
        String l = leftNames.get(p.left());
        String r = rightNames.get(p.right());
        return normalize(l).equals(normalize(r)) ? l : l + " / " + r;
    }

    /** The pair with this name (as {@link #name} gives it, or the left or right name), or -1. */
    public int pairNamed(String name) {
        String n = normalize(name);
        for (int i = 0; i < pairs.size(); i++) {
            if (normalize(name(i)).equals(n)) {
                return i;
            }
        }
        for (int i = 0; i < pairs.size(); i++) {
            if (normalize(leftNames.get(pairs.get(i).left())).equals(n)
                    || normalize(rightNames.get(pairs.get(i).right())).equals(n)) {
                return i;
            }
        }
        return -1;
    }

    /** The pair of left column {@code left}, or -1. */
    public int pairOfLeft(int left) {
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).left() == left) {
                return i;
            }
        }
        return -1;
    }

    /** Left columns compared with nothing. */
    public List<Integer> leftOnly() {
        Set<Integer> used = new HashSet<>();
        pairs.forEach(p -> used.add(p.left()));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < leftNames.size(); i++) {
            if (!used.contains(i)) {
                out.add(i);
            }
        }
        return out;
    }

    /** Right columns compared with nothing. */
    public List<Integer> rightOnly() {
        Set<Integer> used = new HashSet<>();
        pairs.forEach(p -> used.add(p.right()));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < rightNames.size(); i++) {
            if (!used.contains(i)) {
                out.add(i);
            }
        }
        return out;
    }
}
