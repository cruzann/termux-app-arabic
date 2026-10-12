package com.termux.view;

import java.text.Bidi;

public class ArabicRendererHelper {

    public static class LineResult {
        public char[] text;
        public int[] visualToLogicalMap;
    }

    public static boolean hasArabic(char[] text, int start, int count) {
        if (text == null) return false;
        int end = Math.min(start + count, text.length);
        for (int i = start; i < end; i++) {
            char c = text[i];
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || 
                (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) {
                return true;
            }
        }
        return false;
    }

    public static char getPrevChar(com.termux.terminal.TerminalBuffer screen, int row) {
        if (row <= 0) return 0;
        com.termux.terminal.TerminalRow prevLineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row - 1));
        if (prevLineObject != null && prevLineObject.mLineWrap) {
            char[] prevText = prevLineObject.mText;
            for (int j = prevLineObject.getSpaceUsed() - 1; j >= 0; j--) {
                if (!isTashkeel(prevText[j])) {
                    return prevText[j];
                }
            }
        }
        return 0;
    }

    public static char getNextChar(com.termux.terminal.TerminalBuffer screen, int row, com.termux.terminal.TerminalRow lineObject) {
        if (lineObject != null && lineObject.mLineWrap) {
            com.termux.terminal.TerminalRow nextLineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row + 1));
            if (nextLineObject != null) {
                char[] nextText = nextLineObject.mText;
                int nextSpaceUsed = nextLineObject.getSpaceUsed();
                for (int j = 0; j < nextSpaceUsed; j++) {
                    if (!isTashkeel(nextText[j])) {
                        return nextText[j];
                    }
                }
            }
        }
        return 0;
    }

    public static LineResult reshapeAndReorder(char[] text, int count) {
        return reshapeAndReorder(text, count, (char) 0, (char) 0);
    }

    public static LineResult reshapeAndReorder(char[] text, int count, char prevChar, char nextChar) {
        if (text == null) return null;
        int len = text.length;
        int activeCount = Math.min(count, len);
        if (activeCount <= 0) {
            LineResult result = new LineResult();
            result.text = text.clone();
            result.visualToLogicalMap = new int[len];
            for (int i = 0; i < len; i++) result.visualToLogicalMap[i] = i;
            return result;
        }

        // Shape the Arabic characters
        char[] shaped = new char[activeCount];
        for (int i = 0; i < activeCount; i++) shaped[i] = text[i];
        shapeArabic(shaped, prevChar, nextChar);

        // Convert back to string for Bidi
        String shapedString = new String(shaped);
        LineResult result = reorder(shapedString);

        // Reconstruct the full text array with trailing characters
        char[] fullText = new char[len];
        System.arraycopy(result.text, 0, fullText, 0, result.text.length);
        for (int i = result.text.length; i < len; i++) {
            fullText[i] = text[i];
        }

        int[] fullMap = new int[len];
        System.arraycopy(result.visualToLogicalMap, 0, fullMap, 0, result.visualToLogicalMap.length);
        for (int i = result.visualToLogicalMap.length; i < len; i++) {
            fullMap[i] = i;
        }

        result.text = fullText;
        result.visualToLogicalMap = fullMap;
        return result;
    }

    private static LineResult reorder(String text) {
        int len = text.length();
        char[] visualText = new char[len];
        int[] map = new int[len];
        for (int i = 0; i < len; i++) map[i] = i;

        try {
            Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
            if (bidi.isLeftToRight()) {
                for (int i = 0; i < len; i++) visualText[i] = text.charAt(i);
                LineResult result = new LineResult();
                result.text = visualText;
                result.visualToLogicalMap = map;
                return result;
            }

            int count = bidi.getRunCount();

            // java.text.Bidi exposes runs in visual order. Do not reorder the
            // run indices again: doing so reverses mixed Arabic/Latin text twice.
            int visualIndex = 0;
            for (int index = 0; index < count; index++) {
                int runStart = bidi.getRunStart(index);
                int runLimit = bidi.getRunLimit(index);
                int level = bidi.getRunLevel(index);
                if ((level & 1) != 0) { // RTL run
                    // Reverse grapheme-like clusters, not code units, so tashkeel
                    // stays attached to its Arabic base letter.
                    int j = runLimit - 1;
                    while (j >= runStart) {
                        int clusterStart = j;
                        while (clusterStart > runStart && isTashkeel(text.charAt(clusterStart))) {
                            clusterStart--;
                        }
                        for (int k = clusterStart; k <= j; k++) {
                            visualText[visualIndex] = mirrorChar(text.charAt(k));
                            map[visualIndex] = k;
                            visualIndex++;
                        }
                        j = clusterStart - 1;
                    }
                } else { // LTR run
                    for (int j = runStart; j < runLimit; j++) {
                        visualText[visualIndex] = text.charAt(j);
                        map[visualIndex] = j;
                        visualIndex++;
                    }
                }
            }
            LineResult result = new LineResult();
            result.text = visualText;
            result.visualToLogicalMap = map;
            return result;
        } catch (Exception e) {
            // Fallback
            for (int i = 0; i < len; i++) visualText[i] = text.charAt(i);
            LineResult result = new LineResult();
            result.text = visualText;
            result.visualToLogicalMap = map;
            return result;
        }
    }

    private static char mirrorChar(char c) {
        if (c == '(') return ')';
        if (c == ')') return '(';
        if (c == '[') return ']';
        if (c == ']') return '[';
        if (c == '{') return '}';
        if (c == '}') return '{';
        if (c == '<') return '>';
        if (c == '>') return '<';
        return c;
    }

    private static final char[][] ARABIC_GLYPHS = {
        { '\u0621', '\uFE80', '\uFE80', '\uFE80', '\uFE80', 0 }, // Hamza (no connect)
        { '\u0622', '\uFE81', '\uFE81', '\uFE82', '\uFE82', 1 }, // Alef Madda
        { '\u0623', '\uFE83', '\uFE83', '\uFE84', '\uFE84', 1 }, // Alef Hamza Above
        { '\u0624', '\uFE85', '\uFE85', '\uFE86', '\uFE86', 1 }, // Waw Hamza
        { '\u0625', '\uFE87', '\uFE87', '\uFE88', '\uFE88', 1 }, // Alef Hamza Below
        { '\u0626', '\uFE89', '\uFE8B', '\uFE8C', '\uFE8A', 2 }, // Yeh Hamza
        { '\u0627', '\uFE8D', '\uFE8D', '\uFE8E', '\uFE8E', 1 }, // Alef
        { '\u0628', '\uFE8F', '\uFE91', '\uFE92', '\uFE90', 2 }, // Beh
        { '\u0629', '\uFE93', '\uFE93', '\uFE94', '\uFE94', 1 }, // Teh Marbuta
        { '\u062A', '\uFE95', '\uFE97', '\uFE98', '\uFE96', 2 }, // Teh
        { '\u062B', '\uFE99', '\uFE9B', '\uFE9C', '\uFE9A', 2 }, // Theh
        { '\u062C', '\uFE9D', '\uFE9F', '\uFEA0', '\uFE9E', 2 }, // Jeem
        { '\u062D', '\uFEA1', '\uFEA3', '\uFEA4', '\uFEA2', 2 }, // Hah
        { '\u062E', '\uFEA5', '\uFEA7', '\uFEA8', '\uFEA6', 2 }, // Khah
        { '\u062F', '\uFEA9', '\uFEA9', '\uFEAA', '\uFEAA', 1 }, // Dal
        { '\u0630', '\uFEAB', '\uFEAB', '\uFEAC', '\uFEAC', 1 }, // Thal
        { '\u0631', '\uFEAD', '\uFEAD', '\uFEAE', '\uFEAE', 1 }, // Reh
        { '\u0632', '\uFEAF', '\uFEAF', '\uFEB0', '\uFEB0', 1 }, // Zain
        { '\u0633', '\uFEB1', '\uFEB3', '\uFEB4', '\uFEB2', 2 }, // Seen
        { '\u0634', '\uFEB5', '\uFEB7', '\uFEB8', '\uFEB6', 2 }, // Sheen
        { '\u0635', '\uFEB9', '\uFEBB', '\uFEBC', '\uFEBA', 2 }, // Sad
        { '\u0636', '\uFEBD', '\uFEBF', '\uFEC0', '\uFEBE', 2 }, // Dad
        { '\u0637', '\uFEC1', '\uFEC3', '\uFEC4', '\uFEC2', 2 }, // Tah
        { '\u0638', '\uFEC5', '\uFEC7', '\uFEC8', '\uFEC6', 2 }, // Zah
        { '\u0639', '\uFEC9', '\uFECB', '\uFECC', '\uFECA', 2 }, // Ain
        { '\u063A', '\uFECD', '\uFECF', '\uFED0', '\uFECE', 2 }, // Ghain
        { '\u0641', '\uFED1', '\uFED3', '\uFED4', '\uFED2', 2 }, // Feh
        { '\u0642', '\uFED5', '\uFED7', '\uFED8', '\uFED6', 2 }, // Qaf
        { '\u0643', '\uFED9', '\uFEDB', '\uFEDC', '\uFEDA', 2 }, // Kaf
        { '\u0644', '\uFEDD', '\uFEDF', '\uFEE0', '\uFEDE', 2 }, // Lam
        { '\u0645', '\uFEE1', '\uFEE3', '\uFEE4', '\uFEE2', 2 }, // Meem
        { '\u0646', '\uFEE5', '\uFEE7', '\uFEE8', '\uFEE6', 2 }, // Noon
        { '\u0647', '\uFEE9', '\uFEEB', '\uFEEC', '\uFEEA', 2 }, // Heh
        { '\u0648', '\uFEED', '\uFEED', '\uFEEE', '\uFEEE', 1 }, // Waw
        { '\u0649', '\uFEEF', '\uFEEF', '\uFEF0', '\uFEF0', 1 }, // Alef Maksura
        { '\u064A', '\uFEF1', '\uFEF3', '\uFEF4', '\uFEF2', 2 }, // Yeh
        { '\u067E', '\uFB56', '\uFB58', '\uFB59', '\uFB57', 2 }, // Peh
        { '\u0686', '\uFB7A', '\uFB7C', '\uFB7D', '\uFB7B', 2 }, // Tcheh
        { '\u0698', '\uFB8A', '\uFB8A', '\uFB8B', '\uFB8B', 1 }, // Jeh
        { '\u06AF', '\uFB92', '\uFB94', '\uFB95', '\uFB93', 2 }, // Gaf
        { '\u06CC', '\uFBFC', '\uFBFE', '\uFBFF', '\uFBFD', 2 }, // Farsi Yeh
    };

    private static int getGlyphIndex(char c) {
        for (int i = 0; i < ARABIC_GLYPHS.length; i++) {
            if (ARABIC_GLYPHS[i][0] == c) return i;
        }
        return -1;
    }

    public static boolean isTashkeel(char c) {
        return (c >= 0x064B && c <= 0x065F) || (c == 0x0670);
    }

    private static void shapeArabic(char[] text, char prevChar, char nextChar) {
        // Keep the original logical text immutable during shaping. Mutating the
        // input while inspecting neighbors caused already-shaped presentation
        // forms to be mistaken for non-Arabic characters, breaking joining.
        final char[] original = text.clone();

        for (int i = 0; i < original.length; i++) {
            int glyphIndex = getGlyphIndex(original[i]);
            if (glyphIndex < 0) continue;

            int previousIndex = i - 1;
            while (previousIndex >= 0 && isTashkeel(original[previousIndex])) previousIndex--;
            char previousChar = previousIndex >= 0 ? original[previousIndex] : prevChar;
            int previousGlyph = getGlyphIndex(previousChar);
            // The character to the right must be dual-joining for this letter
            // to connect to it on its right side.
            boolean connectRight = previousGlyph >= 0 && ARABIC_GLYPHS[previousGlyph][5] == 2;

            int nextIndex = i + 1;
            while (nextIndex < original.length && isTashkeel(original[nextIndex])) nextIndex++;
            char followingChar = nextIndex < original.length ? original[nextIndex] : nextChar;
            int followingGlyph = getGlyphIndex(followingChar);
            // The character to the left must be able to join on its right;
            // non-joining hamza must not create a false connection.
            boolean connectLeft = followingGlyph >= 0 && ARABIC_GLYPHS[followingGlyph][5] != 0;

            int joiningType = ARABIC_GLYPHS[glyphIndex][5];
            if (joiningType == 0) {
                text[i] = ARABIC_GLYPHS[glyphIndex][1];
            } else if (joiningType == 1) {
                text[i] = connectRight ? ARABIC_GLYPHS[glyphIndex][4] : ARABIC_GLYPHS[glyphIndex][1];
            } else {
                if (connectRight && connectLeft) text[i] = ARABIC_GLYPHS[glyphIndex][3];
                else if (connectRight) text[i] = ARABIC_GLYPHS[glyphIndex][4];
                else if (connectLeft) text[i] = ARABIC_GLYPHS[glyphIndex][2];
                else text[i] = ARABIC_GLYPHS[glyphIndex][1];
            }
        }

        // Lam-alef ligatures must be based on the original logical characters,
        // but use the shaped lam to determine isolated versus connected form.
        for (int i = 0; i < original.length - 1; i++) {
            if (original[i] != '\\u0644') continue;

            int alefIndex = i + 1;
            while (alefIndex < original.length && isTashkeel(original[alefIndex])) alefIndex++;
            if (alefIndex >= original.length) continue;

            char alef = original[alefIndex];
            boolean isMadda = alef == '\\u0622';
            boolean isHamzaAbove = alef == '\\u0623';
            boolean isHamzaBelow = alef == '\\u0625';
            boolean isAlef = alef == '\\u0627';
            if (!isMadda && !isHamzaAbove && !isHamzaBelow && !isAlef) continue;

            boolean connected = text[i] == '\\uFEDE' || text[i] == '\\uFEE0';
            if (isMadda) text[i] = connected ? '\\uFEF6' : '\\uFEF5';
            else if (isHamzaAbove) text[i] = connected ? '\\uFEF8' : '\\uFEF7';
            else if (isHamzaBelow) text[i] = connected ? '\\uFEFA' : '\\uFEF9';
            else text[i] = connected ? '\\uFEFC' : '\\uFEFB';

            // Keep the terminal's fixed cell positions and character mapping.
            text[alefIndex] = ' ';
        }
    }}
