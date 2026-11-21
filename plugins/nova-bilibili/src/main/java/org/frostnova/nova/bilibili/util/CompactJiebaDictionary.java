/*
 * Dictionary loading adapted from jieba-analysis 1.0.2.
 * Copyright (C) 2013 Huaban Inc
 * Licensed under Apache-2.0. See META-INF/LICENSE-jieba-analysis.txt.
 * NovaBot stores the built-in dictionary in arrays and calculates scores at terminal nodes.
 */
package org.frostnova.nova.bilibili.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/** Immutable UTF-16 trie. Each node's children occupy a sorted, contiguous range. */
final class CompactJiebaDictionary {
    static final int ROOT = 0;
    private static final String RESOURCE = "/jieba/dict.txt.gz";

    private final char[] labels;
    private final int[] childStarts;
    private final double[] scores;
    private final double minimumScore;

    private CompactJiebaDictionary(char[] labels, int[] childStarts, double[] scores, double minimumScore) {
        this.labels = labels;
        this.childStarts = childStarts;
        this.scores = scores;
        this.minimumScore = minimumScore;
    }

    static CompactJiebaDictionary load() {
        InputStream input = CompactJiebaDictionary.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new IllegalStateException("Missing jieba dictionary " + RESOURCE);
        }
        try (input; BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(input, 8192), StandardCharsets.UTF_8))) {
            long started = System.nanoTime();
            Builder builder = new Builder();
            Locale locale = Locale.getDefault();
            for (String line; (line = reader.readLine()) != null; ) {
                int wordEnd = 0;
                while (wordEnd < line.length() && !isSeparator(line.charAt(wordEnd))) {
                    wordEnd++;
                }
                int frequencyStart = wordEnd;
                while (frequencyStart < line.length() && isSeparator(line.charAt(frequencyStart))) {
                    frequencyStart++;
                }
                if (frequencyStart == line.length()) {
                    continue;
                }
                int frequencyEnd = frequencyStart;
                while (frequencyEnd < line.length() && !isSeparator(line.charAt(frequencyEnd))) {
                    frequencyEnd++;
                }
                // All counts in the bundled file are decimal integers, exactly representable as doubles.
                int frequency = Integer.parseInt(line, frequencyStart, frequencyEnd, 10);
                builder.add(line.substring(0, wordEnd).trim().toLowerCase(locale), frequency);
            }
            CompactJiebaDictionary dictionary = builder.finish();
            System.out.println(String.format(locale, "main dict load finished, time elapsed %d ms",
                    (System.nanoTime() - started) / 1_000_000));
            return dictionary;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read jieba dictionary " + RESOURCE, e);
        }
    }

    private static boolean isSeparator(char ch) {
        return ch == ' ' || ch == '\t';
    }

    int findChild(int node, char ch) {
        return Arrays.binarySearch(labels, childStarts[node], childStarts[node + 1], ch);
    }

    /** Returns NaN when the node is only a prefix. */
    double score(int node) {
        return scores[node];
    }

    double score(String word) {
        int node = ROOT;
        for (int i = 0; i < word.length(); i++) {
            node = findChild(node, word.charAt(i));
            if (node < 0) {
                return Double.NaN;
            }
        }
        return score(node);
    }

    double minimumScore() {
        return minimumScore;
    }

    private static final class Builder {
        // The bundled dictionary has just under half a million nodes including prefixes.
        private static final int INITIAL_CAPACITY = 1 << 19;
        private char[] labels = new char[INITIAL_CAPACITY];
        private int[] firstChildren = new int[INITIAL_CAPACITY];
        private int[] nextSiblings = new int[INITIAL_CAPACITY];
        private int[] frequencies = new int[INITIAL_CAPACITY]; // Zero marks a node without a word.
        private int size = 1;
        private double total;

        void add(String word, int frequency) {
            int node = ROOT;
            for (int i = 0; i < word.length(); i++) {
                node = findOrAddChild(node, word.charAt(i));
            }
            // Duplicate keys keep the last count, but every row contributes to the total.
            frequencies[node] = frequency;
            total += frequency;
        }

        private int findOrAddChild(int parent, char label) {
            // Descending order puts most new children first because the resource is nearly sorted.
            int previous = 0;
            int child = firstChildren[parent];
            while (child != 0 && labels[child] > label) {
                previous = child;
                child = nextSiblings[child];
            }
            if (child != 0 && labels[child] == label) {
                return child;
            }

            if (size == labels.length) {
                int capacity = size + size / 2;
                labels = Arrays.copyOf(labels, capacity);
                firstChildren = Arrays.copyOf(firstChildren, capacity);
                nextSiblings = Arrays.copyOf(nextSiblings, capacity);
                frequencies = Arrays.copyOf(frequencies, capacity);
            }
            int node = size++;
            labels[node] = label;
            nextSiblings[node] = child;
            if (previous == 0) {
                firstChildren[parent] = node;
            } else {
                nextSiblings[previous] = node;
            }
            return node;
        }

        CompactJiebaDictionary finish() {
            char[] sortedLabels = new char[size];
            int[] starts = new int[size + 1];
            double[] scores = new double[size];
            int[] sourceNodes = new int[size];
            int next = 1;
            double min = Double.MAX_VALUE;
            for (int node = 0; node < size; node++) {
                int source = sourceNodes[node];
                sortedLabels[node] = labels[source];
                int frequency = frequencies[source];
                if (frequency == 0) {
                    scores[node] = Double.NaN;
                } else {
                    scores[node] = Math.log(frequency / total);
                    min = Math.min(min, scores[node]);
                }

                starts[node] = next;
                for (int child = firstChildren[source]; child != 0; child = nextSiblings[child]) {
                    next++;
                }
                // Visit nodes breadth-first, writing each sibling range in ascending order.
                int slot = next;
                for (int child = firstChildren[source]; child != 0; child = nextSiblings[child]) {
                    sourceNodes[--slot] = child;
                }
            }
            starts[size] = next;
            return new CompactJiebaDictionary(sortedLabels, starts, scores, min);
        }
    }
}
