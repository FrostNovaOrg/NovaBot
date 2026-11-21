/*
 * Sentence segmentation adapted from jieba-analysis 1.0.2 (Huaban and contributors),
 * licensed under Apache-2.0. See META-INF/LICENSE-jieba-analysis.txt.
 * NovaBot scores routes directly over an array trie and delegates to the jieba HMM port.
 */
package org.frostnova.nova.bilibili.util;

import java.util.ArrayList;
import java.util.List;

/** The jieba sentenceProcess mode used by danmu word clouds. */
final class CompactJiebaSegmenter {
    private static final CompactJiebaDictionary DICTIONARY = CompactJiebaDictionary.load();
    private static final JiebaHmm HMM = JiebaHmm.load();

    List<String> sentenceProcess(String sentence) {
        int length = sentence.length();
        double[] routeScores = new double[length + 1];
        int[] nextStarts = new int[length];
        for (int start = length - 1; start >= 0; start--) {
            int node = CompactJiebaDictionary.ROOT;
            int bestNext = 0;
            double bestScore = 0;
            for (int end = start; end < length; end++) {
                node = DICTIONARY.findChild(node, sentence.charAt(end));
                if (node < 0) {
                    break;
                }
                double score = DICTIONARY.score(node);
                if (Double.isNaN(score)) {
                    continue;
                }
                double candidate = score + routeScores[end + 1];
                // In an exact tie, jieba keeps the first (shortest) candidate at this offset.
                if (bestNext == 0 || bestScore < candidate) {
                    bestNext = end + 1;
                    bestScore = candidate;
                }
            }
            if (bestNext == 0) {
                bestNext = start + 1;
                bestScore = DICTIONARY.minimumScore() + routeScores[bestNext];
            }
            nextStarts[start] = bestNext;
            routeScores[start] = bestScore;
        }

        List<String> tokens = new ArrayList<>();
        int pending = 0;
        for (int start = 0; start < length; start = nextStarts[start]) {
            int next = nextStarts[start];
            if (next - start > 1) {
                addSingles(sentence, pending, start, tokens);
                tokens.add(sentence.substring(start, next));
                pending = next;
            }
        }
        addSingles(sentence, pending, length, tokens);
        return tokens;
    }

    private static void addSingles(String sentence, int start, int end, List<String> tokens) {
        if (start == end) {
            return;
        }
        String word = sentence.substring(start, end);
        if (end - start == 1 || !Double.isNaN(DICTIONARY.score(word))) {
            tokens.add(word);
        } else {
            HMM.cut(word, tokens);
        }
    }
}
