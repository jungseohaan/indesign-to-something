package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import org.w3c.dom.*;
import java.util.*;
import static kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLXmlUtils.*;

/**
 * IDML 리소스 파싱: designmap, fonts, styles, colors, 마스터 스프레드 마진.
 */
class IDMLResourceParser {

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
            } else if ("Layer".equals(tagName)) {
                String layerVisible = elem.getAttribute("Visible");
                if ("false".equals(layerVisible)) {
                    String layerSelf = elem.getAttribute("Self");
                    if (layerSelf != null && !layerSelf.isEmpty()) {
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
    }

    static IDMLStyleDef parseStyleDef(Element styleElem) {
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

        // 밑줄 / 취소선
        String underline = getAttrOrNull(styleElem, "Underline");
        if ("true".equalsIgnoreCase(underline)) def.underline(true);
        String strikeThru = getAttrOrNull(styleElem, "StrikeThru");
        if ("true".equalsIgnoreCase(strikeThru)) def.strikeThrough(true);

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

        return null;
    }

}
