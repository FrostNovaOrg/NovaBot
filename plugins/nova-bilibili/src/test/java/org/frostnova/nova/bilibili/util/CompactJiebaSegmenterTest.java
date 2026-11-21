package org.frostnova.nova.bilibili.util;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.WordDictionary;
import com.huaban.analysis.jieba.viterbi.FinalSeg;
import org.frostnova.nova.core.lang.StringUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactJiebaSegmenterTest {
    private static final JiebaSegmenter REFERENCE = new JiebaSegmenter();
    private static final CompactJiebaSegmenter SEGMENTER = new CompactJiebaSegmenter();

    @Test
    void matchesSyntheticDanmu() {
        List<String> messages = List.of(
                "主播这段副歌太燃了", "耳机党表示低音特别清楚", "键盘声跟鼓点对上了", "这首原创什么时候发布",
                "龙猫抱枕是不是换位置了", "晚饭煮了砂锅米线", "刚回家就看到抽奖", "猫猫把水杯推到桌边了",
                "画面的蓝紫渐变好漂亮", "剪辑回放能不能加上字幕", "这里的手绘转场太丝滑", "弹幕在排队猜歌名",
                "新版本竟然能联机钓鱼", "开局捡了三个治疗包", "复活点旁边有隐藏宝箱", "打完这一轮就去泡面",
                "蹲一个无伤通关", "对面打野怎么在草丛", "飞船刚才擦过小行星", "电量只剩百分之三",
                "这局交给喵叽咔噜哒噗", "刚才麦克风冒出啵噜咕喵", "恭喜解锁咕嘟蹦咔兽", "画面闪过一只啾哩喵呜鸭");
        List<String> endings = List.of(
                "！！！", "哈哈哈哈", "，我也想试试", "（新来的）", "！刷新一下就好了", "诶？？", "233333",
                "，这回稳了", "～", "💡", "，回放记得保留这一段", "。晚上还有吗", "，屏幕前笑出声", "；下一轮继续！",
                "，刚才有点卡", "，小窗也能看清", "[赞]", "，建议再来一遍", "，给个特写吧", "！YYDS", "，弹幕护体",
                "。直播间各位晚安", "。我这边延迟2秒", "（正在加载）", "，录屏进度99.9%了");
        for (String message : messages) {
            for (String ending : endings) {
                assertSameWords(message + ending);
            }
        }
    }

    @Test
    void matchesSeededDictionaryCombinations() {
        List<String> words = new ArrayList<>(WordDictionary.getInstance().freqs.keySet());
        words.sort(Comparator.naturalOrder());
        Random random = new Random(73498L);
        for (int i = 0; i < 5_000; i++) {
            StringBuilder text = new StringBuilder();
            int wordCount = 2 + random.nextInt(8);
            for (int j = 0; j < wordCount; j++) {
                text.append(words.get(random.nextInt(words.size())));
            }
            assertSameWords(text.toString());
        }
    }

    @Test
    void matchesEdgeInputs() {
        List<String> texts = List.of(
                "", " \t\r\n　", "！？……、#-&+._\t\n", "❤️🇨🇳👨‍👩‍👧🥳",
                "𠮷野家𪚥汉字𠀀\ud800\udfff\ud800", "\u0000\b\u200b\u2028",
                "鿐", "鿿", "啊鿐啊", "哈鿿哈", "\u9fa5\u9fa6",
                "主播DOG开了3.14倍速 C++ C# AT&T 原神v2.0 ＡＢＣ１２３",
                "B超 b超 4S店 4s店 江南Style 江南style I盘 IP地址", "研究生命起源，乒乓球拍卖完了",
                "喵叽咔噜哒噗咕嘟蹦咔兽", "主播唱歌好听GG🥳！".repeat(4_000),
                "龘麤靐".repeat(10_000), "X".repeat(65_536));
        for (String text : texts) {
            assertSameWords(text);
        }
        assertTrue(DanmuWordUtil.extractWords(null).isEmpty());
    }

    @Test
    void matchesAllDictionaryScoresBitForBit() {
        WordDictionary reference = WordDictionary.getInstance();
        CompactJiebaDictionary dictionary = CompactJiebaDictionary.load();
        assertEquals(349_040, reference.freqs.size());
        for (Map.Entry<String, Double> entry : reference.freqs.entrySet()) {
            String word = entry.getKey();
            assertEquals(Double.doubleToRawLongBits(entry.getValue()), Double.doubleToRawLongBits(dictionary.score(word)),
                    () -> "Dictionary score for " + word);
        }
        assertEquals(Double.doubleToRawLongBits(reference.getFreq("🪼")),
                Double.doubleToRawLongBits(dictionary.minimumScore()));
        assertTrue(Double.isNaN(dictionary.score("🪼")));
    }

    @Test
    void matchesTheHmmOnSeededRuns() {
        FinalSeg reference = FinalSeg.getInstance();
        JiebaHmm hmm = JiebaHmm.load();
        Random random = new Random(22481L);
        for (int i = 0; i < 1_024; i++) {
            int length = 2 + random.nextInt(80);
            StringBuilder text = new StringBuilder(length);
            for (int j = 0; j < length; j++) {
                int ch = random.nextInt(5) == 0
                        ? random.nextInt(128)
                        : 0x4E00 + random.nextInt(0x9FA5 - 0x4E00 + 1);
                text.append((char) ch);
            }
            String input = text.toString();
            List<String> expected = new ArrayList<>();
            List<String> actual = new ArrayList<>();
            reference.cut(input, expected);
            hmm.cut(input, actual);
            assertEquals(expected, actual, () -> "HMM segmentation for " + input);
        }
    }

    @Test
    void keepsTheCompleteReleasedResources() throws IOException {
        for (String file : List.of("dict.txt", "prob_emit.txt")) {
            try (InputStream original = JiebaSegmenter.class.getResourceAsStream("/" + file);
                 InputStream bundled = getClass().getResourceAsStream("/jieba/" + file + ".gz");
                 GZIPInputStream decoded = new GZIPInputStream(bundled)) {
                assertArrayEquals(original.readAllBytes(), decoded.readAllBytes(), file);
            }
        }
    }

    private static void assertSameWords(String text) {
        assertEquals(REFERENCE.sentenceProcess(text), SEGMENTER.sentenceProcess(text),
                () -> "Segmentation for " + StringUtil.getOmitString(text, 100));
    }
}
