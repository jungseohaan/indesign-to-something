package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import org.w3c.dom.*;
import java.util.*;
import static kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLXmlUtils.*;

/**
 * IDML 리소스 파싱: designmap, fonts, styles, colors, 마스터 스프레드 마진.
 */
public class IDMLResourceParser {

    // ===== designmap.xml 파싱 =====

    static void parseDesignmap(Document designmap,
                               List<String> spreadSources,
                               List<String> masterSpreadSources,
                               List<IDMLLoader.SectionInfo> sections,
                               IDMLDocument doc) {
        Element root = designmap.getDocumentElement();

        // Spread 참조 수집 (순서 유지)
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element elem = (Element) node;
            String tagName = elem.getTagName();

            if ("idPkg:Spread".equals(tagName)) {
                String src = elem.getAttribute("src");
                if (src != null && !src.isEmpty()) {
                    spreadSources.add(src);
                }
            } else if ("idPkg:MasterSpread".equals(tagName)) {
                String src = elem.getAttribute("src");
                if (src != null && !src.isEmpty()) {
                    masterSpreadSources.add(src);
                }
            } else if ("Section".equals(tagName)) {
                IDMLLoader.SectionInfo si = new IDMLLoader.SectionInfo();
                si.selfId = elem.getAttribute("Self");
                si.pageStart = elem.getAttribute("PageStart");
                si.pageNumberStart = parseIntAttr(elem, "PageNumberStart", 1);
                si.length = parseIntAttr(elem, "Length", 0);
                si.name = elem.getAttribute("Name");
                si.marker = elem.getAttribute("Marker");
                sections.add(si);
            } else if ("TextVariable".equals(tagName)) {
                // MatchCharacterStyleType / MatchParagraphStyleType 변수 → 스타일 기반 Running Header
                String varType = getAttrOrNull(elem, "VariableType");
                String varName = getAttrOrNull(elem, "Name");
                if (varName != null && ("MatchCharacterStyleType".equals(varType)
                        || "MatchParagraphStyleType".equals(varType))) {
                    // 자식 요소에서 AppliedCharacterStyle/AppliedParagraphStyle 추출
                    NodeList varChildren = elem.getChildNodes();
                    for (int vc = 0; vc < varChildren.getLength(); vc++) {
                        Node vcNode = varChildren.item(vc);
                        if (vcNode.getNodeType() != Node.ELEMENT_NODE) continue;
                        Element pref = (Element) vcNode;
                        String styleRef = getAttrOrNull(pref, "AppliedCharacterStyle");
                        if (styleRef == null) styleRef = getAttrOrNull(pref, "AppliedParagraphStyle");
                        if (styleRef != null) {
                            doc.putTextVariableStyleRef(varName, styleRef);
                        }
                    }
                }
            } else if ("Layer".equals(tagName)) {
                String layerSelf = elem.getAttribute("Self");
                if (layerSelf != null && !layerSelf.isEmpty()) {
                    doc.addLayerId(layerSelf);  // front-to-back 순서 보존
                    String layerVisible = elem.getAttribute("Visible");
                    if ("false".equals(layerVisible)) {
                        doc.addHiddenLayerId(layerSelf);
                    }
                }
            }
        }
    }

    // ===== Resources/Fonts.xml 파싱 =====

    static void parseFonts(Document fontsDoc, IDMLDocument doc) {
        NodeList fontFamilies = fontsDoc.getElementsByTagName("FontFamily");
        for (int i = 0; i < fontFamilies.getLength(); i++) {
            Element family = (Element) fontFamilies.item(i);
            String familyName = family.getAttribute("Name");

            List<Element> fonts = getChildElements(family, "Font");
            for (Element fontElem : fonts) {
                IDMLFontDef fontDef = new IDMLFontDef();
                fontDef.selfRef(fontElem.getAttribute("Self"));
                fontDef.fontFamily(familyName);
                fontDef.fontStyleName(fontElem.getAttribute("FontStyleName"));
                fontDef.postScriptName(fontElem.getAttribute("PostScriptName"));
                fontDef.fontType(fontElem.getAttribute("FontType"));
                doc.putFont(fontDef.selfRef(), fontDef);
            }
        }
    }

    // ===== Resources/Styles.xml 파싱 =====

    static void parseStyles(Document stylesDoc, IDMLDocument doc) {
        // ParagraphStyle
        NodeList paraStyles = stylesDoc.getElementsByTagName("ParagraphStyle");
        for (int i = 0; i < paraStyles.getLength(); i++) {
            Element styleElem = (Element) paraStyles.item(i);
            IDMLStyleDef styleDef = parseStyleDef(styleElem);
            doc.putParagraphStyle(styleDef.selfRef(), styleDef);
        }

        // CharacterStyle
        NodeList charStyles = stylesDoc.getElementsByTagName("CharacterStyle");
        for (int i = 0; i < charStyles.getLength(); i++) {
            Element styleElem = (Element) charStyles.item(i);
            IDMLStyleDef styleDef = parseStyleDef(styleElem);
            doc.putCharacterStyle(styleDef.selfRef(), styleDef);
        }

        // CellStyle (text inset/margin)
        NodeList cellStyles = stylesDoc.getElementsByTagName("CellStyle");
        for (int i = 0; i < cellStyles.getLength(); i++) {
            Element elem = (Element) cellStyles.item(i);
            String self = elem.getAttribute("Self");
            if (self == null || self.isEmpty()) continue;
            double top = firstDoubleAttr(elem, 4.0, "TextTopInset", "TopInset");
            double left = firstDoubleAttr(elem, 4.0, "TextLeftInset", "LeftInset");
            double bottom = firstDoubleAttr(elem, 4.0, "TextBottomInset", "BottomInset");
            double right = firstDoubleAttr(elem, 4.0, "TextRightInset", "RightInset");
            String firstBaselineOffset = getAttrOrNull(elem, "FirstBaselineOffset");
            Double minimumFirstBaselineOffset = firstDoubleAttr(elem, "MinimumFirstBaselineOffset");
            String verticalJustification = getAttrOrNull(elem, "VerticalJustification");
            doc.putCellStyle(self, top, left, bottom, right,
                    firstBaselineOffset, minimumFirstBaselineOffset, verticalJustification);
        }

        // ObjectStyle (stroke 색상/두께 + CornerRadius 파싱)
        NodeList objStyles = stylesDoc.getElementsByTagName("ObjectStyle");
        for (int i = 0; i < objStyles.getLength(); i++) {
            Element elem = (Element) objStyles.item(i);
            String self = elem.getAttribute("Self");
            String strokeColor = getAttrOrNull(elem, "StrokeColor");
            String strokeWeight = getAttrOrNull(elem, "StrokeWeight");
            String strokeTint = getAttrOrNull(elem, "StrokeTint");
            if (self != null && strokeColor != null) {
                doc.putObjectStyle(self, strokeColor, strokeWeight, strokeTint);
            }
            // CornerRadius 저장 (ObjectStyle 상속용)
            // CornerOption이 "RoundedCorner"가 아니면 라운딩 비활성 → 저장 불필요
            if (self != null) {
                String cornerOpt = getAttrOrNull(elem, "CornerOption");
                if (cornerOpt != null && cornerOpt.contains("RoundedCorner")) {
                    double cr = parseDoubleAttrDef(elem, "CornerRadius", 0);
                    if (cr > 0) {
                        doc.putObjectStyleCornerRadius(self, cr);
                    }
                }
            }
        }
    }

    public static IDMLStyleDef parseStyleDef(Element styleElem) {
        IDMLStyleDef def = new IDMLStyleDef();
        def.selfRef(styleElem.getAttribute("Self"));
        def.name(styleElem.getAttribute("Name"));
        def.fontStyle(getAttrOrNull(styleElem, "FontStyle"));
        def.fillColor(getAttrOrNull(styleElem, "FillColor"));
        def.textAlignment(getAttrOrNull(styleElem, "Justification"));

        // 숫자 속성
        def.fontSize(parseDoubleAttr(styleElem, "PointSize"));
        def.firstLineIndent(parseDoubleAttr(styleElem, "FirstLineIndent"));
        def.leftIndent(parseDoubleAttr(styleElem, "LeftIndent"));
        def.rightIndent(parseDoubleAttr(styleElem, "RightIndent"));
        def.spaceBefore(parseDoubleAttr(styleElem, "SpaceBefore"));
        def.spaceAfter(parseDoubleAttr(styleElem, "SpaceAfter"));
        def.horizontalScale(parseDoubleAttr(styleElem, "HorizontalScale"));
        def.tracking(parseDoubleAttr(styleElem, "Tracking"));
        def.baselineShift(parseDoubleAttr(styleElem, "BaselineShift"));
        def.position(getAttrOrNull(styleElem, "Position"));
        def.capitalization(getAttrOrNull(styleElem, "Capitalization"));
        String directShadeColor = firstAttr(styleElem,
                "CharacterShadingColor", "ShadingColor", "TextShadingColor");
        if (isShadingEnabled(styleElem, directShadeColor)) {
            def.shadeColor(directShadeColor);
        }
        def.shadeTint(firstDoubleAttr(styleElem,
                "CharacterShadingTint", "ShadingTint", "TextShadingTint"));
        def.underlineWeight(parseDoubleAttr(styleElem, "UnderlineWeight"));
        def.underlineOffset(parseDoubleAttr(styleElem, "UnderlineOffset"));
        def.ruleAboveLineWeight(parseDoubleAttr(styleElem, "RuleAboveLineWeight"));
        def.ruleBelowLineWeight(parseDoubleAttr(styleElem, "RuleBelowLineWeight"));

        // 밑줄 / 취소선
        String underline = getAttrOrNull(styleElem, "Underline");
        if ("true".equalsIgnoreCase(underline)) def.underline(true);
        String strikeThru = getAttrOrNull(styleElem, "StrikeThru");
        if ("true".equalsIgnoreCase(strikeThru)) def.strikeThrough(true);

        // 단락 아래선 (RuleBelow)
        String ruleBelow = getAttrOrNull(styleElem, "RuleBelow");
        if ("true".equalsIgnoreCase(ruleBelow)) def.ruleBelowOn(true);

        // 단락 분리 제어
        String keepWithNext = getAttrOrNull(styleElem, "KeepWithNext");
        if (keepWithNext != null) def.keepWithNext("true".equalsIgnoreCase(keepWithNext));
        String keepAllLines = getAttrOrNull(styleElem, "KeepAllLinesTogether");
        if (keepAllLines != null) def.keepLinesTogether("true".equalsIgnoreCase(keepAllLines));
        String startPara = getAttrOrNull(styleElem, "StartParagraph");
        if (startPara != null) {
            def.pageBreakBefore("NextPage".equalsIgnoreCase(startPara)
                    || "NextOddPage".equalsIgnoreCase(startPara)
                    || "NextEvenPage".equalsIgnoreCase(startPara));
        }

        // 두문자 (DropCap)
        Integer dropCapLines = parseIntAttrNullable(styleElem, "DropCapLines");
        Integer dropCapChars = parseIntAttrNullable(styleElem, "DropCapCharacters");
        if (dropCapLines != null && dropCapLines > 0) {
            def.dropCapLines(dropCapLines);
            def.dropCapCharacters(dropCapChars != null ? dropCapChars : 1);
        }

        // Properties 안의 값들
        Element props = getFirstChildElement(styleElem, "Properties");
        if (props != null) {
            def.basedOn(getPropertyText(props, "BasedOn"));
            def.fontFamily(getPropertyText(props, "AppliedFont"));

            // UnderlineType (Properties 안: <UnderlineType type="object">StrokeStyle/$ID/Wavy</UnderlineType>)
            String ulType = getPropertyText(props, "UnderlineType");
            if (ulType != null) {
                def.underlineType(ulType);
            }
            def.underlineColor(getPropertyText(props, "UnderlineColor"));
            String propShadeColor = firstPropertyText(props,
                    "CharacterShadingColor", "ShadingColor", "TextShadingColor");
            if (def.shadeColor() == null && isShadingEnabled(props, propShadeColor)) {
                def.shadeColor(propShadeColor);
            }
            if (def.shadeTint() == null) {
                def.shadeTint(firstPropertyDouble(props,
                        "CharacterShadingTint", "ShadingTint", "TextShadingTint"));
            }
            def.ruleAboveColor(getPropertyText(props, "RuleAboveColor"));
            def.ruleBelowColor(getPropertyText(props, "RuleBelowColor"));

            // Leading (행간)
            String leadingText = getPropertyText(props, "Leading");
            if (leadingText != null) {
                if ("Auto".equalsIgnoreCase(leadingText)) {
                    def.leadingType("Auto");
                } else {
                    try {
                        def.leading(Double.parseDouble(leadingText));
                        def.leadingType("Fixed");
                    } catch (NumberFormatException e) {
                        def.leadingType(leadingText);
                    }
                }
            }
        }

        // Bold/Italic 판별 (FontStyle 속성에서)
        String fontStyle = def.fontStyle();
        if (fontStyle != null) {
            String lower = fontStyle.toLowerCase();
            def.bold(lower.contains("bold"));
            def.italic(lower.contains("italic") || lower.contains("oblique"));
        }

        // Word Spacing (DesiredWordSpacing, MinimumWordSpacing, MaximumWordSpacing)
        def.desiredWordSpacing(parseDoubleAttr(styleElem, "DesiredWordSpacing"));
        def.minimumWordSpacing(parseDoubleAttr(styleElem, "MinimumWordSpacing"));
        def.maximumWordSpacing(parseDoubleAttr(styleElem, "MaximumWordSpacing"));

        // AutoLeading (퍼센트 값)
        def.autoLeading(parseDoubleAttr(styleElem, "AutoLeading"));

        // TabList (탭 정지점 목록)
        Element props2 = getFirstChildElement(styleElem, "Properties");
        if (props2 != null) {
            // AllGREPStyles (GREP 스타일 규칙 — 단락 스타일에서만 존재)
            Element allGrepStyles = getFirstChildElement(props2, "AllGREPStyles");
            if (allGrepStyles != null) {
                List<Element> grepItems = getChildElements(allGrepStyles, "ListItem");
                for (Element item : grepItems) {
                    String charStyle = getChildElementText(item, "AppliedCharacterStyle");
                    String grepExpr = getChildElementText(item, "GrepExpression");
                    if (charStyle != null && grepExpr != null) {
                        def.addGrepStyle(new IDMLStyleDef.GrepStyleRule(grepExpr, charStyle));
                    }
                }
            }

            // AllNestedStyles (delimiter 기반 문자 스타일 규칙 — 단락 스타일에서만 존재)
            Element allNestedStyles = getFirstChildElement(props2, "AllNestedStyles");
            if (allNestedStyles != null) {
                List<Element> nestedItems = getChildElements(allNestedStyles, "ListItem");
                for (Element item : nestedItems) {
                    String charStyle = getChildElementText(item, "AppliedCharacterStyle");
                    String delimiter = getChildElementText(item, "Delimiter");
                    int repetition = parseChildElementInt(item, "Repetition", 1);
                    boolean inclusive = Boolean.parseBoolean(
                            String.valueOf(getChildElementText(item, "Inclusive")));
                    if (charStyle != null && delimiter != null && !delimiter.isEmpty()) {
                        def.addNestedStyle(new IDMLStyleDef.NestedStyleRule(
                                delimiter, charStyle, Math.max(1, repetition), inclusive));
                    }
                }
            }

            Element tabList = getFirstChildElement(props2, "TabList");
            if (tabList != null) {
                List<Element> listItems = getChildElements(tabList, "ListItem");
                for (Element item : listItems) {
                    Double position = parseChildElementDouble(item, "Position");
                    String alignment = getChildElementText(item, "Alignment");
                    String leader = getChildElementText(item, "Leader");
                    if (position != null) {
                        def.addTabStop(new IDMLStyleDef.TabStop(position, alignment, leader));
                    }
                }
            }
        }

        return def;
    }

    private static String firstAttr(Element elem, String... names) {
        if (elem == null || names == null) return null;
        for (String name : names) {
            String value = getAttrOrNull(elem, name);
            if (value != null && !value.isEmpty() && !"Nothing".equalsIgnoreCase(value)
                    && !"None".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private static Double firstDoubleAttr(Element elem, String... names) {
        String value = firstAttr(elem, names);
        if (value == null) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double firstDoubleAttr(Element elem, double fallback, String... names) {
        Double value = firstDoubleAttr(elem, names);
        return value != null ? value : fallback;
    }

    private static String firstPropertyText(Element props, String... names) {
        if (props == null || names == null) return null;
        for (String name : names) {
            String value = getPropertyText(props, name);
            if (value != null && !value.isEmpty() && !"Nothing".equalsIgnoreCase(value)
                    && !"None".equalsIgnoreCase(value)) {
                return value;
            }
        }
        return null;
    }

    private static Double firstPropertyDouble(Element props, String... names) {
        String value = firstPropertyText(props, names);
        if (value == null) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isShadingEnabled(Element elem, String color) {
        if (elem == null) return false;
        String on = firstAttr(elem, "CharacterShadingOn", "ShadingOn", "TextShadingOn");
        if (on != null) return "true".equalsIgnoreCase(on);
        String propOn = getPropertyText(elem, "CharacterShadingOn");
        if (propOn == null) propOn = getPropertyText(elem, "ShadingOn");
        if (propOn == null) propOn = getPropertyText(elem, "TextShadingOn");
        if (propOn != null) return "true".equalsIgnoreCase(propOn);
        return color != null;
    }

    // ===== Resources/Graphic.xml 파싱 =====

    static void parseGraphic(Document graphicDoc, IDMLDocument doc) {
        // 색상 파싱
        NodeList colors = graphicDoc.getElementsByTagName("Color");
        for (int i = 0; i < colors.getLength(); i++) {
            Element colorElem = (Element) colors.item(i);
            String selfRef = colorElem.getAttribute("Self");
            String colorValue = colorElem.getAttribute("ColorValue");
            String model = colorElem.getAttribute("Model");
            String space = colorElem.getAttribute("Space");

            String hexColor = convertColorToHex(colorValue, model, space);
            if (hexColor != null) {
                doc.putColor(selfRef, hexColor);
            }
        }

        // DashedStrokeStyle 파싱: 사용자 정의 점선/파선 패턴
        NodeList dashedStyles = graphicDoc.getElementsByTagName("DashedStrokeStyle");
        for (int i = 0; i < dashedStyles.getLength(); i++) {
            Element elem = (Element) dashedStyles.item(i);
            String self = elem.getAttribute("Self");
            String dashArrayStr = getAttrOrNull(elem, "DashArray");
            if (self != null && dashArrayStr != null) {
                String[] parts = dashArrayStr.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        double[] dashArray = new double[parts.length];
                        for (int j = 0; j < parts.length; j++) {
                            dashArray[j] = Double.parseDouble(parts[j]);
                        }
                        doc.putDashedStrokeStyle(self, dashArray);
                    } catch (NumberFormatException e) {
                        System.err.println("[IDMLResourceParser] DashArray 파싱 실패: " + dashArrayStr);
                    }
                }
            }
        }

        // 그레이디언트 파싱: 첫 번째 GradientStop의 색상을 대표 색상으로 등록
        NodeList gradients = graphicDoc.getElementsByTagName("Gradient");
        for (int i = 0; i < gradients.getLength(); i++) {
            Element gradientElem = (Element) gradients.item(i);
            String selfRef = gradientElem.getAttribute("Self");

            // 첫 번째 GradientStop의 StopColor를 찾아 대표 색상으로 사용
            NodeList stops = gradientElem.getElementsByTagName("GradientStop");
            if (stops.getLength() > 0) {
                Element firstStop = (Element) stops.item(0);
                String stopColorRef = firstStop.getAttribute("StopColor");
                if (stopColorRef != null && !stopColorRef.isEmpty()) {
                    String hexColor = doc.getColor(stopColorRef);
                    if (hexColor != null) {
                        doc.putColor(selfRef, hexColor);
                    }
                }
            }
        }
    }

    /**
     * CMYK 또는 RGB ColorValue를 #RRGGBB로 변환한다.
     */
    public static String convertColorToHex(String colorValue, String model, String space) {
        if (colorValue == null || colorValue.isEmpty()) return null;

        String[] parts = colorValue.trim().split("\\s+");
        try {
            if ("CMYK".equals(space) && parts.length >= 4) {
                double c = Double.parseDouble(parts[0]) / 100.0;
                double m = Double.parseDouble(parts[1]) / 100.0;
                double y = Double.parseDouble(parts[2]) / 100.0;
                double k = Double.parseDouble(parts[3]) / 100.0;
                return kr.dogfoot.hwpxlib.tool.idmlconverter.util.CMYKColorConverter.cmykToHex(c, m, y, k);
            } else if ("RGB".equals(space) && parts.length >= 3) {
                int r = Math.max(0, Math.min(255, (int) Math.round(Double.parseDouble(parts[0]))));
                int g = Math.max(0, Math.min(255, (int) Math.round(Double.parseDouble(parts[1]))));
                int b = Math.max(0, Math.min(255, (int) Math.round(Double.parseDouble(parts[2]))));
                return String.format("#%02X%02X%02X", r, g, b);
            }
        } catch (NumberFormatException e) {
            System.err.println("[IDMLResourceParser] 색상값 파싱 실패: " + colorValue + " (" + space + ")");
        }

        return null;
    }

    private static int parseChildElementInt(Element parent, String childName, int fallback) {
        String text = getChildElementText(parent, childName);
        if (text == null || text.isEmpty()) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

}
