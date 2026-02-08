package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.Border;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.ParaListCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Ellipse;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Polygon;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawingObject;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapecomponent.ShapeComponent;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapeobject.ShapeObject;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertResult;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.hwpx.HwpxImageWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.hwpx.HwpxShapeWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.hwpx.HwpxTextFrameWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.*;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
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

    // 폰트 및 스타일 레지스트리
    private FontRegistry fontRegistry;
    private StyleRegistry styleRegistry;

    // BorderFill ID 카운터 (1, 2는 BlankFileMaker에서 사용)
    private final AtomicInteger borderFillIdCounter = new AtomicInteger(3);

    // 도형 작성기 (shape frame → HWPX Rectangle/Ellipse/Polygon)
    private final HwpxShapeWriter shapeWriter = new HwpxShapeWriter(SHAPE_ID_COUNTER);

    // 이미지 작성기 (image frame → HWPX Picture)
    private HwpxImageWriter imageWriter;

    // 텍스트 프레임 작성기 (text frame → HWPX Rectangle+DrawText)
    private HwpxTextFrameWriter textFrameWriter;

    private IntermediateToHwpxConverter(IntermediateDocument doc) {
        this.doc = doc;
        this.result = new ConvertResult();
    }

    private ConvertResult doConvert() throws ConvertException {
        // 1. BlankFileMaker로 기본 구조 생성
        hwpxFile = BlankFileMaker.make();

        // 2. 이미지 작성기 초기화 (hwpxFile 필요)
        imageWriter = new HwpxImageWriter(SHAPE_ID_COUNTER, hwpxFile, result::addWarning);

        // 3. 레지스트리 초기화
        fontRegistry = new FontRegistry(hwpxFile);
        styleRegistry = new StyleRegistry(hwpxFile, fontRegistry);

        // 3. 폰트 등록
        fontRegistry.registerFonts(doc);

        // 4. 스타일 등록
        registerStyles();

        // 5. 텍스트 프레임 작성기 초기화
        textFrameWriter = new HwpxTextFrameWriter(SHAPE_ID_COUNTER, PARA_ID_COUNTER,
                hwpxFile, fontRegistry, styleRegistry, result::addWarning, borderFillIdCounter);

        // 6. 스프레드/페이지별로 별도 section 파일 생성
        int pagesConverted = 0;
        int framesConverted = 0;
        int equationsConverted = 0;
        int imagesConverted = 0;

        // 기존 section0 초기화 (첫 페이지용)
        SectionXMLFile section0 = hwpxFile.sectionXMLFileList().get(0);
        section0.removeAllParas();

        // 디버그: 스프레드 모드 상태 출력
        System.err.println("[DEBUG] useSpreadMode: " + doc.useSpreadMode());
        System.err.println("[DEBUG] spreads count: " + (doc.spreads() != null ? doc.spreads().size() : "null"));
        System.err.println("[DEBUG] pages count: " + (doc.pages() != null ? doc.pages().size() : "null"));

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

                // 프레임을 콘텐츠 카테고리 순서대로 정렬:
                // 배경 이미지 → 디자인 포맷 이미지 → 벡터 그래픽 → 일반 이미지 → 텍스트
                // 같은 카테고리 내에서는 z-order 순서로 정렬
                List<IntermediateFrame> sortedFrames = new ArrayList<>(spread.frames());
                sortedFrames.sort((a, b) -> Integer.compare(a.exportOrder(), b.exportOrder()));

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
                    } else if ("rectangle".equals(frame.frameType())) {
                        convertRectangleFrame(frameRun, frame);
                        framesConverted++;
                    } else if ("table".equals(frame.frameType())) {
                        convertTableFrame(frameRun, frame);
                        framesConverted++;
                    } else if ("shape".equals(frame.frameType())) {
                        convertShapeFrame(frameRun, frame);
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

                // 프레임을 콘텐츠 카테고리 순서대로 정렬:
                // 배경 이미지 → 디자인 포맷 이미지 → 벡터 그래픽 → 일반 이미지 → 텍스트
                List<IntermediateFrame> sortedFrames = new ArrayList<>(page.frames());
                sortedFrames.sort((a, b) -> Integer.compare(a.exportOrder(), b.exportOrder()));

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
                    } else if ("rectangle".equals(frame.frameType())) {
                        Para framePara = createFloatingObjectPara(section);
                        Run frameRun = framePara.runs().iterator().next();
                        convertRectangleFrame(frameRun, frame);
                        framesConverted++;
                        addMinimalLineSegArray(framePara);
                    } else if ("table".equals(frame.frameType())) {
                        Para framePara = createFloatingObjectPara(section);
                        Run frameRun = framePara.runs().iterator().next();
                        convertTableFrame(frameRun, frame);
                        framesConverted++;
                        addMinimalLineSegArray(framePara);
                    } else if ("shape".equals(frame.frameType())) {
                        Para framePara = createFloatingObjectPara(section);
                        Run frameRun = framePara.runs().iterator().next();
                        convertShapeFrame(frameRun, frame);
                        framesConverted++;
                        addMinimalLineSegArray(framePara);
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
        result.stylesConverted(styleRegistry.totalStyleCount());

        return result;
    }

    // ── 스타일 등록 ──

    private void registerStyles() {
        // 단락 스타일
        if (doc.paragraphStyles() != null) {
            for (IntermediateStyleDef styleDef : doc.paragraphStyles()) {
                styleRegistry.registerParagraphStyle(styleDef);
            }
        }

        // 문자 스타일
        if (doc.characterStyles() != null) {
            for (IntermediateStyleDef styleDef : doc.characterStyles()) {
                styleRegistry.registerCharacterStyle(styleDef);
            }
        }
    }

    // ── 섹션/페이지 분리 ──

    /**
     * SecPr 단락을 생성한다. SecPr + ColPr + PageNum + <hp:t/> + linesegarray.
     * floating 객체는 포함하지 않는다 (별도 단락에 배치).
     */
    /**
     * SecPr 단락을 생성한다.
     * 페이지 모드와 스프레드 모드 모두에서 사용 가능한 통합 메서드.
     *
     * @param section       섹션 파일
     * @param pageWidth     페이지/스프레드 너비 (HWPUNIT)
     * @param pageHeight    페이지/스프레드 높이 (HWPUNIT)
     * @param marginTop     상단 마진 (HWPUNIT), 0이면 기본값 사용
     * @param marginBottom  하단 마진 (HWPUNIT), 0이면 기본값 사용
     * @param marginLeft    좌측 마진 (HWPUNIT), 0이면 기본값 사용
     * @param marginRight   우측 마진 (HWPUNIT), 0이면 기본값 사용
     * @param addPageNum    true: 페이지 번호 컨트롤 추가
     * @return 생성된 Para 객체
     */
    private Para createSectionBreakParaWithMargins(SectionXMLFile section, long pageWidth, long pageHeight,
                                         long marginTop, long marginBottom, long marginLeft, long marginRight,
                                         boolean addPageNum) {
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

        // 페이지/스프레드 크기
        secPr.createPagePr();
        secPr.pagePr()
                .landscapeAnd(PageDirection.WIDELY)
                .widthAnd((int) pageWidth)
                .heightAnd((int) pageHeight)
                .gutterType(GutterMethod.LEFT_ONLY);

        // 마진 - 명시적으로 전달된 값 사용 (0이면 최소값 적용)
        int mTop = marginTop > 0 ? (int) marginTop : 1417;
        int mBottom = marginBottom > 0 ? (int) marginBottom : 1417;
        int mLeft = marginLeft > 0 ? (int) marginLeft : 1417;
        int mRight = marginRight > 0 ? (int) marginRight : 1417;
        int headerFooter = 1417;

        secPr.pagePr().createMargin();
        secPr.pagePr().margin()
                .headerAnd(headerFooter).footerAnd(headerFooter).gutterAnd(0)
                .leftAnd(mLeft).rightAnd(mRight)
                .topAnd(mTop).bottom(mBottom);

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

        // 페이지 번호 (페이지 모드에서만)
        if (addPageNum) {
            Ctrl pageNumCtrl = run.addNewCtrl();
            pageNumCtrl.addNewPageNum()
                    .posAnd(PageNumPosition.BOTTOM_CENTER)
                    .formatTypeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.NumberType1.DIGIT)
                    .sideCharAnd("");
        }

        run.addNewT();
        addLineSegArray(para);

        return para;
    }

    // 페이지 모드용 래퍼 - 페이지별 마진 사용
    private void addSectionBreakPara(SectionXMLFile section, IntermediatePage page, boolean isFirst) {
        createSectionBreakParaWithMargins(section, page.pageWidth(), page.pageHeight(),
                page.marginTop(), page.marginBottom(), page.marginLeft(), page.marginRight(), true);
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

    // 스프레드 모드용 래퍼 (Para 반환) - 스프레드는 마진 0 사용 (최소값 적용됨)
    private Para addSectionBreakParaForSpreadWithReturn(SectionXMLFile section, IntermediateSpread spread) {
        return createSectionBreakParaWithMargins(section, spread.spreadWidth(), spread.spreadHeight(),
                0, 0, 0, 0, false);
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

    /**
     * 텍스트 프레임을 HWPX Rectangle+DrawText로 변환한다.
     * HwpxTextFrameWriter에 위임한다.
     */
    private int convertTextFrame(Run anchorRun, IntermediateFrame frame) {
        return textFrameWriter.write(anchorRun, frame);
    }

    /**
     * IntermediateTable 프레임을 HWPX Table로 변환한다.
     */
    private void convertTableFrame(Run anchorRun, IntermediateFrame frame) {
        IntermediateTable iTable = frame.table();
        if (iTable == null) return;

        // PAPER 기준 좌표
        long x = Math.max(0, frame.x());
        long y = Math.max(0, frame.y());
        long totalWidth = frame.width();
        long totalHeight = frame.height();

        // 테이블 생성
        Table table = anchorRun.addNewTable();

        // ShapeObject 기본 속성
        String tableId = nextShapeId();
        table.idAnd(tableId)
                .zOrderAnd(frame.zOrder())
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // 테이블 속성
        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) iTable.rowCount())
                .colCntAnd((short) iTable.columnCount())
                .cellSpacingAnd(iTable.cellSpacing())
                .borderFillIDRefAnd("1")  // 기본 테두리 (TODO: 커스텀 테두리 지원)
                .noAdjustAnd(false);

        // ShapeSize
        table.createSZ();
        table.sz().widthAnd(totalWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(totalHeight).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(true);  // 크기 고정

        // ShapePosition — 용지(PAPER) 기준 절대 좌표
        table.createPos();
        table.pos().treatAsCharAnd(false)
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
        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // InMargin (테이블 내부 여백)
        table.createInMargin();
        table.inMargin().leftAnd(iTable.insetLeft())
                .rightAnd(iTable.insetRight())
                .topAnd(iTable.insetTop())
                .bottomAnd(iTable.insetBottom());

        // 행(Tr) 생성
        for (IntermediateTableRow iRow : iTable.rows()) {
            Tr tr = table.addNewTr();

            // 각 셀(Tc) 생성
            for (IntermediateTableCell iCell : iRow.cells()) {
                Tc tc = tr.addNewTc();

                // 셀별 테두리/채우기 BorderFill 생성
                String cellBorderFillId = createCellBorderFill(iCell);

                tc.nameAnd("")
                        .headerAnd(false)
                        .hasMarginAnd(true)
                        .protectAnd(false)
                        .editableAnd(false)
                        .dirtyAnd(false)
                        .borderFillIDRefAnd(cellBorderFillId);

                // 셀 주소
                tc.createCellAddr();
                tc.cellAddr().colAddrAnd((short) iCell.columnIndex())
                        .rowAddrAnd((short) iCell.rowIndex());

                // 셀 병합
                tc.createCellSpan();
                tc.cellSpan().colSpanAnd((short) iCell.columnSpan())
                        .rowSpanAnd((short) iCell.rowSpan());

                // 셀 크기
                tc.createCellSz();
                tc.cellSz().widthAnd(iCell.width()).heightAnd(iCell.height());

                // 셀 여백
                tc.createCellMargin();
                tc.cellMargin().leftAnd(iCell.marginLeft())
                        .rightAnd(iCell.marginRight())
                        .topAnd(iCell.marginTop())
                        .bottomAnd(iCell.marginBottom());

                // 셀 내부 SubList
                tc.createSubList();
                SubList subList = tc.subList();
                subList.idAnd("")
                        .textDirectionAnd(TextDirection.HORIZONTAL)
                        .lineWrapAnd(LineWrapMethod.BREAK);

                // 수직 정렬
                String vAlign = iCell.verticalAlign();
                if ("center".equals(vAlign)) {
                    subList.vertAlignAnd(VerticalAlign2.CENTER);
                } else if ("bottom".equals(vAlign)) {
                    subList.vertAlignAnd(VerticalAlign2.BOTTOM);
                } else {
                    subList.vertAlignAnd(VerticalAlign2.TOP);
                }

                // 셀 내용 (단락) 추가
                for (IntermediateParagraph iPara : iCell.paragraphs()) {
                    textFrameWriter.addParagraphToSubList(subList, iPara);
                }

                // 빈 셀 방지
                if (subList.countOfPara() == 0) {
                    Para emptyPara = subList.addNewPara();
                    emptyPara.idAnd(nextParaId())
                            .paraPrIDRefAnd("3")
                            .styleIDRefAnd("0")
                            .pageBreakAnd(false)
                            .columnBreakAnd(false)
                            .merged(false);
                    Run run = emptyPara.addNewRun();
                    run.charPrIDRef("0");
                    run.addNewT();
                }
            }
        }
    }

    /**
     * 테이블 셀용 BorderFill을 생성한다.
     */
    private String createCellBorderFill(IntermediateTableCell cell) {
        String bfId = String.valueOf(borderFillIdCounter.getAndIncrement());
        BorderFill bf = hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        // 대각선 설정 (slash = 좌상→우하 ↘, backSlash = 우상→좌하 ↙)
        bf.createSlash();
        if (cell.topLeftDiagonalLine()) {
            bf.slash().typeAnd(SlashType.CENTER).CrookedAnd(false).isCounter(false);
        } else {
            bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        }

        bf.createBackSlash();
        if (cell.topRightDiagonalLine()) {
            bf.backSlash().typeAnd(SlashType.CENTER).CrookedAnd(false).isCounter(false);
        } else {
            bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        }

        // 테두리 설정
        bf.createLeftBorder();
        applyCellBorder(bf.leftBorder(), cell.leftBorder());

        bf.createRightBorder();
        applyCellBorder(bf.rightBorder(), cell.rightBorder());

        bf.createTopBorder();
        applyCellBorder(bf.topBorder(), cell.topBorder());

        bf.createBottomBorder();
        applyCellBorder(bf.bottomBorder(), cell.bottomBorder());

        // 대각선 스타일 설정
        bf.createDiagonal();
        if ((cell.topLeftDiagonalLine() || cell.topRightDiagonalLine()) && cell.diagonalBorder() != null) {
            IntermediateTableCell.CellBorder diag = cell.diagonalBorder();
            LineType2 lineType = strokeTypeToLineType(diag.strokeType);
            LineWidth lineWidth = hwpunitToLineWidth(diag.strokeWeight);
            String color = diag.strokeColor != null ? diag.strokeColor : "#000000";
            bf.diagonal().typeAnd(lineType).widthAnd(lineWidth).color(color);
        } else {
            bf.diagonal().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        }

        // 배경 채우기 설정
        if (cell.fillColor() != null && !cell.fillColor().isEmpty()) {
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush()
                    .faceColorAnd(cell.fillColor())
                    .hatchColorAnd("#FF000000")
                    .alpha(0f);
        }

        return bfId;
    }

    /**
     * IntermediateTableCell.CellBorder를 HWPX Border에 적용한다.
     */
    private void applyCellBorder(Border hwpxBorder, IntermediateTableCell.CellBorder cellBorder) {
        if (cellBorder == null || cellBorder.strokeWeight <= 0) {
            // 테두리 없음
            hwpxBorder.typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
            return;
        }

        // 선 타입
        LineType2 lineType = strokeTypeToLineType(cellBorder.strokeType);
        hwpxBorder.typeAnd(lineType);

        // 선 두께 (HWPUNIT → mm → LineWidth)
        LineWidth lineWidth = hwpunitToLineWidth(cellBorder.strokeWeight);
        hwpxBorder.widthAnd(lineWidth);

        // 선 색상
        String color = cellBorder.strokeColor;
        if (color == null || color.isEmpty()) {
            color = "#000000";
        }
        hwpxBorder.color(color);
    }

    /**
     * HWPUNIT (1/7200 inch) 값을 LineWidth enum으로 변환한다.
     */
    private LineWidth hwpunitToLineWidth(double hwpunit) {
        // 1 inch = 7200 HWPUNIT, 1 inch = 25.4 mm
        // mm = hwpunit * 25.4 / 7200
        double mm = hwpunit * 25.4 / 7200.0;

        // 가장 가까운 LineWidth 선택
        if (mm <= 0.1) return LineWidth.MM_0_1;
        if (mm <= 0.12) return LineWidth.MM_0_12;
        if (mm <= 0.15) return LineWidth.MM_0_15;
        if (mm <= 0.2) return LineWidth.MM_0_2;
        if (mm <= 0.25) return LineWidth.MM_0_25;
        if (mm <= 0.3) return LineWidth.MM_0_3;
        if (mm <= 0.4) return LineWidth.MM_0_4;
        if (mm <= 0.5) return LineWidth.MM_0_5;
        if (mm <= 0.6) return LineWidth.MM_0_6;
        if (mm <= 0.7) return LineWidth.MM_0_7;
        if (mm <= 1.0) return LineWidth.MM_1_0;
        if (mm <= 1.5) return LineWidth.MM_1_5;
        if (mm <= 2.0) return LineWidth.MM_2_0;
        if (mm <= 3.0) return LineWidth.MM_3_0;
        if (mm <= 4.0) return LineWidth.MM_4_0;
        return LineWidth.MM_5_0;
    }

    /**
     * strokeType 문자열을 LineType2 enum으로 변환한다.
     */
    private LineType2 strokeTypeToLineType(String strokeType) {
        if (strokeType == null) return LineType2.SOLID;
        switch (strokeType.toLowerCase()) {
            case "solid": return LineType2.SOLID;
            case "dashed": case "dash": return LineType2.DASH;
            case "dotted": case "dot": return LineType2.DOT;
            case "none": return LineType2.NONE;
            default: return LineType2.SOLID;
        }
    }

    // ── 이미지 프레임 변환 (floating Picture) ──

    /**
     * 이미지 프레임을 HWPX Picture로 변환한다.
     * HwpxImageWriter에 위임한다.
     */
    private boolean convertImageFrame(Run anchorRun, IntermediateFrame frame) {
        return imageWriter.write(anchorRun, frame);
    }

    // ── 사각형 프레임 변환 (페이지 경계선 등) ──

    /**
     * 인라인 벡터 도형을 HWPX Rectangle/Ellipse/Polygon으로 변환한다.
     * HwpxShapeWriter에 위임한다.
     */
    private void convertShapeFrame(Run anchorRun, IntermediateFrame frame) {
        shapeWriter.write(anchorRun, frame);
    }

    private void convertRectangleFrame(Run anchorRun, IntermediateFrame frame) {
        long x = frame.x();
        long y = frame.y();
        long w = frame.width();
        long h = frame.height();

        Rectangle rect = anchorRun.addNewRectangle();

        // ShapeObject 기본 속성
        String shapeId = nextShapeId();
        rect.idAnd(shapeId)
                .zOrderAnd(frame.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)  // 텍스트 앞에 배치
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(shapeId);

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

        // Rectangle 고유 속성 — 4 코너
        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        // LineShape (테두리 색상 및 두께)
        String borderColor = frame.borderColor() != null ? frame.borderColor() : "#000000";
        // borderWidth는 points 단위, HWPX는 hwpunit (1pt = 100 hwpunit)
        int borderWidthHwp = (int) (frame.borderWidth() * 100);
        if (borderWidthHwp < 14) borderWidthHwp = 14;  // 최소 두께

        rect.createLineShape();
        rect.lineShape().colorAnd(borderColor).widthAnd(borderWidthHwp)
                .styleAnd(LineType2.SOLID)
                .endCapAnd(LineCap.FLAT)
                .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                .headfillAnd(true).tailfillAnd(true)
                .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                .outlineStyleAnd(OutlineStyle.NORMAL).alpha(0f);

        // FillBrush — 채우기 (투명이면 생략, 지정된 색상이면 WinBrush 설정)
        if (frame.fillColor() != null) {
            rect.createFillBrush();
            rect.fillBrush().createWinBrush();
            rect.fillBrush().winBrush().faceColorAnd(frame.fillColor())
                    .hatchColorAnd(frame.fillColor())
                    .alpha(0f);
        }
        // fillColor가 null이면 FillBrush를 생성하지 않음 → 투명 (채우기 없음)
    }

    // ── 유틸리티 ──

    private static String nextParaId() {
        return String.valueOf(PARA_ID_COUNTER.incrementAndGet());
    }

    private static String nextShapeId() {
        return String.valueOf(SHAPE_ID_COUNTER.incrementAndGet());
    }
}
