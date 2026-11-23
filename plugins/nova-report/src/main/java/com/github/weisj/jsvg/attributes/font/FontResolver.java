/*
 * MIT License
 *
 * Copyright (c) 2021-2026 Jannis Weis
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */
package com.github.weisj.jsvg.attributes.font;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.text.AttributedCharacterIterator;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.github.weisj.jsvg.renderer.MeasureContext;
import com.github.weisj.jsvg.renderer.PlatformSupport;

/**
 * Adapted from JSVG 2.2.0 for the sketch subprocess, which loads this class before the JSVG jar.
 * Try the supplied font before initializing JSVG's system font cache.
 */
public final class FontResolver {
    private FontResolver() {}

    public static void clearFontCache() {
        FontCache.INSTANCE.cache.clear();
    }

    public static SVGFont resolve(MeasurableFontSpec fontSpec,
            MeasureContext measureContext, PlatformSupport platformSupport) {
        FontCache.CacheKey key = new FontCache.CacheKey(fontSpec, measureContext);
        return FontCache.INSTANCE.cache.computeIfAbsent(key,
                k -> resolveWithoutCache(fontSpec, measureContext, platformSupport));
    }

    public static SVGFont resolveWithoutCache(MeasurableFontSpec fontSpec,
            MeasureContext measureContext, PlatformSupport platformSupport) {
        FontStyle style = fontSpec.style();

        float weight = cssWeightToAwtWeight(fontSpec.currentWeight());
        float size = fontSpec.effectiveSize(measureContext);
        float stretch = fontSpec.stretch().orElseIfUnspecified(1).value();

        Map<AttributedCharacterIterator.Attribute, Object> attributes = new HashMap<>(5, 1f);
        attributes.put(TextAttribute.SIZE, size);
        attributes.put(TextAttribute.WEIGHT, weight);
        attributes.put(TextAttribute.WIDTH, stretch);

        if (style instanceof FontStyle.Normal) {
            attributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_REGULAR);
        } else if (style instanceof FontStyle.Italic) {
            attributes.put(TextAttribute.POSTURE, TextAttribute.POSTURE_OBLIQUE);
        } else {
            AffineTransform transform = style.transform();
            if (transform != null) attributes.put(TextAttribute.TRANSFORM, transform);
        }

        Font font = resolveFont(fontSpec, platformSupport, attributes);
        return new AWTSVGFont(font);
    }

    /** Resolves and sets the first matching font family based on {@code fontSpec.families}. */
    private static Font resolveFont(MeasurableFontSpec fontSpec,
            PlatformSupport platformSupport,
            Map<AttributedCharacterIterator.Attribute, Object> attributes) {
        for (String family : fontSpec.families()) {
            Font customFont = platformSupport.customFont(family);
            if (customFont != null) {
                // No FAMILY attribute: deriveFont discards the created font handle if it doesn't
                // match the font's name exactly and falls back to a system font lookup.
                return customFont.deriveFont(attributes);
            }
            if (FontFamiliesCache.INSTANCE.isSupportedFontFamily(family)) {
                attributes.put(TextAttribute.FAMILY, family);
                return new Font(attributes);
            }
        }
        // return default font if nothing was matched
        attributes.put(TextAttribute.FAMILY, platformSupport.fontFamily());
        return new Font(attributes);
    }

    private static float cssWeightToAwtWeight(float weight) {
        int normalWeight = PredefinedFontWeight.NORMAL_WEIGHT;
        float currentWeight = weight;
        if (currentWeight > normalWeight) {
            // The bold weight for css and awt differ. We compensate for this difference to ensure
            // that bold css fonts correspond to bold awt fonts, as this is the most commonly supported
            // font variation.
            float awtWeightCompensationFactor =
                    (TextAttribute.WEIGHT_BOLD * normalWeight) / PredefinedFontWeight.BOLD_WEIGHT;
            currentWeight *= awtWeightCompensationFactor;
        }
        return currentWeight / normalWeight;
    }

    public static List<String> supportedFonts() {
        return Collections.unmodifiableList(Arrays.asList(FontFamiliesCache.INSTANCE.supportedFonts));
    }

    @SuppressWarnings("ImmutableEnumChecker")
    private enum FontFamiliesCache {
        INSTANCE;

        private final String[] supportedFonts;

        FontFamiliesCache() {
            supportedFonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        }

        boolean isSupportedFontFamily(final String fontName) {
            for (String supportedFont : supportedFonts) {
                if (supportedFont.equalsIgnoreCase(fontName)) return true;
            }
            return false;
        }
    }

    @SuppressWarnings("ImmutableEnumChecker")
    private enum FontCache {
        INSTANCE;

        private final HashMap<CacheKey, SVGFont> cache = new HashMap<>();

        private static final class CacheKey {
            private final MeasurableFontSpec spec;
            private final MeasureContext context;

            private CacheKey(MeasurableFontSpec spec, MeasureContext context) {
                this.spec = spec;
                this.context = context;
            }

            @Override
            public String toString() {
                return "CacheKey{" +
                        "spec=" + spec +
                        ", context=" + context +
                        '}';
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof CacheKey)) return false;
                CacheKey cacheKey = (CacheKey) o;
                return spec.equals(cacheKey.spec) && context.equals(cacheKey.context);
            }

            @Override
            public int hashCode() {
                return Objects.hash(spec, context);
            }
        }
    }
}
