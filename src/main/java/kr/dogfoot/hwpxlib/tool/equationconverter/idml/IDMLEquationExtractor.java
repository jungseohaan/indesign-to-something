package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * IDML Story XML 파일에서 NP 폰트 기반 수식을 추출하고
 * HWP 수식 스크립트로 변환한다.
 */
public class IDMLEquationExtractor {

    private final String storiesDir;

    public IDMLEquationExtractor(String storiesDir) {
        this.storiesDir = storiesDir;
    }

    /**
     * Story XML 파일에서 수식 부분을 추출하여 HWP Script 목록으로 반환한다.
     * 하나의 Story에 여러 수식이 포함될 수 있다.
     */
    public List<ExtractedEquation> extractFromStory(String storyFilename) {
        List<ExtractedEquation> equations = new ArrayList<ExtractedEquation>();
        try {
            File file = new File(storiesDir, storyFilename);
            if (!file.exists()) return equations;

            Document doc = parseXML(file);
            NodeList paraRanges = doc.getElementsByTagName("ParagraphStyleRange");

            for (int i = 0; i < paraRanges.getLength(); i++) {
                Element paraRange = (Element) paraRanges.item(i);
                extractEquationsFromParagraph(paraRange, equations);
            }
        } catch (Exception e) {
            // Skip unparseable files
        }
        return equations;
    }

    /**
     * 하나의 ParagraphStyleRange에서 수식 영역을 식별하고 추출한다.
     */
    private void extractEquationsFromParagraph(Element paraRange, List<ExtractedEquation> equations) {
        List<Element> charRanges = getChildElements(paraRange, "CharacterStyleRange");
        if (charRanges.isEmpty()) return;

        // 연속된 수식 토큰들을 그룹으로 묶기
        List<List<EquationToken>> equationGroups = new ArrayList<List<EquationToken>>();
        List<EquationToken> currentGroup = new ArrayList<EquationToken>();
        boolean inEquation = false;

        for (int i = 0; i < charRanges.size(); i++) {
            Element charRange = charRanges.get(i);
            String styleRef = charRange.getAttribute("AppliedCharacterStyle");
            String npFont = NPFontGlyphMap.extractNPFontName(styleRef);
            String position = charRange.getAttribute("Position");
            boolean hasNPFont = npFont != null;

            // TextFrame (분수/lim 블록) 확인
            List<Element> textFrames = getChildElements(charRange, "TextFrame");
            boolean hasFractionFrame = false;
            boolean hasLimitFrame = false;
            for (Element tf : textFrames) {
                String objStyle = tf.getAttribute("AppliedObjectStyle");
                if (objStyle.contains("분수")) {
                    hasFractionFrame = true;
                } else if (objStyle.contains("[Normal Text Frame]")) {
                    hasLimitFrame = true;
                }
            }

            boolean isEquationPart = hasNPFont || hasFractionFrame || hasLimitFrame;

            // Content와 구조 분석
            if (isEquationPart) {
                if (!inEquation) {
                    inEquation = true;
                    currentGroup = new ArrayList<EquationToken>();
                }

                // TextFrame 처리 (분수 또는 limit 블록)
                for (Element tf : textFrames) {
                    String parentStory = tf.getAttribute("ParentStory");
                    String objStyle = tf.getAttribute("AppliedObjectStyle");
                    if (objStyle.contains("분수")) {
                        // 분수 TextFrame - 내부 Story를 파싱하여 분자/분모 추출
                        FractionContent frac = parseFractionStory(parentStory);
                        if (frac != null) {
                            currentGroup.add(new EquationToken(EquationToken.Type.FRACTION, frac));
                        }
                    } else {
                        // Limit/기타 블록 TextFrame - 내부 수식 추출
                        String subScript = parseLimitBlockStory(parentStory);
                        if (subScript != null && !subScript.trim().isEmpty()) {
                            currentGroup.add(new EquationToken(EquationToken.Type.LIMIT_BLOCK, subScript));
                        }
                    }
                }

                // Content 텍스트 처리
                String contentText = getContentText(charRange);
                if (contentText != null && !contentText.trim().isEmpty()) {
                    if (hasNPFont) {
                        NPFontGlyphMap.FontCategory cat = NPFontGlyphMap.getCategory(npFont);
                        boolean isSubscript = "Subscript".equals(position);
                        boolean isSuperscript = "Superscript".equals(position);
                        currentGroup.add(new EquationToken(
                                EquationToken.Type.NP_TEXT, contentText, npFont, cat,
                                isSubscript, isSuperscript));
                    }
                }
            } else {
                // 일반 텍스트 - 수식 중이면 경계 텍스트인지 확인
                String contentText = getContentText(charRange);
                if (inEquation && contentText != null) {
                    String trimmed = contentText.trim();
                    // 다음 charRange가 NP 폰트인지 확인 (lookahead)
                    boolean nextIsNP = false;
                    if (i + 1 < charRanges.size()) {
                        String nextStyle = charRanges.get(i + 1).getAttribute("AppliedCharacterStyle");
                        nextIsNP = NPFontGlyphMap.extractNPFontName(nextStyle) != null;
                    }
                    // 수식 사이의 연결 텍스트 (=, +, -, 숫자, 변수 등)
                    if (isInlineEquationText(trimmed, charRange) || nextIsNP) {
                        boolean isSubscript = "Subscript".equals(position);
                        boolean isSuperscript = "Superscript".equals(position);
                        currentGroup.add(new EquationToken(
                                EquationToken.Type.PLAIN_MATH, trimmed, null, null,
                                isSubscript, isSuperscript));
                    } else {
                        // 수식 종료
                        if (!currentGroup.isEmpty()) {
                            equationGroups.add(currentGroup);
                        }
                        inEquation = false;
                        currentGroup = new ArrayList<EquationToken>();
                    }
                } else if (!inEquation && contentText != null) {
                    // 수식 시작 전 - 다음이 NP 폰트이고 현재 텍스트가 짧은 수학 텍스트면 수식 시작
                    String trimmed = contentText.trim();
                    boolean nextIsNP = false;
                    if (i + 1 < charRanges.size()) {
                        String nextStyle = charRanges.get(i + 1).getAttribute("AppliedCharacterStyle");
                        nextIsNP = NPFontGlyphMap.extractNPFontName(nextStyle) != null;
                    }
                    if (nextIsNP && isShortMathText(trimmed)) {
                        inEquation = true;
                        currentGroup = new ArrayList<EquationToken>();
                        boolean isSubscript = "Subscript".equals(position);
                        boolean isSuperscript = "Superscript".equals(position);
                        currentGroup.add(new EquationToken(
                                EquationToken.Type.PLAIN_MATH, trimmed, null, null,
                                isSubscript, isSuperscript));
                    }
                }
            }
        }

        // 마지막 그룹
        if (inEquation && !currentGroup.isEmpty()) {
            equationGroups.add(currentGroup);
        }

        // 각 그룹을 HWP Script로 변환 (최소 길이 필터)
        for (List<EquationToken> group : equationGroups) {
            String hwpScript = convertTokensToHwpScript(group);
            if (hwpScript != null && !hwpScript.trim().isEmpty()) {
                String trimmed = hwpScript.trim();
                // 한 글자짜리이거나 의미 없는 수식은 건너뛰기
                if (trimmed.length() <= 1) continue;
                // 순수한 단일 문자/숫자만 있는 것은 건너뛰기
                if (trimmed.matches("[a-zA-Z]")) continue;
                // 단순 연산 기호만 있는 것은 건너뛰기
                if (trimmed.matches("[<>=+\\-*/.,!?%]+")) continue;
                String paraStyle = paraRange.getAttribute("AppliedParagraphStyle");
                equations.add(new ExtractedEquation(hwpScript, paraStyle));
            }
        }
    }

    /**
     * 수식 토큰 그룹을 HWP 수식 스크립트로 변환한다.
     */
    private String convertTokensToHwpScript(List<EquationToken> tokens) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < tokens.size(); i++) {
            EquationToken token = tokens.get(i);

            // 토큰 간 공백 삽입: 이전 출력이 있고, subscript/superscript가 아닌 경우
            boolean needsSpace = sb.length() > 0
                    && !token.isSubscript && !token.isSuperscript
                    && token.type != EquationToken.Type.FRACTION
                    || (token.type == EquationToken.Type.FRACTION && sb.length() > 0);
            if (needsSpace) {
                // subscript/superscript 카테고리 토큰도 공백 불필요 (appendSubscript이 처리)
                boolean isSubSuperCategory = token.type == EquationToken.Type.NP_TEXT
                        && token.category != null
                        && (token.category == NPFontGlyphMap.FontCategory.SUBSCRIPT_INDEX
                            || token.category == NPFontGlyphMap.FontCategory.SUPERSCRIPT_INDEX);
                if (!isSubSuperCategory) {
                    char lastChar = sb.charAt(sb.length() - 1);
                    if (lastChar != ' ' && lastChar != '{') {
                        sb.append(' ');
                    }
                }
            }

            switch (token.type) {
                case LIMIT_BLOCK: {
                    // limit 블록: "lim" + subscript
                    sb.append(token.text);
                    break;
                }
                case FRACTION: {
                    FractionContent frac = token.fraction;
                    if (frac != null) {
                        sb.append("{").append(frac.numerator).append("} over {").append(frac.denominator).append("}");
                    }
                    break;
                }
                case NP_TEXT: {
                    String text = token.text;
                    String npFont = token.npFont;
                    NPFontGlyphMap.FontCategory cat = token.category;

                    if (cat == NPFontGlyphMap.FontCategory.FRACTION_BAR) {
                        // NP_BUN: "[", "]" 은 분수의 시작/끝 구분자 (이미 TextFrame으로 처리)
                        // "-", "=" 는 분수선 (이미 TextFrame으로 처리됨)
                        // 인라인에서 나오면 무시하거나 간단한 구분자
                        String mapped = text.trim();
                        if ("- ".equals(text) || "-".equals(mapped) || "=".equals(mapped)
                                || " =".equals(text)) {
                            // 분수선은 TextFrame에서 처리되므로 스킵
                        } else if ("[".equals(mapped) || "]".equals(mapped)) {
                            // 분수 구분자 - 스킵
                        } else if ("][".equals(mapped)) {
                            // 연속 분수 구분자 - 스킵
                        } else {
                            sb.append(mapped);
                        }
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.SPECIAL_SYMBOL) {
                        String mapped = NPFontGlyphMap.mapGlyph(npFont, text);
                        if (token.isSubscript) {
                            appendSubscript(sb, mapped);
                        } else if (token.isSuperscript) {
                            appendSuperscript(sb, mapped);
                        } else {
                            sb.append(mapped);
                        }
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.VARIABLE) {
                        String mapped = NPFontGlyphMap.mapGlyph(npFont, text);
                        if (token.isSubscript) {
                            appendSubscript(sb, mapped);
                        } else if (token.isSuperscript) {
                            appendSuperscript(sb, mapped);
                        } else {
                            sb.append(mapped);
                        }
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.SUBSCRIPT_INDEX) {
                        appendSubscript(sb, text);
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.SUPERSCRIPT_INDEX) {
                        appendSuperscript(sb, text);
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.OPERATOR ||
                            cat == NPFontGlyphMap.FontCategory.ITALIC) {
                        if (token.isSubscript) {
                            appendSubscript(sb, text);
                        } else if (token.isSuperscript) {
                            appendSuperscript(sb, text);
                        } else {
                            sb.append(text);
                        }
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.INTEGRAL) {
                        String mapped = NPFontGlyphMap.mapGlyph(npFont, text);
                        sb.append(mapped);
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.SUMMATION) {
                        sb.append("sum");
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.LIMIT) {
                        sb.append("lim");
                        break;
                    }

                    if (cat == NPFontGlyphMap.FontCategory.ROOT) {
                        sb.append("sqrt");
                        break;
                    }

                    // Default
                    if (token.isSubscript) {
                        appendSubscript(sb, text);
                    } else if (token.isSuperscript) {
                        appendSuperscript(sb, text);
                    } else {
                        sb.append(text);
                    }
                    break;
                }
                case PLAIN_MATH: {
                    String text = token.text;
                    if (token.isSubscript) {
                        appendSubscript(sb, text);
                    } else if (token.isSuperscript) {
                        appendSuperscript(sb, text);
                    } else {
                        sb.append(text);
                    }
                    break;
                }
            }
        }

        return cleanHwpScript(sb.toString());
    }

    private void appendSubscript(StringBuilder sb, String text) {
        sb.append(" _{").append(text).append("}");
    }

    private void appendSuperscript(StringBuilder sb, String text) {
        sb.append(" ^{").append(text).append("}");
    }

    /**
     * HWP Script를 정리한다.
     */
    private String cleanHwpScript(String script) {
        // backtick(`) 을 → 로 변환 (InDesign에서 `는 → 기호로 사용)
        script = script.replace("`", " -> ");
        // "@" 을 ^{2}로 변환 (InDesign NP폰트에서 @는 제곱을 의미)
        // 단, "int"는 적분 기호이므로 제외 (이미 변환됨)
        script = script.replaceAll("([a-zA-Z0-9])@", "$1 ^{2}");
        // 독립적인 @ 는 제곱
        script = script.replace("@", " ^{2}");
        // 중복된 화살표 제거: "-> -> " → "-> "
        while (script.contains("-> ->")) {
            script = script.replace("-> ->", "->");
        }
        // 연속 공백 정리
        script = script.replaceAll("\\s+", " ");
        // 연속 subscript 병합: _{a} _{b} → _{a b}
        script = mergeConsecutiveScripts(script, "_");
        script = mergeConsecutiveScripts(script, "^");
        return script.trim();
    }

    /**
     * 연속된 _{...} 또는 ^{...}를 병합한다.
     */
    private String mergeConsecutiveScripts(String script, String marker) {
        String pattern = "\\} " + (marker.equals("^") ? "\\^" : marker) + "\\{";
        return script.replaceAll(pattern, " ");
    }

    /**
     * 분수 Story를 파싱하여 분자/분모를 추출한다.
     * 분수 Story는 2개의 ParagraphStyleRange로 구성:
     * - 첫 번째 ParagraphStyleRange = 분자 (Br로 끝남)
     * - 두 번째 ParagraphStyleRange = 분모
     */
    private FractionContent parseFractionStory(String storyId) {
        try {
            File file = new File(storiesDir, "Story_" + storyId + ".xml");
            if (!file.exists()) return null;

            Document doc = parseXML(file);
            NodeList paraRanges = doc.getElementsByTagName("ParagraphStyleRange");
            if (paraRanges.getLength() == 0) return null;

            // 모든 ParagraphStyleRange의 내용을 순서대로 수집
            List<String> parts = new ArrayList<String>();
            for (int p = 0; p < paraRanges.getLength(); p++) {
                Element paraRange = (Element) paraRanges.item(p);
                StringBuilder partBuilder = new StringBuilder();
                List<Element> charRanges = getChildElements(paraRange, "CharacterStyleRange");

                for (Element charRange : charRanges) {
                    String styleRef = charRange.getAttribute("AppliedCharacterStyle");
                    String npFont = NPFontGlyphMap.extractNPFontName(styleRef);
                    String position = charRange.getAttribute("Position");

                    // 내부에 TextFrame이 있으면 재귀적으로 분수 처리
                    List<Element> innerFrames = getChildElements(charRange, "TextFrame");
                    for (Element tf : innerFrames) {
                        String innerStory = tf.getAttribute("ParentStory");
                        String objStyle = tf.getAttribute("AppliedObjectStyle");
                        if (objStyle.contains("분수")) {
                            FractionContent innerFrac = parseFractionStory(innerStory);
                            if (innerFrac != null) {
                                partBuilder.append("{").append(innerFrac.numerator)
                                        .append("} over {").append(innerFrac.denominator).append("}");
                            }
                        }
                    }

                    String text = getContentText(charRange);
                    if (text != null && !text.trim().isEmpty()) {
                        String processedText = text;
                        if (npFont != null) {
                            processedText = NPFontGlyphMap.mapGlyph(npFont, text);
                            NPFontGlyphMap.FontCategory cat = NPFontGlyphMap.getCategory(npFont);
                            if (cat == NPFontGlyphMap.FontCategory.SUBSCRIPT_INDEX
                                    || "Subscript".equals(position)) {
                                processedText = " _{" + processedText + "}";
                            } else if (cat == NPFontGlyphMap.FontCategory.SUPERSCRIPT_INDEX
                                    || "Superscript".equals(position)) {
                                processedText = " ^{" + processedText + "}";
                            } else if (cat == NPFontGlyphMap.FontCategory.SPECIAL_SYMBOL) {
                                processedText = NPFontGlyphMap.mapGlyph(npFont, text);
                            }
                        } else {
                            if ("Subscript".equals(position)) {
                                processedText = " _{" + processedText + "}";
                            } else if ("Superscript".equals(position)) {
                                processedText = " ^{" + processedText + "}";
                            }
                        }
                        partBuilder.append(processedText);
                    }
                }

                String part = partBuilder.toString().trim();
                if (!part.isEmpty()) {
                    parts.add(cleanHwpScript(part));
                }
            }

            if (parts.size() >= 2) {
                return new FractionContent(parts.get(0), parts.get(1));
            } else if (parts.size() == 1) {
                return new FractionContent(parts.get(0), "");
            }
        } catch (Exception e) {
            // Skip
        }
        return null;
    }

    /**
     * Limit/기타 블록 Story를 파싱하여 수식 내용을 추출한다.
     * (limit 표현 등이 별도의 TextFrame에 들어있는 경우)
     */
    private String parseLimitBlockStory(String storyId) {
        try {
            File file = new File(storiesDir, "Story_" + storyId + ".xml");
            if (!file.exists()) return null;

            Document doc = parseXML(file);
            NodeList paraRanges = doc.getElementsByTagName("ParagraphStyleRange");
            if (paraRanges.getLength() == 0) return null;

            StringBuilder result = new StringBuilder();
            for (int p = 0; p < paraRanges.getLength(); p++) {
                Element paraRange = (Element) paraRanges.item(p);
                List<Element> charRanges = getChildElements(paraRange, "CharacterStyleRange");

                boolean hasBreak = false;
                StringBuilder mainPart = new StringBuilder();
                StringBuilder subPart = new StringBuilder();

                for (Element charRange : charRanges) {
                    String styleRef = charRange.getAttribute("AppliedCharacterStyle");
                    String npFont = NPFontGlyphMap.extractNPFontName(styleRef);
                    String position = charRange.getAttribute("Position");
                    String text = getContentText(charRange);
                    List<Element> brs = getChildElements(charRange, "Br");

                    if (text != null && !text.trim().isEmpty()) {
                        String processed = text;
                        if (npFont != null) {
                            processed = NPFontGlyphMap.mapGlyph(npFont, text);
                        }

                        boolean isSub = "Subscript".equals(position);
                        if (isSub || hasBreak) {
                            subPart.append(processed);
                        } else {
                            mainPart.append(processed);
                        }
                    }

                    if (!brs.isEmpty()) {
                        hasBreak = true;
                    }
                }

                String main = mainPart.toString().trim();
                String sub = subPart.toString().trim();

                if (!main.isEmpty()) {
                    result.append(main);
                    if (!sub.isEmpty()) {
                        result.append(" _{").append(cleanHwpScript(sub)).append("}");
                    }
                } else if (!sub.isEmpty()) {
                    result.append(cleanHwpScript(sub));
                }
            }

            return result.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 인라인 수식 텍스트인지 판단한다.
     * (수식과 수식 사이의 연결 텍스트)
     */
    private boolean isInlineEquationText(String text, Element charRange) {
        if (text.isEmpty()) return false;
        // 한글이 포함되면 일반 텍스트
        for (char c : text.toCharArray()) {
            if (c >= '\uAC00' && c <= '\uD7A3') return false; // 한글
            if (c >= '\u3131' && c <= '\u318E') return false; // 자음/모음
        }
        // 수학 기호, 숫자, 영문자, 공백, 연산자면 수식 텍스트로 취급
        return text.matches("[a-zA-Z0-9+\\-*/=(){}\\[\\]<>^_., \\t]+");
    }

    /**
     * 짧은 수학 텍스트인지 확인 (수식 시작 전 변수명 등)
     */
    private boolean isShortMathText(String text) {
        if (text.isEmpty()) return false;
        // 짧은 영문/숫자/기호만 (변수명, 숫자, 수학기호)
        return text.length() <= 5 && text.matches("[a-zA-Z0-9+\\-*/=(){}\\[\\]<>^_., ]+");
    }

    // --- XML 유틸리티 ---

    private Document parseXML(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    private List<Element> getChildElements(Element parent, String tagName) {
        List<Element> result = new ArrayList<Element>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private String getContentText(Element charRange) {
        StringBuilder sb = new StringBuilder();
        NodeList children = charRange.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "Content".equals(node.getNodeName())) {
                sb.append(node.getTextContent());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // --- 내부 데이터 클래스들 ---

    /**
     * 추출된 수식 정보
     */
    public static class ExtractedEquation {
        public final String hwpScript;
        public final String paragraphStyle;

        public ExtractedEquation(String hwpScript, String paragraphStyle) {
            this.hwpScript = hwpScript;
            this.paragraphStyle = paragraphStyle;
        }

        @Override
        public String toString() {
            return hwpScript;
        }
    }

    /**
     * 수식 토큰
     */
    static class EquationToken {
        enum Type {
            NP_TEXT,        // NP 폰트 텍스트
            PLAIN_MATH,    // 일반 수학 텍스트
            FRACTION,      // 분수 (TextFrame)
            LIMIT_BLOCK    // Limit 블록 (TextFrame)
        }

        final Type type;
        final String text;
        final String npFont;
        final NPFontGlyphMap.FontCategory category;
        final boolean isSubscript;
        final boolean isSuperscript;
        final FractionContent fraction;

        EquationToken(Type type, String text, String npFont,
                      NPFontGlyphMap.FontCategory category,
                      boolean isSubscript, boolean isSuperscript) {
            this.type = type;
            this.text = text;
            this.npFont = npFont;
            this.category = category;
            this.isSubscript = isSubscript;
            this.isSuperscript = isSuperscript;
            this.fraction = null;
        }

        EquationToken(Type type, FractionContent fraction) {
            this.type = type;
            this.text = null;
            this.npFont = null;
            this.category = null;
            this.isSubscript = false;
            this.isSuperscript = false;
            this.fraction = fraction;
        }

        EquationToken(Type type, String text) {
            this.type = type;
            this.text = text;
            this.npFont = null;
            this.category = null;
            this.isSubscript = false;
            this.isSuperscript = false;
            this.fraction = null;
        }
    }

    /**
     * 분수 내용 (분자/분모)
     */
    static class FractionContent {
        final String numerator;
        final String denominator;

        FractionContent(String numerator, String denominator) {
            this.numerator = numerator;
            this.denominator = denominator;
        }
    }
}
