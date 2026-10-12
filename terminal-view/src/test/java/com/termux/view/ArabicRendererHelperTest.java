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

        // Beh final and initial presentation forms should both be produced.
        assertTrue("Expected final Beh form", visual.indexOf('\uFE90') >= 0);
        assertTrue("Expected initial Beh form", visual.indexOf('\uFE91') >= 0);
    }

    @Test
    public void formsLamAlefLigature() {
        ArabicRendererHelper.LineResult result = render("السلام");
        String visual = new String(result.text);

        assertTrue("Expected connected Lam-Alef ligature", visual.indexOf('\uFEFC') >= 0);
    }

    @Test
    public void preservesLatinRunOrderInMixedText() {
        ArabicRendererHelper.LineResult result = render("echo السلام");
        String visual = new String(result.text);

        assertTrue("Latin command text should retain left-to-right order", visual.startsWith("echo "));
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
