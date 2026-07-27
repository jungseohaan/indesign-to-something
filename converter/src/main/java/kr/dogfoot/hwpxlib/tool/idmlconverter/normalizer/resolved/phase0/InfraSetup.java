package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase0;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFontDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLResourceParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * SPEC-013 Phase 0: IDML 폰트/스타일 정의 복사 + resolved 기반 스타일 보강.
 *
 * <p>{@code ResolvedToASTBuilder.copyIDMLDefinitions} / {@code enrichStyleAlignmentFromResolved}
 * 에서 stateless static helper로 발췌. 동작은 동일하며 ResolvedToASTBuilder는 위임만 한다.</p>
 *
 * <p>참고: stage 1 단계에서는 {@link ResolvedBuildContext}를 인자로 받지만, 점진적 분리 도중이므로
 * astDocument/idmlDir/resolvedData만 사용한다. 후속 stage에서 다른 phase가 의존하는 필드가
 * 추가되면 ctx 의존이 자연스럽게 늘어난다.</p>
 */
public final class InfraSetup {

    private InfraSetup() {}

    /**
     * IDML Resources/Styles.xml + Fonts.xml에서 폰트/단락 스타일 정의를 읽어 ASTDocument에 복사한다.
     * idmlDir이 null이면 무시 (IDML-Free 경로).
     */
    public static void copyIDMLDefinitions(ResolvedBuildContext ctx) {
        ASTDocument doc = ctx.astDocument;
        File idmlDir = ctx.idmlDir;
        if (idmlDir == null) {
            System.err.println("[ResolvedToASTBuilder] Phase 0: idmlDir is null — skipping");
            return;
        }

        try {
            // Styles.xml에서 폰트, 단락스타일, 색상 파싱
            File stylesFile = new File(new File(idmlDir, "Resources"), "Styles.xml");
            if (!stylesFile.exists()) return;
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // IDML의 ParagraphStyle은 200+ 속성을 가질 수 있으므로 제한 해제
            try { dbf.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit", "0"); } catch (Exception e) {}
            try { dbf.setAttribute("http://www.oracle.com/xml/jaxp/properties/totalEntitySizeLimit", "0"); } catch (Exception e) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document xmlDoc = db.parse(stylesFile);

            // 폰트 정의 수집 (Fonts.xml에서)
            File fontsFile = new File(new File(idmlDir, "Resources"), "Fonts.xml");
            if (fontsFile.exists()) {
                try {
                    org.w3c.dom.Document fontsDoc = db.parse(fontsFile);
                    org.w3c.dom.NodeList fontFamilies = fontsDoc.getElementsByTagName("FontFamily");
                    for (int i = 0; i < fontFamilies.getLength(); i++) {
                        org.w3c.dom.Element ff = (org.w3c.dom.Element) fontFamilies.item(i);
                        String name = ff.getAttribute("Name");
                        if (name != null && !name.isEmpty()) {
                            ASTFontDef fd = new ASTFontDef();
                            fd.fontFamily(name);
                            doc.addFont(fd);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[ResolvedToASTBuilder] Fonts.xml 파싱 실패: " + e.getMessage());
                }
            }

            // 단락 스타일 수집
            org.w3c.dom.NodeList paraStyles = xmlDoc.getElementsByTagName("ParagraphStyle");
            for (int i = 0; i < paraStyles.getLength(); i++) {
                org.w3c.dom.Element ps = (org.w3c.dom.Element) paraStyles.item(i);
                String self = ps.getAttribute("Self");
                String name = ps.getAttribute("Name");
                if (self != null && name != null) {
                    IDMLStyleDef resolvedStyle = ctx.styleResolver != null
                            ? ctx.styleResolver.getResolvedParagraphStyle(self) : null;
                    ASTStyleDef sd = new ASTStyleDef();
                    sd.styleId(self);
                    sd.styleName(name);
                    if (resolvedStyle != null) {
                        if (resolvedStyle.fontFamily() != null) sd.fontFamily(resolvedStyle.fontFamily());
                        if (resolvedStyle.fontSize() != null && resolvedStyle.fontSize() > 0) {
                            sd.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(resolvedStyle.fontSize()));
                        }
                        if (resolvedStyle.fillColor() != null) sd.textColor(resolvedStyle.fillColor());
                        if (resolvedStyle.fontStyle() != null) sd.fontStyle(resolvedStyle.fontStyle());
                        if (resolvedStyle.textAlignment() != null) sd.alignment(resolvedStyle.textAlignment());
                        if (resolvedStyle.leftIndent() != null) sd.leftMargin(CoordinateConverter.pointsToHwpunits(resolvedStyle.leftIndent()));
                        if (resolvedStyle.firstLineIndent() != null) sd.firstLineIndent(CoordinateConverter.pointsToHwpunits(resolvedStyle.firstLineIndent()));
                        if (resolvedStyle.rightIndent() != null) sd.rightMargin(CoordinateConverter.pointsToHwpunits(resolvedStyle.rightIndent()));
                        copyLeading(resolvedStyle, sd);
                    } else {
                        // 폰트 정보 추출
                        org.w3c.dom.NodeList appliedFonts = ps.getElementsByTagName("AppliedFont");
                        String font = null;
                        if (appliedFonts.getLength() > 0) {
                            font = appliedFonts.item(0).getTextContent();
                        }
                        if (font != null) sd.fontFamily(font);
                        String fontSize = ps.getAttribute("PointSize");
                        if (fontSize != null && !fontSize.isEmpty()) {
                            try { sd.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(Double.parseDouble(fontSize))); } catch (NumberFormatException e) {}
                        }
                        sd.textColor(ps.getAttribute("FillColor"));
                        sd.fontStyle(ps.getAttribute("FontStyle"));
                        copyDirectLeading(ps, sd);
                    }
                    // 정렬 (Justification)
                    String justification = ps.getAttribute("Justification");
                    if (justification != null && !justification.isEmpty()) {
                        sd.alignment(justification);
                    }
                    // 들여쓰기 (LeftIndent / FirstLineIndent / RightIndent) — pt → HWPUNIT
                    String leftIndentStr = ps.getAttribute("LeftIndent");
                    if (leftIndentStr != null && !leftIndentStr.isEmpty()) {
                        try { sd.leftMargin(CoordinateConverter.pointsToHwpunits(Double.parseDouble(leftIndentStr))); } catch (NumberFormatException e) {}
                    }
                    String firstLineStr = ps.getAttribute("FirstLineIndent");
                    if (firstLineStr != null && !firstLineStr.isEmpty()) {
                        try { sd.firstLineIndent(CoordinateConverter.pointsToHwpunits(Double.parseDouble(firstLineStr))); } catch (NumberFormatException e) {}
                    }
                    String rightIndentStr = ps.getAttribute("RightIndent");
                    if (rightIndentStr != null && !rightIndentStr.isEmpty()) {
                        try { sd.rightMargin(CoordinateConverter.pointsToHwpunits(Double.parseDouble(rightIndentStr))); } catch (NumberFormatException e) {}
                    }
                    // 탭 정지점 (TabList) — Properties/TabList 안의 ListItem들
                    org.w3c.dom.NodeList tabLists = ps.getElementsByTagName("TabList");
                    if (tabLists.getLength() > 0) {
                        org.w3c.dom.Element tabList = (org.w3c.dom.Element) tabLists.item(0);
                        org.w3c.dom.NodeList tabItems = tabList.getElementsByTagName("ListItem");
                        java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop> tabStops = new java.util.ArrayList<>();
                        for (int ti = 0; ti < tabItems.getLength(); ti++) {
                            org.w3c.dom.Element tabItem = (org.w3c.dom.Element) tabItems.item(ti);
                            String posStr = null;
                            String alignStr = "LeftAlign";
                            String leaderStr = null;
                            org.w3c.dom.NodeList children = tabItem.getChildNodes();
                            for (int ci = 0; ci < children.getLength(); ci++) {
                                if (children.item(ci).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                                org.w3c.dom.Element ch = (org.w3c.dom.Element) children.item(ci);
                                String tag = ch.getNodeName();
                                if ("Position".equals(tag)) posStr = ch.getTextContent();
                                else if ("Alignment".equals(tag)) alignStr = ch.getTextContent();
                                else if ("Leader".equals(tag)) leaderStr = ch.getTextContent();
                            }
                            if (posStr != null) {
                                try {
                                    long posHwpunits = CoordinateConverter.pointsToHwpunits(Double.parseDouble(posStr));
                                    // IDML Alignment → HWPX 탭 정렬 (LeftAlign/CenterAlign/RightAlign/CharacterAlign)
                                    String mappedAlign = "left";
                                    if (alignStr != null) {
                                        if (alignStr.contains("Right")) mappedAlign = "right";
                                        else if (alignStr.contains("Center")) mappedAlign = "center";
                                        else if (alignStr.contains("Character")) mappedAlign = "decimal";
                                    }
                                    tabStops.add(new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop(posHwpunits, mappedAlign, leaderStr));
                                } catch (NumberFormatException e) {}
                            }
                        }
                        if (!tabStops.isEmpty()) sd.tabStops(tabStops);
                    }
                    doc.addParagraphStyle(sd);
                }
            }
            org.w3c.dom.NodeList charStyles = xmlDoc.getElementsByTagName("CharacterStyle");
            for (int i = 0; i < charStyles.getLength(); i++) {
                org.w3c.dom.Element cs = (org.w3c.dom.Element) charStyles.item(i);
                String self = cs.getAttribute("Self");
                String name = cs.getAttribute("Name");
                if (self == null || self.isEmpty()) continue;

                IDMLStyleDef rawStyle = IDMLResourceParser.parseStyleDef(cs);
                IDMLStyleDef resolvedStyle = ctx.styleResolver != null
                        ? ctx.styleResolver.getResolvedCharacterStyle(self) : null;
                IDMLStyleDef style = resolvedStyle != null ? resolvedStyle : rawStyle;

                ASTStyleDef sd = new ASTStyleDef();
                sd.styleId(self);
                sd.styleName(name != null && !name.isEmpty()
                        ? name : (rawStyle != null ? rawStyle.simpleName() : self));
                if (rawStyle != null) sd.basedOnStyleRef(rawStyle.basedOn());
                copyCharacterStyle(style, sd);
                doc.addCharacterStyle(sd);
            }
            System.err.println("[ResolvedToASTBuilder] Phase 0: fonts=" + doc.fonts().size()
                    + " paragraphStyles=" + doc.paragraphStyles().size()
                    + " characterStyles=" + doc.characterStyles().size());
        } catch (Exception e) {
            System.err.println("[ResolvedToASTBuilder] IDML 정의 복사 실패: " + e.getMessage());
        }
    }

    private static void copyCharacterStyle(IDMLStyleDef source, ASTStyleDef target) {
        if (source == null || target == null) return;
        target.fontFamily(source.fontFamily());
        target.fontStyle(source.fontStyle());
        if (source.fontSize() != null && source.fontSize() > 0) {
            target.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(source.fontSize()));
        }
        if (source.fillColor() != null) {
            target.textColor(source.fillColor());
        }
        if (source.tracking() != null) {
            target.letterSpacing((short) Math.round(source.tracking() / 10.0));
        }
        target.bold(source.bold());
        target.italic(source.italic());
        if (source.horizontalScale() != null) {
            target.horizontalScale((short) Math.round(source.horizontalScale()));
        }
        target.underline(source.underline());
        target.underlineType(source.underlineType());
        target.underlineColor(source.underlineColor());
        target.strikeThrough(source.strikeThrough());
    }

    private static void copyLeading(IDMLStyleDef source, ASTStyleDef target) {
        if (source == null || target == null) return;
        if (source.leading() != null && source.leading() > 0) {
            target.lineSpacing((int) CoordinateConverter.pointsToHwpunits(source.leading()));
            target.lineSpacingType("fixed");
        } else if ("Auto".equalsIgnoreCase(source.leadingType())) {
            target.lineSpacingType("percent");
            double autoLeading = source.autoLeading() != null ? source.autoLeading() : 120.0;
            target.lineSpacing((int) Math.round(autoLeading));
        }
        if (source.autoLeading() != null) {
            target.autoLeading(source.autoLeading());
        }
    }

    private static void copyDirectLeading(org.w3c.dom.Element paragraphStyle, ASTStyleDef target) {
        if (paragraphStyle == null || target == null) return;
        String leading = firstPropertyText(paragraphStyle, "Leading");
        if (leading != null && !leading.isEmpty()) {
            if ("Auto".equalsIgnoreCase(leading)) {
                target.lineSpacingType("percent");
                target.lineSpacing(120);
            } else {
                try {
                    target.lineSpacing((int) CoordinateConverter.pointsToHwpunits(Double.parseDouble(leading)));
                    target.lineSpacingType("fixed");
                } catch (NumberFormatException ignored) {
                    // Ignore unknown InDesign leading tokens.
                }
            }
        }
        Double autoLeading = parseDoubleAttr(paragraphStyle, "AutoLeading");
        if (autoLeading != null) {
            target.autoLeading(autoLeading);
            if (target.lineSpacing() == null) {
                target.lineSpacingType("percent");
                target.lineSpacing((int) Math.round(autoLeading));
            }
        }
    }

    private static String firstPropertyText(org.w3c.dom.Element parent, String tagName) {
        org.w3c.dom.NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes == null || nodes.getLength() == 0) return null;
        String text = nodes.item(0).getTextContent();
        return text != null && !text.isEmpty() ? text : null;
    }

    private static Double parseDoubleAttr(org.w3c.dom.Element element, String attrName) {
        String value = element.getAttribute(attrName);
        if (value == null || value.isEmpty()) return null;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * resolved paragraphStyles의 justification으로 ASTDocument 스타일 보강.
     * 1차: resolved.json top-level paragraphStyles (가장 정확한 소스)
     * 2차: resolved Story 단락의 개별 justification (fallback)
     * IDML Styles.xml에 이미 Justification이 설정된 스타일은 건너뜀.
     */
    public static void enrichStyleAlignmentFromResolved(ResolvedBuildContext ctx) {
        ASTDocument doc = ctx.astDocument;
        if (ctx.resolvedData == null) return;

        // 1차 소스: resolved.json의 top-level paragraphStyles (ResolvedDataReader에서 파싱)
        Map<String, String> topLevelJustMap = ctx.resolvedData.paragraphStyleJustMap();

        // 2차 소스: 각 Story 단락의 개별 justification (fallback)
        Map<String, String> storyParaJustMap = new HashMap<>();
        try {
            for (String sid : ctx.resolvedData.allStoryIds()) {
                ResolvedStory rs = ctx.resolvedData.getStory(sid);
                if (rs == null || rs.paragraphs() == null) continue;
                for (ResolvedParagraph rp : rs.paragraphs()) {
                    if (rp.styleName() != null && rp.justification() != null) {
                        storyParaJustMap.putIfAbsent(rp.styleName(), rp.justification());
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }

        // ASTDocument 스타일에 alignment 보강
        int enriched = 0;
        for (ASTStyleDef sd : doc.paragraphStyles()) {
            if (sd.alignment() == null && sd.styleName() != null) {
                // 1차: top-level paragraphStyles
                String just = topLevelJustMap != null ? topLevelJustMap.get(sd.styleName()) : null;
                // 2차: Story 단락 fallback
                if (just == null) {
                    just = storyParaJustMap.get(sd.styleName());
                }
                if (just != null) {
                    sd.alignment(just);
                    enriched++;
                }
            }
        }
        if (enriched > 0) {
            System.err.println("[ResolvedToASTBuilder] enrichStyleAlignment: " + enriched
                    + " styles enriched from resolved (topLevel=" + (topLevelJustMap != null ? topLevelJustMap.size() : 0) + ")");
        }
    }
}
