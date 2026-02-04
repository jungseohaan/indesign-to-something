package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
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
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertResult;
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
     * @param useDocMargins true: 문서 레이아웃 마진 사용, false: 최소 마진 사용
     * @param addPageNum    true: 페이지 번호 컨트롤 추가
     * @return 생성된 Para 객체
     */
    private Para createSectionBreakPara(SectionXMLFile section, long pageWidth, long pageHeight,
                                         boolean useDocMargins, boolean addPageNum) {
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

        // 마진
        int marginTop, marginBottom, marginLeft, marginRight, headerFooter;
        if (useDocMargins) {
            DocumentLayout layout = doc.layout();
            marginTop = (int) (layout != null ? layout.marginTop() : 5668);
            marginBottom = (int) (layout != null ? layout.marginBottom() : 4252);
            marginLeft = (int) (layout != null ? layout.marginLeft() : 8504);
            marginRight = (int) (layout != null ? layout.marginRight() : 8504);
            headerFooter = 4252;
        } else {
            // 스프레드 모드: 최소 마진
            marginTop = marginBottom = marginLeft = marginRight = 1417;
            headerFooter = 1417;
        }

        secPr.pagePr().createMargin();
        secPr.pagePr().margin()
                .headerAnd(headerFooter).footerAnd(headerFooter).gutterAnd(0)
                .leftAnd(marginLeft).rightAnd(marginRight)
                .topAnd(marginTop).bottom(marginBottom);

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

    // 페이지 모드용 래퍼 (하위 호환성)
    private void addSectionBreakPara(SectionXMLFile section, IntermediatePage page, boolean isFirst) {
        createSectionBreakPara(section, page.pageWidth(), page.pageHeight(), true, true);
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

    // 스프레드 모드용 래퍼 (Para 반환)
    private Para addSectionBreakParaForSpreadWithReturn(SectionXMLFile section, IntermediateSpread spread) {
        return createSectionBreakPara(section, spread.spreadWidth(), spread.spreadHeight(), false, false);
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
     * 다중 컬럼 텍스트 프레임을 1행 N열 테이블로 변환한다.
     */
    private int convertMultiColumnTextFrame(Run anchorRun, IntermediateFrame frame) {
        int equationCount = 0;
        int colCount = frame.columnCount();

        // PAPER 기준 좌표
        long x = Math.max(0, frame.x());
        long y = Math.max(0, frame.y());
        long totalWidth = frame.width();
        long height = frame.height();
        long gutter = frame.columnGutter();

        // 컬럼 너비 = (전체너비 - 간격합계) / 컬럼수
        long totalGutter = gutter * (colCount - 1);
        long cellWidth = (totalWidth - totalGutter) / colCount;

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
                .rowCntAnd((short) 1)
                .colCntAnd((short) colCount)
                .cellSpacingAnd(0)  // 셀 간격은 개별 셀 여백으로 처리
                .borderFillIDRefAnd("1")  // 투명 테두리
                .noAdjustAnd(false);

        // ShapeSize
        table.createSZ();
        table.sz().widthAnd(totalWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(height).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

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

        // InMargin (프레임 내부 여백)
        table.createInMargin();
        table.inMargin().leftAnd(frame.insetLeft())
                .rightAnd(frame.insetRight())
                .topAnd(frame.insetTop())
                .bottomAnd(frame.insetBottom());

        // 단락 분배: 균등 분배 전략
        List<IntermediateParagraph> allParas = frame.paragraphs();
        int totalParas = allParas.size();
        int parasPerColumn = Math.max(1, (totalParas + colCount - 1) / colCount);

        // 단일 행(Tr) 생성
        Tr tr = table.addNewTr();

        // 각 컬럼(Tc) 생성
        for (int col = 0; col < colCount; col++) {
            Tc tc = tr.addNewTc();
            tc.nameAnd("")
                    .headerAnd(false)
                    .hasMarginAnd(true)  // 셀 마진 사용
                    .protectAnd(false)
                    .editableAnd(false)
                    .dirtyAnd(false)
                    .borderFillIDRefAnd("1");  // 투명 테두리

            // 셀 주소
            tc.createCellAddr();
            tc.cellAddr().colAddrAnd((short) col).rowAddrAnd((short) 0);

            // 셀 병합 (병합 없음)
            tc.createCellSpan();
            tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);

            // 셀 크기
            tc.createCellSz();
            tc.cellSz().widthAnd(cellWidth).heightAnd(height);

            // 셀 여백
            tc.createCellMargin();
            if (col < colCount - 1) {
                // 마지막 컬럼이 아니면 오른쪽 여백으로 gutter 절반
                tc.cellMargin().leftAnd(0L).rightAnd(gutter / 2).topAnd(0L).bottomAnd(0L);
            } else {
                tc.cellMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
            }
            if (col > 0) {
                // 첫 컬럼이 아니면 왼쪽 여백으로 gutter 절반
                tc.cellMargin().leftAnd(gutter / 2);
            }

            // 셀 내부 SubList
            tc.createSubList();
            SubList subList = tc.subList();
            subList.idAnd("")
                    .textDirectionAnd(TextDirection.HORIZONTAL)
                    .lineWrapAnd(LineWrapMethod.BREAK)
                    .vertAlignAnd(VerticalAlign2.TOP);

            // 이 컬럼에 배정된 단락들 추가
            int startIdx = col * parasPerColumn;
            int endIdx = Math.min(startIdx + parasPerColumn, totalParas);

            for (int i = startIdx; i < endIdx; i++) {
                IntermediateParagraph iPara = allParas.get(i);
                equationCount += addParagraphToSubList(subList, iPara);
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

        return equationCount;
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
        String charPrId = styleRegistry.nextCharPrId();
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
        String fontId = fontRegistry.resolveFontId(fontFamily);
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

    // ── 유틸리티 ──

    private static String nextParaId() {
        return String.valueOf(PARA_ID_COUNTER.incrementAndGet());
    }

    private static String nextShapeId() {
        return String.valueOf(SHAPE_ID_COUNTER.incrementAndGet());
    }
}
