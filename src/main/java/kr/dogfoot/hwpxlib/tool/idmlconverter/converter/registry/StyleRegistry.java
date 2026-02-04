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
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateStyleDef;

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

    // 단락 스타일 ID → IntermediateStyleDef
    private final Map<String, IntermediateStyleDef> paraStyleIdToStyleDef = new LinkedHashMap<>();

    // 문자 스타일 ID → IntermediateStyleDef
    private final Map<String, IntermediateStyleDef> charStyleIdToStyleDef = new LinkedHashMap<>();

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
    public void registerParagraphStyle(IntermediateStyleDef styleDef) {
        // CharPr 생성
        String charPrId = String.valueOf(nextCharPrIndex++);
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();
        buildCharPr(charPr, charPrId, styleDef);

        // TabPr 생성 (탭 정지점이 있는 경우)
        String tabPrId = "0";
        if (styleDef.tabStops() != null && !styleDef.tabStops().isEmpty()) {
            tabPrId = createTabPr(styleDef);
            paraStyleIdToTabPrId.put(styleDef.id(), tabPrId);
        }

        // ParaPr 생성
        String paraPrId = String.valueOf(nextParaPrIndex++);
        ParaPr paraPr = hwpxFile.headerXMLFile().refList().paraProperties().addNew();
        buildParaPr(paraPr, paraPrId, styleDef, tabPrId);

        // Style 생성
        String styleId = String.valueOf(nextStyleIndex++);
        Style style = hwpxFile.headerXMLFile().refList().styles().addNew();
        style.idAnd(styleId)
                .typeAnd(StyleType.PARA)
                .nameAnd(styleDef.name() != null ? styleDef.name() : "Style_" + styleId)
                .engNameAnd(styleDef.name() != null ? styleDef.name() : "Style_" + styleId)
                .paraPrIDRefAnd(paraPrId)
                .charPrIDRefAnd(charPrId)
                .nextStyleIDRefAnd(styleId)
                .langIDAnd("1042")
                .lockForm(false);

        paraStyleIdToParaPrId.put(styleDef.id(), paraPrId);
        paraStyleIdToStyleId.put(styleDef.id(), styleId);
        charStyleIdToCharPrId.put(styleDef.id(), charPrId);
        paraStyleIdToStyleDef.put(styleDef.id(), styleDef);
    }

    /**
     * 문자 스타일을 등록한다.
     */
    public void registerCharacterStyle(IntermediateStyleDef styleDef) {
        String charPrId = String.valueOf(nextCharPrIndex++);
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();
        buildCharPr(charPr, charPrId, styleDef);
        charStyleIdToCharPrId.put(styleDef.id(), charPrId);
        charStyleIdToStyleDef.put(styleDef.id(), styleDef);
    }

    // ── Getters ──

    /**
     * 다음 CharPr ID를 할당하고 반환한다.
     * 인라인 스타일 오버라이드용 CharPr을 생성할 때 사용한다.
     */
    public String nextCharPrId() {
        return String.valueOf(nextCharPrIndex++);
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

    public IntermediateStyleDef getParagraphStyleDef(String styleId) {
        return paraStyleIdToStyleDef.get(styleId);
    }

    public IntermediateStyleDef getCharacterStyleDef(String styleId) {
        return charStyleIdToStyleDef.get(styleId);
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

    // ── Private helpers ──

    private String createTabPr(IntermediateStyleDef styleDef) {
        String tabPrId = String.valueOf(nextTabPrIndex++);
        TabPr tabPr = hwpxFile.headerXMLFile().refList().tabProperties().addNew();
        tabPr.idAnd(tabPrId)
                .autoTabLeftAnd(false)
                .autoTabRightAnd(false);

        for (IntermediateStyleDef.TabStop ts : styleDef.tabStops()) {
            TabItem item = tabPr.addNewTabItem();
            item.posAnd((int) ts.position())
                    .typeAnd(mapTabItemType(ts.alignment()))
                    .leaderAnd(mapTabLeader(ts.leader()))
                    .unitAnd(ValueUnit2.HWPUNIT);
        }

        return tabPrId;
    }

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

    private void buildCharPr(CharPr charPr, String id, IntermediateStyleDef styleDef) {
        int height = styleDef.fontSizeHwpunits() != null ? styleDef.fontSizeHwpunits() : 1000;
        String textColor = styleDef.textColor() != null ? styleDef.textColor() : "#000000";

        charPr.idAnd(id)
                .heightAnd(height)
                .textColorAnd(textColor)
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");

        // Bold/Italic 설정
        if (Boolean.TRUE.equals(styleDef.bold())) {
            charPr.createBold();
        }
        if (Boolean.TRUE.equals(styleDef.italic())) {
            charPr.createItalic();
        }

        // 폰트 참조
        String fontId = fontRegistry.resolveFontId(styleDef.fontFamily());
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        charPr.createRatio();
        charPr.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);

        // 자간 (letterSpacing)
        short spacing = styleDef.letterSpacing() != null ? styleDef.letterSpacing() : 0;
        charPr.createSpacing();
        charPr.spacing().set(spacing, spacing, spacing, spacing, spacing, spacing, spacing);

        charPr.createRelSz();
        charPr.relSz().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);

        charPr.createOffset();
        charPr.offset().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);

        charPr.createUnderline();
        charPr.underline().typeAnd(UnderlineType.NONE).shapeAnd(LineType3.SOLID).color("#000000");

        charPr.createStrikeout();
        charPr.strikeout().shapeAnd(LineType2.NONE).color("#000000");

        charPr.createOutline();
        charPr.outline().type(LineType1.NONE);

        charPr.createShadow();
        charPr.shadow().typeAnd(CharShadowType.NONE).colorAnd("#B2B2B2")
                .offsetXAnd((short) 10).offsetY((short) 10);
    }

    private void buildParaPr(ParaPr paraPr, String id, IntermediateStyleDef styleDef, String tabPrId) {
        paraPr.idAnd(id)
                .tabPrIDRefAnd(tabPrId)
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        // 정렬
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

        // 마진
        int indent = styleDef.firstLineIndent() != null ? styleDef.firstLineIndent().intValue() : 0;
        int left = styleDef.leftMargin() != null ? styleDef.leftMargin().intValue() : 0;
        int right = styleDef.rightMargin() != null ? styleDef.rightMargin().intValue() : 0;
        int prev = styleDef.spaceBefore() != null ? styleDef.spaceBefore().intValue() : 0;
        int next = styleDef.spaceAfter() != null ? styleDef.spaceAfter().intValue() : 0;

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

        // 줄 간격
        LineSpacingType lsType = LineSpacingType.PERCENT;
        int lsValue = 130;
        if (styleDef.lineSpacingType() != null) {
            if ("fixed".equals(styleDef.lineSpacingType())) {
                lsType = LineSpacingType.FIXED;
            }
        }
        if (styleDef.lineSpacingPercent() != null) {
            lsValue = styleDef.lineSpacingPercent();
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
        if (alignment == null) return HorizontalAlign2.JUSTIFY;
        switch (alignment) {
            case "left":
                return HorizontalAlign2.LEFT;
            case "center":
                return HorizontalAlign2.CENTER;
            case "right":
                return HorizontalAlign2.RIGHT;
            case "justify":
                return HorizontalAlign2.JUSTIFY;
            default:
                return HorizontalAlign2.JUSTIFY;
        }
    }
}
