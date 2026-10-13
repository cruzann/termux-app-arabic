package com.termux.view;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ArabicRendererHelperTest {

    private ArabicRendererHelper.LineResult render(String text) {
        char[] line = text.toCharArray();
        return ArabicRendererHelper.reshapeAndReorder(line, line.length);
    }

    @Test
    public void keepsInputLogicalTextUnchanged() {
        char[] input = "السلام عليكم".toCharArray();
        char[] before = input.clone();

        ArabicRendererHelper.reshapeAndReorder(input, input.length);

        assertArrayEquals("Rendering must not mutate terminal buffer text", before, input);
    }

    @Test
    public void joinsAdjacentArabicLetters() {
        ArabicRendererHelper.LineResult result = render("بب");
        String visual = new String(result.text);

        // In visual left-to-right cell order, the logical second letter appears first.
        assertTrue("Expected two rendered Arabic cells", visual.length() >= 2);
        assertEquals("Rightmost logical Beh should render first", '\uFE90', visual.charAt(0));
        assertEquals("Leftmost logical Beh should render second", '\uFE91', visual.charAt(1));
    }

    @Test
    public void keepsLamAlefAdjacentWithoutInsertingBlankCells() {
        ArabicRendererHelper.LineResult result = render("السلام");
        String visual = new String(result.text);

        assertTrue("Expected shaped Lam glyph", visual.indexOf('\uFEE0') >= 0);
        assertTrue("Expected shaped Alef glyph", visual.indexOf('\uFE8E') >= 0);
        assertEquals("Shaping must not inject spaces into a word", -1, visual.indexOf(' '));
    }

    @Test
    public void preservesLatinRunOrderInMixedText() {
        ArabicRendererHelper.LineResult result = render("echo السلام");
        String visual = new String(result.text);

        assertTrue("Latin command text should retain left-to-right order", visual.startsWith("echo "));
    }

    @Test
    public void keepsBoxDrawingBordersInTheirOriginalColumns() {
        String input = "│  جسر عربي v1.0  │";
        ArabicRendererHelper.LineResult result = render(input);
        String visual = new String(result.text);

        assertEquals("The left frame border must stay in column zero", '│', visual.charAt(0));
        assertEquals("The right frame border must stay in the last column", '│', visual.charAt(input.length() - 1));
        assertEquals("Bidi rendering must preserve terminal cell count", input.length(), visual.length());
    }

    @Test
    public void keepsShellQuotesAndLatinCommandAtTheirOriginalEdges() {
        String input = "echo 'السلام عليكم'";
        ArabicRendererHelper.LineResult result = render(input);
        String visual = new String(result.text);

        assertTrue("The shell command must remain at the left edge", visual.startsWith("echo '"));
        assertTrue("The closing quote must remain at the original right edge", visual.endsWith("'"));
    }

    @Test
    public void identifiesArabicOnlyOutputWithoutReclassifyingMixedText() {
        assertTrue(ArabicRendererHelper.isArabicOnlyLine("السلام عليكم".toCharArray(), "السلام عليكم".length()));
        assertFalse(ArabicRendererHelper.isArabicOnlyLine("Mixed text: English 123 عربي".toCharArray(),
            "Mixed text: English 123 عربي".length()));
        assertFalse(ArabicRendererHelper.isArabicOnlyLine("│ جسر عربي v1.0 │".toCharArray(),
            "│ جسر عربي v1.0 │".length()));
    }

    @Test
    public void ignoresStaleTrailingSpacesWhenMeasuringVisibleArabicText() {
        char[] shortRow = "اختبار        ".toCharArray();
        char[] longRow = "اختبار                                ".toCharArray();

        assertEquals("Visible length must stop at the last non-space", "اختبار".length(),
            ArabicRendererHelper.getVisibleLength(shortRow, shortRow.length));
        assertEquals("Wider rows must not change visible text length", "اختبار".length(),
            ArabicRendererHelper.getVisibleLength(longRow, longRow.length));
        assertEquals("An empty/space-only row has no visible content", 0,
            ArabicRendererHelper.getVisibleLength("          ".toCharArray(), 10));
    }

    @Test
    public void rightAlignedArabicWordIsStableAcrossTerminalWidths() {
        String word = "اختبار";
        int[] widths = {16, 36};
        String expected = null;

        for (int width : widths) {
            char[] row = new char[width];
            Arrays.fill(row, ' ');
            word.getChars(0, word.length(), row, 0);

            int visibleLength = ArabicRendererHelper.getVisibleLength(row, row.length);
            ArabicRendererHelper.LineResult result = ArabicRendererHelper.reshapeAndReorder(row, visibleLength);
            result = ArabicRendererHelper.rightAlignVisualLine(result, visibleLength);

            String visible = new String(result.text, width - visibleLength, visibleLength);
            if (expected == null) expected = visible;
            else assertEquals("Resizing must not change the shaped/ordered Arabic word", expected, visible);
        }
    }

    @Test
    public void rightAlignsArabicOutputAndPreservesColumnMapping() {
        String word = "اختبار";
        char[] row = new char[16];
        Arrays.fill(row, ' ');
        word.getChars(0, word.length(), row, 0);

        ArabicRendererHelper.LineResult result = ArabicRendererHelper.reshapeAndReorder(row, word.length());
        result = ArabicRendererHelper.rightAlignVisualLine(result, word.length());

        int start = row.length - word.length();
        for (int i = 0; i < start; i++) {
            assertEquals("Leading cells should be blank", ' ', result.text[i]);
            assertEquals("Leading blank cells map to original trailing blanks", word.length() + i,
                result.visualToLogicalMap[i]);
        }
        int[] sortedMap = Arrays.copyOf(result.visualToLogicalMap, result.visualToLogicalMap.length);
        Arrays.sort(sortedMap);
        for (int i = 0; i < sortedMap.length; i++) {
            assertEquals("Right alignment must preserve a complete cell mapping", i, sortedMap[i]);
        }
    }

    @Test
    public void keepsTashkeelAttachedToItsArabicBase() {
        ArabicRendererHelper.LineResult result = render("بَت");
        String visual = new String(result.text);

        int base = visual.indexOf('\uFE91');
        assertTrue("Expected initial Beh form", base >= 0);
        assertTrue("Fatha must remain immediately after its base character",
            base + 1 < visual.length() && visual.charAt(base + 1) == '\u064E');
    }

    @Test
    public void visualToLogicalMapIsValidPermutation() {
        String input = "عربي 123 English";
        ArabicRendererHelper.LineResult result = render(input);
        int[] map = Arrays.copyOf(result.visualToLogicalMap, input.length());
        Arrays.sort(map);

        assertEquals(input.length(), result.text.length);
        assertEquals(input.length(), map.length);
        for (int i = 0; i < map.length; i++) {
            assertEquals("Every logical character must map exactly once", i, map[i]);
        }
    }
}
