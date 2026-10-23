package org.frostnova.nova.core.model;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TextWithStyleTest {
    @Test
    void twoArgumentConstructorExposesBlackForColorReaders() {
        var text = new TextWithStyle("text", 24);

        assertEquals(Color.BLACK, text.getColor());
        assertEquals(Color.BLACK.getRGB(), text.getColor().getRGB());
    }

    @Test
    void noArgumentConstructorLeavesColorUnset() {
        var text = new TextWithStyle();
        text.setText("text");
        text.setSize(24);

        assertNull(text.getColor());
    }

    @Test
    void constructorsAndSetterExposeSuppliedColors() {
        var texts = List.of(new TextWithStyle(), new TextWithStyle("text", 24),
                new TextWithStyle("text", 24, Color.BLUE),
                new TextWithStyle("text", 24, Color.BLUE, Font.BOLD),
                new TextWithStyle("text", new Font(Font.SANS_SERIF, Font.PLAIN, 24),
                        24, Color.BLUE, Font.BOLD));
        for (var text : texts.subList(2, texts.size())) {
            assertEquals(Color.BLUE, text.getColor());
        }
        for (var text : texts) {
            text.setColor(Color.RED);
            assertEquals(Color.RED, text.getColor());
        }
    }
}
