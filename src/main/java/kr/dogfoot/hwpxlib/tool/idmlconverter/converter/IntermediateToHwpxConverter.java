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
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.hwpx.HwpxShapeWriter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;
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

    // 폰트 및 스타일 레지스트리
    private FontRegistry fontRegistry;
    private StyleRegistry styleRegistry;

    // BorderFill ID 카운터 (1, 2는 BlankFileMaker에서 사용)
    private int borderFillIdCounter = 3;

    // 도형 작성기 (shape frame → HWPX Rectangle/Ellipse/Polygon)
    private final HwpxShapeWriter shapeWriter = new HwpxShapeWriter(SHAPE_ID_COUNTER);

    private IntermediateToHwpxConverter(IntermediateDocument doc) {
        this.doc = doc;
        this.result = new ConvertResult();
    }

    private ConvertResult doConvert() throws ConvertException {
        // 1. BlankFileMaker로 기본 구조 생성
        hwpxFile = BlankFileMaker.make();

        // 2. 레지스트리 초기화
        fontRegistry = new FontRegistry(hwpxFile);
        styleRegistry = new StyleRegistry(hwpxFile, fontRegistry);

        // 3. 폰트 등록
        fontRegistry.registerFonts(doc);

        // 4. 스타일 등록
        registerStyles();

        // 4. 스프레드/페이지별로 별도 section 파일 생성
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

    private int convertTextFrame(Run anchorRun, IntermediateFrame frame) {
        int equationCount = 0;
        if (frame.paragraphs() == null) return 0;

        // 다중 컬럼인 경우 테이블로 변환
        if (frame.columnCount() > 1) {
            return convertMultiColumnTextFrame(anchorRun, frame);
        }

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

        // 회전 정보 적용
        // HWPX rotationInfo.angle은 도(degree) 단위
        // IDML과 HWPX 모두 같은 방향 사용
        double hwpxAngle = frame.rotationAngle();
        short rotationAngleDegrees = (short) Math.round(hwpxAngle);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd(rotationAngleDegrees)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);

        // 회전 행렬 계산
        // HWPX rotMatrix 형식: [cos, -sin, tx, sin, cos, ty]
        double radians = Math.toRadians(hwpxAngle);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(cos, -sin, 0f, sin, cos, 0f);

        // ShapeSize (크기 고정)
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(true);

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

        // LineShape (테두리/획)
        rect.createLineShape();
        String strokeColor = frame.strokeColor();
        double strokeWeight = frame.strokeWeight();
        double strokeTint = frame.strokeTint();

        if (strokeColor != null && strokeWeight > 0) {
            // 획이 있는 경우
            int strokeWidthHwp = (int) (strokeWeight * 100);  // 1pt = 100 hwpunit
            if (strokeWidthHwp < 14) strokeWidthHwp = 14;  // 최소 두께
            float strokeAlpha = (float) ((100.0 - strokeTint) / 100.0);  // 투명도 (0=불투명, 1=투명)

            rect.lineShape().colorAnd(strokeColor).widthAnd(strokeWidthHwp)
                    .styleAnd(LineType2.SOLID)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alpha(strokeAlpha);
        } else {
            // 획 없음 - 투명
            rect.lineShape().colorAnd("#000000").widthAnd(0)
                    .styleAnd(LineType2.NONE)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alpha(0f);
        }

        // FillBrush (채우기)
        String textFrameFillColor = frame.textFrameFillColor();
        double fillTint = frame.fillTint();
        if (textFrameFillColor != null) {
            float fillAlpha = (float) ((100.0 - fillTint) / 100.0);  // 투명도
            rect.createFillBrush();
            rect.fillBrush().createWinBrush();
            rect.fillBrush().winBrush().faceColorAnd(textFrameFillColor)
                    .hatchColorAnd(textFrameFillColor)
                    .alpha(fillAlpha);
        }

        // DrawText — 내부 텍스트
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false).lineShapeFixedAnd(true);
        dt.createTextMargin();
        dt.textMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        dt.createSubList();
        SubList subList = dt.subList();
        TextDirection textDir = frame.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP);

        // SubList에 단락/런 추가
        equationCount = addParagraphsToParaList(subList, frame);

        return equationCount;
    }

    /**
     * 다중 컬럼 텍스트 프레임을 연결된 글상자(Linked Text Boxes)로 변환한다.
     * N개 Rectangle을 생성하고 DrawText.next/prev로 연결한다.
     */
    private int convertMultiColumnTextFrame(Run anchorRun, IntermediateFrame frame) {
        int equationCount = 0;
        int colCount = frame.columnCount();

        // PAPER 기준 좌표
        long x = frame.x();
        long y = frame.y();
        long totalWidth = frame.width();
        long h = frame.height();
        long gutter = frame.columnGutter();

        // 컬럼 너비 = (전체너비 - 간격합계) / 컬럼수
        long totalGutter = gutter * (colCount - 1);
        long colWidth = (totalWidth - totalGutter) / colCount;

        // 회전 정보
        double hwpxAngle = frame.rotationAngle();
        short rotationAngleDegrees = (short) Math.round(hwpxAngle);
        double radians = Math.toRadians(hwpxAngle);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        // 단락 분배: 균등 분배 전략
        List<IntermediateParagraph> allParas = frame.paragraphs();
        int totalParas = allParas.size();
        int parasPerColumn = Math.max(1, (totalParas + colCount - 1) / colCount);

        // 컬럼별 ID 먼저 생성 (next/prev 링크 설정에 필요)
        List<String> boxIds = new ArrayList<>();
        for (int col = 0; col < colCount; col++) {
            boxIds.add(nextShapeId());
        }

        // N개 Rectangle 생성
        for (int col = 0; col < colCount; col++) {
            String boxId = boxIds.get(col);
            long colX = x + (colWidth + gutter) * col;

            Rectangle rect = anchorRun.addNewRectangle();

            // ShapeObject 기본 속성
            rect.idAnd(boxId)
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
            rect.orgSz().set(colWidth, h);

            rect.createCurSz();
            rect.curSz().set(colWidth, h);

            rect.createFlip();
            rect.flip().horizontalAnd(false).verticalAnd(false);

            // 회전 정보
            rect.createRotationInfo();
            rect.rotationInfo().angleAnd(rotationAngleDegrees)
                    .centerXAnd(colWidth / 2).centerYAnd(h / 2).rotateimageAnd(true);

            rect.createRenderingInfo();
            rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            rect.renderingInfo().addNewRotMatrix().set(cos, -sin, 0f, sin, cos, 0f);

            // ShapeSize
            rect.createSZ();
            rect.sz().widthAnd(colWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                    .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                    .protectAnd(true);

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
                    .horzOffset(colX);

            // OutMargin
            rect.createOutMargin();
            rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

            // Rectangle 고유 속성 — 4 코너, 직각
            rect.ratioAnd((short) 0);
            rect.createPt0();
            rect.pt0().set(0L, 0L);
            rect.createPt1();
            rect.pt1().set(colWidth, 0L);
            rect.createPt2();
            rect.pt2().set(colWidth, h);
            rect.createPt3();
            rect.pt3().set(0L, h);

            // LineShape (테두리/획)
            rect.createLineShape();
            String strokeColor = frame.strokeColor();
            double strokeWeight = frame.strokeWeight();
            double strokeTint = frame.strokeTint();

            if (strokeColor != null && strokeWeight > 0) {
                int strokeWidthHwp = (int) (strokeWeight * 100);
                if (strokeWidthHwp < 14) strokeWidthHwp = 14;
                float strokeAlpha = (float) ((100.0 - strokeTint) / 100.0);

                rect.lineShape().colorAnd(strokeColor).widthAnd(strokeWidthHwp)
                        .styleAnd(LineType2.SOLID)
                        .endCapAnd(LineCap.FLAT)
                        .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                        .headfillAnd(true).tailfillAnd(true)
                        .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                        .outlineStyleAnd(OutlineStyle.NORMAL).alpha(strokeAlpha);
            } else {
                rect.lineShape().colorAnd("#000000").widthAnd(0)
                        .styleAnd(LineType2.NONE)
                        .endCapAnd(LineCap.FLAT)
                        .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                        .headfillAnd(true).tailfillAnd(true)
                        .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                        .outlineStyleAnd(OutlineStyle.NORMAL).alpha(0f);
            }

            // FillBrush (채우기) - 첫 번째 컬럼만 배경색 적용
            String textFrameFillColor = frame.textFrameFillColor();
            double fillTint = frame.fillTint();
            if (textFrameFillColor != null) {
                float fillAlpha = (float) ((100.0 - fillTint) / 100.0);
                rect.createFillBrush();
                rect.fillBrush().createWinBrush();
                rect.fillBrush().winBrush().faceColorAnd(textFrameFillColor)
                        .hatchColorAnd(textFrameFillColor)
                        .alpha(fillAlpha);
            }

            // DrawText — 내부 텍스트
            rect.createDrawText();
            DrawText dt = rect.drawText();
            dt.lastWidthAnd(colWidth).nameAnd("col_" + col).editableAnd(false).lineShapeFixedAnd(true);

            // 연결된 글상자 링크 설정
            if (col > 0) {
                dt.prevAnd(boxIds.get(col - 1));
            }
            if (col < colCount - 1) {
                dt.nextAnd(boxIds.get(col + 1));
            }

            dt.createTextMargin();
            // 프레임 여백 (첫/마지막 컬럼에만 좌우 여백 적용)
            long leftMargin = (col == 0) ? frame.insetLeft() : 0L;
            long rightMargin = (col == colCount - 1) ? frame.insetRight() : 0L;
            dt.textMargin().leftAnd(leftMargin).rightAnd(rightMargin)
                    .topAnd(frame.insetTop()).bottomAnd(frame.insetBottom());

            dt.createSubList();
            SubList subList = dt.subList();
            TextDirection textDir = frame.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
            subList.idAnd("").textDirectionAnd(textDir)
                    .lineWrapAnd(LineWrapMethod.BREAK)
                    .vertAlignAnd(VerticalAlign2.TOP);

            // 이 컬럼에 배정된 단락들 추가
            int startIdx = col * parasPerColumn;
            int endIdx = Math.min(startIdx + parasPerColumn, totalParas);

            for (int i = startIdx; i < endIdx; i++) {
                IntermediateParagraph iPara = allParas.get(i);
                equationCount += addParagraphToSubList(subList, iPara);
            }

            // 빈 박스 방지 - 최소 빈 단락 추가
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

        return equationCount;
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
                    addParagraphToSubList(subList, iPara);
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
     * 단락을 SubList에 추가한다 (테이블 셀용).
     */
    private int addParagraphToSubList(SubList subList, IntermediateParagraph iPara) {
        int equationCount = 0;

        String paraPrId = "3";
        String styleId = "0";
        String paraCharPrId = "0";
        IntermediateStyleDef paraStyleDef = null;

        if (iPara.paragraphStyleRef() != null) {
            String mapped = styleRegistry.getParaPrId(iPara.paragraphStyleRef());
            if (mapped != null) paraPrId = mapped;
            String mappedStyle = styleRegistry.getStyleId(iPara.paragraphStyleRef());
            if (mappedStyle != null) styleId = mappedStyle;
            String mappedCharPr = styleRegistry.getCharPrId(iPara.paragraphStyleRef());
            if (mappedCharPr != null) paraCharPrId = mappedCharPr;
            paraStyleDef = styleRegistry.getParagraphStyleDef(iPara.paragraphStyleRef());
        }

        // 인라인 단락 속성이 있으면 새 ParaPr 생성
        if (hasInlineParagraphOverrides(iPara)) {
            paraPrId = createInlineParaPr(iPara, paraStyleDef);
        }

        Para para = subList.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd(styleId)
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        if (iPara.contentItems().isEmpty()) {
            Run run = para.addNewRun();
            run.charPrIDRef(paraCharPrId);
            run.addNewT();
            return 0;
        }

        for (IntermediateParagraph.ContentItem item : iPara.contentItems()) {
            if (item.isEquation()) {
                addInlineEquationRun(para, item.equation());
                equationCount++;
            } else if (item.isTextRun()) {
                String charPrId = resolveCharPrId(item.textRun(), paraCharPrId, paraStyleDef);
                String text = sanitizeText(item.textRun().text() != null ? item.textRun().text() : "");

                Run run = para.addNewRun();
                run.charPrIDRef(charPrId);
                run.addNewT().addText(text);
            }
        }

        if (para.countOfRun() == 0) {
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT();
        }

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
                String mapped = styleRegistry.getParaPrId(iPara.paragraphStyleRef());
                if (mapped != null) paraPrId = mapped;
                String mappedStyle = styleRegistry.getStyleId(iPara.paragraphStyleRef());
                if (mappedStyle != null) styleId = mappedStyle;
                String mappedCharPr = styleRegistry.getCharPrId(iPara.paragraphStyleRef());
                if (mappedCharPr != null) paraCharPrId = mappedCharPr;
                paraStyleDef = styleRegistry.getParagraphStyleDef(iPara.paragraphStyleRef());
            }

            // 인라인 단락 속성이 있으면 새 ParaPr 생성
            if (hasInlineParagraphOverrides(iPara)) {
                paraPrId = createInlineParaPr(iPara, paraStyleDef);
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
     * 인라인 단락 속성(로컬 오버라이드)이 있는지 확인한다.
     */
    private boolean hasInlineParagraphOverrides(IntermediateParagraph iPara) {
        return iPara.inlineAlignment() != null
                || iPara.inlineFirstLineIndent() != null
                || iPara.inlineLeftMargin() != null
                || iPara.inlineRightMargin() != null
                || iPara.inlineSpaceBefore() != null
                || iPara.inlineSpaceAfter() != null
                || iPara.inlineLineSpacing() != null
                || iPara.shadingOn();
    }

    /**
     * 인라인 단락 속성을 적용한 새 ParaPr을 생성한다.
     */
    private String createInlineParaPr(IntermediateParagraph iPara, IntermediateStyleDef baseStyle) {
        String paraPrId = styleRegistry.nextParaPrId();
        ParaPr paraPr = hwpxFile.headerXMLFile().refList().paraProperties().addNew();

        // 기본 속성 설정 (StyleRegistry.buildParaPr과 유사)
        paraPr.idAnd(paraPrId)
                .tabPrIDRefAnd("0")
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        // 정렬 - 인라인 우선, 없으면 스타일, 기본값 JUSTIFY
        String alignment = iPara.inlineAlignment();
        if (alignment == null && baseStyle != null) {
            alignment = baseStyle.alignment();
        }
        HorizontalAlign2 hAlign = mapAlignmentString(alignment);
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

        // 마진 - 인라인 우선, 없으면 스타일
        int indent = iPara.inlineFirstLineIndent() != null ? iPara.inlineFirstLineIndent().intValue()
                : (baseStyle != null && baseStyle.firstLineIndent() != null ? baseStyle.firstLineIndent().intValue() : 0);
        int left = iPara.inlineLeftMargin() != null ? iPara.inlineLeftMargin().intValue()
                : (baseStyle != null && baseStyle.leftMargin() != null ? baseStyle.leftMargin().intValue() : 0);
        int right = iPara.inlineRightMargin() != null ? iPara.inlineRightMargin().intValue()
                : (baseStyle != null && baseStyle.rightMargin() != null ? baseStyle.rightMargin().intValue() : 0);
        int prev = iPara.inlineSpaceBefore() != null ? iPara.inlineSpaceBefore().intValue()
                : (baseStyle != null && baseStyle.spaceBefore() != null ? baseStyle.spaceBefore().intValue() : 0);
        int next = iPara.inlineSpaceAfter() != null ? iPara.inlineSpaceAfter().intValue()
                : (baseStyle != null && baseStyle.spaceAfter() != null ? baseStyle.spaceAfter().intValue() : 0);

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

        // 줄 간격 - 인라인 우선, 없으면 스타일
        LineSpacingType lsType = LineSpacingType.PERCENT;
        int lsValue = 130;
        if (iPara.inlineLineSpacing() != null) {
            // 인라인 줄간격이 있으면 백분율로 적용
            lsType = LineSpacingType.PERCENT;
            lsValue = iPara.inlineLineSpacing();
        } else if (baseStyle != null) {
            if ("fixed".equals(baseStyle.lineSpacingType())) {
                lsType = LineSpacingType.FIXED;
            }
            if (baseStyle.lineSpacingPercent() != null) {
                lsValue = baseStyle.lineSpacingPercent();
            }
        }
        paraPr.createLineSpacing();
        paraPr.lineSpacing().typeAnd(lsType).valueAnd(lsValue).unit(ValueUnit2.HWPUNIT);

        // 단락 음영 처리
        String borderFillId = "2";  // 기본 BorderFill (투명)
        int offsetLeft = 0, offsetRight = 0, offsetTop = 0, offsetBottom = 0;
        if (iPara.shadingOn() && iPara.shadingColor() != null) {
            borderFillId = createShadingBorderFill(iPara.shadingColor());
            // 음영 오프셋 적용
            if (iPara.shadingOffsetLeft() != null) {
                offsetLeft = iPara.shadingOffsetLeft().intValue();
            }
            if (iPara.shadingOffsetRight() != null) {
                offsetRight = iPara.shadingOffsetRight().intValue();
            }
            if (iPara.shadingOffsetTop() != null) {
                offsetTop = iPara.shadingOffsetTop().intValue();
            }
            if (iPara.shadingOffsetBottom() != null) {
                offsetBottom = iPara.shadingOffsetBottom().intValue();
            }
        }

        paraPr.createBorder();
        paraPr.border().borderFillIDRefAnd(borderFillId)
                .offsetLeftAnd(offsetLeft).offsetRightAnd(offsetRight)
                .offsetTopAnd(offsetTop).offsetBottomAnd(offsetBottom)
                .connectAnd(false).ignoreMargin(false);

        return paraPrId;
    }

    /**
     * 테이블 셀용 BorderFill을 생성한다.
     */
    private String createCellBorderFill(IntermediateTableCell cell) {
        String bfId = String.valueOf(borderFillIdCounter++);
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

    /**
     * 단락 음영용 BorderFill을 생성한다.
     */
    private String createShadingBorderFill(String fillColor) {
        String bfId = String.valueOf(borderFillIdCounter++);
        BorderFill bf = hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);

        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);

        // 테두리 없음
        bf.createLeftBorder();
        bf.leftBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createRightBorder();
        bf.rightBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createTopBorder();
        bf.topBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_1).color("#000000");

        // 배경 채우기 설정
        bf.createFillBrush();
        bf.fillBrush().createWinBrush();
        bf.fillBrush().winBrush()
                .faceColorAnd(fillColor)
                .hatchColorAnd("#FF000000")
                .alpha(0f);

        return bfId;
    }

    /**
     * 정렬 문자열을 HorizontalAlign2로 변환한다.
     */
    private static HorizontalAlign2 mapAlignmentString(String alignment) {
        if (alignment == null) return HorizontalAlign2.JUSTIFY;
        switch (alignment) {
            case "left": return HorizontalAlign2.LEFT;
            case "center": return HorizontalAlign2.CENTER;
            case "right": return HorizontalAlign2.RIGHT;
            case "justify": return HorizontalAlign2.JUSTIFY;
            default: return HorizontalAlign2.JUSTIFY;
        }
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
            IntermediateStyleDef charStyleDef = styleRegistry.getCharacterStyleDef(iRun.characterStyleRef());
            if (charStyleDef != null && paraStyleDef != null && hasCharStyleOverrides(charStyleDef)) {
                // 문자 스타일이 단락 스타일과 다른 속성을 가지면 병합된 CharPr 생성
                return createMergedCharPr(paraStyleDef, charStyleDef);
            }
            // 문자 스타일에 오버라이드가 없으면 단락 스타일 CharPr 사용
            if (charStyleDef != null && paraStyleDef != null) {
                return paraCharPrId;
            }
            // paraStyleDef가 없으면 문자 스타일 CharPr 직접 사용
            String id = styleRegistry.getCharPrId(iRun.characterStyleRef());
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
        String charPrId = styleRegistry.nextCharPrId();
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
        String fontId = fontRegistry.resolveFontId(fontFamily);
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        charPr.createRatio();
        charPr.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);

        // 자간 (letterSpacing) - 문자 스타일 우선, 없으면 단락 스타일에서 상속
        short spacing = charStyle.letterSpacing() != null ? charStyle.letterSpacing()
                : (paraStyle.letterSpacing() != null ? paraStyle.letterSpacing() : (short) 0);
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

        return charPrId;
    }

    private boolean hasRunOverrides(IntermediateTextRun iRun) {
        return iRun.bold() != null || iRun.italic() != null
                || iRun.fontSizeHwpunits() != null
                || iRun.textColor() != null
                || iRun.fontFamily() != null
                || iRun.letterSpacing() != null;
    }

    private String createRunCharPr(IntermediateTextRun iRun, IntermediateStyleDef paraStyleDef) {
        String charPrId = styleRegistry.nextCharPrId();
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();

        // 단락 스타일 속성을 기본값으로 사용하고, 런 레벨 오버라이드로 덮어쓴다
        int baseHeight = 1000;
        String baseTextColor = "#000000";
        String baseFontFamily = null;
        Boolean baseBold = null;
        Boolean baseItalic = null;
        short baseLetterSpacing = 0;
        if (paraStyleDef != null) {
            if (paraStyleDef.fontSizeHwpunits() != null) baseHeight = paraStyleDef.fontSizeHwpunits();
            if (paraStyleDef.textColor() != null) baseTextColor = paraStyleDef.textColor();
            baseFontFamily = paraStyleDef.fontFamily();
            baseBold = paraStyleDef.bold();
            baseItalic = paraStyleDef.italic();
            if (paraStyleDef.letterSpacing() != null) baseLetterSpacing = paraStyleDef.letterSpacing();
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
        String fontId = fontRegistry.resolveFontId(fontFamily);
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        charPr.createRatio();
        charPr.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);

        // 자간 (letterSpacing) - 런 레벨 우선, 없으면 스타일에서 상속
        short letterSpacing = iRun.letterSpacing() != null ? iRun.letterSpacing() : baseLetterSpacing;
        charPr.createSpacing();
        charPr.spacing().set(letterSpacing, letterSpacing, letterSpacing, letterSpacing, letterSpacing, letterSpacing, letterSpacing);

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
            long croppedW = displayW;
            long croppedH = displayH;

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
            pic.curSz().set(croppedW, croppedH);

            pic.createFlip();
            pic.flip().horizontalAnd(false).verticalAnd(false);

            pic.createRotationInfo();
            pic.rotationInfo().angleAnd((short) 0)
                    .centerXAnd(croppedW / 2).centerYAnd(croppedH / 2)
                    .rotateimageAnd(true);

            pic.createRenderingInfo();
            pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
            pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

            // ShapeSize
            pic.createSZ();
            pic.sz().widthAnd(croppedW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                    .heightAnd(croppedH).heightRelToAnd(HeightRelTo.ABSOLUTE)
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

            // ImageRect (크롭 후 크기)
            pic.createImgRect();
            pic.imgRect().createPt0();
            pic.imgRect().pt0().set(0L, 0L);
            pic.imgRect().createPt1();
            pic.imgRect().pt1().set(croppedW, 0L);
            pic.imgRect().createPt2();
            pic.imgRect().pt2().set(croppedW, croppedH);
            pic.imgRect().createPt3();
            pic.imgRect().pt3().set(0L, croppedH);

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
        rect.idAnd(nextShapeId())
                .zOrderAnd(frame.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)  // 텍스트 앞에 배치
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
