/*
 * Adapted from FinalSeg and CharacterUtil in jieba-analysis 1.0.2 (Huaban and contributors),
 * licensed under Apache-2.0. See META-INF/LICENSE-jieba-analysis.txt.
 * NovaBot reads the original gzip-wrapped model and uses two score rows with array backtracking.
 */
package org.frostnova.nova.bilibili.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Jieba's hidden Markov model for cutting runs of single-character words. */
final class JiebaHmm {
    private static final String RESOURCE = "/jieba/prob_emit.txt.gz";
    private static final Pattern OTHER_WORD = Pattern.compile("(\\d+\\.\\d+|[a-zA-Z0-9]+)");
    private static final double MIN_SCORE = -3.14e100;

    private static final byte BEGIN = 0;
    private static final byte MIDDLE = 1;
    private static final byte END = 2;
    private static final byte SINGLE = 3;
    private static final int STATE_COUNT = 4;
    private static final double[] START = {-0.26268660809250016, MIN_SCORE, MIN_SCORE, -1.4652633398537678};

    // Both tables are indexed by destination state (B, M, E, S), then by original predecessor order.
    private static final byte[][] PREDECESSORS = {{END, SINGLE}, {MIDDLE, BEGIN}, {BEGIN, MIDDLE}, {SINGLE, END}};
    private static final double[][] TRANSITIONS = {
            {-0.5897149736854513, -0.7211965654669841},
            {-1.2603623820268226, -0.916290731874155},
            {-0.510825623765990, -0.33344856811948514},
            {-0.6658631448798212, -0.8085250474669937}
    };

    private final List<Map<Character, Double>> emissions;

    private JiebaHmm(List<Map<Character, Double>> emissions) {
        this.emissions = emissions;
    }

    static JiebaHmm load() {
        InputStream input = JiebaHmm.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new IllegalStateException("Missing jieba model " + RESOURCE);
        }
        long started = System.nanoTime();
        List<Map<Character, Double>> emissions = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        try (input; BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(input, 8192), StandardCharsets.UTF_8))) {
            Map<Character, Double> values = null;
            for (String line; (line = reader.readLine()) != null; ) {
                char label = line.charAt(0);
                if (line.length() == 1) {
                    values = emissions.get(switch (label) {
                        case 'B' -> BEGIN;
                        case 'M' -> MIDDLE;
                        case 'E' -> END;
                        case 'S' -> SINGLE;
                        default -> throw new IllegalStateException("Unknown jieba model state " + label);
                    });
                } else {
                    values.put(label, Double.parseDouble(line.substring(2)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read jieba model " + RESOURCE, e);
        }
        System.out.println(String.format(Locale.getDefault(), "model load finished, time elapsed %d ms.",
                (System.nanoTime() - started) / 1_000_000));
        return new JiebaHmm(emissions);
    }

    void cut(String text, List<String> tokens) {
        for (int start = 0; start < text.length(); ) {
            boolean chinese = isChinese(text.charAt(start));
            int end = start + 1;
            while (end < text.length() && isChinese(text.charAt(end)) == chinese) {
                end++;
            }
            String run = text.substring(start, end);
            if (chinese) {
                cutChinese(run, tokens);
            } else {
                cutOther(run, tokens);
            }
            start = end;
        }
    }

    private static boolean isChinese(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FA5;
    }

    private void cutChinese(String text, List<String> tokens) {
        double[] previousScores = new double[STATE_COUNT];
        double[] scores = new double[STATE_COUNT];
        byte[][] backLinks = new byte[STATE_COUNT][text.length()];
        for (int state = 0; state < STATE_COUNT; state++) {
            previousScores[state] = START[state] + emission(state, text.charAt(0));
        }
        for (int i = 1; i < text.length(); i++) {
            for (int state = 0; state < STATE_COUNT; state++) {
                double emission = emission(state, text.charAt(i));
                byte first = PREDECESSORS[state][0];
                byte second = PREDECESSORS[state][1];
                double firstScore = TRANSITIONS[state][0] + (emission + previousScores[first]);
                double secondScore = TRANSITIONS[state][1] + (emission + previousScores[second]);
                // Jieba takes the second predecessor on a tie, preserving the order of the additions.
                boolean useSecond = firstScore <= secondScore;
                scores[state] = useSecond ? secondScore : firstScore;
                backLinks[state][i] = useSecond ? second : first;
            }
            double[] swap = previousScores;
            previousScores = scores;
            scores = swap;
        }

        byte state = previousScores[END] < previousScores[SINGLE] ? SINGLE : END;
        byte[] path = new byte[text.length()];
        for (int i = path.length - 1; i >= 0; i--) {
            path[i] = state;
            state = backLinks[state][i];
        }
        int begin = 0;
        int next = 0;
        for (int i = 0; i < path.length; i++) {
            switch (path[i]) {
                case BEGIN -> begin = i;
                case END -> {
                    tokens.add(text.substring(begin, i + 1));
                    next = i + 1;
                }
                case SINGLE -> {
                    tokens.add(text.substring(i, i + 1));
                    next = i + 1;
                }
                default -> { }
            }
        }
        if (next < text.length()) {
            tokens.add(text.substring(next));
        }
    }

    private double emission(int state, char ch) {
        Double value = emissions.get(state).get(ch);
        return value == null ? MIN_SCORE : value;
    }

    private static void cutOther(String text, List<String> tokens) {
        Matcher matcher = OTHER_WORD.matcher(text);
        int offset = 0;
        while (matcher.find()) {
            if (matcher.start() > offset) {
                tokens.add(text.substring(offset, matcher.start()));
            }
            tokens.add(matcher.group());
            offset = matcher.end();
        }
        if (offset < text.length()) {
            tokens.add(text.substring(offset));
        }
    }
}
