package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.Border;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertResult;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateStyleDef;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ASTDocument를 HWPXFile로 변환한다.
 *
 * 변환 전략:
 * 1. BlankFileMaker로 기본 HWPX 구조 생성
 * 2. 폰트/스타일 등록
 * 3. 섹션별 SecPr 생성 (페이지 레이아웃)
 * 4. 블록별 HWPX 요소 생성:
 *    - ASTTextFrameBlock → Rectangle + DrawText
 *    - ASTTable → Table + Tr + Tc
 *    - ASTFigure → Picture
 * 5. 배경 PNG → Picture (z-order 최하위)
 */
public class ASTToHwpxConverter {

    private static final AtomicLong PARA_ID_COUNTER = new AtomicLong(2000000000L);
    private static final AtomicLong SHAPE_ID_COUNTER = new AtomicLong(8000000L);

    public static ConvertResult convert(ASTDocument doc) throws ConvertException {
        try {
            return new ASTToHwpxConverter(doc).doConvert();
        } catch (ConvertException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.HWPX_GENERATION,
                    "AST→HWPX conversion failed: " + e.getMessage(), e);
        }
    }

    private final ASTDocument doc;
    private final ConvertResult result;
    private HWPXFile hwpxFile;
    private FontRegistry fontRegistry;
    private StyleRegistry styleRegistry;
    private final AtomicInteger borderFillIdCounter = new AtomicInteger(3);

    // 통계
    private int pagesConverted;
    private int framesConverted;
    private int imagesConverted;

    private ASTToHwpxConverter(ASTDocument doc) {
        this.doc = doc;
        this.result = new ConvertResult();
    }

    private ConvertResult doConvert() throws ConvertException {
        System.err.println("[ASTToHwpxConverter] Starting conversion...");

        // 1. 기본 HWPX 구조 생성
        hwpxFile = BlankFileMaker.make();

        // 2. 레지스트리 초기화
        fontRegistry = new FontRegistry(hwpxFile);
        styleRegistry = new StyleRegistry(hwpxFile, fontRegistry);

        // 3. 폰트 등록
        registerFonts();

        // 4. 스타일 등록
        registerStyles();

        // 5. 섹션/블록 변환
        SectionXMLFile section0 = hwpxFile.sectionXMLFileList().get(0);
        section0.removeAllParas();

        for (ASTSection section : doc.sections()) {
            convertSection(section0, section);
            pagesConverted++;
        }

        // 6. 배경 PNG 배치
        for (ASTPageBackground bg : doc.backgrounds()) {
            if (bg.pngData() != null && bg.pngData().length > 0) {
                addBackgroundImage(section0, bg);
            }
        }

        // 7. 빈 section 방지
        if (section0.countOfPara() == 0) {
            addEmptyPara(section0);
        }

        hwpxFile.headerXMLFile().secCnt((short) 1);

        result.hwpxFile(hwpxFile);
        result.pagesConverted(pagesConverted);
        result.framesConverted(framesConverted);
        result.imagesConverted(imagesConverted);
        result.stylesConverted(styleRegistry.totalStyleCount());

        System.err.println("[ASTToHwpxConverter] Done. " + result.summary());
        return result;
    }

    // ── 폰트 등록 ──

    private void registerFonts() {
        for (ASTFontDef fontDef : doc.fonts()) {
            String fontFamily = fontDef.fontFamily();
            if (fontFamily != null) {
                // FontRegistry.resolveFontId()가 내부적으로 FontMapper를 사용하여 등록
                fontRegistry.resolveFontId(fontFamily);
            }
        }
    }

    // ── 스타일 등록 ──

    private void registerStyles() {
        for (ASTStyleDef astStyle : doc.paragraphStyles()) {
            IntermediateStyleDef iStyle = toIntermediateStyleDef(astStyle, "paragraph");
            styleRegistry.registerParagraphStyle(iStyle);
        }
        for (ASTStyleDef astStyle : doc.characterStyles()) {
            IntermediateStyleDef iStyle = toIntermediateStyleDef(astStyle, "character");
            styleRegistry.registerCharacterStyle(iStyle);
        }
    }

    private IntermediateStyleDef toIntermediateStyleDef(ASTStyleDef ast, String type) {
        IntermediateStyleDef def = new IntermediateStyleDef();
        def.id(ast.styleId());
        def.name(ast.styleName());
        def.type(type);
        def.basedOn(ast.basedOnStyleRef());
        def.fontFamily(ast.fontFamily());
        def.fontSizeHwpunits(ast.fontSizeHwpunits());
        def.textColor(ast.textColor());
        def.alignment(ast.alignment());
        def.firstLineIndent(ast.firstLineIndent());
        def.leftMargin(ast.leftMargin());
        def.rightMargin(ast.rightMargin());
        def.spaceBefore(ast.spaceBefore());
        def.spaceAfter(ast.spaceAfter());
        def.lineSpacingPercent(ast.lineSpacing());
        def.lineSpacingType(ast.lineSpacingType());
        def.letterSpacing(ast.letterSpacing());
        // fontStyle → bold/italic
        if (ast.fontStyle() != null) {
            String style = ast.fontStyle().toLowerCase();
            def.bold(style.contains("bold"));
            def.italic(style.contains("italic"));
        }
        return def;
    }

    // ── 섹션 변환 ──

    private void convertSection(SectionXMLFile sectionFile, ASTSection section) {
        ASTPageLayout layout = section.layout();
        if (layout == null) return;

        // SecPr 단락 생성
        createSectionBreakPara(sectionFile, layout);

        // TEXT_FRAME_BLOCK 수집
        List<ASTTextFrameBlock> textFrameBlocks = new ArrayList<>();
        List<ASTBlock> otherBlocks = new ArrayList<>();
        for (ASTBlock block : section.blocks()) {
            if (block.blockType() == ASTBlock.BlockType.TEXT_FRAME_BLOCK) {
                textFrameBlocks.add((ASTTextFrameBlock) block);
            } else {
                otherBlocks.add(block);
            }
        }

        // 텍스트 프레임 → 인라인 글상자 또는 플로팅 테이블로 변환
        if (!textFrameBlocks.isEmpty()) {
            // 인라인 글상자 대상과 플로팅 대상 분리
            List<ASTTextFrameBlock> inlineBlocks = new ArrayList<>();
            List<ASTTextFrameBlock> floatingBlocks = new ArrayList<>();
            for (ASTTextFrameBlock block : textFrameBlocks) {
                if (needsFloatingPosition(block, layout)) {
                    floatingBlocks.add(block);
                } else {
                    inlineBlocks.add(block);
                }
            }

            // 인라인 글상자: 읽기 순서 정렬 후 변환
            sortInReadingOrder(inlineBlocks);
            for (ASTTextFrameBlock block : inlineBlocks) {
                addTextBox(sectionFile, block);
                framesConverted++;
            }

            // 플로팅 테이블: 원래 절대 좌표 유지
            for (ASTTextFrameBlock block : floatingBlocks) {
                convertTextFrameBlock(sectionFile, block);
                framesConverted++;
            }
        }

        // 나머지 (TABLE, FIGURE)는 기존대로 플로팅
        for (ASTBlock block : otherBlocks) {
            switch (block.blockType()) {
                case TABLE:
                    convertTable(sectionFile, (ASTTable) block);
                    framesConverted++;
                    break;
                case FIGURE:
                    convertFigure(sectionFile, (ASTFigure) block);
                    framesConverted++;
                    break;
            }
        }
    }

    // ── 글상자 변환 ──

    /**
     * 텍스트 프레임이 플로팅 배치가 필요한지 판별.
     * IDML의 모든 텍스트 프레임은 절대 좌표를 가지므로 플로팅으로 배치한다.
     */
    private boolean needsFloatingPosition(ASTTextFrameBlock block, ASTPageLayout layout) {
        return true;
    }

    /**
     * TEXT_FRAME_BLOCK 리스트를 읽기 순서 (위→아래, 왼→오른)로 정렬.
     */
    private void sortInReadingOrder(List<ASTTextFrameBlock> blocks) {
        long minHeight = Long.MAX_VALUE;
        for (ASTTextFrameBlock b : blocks) {
            if (b.height() > 0 && b.height() < minHeight) minHeight = b.height();
        }
        final long tolerance = minHeight > 0 && minHeight < Long.MAX_VALUE ? minHeight / 5 : 500;

        blocks.sort((a, b) -> {
            long yDiff = a.y() - b.y();
            if (Math.abs(yDiff) <= tolerance) {
                return Long.compare(a.x(), b.x());
            }
            return Long.compare(a.y(), b.y());
        });
    }

    /**
     * ASTTextFrameBlock → hp:rect + hp:drawText (글상자, treatAsChar="1")
     */
    private void addTextBox(SectionXMLFile sectionFile, ASTTextFrameBlock block) {
        // 앵커 단락
        Para framePara = sectionFile.addNewPara();
        framePara.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        long w = block.width();
        if (w < 142) w = 142;

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = nextShapeId();

        // ShapeObject
        rect.idAnd(shapeId)
                .zOrderAnd(block.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        long textBoxMinH = 1600; // 최소 높이 (한 줄), 내용에 맞게 자동 확장됨
        rect.createOrgSz();
        rect.orgSz().set(w, textBoxMinH);
        rect.createCurSz();
        rect.curSz().set(w, 0L); // height=0: 한컴이 내용에 맞게 자동 계산
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(textBoxMinH / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // LineShape (테두리)
        setupTextBoxLineShape(rect, block.strokeColor(), block.strokeWeight(),
                block.strokeType(), block.strokeTint());

        // FillBrush (배경색)
        setupTextBoxFillBrush(rect, block.fillColor(), block.fillTint());

        // DrawText (글상자 내용)
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);

        dt.createTextMargin();
        dt.textMargin()
                .leftAnd(block.insetLeft())
                .rightAnd(block.insetRight())
                .topAnd(block.insetTop())
                .bottomAnd(block.insetBottom());

        dt.createSubList();
        SubList subList = dt.subList();
        TextDirection textDir = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        VerticalAlign2 vAlign = mapVerticalJustification(block.verticalJustification());

        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(vAlign)
                .linkListIDRefAnd("0")
                .linkListNextIDRefAnd("0")
                .textWidthAnd(0)
                .textHeightAnd(0)
                .hasTextRefAnd(false)
                .hasNumRefAnd(false);

        // 내용 단락
        for (ASTParagraph para : block.paragraphs()) {
            addParagraphToSubList(subList, para);
        }

        if (subList.countOfPara() == 0) {
            addEmptySubListPara(subList);
        }

        // Rectangle 꼭짓점 — 최소 높이, curSz height=0 으로 내용에 맞게 자동 확장
        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, textBoxMinH);
        rect.createPt3();
        rect.pt3().set(0L, textBoxMinH);

        // ShapeSize
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(textBoxMinH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — 글자처럼 취급 (인라인)
        rect.createPos();
        rect.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.PARA)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        // OutMargin
        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 앵커 런에 빈 텍스트 추가
        anchorRun.addNewT();
    }

    /**
     * 글상자 테두리 설정
     */
    private void setupTextBoxLineShape(Rectangle rect, String strokeColor, double strokeWeightPt,
                                        String strokeType, double strokeTint) {
        rect.createLineShape();
        boolean hasStroke = strokeColor != null && strokeColor.startsWith("#") && strokeWeightPt > 0;

        if (hasStroke) {
            // points → hwpunit (1pt = 약 100 hwpunit for line width)
            int strokeW = (int) Math.round(strokeWeightPt * 100);
            if (strokeW < 14) strokeW = 14;
            float alpha = (float) ((100.0 - strokeTint) / 100.0);
            LineType2 lineType = strokeTypeToLineType(strokeType);

            rect.lineShape().colorAnd(strokeColor).widthAnd(strokeW)
                    .styleAnd(lineType)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.SMALL_SMALL).tailSzAnd(ArrowSize.SMALL_SMALL)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alphaAnd(alpha);
        } else {
            rect.lineShape().colorAnd("#000000").widthAnd(14)
                    .styleAnd(LineType2.NONE)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.SMALL_SMALL).tailSzAnd(ArrowSize.SMALL_SMALL)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alphaAnd(0f);
        }
    }

    /**
     * 글상자 배경색 설정
     */
    private void setupTextBoxFillBrush(Rectangle rect, String fillColor, double fillTint) {
        if (fillColor != null && fillColor.startsWith("#")) {
            float alpha = (float) ((100.0 - fillTint) / 100.0);
            rect.createFillBrush();
            rect.fillBrush().createWinBrush();
            rect.fillBrush().winBrush()
                    .faceColorAnd(fillColor)
                    .hatchColorAnd("#000000")
                    .alphaAnd(alpha);
        }
    }

    /**
     * IDML verticalJustification → HWPX VerticalAlign2 매핑
     */
    private VerticalAlign2 mapVerticalJustification(String vj) {
        if (vj == null) return VerticalAlign2.TOP;
        switch (vj.toLowerCase()) {
            case "centeralign": case "center": return VerticalAlign2.CENTER;
            case "bottomalign": case "bottom": return VerticalAlign2.BOTTOM;
            default: return VerticalAlign2.TOP;
        }
    }

    /**
     * SubList에 ColPr(colCount=1) 리셋 단락 추가
     */
    private void addColPrResetParagraph(SubList subList) {
        Para colPrPara = subList.addNewPara();
        colPrPara.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run colPrRun = colPrPara.addNewRun();
        colPrRun.charPrIDRef("0");

        Ctrl colCtrl = colPrRun.addNewCtrl();
        colCtrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(1).sameSzAnd(true).sameGap(0);
    }

    // ── SecPr 생성 ──

    private void createSectionBreakPara(SectionXMLFile sectionFile, ASTPageLayout layout) {
        Para para = sectionFile.addNewPara();
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
                .tabStopUnitAnd(ValueUnit1.HWPUNIT)
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

        // 페이지 크기 및 방향 (WIDELY=세로, NARROWLY=가로)
        PageDirection direction = layout.pageWidth() > layout.pageHeight()
                ? PageDirection.NARROWLY : PageDirection.WIDELY;
        secPr.createPagePr();
        secPr.pagePr()
                .landscapeAnd(direction)
                .widthAnd((int) layout.pageWidth())
                .heightAnd((int) layout.pageHeight())
                .gutterType(GutterMethod.LEFT_ONLY);

        // 마진
        int mTop = layout.marginTop() > 0 ? (int) layout.marginTop() : 1417;
        int mBottom = layout.marginBottom() > 0 ? (int) layout.marginBottom() : 1417;
        int mLeft = layout.marginLeft() > 0 ? (int) layout.marginLeft() : 1417;
        int mRight = layout.marginRight() > 0 ? (int) layout.marginRight() : 1417;
        int headerFooter = 1417;

        secPr.pagePr().createMargin();
        secPr.pagePr().margin()
                .headerAnd(headerFooter).footerAnd(headerFooter).gutterAnd(0)
                .leftAnd(mLeft).rightAnd(mRight)
                .topAnd(mTop).bottom(mBottom);

        // FootNotePr
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

        // EndNotePr
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

        // PageBorderFills
        addPageBorderFills(secPr);

        // ColPr (다단 설정)
        int colCount = layout.columnCount() > 0 ? layout.columnCount() : 1;
        int colGutter = (int) layout.columnGutter();
        Ctrl ctrl = run.addNewCtrl();
        ctrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(colCount).sameSzAnd(true).sameGap(colGutter);

        // 페이지 번호
        Ctrl pageNumCtrl = run.addNewCtrl();
        pageNumCtrl.addNewPageNum()
                .posAnd(PageNumPosition.BOTTOM_CENTER)
                .formatTypeAnd(NumberType1.DIGIT)
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

    // ── 텍스트 프레임 블록 변환 (플로팅 1x1 테이블) ──
    // 글상자(rect+drawText) 대신 1x1 테이블을 사용하여 클릭만으로 텍스트 편집 가능

    private void convertTextFrameBlock(SectionXMLFile sectionFile, ASTTextFrameBlock block) {
        Para framePara = createFloatingObjectPara(sectionFile);
        Run anchorRun = framePara.runs().iterator().next();

        long x = Math.max(0, block.x());
        long y = Math.max(0, block.y());
        long w = block.width();
        long h = block.height();

        Table table = anchorRun.addNewTable();

        // ShapeObject
        String tableId = nextShapeId();
        table.idAnd(tableId)
                .zOrderAnd(block.zOrder())
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // 테이블 속성 — 1행 1열
        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) 1)
                .colCntAnd((short) 1)
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        // ShapeSize
        table.createSZ();
        table.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — PAPER 기준 절대 좌표
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

        // InMargin
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 1행 1열 셀 생성
        Tr tr = table.addNewTr();
        Tc tc = tr.addNewTc();

        String cellBfId = createTextFrameBorderFill(block);

        tc.nameAnd("")
                .headerAnd(false)
                .hasMarginAnd(true)
                .protectAnd(false)
                .editableAnd(true)
                .dirtyAnd(false)
                .borderFillIDRefAnd(cellBfId);

        // 셀 주소
        tc.createCellAddr();
        tc.cellAddr().colAddrAnd((short) 0).rowAddrAnd((short) 0);

        // 셀 병합 (1x1이므로 span=1)
        tc.createCellSpan();
        tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);

        // 셀 크기
        tc.createCellSz();
        tc.cellSz().widthAnd(w).heightAnd(h);

        // 셀 여백 — 텍스트 프레임의 inset 값 사용
        tc.createCellMargin();
        tc.cellMargin().leftAnd(block.insetLeft())
                .rightAnd(block.insetRight())
                .topAnd(block.insetTop())
                .bottomAnd(block.insetBottom());

        // 셀 내부 SubList
        tc.createSubList();
        SubList subList = tc.subList();
        TextDirection textDir = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP);

        // 단락 추가
        for (ASTParagraph para : block.paragraphs()) {
            addParagraphToSubList(subList, para);
        }

        // 빈 텍스트 프레임 방지
        if (subList.countOfPara() == 0) {
            addEmptySubListPara(subList);
        }
    }

    /**
     * 텍스트 프레임 블록의 테두리/배경을 BorderFill로 생성.
     * 테두리가 없는 경우 NONE으로, 배경이 없는 경우 투명으로 설정.
     */
    private String createTextFrameBorderFill(ASTTextFrameBlock block) {
        String bfId = String.valueOf(borderFillIdCounter.getAndIncrement());
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

        // 테두리 — 텍스트 프레임의 strokeColor/strokeWeight 반영
        String stroke = block.strokeColor();
        boolean hasStroke = stroke != null && stroke.startsWith("#") && block.strokeWeight() > 0;

        LineType2 lineType = LineType2.NONE;
        LineWidth lineWidth = LineWidth.MM_0_1;
        String borderColor = "#000000";

        if (hasStroke) {
            lineType = LineType2.SOLID;
            lineWidth = hwpunitToLineWidth((long) Math.round(block.strokeWeight()));
            borderColor = stroke;
        }

        bf.createLeftBorder();
        bf.leftBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createRightBorder();
        bf.rightBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createTopBorder();
        bf.topBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(lineType).widthAnd(lineWidth).color(borderColor);

        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");

        // 배경 채우기
        String fill = block.fillColor();
        if (fill != null && fill.startsWith("#")) {
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush()
                    .faceColorAnd(fill)
                    .hatchColorAnd("#FF000000")
                    .alpha(0f);
        }

        return bfId;
    }

    // ── 단락 변환 (SubList 내) ──

    private void addParagraphToSubList(SubList subList, ASTParagraph astPara) {
        addParagraphToSubList(subList, astPara, 0);
    }

    private void addParagraphToSubList(SubList subList, ASTParagraph astPara, long cellHeight) {
        String paraPrId = "3";
        String styleId = "0";
        String paraCharPrId = "0";

        // 스타일 해결
        if (astPara.paragraphStyleRef() != null) {
            String ref = resolveStyleRef(astPara.paragraphStyleRef());
            String mapped = styleRegistry.getParaPrId(ref);
            if (mapped != null) paraPrId = mapped;
            String mappedStyle = styleRegistry.getStyleId(ref);
            if (mappedStyle != null) styleId = mappedStyle;
            String mappedCharPr = styleRegistry.getCharPrId(ref);
            if (mappedCharPr != null) paraCharPrId = mappedCharPr;
        }

        // 단락 속성 오버라이드가 있으면 새 ParaPr 생성
        if (hasParagraphOverrides(astPara)) {
            paraPrId = createOverrideParaPr(astPara, paraPrId);
        }

        // 셀 높이가 작으면 줄간격을 FIXED로 강제하여 한컴의 최소 행높이 확장 방지
        if (cellHeight > 0 && cellHeight < 1600) {
            paraPrId = getOrCreateTinyParaPr(cellHeight);
            paraCharPrId = getOrCreateTinyCharPr();
        }

        Para para = subList.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd(styleId)
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        // 인라인 항목 변환
        for (ASTInlineItem item : astPara.items()) {
            switch (item.itemType()) {
                case TEXT_RUN:
                    addTextRun(para, (ASTTextRun) item, paraCharPrId);
                    break;
                case INLINE_OBJECT:
                    addInlineObject(para, (ASTInlineObject) item);
                    break;
                case BREAK:
                    addBreak(para, (ASTBreak) item);
                    break;
            }
        }

        // 빈 단락이면 최소 Run 추가
        if (!para.runs().iterator().hasNext()) {
            Run run = para.addNewRun();
            run.charPrIDRef(paraCharPrId);
            run.addNewT();
        }
    }

    private boolean hasParagraphOverrides(ASTParagraph para) {
        return para.alignment() != null
                || para.firstLineIndent() != null
                || para.leftMargin() != null
                || para.rightMargin() != null
                || para.spaceBefore() != null
                || para.spaceAfter() != null
                || para.lineSpacing() != null;
    }

    private String createOverrideParaPr(ASTParagraph astPara, String baseParaPrId) {
        String newId = styleRegistry.nextParaPrId();
        ParaPr paraPr = hwpxFile.headerXMLFile().refList().paraProperties().addNew();
        paraPr.idAnd(newId)
                .tabPrIDRefAnd("0")
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        // 정렬
        HorizontalAlign2 hAlign = mapAlignment(astPara.alignment());
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
        int indent = astPara.firstLineIndent() != null ? astPara.firstLineIndent().intValue() : 0;
        int left = astPara.leftMargin() != null ? astPara.leftMargin().intValue() : 0;
        int right = astPara.rightMargin() != null ? astPara.rightMargin().intValue() : 0;
        int prev = astPara.spaceBefore() != null ? astPara.spaceBefore().intValue() : 0;
        int next = astPara.spaceAfter() != null ? astPara.spaceAfter().intValue() : 0;

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
        paraPr.createLineSpacing();
        if (astPara.lineSpacing() != null) {
            paraPr.lineSpacing()
                    .typeAnd(LineSpacingType.PERCENT)
                    .valueAnd(astPara.lineSpacing())
                    .unit(ValueUnit2.HWPUNIT);
        } else {
            paraPr.lineSpacing()
                    .typeAnd(LineSpacingType.PERCENT)
                    .valueAnd(160)
                    .unit(ValueUnit2.HWPUNIT);
        }

        return newId;
    }

    // ── 텍스트 런 변환 ──

    private void addTextRun(Para para, ASTTextRun textRun, String defaultCharPrId) {
        String text = sanitizeText(textRun.text());
        if (text == null || text.isEmpty()) return;

        String charPrId = defaultCharPrId;

        // 인라인 스타일 오버라이드
        if (hasCharacterOverrides(textRun)) {
            charPrId = createOverrideCharPr(textRun);
        } else if (textRun.characterStyleRef() != null) {
            String charRef = resolveStyleRef(textRun.characterStyleRef());
            String mapped = styleRegistry.getCharPrId(charRef);
            if (mapped != null) charPrId = mapped;
        }

        Run run = para.addNewRun();
        run.charPrIDRef(charPrId);

        // 탭/줄바꿈 문자를 적절한 HWPX 요소로 변환
        if (text.indexOf('\t') >= 0 || text.indexOf('\n') >= 0) {
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T t = run.addNewT();
            addTextWithSpecialChars(t, text);
        } else {
            run.addNewT().addText(text);
        }
    }

    /**
     * 텍스트 내의 탭(\t)과 줄바꿈(\n) 문자를 HWPX 요소로 변환하여 T에 추가.
     * \t → <tab />, \n → <lineBreak />
     */
    private void addTextWithSpecialChars(
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T t, String text) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t') {
                if (buf.length() > 0) {
                    t.addText(buf.toString());
                    buf.setLength(0);
                }
                t.addNewTab();
            } else if (c == '\n') {
                if (buf.length() > 0) {
                    t.addText(buf.toString());
                    buf.setLength(0);
                }
                t.addNewLineBreak();
            } else if (c == '\r') {
                // \r 무시 (\r\n의 경우 \n이 처리)
            } else {
                buf.append(c);
            }
        }
        if (buf.length() > 0) {
            t.addText(buf.toString());
        }
    }

    private boolean hasCharacterOverrides(ASTTextRun run) {
        return run.fontFamily() != null
                || run.fontSizeHwpunits() != null
                || run.textColor() != null
                || run.letterSpacing() != null
                || run.subscript()
                || run.superscript();
    }

    private final Map<String, String> charPrCache = new HashMap<>();

    private String charPrCacheKey(ASTTextRun textRun) {
        return (textRun.fontFamily() != null ? textRun.fontFamily() : "")
                + "|" + (textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : "")
                + "|" + (textRun.textColor() != null ? textRun.textColor() : "")
                + "|" + (textRun.fontStyle() != null ? textRun.fontStyle() : "")
                + "|" + (textRun.letterSpacing() != null ? textRun.letterSpacing() : "")
                + "|" + textRun.superscript()
                + "|" + textRun.subscript();
    }

    private String createOverrideCharPr(ASTTextRun textRun) {
        String cacheKey = charPrCacheKey(textRun);
        String cached = charPrCache.get(cacheKey);
        if (cached != null) return cached;

        String newId = styleRegistry.nextCharPrId();
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();

        int height = textRun.fontSizeHwpunits() != null ? textRun.fontSizeHwpunits() : 1000;
        String textColor = textRun.textColor() != null ? textRun.textColor() : "#000000";

        charPr.idAnd(newId)
                .heightAnd(height)
                .textColorAnd(textColor)
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");

        // Bold/Italic
        if (textRun.fontStyle() != null) {
            String style = textRun.fontStyle().toLowerCase();
            if (style.contains("bold")) charPr.createBold();
            if (style.contains("italic")) charPr.createItalic();
        }

        // 위첨자/아래첨자
        if (textRun.superscript()) {
            charPr.createSupscript();
        }
        if (textRun.subscript()) {
            charPr.createSubscript();
        }

        // 폰트 참조
        String fontId = fontRegistry.resolveFontId(textRun.fontFamily());
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        charPr.createRatio();
        charPr.ratio().set((short) 90, (short) 90, (short) 90, (short) 90, (short) 90, (short) 90, (short) 90);

        // 자간 — 전역 -10% 적용
        short baseSpacing = textRun.letterSpacing() != null ? textRun.letterSpacing() : 0;
        short spacing = (short) (baseSpacing - 10);
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

        charPrCache.put(cacheKey, newId);
        return newId;
    }

    // ── 인라인 객체 변환 ──

    private void addInlineObject(Para para, ASTInlineObject obj) {
        if (obj.kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) {
            addInlineTextFrame(para, obj);
        } else if (obj.kind() == ASTInlineObject.ObjectKind.RENDERED_GROUP) {
            // 단락 콘텐츠가 있는 그룹은 글상자로, 없으면 이미지로
            if (obj.paragraphs() != null && !obj.paragraphs().isEmpty()) {
                addInlineTextFrame(para, obj);
            } else {
                addInlineImage(para, obj);
            }
        } else if (obj.kind() == ASTInlineObject.ObjectKind.IMAGE) {
            addInlineImage(para, obj);
        }
    }

    /**
     * 인라인 텍스트 프레임 / 그룹 → hp:rect + hp:drawText (글상자, treatAsChar="1")
     */
    private void addInlineTextFrame(Para para, ASTInlineObject obj) {
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return;

        long w = obj.width() > 0 ? obj.width() : 5000;
        if (w < 142) w = 142;
        long inlineMinH = 1600; // 최소 높이 (한 줄), 내용에 맞게 자동 확장됨

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Rectangle rect = run.addNewRectangle();
        String shapeId = nextShapeId();

        // ShapeObject
        rect.idAnd(shapeId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
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
        rect.orgSz().set(w, inlineMinH);
        rect.createCurSz();
        rect.curSz().set(w, 0L); // height=0: 내용에 맞게 자동 확장
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(inlineMinH / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // LineShape — 인라인 프레임은 테두리 없음
        setupTextBoxLineShape(rect, null, 0, "Solid", 100);

        // DrawText
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);
        dt.createTextMargin();
        dt.textMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        dt.createSubList();
        SubList subList = dt.subList();
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP)
                .linkListIDRefAnd("0")
                .linkListNextIDRefAnd("0")
                .textWidthAnd(0)
                .textHeightAnd(0)
                .hasTextRefAnd(false)
                .hasNumRefAnd(false);

        // 내용 단락 (풀 버전 — 인라인 객체도 재귀 처리)
        for (ASTParagraph astPara : obj.paragraphs()) {
            addParagraphToSubList(subList, astPara);
        }

        // 인라인 테이블 → SubList 내 인라인 테이블
        if (obj.inlineTables() != null) {
            for (ASTTable astTable : obj.inlineTables()) {
                addInlineTableToSubList(subList, astTable);
            }
        }

        if (subList.countOfPara() == 0) {
            addEmptySubListPara(subList);
        }

        // Rectangle 꼭짓점
        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, inlineMinH);
        rect.createPt3();
        rect.pt3().set(0L, inlineMinH);

        // ShapeSize
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(inlineMinH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — 글자처럼 취급 (인라인)
        rect.createPos();
        rect.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(true)
                .flowWithTextAnd(true)
                .allowOverlapAnd(false)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.PARA)
                .vertAlignAnd(VertAlign.BOTTOM)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        // OutMargin
        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }

    /**
     * SubList 내에 인라인 테이블을 추가한다 (treatAsChar=true).
     */
    private void addInlineTableToSubList(SubList subList, ASTTable astTable) {
        long totalWidth = astTable.width();

        Para tablePara = subList.addNewPara();
        tablePara.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = tablePara.addNewRun();
        run.charPrIDRef("0");

        Table table = run.addNewTable();
        String tableId = nextShapeId();

        table.idAnd(tableId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) astTable.rowCount())
                .colCntAnd((short) astTable.colCount())
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        table.createSZ();
        table.sz().widthAnd(totalWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(astTable.height()).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // 인라인 위치 (글자처럼 취급)
        table.createPos();
        table.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(true)
                .flowWithTextAnd(true)
                .allowOverlapAnd(false)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.PARA)
                .vertAlignAnd(VertAlign.BOTTOM)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 행(Tr) 생성
        for (ASTTableRow astRow : astTable.rows()) {
            Tr tr = table.addNewTr();

            for (ASTTableCell astCell : astRow.cells()) {
                Tc tc = tr.addNewTc();

                String cellBorderFillId = createCellBorderFill(astCell);

                tc.nameAnd("")
                        .headerAnd(false)
                        .hasMarginAnd(true)
                        .protectAnd(false)
                        .editableAnd(true)
                        .dirtyAnd(false)
                        .borderFillIDRefAnd(cellBorderFillId);

                tc.createCellAddr();
                tc.cellAddr().colAddrAnd((short) astCell.columnIndex())
                        .rowAddrAnd((short) astCell.rowIndex());

                tc.createCellSpan();
                tc.cellSpan().colSpanAnd((short) astCell.columnSpan())
                        .rowSpanAnd((short) astCell.rowSpan());

                tc.createCellSz();
                tc.cellSz().widthAnd(astCell.width()).heightAnd(astCell.height());

                tc.createCellMargin();
                tc.cellMargin().leftAnd(astCell.marginLeft())
                        .rightAnd(astCell.marginRight())
                        .topAnd(astCell.marginTop())
                        .bottomAnd(astCell.marginBottom());

                tc.createSubList();
                SubList cellSubList = tc.subList();
                cellSubList.idAnd("")
                        .textDirectionAnd(TextDirection.HORIZONTAL)
                        .lineWrapAnd(LineWrapMethod.BREAK);

                String vAlign = astCell.verticalAlign();
                if ("CenterAlign".equals(vAlign) || "center".equals(vAlign)) {
                    cellSubList.vertAlignAnd(VerticalAlign2.CENTER);
                } else if ("BottomAlign".equals(vAlign) || "bottom".equals(vAlign)) {
                    cellSubList.vertAlignAnd(VerticalAlign2.BOTTOM);
                } else {
                    cellSubList.vertAlignAnd(VerticalAlign2.TOP);
                }

                for (ASTParagraph astPara : astCell.paragraphs()) {
                    addParagraphToSubList(cellSubList, astPara, astCell.height());
                }

                if (cellSubList.countOfPara() == 0) {
                    addEmptySubListPara(cellSubList, astCell.height());
                }
            }
        }
    }

    private void addInlineImage(Para para, ASTInlineObject obj) {
        byte[] imageData = obj.imageData();
        if (imageData == null || imageData.length == 0) return;

        String format = obj.imageFormat() != null ? obj.imageFormat() : "png";
        String itemId = ImageInserter.registerImage(hwpxFile, imageData, format);

        long displayW = obj.width() > 0 ? obj.width() : 1000;
        long displayH = obj.height() > 0 ? obj.height() : 1000;

        int pixelW = obj.pixelWidth() > 0 ? obj.pixelWidth() : 100;
        int pixelH = obj.pixelHeight() > 0 ? obj.pixelHeight() : 100;

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Picture pic = run.addNewPicture();
        String picId = nextShapeId();

        // ShapeObject (인라인)
        pic.idAnd(picId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(nextShapeId());

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
                .centerXAnd(displayW / 2).centerYAnd(displayH / 2).rotateimageAnd(true);

        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(displayW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(displayH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — 인라인 (글자처럼 취급)
        pic.createPos();
        pic.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(true)
                .flowWithTextAnd(true)
                .allowOverlapAnd(false)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.PARA)
                .vertAlignAnd(VertAlign.BOTTOM)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        // OutMargin
        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // ImageRect — 표시 영역 (HWPUNIT)
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(displayW, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(displayW, displayH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, displayH);

        // ImageClip/Dim — 소스 이미지 크기 (pixel × 75)
        long clipW = (long) pixelW * 75;
        long clipH = (long) pixelH * 75;
        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(clipW).topAnd(0L).bottomAnd(clipH);

        pic.createImgDim();
        pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

        // Image 참조
        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

        imagesConverted++;
    }

    // ── 줄바꿈 ──

    private void addBreak(Para para, ASTBreak breakItem) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addNewLineBreak();
    }

    // ── 테이블 변환 ──

    private void convertTable(SectionXMLFile sectionFile, ASTTable astTable) {
        Para framePara = createFloatingObjectPara(sectionFile);
        Run anchorRun = framePara.runs().iterator().next();

        long x = Math.max(0, astTable.x());
        long y = Math.max(0, astTable.y());
        long totalWidth = astTable.width();

        Table table = anchorRun.addNewTable();

        // ShapeObject
        String tableId = nextShapeId();
        table.idAnd(tableId)
                .zOrderAnd(astTable.zOrder())
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // 테이블 속성
        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) astTable.rowCount())
                .colCntAnd((short) astTable.colCount())
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        // ShapeSize
        table.createSZ();
        table.sz().widthAnd(totalWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(astTable.height()).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — PAPER 기준
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

        // InMargin
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 행(Tr) 생성
        for (ASTTableRow astRow : astTable.rows()) {
            Tr tr = table.addNewTr();

            for (ASTTableCell astCell : astRow.cells()) {
                Tc tc = tr.addNewTc();

                String cellBorderFillId = createCellBorderFill(astCell);

                tc.nameAnd("")
                        .headerAnd(false)
                        .hasMarginAnd(true)
                        .protectAnd(false)
                        .editableAnd(true)
                        .dirtyAnd(false)
                        .borderFillIDRefAnd(cellBorderFillId);

                // 셀 주소
                tc.createCellAddr();
                tc.cellAddr().colAddrAnd((short) astCell.columnIndex())
                        .rowAddrAnd((short) astCell.rowIndex());

                // 셀 병합
                tc.createCellSpan();
                tc.cellSpan().colSpanAnd((short) astCell.columnSpan())
                        .rowSpanAnd((short) astCell.rowSpan());

                // 셀 크기
                tc.createCellSz();
                tc.cellSz().widthAnd(astCell.width()).heightAnd(astCell.height());

                // 셀 여백
                tc.createCellMargin();
                tc.cellMargin().leftAnd(astCell.marginLeft())
                        .rightAnd(astCell.marginRight())
                        .topAnd(astCell.marginTop())
                        .bottomAnd(astCell.marginBottom());

                // 셀 내부 SubList
                tc.createSubList();
                SubList subList = tc.subList();
                subList.idAnd("")
                        .textDirectionAnd(TextDirection.HORIZONTAL)
                        .lineWrapAnd(LineWrapMethod.BREAK);

                // 수직 정렬
                String vAlign = astCell.verticalAlign();
                if ("CenterAlign".equals(vAlign) || "center".equals(vAlign)) {
                    subList.vertAlignAnd(VerticalAlign2.CENTER);
                } else if ("BottomAlign".equals(vAlign) || "bottom".equals(vAlign)) {
                    subList.vertAlignAnd(VerticalAlign2.BOTTOM);
                } else {
                    subList.vertAlignAnd(VerticalAlign2.TOP);
                }

                // 셀 내용 (단락) 추가
                for (ASTParagraph astPara : astCell.paragraphs()) {
                    addParagraphToSubList(subList, astPara, astCell.height());
                }

                // 빈 셀 방지
                if (subList.countOfPara() == 0) {
                    addEmptySubListPara(subList, astCell.height());
                }
            }
        }
    }

    private String createCellBorderFill(ASTTableCell cell) {
        String bfId = String.valueOf(borderFillIdCounter.getAndIncrement());
        BorderFill bf = hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        // 대각선
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

        // 테두리
        bf.createLeftBorder();
        applyCellBorder(bf.leftBorder(), cell.leftBorder());

        bf.createRightBorder();
        applyCellBorder(bf.rightBorder(), cell.rightBorder());

        bf.createTopBorder();
        applyCellBorder(bf.topBorder(), cell.topBorder());

        bf.createBottomBorder();
        applyCellBorder(bf.bottomBorder(), cell.bottomBorder());

        // 대각선 스타일
        bf.createDiagonal();
        if ((cell.topLeftDiagonalLine() || cell.topRightDiagonalLine()) && cell.diagonalBorder() != null) {
            ASTTableCell.CellBorder diag = cell.diagonalBorder();
            LineType2 lineType = strokeTypeToLineType(diag.strokeType());
            LineWidth lineWidth = hwpunitToLineWidth(diag.weight());
            String color = diag.color() != null ? diag.color() : "#000000";
            bf.diagonal().typeAnd(lineType).widthAnd(lineWidth).color(color);
        } else {
            bf.diagonal().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        }

        // 배경 채우기 — 실제 색상값(#으로 시작)이 있을 때만
        String cellFill = cell.fillColor();
        if (cellFill != null && cellFill.startsWith("#")) {
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush()
                    .faceColorAnd(cellFill)
                    .hatchColorAnd("#FF000000")
                    .alpha(0f);
        }

        return bfId;
    }

    private void applyCellBorder(Border hwpxBorder, ASTTableCell.CellBorder cellBorder) {
        if (cellBorder == null || cellBorder.weight() <= 0) {
            hwpxBorder.typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
            return;
        }
        LineType2 lineType = strokeTypeToLineType(cellBorder.strokeType());
        hwpxBorder.typeAnd(lineType);
        LineWidth lineWidth = hwpunitToLineWidth(cellBorder.weight());
        hwpxBorder.widthAnd(lineWidth);
        String color = cellBorder.color();
        if (color == null || color.isEmpty() || !color.startsWith("#")) color = "#000000";
        hwpxBorder.color(color);
    }

    // ── 이미지/도형 변환 (floating Picture) ──

    private void convertFigure(SectionXMLFile sectionFile, ASTFigure figure) {
        if (figure.kind() == ASTFigure.FigureKind.IMAGE) {
            convertFigureImage(sectionFile, figure);
        } else {
            // RENDERED_SHAPE, RENDERED_GROUP → PNG 이미지로 처리
            convertFigureImage(sectionFile, figure);
        }
    }

    private void convertFigureImage(SectionXMLFile sectionFile, ASTFigure figure) {
        byte[] imageData = figure.imageData();
        if (imageData == null || imageData.length == 0) return;

        String format = figure.imageFormat() != null ? figure.imageFormat() : "png";
        String itemId = ImageInserter.registerImage(hwpxFile, imageData, format);

        long x = Math.max(0, figure.x());
        long y = Math.max(0, figure.y());
        long displayW = figure.width();
        long displayH = figure.height();
        int pixelW = figure.pixelWidth() > 0 ? figure.pixelWidth() : 100;
        int pixelH = figure.pixelHeight() > 0 ? figure.pixelHeight() : 100;

        Para framePara = createFloatingObjectPara(sectionFile);
        Run anchorRun = framePara.runs().iterator().next();

        Picture pic = anchorRun.addNewPicture();
        String picId = nextShapeId();

        // ShapeObject
        pic.idAnd(picId)
                .zOrderAnd(figure.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.BEHIND_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(nextShapeId());

        pic.createOffset();
        pic.offset().set(0L, 0L);

        pic.createOrgSz();
        pic.orgSz().set(displayW, displayH);

        pic.createCurSz();
        pic.curSz().set(displayW, displayH);

        pic.createFlip();
        pic.flip().horizontalAnd(false).verticalAnd(false);

        pic.createRotationInfo();
        short rotAngle = (short) Math.round(figure.rotationAngle());
        pic.rotationInfo().angleAnd(rotAngle)
                .centerXAnd(displayW / 2).centerYAnd(displayH / 2).rotateimageAnd(true);

        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        if (rotAngle != 0) {
            double radians = Math.toRadians(rotAngle);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            pic.renderingInfo().addNewRotMatrix().set(cos, -sin, 0f, sin, cos, 0f);
        } else {
            pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        }

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(displayW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(displayH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — PAPER 기준 절대 좌표
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
                .vertOffsetAnd(y)
                .horzOffset(x);

        // OutMargin
        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // ImageRect — 표시 영역 (HWPUNIT)
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(displayW, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(displayW, displayH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, displayH);

        // ImageClip/Dim — 소스 이미지 크기 (pixel × 75)
        long clipW = (long) pixelW * 75;
        long clipH = (long) pixelH * 75;
        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(clipW).topAnd(0L).bottomAnd(clipH);

        pic.createImgDim();
        pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

        // Image 참조
        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

        imagesConverted++;
    }

    // ── 배경 PNG ──

    private void addBackgroundImage(SectionXMLFile sectionFile, ASTPageBackground bg) {
        byte[] pngData = bg.pngData();
        if (pngData == null || pngData.length == 0) return;

        String itemId = ImageInserter.registerImage(hwpxFile, pngData, "png");

        long w = bg.pageWidth();
        long h = bg.pageHeight();

        Para framePara = createFloatingObjectPara(sectionFile);
        Run anchorRun = framePara.runs().iterator().next();

        Picture pic = anchorRun.addNewPicture();
        String picId = nextShapeId();

        // ShapeObject — 배경: z-order=0, BEHIND_TEXT
        pic.idAnd(picId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.BEHIND_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(nextShapeId());

        pic.createOffset();
        pic.offset().set(0L, 0L);

        pic.createOrgSz();
        pic.orgSz().set(w, h);

        pic.createCurSz();
        pic.curSz().set(w, h);

        pic.createFlip();
        pic.flip().horizontalAnd(false).verticalAnd(false);

        pic.createRotationInfo();
        pic.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);

        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — (0,0) from PAPER
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
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        pic.createOutMargin();
        pic.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(w, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(w, h);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, h);

        // ImageClip/Dim — 소스 이미지 크기 (pixel × 75)
        int bgPixelW = bg.pixelWidth() > 0 ? bg.pixelWidth() : 100;
        int bgPixelH = bg.pixelHeight() > 0 ? bg.pixelHeight() : 100;
        long bgClipW = (long) bgPixelW * 75;
        long bgClipH = (long) bgPixelH * 75;
        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(bgClipW).topAnd(0L).bottomAnd(bgClipH);

        pic.createImgDim();
        pic.imgDim().dimwidthAnd(bgClipW).dimheightAnd(bgClipH);

        pic.createImg();
        pic.img().binaryItemIDRefAnd(itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);
    }

    // ── 유틸리티 ──

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

    private void addEmptyPara(SectionXMLFile section) {
        Para emptyPara = section.addNewPara();
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

    private void addEmptySubListPara(SubList subList) {
        addEmptySubListPara(subList, 0);
    }

    private void addEmptySubListPara(SubList subList, long cellHeight) {
        // 셀 높이가 기본 폰트 줄 높이(약 1600 hwpunit)보다 작으면
        // 전용 charPr(1pt) + paraPr(FIXED 줄간격)을 사용하여
        // 한컴이 최소 행 높이로 셀을 늘리는 것을 방지
        String paraPrId = "3";
        String charPrId = "0";

        if (cellHeight > 0 && cellHeight < 1600) {
            charPrId = getOrCreateTinyCharPr();
            paraPrId = getOrCreateTinyParaPr(cellHeight);
        }

        Para emptyPara = subList.addNewPara();
        emptyPara.idAnd(nextParaId())
                .paraPrIDRefAnd(paraPrId)
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run run = emptyPara.addNewRun();
        run.charPrIDRef(charPrId);
        run.addNewT();
    }

    private String tinyCharPrId;

    private String getOrCreateTinyCharPr() {
        if (tinyCharPrId != null) return tinyCharPrId;
        tinyCharPrId = styleRegistry.nextCharPrId();
        CharPr charPr = hwpxFile.headerXMLFile().refList().charProperties().addNew();
        charPr.idAnd(tinyCharPrId)
                .heightAnd(100)  // 1pt
                .textColorAnd("#000000")
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");
        return tinyCharPrId;
    }

    private final Map<Long, String> tinyParaPrCache = new HashMap<>();

    private String getOrCreateTinyParaPr(long cellHeight) {
        String cached = tinyParaPrCache.get(cellHeight);
        if (cached != null) return cached;

        String newId = styleRegistry.nextParaPrId();
        ParaPr paraPr = hwpxFile.headerXMLFile().refList().paraProperties().addNew();
        paraPr.idAnd(newId);
        paraPr.createLineSpacing();
        paraPr.lineSpacing()
                .typeAnd(LineSpacingType.FIXED)
                .valueAnd((int) cellHeight)
                .unitAnd(ValueUnit2.HWPUNIT);
        paraPr.createMargin();
        paraPr.margin().createIntent();
        paraPr.margin().intent().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createLeft();
        paraPr.margin().left().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createRight();
        paraPr.margin().right().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createPrev();
        paraPr.margin().prev().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createNext();
        paraPr.margin().next().valueAnd(0).unit(ValueUnit2.HWPUNIT);

        tinyParaPrCache.put(cellHeight, newId);
        return newId;
    }

    private void addLineSegArray(Para para) {
        para.createLineSegArray();
        LineSeg lineSeg = para.lineSegArray().addNew();
        lineSeg.textposAnd(0).vertposAnd(0).vertsizeAnd(1000)
                .textheightAnd(1000).baselineAnd(850).spacingAnd(600)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
    }



    private static String nextParaId() {
        return String.valueOf(PARA_ID_COUNTER.incrementAndGet());
    }

    private static String nextShapeId() {
        return String.valueOf(SHAPE_ID_COUNTER.incrementAndGet());
    }

    /**
     * AST 단락의 스타일 참조를 StyleRegistry 키로 변환.
     * AST에서는 "01_발문" 형태이고, StyleRegistry는 "ParagraphStyle/01_발문"으로 등록됨.
     */
    private String resolveStyleRef(String ref) {
        if (ref == null) return null;
        // 그대로 찾아보기
        if (styleRegistry.getParaPrId(ref) != null) return ref;
        // ParagraphStyle/ 접두어 붙여서 찾기
        String withPrefix = "ParagraphStyle/" + ref;
        if (styleRegistry.getParaPrId(withPrefix) != null) return withPrefix;
        // CharacterStyle/ 접두어 붙여서 찾기
        String withCharPrefix = "CharacterStyle/" + ref;
        if (styleRegistry.getCharPrId(withCharPrefix) != null) return withCharPrefix;
        return ref;
    }

    private String sanitizeText(String text) {
        if (text == null) return null;
        // 제어 문자 제거 (탭, 줄바꿈은 유지)
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || c >= ' ') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private HorizontalAlign2 mapAlignment(String alignment) {
        if (alignment == null) return HorizontalAlign2.JUSTIFY;
        switch (alignment.toLowerCase()) {
            case "left": case "leftjustify": return HorizontalAlign2.LEFT;
            case "center": case "centerjustify": return HorizontalAlign2.CENTER;
            case "right": case "rightjustify": return HorizontalAlign2.RIGHT;
            case "justify": case "fulljustify": case "leftjustified": return HorizontalAlign2.JUSTIFY;
            default: return HorizontalAlign2.JUSTIFY;
        }
    }

    private LineWidth hwpunitToLineWidth(double hwpunit) {
        double mm = hwpunit * 25.4 / 7200.0;
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
}
