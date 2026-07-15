package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

/**
 * Normalizes IDML/resolved text control characters before ASTTextRun creation.
 *
 * <p>This class owns source-text cleanup only. Style application and paragraph
 * layout remain in their own builders so every text path can share the same
 * character policy without also sharing placement logic.</p>
 */
public final class TextControlNormalizer {
    private static final String UNDERLINE_BLANK_UNIT = "\t";

    private TextControlNormalizer() {}

    public static String underlineBlankUnit() {
        return UNDERLINE_BLANK_UNIT;
    }

    public static String normalize(String text,
                                   boolean preserveTabs,
                                   boolean preserveUnderlineBlank,
                                   boolean markOverline) {
        if (text == null || text.isEmpty()) return "";

        String out = text;
        out = out.replace("\t\u0008", preserveUnderlineBlank ? UNDERLINE_BLANK_UNIT : "");
        out = out.replace("\u0003", "");
        out = out.replace("\u0007", "");
        out = out.replace("\u0008", preserveUnderlineBlank ? UNDERLINE_BLANK_UNIT : "");
        out = out.replace("\n", "");
        out = out.replace("\r", "");
        out = out.replace("\uFFFC", "");

        out = out.replace("\u2009", " ");
        out = out.replace("\u2002", " ");
        out = out.replace("\u2003", " ");
        out = out.replace("\u200A", "");
        out = out.replace("\u200B", "");
        out = out.replace("\u200C", "");
        out = out.replace("\u200D", "");
        out = out.replace("\u200E", "");
        out = out.replace("\u200F", "");
        out = out.replace("\u202A", "");
        out = out.replace("\u202B", "");
        out = out.replace("\u202C", "");
        out = out.replace("\u202D", "");
        out = out.replace("\u202E", "");
        // U+FFE3 is used in source text as a degree range separator (0°￣30°).
        // Do not normalize it to "~": BT math fallback treats leading "~" as
        // an internal marker and strips it. Keep a visible text-range glyph.
        out = out.replace("\uFFE3", "∼");

        out = out.replace('\uE285', '\u25A1')
                .replace('\uE287', '\u25A1')
                .replace('\uE288', '\u25A1');

        if (markOverline && out.indexOf('\u00D3') >= 0) {
            out = markOverlineSegments(out);
        }
        if (!preserveTabs) {
            out = out.replace("\t", " ");
        }
        return out;
    }

    private static String markOverlineSegments(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00D3') {
                int endPos = sb.length();
                int startPos = endPos;
                while (startPos > 0 && sb.charAt(startPos - 1) >= 'A' && sb.charAt(startPos - 1) <= 'Z') {
                    startPos--;
                }
                if (startPos < endPos) {
                    String letters = sb.substring(startPos, endPos);
                    sb.delete(startPos, endPos);
                    sb.append('\uE000').append(letters).append('\uE001');
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
