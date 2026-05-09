package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Style;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.TabPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.tabpr.TabItem;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.TabItemType;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HWPX 스타일 레지스트리.
 * 단락/문자 스타일 등록 및 ID 매핑을 관리한다.
 */
public class StyleRegistry {

    // 단락 스타일 ID → HWPX ParaPr ID
    private final Map<String, String> paraStyleIdToParaPrId = new LinkedHashMap<>();

    // 단락 스타일 ID → HWPX Style ID
    private final Map<String, String> paraStyleIdToStyleId = new LinkedHashMap<>();

    // 문자 스타일 ID → HWPX CharPr ID (단락 스타일의 CharPr도 포함)
    private final Map<String, String> charStyleIdToCharPrId = new LinkedHashMap<>();

    // 단락 스타일 ID → TabPr ID
    private final Map<String, String> paraStyleIdToTabPrId = new LinkedHashMap<>();

    // 인덱스 카운터
    private int nextCharPrIndex;
    private int nextParaPrIndex;
    private int nextStyleIndex;
    private int nextTabPrIndex;

    private final HWPXFile hwpxFile;
    private final FontRegistry fontRegistry;

    public StyleRegistry(HWPXFile hwpxFile, FontRegistry fontRegistry) {
        this.hwpxFile = hwpxFile;
        this.fontRegistry = fontRegistry;

        // 기존 HWPX 파일의 인덱스 파악
        this.nextCharPrIndex = hwpxFile.headerXMLFile().refList().charProperties().count();
        this.nextParaPrIndex = hwpxFile.headerXMLFile().refList().paraProperties().count();
        this.nextStyleIndex = hwpxFile.headerXMLFile().refList().styles().count();

        // TabProperties 초기화
        if (hwpxFile.headerXMLFile().refList().tabProperties() == null) {
            hwpxFile.headerXMLFile().refList().createTabProperties();
        }
        this.nextTabPrIndex = hwpxFile.headerXMLFile().refList().tabProperties().count();
    }

    /**
     * 단락 스타일을 등록한다.
     */
    public void registerParagraphStyle(ASTStyleDef styleDef) {
        String charPrId = String.valueOf(nextCharPrIndex++);
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();
        buildCharPr(charPr, charPrId, styleDef);

        String paraPrId = String.valueOf(nextParaPrIndex++);
        ParaPr paraPr = hwpxFile.headerXMLFile().refList().paraProperties().addNew();
        buildParaPr(paraPr, paraPrId, styleDef);

        String styleId = String.valueOf(nextStyleIndex++);
        Style style = hwpxFile.headerXMLFile().refList().styles().addNew();
        String name = styleDef.styleName() != null ? styleDef.styleName() : "Style_" + styleId;
        style.idAnd(styleId)
                .typeAnd(StyleType.PARA)
                .nameAnd(name)
                .engNameAnd(name)
                .paraPrIDRefAnd(paraPrId)
                .charPrIDRefAnd(charPrId)
                .nextStyleIDRefAnd(styleId)
                .langIDAnd("1042")
                .lockForm(false);

        paraStyleIdToParaPrId.put(styleDef.styleId(), paraPrId);
        paraStyleIdToStyleId.put(styleDef.styleId(), styleId);
        charStyleIdToCharPrId.put(styleDef.styleId(), charPrId);
    }

    /**
     * 문자 스타일을 등록한다.
     */
    public void registerCharacterStyle(ASTStyleDef styleDef) {
        String charPrId = String.valueOf(nextCharPrIndex++);
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();
        buildCharPr(charPr, charPrId, styleDef);
        charStyleIdToCharPrId.put(styleDef.styleId(), charPrId);
    }

    // ── Getters ──

    /**
     * 다음 CharPr ID를 할당하고 반환한다.
     * 인라인 스타일 오버라이드용 CharPr을 생성할 때 사용한다.
     */
    public String nextCharPrId() {
        return String.valueOf(nextCharPrIndex++);
    }

    /**
     * 다음 ParaPr ID를 할당하고 반환한다.
     * 인라인 단락 속성 오버라이드용 ParaPr을 생성할 때 사용한다.
     */
    public String nextParaPrId() {
        return String.valueOf(nextParaPrIndex++);
    }

    public String getParaPrId(String styleId) {
        return paraStyleIdToParaPrId.get(styleId);
    }

    public String getStyleId(String styleId) {
        return paraStyleIdToStyleId.get(styleId);
    }

    public String getCharPrId(String styleId) {
        return charStyleIdToCharPrId.get(styleId);
    }

    public String getTabPrId(String styleId) {
        return paraStyleIdToTabPrId.get(styleId);
    }

    public int paragraphStyleCount() {
        return paraStyleIdToParaPrId.size();
    }

    public int characterStyleCount() {
        return charStyleIdToCharPrId.size();
    }

    public int totalStyleCount() {
        return paragraphStyleCount() + characterStyleCount();
    }

    /**
     * ASTParagraph의 인라인 탭 정지점으로부터 TabPr을 생성한다.
     */
    public String createInlineTabPr(java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop> tabStops) {
        String tabPrId = String.valueOf(nextTabPrIndex++);
        TabPr tabPr = hwpxFile.headerXMLFile().refList().tabProperties().addNew();
        boolean hasRightTab = false;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop ts : tabStops) {
            if ("right".equals(ts.alignment())) { hasRightTab = true; break; }
        }

        // pos=0인 탭만 있으면 → autoTabLeft로 기본 탭 간격 설정 (pos=0은 HWPX에서 무시됨)
        boolean allZero = true;
        long maxPos = 0;
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop ts : tabStops) {
            if (ts.position() > 0) { allZero = false; }
            if (ts.position() > maxPos) maxPos = ts.position();
        }

        tabPr.idAnd(tabPrId)
                .autoTabLeftAnd(hasRightTab || allZero)  // pos=0만 있으면 auto tab 활성화
                .autoTabRightAnd(false);

        for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop ts : tabStops) {
            // HWPX에서 RIGHT 탭은 LEFT처럼 동작하여 텍스트가 탭 위치에서 시작됨
            // → RIGHT 탭은 건너뛰고, 해당 TAB 문자는 auto tab 간격으로 처리
            if ("right".equals(ts.alignment())) continue;
            // pos=0은 최소값 100(1pt)으로 보정하여 tabItem 생성 (빈 tabPr 방지)
            if (ts.position() <= 0) {
                TabItem item = tabPr.addNewTabItem();
                item.posAnd(100).typeAnd(TabItemType.LEFT).leaderAnd(LineType2.NONE).unitAnd(ValueUnit2.HWPUNIT);
                continue;
            }
            TabItem item = tabPr.addNewTabItem();
            item.posAnd((int) ts.position())
                    .typeAnd(mapTabItemType(ts.alignment()))
                    .leaderAnd(mapTabLeader(ts.leader()))
                    .unitAnd(ValueUnit2.HWPUNIT);
        }

        return tabPrId;
    }

    // ── Private helpers ──

    private TabItemType mapTabItemType(String alignment) {
        if (alignment == null) return TabItemType.LEFT;
        switch (alignment) {
            case "left": return TabItemType.LEFT;
            case "center": return TabItemType.CENTER;
            case "right": return TabItemType.RIGHT;
            case "decimal": return TabItemType.DECIMAL;
            default: return TabItemType.LEFT;
        }
    }

    private LineType2 mapTabLeader(String leader) {
        if (leader == null || leader.isEmpty()) return LineType2.NONE;
        if (".".equals(leader)) return LineType2.DOT;
        if ("-".equals(leader) || "—".equals(leader)) return LineType2.DASH;
        if ("_".equals(leader)) return LineType2.SOLID;
        return LineType2.NONE;
    }

    private void buildCharPr(CharPr charPr, String id, ASTStyleDef styleDef) {
        int height = styleDef.fontSizeHwpunits() != null ? styleDef.fontSizeHwpunits() : 1000;
        String textColor = (styleDef.textColor() != null && !styleDef.textColor().isEmpty()) ? styleDef.textColor() : "#000000";
        // bold/italic: 명시적 플래그 우선, 없으면 fontStyle 파싱
        boolean bold = Boolean.TRUE.equals(styleDef.bold());
        boolean italic = Boolean.TRUE.equals(styleDef.italic());
        if (!bold && !italic && styleDef.fontStyle() != null) {
            String fs = styleDef.fontStyle().toLowerCase();
            bold = fs.contains("bold");
            italic = fs.contains("italic") || fs.contains("oblique");
        }
        boolean underline = Boolean.TRUE.equals(styleDef.underline());
        boolean strikeThrough = Boolean.TRUE.equals(styleDef.strikeThrough());
        LineType3 ulShape = mapIdmlUnderlineType(styleDef.underlineType());
        CharPrBuilder.build(charPr, id, height, textColor,
                styleDef.fontFamily(), fontRegistry,
                styleDef.letterSpacing(),
                bold, italic,
                false, false,
                underline ? UnderlineType.BOTTOM : UnderlineType.NONE,
                underline ? textColor : "#000000",
                ulShape,
                styleDef.horizontalScale(),
                strikeThrough,
                null, null);
    }

    private void buildParaPr(ParaPr paraPr, String id, ASTStyleDef styleDef) {
        int indent = styleDef.firstLineIndent() != null ? styleDef.firstLineIndent().intValue() : 0;
        int left = styleDef.leftMargin() != null ? styleDef.leftMargin().intValue() : 0;
        int right = styleDef.rightMargin() != null ? styleDef.rightMargin().intValue() : 0;
        int prev = styleDef.spaceBefore() != null ? styleDef.spaceBefore().intValue() : 0;
        int next = styleDef.spaceAfter() != null ? styleDef.spaceAfter().intValue() : 0;

        // InDesign 탭 위치는 프레임 기준(절대) — HWPX에서도 동일하게 사용
        boolean hangingIndent = indent < 0 && left > 0;

        // 행잉 인덴트 들여쓰기 폭 결정
        // 1) IDML TabList Position 이 정의되어 있으면 그것을 사용 (디자인 의도)
        // 2) 없으면 |FirstLineIndent| 사용
        long hangPos = -indent;  // 기본값
        if (hangingIndent && styleDef.hasTabStops()) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop ts : styleDef.tabStops()) {
                if (ts.position() > 0) { hangPos = ts.position(); break; }
            }
        }

        // 행잉 인덴트에서 첫줄 offset(left + indent) 이 양수이면 첫줄을 셀/프레임 가장자리에 붙이고
        // 들여쓰기 폭은 hangPos 만큼만 유지하도록 정규화.
        if (hangingIndent && (left + indent) > 0) {
            left = (int) hangPos;
            indent = -(int) hangPos;
        }

        // 스타일 내 탭 정지점 → TabPr 생성
        String tabPrId = "0";
        if (hangingIndent) {
            // 행잉 인덴트의 자연스러운 탭 정지점 = 감싸진 줄이 시작하는 위치 = LeftIndent (hangPos)
            java.util.List<kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop> tabs =
                    new java.util.ArrayList<>();
            tabs.add(new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop(hangPos, "left", null));
            tabPrId = createInlineTabPr(tabs);
        } else if (styleDef.hasTabStops()) {
            // 인덴트가 없는 일반 스타일이라도 IDML TabList 가 있으면 그대로 보존.
            tabPrId = createInlineTabPr(styleDef.tabStops());
        }

        paraPr.idAnd(id)
                .tabPrIDRefAnd(tabPrId)
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        HorizontalAlign2 hAlign = mapAlignment(styleDef.alignment());
        paraPr.createAlign();
        paraPr.align().horizontalAnd(hAlign).vertical(VerticalAlign1.BASELINE);

        paraPr.createHeading();
        paraPr.heading().typeAnd(ParaHeadingType.NONE).idRefAnd("0").level((byte) 0);

        paraPr.createBreakSetting();
        paraPr.breakSetting()
                .breakLatinWordAnd(LineBreakForLatin.KEEP_WORD)
                .breakNonLatinWordAnd(LineBreakForNonLatin.KEEP_WORD)
                .widowOrphanAnd(false)
                .keepWithNextAnd(false)
                .keepLinesAnd(false)
                .pageBreakBeforeAnd(false)
                .lineWrap(LineWrap.BREAK);

        paraPr.createAutoSpacing();
        paraPr.autoSpacing().eAsianEngAnd(false).eAsianNum(false);

        paraPr.createMargin();
        paraPr.margin().createIntent();
        paraPr.margin().intent().valueAnd(indent).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createLeft();
        paraPr.margin().left().valueAnd(left).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createRight();
        paraPr.margin().right().valueAnd(right).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createPrev();
        paraPr.margin().prev().valueAnd(prev).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createNext();
        paraPr.margin().next().valueAnd(next).unit(ValueUnit2.HWPUNIT);

        LineSpacingType lsType = LineSpacingType.PERCENT;
        int lsValue = 130;
        if (styleDef.lineSpacingType() != null) {
            if ("fixed".equals(styleDef.lineSpacingType())) {
                lsType = LineSpacingType.FIXED;
            }
        }
        if (styleDef.lineSpacing() != null) {
            lsValue = styleDef.lineSpacing();
        }

        paraPr.createLineSpacing();
        paraPr.lineSpacing().typeAnd(lsType).valueAnd(lsValue).unit(ValueUnit2.HWPUNIT);

        paraPr.createBorder();
        paraPr.border().borderFillIDRefAnd("2")
                .offsetLeftAnd(0).offsetRightAnd(0)
                .offsetTopAnd(0).offsetBottomAnd(0)
                .connectAnd(false).ignoreMargin(false);
    }

    private static HorizontalAlign2 mapAlignment(String alignment) {
        return kr.dogfoot.hwpxlib.tool.idmlconverter.converter.HwpxEnumMapper.mapAlignment(alignment);
    }

    /**
     * IDML UnderlineType → HWPX LineType3 매핑.
     * IDML: "StrokeStyle/$ID/Wavy", "StrokeStyle/$ID/Dashed", etc.
     */
    static LineType3 mapIdmlUnderlineType(String idmlType) {
        if (idmlType == null) return null;
        String lower = idmlType.toLowerCase();
        if (lower.contains("wavy") || lower.contains("wave")) return LineType3.WAVE;
        if (lower.contains("dashed") || lower.contains("dash")) return LineType3.DASH;
        if (lower.contains("dotted") || lower.contains("dot")) return LineType3.DOT;
        return null;  // Solid는 기본값
    }
}
