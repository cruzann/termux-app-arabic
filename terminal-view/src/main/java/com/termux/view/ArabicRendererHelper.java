package com.termux.view;

import java.text.Bidi;

public class ArabicRendererHelper {

    public static class LineResult {
        public char[] text;
        public int[] visualToLogicalMap;
    }

    /**
     * Return the used prefix without stale trailing blank cells. TerminalRow may keep
     * mSpaceUsed at an older extent after redraws/resizes; those blank cells must not
     * participate in bidi analysis or determine where an Arabic-only output is aligned.
     */
    public static int getVisibleLength(char[] text, int count) {
        if (text == null || count <= 0) return 0;
        int end = Math.min(count, text.length);
        while (end > 0 && text[end - 1] == ' ') end--;
        return end;
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

    private static boolean isArabicCodePoint(int codePoint) {
        return (codePoint >= 0x0600 && codePoint <= 0x06FF)
            || (codePoint >= 0x0750 && codePoint <= 0x077F)
            || (codePoint >= 0xFB50 && codePoint <= 0xFDFF)
            || (codePoint >= 0xFE70 && codePoint <= 0xFEFF);
    }

    /**
     * True for an Arabic-only terminal output line that can be visually aligned
     * to the right without moving ASCII commands, numbers, or box-drawing UI.
     */
    public static boolean isArabicOnlyLine(char[] text, int count) {
        if (text == null) return false;
        int end = Math.max(0, Math.min(count, text.length));
        boolean foundArabic = false;
        for (int i = 0; i < end; i++) {
            int codePoint = text[i];
            if (Character.isHighSurrogate(text[i]) && i + 1 < end &&
                Character.isLowSurrogate(text[i + 1])) {
                codePoint = Character.toCodePoint(text[i], text[++i]);
            }

            if (isArabicCodePoint(codePoint) || Character.isWhitespace(codePoint)) {
                if (isArabicCodePoint(codePoint)) foundArabic = true;
                continue;
            }

            // Keep common neutral punctuation, but never right-align Latin text,
            // shell commands, IP addresses, digits, or terminal frame characters.
            if (codePoint == '.' || codePoint == ',' || codePoint == ':' || codePoint == ';'
                || codePoint == '!' || codePoint == '?' || codePoint == 39
                || codePoint == '"' || codePoint == '(' || codePoint == ')'
                || codePoint == '[' || codePoint == ']' || codePoint == '-' || codePoint == '/') {
                continue;
            }
            return false;
        }
        return foundArabic;
    }

    /**
     * Move a shaped visual line to the right edge of the terminal row while
     * keeping each displayed cell mapped to its original logical column.
     * This is used only for completed Arabic-only output rows, never the active
     * cursor row or rows containing Latin/ASCII UI text.
     */
    public static LineResult rightAlignVisualLine(LineResult source, int activeCount) {
        if (source == null || source.text == null || source.visualToLogicalMap == null) return source;
        int len = source.text.length;
        int active = Math.max(0, Math.min(activeCount, len));
        int padding = len - active;
        if (active == 0 || padding == 0) return source;

        char[] alignedText = new char[len];
        java.util.Arrays.fill(alignedText, ' ');
        int[] alignedMap = new int[len];

        // The leading blank cells correspond to the original trailing blank cells.
        for (int i = 0; i < padding; i++) {
            alignedMap[i] = active + i;
        }
        System.arraycopy(source.text, 0, alignedText, padding, active);
        System.arraycopy(source.visualToLogicalMap, 0, alignedMap, padding, active);

        source.text = alignedText;
        source.visualToLogicalMap = alignedMap;
        return source;
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
        int activeCount = Math.max(0, Math.min(count, len));
        if (activeCount == 0) {
            LineResult result = new LineResult();
            result.text = text.clone();
            result.visualToLogicalMap = new int[len];
            for (int i = 0; i < len; i++) result.visualToLogicalMap[i] = i;
            return result;
        }

        // Analyze direction from the original logical Unicode characters,
        // then apply the resulting visual order to the shaped glyphs. This also
        // keeps the visual-to-logical map aligned with terminal cell positions.
        char[] logical = new char[activeCount];
        char[] shaped = new char[activeCount];
        System.arraycopy(text, 0, logical, 0, activeCount);
        System.arraycopy(logical, 0, shaped, 0, activeCount);
        shapeArabic(shaped, prevChar, nextChar);

        LineResult visual = reorder(new String(logical), shaped);
        char[] fullText = new char[len];
        int[] fullMap = new int[len];
        System.arraycopy(visual.text, 0, fullText, 0, activeCount);
        System.arraycopy(visual.visualToLogicalMap, 0, fullMap, 0, activeCount);
        for (int i = activeCount; i < len; i++) {
            fullText[i] = text[i];
            fullMap[i] = i;
        }

        visual.text = fullText;
        visual.visualToLogicalMap = fullMap;
        return visual;
    }

    private static LineResult reorder(String logicalText, char[] shapedText) {
        int len = logicalText.length();
        char[] visualText = new char[len];
        int[] map = new int[len];
        for (int i = 0; i < len; i++) map[i] = i;

        try {
            // A terminal row is a fixed left-to-right grid even when its first strong
            // character is Arabic. Letting neutral frame characters inherit RTL paragraph
            // direction can move ASCII/box-drawing borders across the whole row.
            Bidi bidi = new Bidi(logicalText, Bidi.DIRECTION_LEFT_TO_RIGHT);
            if (bidi.isLeftToRight()) {
                System.arraycopy(shapedText, 0, visualText, 0, len);
                LineResult result = new LineResult();
                result.text = visualText;
                result.visualToLogicalMap = map;
                return result;
            }

            int runCount = bidi.getRunCount();
            byte[] levels = new byte[runCount];
            Integer[] runOrder = new Integer[runCount];
            for (int i = 0; i < runCount; i++) {
                levels[i] = (byte) bidi.getRunLevel(i);
                runOrder[i] = i;
            }
            // Bidi runs are returned in logical order. Reorder the run indices
            // once, based on their embedding levels, to obtain visual order.
            Bidi.reorderVisually(levels, 0, runOrder, 0, runCount);

            int visualIndex = 0;
            for (int visualRun = 0; visualRun < runCount; visualRun++) {
                int logicalRun = runOrder[visualRun];
                int runStart = bidi.getRunStart(logicalRun);
                int runLimit = bidi.getRunLimit(logicalRun);
                int level = bidi.getRunLevel(logicalRun);

                if ((level & 1) != 0) {
                    // Reverse character clusters so vowel marks remain attached
                    // to their base characters in a right-to-left run.
                    int j = runLimit - 1;
                    while (j >= runStart) {
                        int clusterStart = j;
                        while (clusterStart > runStart && isTashkeel(logicalText.charAt(clusterStart))) {
                            clusterStart--;
                        }
                        for (int k = clusterStart; k <= j; k++) {
                            visualText[visualIndex] = mirrorChar(shapedText[k]);
                            map[visualIndex] = k;
                            visualIndex++;
                        }
                        j = clusterStart - 1;
                    }
                } else {
                    for (int j = runStart; j < runLimit; j++) {
                        visualText[visualIndex] = shapedText[j];
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
            System.arraycopy(shapedText, 0, visualText, 0, len);
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


    }
}
