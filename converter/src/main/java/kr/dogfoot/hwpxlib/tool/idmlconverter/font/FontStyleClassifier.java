package kr.dogfoot.hwpxlib.tool.idmlconverter.font;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interprets IDML/installed-font style names into HWPX style flags.
 */
public final class FontStyleClassifier {
    private static final Pattern LEADING_NUMBERED_STYLE = Pattern.compile("^(\\d{2,3})\\s+.*");
    private static final Pattern NUMBER_TOKEN = Pattern.compile("(?<!\\d)(\\d{1,3})(?!\\d)");

    private FontStyleClassifier() {
    }

    public static boolean isBoldStyle(String fontStyle) {
        return inferStyleWeight(fontStyle) >= 600;
    }

    public static boolean isItalicStyle(String fontStyle) {
        if (fontStyle == null || fontStyle.isEmpty()) return false;
        String lower = fontStyle.toLowerCase(Locale.ROOT);
        Set<String> tokens = tokens(lower);
        return lower.contains("italic")
                || lower.contains("oblique")
                || lower.contains("slanted")
                || hasToken(tokens, "it", "ita", "obl");
    }

    public static int inferWeight(String name, String style) {
        int styleWeight = inferStyleWeight(style);
        if (styleWeight > 0) return styleWeight;
        return inferStyleWeight(name);
    }

    public static int inferStyleWeight(String fontStyle) {
        if (fontStyle == null || fontStyle.isEmpty()) return 0;
        String lower = fontStyle.toLowerCase(Locale.ROOT);
        Set<String> tokens = tokens(lower);

        Matcher numberedStyle = LEADING_NUMBERED_STYLE.matcher(lower);
        if (numberedStyle.matches()) {
            int weight = parseNumberedStyleWeight(numberedStyle.group(1));
            if (weight >= 600) return weight;
        }

        if (lower.contains("ultralight") || lower.contains("ultra light")
                || lower.contains("hairline") || hasToken(tokens, "ul", "th", "thin")) {
            return 100;
        }
        if (lower.contains("extralight") || lower.contains("extra light")
                || hasToken(tokens, "el")) {
            return 200;
        }
        if (lower.contains("light") || hasToken(tokens, "lt", "l")) {
            return 300;
        }
        if (lower.contains("regular") || lower.contains("normal")
                || lower.contains("book") || hasToken(tokens, "rg", "r")) {
            return 400;
        }
        if (lower.contains("medium") || hasToken(tokens, "md", "m")) {
            return 500;
        }
        if (lower.contains("semibold") || lower.contains("semi bold")
                || lower.contains("demibold") || lower.contains("demi bold")
                || hasToken(tokens, "sb", "db")) {
            return 600;
        }
        if (lower.contains("extrabold") || lower.contains("extra bold")
                || lower.contains("ultrabold") || lower.contains("ultra bold")
                || hasToken(tokens, "eb", "xb")) {
            return 800;
        }
        // SPEC-067: "bk" 는 InDesign 가변폰트 Black 약자(예: "19 Bk"). DOM 이 아니라
        // IDML 원본 FontStyle 을 쓰면 이 약자로 나온다(영어 u3/u4 단원명 "Think Twice"
        // 19 Bk). 앞 웨이트 숫자가 낮아도(19) Bk 가 Black 신호다.
        if (lower.contains("black") || hasToken(tokens, "blk", "bk")) {
            return 900;
        }
        if (lower.contains("heavy") || hasToken(tokens, "hv")) {
            return 800;
        }
        if (lower.contains("bold") || hasToken(tokens, "bd", "b")) {
            return 700;
        }

        Matcher number = NUMBER_TOKEN.matcher(lower);
        while (number.find()) {
            int n = parseInt(number.group(1));
            if (n >= 100 && n <= 900) return n;
            if (n >= 10 && n <= 90) return n * 10;
        }
        return 0;
    }

    private static int parseNumberedStyleWeight(String value) {
        int n = parseInt(value);
        if (n >= 100 && n <= 900) return n;
        if (n >= 65 && n <= 99) return n * 10;
        return 0;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        if (value == null || value.isEmpty()) return result;
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        if (normalized.isEmpty()) return result;
        for (String token : normalized.split("\\s+")) {
            if (!token.isEmpty()) result.add(token);
        }
        return result;
    }

    private static boolean hasToken(Set<String> tokens, String... choices) {
        for (String choice : choices) {
            if (tokens.contains(choice)) return true;
        }
        return false;
    }
}
