package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;

/**
 * HWPX 구조 문제를 binary search로 찾기 위한 점진적 테스트.
 *
 * 사용법: java TestIncremental <level> <output-path>
 *
 * level 0: 빈 문서 (BlankFileMaker만)
 * level 1: SecPr만 교체 (커스텀 페이지 크기)
 * level 2: 단순 텍스트 rect 1개 추가
 * level 3: 텍스트 rect 1개 + 추가 charPr/paraPr
 * level 4: 테이블 1개 추가
 * level 5: 여러 섹션 (4개 SecPr)
 */
public class TestIncremental {

    private static long paraIdCounter = 2000000000L;
    private static long shapeIdCounter = 8000000L;

    public static void main(String[] args) throws Exception {
        int level = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        String path = args.length > 1 ? args[1] : "/tmp/test_level_" + level + ".hwpx";

        HWPXFile hwpx = BlankFileMaker.make();
        SectionXMLFile section0 = hwpx.sectionXMLFileList().get(0);

        switch (level) {
            case 0:
                // 빈 문서 그대로
                break;
            case 1:
                // 기존 para 제거 후 커스텀 SecPr만
                section0.removeAllParas();
                addSectionBreakPara(section0, 63780, 85039);
                break;
            case 2:
                // SecPr + 단순 rect 1개
                section0.removeAllParas();
                addSectionBreakPara(section0, 63780, 85039);
                addSimpleRect(section0, 5669, 7512, 10000, 5000, "Hello World");
                break;
            case 3:
                // SecPr + 추가 charPr/paraPr + rect
                section0.removeAllParas();
                addCustomCharPr(hwpx, "10", 1200, "#FF0000");
                addCustomParaPr(hwpx, "10");
                addSectionBreakPara(section0, 63780, 85039);
                addSimpleRect(section0, 5669, 7512, 10000, 5000, "Styled text");
                break;
            case 4:
                // SecPr + rect + table
                section0.removeAllParas();
                addSectionBreakPara(section0, 63780, 85039);
                addSimpleRect(section0, 5669, 7512, 10000, 3000, "Text frame");
                addSimpleTable(section0, 5669, 15000, 40000, 5000);
                break;
            case 5:
                // 여러 섹션 (4개 SecPr)
                section0.removeAllParas();
                for (int i = 0; i < 4; i++) {
                    addSectionBreakPara(section0, 63780, 85039);
                    addSimpleRect(section0, 5669, 7512, 10000, 3000, "Section " + (i + 1));
                }
                hwpx.headerXMLFile().secCnt((short) 1);
                break;
        }

        HWPXWriter.toFilepath(hwpx, path);
        System.out.println("Level " + level + " written to " + path);
    }

    private static void addSectionBreakPara(SectionXMLFile section, int pageWidth, int pageHeight) {
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

        secPr.createPagePr();
        secPr.pagePr()
                .landscapeAnd(PageDirection.WIDELY)
                .widthAnd(pageWidth)
                .heightAnd(pageHeight)
                .gutterType(GutterMethod.LEFT_ONLY);

        secPr.pagePr().createMargin();
        secPr.pagePr().margin()
                .headerAnd(1417).footerAnd(1417).gutterAnd(0)
                .leftAnd(5669).rightAnd(5669)
                .topAnd(7512).bottom(5102);

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

        addPageBorderFill(secPr, ApplyPageType.BOTH);
        addPageBorderFill(secPr, ApplyPageType.EVEN);
        addPageBorderFill(secPr, ApplyPageType.ODD);

        Ctrl ctrl = run.addNewCtrl();
        ctrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(1).sameSzAnd(true).sameGap(0);

        run.addNewT();

        para.createLineSegArray();
        LineSeg lineSeg = para.lineSegArray().addNew();
        lineSeg.textposAnd(0).vertposAnd(0).vertsizeAnd(1000)
                .textheightAnd(1000).baselineAnd(850).spacingAnd(600)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
    }

    private static void addPageBorderFill(SecPr secPr, ApplyPageType type) {
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

    private static void addSimpleRect(SectionXMLFile section, long x, long y, long w, long h, String text) {
        Para framePara = section.addNewPara();
        framePara.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = nextShapeId();

        rect.idAnd(shapeId)
                .zOrderAnd(1)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

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
        rect.rotationInfo().angleAnd((short) 0).centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(true);

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

        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        rect.createLineShape();
        rect.lineShape().colorAnd("#000000")
                .widthAnd(0).styleAnd(LineType2.NONE).endCapAnd(LineCap.FLAT)
                .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                .headfillAnd(true).tailfillAnd(true)
                .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM);

        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false).lineShapeFixedAnd(true);
        dt.createTextMargin();
        dt.textMargin().leftAnd(283L).rightAnd(283L).topAnd(283L).bottomAnd(283L);
        dt.createSubList();
        SubList subList = dt.subList();
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP);

        Para textPara = subList.addNewPara();
        textPara.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run textRun = textPara.addNewRun();
        textRun.charPrIDRef("0");
        textRun.addNewT().addText(text);

        // Anchor para에 minimal LineSegArray 추가
        framePara.createLineSegArray();
        LineSeg ls = framePara.lineSegArray().addNew();
        ls.textposAnd(0).vertposAnd(0).vertsizeAnd(1)
                .textheightAnd(1).baselineAnd(0).spacingAnd(0)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
    }

    private static void addSimpleTable(SectionXMLFile section, long x, long y, long w, long h) {
        Para framePara = section.addNewPara();
        framePara.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table table = anchorRun.addNewTable();
        String tableId = nextShapeId();

        table.idAnd(tableId)
                .zOrderAnd(1)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) 2)
                .colCntAnd((short) 2)
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        table.createSZ();
        table.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(true);

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

        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        long cellW = w / 2;
        long cellH = h / 2;

        for (int r = 0; r < 2; r++) {
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr tr = table.addNewTr();
            for (int c = 0; c < 2; c++) {
                kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc tc = tr.addNewTc();
                tc.nameAnd("")
                        .headerAnd(false)
                        .hasMarginAnd(true)
                        .protectAnd(false)
                        .editableAnd(false)
                        .dirtyAnd(false)
                        .borderFillIDRefAnd("1");

                tc.createCellAddr();
                tc.cellAddr().colAddrAnd((short) c).rowAddrAnd((short) r);
                tc.createCellSpan();
                tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);
                tc.createCellSz();
                tc.cellSz().widthAnd(cellW).heightAnd(cellH);
                tc.createCellMargin();
                tc.cellMargin().leftAnd(283L).rightAnd(283L).topAnd(283L).bottomAnd(283L);

                tc.createSubList();
                SubList sl = tc.subList();
                sl.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                        .lineWrapAnd(LineWrapMethod.BREAK)
                        .vertAlignAnd(VerticalAlign2.TOP);

                Para cellPara = sl.addNewPara();
                cellPara.idAnd(nextParaId())
                        .paraPrIDRefAnd("3")
                        .styleIDRefAnd("0")
                        .pageBreakAnd(false)
                        .columnBreakAnd(false)
                        .merged(false);
                Run cellRun = cellPara.addNewRun();
                cellRun.charPrIDRef("0");
                cellRun.addNewT().addText("Cell " + r + "," + c);
            }
        }

        framePara.createLineSegArray();
        LineSeg ls = framePara.lineSegArray().addNew();
        ls.textposAnd(0).vertposAnd(0).vertsizeAnd(1)
                .textheightAnd(1).baselineAnd(0).spacingAnd(0)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
    }

    private static void addCustomCharPr(HWPXFile hwpx, String id, int height, String color) {
        CharPr charPr = hwpx.headerXMLFile().refList().charProperties().addNew();
        charPr.idAnd(id)
                .heightAnd(height)
                .textColorAnd(color)
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");

        charPr.createFontRef();
        charPr.fontRef().set("0", "0", "0", "0", "0", "0", "0");
        charPr.createRatio();
        charPr.ratio().set((short)100, (short)100, (short)100, (short)100, (short)100, (short)100, (short)100);
        charPr.createSpacing();
        charPr.spacing().set((short)0, (short)0, (short)0, (short)0, (short)0, (short)0, (short)0);
        charPr.createRelSz();
        charPr.relSz().set((short)100, (short)100, (short)100, (short)100, (short)100, (short)100, (short)100);
        charPr.createOffset();
        charPr.offset().set((short)0, (short)0, (short)0, (short)0, (short)0, (short)0, (short)0);
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

    private static void addCustomParaPr(HWPXFile hwpx, String id) {
        ParaPr paraPr = hwpx.headerXMLFile().refList().paraProperties().addNew();
        paraPr.idAnd(id)
                .tabPrIDRefAnd("0")
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        paraPr.createAlign();
        paraPr.align().horizontalAnd(HorizontalAlign2.LEFT).vertical(VerticalAlign1.BASELINE);
        paraPr.createHeading();
        paraPr.heading().typeAnd(ParaHeadingType.NONE).idRefAnd("0").level((byte) 0);
        paraPr.createBreakSetting();
        paraPr.breakSetting()
                .breakLatinWordAnd(LineBreakForLatin.KEEP_WORD)
                .breakNonLatinWordAnd(LineBreakForNonLatin.KEEP_WORD)
                .widowOrphanAnd(false).keepWithNextAnd(false).keepLinesAnd(false)
                .pageBreakBeforeAnd(false).lineWrap(LineWrap.BREAK);
        paraPr.createAutoSpacing();
        paraPr.autoSpacing().eAsianEngAnd(false).eAsianNum(false);
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
        paraPr.createLineSpacing();
        paraPr.lineSpacing().typeAnd(LineSpacingType.PERCENT).valueAnd(160).unit(ValueUnit2.HWPUNIT);
    }

    private static String nextParaId() {
        return String.valueOf(++paraIdCounter);
    }

    private static String nextShapeId() {
        return String.valueOf(++shapeIdCounter);
    }
}
