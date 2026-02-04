package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Fontface;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.Style;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.fontface.Font;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.parapr.LineSpacing;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.parapr.ParaMargin;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.ParaListCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertResult;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IntermediateDocument를 HWPXFile로 변환한다.
 *
 * 변환 전략:
 * 1. BlankFileMaker로 기본 HWPX 구조 생성
 * 2. 폰트 등록 (Fontfaces)
 * 3. 스타일별 CharPr/ParaPr/Style 등록
 * 4. 페이지별 Para/Run 생성
 * 5. 수식은 EquationBuilder.fromHwpScript()
 * 6. 이미지는 ImageInserter.registerImage() + insertInline()
 * 7. 페이지 크기 변경 시 SecPr로 섹션 분리
 */
public class IntermediateToHwpxConverter {

    private static final AtomicLong PARA_ID_COUNTER = new AtomicLong(1000000000L);
    private static final AtomicLong SHAPE_ID_COUNTER = new AtomicLong(5000000L);

    /**
     * IntermediateDocument를 ConvertResult로 변환한다.
     */
    public static ConvertResult convert(IntermediateDocument doc) throws ConvertException {
        try {
            return new IntermediateToHwpxConverter(doc).doConvert();
        } catch (ConvertException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.HWPX_GENERATION,
                    "Failed to convert intermediate to HWPX: " + e.getMessage(), e);
        }
    }

    private final IntermediateDocument doc;
    private final ConvertResult result;
    private HWPXFile hwpxFile;

    // 스타일 ID → HWPX 내부 인덱스 매핑
    private final Map<String, String> fontNameToId;
    private final Map<String, String> paraStyleIdToParaPrId;
    private final Map<String, String> paraStyleIdToStyleId;
    private final Map<String, String> charStyleIdToCharPrId;
    private final Map<String, IntermediateStyleDef> paraStyleIdToStyleDef;
    private final Map<String, IntermediateStyleDef> charStyleIdToStyleDef;

    private int nextCharPrIndex;
    private int nextParaPrIndex;
    private int nextStyleIndex;

    private IntermediateToHwpxConverter(IntermediateDocument doc) {
        this.doc = doc;
        this.result = new ConvertResult();
        this.fontNameToId = new LinkedHashMap<String, String>();
        this.paraStyleIdToParaPrId = new LinkedHashMap<String, String>();
        this.paraStyleIdToStyleId = new LinkedHashMap<String, String>();
        this.charStyleIdToCharPrId = new LinkedHashMap<String, String>();
        this.paraStyleIdToStyleDef = new LinkedHashMap<String, IntermediateStyleDef>();
        this.charStyleIdToStyleDef = new LinkedHashMap<String, IntermediateStyleDef>();
    }

    private ConvertResult doConvert() throws ConvertException {
        // 1. BlankFileMaker로 기본 구조 생성
        hwpxFile = BlankFileMaker.make();

        // 기존 기본 스타일 인덱스 파악
        nextCharPrIndex = hwpxFile.headerXMLFile().refList().charProperties().count();
        nextParaPrIndex = hwpxFile.headerXMLFile().refList().paraProperties().count();
        nextStyleIndex = hwpxFile.headerXMLFile().refList().styles().count();

        // 2. 폰트 등록
        registerFonts();

        // 3. 스타일 등록
        registerStyles();

        // 4. 스프레드/페이지별로 별도 section 파일 생성
        int pagesConverted = 0;
        int framesConverted = 0;
        int equationsConverted = 0;
        int imagesConverted = 0;

        // 기존 section0 초기화 (첫 페이지용)
        SectionXMLFile section0 = hwpxFile.sectionXMLFileList().get(0);
        section0.removeAllParas();

        if (doc.useSpreadMode() && doc.spreads() != null && !doc.spreads().isEmpty()) {
            // === 스프레드 모드: 각 스프레드를 하나의 section으로 변환 ===
            int spreadIndex = 0;
            for (IntermediateSpread spread : doc.spreads()) {
                SectionXMLFile section;
                if (spreadIndex == 0) {
                    section = section0;
                } else {
                    section = hwpxFile.sectionXMLFileList().addNew();
                    hwpxFile.contentHPFFile().manifest().addNew()
                            .idAnd("section" + spreadIndex)
                            .hrefAnd("Contents/section" + spreadIndex + ".xml")
                            .mediaType("application/xml");
                    hwpxFile.contentHPFFile().spine().addNew()
                            .idref("section" + spreadIndex);
                }

                // 스프레드 크기로 SecPr 생성 - 모든 floating 객체를 이 단락에 추가
                Para secPrPara = addSectionBreakParaForSpreadWithReturn(section, spread);

                // 프레임을 z-order 순서대로 정렬 (낮은 것부터 → 나중에 추가된 것이 위에 표시됨)
                List<IntermediateFrame> sortedFrames = new ArrayList<>(spread.frames());
                sortedFrames.sort((a, b) -> Integer.compare(a.zOrder(), b.zOrder()));

                // 모든 프레임을 SecPr 단락의 새 Run에 추가 (단일 단락 = 페이지 오버플로우 방지)
                for (IntermediateFrame frame : sortedFrames) {
                    Run frameRun = secPrPara.addNewRun();
                    frameRun.charPrIDRef("0");

                    if ("text".equals(frame.frameType())) {
                        int eqCount = convertTextFrame(frameRun, frame);
                        equationsConverted += eqCount;
                        framesConverted++;
                    } else if ("image".equals(frame.frameType())) {
                        boolean ok = convertImageFrame(frameRun, frame);
                        if (ok) imagesConverted++;
                        framesConverted++;
                    }
                }

                // 빈 section 방지 - SecPr 단락이 이미 있으므로 필요 없음

                pagesConverted++;
                spreadIndex++;
            }
        } else {
            // === 페이지 모드: 각 페이지를 별도 section으로 변환 ===
            int pageIndex = 0;
            for (IntermediatePage page : doc.pages()) {
                SectionXMLFile section;
                if (pageIndex == 0) {
                    section = section0;
                } else {
                    section = hwpxFile.sectionXMLFileList().addNew();
                    hwpxFile.contentHPFFile().manifest().addNew()
                            .idAnd("section" + pageIndex)
                            .hrefAnd("Contents/section" + pageIndex + ".xml")
                            .mediaType("application/xml");
                    hwpxFile.contentHPFFile().spine().addNew()
                            .idref("section" + pageIndex);
                }

                // SecPr 단락 생성 (페이지 속성)
                addSectionBreakPara(section, page, true);

                // 프레임을 z-order 순서대로 정렬 (낮은 것부터 → 나중에 추가된 것이 위에 표시됨)
                List<IntermediateFrame> sortedFrames = new ArrayList<>(page.frames());
                sortedFrames.sort((a, b) -> Integer.compare(a.zOrder(), b.zOrder()));

                // 각 floating 객체를 별도 단락에 배치
                for (IntermediateFrame frame : sortedFrames) {
                    if ("text".equals(frame.frameType())) {
                        Para framePara = createFloatingObjectPara(section);
                        Run frameRun = framePara.runs().iterator().next();
                        int eqCount = convertTextFrame(frameRun, frame);
                        equationsConverted += eqCount;
                        framesConverted++;
                        addMinimalLineSegArray(framePara);  // 최소 높이로 페이지 오버플로우 방지
                    } else if ("image".equals(frame.frameType())) {
                        Para framePara = createFloatingObjectPara(section);
                        Run frameRun = framePara.runs().iterator().next();
                        boolean ok = convertImageFrame(frameRun, frame);
                        if (ok) imagesConverted++;
                        framesConverted++;
                        addMinimalLineSegArray(framePara);  // 최소 높이로 페이지 오버플로우 방지
                    }
                }

                // 빈 section 방지
                if (section.countOfPara() == 0) {
                    addEmptyPara(section);
                }

                pagesConverted++;
                pageIndex++;
            }
        }

        // header.xml의 secCnt를 섹션 개수로 업데이트
        hwpxFile.headerXMLFile().secCnt((short) hwpxFile.sectionXMLFileList().count());

        result.hwpxFile(hwpxFile);
        result.pagesConverted(pagesConverted);
        result.framesConverted(framesConverted);
        result.equationsConverted(equationsConverted);
        result.imagesConverted(imagesConverted);
        result.stylesConverted(paraStyleIdToParaPrId.size() + charStyleIdToCharPrId.size());

        return result;
    }

    // ── 폰트 등록 ──

    private void registerFonts() {
        if (doc.fonts() == null || doc.fonts().isEmpty()) return;

        // 폰트 이름 → ID 매핑 구축
        // BlankFileMaker에서 이미 "함초롬돋움"=0, "함초롬바탕"=1 등록됨
        fontNameToId.put("함초롬돋움", "0");
        fontNameToId.put("함초롬바탕", "1");

        int nextFontId = 2;
        Set<String> registeredFonts = new HashSet<String>();
        registeredFonts.add("함초롬돋움");
        registeredFonts.add("함초롬바탕");

        for (IntermediateFontDef fontDef : doc.fonts()) {
            String hwpxName = fontDef.hwpxMappedName();
            if (hwpxName == null) hwpxName = FontMapper.DEFAULT_HWPX_FONT;

            if (!registeredFonts.contains(hwpxName)) {
                String fontId = String.valueOf(nextFontId);
                // 각 언어 Fontface에 폰트 추가
                addFontToAllLanguages(hwpxName, fontId);
                fontNameToId.put(hwpxName, fontId);
                registeredFonts.add(hwpxName);
                nextFontId++;
            }
        }
    }

    private void addFontToAllLanguages(String fontName, String fontId) {
        for (Fontface face : hwpxFile.headerXMLFile().refList().fontfaces().fontfaces()) {
            Font font = face.addNewFont();
            font.idAnd(fontId)
                    .faceAnd(fontName)
                    .typeAnd(FontType.TTF)
                    .isEmbeddedAnd(false);
            font.createTypeInfo();
            font.typeInfo()
                    .familyTypeAnd(FontFamilyType.FCAT_GOTHIC)
                    .weightAnd(8)
                    .proportionAnd(4)
                    .contrastAnd(0)
                    .strokeVariationAnd(1)
                    .armStyleAnd(true)
                    .letterformAnd(true)
                    .midlineAnd(1)
                    .xHeight(1);
        }
    }

    // ── 스타일 등록 ──

    private void registerStyles() {
        // 단락 스타일
        if (doc.paragraphStyles() != null) {
            for (IntermediateStyleDef styleDef : doc.paragraphStyles()) {
                registerParagraphStyle(styleDef);
            }
        }

        // 문자 스타일
        if (doc.characterStyles() != null) {
            for (IntermediateStyleDef styleDef : doc.characterStyles()) {
                registerCharacterStyle(styleDef);
            }
        }
    }

    private void registerParagraphStyle(IntermediateStyleDef styleDef) {
        // CharPr 생성
        String charPrId = String.valueOf(nextCharPrIndex++);
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();
        buildCharPr(charPr, charPrId, styleDef);

        // ParaPr 생성
        String paraPrId = String.valueOf(nextParaPrIndex++);
        ParaPr paraPr = hwpxFile.headerXMLFile().refList().paraProperties().addNew();
        buildParaPr(paraPr, paraPrId, styleDef);

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

    private void registerCharacterStyle(IntermediateStyleDef styleDef) {
        String charPrId = String.valueOf(nextCharPrIndex++);
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();
        buildCharPr(charPr, charPrId, styleDef);
        charStyleIdToCharPrId.put(styleDef.id(), charPrId);
        charStyleIdToStyleDef.put(styleDef.id(), styleDef);
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
        String fontId = resolveFontId(styleDef.fontFamily());
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

    private void buildParaPr(ParaPr paraPr, String id, IntermediateStyleDef styleDef) {
        paraPr.idAnd(id)
                .tabPrIDRefAnd("0")
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

    private String resolveFontId(String fontFamily) {
        if (fontFamily != null) {
            String hwpxName = FontMapper.mapToHwpxFont(fontFamily);
            String id = fontNameToId.get(hwpxName);
            if (id != null) return id;
        }
        // 기본: 함초롬바탕 (id=1)
        return "1";
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

    // ── 섹션/페이지 분리 ──

    /**
     * SecPr 단락을 생성한다. SecPr + ColPr + PageNum + <hp:t/> + linesegarray.
     * floating 객체는 포함하지 않는다 (별도 단락에 배치).
     */
    private void addSectionBreakPara(SectionXMLFile section, IntermediatePage page, boolean isFirst) {
        Para para = section.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)  // SecPr 자체가 섹션 구분 역할을 한다
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        run.createSecPr();
        SecPr secPr = run.secPr();
        secPr.idAnd("")
                .textDirectionAnd(TextDirection.HORIZONTAL)
                .spaceColumnsAnd(1134)
                .tabStopAnd(8000)
                .tabStopValAnd(4000)
                .tabStopUnitAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit1.HWPUNIT)
                .outlineShapeIDRefAnd("1")
                .memoShapeIDRefAnd("0")
                .textVerticalWidthHeadAnd(false);

        secPr.createGrid();
        secPr.grid().lineGridAnd(0).charGridAnd(0).wonggojiFormat(false);

        secPr.createStartNum();
        secPr.startNum().pageStartsOnAnd(PageStartON.BOTH)
                .pageAnd(0).picAnd(0).tblAnd(0).equation(0);

        secPr.createVisibility();
        secPr.visibility()
                .hideFirstHeaderAnd(false).hideFirstFooterAnd(false)
                .hideFirstMasterPageAnd(false)
                .borderAnd(VisibilityOption.SHOW_ALL).fillAnd(VisibilityOption.SHOW_ALL)
                .hideFirstPageNumAnd(false).hideFirstEmptyLineAnd(false).showLineNumber(false);

        secPr.createLineNumberShape();
        secPr.lineNumberShape()
                .restartTypeAnd(LineNumberRestartType.Unknown)
                .countByAnd(0).distanceAnd(0).startNumber(0);

        // 페이지 크기
        secPr.createPagePr();
        secPr.pagePr()
                .landscapeAnd(PageDirection.WIDELY)
                .widthAnd((int) page.pageWidth())
                .heightAnd((int) page.pageHeight())
                .gutterType(GutterMethod.LEFT_ONLY);

        // 마진
        DocumentLayout layout = doc.layout();
        long marginTop = layout != null ? layout.marginTop() : 5668;
        long marginBottom = layout != null ? layout.marginBottom() : 4252;
        long marginLeft = layout != null ? layout.marginLeft() : 8504;
        long marginRight = layout != null ? layout.marginRight() : 8504;

        secPr.pagePr().createMargin();
        secPr.pagePr().margin()
                .headerAnd(4252).footerAnd(4252).gutterAnd(0)
                .leftAnd((int) marginLeft).rightAnd((int) marginRight)
                .topAnd((int) marginTop).bottom((int) marginBottom);

        secPr.createFootNotePr();
        secPr.footNotePr().createAutoNumFormat();
        secPr.footNotePr().autoNumFormat()
                .typeAnd(NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        secPr.footNotePr().createNoteLine();
        secPr.footNotePr().noteLine().lengthAnd(-1)
                .typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_12).color("#000000");
        secPr.footNotePr().createNoteSpacing();
        secPr.footNotePr().noteSpacing().betweenNotesAnd(283).belowLineAnd(567).aboveLine(850);
        secPr.footNotePr().createNumbering();
        secPr.footNotePr().numbering().typeAnd(FootNoteNumberingType.CONTINUOUS).newNum(1);
        secPr.footNotePr().createPlacement();
        secPr.footNotePr().placement().placeAnd(FootNotePlace.EACH_COLUMN).beneathText(false);

        secPr.createEndNotePr();
        secPr.endNotePr().createAutoNumFormat();
        secPr.endNotePr().autoNumFormat()
                .typeAnd(NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        secPr.endNotePr().createNoteLine();
        secPr.endNotePr().noteLine().lengthAnd(14692344)
                .typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_12).color("#000000");
        secPr.endNotePr().createNoteSpacing();
        secPr.endNotePr().noteSpacing().betweenNotesAnd(0).belowLineAnd(567).aboveLine(850);
        secPr.endNotePr().createNumbering();
        secPr.endNotePr().numbering().typeAnd(EndNoteNumberingType.CONTINUOUS).newNum(1);
        secPr.endNotePr().createPlacement();
        secPr.endNotePr().placement().placeAnd(EndNotePlace.END_OF_DOCUMENT).beneathText(false);

        addPageBorderFills(secPr);

        // ColPr
        Ctrl ctrl = run.addNewCtrl();
        ctrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(1).sameSzAnd(true).sameGap(0);

        // 페이지 번호 (하단 중앙, 아라비아 숫자)
        Ctrl pageNumCtrl = run.addNewCtrl();
        pageNumCtrl.addNewPageNum()
                .posAnd(PageNumPosition.BOTTOM_CENTER)
                .formatTypeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.NumberType1.DIGIT)
                .sideCharAnd("");

        run.addNewT();

        addLineSegArray(para);
    }

    private void addPageBorderFills(SecPr secPr) {
        addPageBorderFill(secPr, ApplyPageType.BOTH);
        addPageBorderFill(secPr, ApplyPageType.EVEN);
        addPageBorderFill(secPr, ApplyPageType.ODD);
    }

    private void addPageBorderFill(SecPr secPr, ApplyPageType type) {
        kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.pageborder.PageBorderFill pbf =
                secPr.addNewPageBorderFill();
        pbf.typeAnd(type)
                .borderFillIDRefAnd("1")
                .textBorderAnd(PageBorderPositionCriterion.PAPER)
                .headerInsideAnd(false).footerInsideAnd(false)
                .fillArea(PageFillArea.PAPER);
        pbf.createOffset();
        pbf.offset().leftAnd(1417L).rightAnd(1417L).topAnd(1417L).bottom(1417L);
    }

    /**
     * 스프레드용 SecPr 단락 생성.
     */
    private void addSectionBreakParaForSpread(SectionXMLFile section, IntermediateSpread spread, boolean isFirst) {
        Para para = section.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        run.createSecPr();
        SecPr secPr = run.secPr();
        secPr.idAnd("")
                .textDirectionAnd(TextDirection.HORIZONTAL)
                .spaceColumnsAnd(1134)
                .tabStopAnd(8000)
                .tabStopValAnd(4000)
                .tabStopUnitAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit1.HWPUNIT)
                .outlineShapeIDRefAnd("1")
                .memoShapeIDRefAnd("0")
                .textVerticalWidthHeadAnd(false);

        secPr.createGrid();
        secPr.grid().lineGridAnd(0).charGridAnd(0).wonggojiFormat(false);

        secPr.createStartNum();
        secPr.startNum().pageStartsOnAnd(PageStartON.BOTH)
                .pageAnd(0).picAnd(0).tblAnd(0).equation(0);

        secPr.createVisibility();
        secPr.visibility()
                .hideFirstHeaderAnd(false).hideFirstFooterAnd(false)
                .hideFirstMasterPageAnd(false)
                .borderAnd(VisibilityOption.SHOW_ALL).fillAnd(VisibilityOption.SHOW_ALL)
                .hideFirstPageNumAnd(false).hideFirstEmptyLineAnd(false).showLineNumber(false);

        secPr.createLineNumberShape();
        secPr.lineNumberShape()
                .restartTypeAnd(LineNumberRestartType.Unknown)
                .countByAnd(0).distanceAnd(0).startNumber(0);

        // 스프레드 크기
        secPr.createPagePr();
        secPr.pagePr()
                .landscapeAnd(PageDirection.WIDELY)
                .widthAnd((int) spread.spreadWidth())
                .heightAnd((int) spread.spreadHeight())
                .gutterType(GutterMethod.LEFT_ONLY);

        // 마진 (스프레드에서는 0 또는 최소값)
        secPr.pagePr().createMargin();
        secPr.pagePr().margin()
                .headerAnd(1417).footerAnd(1417).gutterAnd(0)
                .leftAnd(1417).rightAnd(1417)
                .topAnd(1417).bottom(1417);

        secPr.createFootNotePr();
        secPr.footNotePr().createAutoNumFormat();
        secPr.footNotePr().autoNumFormat()
                .typeAnd(NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        secPr.footNotePr().createNoteLine();
        secPr.footNotePr().noteLine().lengthAnd(-1)
                .typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_12).color("#000000");
        secPr.footNotePr().createNoteSpacing();
        secPr.footNotePr().noteSpacing().betweenNotesAnd(283).belowLineAnd(567).aboveLine(850);
        secPr.footNotePr().createNumbering();
        secPr.footNotePr().numbering().typeAnd(FootNoteNumberingType.CONTINUOUS).newNum(1);
        secPr.footNotePr().createPlacement();
        secPr.footNotePr().placement().placeAnd(FootNotePlace.EACH_COLUMN).beneathText(false);

        secPr.createEndNotePr();
        secPr.endNotePr().createAutoNumFormat();
        secPr.endNotePr().autoNumFormat()
                .typeAnd(NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        secPr.endNotePr().createNoteLine();
        secPr.endNotePr().noteLine().lengthAnd(14692344)
                .typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_12).color("#000000");
        secPr.endNotePr().createNoteSpacing();
        secPr.endNotePr().noteSpacing().betweenNotesAnd(0).belowLineAnd(567).aboveLine(850);
        secPr.endNotePr().createNumbering();
        secPr.endNotePr().numbering().typeAnd(EndNoteNumberingType.CONTINUOUS).newNum(1);
        secPr.endNotePr().createPlacement();
        secPr.endNotePr().placement().placeAnd(EndNotePlace.END_OF_DOCUMENT).beneathText(false);

        addPageBorderFills(secPr);

        // ColPr
        Ctrl ctrl = run.addNewCtrl();
        ctrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(1).sameSzAnd(true).sameGap(0);

        run.addNewT();
        addLineSegArray(para);
    }

    /**
     * 스프레드용 SecPr 단락 생성 (Para 반환).
     * 모든 floating 객체를 이 단락에 추가하여 페이지 오버플로우를 방지한다.
     */
    private Para addSectionBreakParaForSpreadWithReturn(SectionXMLFile section, IntermediateSpread spread) {
        Para para = section.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        run.createSecPr();
        SecPr secPr = run.secPr();
        secPr.idAnd("")
                .textDirectionAnd(TextDirection.HORIZONTAL)
                .spaceColumnsAnd(1134)
                .tabStopAnd(8000)
                .tabStopValAnd(4000)
                .tabStopUnitAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit1.HWPUNIT)
                .outlineShapeIDRefAnd("1")
                .memoShapeIDRefAnd("0")
                .textVerticalWidthHeadAnd(false);

        secPr.createGrid();
        secPr.grid().lineGridAnd(0).charGridAnd(0).wonggojiFormat(false);

        secPr.createStartNum();
        secPr.startNum().pageStartsOnAnd(PageStartON.BOTH)
                .pageAnd(0).picAnd(0).tblAnd(0).equation(0);

        secPr.createVisibility();
        secPr.visibility()
                .hideFirstHeaderAnd(false).hideFirstFooterAnd(false)
                .hideFirstMasterPageAnd(false)
                .borderAnd(VisibilityOption.SHOW_ALL).fillAnd(VisibilityOption.SHOW_ALL)
                .hideFirstPageNumAnd(false).hideFirstEmptyLineAnd(false).showLineNumber(false);

        secPr.createLineNumberShape();
        secPr.lineNumberShape()
                .restartTypeAnd(LineNumberRestartType.Unknown)
                .countByAnd(0).distanceAnd(0).startNumber(0);

        // 스프레드 크기
        secPr.createPagePr();
        secPr.pagePr()
                .landscapeAnd(PageDirection.WIDELY)
                .widthAnd((int) spread.spreadWidth())
                .heightAnd((int) spread.spreadHeight())
                .gutterType(GutterMethod.LEFT_ONLY);

        // 마진 (스프레드에서는 0 또는 최소값)
        secPr.pagePr().createMargin();
        secPr.pagePr().margin()
                .headerAnd(1417).footerAnd(1417).gutterAnd(0)
                .leftAnd(1417).rightAnd(1417)
                .topAnd(1417).bottom(1417);

        secPr.createFootNotePr();
        secPr.footNotePr().createAutoNumFormat();
        secPr.footNotePr().autoNumFormat()
                .typeAnd(NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        secPr.footNotePr().createNoteLine();
        secPr.footNotePr().noteLine().lengthAnd(-1)
                .typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_12).color("#000000");
        secPr.footNotePr().createNoteSpacing();
        secPr.footNotePr().noteSpacing().betweenNotesAnd(283).belowLineAnd(567).aboveLine(850);
        secPr.footNotePr().createNumbering();
        secPr.footNotePr().numbering().typeAnd(FootNoteNumberingType.CONTINUOUS).newNum(1);
        secPr.footNotePr().createPlacement();
        secPr.footNotePr().placement().placeAnd(FootNotePlace.EACH_COLUMN).beneathText(false);

        secPr.createEndNotePr();
        secPr.endNotePr().createAutoNumFormat();
        secPr.endNotePr().autoNumFormat()
                .typeAnd(NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        secPr.endNotePr().createNoteLine();
        secPr.endNotePr().noteLine().lengthAnd(14692344)
                .typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_12).color("#000000");
        secPr.endNotePr().createNoteSpacing();
        secPr.endNotePr().noteSpacing().betweenNotesAnd(0).belowLineAnd(567).aboveLine(850);
        secPr.endNotePr().createNumbering();
        secPr.endNotePr().numbering().typeAnd(EndNoteNumberingType.CONTINUOUS).newNum(1);
        secPr.endNotePr().createPlacement();
        secPr.endNotePr().placement().placeAnd(EndNotePlace.END_OF_DOCUMENT).beneathText(false);

        addPageBorderFills(secPr);

        // ColPr
        Ctrl ctrl = run.addNewCtrl();
        ctrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(1).sameSzAnd(true).sameGap(0);

        run.addNewT();
        addLineSegArray(para);

        return para;
    }

    /**
     * 빈 단락 추가 (section이 빈 경우 방지).
     */
    private void addEmptyPara(SectionXMLFile section) {
        Para emptyPara = section.addNewPara();
        emptyPara.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run emptyRun = emptyPara.addNewRun();
        emptyRun.charPrIDRef("0");
        emptyRun.addNewT();
    }

    /**
     * floating 객체 하나를 담을 단락을 생성한다.
     * pageBreak=0, Run 하나 포함.
     */
    private Para createFloatingObjectPara(SectionXMLFile section) {
        Para para = section.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        return para;
    }

    /**
     * 단락에 linesegarray를 추가한다.
     * 참조 파일 패턴: textpos=0, vertpos=0, vertsize=1000, textheight=1000,
     * baseline=850, spacing=600, horzpos=0, horzsize=42520, flags=393216
     */
    private void addLineSegArray(Para para) {
        para.createLineSegArray();
        LineSeg lineSeg = para.lineSegArray().addNew();
        lineSeg.textposAnd(0).vertposAnd(0).vertsizeAnd(1000)
                .textheightAnd(1000).baselineAnd(850).spacingAnd(600)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
    }

    /**
     * Floating 객체 단락에 최소 높이 linesegarray를 추가한다.
     * floating 객체는 절대 위치로 배치되므로 단락 자체는 높이가 거의 없어야 한다.
     * 그렇지 않으면 많은 floating 객체가 페이지 오버플로우를 일으킨다.
     */
    private void addMinimalLineSegArray(Para para) {
        para.createLineSegArray();
        LineSeg lineSeg = para.lineSegArray().addNew();
        // vertsize와 textheight를 최소값(1)으로 설정하여 단락 높이를 거의 0으로 만듦
        lineSeg.textposAnd(0).vertposAnd(0).vertsizeAnd(1)
                .textheightAnd(1).baselineAnd(0).spacingAnd(0)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
    }

    // ── 텍스트 프레임 변환 (Rectangle + DrawText) ──

    private int convertTextFrame(Run anchorRun, IntermediateFrame frame) {
        int equationCount = 0;
        if (frame.paragraphs() == null) return 0;

        // PAPER 기준 좌표: 음수 좌표는 bleed 영역(페이지 밖)을 의미하며 유효함
        long x = frame.x();
        long y = frame.y();
        long w = frame.width();
        long h = frame.height();

        Rectangle rect = anchorRun.addNewRectangle();

        // ShapeObject 기본 속성
        rect.idAnd(nextShapeId())
                .zOrderAnd(frame.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(nextShapeId());

        rect.createOffset();
        rect.offset().set(0L, 0L);

        rect.createOrgSz();
        rect.orgSz().set(w, h);

        rect.createCurSz();
        rect.curSz().set(w, h);

        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);

        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);

        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — 용지(PAPER) 기준 절대 좌표
        rect.createPos();
        rect.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(y)
                .horzOffset(x);

        // OutMargin
        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // Rectangle 고유 속성 — 4 코너, 직각
        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        // LineShape (파란색 얇은 실선, 0.1mm ≈ 28 hwpunit)
        rect.createLineShape();
        rect.lineShape().colorAnd("#0000FF").widthAnd(28)
                .styleAnd(LineType2.SOLID)
                .endCapAnd(LineCap.FLAT)
                .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                .headfillAnd(true).tailfillAnd(true)
                .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                .outlineStyleAnd(OutlineStyle.NORMAL).alpha(0f);

        // DrawText — 내부 텍스트
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);
        dt.createTextMargin();
        dt.textMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        dt.createSubList();
        SubList subList = dt.subList();
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP);

        // SubList에 단락/런 추가
        equationCount = addParagraphsToParaList(subList, frame);

        return equationCount;
    }

    /**
     * IntermediateFrame의 단락들을 ParaListCore(SubList 또는 SectionXMLFile)에 추가한다.
     */
    private int addParagraphsToParaList(ParaListCore paraList, IntermediateFrame frame) {
        int equationCount = 0;

        for (IntermediateParagraph iPara : frame.paragraphs()) {
            String paraPrId = "3";
            String styleId = "0";
            String paraCharPrId = "0";  // 단락 스타일의 CharPr ID (런 기본값)
            IntermediateStyleDef paraStyleDef = null;
            if (iPara.paragraphStyleRef() != null) {
                String mapped = paraStyleIdToParaPrId.get(iPara.paragraphStyleRef());
                if (mapped != null) paraPrId = mapped;
                String mappedStyle = paraStyleIdToStyleId.get(iPara.paragraphStyleRef());
                if (mappedStyle != null) styleId = mappedStyle;
                String mappedCharPr = charStyleIdToCharPrId.get(iPara.paragraphStyleRef());
                if (mappedCharPr != null) paraCharPrId = mappedCharPr;
                paraStyleDef = paraStyleIdToStyleDef.get(iPara.paragraphStyleRef());
            }

            if (iPara.contentItems().isEmpty()) {
                // 빈 단락
                Para para = createTextPara(paraList, paraPrId, styleId);
                Run run = para.addNewRun();
                run.charPrIDRef(paraCharPrId);
                run.addNewT();
                continue;
            }

            Para currentPara = createTextPara(paraList, paraPrId, styleId);

            for (IntermediateParagraph.ContentItem item : iPara.contentItems()) {
                if (item.isEquation()) {
                    // 인라인 수식: 같은 Para 안에 수식 Run 추가
                    addInlineEquationRun(currentPara, item.equation());
                    equationCount++;
                } else if (item.isTextRun()) {
                    // 텍스트 런
                    String charPrId = resolveCharPrId(item.textRun(), paraCharPrId, paraStyleDef);
                    String text = item.textRun().text() != null ? item.textRun().text() : "";
                    text = sanitizeText(text);

                    if (!text.contains("\n")) {
                        Run run = currentPara.addNewRun();
                        run.charPrIDRef(charPrId);
                        run.addNewT().addText(text);
                    } else {
                        String[] parts = text.split("\n", -1);
                        for (int pi = 0; pi < parts.length; pi++) {
                            if (pi > 0) {
                                ensureParaHasRun(currentPara);
                                currentPara = createTextPara(paraList, paraPrId, styleId);
                            }
                            String part = parts[pi];
                            if (!part.isEmpty()) {
                                Run run = currentPara.addNewRun();
                                run.charPrIDRef(charPrId);
                                run.addNewT().addText(part);
                            }
                        }
                    }
                }
            }

            ensureParaHasRun(currentPara);
        }

        // ParaList가 비어있으면 빈 단락 추가
        if (paraList.countOfPara() == 0) {
            Para para = createTextPara(paraList, "3", "0");
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT();
        }

        return equationCount;
    }

    /**
     * 텍스트 정제: HWPX에서 허용되지 않는 특수 문자를 변환하고,
     * 한글 자모 분리 문제를 해결하기 위해 Unicode NFC 정규화를 적용한다.
     *
     * IDML에서 한글이 NFD(Decomposed Form)로 저장될 수 있다.
     * 예: "마음" → "ㅁㅏ음" (자모 분리)
     * NFC 정규화로 완성형 한글로 변환한다.
     */
    private String sanitizeText(String text) {
        // Unicode NFC 정규화: NFD → NFC (한글 자모 분리 해결)
        text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC);
        text = text.replace('\t', ' ');
        return text;
    }

    /**
     * 새 텍스트 단락을 ParaListCore에 생성한다.
     */
    private Para createTextPara(ParaListCore paraList, String paraPrId, String styleId) {
        Para para = paraList.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd(styleId)
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        return para;
    }

    /**
     * 단락에 런이 하나도 없으면 빈 런을 추가한다.
     */
    private void ensureParaHasRun(Para para) {
        if (para.countOfRun() == 0) {
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT();
        }
    }

    private String resolveCharPrId(IntermediateTextRun iRun, String paraCharPrId,
                                    IntermediateStyleDef paraStyleDef) {
        // 런 레벨 오버라이드가 있으면 새 CharPr 생성 (단락 스타일 속성을 기본값으로 사용)
        if (hasRunOverrides(iRun)) {
            return createRunCharPr(iRun, paraStyleDef);
        }

        // 문자 스타일 참조로 찾기
        if (iRun.characterStyleRef() != null) {
            IntermediateStyleDef charStyleDef = charStyleIdToStyleDef.get(iRun.characterStyleRef());
            if (charStyleDef != null && paraStyleDef != null && hasCharStyleOverrides(charStyleDef)) {
                // 문자 스타일이 단락 스타일과 다른 속성을 가지면 병합된 CharPr 생성
                return createMergedCharPr(paraStyleDef, charStyleDef);
            }
            // 문자 스타일에 오버라이드가 없으면 단락 스타일 CharPr 사용
            if (charStyleDef != null && paraStyleDef != null) {
                return paraCharPrId;
            }
            // paraStyleDef가 없으면 문자 스타일 CharPr 직접 사용
            String id = charStyleIdToCharPrId.get(iRun.characterStyleRef());
            if (id != null) return id;
        }

        // 단락 스타일의 CharPr을 기본값으로 사용
        return paraCharPrId;
    }

    /**
     * 문자 스타일이 실질적인 오버라이드를 가지고 있는지 확인한다.
     */
    private boolean hasCharStyleOverrides(IntermediateStyleDef charStyleDef) {
        return charStyleDef.fontSizeHwpunits() != null
                || charStyleDef.textColor() != null
                || charStyleDef.fontFamily() != null
                || charStyleDef.bold() != null
                || charStyleDef.italic() != null;
    }

    /**
     * 단락 스타일과 문자 스타일을 병합한 CharPr을 생성한다.
     * 문자 스타일의 속성이 있으면 그것을 사용하고, 없으면 단락 스타일에서 상속한다.
     */
    private String createMergedCharPr(IntermediateStyleDef paraStyle, IntermediateStyleDef charStyle) {
        String charPrId = String.valueOf(nextCharPrIndex++);
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();

        // 문자 스타일 우선, 없으면 단락 스타일에서 상속
        int height = charStyle.fontSizeHwpunits() != null ? charStyle.fontSizeHwpunits()
                : (paraStyle.fontSizeHwpunits() != null ? paraStyle.fontSizeHwpunits() : 1000);
        String textColor = charStyle.textColor() != null ? charStyle.textColor()
                : (paraStyle.textColor() != null ? paraStyle.textColor() : "#000000");

        charPr.idAnd(charPrId)
                .heightAnd(height)
                .textColorAnd(textColor)
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");

        boolean bold = charStyle.bold() != null ? Boolean.TRUE.equals(charStyle.bold())
                : Boolean.TRUE.equals(paraStyle.bold());
        boolean italic = charStyle.italic() != null ? Boolean.TRUE.equals(charStyle.italic())
                : Boolean.TRUE.equals(paraStyle.italic());
        if (bold) charPr.createBold();
        if (italic) charPr.createItalic();

        String fontFamily = charStyle.fontFamily() != null ? charStyle.fontFamily() : paraStyle.fontFamily();
        String fontId = resolveFontId(fontFamily);
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        charPr.createRatio();
        charPr.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);
        charPr.createSpacing();
        charPr.spacing().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);
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

        return charPrId;
    }

    private boolean hasRunOverrides(IntermediateTextRun iRun) {
        return iRun.bold() != null || iRun.italic() != null
                || iRun.fontSizeHwpunits() != null
                || iRun.textColor() != null
                || iRun.fontFamily() != null;
    }

    private String createRunCharPr(IntermediateTextRun iRun, IntermediateStyleDef paraStyleDef) {
        String charPrId = String.valueOf(nextCharPrIndex++);
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();

        // 단락 스타일 속성을 기본값으로 사용하고, 런 레벨 오버라이드로 덮어쓴다
        int baseHeight = 1000;
        String baseTextColor = "#000000";
        String baseFontFamily = null;
        Boolean baseBold = null;
        Boolean baseItalic = null;
        if (paraStyleDef != null) {
            if (paraStyleDef.fontSizeHwpunits() != null) baseHeight = paraStyleDef.fontSizeHwpunits();
            if (paraStyleDef.textColor() != null) baseTextColor = paraStyleDef.textColor();
            baseFontFamily = paraStyleDef.fontFamily();
            baseBold = paraStyleDef.bold();
            baseItalic = paraStyleDef.italic();
        }

        int height = iRun.fontSizeHwpunits() != null ? iRun.fontSizeHwpunits() : baseHeight;
        String textColor = iRun.textColor() != null ? iRun.textColor() : baseTextColor;

        charPr.idAnd(charPrId)
                .heightAnd(height)
                .textColorAnd(textColor)
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");

        boolean bold = Boolean.TRUE.equals(iRun.bold())
                || (iRun.bold() == null && Boolean.TRUE.equals(baseBold));
        boolean italic = Boolean.TRUE.equals(iRun.italic())
                || (iRun.italic() == null && Boolean.TRUE.equals(baseItalic));
        if (bold) {
            charPr.createBold();
        }
        if (italic) {
            charPr.createItalic();
        }

        String fontFamily = iRun.fontFamily() != null ? iRun.fontFamily() : baseFontFamily;
        String fontId = resolveFontId(fontFamily);
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        charPr.createRatio();
        charPr.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);

        charPr.createSpacing();
        charPr.spacing().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);

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

        return charPrId;
    }

    // ── 인라인 수식 ──

    /**
     * 인라인 수식을 같은 Para 안에 Run으로 추가한다.
     */
    private void addInlineEquationRun(Para para, IntermediateEquation iEq) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");

        try {
            Equation equation = EquationBuilder.fromHwpScript(iEq.hwpScript());
            Equation eq = run.addNewEquation();
            eq.versionAnd(equation.version())
                    .textColorAnd(equation.textColor())
                    .baseUnitAnd(equation.baseUnit())
                    .lineModeAnd(equation.lineMode())
                    .fontAnd(equation.font());
            eq.createScript();
            eq.script().addText(iEq.hwpScript());
        } catch (Exception e) {
            result.addWarning("Equation conversion failed: " + iEq.hwpScript()
                    + " - " + e.getMessage());
            run.addNewT().addText("[수식: " + iEq.hwpScript() + "]");
        }
    }

    // ── 이미지 프레임 변환 (floating Picture) ──

    private boolean convertImageFrame(Run anchorRun, IntermediateFrame frame) {
        IntermediateImage image = frame.image();
        if (image == null) {
            return false;
        }

        if (image.base64Data() == null) {
            return false;
        }

        try {
            byte[] imageData = Base64.getDecoder().decode(image.base64Data());
            String format = image.format() != null ? image.format() : "png";

            String itemId = ImageInserter.registerImage(hwpxFile, imageData, format);

            int pixelW = image.pixelWidth() > 0 ? image.pixelWidth() : 100;
            int pixelH = image.pixelHeight() > 0 ? image.pixelHeight() : 100;
            long displayW = frame.width();
            long displayH = frame.height();

            // PAPER 기준 좌표: 음수 좌표는 bleed 영역(용지 바깥)을 의미하며 유효함
            long posX = frame.x();
            long posY = frame.y();

            Picture pic = anchorRun.addNewPicture();
            String picId = nextShapeId();

            // ShapeObject (이미지는 텍스트 뒤에 배치)
            pic.idAnd(picId).zOrderAnd(frame.zOrder())
                    .numberingTypeAnd(NumberingType.PICTURE)
                    .textWrapAnd(TextWrapMethod.BEHIND_TEXT)
                    .textFlowAnd(TextFlowSide.BOTH_SIDES)
                    .lockAnd(false).dropcapstyleAnd(DropCapStyle.None);

            // ShapeComponent
            pic.hrefAnd("");
            pic.groupLevelAnd((short) 0);
            pic.instidAnd(nextShapeId());
            pic.reverseAnd(false);

            pic.createOffset();
            pic.offset().set(0L, 0L);

            pic.createOrgSz();
            pic.orgSz().set(displayW, displayH);

            pic.createCurSz();
            pic.curSz().set(displayW, displayH);

            pic.createFlip();
            pic.flip().horizontalAnd(false).verticalAnd(false);

            pic.createRotationInfo();
            pic.rotationInfo().angleAnd((short) 0)
                    .centerXAnd(displayW / 2).centerYAnd(displayH / 2)
                    .rotateimageAnd(true);

            pic.createRenderingInfo();
            pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

            // ShapeSize
            pic.createSZ();
            pic.sz().widthAnd(displayW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                    .heightAnd(displayH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                    .protectAnd(false);

            // ShapePosition — 용지(PAPER) 기준 절대 좌표
            pic.createPos();
            pic.pos().treatAsCharAnd(false)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(false)
                    .allowOverlapAnd(true)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PAPER)
                    .horzRelToAnd(HorzRelTo.PAPER)
                    .vertAlignAnd(VertAlign.TOP)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(posY)
                    .horzOffset(posX);

            // OutMargin
            pic.createOutMargin();
            pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

            // ImageRect
            pic.createImgRect();
            pic.imgRect().createPt0();
            pic.imgRect().pt0().set(0L, 0L);
            pic.imgRect().createPt1();
            pic.imgRect().pt1().set(displayW, 0L);
            pic.imgRect().createPt2();
            pic.imgRect().pt2().set(displayW, displayH);
            pic.imgRect().createPt3();
            pic.imgRect().pt3().set(0L, displayH);

            // ImageClip (pixel * 75)
            long clipW = (long) pixelW * 75;
            long clipH = (long) pixelH * 75;
            pic.createImgClip();
            pic.imgClip().leftAnd(0L).rightAnd(clipW).topAnd(0L).bottomAnd(clipH);

            // InMargin
            pic.createInMargin();
            pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

            // ImageDim (pixel * 75)
            pic.createImgDim();
            pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

            // Image reference
            pic.createImg();
            pic.img().binaryItemIDRefAnd(itemId)
                    .brightAnd(0).contrastAnd(0)
                    .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

            return true;
        } catch (Exception e) {
            result.addWarning("Image insertion failed for " + frame.frameId() + ": " + e.getMessage());
            return false;
        }
    }

    // ── 유틸리티 ──

    private static String nextParaId() {
        return String.valueOf(PARA_ID_COUNTER.incrementAndGet());
    }

    private static String nextShapeId() {
        return String.valueOf(SHAPE_ID_COUNTER.incrementAndGet());
    }
}
