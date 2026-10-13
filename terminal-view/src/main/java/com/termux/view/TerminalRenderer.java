package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();
    /** Android fallback used for Arabic Presentation Forms not covered correctly by Kawkab Mono. */
    private final Paint mArabicFallbackPaint = new Paint();
    private final Typeface mArabicFallbackTypeface = Typeface.create("monospace", Typeface.NORMAL);

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        // Kawkab Mono is useful for normal Arabic/Latin monospace text, but some
        // builds lack specific Arabic Presentation Form glyphs used by the
        // terminal shaper. Measure and draw those forms with Android's system
        // monospace fallback instead of rendering .notdef boxes.
        mArabicFallbackPaint.setTypeface(mArabicFallbackTypeface);
        mArabicFallbackPaint.setAntiAlias(true);
        mArabicFallbackPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            char[] line = lineObject.mText;
            int storedCharsUsedInLine = lineObject.getSpaceUsed();
            int visibleCharsUsedInLine = ArabicRendererHelper.getVisibleLength(line, storedCharsUsedInLine);
            int charsUsedInLine = storedCharsUsedInLine;

            boolean hasArabic = ArabicRendererHelper.hasArabic(line, 0, visibleCharsUsedInLine);
            ArabicRendererHelper.LineResult arabicResult = null;
            int[] charToColMap = null;
            if (hasArabic) {
                char prevChar = ArabicRendererHelper.getPrevChar(screen, row);
                char nextChar = ArabicRendererHelper.getNextChar(screen, row, lineObject);
                arabicResult = ArabicRendererHelper.reshapeAndReorder(line, visibleCharsUsedInLine, prevChar, nextChar);

                // Align only the actual visible text. Do not let stale trailing blanks
                // retained by TerminalRow after resizing change the bidi paragraph or
                // move the word based on a previous, wider/narrower screen.
                if (row != cursorRow && ArabicRendererHelper.isArabicOnlyLine(line, visibleCharsUsedInLine)) {
                    arabicResult = ArabicRendererHelper.rightAlignVisualLine(arabicResult, visibleCharsUsedInLine);
                }

                line = arabicResult.text;
                charsUsedInLine = line.length;

                // Build char to column map
                char[] origLine = lineObject.mText;
                charToColMap = new int[origLine.length];
                int currentChar = 0;
                int currentColumn = 0;
                while (currentChar < origLine.length) {
                    charToColMap[currentChar] = currentColumn;
                    char c = origLine[currentChar++];
                    boolean isHigh = Character.isHighSurrogate(c) && (currentChar < origLine.length);
                    int codePoint = c;
                    if (isHigh) {
                        charToColMap[currentChar] = currentColumn; // map the low surrogate
                        codePoint = Character.toCodePoint(c, origLine[currentChar++]);
                    }
                    int wcwidth = WcWidth.width(codePoint);
                    if (wcwidth > 0) {
                        currentColumn += wcwidth;
                    }
                }
            }

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            boolean lastRunArabicPresentationForm = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final int logicalColumn;
                if (hasArabic) {
                    int logCharIndex = arabicResult.visualToLogicalMap[currentCharIndex];
                    if (logCharIndex >= 0 && logCharIndex < charToColMap.length) {
                        logicalColumn = Math.min(charToColMap[logCharIndex], columns - 1);
                    } else {
                        logicalColumn = column;
                    }
                } else {
                    logicalColumn = column;
                }
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == logicalColumn || (codePointWcWidth == 2 && cursorX == logicalColumn + 1));
                final boolean insideSelection = logicalColumn >= selx1 && logicalColumn <= selx2;
                final long style = lineObject.getStyle(logicalColumn);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final boolean arabicPresentationForm = isArabicPresentationForm(codePoint);
                final Paint measurePaint = arabicPresentationForm ? mArabicFallbackPaint : mTextPaint;
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : measurePaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                // Draw a whole same-style Arabic word/run together. Flushing each
                // pre-shaped letter separately stretches every glyph into its own
                // cell and creates visible gaps between otherwise connected forms.
                // Split only when the run crosses a style/cursor/selection/font class.
                if (style != lastRunStyle || insideCursor != lastRunInsideCursor ||
                    insideSelection != lastRunInsideSelection ||
                    (!arabicPresentationForm && fontWidthMismatch != lastRunFontWidthMismatch) ||
                    arabicPresentationForm != lastRunArabicPresentationForm) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                    lastRunArabicPresentationForm = arabicPresentationForm;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT.
            Typeface originalTypeface = mTextPaint.getTypeface();
            boolean useArabicFallback = containsArabicPresentationForm(text, startCharIndex, runWidthChars);
            boolean compressArabicRun = useArabicFallback && runWidthColumns > 1;
            boolean savedArabicMatrix = false;
            if (useArabicFallback) mTextPaint.setTypeface(mArabicFallbackTypeface);

            // Slightly tighten Arabic words without changing terminal cell widths,
            // selection backgrounds, or cursor placement. Center the glyphs in the
            // run's existing cell rectangle to avoid drifting the next character.
            if (compressArabicRun) {
                final float glyphScale = 0.94f;
                final float runWidth = right - left;
                canvas.save();
                canvas.translate(left * (1.f - glyphScale) + runWidth * (1.f - glyphScale) / 2.f, 0);
                canvas.scale(glyphScale, 1.f);
                savedArabicMatrix = true;
            }

            try {
                canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);
            } finally {
                if (savedArabicMatrix) canvas.restore();
                if (useArabicFallback) mTextPaint.setTypeface(originalTypeface);
            }
        }

        if (savedMatrix) canvas.restore();
    }

    private static boolean isArabicPresentationForm(int codePoint) {
        return (codePoint >= 0xFB50 && codePoint <= 0xFDFF)
            || (codePoint >= 0xFE70 && codePoint <= 0xFEFF);
    }

    private static boolean containsArabicPresentationForm(char[] text, int start, int count) {
        int end = Math.min(text.length, start + count);
        for (int i = Math.max(0, start); i < end; i++) {
            char c = text[i];
            if (isArabicPresentationForm(c)) return true;
            if (Character.isHighSurrogate(c) && i + 1 < end) i++;
        }
        return false;
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
