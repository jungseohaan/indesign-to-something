package kr.dogfoot.hwpxlib.tool.table;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.FillBrush;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

/**
 * 100가지 다양한 테이블 샘플을 생성하는 테스트.
 *
 * 카테고리:
 *   1~10: 기본 테이블 (크기, 행/열 수 변화)
 *  11~20: 셀 병합 (가로, 세로, 복합)
 *  21~30: 테두리 스타일 (선 종류, 두께, 색상)
 *  31~40: 셀 배경색
 *  41~50: 테마 테이블 (비즈니스, 학술 등)
 *  51~60: 그라데이션 배경
 *  61~70: 대각선/빗금 셀
 *  71~80: 중첩 테이블 (table in table)
 *  81~90: 복합 스타일
 *  91~100: 특수 레이아웃
 */
public class SampleTableShowcaseWriter {

    private static final long TABLE_WIDTH = 42520L;
    private static final long CELL_MARGIN_LR = 510L;
    private static final long CELL_MARGIN_TB = 141L;

    private int bfIdCounter = 3; // 기존 BorderFill ID 1,2 사용 중
    private int idCounter = 0;

    @Test
    public void createTableShowcase() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);
        ObjectList<BorderFill> borderFills = hwpxFile.headerXMLFile().refList().borderFills();

        for (int i = 0; i < 100; i++) {
            boolean pageBreak = (i > 0);
            Para para = addTitlePara(section, (i + 1) + ". " + getTableTitle(i), pageBreak);

            Para tablePara = section.addNewPara();
            tablePara.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                    .pageBreakAnd(false).columnBreakAnd(false).merged(false);
            Run tableRun = tablePara.addNewRun();
            tableRun.charPrIDRef("0");

            Table table = tableRun.addNewTable();
            buildTable(i, table, borderFills);

            tableRun.addNewT().addText("");
        }

        String filepath = "testFile/tool/sample_table_showcase.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Table Showcase Result ===");
        System.out.println("Tables: 100");
        System.out.println("File: " + filepath);

        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        Assert.assertTrue(readBack.sectionXMLFileList().get(0).countOfPara() > 0);
        System.out.println("Round-trip verification passed!");
    }

    // ── 테이블 제목 ──

    private String getTableTitle(int idx) {
        String[] titles = {
            // 1~10: 기본
            "Basic 2x2", "Basic 3x3", "Basic 4x4", "Basic 5x3", "Basic 2x5",
            "Narrow Columns", "Wide Columns", "Tall Rows", "Single Row", "Single Column",
            // 11~20: 셀 병합
            "Horizontal Merge (top)", "Vertical Merge (left)", "L-Shape Merge",
            "Center 2x2 Merge", "Full Top Merge", "Full Left Merge",
            "Staircase Merge", "Cross Merge", "Corner Merge", "Checkerboard Merge",
            // 21~30: 테두리
            "Solid Border", "Dash Border", "Dot Border", "Double Border",
            "Thick Border", "Colored Border (Blue)", "Colored Border (Red)",
            "Mixed Border Styles", "No Inner Border", "No Outer Border",
            // 31~40: 배경색
            "Blue Header", "Green Header", "Red Header", "Yellow Cells",
            "Alternating Row Colors", "Alternating Column Colors",
            "Gradient Blue Cells", "Pastel Rainbow", "Dark Theme", "Warm Theme",
            // 41~50: 테마
            "Corporate Blue", "Academic Gray", "Nature Green", "Sunset Orange",
            "Ocean Theme", "Lavender Theme", "Monochrome", "High Contrast",
            "Minimal Lines", "Classic Grid",
            // 51~60: 그라데이션
            "Linear Gradient (H)", "Linear Gradient (V)", "Radial Gradient",
            "Blue-White Gradient", "Green-Yellow Gradient", "Red-Orange Gradient",
            "Purple Gradient", "Gray Gradient", "Warm Gradient", "Cool Gradient",
            // 61~70: 대각선/빗금
            "Diagonal Cells", "BackSlash Cells", "Hatch Horizontal",
            "Hatch Vertical", "Hatch Cross", "Hatch Slash",
            "Diagonal Header", "Mixed Diagonal", "Striped Cells", "Pattern Fill",
            // 71~80: 중첩 테이블
            "Nested 2x2 in Cell", "Nested 3x2 in Cell", "Double Nested",
            "Nested with Merge", "Nested with Colors", "Nested with Border",
            "Nested in Each Cell", "Nested Asymmetric", "Deep Nested", "Nested Grid",
            // 81~90: 복합
            "Merge + Color", "Merge + Border", "Color + Border",
            "Merge + Gradient", "Full Style Mix", "Invoice Layout",
            "Calendar Layout", "Report Header", "Data Grid", "Summary Table",
            // 91~100: 특수
            "1x1 Single Cell", "10x1 Long Row", "1x10 Long Column",
            "Uneven Columns", "Uneven Rows", "Wide Margin Table",
            "Zero Margin Table", "Header Row Repeat", "Large 6x8",
            "Showcase Finale"
        };
        return titles[idx];
    }

    // ── 테이블 빌드 디스패치 ──

    private void buildTable(int idx, Table table, ObjectList<BorderFill> bfs) {
        switch (idx) {
            // 기본 테이블 (1~10)
            case 0: buildBasicTable(table, bfs, 2, 2); break;
            case 1: buildBasicTable(table, bfs, 3, 3); break;
            case 2: buildBasicTable(table, bfs, 4, 4); break;
            case 3: buildBasicTable(table, bfs, 5, 3); break;
            case 4: buildBasicTable(table, bfs, 2, 5); break;
            case 5: buildNarrowColTable(table, bfs); break;
            case 6: buildWideColTable(table, bfs); break;
            case 7: buildTallRowTable(table, bfs); break;
            case 8: buildBasicTable(table, bfs, 1, 5); break;
            case 9: buildBasicTable(table, bfs, 5, 1); break;
            // 셀 병합 (11~20)
            case 10: buildHMergeTop(table, bfs); break;
            case 11: buildVMergeLeft(table, bfs); break;
            case 12: buildLShapeMerge(table, bfs); break;
            case 13: buildCenterMerge(table, bfs); break;
            case 14: buildFullTopMerge(table, bfs); break;
            case 15: buildFullLeftMerge(table, bfs); break;
            case 16: buildStaircaseMerge(table, bfs); break;
            case 17: buildCrossMerge(table, bfs); break;
            case 18: buildCornerMerge(table, bfs); break;
            case 19: buildCheckerboardMerge(table, bfs); break;
            // 테두리 (21~30)
            case 20: buildBorderTable(table, bfs, LineType2.SOLID, LineWidth.MM_0_5, "#000000"); break;
            case 21: buildBorderTable(table, bfs, LineType2.DASH, LineWidth.MM_0_4, "#000000"); break;
            case 22: buildBorderTable(table, bfs, LineType2.DOT, LineWidth.MM_0_3, "#000000"); break;
            case 23: buildBorderTable(table, bfs, LineType2.DOUBLE_SLIM, LineWidth.MM_0_4, "#000000"); break;
            case 24: buildBorderTable(table, bfs, LineType2.SOLID, LineWidth.MM_2_0, "#000000"); break;
            case 25: buildBorderTable(table, bfs, LineType2.SOLID, LineWidth.MM_0_5, "#2E74B5"); break;
            case 26: buildBorderTable(table, bfs, LineType2.SOLID, LineWidth.MM_0_5, "#C00000"); break;
            case 27: buildMixedBorderTable(table, bfs); break;
            case 28: buildNoInnerBorderTable(table, bfs); break;
            case 29: buildNoOuterBorderTable(table, bfs); break;
            // 배경색 (31~40)
            case 30: buildHeaderColorTable(table, bfs, "#DEEAF6", "#2E74B5"); break;
            case 31: buildHeaderColorTable(table, bfs, "#E2EFDA", "#548235"); break;
            case 32: buildHeaderColorTable(table, bfs, "#FCE4EC", "#C00000"); break;
            case 33: buildYellowCellsTable(table, bfs); break;
            case 34: buildAlternatingRowTable(table, bfs); break;
            case 35: buildAlternatingColTable(table, bfs); break;
            case 36: buildGradientBlueCells(table, bfs); break;
            case 37: buildPastelRainbow(table, bfs); break;
            case 38: buildDarkTheme(table, bfs); break;
            case 39: buildWarmTheme(table, bfs); break;
            // 테마 (41~50)
            case 40: buildCorporateBlue(table, bfs); break;
            case 41: buildAcademicGray(table, bfs); break;
            case 42: buildNatureGreen(table, bfs); break;
            case 43: buildSunsetOrange(table, bfs); break;
            case 44: buildOceanTheme(table, bfs); break;
            case 45: buildLavenderTheme(table, bfs); break;
            case 46: buildMonochrome(table, bfs); break;
            case 47: buildHighContrast(table, bfs); break;
            case 48: buildMinimalLines(table, bfs); break;
            case 49: buildClassicGrid(table, bfs); break;
            // 그라데이션 (51~60)
            case 50: buildGradientTable(table, bfs, GradationType.LINEAR, 0, "#4472C4", "#FFFFFF"); break;
            case 51: buildGradientTable(table, bfs, GradationType.LINEAR, 90, "#4472C4", "#FFFFFF"); break;
            case 52: buildGradientTable(table, bfs, GradationType.RADIAL, 0, "#4472C4", "#FFFFFF"); break;
            case 53: buildGradientTable(table, bfs, GradationType.LINEAR, 0, "#2E74B5", "#FFFFFF"); break;
            case 54: buildGradientTable(table, bfs, GradationType.LINEAR, 90, "#548235", "#FFF2CC"); break;
            case 55: buildGradientTable(table, bfs, GradationType.LINEAR, 45, "#C00000", "#FF6600"); break;
            case 56: buildGradientTable(table, bfs, GradationType.RADIAL, 0, "#7030A0", "#E6CCFF"); break;
            case 57: buildGradientTable(table, bfs, GradationType.LINEAR, 0, "#808080", "#F2F2F2"); break;
            case 58: buildGradientTable(table, bfs, GradationType.LINEAR, 135, "#FF6600", "#FFF2CC"); break;
            case 59: buildGradientTable(table, bfs, GradationType.LINEAR, 45, "#2E74B5", "#E2EFDA"); break;
            // 대각선/빗금 (61~70)
            case 60: buildDiagonalCells(table, bfs, true); break;
            case 61: buildDiagonalCells(table, bfs, false); break;
            case 62: buildHatchTable(table, bfs, HatchStyle.HORIZONTAL); break;
            case 63: buildHatchTable(table, bfs, HatchStyle.VERTICAL); break;
            case 64: buildHatchTable(table, bfs, HatchStyle.CROSS); break;
            case 65: buildHatchTable(table, bfs, HatchStyle.SLASH); break;
            case 66: buildDiagonalHeaderTable(table, bfs); break;
            case 67: buildMixedDiagonalTable(table, bfs); break;
            case 68: buildStripedCells(table, bfs); break;
            case 69: buildPatternFill(table, bfs); break;
            // 중첩 테이블 (71~80)
            case 70: buildNestedTable(table, bfs, 2, 2); break;
            case 71: buildNestedTable(table, bfs, 3, 2); break;
            case 72: buildDoubleNested(table, bfs); break;
            case 73: buildNestedWithMerge(table, bfs); break;
            case 74: buildNestedWithColors(table, bfs); break;
            case 75: buildNestedWithBorder(table, bfs); break;
            case 76: buildNestedInEachCell(table, bfs); break;
            case 77: buildNestedAsymmetric(table, bfs); break;
            case 78: buildDeepNested(table, bfs); break;
            case 79: buildNestedGrid(table, bfs); break;
            // 복합 (81~90)
            case 80: buildMergeAndColor(table, bfs); break;
            case 81: buildMergeAndBorder(table, bfs); break;
            case 82: buildColorAndBorder(table, bfs); break;
            case 83: buildMergeAndGradient(table, bfs); break;
            case 84: buildFullStyleMix(table, bfs); break;
            case 85: buildInvoiceLayout(table, bfs); break;
            case 86: buildCalendarLayout(table, bfs); break;
            case 87: buildReportHeader(table, bfs); break;
            case 88: buildDataGrid(table, bfs); break;
            case 89: buildSummaryTable(table, bfs); break;
            // 특수 (91~100)
            case 90: buildBasicTable(table, bfs, 1, 1); break;
            case 91: buildBasicTable(table, bfs, 1, 10); break;
            case 92: buildBasicTable(table, bfs, 10, 1); break;
            case 93: buildUnevenColumns(table, bfs); break;
            case 94: buildUnevenRows(table, bfs); break;
            case 95: buildWideMarginTable(table, bfs); break;
            case 96: buildZeroMarginTable(table, bfs); break;
            case 97: buildHeaderRepeatTable(table, bfs); break;
            case 98: buildLargeTable(table, bfs); break;
            case 99: buildFinaleTable(table, bfs); break;
        }
    }

    // ══════════════════════════════════════════
    //  유틸리티 메서드
    // ══════════════════════════════════════════

    private String nextId() {
        return String.valueOf(1000000 + (idCounter++));
    }

    private String nextBfId() {
        return String.valueOf(bfIdCounter++);
    }

    private Para addTitlePara(SectionXMLFile section, String title, boolean pageBreak) {
        Para p = section.addNewPara();
        p.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(pageBreak).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");
        r.addNewT().addText(title);
        return p;
    }

    /** 기본 BorderFill: 4면 solid 검정 */
    private String makeSolidBorderFill(ObjectList<BorderFill> bfs,
                                       LineType2 type, LineWidth width, String color) {
        String id = nextBfId();
        BorderFill bf = bfs.addNew();
        bf.idAnd(id).threeDAnd(false).shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE).breakCellSeparateLine(false);
        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        setBorder(bf, type, width, color);
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_1).color("#000000");
        return id;
    }

    /** 배경색 + 테두리 BorderFill */
    private String makeBfWithBg(ObjectList<BorderFill> bfs, String bgColor,
                                LineType2 type, LineWidth width, String borderColor) {
        String id = nextBfId();
        BorderFill bf = bfs.addNew();
        bf.idAnd(id).threeDAnd(false).shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE).breakCellSeparateLine(false);
        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        setBorder(bf, type, width, borderColor);
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_1).color("#000000");
        if (bgColor != null) {
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush().faceColorAnd(bgColor).hatchColorAnd("#FF000000").alpha(0f);
        }
        return id;
    }

    /** 대각선 BorderFill */
    private String makeDiagonalBf(ObjectList<BorderFill> bfs, boolean slash,
                                   LineType2 type, LineWidth width, String color) {
        String id = nextBfId();
        BorderFill bf = bfs.addNew();
        bf.idAnd(id).threeDAnd(false).shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE).breakCellSeparateLine(false);
        bf.createSlash();
        bf.createBackSlash();
        if (slash) {
            bf.slash().typeAnd(SlashType.CENTER).CrookedAnd(false).isCounter(false);
            bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        } else {
            bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
            bf.backSlash().typeAnd(SlashType.CENTER).CrookedAnd(false).isCounter(false);
        }
        setBorder(bf, type, width, color);
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_12).color(color);
        return id;
    }

    /** 빗금(Hatch) 패턴 BorderFill */
    private String makeHatchBf(ObjectList<BorderFill> bfs, HatchStyle style,
                                String faceColor, String hatchColor) {
        String id = nextBfId();
        BorderFill bf = bfs.addNew();
        bf.idAnd(id).threeDAnd(false).shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE).breakCellSeparateLine(false);
        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        setBorder(bf, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createFillBrush();
        bf.fillBrush().createWinBrush();
        bf.fillBrush().winBrush()
                .faceColorAnd(faceColor)
                .hatchColorAnd(hatchColor)
                .hatchStyleAnd(style)
                .alpha(0f);
        return id;
    }

    /** 그라데이션 BorderFill */
    private String makeGradientBf(ObjectList<BorderFill> bfs, GradationType gType,
                                   int angle, String color1, String color2) {
        String id = nextBfId();
        BorderFill bf = bfs.addNew();
        bf.idAnd(id).threeDAnd(false).shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE).breakCellSeparateLine(false);
        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        setBorder(bf, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createFillBrush();
        bf.fillBrush().createGradation();
        bf.fillBrush().gradation()
                .typeAnd(gType).angleAnd(angle)
                .centerXAnd(50).centerYAnd(50)
                .stepAnd((short) 50).stepCenterAnd((short) 50).alphaAnd(0f);
        bf.fillBrush().gradation().addNewColor().valueAnd(color1);
        bf.fillBrush().gradation().addNewColor().valueAnd(color2);
        return id;
    }

    /** 커스텀 4면 개별 테두리 BorderFill */
    private String makeCustomBorderBf(ObjectList<BorderFill> bfs,
                                       LineType2 lt, LineWidth lw, String lc,
                                       LineType2 rt, LineWidth rw, String rc,
                                       LineType2 tt, LineWidth tw, String tc,
                                       LineType2 bt, LineWidth bw, String bc,
                                       String bgColor) {
        String id = nextBfId();
        BorderFill bf = bfs.addNew();
        bf.idAnd(id).threeDAnd(false).shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE).breakCellSeparateLine(false);
        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createLeftBorder();
        bf.leftBorder().typeAnd(lt).widthAnd(lw).color(lc);
        bf.createRightBorder();
        bf.rightBorder().typeAnd(rt).widthAnd(rw).color(rc);
        bf.createTopBorder();
        bf.topBorder().typeAnd(tt).widthAnd(tw).color(tc);
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(bt).widthAnd(bw).color(bc);
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_1).color("#000000");
        if (bgColor != null) {
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush().faceColorAnd(bgColor).hatchColorAnd("#FF000000").alpha(0f);
        }
        return id;
    }

    private void setBorder(BorderFill bf, LineType2 type, LineWidth width, String color) {
        bf.createLeftBorder();
        bf.leftBorder().typeAnd(type).widthAnd(width).color(color);
        bf.createRightBorder();
        bf.rightBorder().typeAnd(type).widthAnd(width).color(color);
        bf.createTopBorder();
        bf.topBorder().typeAnd(type).widthAnd(width).color(color);
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(type).widthAnd(width).color(color);
    }

    private void setupTable(Table table, int rows, int cols, long totalWidth, long rowHeight,
                            String bfId, boolean treatAsChar) {
        table.idAnd(nextId()).zOrderAnd(0)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false).dropcapstyleAnd(DropCapStyle.None);
        table.pageBreakAnd(TablePageBreak.CELL).repeatHeaderAnd(false)
                .rowCntAnd((short) rows).colCntAnd((short) cols)
                .cellSpacingAnd(0).borderFillIDRefAnd(bfId).noAdjustAnd(false);
        table.createSZ();
        table.sz().widthAnd(totalWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(rowHeight * rows).heightRelToAnd(HeightRelTo.ABSOLUTE).protectAnd(false);
        table.createPos();
        table.pos().treatAsCharAnd(treatAsChar).affectLSpacingAnd(false)
                .flowWithTextAnd(true).allowOverlapAnd(false).holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA).horzRelToAnd(HorzRelTo.COLUMN)
                .vertAlignAnd(VertAlign.TOP).horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L).horzOffset(0L);
        table.createOutMargin();
        table.outMargin().leftAnd(283L).rightAnd(283L).topAnd(283L).bottomAnd(283L);
        table.createInMargin();
        table.inMargin().leftAnd(CELL_MARGIN_LR).rightAnd(CELL_MARGIN_LR)
                .topAnd(CELL_MARGIN_TB).bottomAnd(CELL_MARGIN_TB);
    }

    private Tc addCell(Tr tr, int col, int row, int colSpan, int rowSpan,
                       long width, long height, String bfId, String text) {
        Tc tc = tr.addNewTc();
        tc.nameAnd("").headerAnd(row == 0).hasMarginAnd(false)
                .protectAnd(false).editableAnd(false).dirtyAnd(false)
                .borderFillIDRef(bfId);
        tc.createCellAddr();
        tc.cellAddr().colAddrAnd((short) col).rowAddr((short) row);
        tc.createCellSpan();
        tc.cellSpan().colSpanAnd((short) colSpan).rowSpan((short) rowSpan);
        tc.createCellSz();
        tc.cellSz().widthAnd(width).height(height);
        tc.createCellMargin();
        tc.cellMargin().leftAnd(CELL_MARGIN_LR).rightAnd(CELL_MARGIN_LR)
                .topAnd(CELL_MARGIN_TB).bottomAnd(CELL_MARGIN_TB);
        tc.createSubList();
        tc.subList().idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK).vertAlignAnd(VerticalAlign2.CENTER)
                .linkListIDRefAnd("0").linkListNextIDRefAnd("0")
                .textWidthAnd(0).textHeightAnd(0).hasTextRefAnd(false).hasNumRefAnd(false);
        addTextToPara(tc.subList(), text);
        return tc;
    }

    private void addTextToPara(SubList subList, String text) {
        Para p = subList.addNewPara();
        p.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");
        r.addNewT().addText(text);
    }

    /** 셀 안에 중첩 테이블 삽입 */
    private void addNestedTable(SubList subList, ObjectList<BorderFill> bfs,
                                int rows, int cols, long width, long rowHeight, String bfId) {
        Para p = subList.addNewPara();
        p.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");

        Table inner = r.addNewTable();
        setupTable(inner, rows, cols, width, rowHeight, bfId, true);
        long cellW = width / cols;
        for (int row = 0; row < rows; row++) {
            Tr tr = inner.addNewTr();
            for (int col = 0; col < cols; col++) {
                addCell(tr, col, row, 1, 1, cellW, rowHeight, bfId,
                        "N" + row + "," + col);
            }
        }
        r.addNewT().addText("");
    }

    // ══════════════════════════════════════════
    //  1~10: 기본 테이블
    // ══════════════════════════════════════════

    private void buildBasicTable(Table table, ObjectList<BorderFill> bfs, int rows, int cols) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / cols;
        long cellH = 2000L;
        setupTable(table, rows, cols, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < rows; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < cols; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "R" + r + "C" + c);
            }
        }
    }

    private void buildNarrowColTable(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        int cols = 6;
        long cellW = TABLE_WIDTH / cols;
        long cellH = 2000L;
        setupTable(table, 3, cols, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < cols; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, bfId, String.valueOf(r * cols + c + 1));
            }
        }
    }

    private void buildWideColTable(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellH = 2000L;
        setupTable(table, 3, 2, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            addCell(tr, 0, r, 1, 1, TABLE_WIDTH / 2, cellH, bfId, "Wide " + (r + 1) + "-A");
            addCell(tr, 1, r, 1, 1, TABLE_WIDTH / 2, cellH, bfId, "Wide " + (r + 1) + "-B");
        }
    }

    private void buildTallRowTable(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 4000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "Tall R" + r + "C" + c);
            }
        }
    }

    // ══════════════════════════════════════════
    //  11~20: 셀 병합
    // ══════════════════════════════════════════

    private void buildHMergeTop(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        // Row 0: merged top (3 cols into 1)
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 3, 1, TABLE_WIDTH, cellH, bfId, "Merged Top Row");
        // Row 1,2: normal
        for (int r = 1; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "R" + r + "C" + c);
            }
        }
    }

    private void buildVMergeLeft(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        // Row 0: left cell spans 3 rows
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 1, 3, cellW, cellH * 3, bfId, "V-Merged Left");
        addCell(tr0, 1, 0, 1, 1, cellW, cellH, bfId, "R0C1");
        addCell(tr0, 2, 0, 1, 1, cellW, cellH, bfId, "R0C2");
        for (int r = 1; r < 3; r++) {
            Tr tr = table.addNewTr();
            addCell(tr, 1, r, 1, 1, cellW, cellH, bfId, "R" + r + "C1");
            addCell(tr, 2, r, 1, 1, cellW, cellH, bfId, "R" + r + "C2");
        }
    }

    private void buildLShapeMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        // Row 0: 2x2 merged + 1
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 2, 2, cellW * 2, cellH * 2, bfId, "2x2 Merged");
        addCell(tr0, 2, 0, 1, 1, cellW, cellH, bfId, "R0C2");
        Tr tr1 = table.addNewTr();
        addCell(tr1, 2, 1, 1, 1, cellW, cellH, bfId, "R1C2");
        Tr tr2 = table.addNewTr();
        for (int c = 0; c < 3; c++) {
            addCell(tr2, c, 2, 1, 1, cellW, cellH, bfId, "R2C" + c);
        }
    }

    private void buildCenterMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 4, 4, TABLE_WIDTH, cellH, bfId, true);
        // Row 0: normal
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 4; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, bfId, "R0C" + c);
        // Row 1: col1-2 merged (2x2)
        Tr tr1 = table.addNewTr();
        addCell(tr1, 0, 1, 1, 1, cellW, cellH, bfId, "R1C0");
        addCell(tr1, 1, 1, 2, 2, cellW * 2, cellH * 2, bfId, "Center 2x2");
        addCell(tr1, 3, 1, 1, 1, cellW, cellH, bfId, "R1C3");
        Tr tr2 = table.addNewTr();
        addCell(tr2, 0, 2, 1, 1, cellW, cellH, bfId, "R2C0");
        addCell(tr2, 3, 2, 1, 1, cellW, cellH, bfId, "R2C3");
        Tr tr3 = table.addNewTr();
        for (int c = 0; c < 4; c++) addCell(tr3, c, 3, 1, 1, cellW, cellH, bfId, "R3C" + c);
    }

    private void buildFullTopMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 3, 4, TABLE_WIDTH, cellH, bfId, true);
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 4, 1, TABLE_WIDTH, cellH, bfId, "Full Top Merged");
        for (int r = 1; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 4; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "R" + r + "C" + c);
        }
    }

    private void buildFullLeftMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 4, 3, TABLE_WIDTH, cellH, bfId, true);
        Tr tr0 = table.addNewTr();
        long leftW = TABLE_WIDTH / 3;
        long rightW = (TABLE_WIDTH - leftW) / 2;
        addCell(tr0, 0, 0, 1, 4, leftW, cellH * 4, bfId, "Full Left");
        addCell(tr0, 1, 0, 1, 1, rightW, cellH, bfId, "R0C1");
        addCell(tr0, 2, 0, 1, 1, rightW, cellH, bfId, "R0C2");
        for (int r = 1; r < 4; r++) {
            Tr tr = table.addNewTr();
            addCell(tr, 1, r, 1, 1, rightW, cellH, bfId, "R" + r + "C1");
            addCell(tr, 2, r, 1, 1, rightW, cellH, bfId, "R" + r + "C2");
        }
    }

    private void buildStaircaseMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 4, 4, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < 4; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 4; c++) {
                if (c == r && c < 3) {
                    addCell(tr, c, r, 2, 1, cellW * 2, cellH, bfId, "Stair " + r);
                    c++; // skip next
                } else if (c == r + 1 && r > 0) {
                    continue; // skip (was part of prev row merge)
                } else {
                    addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "R" + r + "C" + c);
                }
            }
        }
    }

    private void buildCrossMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        // Row 0: top merged
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 3, 1, TABLE_WIDTH, cellH, bfId, "Top Merged");
        // Row 1: left merged col
        Tr tr1 = table.addNewTr();
        addCell(tr1, 0, 1, 1, 1, cellW, cellH, bfId, "Left");
        addCell(tr1, 1, 1, 1, 1, cellW, cellH, bfId, "Center");
        addCell(tr1, 2, 1, 1, 1, cellW, cellH, bfId, "Right");
        // Row 2: bottom merged
        Tr tr2 = table.addNewTr();
        addCell(tr2, 0, 2, 3, 1, TABLE_WIDTH, cellH, bfId, "Bottom Merged");
    }

    private void buildCornerMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 4, 4, TABLE_WIDTH, cellH, bfId, true);
        // TL 2x2
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 2, 2, cellW * 2, cellH * 2, bfId, "TL 2x2");
        addCell(tr0, 2, 0, 1, 1, cellW, cellH, bfId, "R0C2");
        addCell(tr0, 3, 0, 1, 1, cellW, cellH, bfId, "R0C3");
        Tr tr1 = table.addNewTr();
        addCell(tr1, 2, 1, 1, 1, cellW, cellH, bfId, "R1C2");
        addCell(tr1, 3, 1, 1, 1, cellW, cellH, bfId, "R1C3");
        Tr tr2 = table.addNewTr();
        addCell(tr2, 0, 2, 1, 1, cellW, cellH, bfId, "R2C0");
        addCell(tr2, 1, 2, 1, 1, cellW, cellH, bfId, "R2C1");
        addCell(tr2, 2, 2, 2, 2, cellW * 2, cellH * 2, bfId, "BR 2x2");
        Tr tr3 = table.addNewTr();
        addCell(tr3, 0, 3, 1, 1, cellW, cellH, bfId, "R3C0");
        addCell(tr3, 1, 3, 1, 1, cellW, cellH, bfId, "R3C1");
    }

    private void buildCheckerboardMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 4, 4, TABLE_WIDTH, cellH, bfId, true);
        // Row 0: merge col 0-1, col 2-3
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 2, 1, cellW * 2, cellH, bfId, "A");
        addCell(tr0, 2, 0, 2, 1, cellW * 2, cellH, bfId, "B");
        // Row 1: normal
        Tr tr1 = table.addNewTr();
        for (int c = 0; c < 4; c++) addCell(tr1, c, 1, 1, 1, cellW, cellH, bfId, String.valueOf((char) ('C' + c)));
        // Row 2: merge col 0-1, col 2-3
        Tr tr2 = table.addNewTr();
        addCell(tr2, 0, 2, 2, 1, cellW * 2, cellH, bfId, "G");
        addCell(tr2, 2, 2, 2, 1, cellW * 2, cellH, bfId, "H");
        // Row 3: normal
        Tr tr3 = table.addNewTr();
        for (int c = 0; c < 4; c++) addCell(tr3, c, 3, 1, 1, cellW, cellH, bfId, String.valueOf((char) ('I' + c)));
    }

    // ══════════════════════════════════════════
    //  21~30: 테두리 스타일
    // ══════════════════════════════════════════

    private void buildBorderTable(Table table, ObjectList<BorderFill> bfs,
                                   LineType2 type, LineWidth width, String color) {
        String bfId = makeSolidBorderFill(bfs, type, width, color);
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, bfId, type.str());
            }
        }
    }

    private void buildMixedBorderTable(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeCustomBorderBf(bfs,
                LineType2.SOLID, LineWidth.MM_0_5, "#C00000",
                LineType2.DASH, LineWidth.MM_0_3, "#2E74B5",
                LineType2.DOT, LineWidth.MM_0_4, "#548235",
                LineType2.DOUBLE_SLIM, LineWidth.MM_0_5, "#7030A0",
                null);
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "Mixed");
            }
        }
    }

    private void buildNoInnerBorderTable(Table table, ObjectList<BorderFill> bfs) {
        // Outer: solid, inner cells: NONE
        String outerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_5, "#000000");
        String innerBf = makeSolidBorderFill(bfs, LineType2.NONE, LineWidth.MM_0_1, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, outerBf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                String bf = (r == 0 || r == 2 || c == 0 || c == 2) ? outerBf : innerBf;
                addCell(tr, c, r, 1, 1, cellW, cellH, bf, "No Inner");
            }
        }
    }

    private void buildNoOuterBorderTable(Table table, ObjectList<BorderFill> bfs) {
        String innerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        String outerBf = makeSolidBorderFill(bfs, LineType2.NONE, LineWidth.MM_0_1, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, outerBf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, innerBf, "No Outer");
            }
        }
    }

    // ══════════════════════════════════════════
    //  31~40: 배경색
    // ══════════════════════════════════════════

    private void buildHeaderColorTable(Table table, ObjectList<BorderFill> bfs,
                                        String headerBg, String headerBorder) {
        String hdrBf = makeBfWithBg(bfs, headerBg, LineType2.SOLID, LineWidth.MM_0_12, headerBorder);
        String bodyBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, headerBorder);
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 4, 3, TABLE_WIDTH, cellH, bodyBf, true);
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 3; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, "Header " + (c + 1));
        for (int r = 1; r < 4; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, "Data " + r + "-" + (c + 1));
        }
    }

    private void buildYellowCellsTable(Table table, ObjectList<BorderFill> bfs) {
        String bf = makeBfWithBg(bfs, "#FFF2CC", LineType2.SOLID, LineWidth.MM_0_12, "#BF8F00");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bf, "Yellow");
        }
    }

    private void buildAlternatingRowTable(Table table, ObjectList<BorderFill> bfs) {
        String bf1 = makeBfWithBg(bfs, "#FFFFFF", LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        String bf2 = makeBfWithBg(bfs, "#D9E2F3", LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 5, 3, TABLE_WIDTH, cellH, bf1, true);
        for (int r = 0; r < 5; r++) {
            Tr tr = table.addNewTr();
            String bf = (r % 2 == 0) ? bf1 : bf2;
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bf, "Row " + (r + 1));
        }
    }

    private void buildAlternatingColTable(Table table, ObjectList<BorderFill> bfs) {
        String bf1 = makeBfWithBg(bfs, "#FFFFFF", LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        String bf2 = makeBfWithBg(bfs, "#E2EFDA", LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 3, 4, TABLE_WIDTH, cellH, bf1, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 4; c++) {
                String bf = (c % 2 == 0) ? bf1 : bf2;
                addCell(tr, c, r, 1, 1, cellW, cellH, bf, "Col " + (c + 1));
            }
        }
    }

    private void buildGradientBlueCells(Table table, ObjectList<BorderFill> bfs) {
        String[] colors = {"#DEEAF6", "#BDD7EE", "#9DC3E6", "#5B9BD5", "#2E74B5"};
        long cellW = TABLE_WIDTH / 5;
        long cellH = 2500L;
        String baseBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        setupTable(table, 1, 5, TABLE_WIDTH, cellH, baseBf, true);
        Tr tr = table.addNewTr();
        for (int c = 0; c < 5; c++) {
            String bf = makeBfWithBg(bfs, colors[c], LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
            addCell(tr, c, 0, 1, 1, cellW, cellH, bf, "Shade " + (c + 1));
        }
    }

    private void buildPastelRainbow(Table table, ObjectList<BorderFill> bfs) {
        String[] colors = {"#FFD9D9", "#FFE8CC", "#FFFFCC", "#D9FFD9", "#CCE5FF", "#E8CCFF"};
        long cellW = TABLE_WIDTH / 6;
        long cellH = 2500L;
        String baseBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#808080");
        setupTable(table, 1, 6, TABLE_WIDTH, cellH, baseBf, true);
        Tr tr = table.addNewTr();
        for (int c = 0; c < 6; c++) {
            String bf = makeBfWithBg(bfs, colors[c], LineType2.SOLID, LineWidth.MM_0_12, "#808080");
            addCell(tr, c, 0, 1, 1, cellW, cellH, bf, colors[c]);
        }
    }

    private void buildDarkTheme(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#1F1F1F", LineType2.SOLID, LineWidth.MM_0_12, "#444444");
        String bodyBf = makeBfWithBg(bfs, "#333333", LineType2.SOLID, LineWidth.MM_0_12, "#444444");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 4, 3, TABLE_WIDTH, cellH, bodyBf, true);
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 3; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, "Dark Hdr " + (c + 1));
        for (int r = 1; r < 4; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, "Dark " + r);
        }
    }

    private void buildWarmTheme(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#F4B183", LineType2.SOLID, LineWidth.MM_0_12, "#843C0C");
        String bodyBf = makeBfWithBg(bfs, "#FBE5D6", LineType2.SOLID, LineWidth.MM_0_12, "#843C0C");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 4, 3, TABLE_WIDTH, cellH, bodyBf, true);
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 3; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, "Warm H" + (c + 1));
        for (int r = 1; r < 4; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, "Warm " + r + "-" + (c + 1));
        }
    }

    // ══════════════════════════════════════════
    //  41~50: 테마 테이블
    // ══════════════════════════════════════════

    private void buildThemeTable(Table table, ObjectList<BorderFill> bfs,
                                  String hdrBg, String bodyBg, String borderColor) {
        String hdrBf = makeBfWithBg(bfs, hdrBg, LineType2.SOLID, LineWidth.MM_0_25, borderColor);
        String bodyBf = makeBfWithBg(bfs, bodyBg, LineType2.SOLID, LineWidth.MM_0_12, borderColor);
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 4, 4, TABLE_WIDTH, cellH, bodyBf, true);
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 4; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, "Header " + (c + 1));
        for (int r = 1; r < 4; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 4; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, "D" + r + "-" + (c + 1));
        }
    }

    private void buildCorporateBlue(Table t, ObjectList<BorderFill> b) { buildThemeTable(t, b, "#2E74B5", "#DEEAF6", "#2E74B5"); }
    private void buildAcademicGray(Table t, ObjectList<BorderFill> b) { buildThemeTable(t, b, "#808080", "#F2F2F2", "#808080"); }
    private void buildNatureGreen(Table t, ObjectList<BorderFill> b) { buildThemeTable(t, b, "#548235", "#E2EFDA", "#548235"); }
    private void buildSunsetOrange(Table t, ObjectList<BorderFill> b) { buildThemeTable(t, b, "#ED7D31", "#FBE5D6", "#ED7D31"); }
    private void buildOceanTheme(Table t, ObjectList<BorderFill> b) { buildThemeTable(t, b, "#1F4E79", "#D6E4F0", "#1F4E79"); }
    private void buildLavenderTheme(Table t, ObjectList<BorderFill> b) { buildThemeTable(t, b, "#7030A0", "#F2E6FF", "#7030A0"); }

    private void buildMonochrome(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#000000", LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        String bodyBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 4, 3, TABLE_WIDTH, cellH, bodyBf, true);
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 3; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, "Mono " + (c + 1));
        for (int r = 1; r < 4; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, "Data");
        }
    }

    private void buildHighContrast(Table table, ObjectList<BorderFill> bfs) {
        String bf1 = makeBfWithBg(bfs, "#000000", LineType2.SOLID, LineWidth.MM_1_0, "#FF0000");
        String bf2 = makeBfWithBg(bfs, "#FFFF00", LineType2.SOLID, LineWidth.MM_1_0, "#FF0000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bf1, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                String bf = ((r + c) % 2 == 0) ? bf1 : bf2;
                addCell(tr, c, r, 1, 1, cellW, cellH, bf, "HC");
            }
        }
    }

    private void buildMinimalLines(Table table, ObjectList<BorderFill> bfs) {
        // top/bottom only
        String bf = makeCustomBorderBf(bfs,
                LineType2.NONE, LineWidth.MM_0_1, "#000000",
                LineType2.NONE, LineWidth.MM_0_1, "#000000",
                LineType2.SOLID, LineWidth.MM_0_25, "#000000",
                LineType2.SOLID, LineWidth.MM_0_25, "#000000",
                null);
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bf, "Minimal");
        }
    }

    private void buildClassicGrid(Table table, ObjectList<BorderFill> bfs) {
        String bf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_25, "#000000");
        long cellW = TABLE_WIDTH / 5;
        long cellH = 1800L;
        setupTable(table, 5, 5, TABLE_WIDTH, cellH, bf, true);
        for (int r = 0; r < 5; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 5; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bf, (r * 5 + c + 1) + "");
        }
    }

    // ══════════════════════════════════════════
    //  51~60: 그라데이션
    // ══════════════════════════════════════════

    private void buildGradientTable(Table table, ObjectList<BorderFill> bfs,
                                     GradationType gType, int angle, String c1, String c2) {
        String gradBf = makeGradientBf(bfs, gType, angle, c1, c2);
        String normalBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, normalBf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, gradBf, gType.str());
            }
        }
    }

    // ══════════════════════════════════════════
    //  61~70: 대각선/빗금
    // ══════════════════════════════════════════

    private void buildDiagonalCells(Table table, ObjectList<BorderFill> bfs, boolean slash) {
        String diagBf = makeDiagonalBf(bfs, slash, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, diagBf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, diagBf, slash ? "Slash" : "BackSlash");
            }
        }
    }

    private void buildHatchTable(Table table, ObjectList<BorderFill> bfs, HatchStyle style) {
        String hatchBf = makeHatchBf(bfs, style, "#FFFFFF", "#CCCCCC");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, hatchBf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, hatchBf, style.str());
            }
        }
    }

    private void buildDiagonalHeaderTable(Table table, ObjectList<BorderFill> bfs) {
        String diagBf = makeDiagonalBf(bfs, true, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        String normalBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, normalBf, true);
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 1, 1, cellW, cellH, diagBf, "Diag");
        addCell(tr0, 1, 0, 1, 1, cellW, cellH, normalBf, "Col A");
        addCell(tr0, 2, 0, 1, 1, cellW, cellH, normalBf, "Col B");
        for (int r = 1; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, normalBf, "R" + r + "C" + c);
        }
    }

    private void buildMixedDiagonalTable(Table table, ObjectList<BorderFill> bfs) {
        String slashBf = makeDiagonalBf(bfs, true, LineType2.SOLID, LineWidth.MM_0_12, "#C00000");
        String backBf = makeDiagonalBf(bfs, false, LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        String normalBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, normalBf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                String bf = ((r + c) % 3 == 0) ? slashBf : ((r + c) % 3 == 1) ? backBf : normalBf;
                addCell(tr, c, r, 1, 1, cellW, cellH, bf, "Mix");
            }
        }
    }

    private void buildStripedCells(Table table, ObjectList<BorderFill> bfs) {
        String hBf = makeHatchBf(bfs, HatchStyle.HORIZONTAL, "#E8F0FE", "#BDD7EE");
        String vBf = makeHatchBf(bfs, HatchStyle.VERTICAL, "#FEF3E8", "#F4B183");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2500L;
        setupTable(table, 2, 4, TABLE_WIDTH, cellH, hBf, true);
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 4; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hBf, "H-Stripe");
        Tr tr1 = table.addNewTr();
        for (int c = 0; c < 4; c++) addCell(tr1, c, 1, 1, 1, cellW, cellH, vBf, "V-Stripe");
    }

    private void buildPatternFill(Table table, ObjectList<BorderFill> bfs) {
        HatchStyle[] styles = {HatchStyle.HORIZONTAL, HatchStyle.VERTICAL, HatchStyle.CROSS,
                HatchStyle.SLASH, HatchStyle.BACK_SLASH, HatchStyle.CROSS_DIAGONAL};
        long cellW = TABLE_WIDTH / 6;
        long cellH = 2500L;
        String baseBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        setupTable(table, 1, 6, TABLE_WIDTH, cellH, baseBf, true);
        Tr tr = table.addNewTr();
        for (int c = 0; c < 6; c++) {
            String bf = makeHatchBf(bfs, styles[c], "#FFFFFF", "#999999");
            addCell(tr, c, 0, 1, 1, cellW, cellH, bf, styles[c].str());
        }
    }

    // ══════════════════════════════════════════
    //  71~80: 중첩 테이블
    // ══════════════════════════════════════════

    private void buildNestedTable(Table table, ObjectList<BorderFill> bfs, int innerR, int innerC) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        String innerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        long cellW = TABLE_WIDTH / 2;
        long cellH = 4000L;
        setupTable(table, 2, 2, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < 2; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 2; c++) {
                Tc tc = addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "");
                if (r == 0 && c == 0) {
                    addNestedTable(tc.subList(), bfs, innerR, innerC, cellW - 1000, 1500L, innerBf);
                } else {
                    // text already set by addCell
                }
            }
        }
    }

    private void buildDoubleNested(Table table, ObjectList<BorderFill> bfs) {
        String outerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_25, "#000000");
        String midBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        String innerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#C00000");
        long cellW = TABLE_WIDTH;
        long cellH = 6000L;
        setupTable(table, 1, 1, TABLE_WIDTH, cellH, outerBf, true);
        Tr tr = table.addNewTr();
        Tc tc = addCell(tr, 0, 0, 1, 1, cellW, cellH, outerBf, "");
        // Add mid-level nested table
        Para p = tc.subList().addNewPara();
        p.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run rn = p.addNewRun();
        rn.charPrIDRef("0");
        Table midTable = rn.addNewTable();
        setupTable(midTable, 2, 2, cellW - 2000, 2500L, midBf, true);
        long midCellW = (cellW - 2000) / 2;
        for (int mr = 0; mr < 2; mr++) {
            Tr mtr = midTable.addNewTr();
            for (int mc = 0; mc < 2; mc++) {
                Tc mtc = addCell(mtr, mc, mr, 1, 1, midCellW, 2500L, midBf, "");
                if (mr == 0 && mc == 0) {
                    addNestedTable(mtc.subList(), bfs, 2, 2, midCellW - 1000, 1000L, innerBf);
                }
            }
        }
        rn.addNewT().addText("");
    }

    private void buildNestedWithMerge(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        String innerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#548235");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 4000L;
        setupTable(table, 2, 3, TABLE_WIDTH, cellH, bfId, true);
        Tr tr0 = table.addNewTr();
        Tc merged = addCell(tr0, 0, 0, 2, 1, cellW * 2, cellH, bfId, "");
        addNestedTable(merged.subList(), bfs, 2, 3, cellW * 2 - 1000, 1500L, innerBf);
        addCell(tr0, 2, 0, 1, 1, cellW, cellH, bfId, "Side");
        Tr tr1 = table.addNewTr();
        for (int c = 0; c < 3; c++) addCell(tr1, c, 1, 1, 1, cellW, cellH, bfId, "R1C" + c);
    }

    private void buildNestedWithColors(Table table, ObjectList<BorderFill> bfs) {
        String outerBf = makeBfWithBg(bfs, "#E2EFDA", LineType2.SOLID, LineWidth.MM_0_12, "#548235");
        String innerBf = makeBfWithBg(bfs, "#DEEAF6", LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        long cellW = TABLE_WIDTH / 2;
        long cellH = 4000L;
        setupTable(table, 2, 2, TABLE_WIDTH, cellH, outerBf, true);
        for (int r = 0; r < 2; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 2; c++) {
                Tc tc = addCell(tr, c, r, 1, 1, cellW, cellH, outerBf, "");
                if ((r + c) % 2 == 0) {
                    addNestedTable(tc.subList(), bfs, 2, 2, cellW - 1000, 1500L, innerBf);
                }
            }
        }
    }

    private void buildNestedWithBorder(Table table, ObjectList<BorderFill> bfs) {
        String outerBf = makeSolidBorderFill(bfs, LineType2.DOUBLE_SLIM, LineWidth.MM_0_5, "#000000");
        String innerBf = makeSolidBorderFill(bfs, LineType2.DASH, LineWidth.MM_0_3, "#C00000");
        long cellW = TABLE_WIDTH / 2;
        long cellH = 4000L;
        setupTable(table, 1, 2, TABLE_WIDTH, cellH, outerBf, true);
        Tr tr = table.addNewTr();
        Tc tc0 = addCell(tr, 0, 0, 1, 1, cellW, cellH, outerBf, "");
        addNestedTable(tc0.subList(), bfs, 2, 2, cellW - 1000, 1500L, innerBf);
        addCell(tr, 1, 0, 1, 1, cellW, cellH, outerBf, "Normal");
    }

    private void buildNestedInEachCell(Table table, ObjectList<BorderFill> bfs) {
        String outerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_25, "#000000");
        String innerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#7030A0");
        long cellW = TABLE_WIDTH / 2;
        long cellH = 4000L;
        setupTable(table, 2, 2, TABLE_WIDTH, cellH, outerBf, true);
        for (int r = 0; r < 2; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 2; c++) {
                Tc tc = addCell(tr, c, r, 1, 1, cellW, cellH, outerBf, "");
                addNestedTable(tc.subList(), bfs, 2, 2, cellW - 1000, 1200L, innerBf);
            }
        }
    }

    private void buildNestedAsymmetric(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        String innerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#BF8F00");
        long leftW = TABLE_WIDTH * 2 / 3;
        long rightW = TABLE_WIDTH - leftW;
        long cellH = 5000L;
        setupTable(table, 1, 2, TABLE_WIDTH, cellH, bfId, true);
        Tr tr = table.addNewTr();
        Tc tc0 = addCell(tr, 0, 0, 1, 1, leftW, cellH, bfId, "");
        addNestedTable(tc0.subList(), bfs, 3, 2, leftW - 1000, 1200L, innerBf);
        addCell(tr, 1, 0, 1, 1, rightW, cellH, bfId, "Side Panel");
    }

    private void buildDeepNested(Table table, ObjectList<BorderFill> bfs) {
        String bf1 = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_25, "#000000");
        String bf2 = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        String bf3 = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#C00000");
        long cellH = 8000L;
        setupTable(table, 1, 1, TABLE_WIDTH, cellH, bf1, true);
        Tr tr = table.addNewTr();
        Tc tc = addCell(tr, 0, 0, 1, 1, TABLE_WIDTH, cellH, bf1, "");
        // Level 2
        Para p2 = tc.subList().addNewPara();
        p2.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run r2 = p2.addNewRun();
        r2.charPrIDRef("0");
        Table t2 = r2.addNewTable();
        setupTable(t2, 1, 1, TABLE_WIDTH - 2000, 5000L, bf2, true);
        Tr tr2 = t2.addNewTr();
        Tc tc2 = addCell(tr2, 0, 0, 1, 1, TABLE_WIDTH - 2000, 5000L, bf2, "");
        // Level 3
        addNestedTable(tc2.subList(), bfs, 2, 2, TABLE_WIDTH - 4000, 1500L, bf3);
        r2.addNewT().addText("");
    }

    private void buildNestedGrid(Table table, ObjectList<BorderFill> bfs) {
        String outerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_25, "#44546A");
        String innerBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#44546A");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 3500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, outerBf, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                Tc tc = addCell(tr, c, r, 1, 1, cellW, cellH, outerBf, "");
                if (r == 1 && c == 1) {
                    addNestedTable(tc.subList(), bfs, 2, 2, cellW - 1000, 1000L, innerBf);
                }
            }
        }
    }

    // ══════════════════════════════════════════
    //  81~90: 복합 스타일
    // ══════════════════════════════════════════

    private void buildMergeAndColor(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#2E74B5", LineType2.SOLID, LineWidth.MM_0_12, "#1F4E79");
        String bodyBf = makeBfWithBg(bfs, "#DEEAF6", LineType2.SOLID, LineWidth.MM_0_12, "#1F4E79");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bodyBf, true);
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 3, 1, TABLE_WIDTH, cellH, hdrBf, "Merged Header with Color");
        for (int r = 1; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, "D" + r + "C" + c);
        }
    }

    private void buildMergeAndBorder(Table table, ObjectList<BorderFill> bfs) {
        String thickBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_1_0, "#000000");
        String thinBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, thinBf, true);
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 2, 2, cellW * 2, cellH * 2, thickBf, "Thick 2x2");
        addCell(tr0, 2, 0, 1, 1, cellW, cellH, thinBf, "Thin");
        Tr tr1 = table.addNewTr();
        addCell(tr1, 2, 1, 1, 1, cellW, cellH, thinBf, "Thin");
        Tr tr2 = table.addNewTr();
        for (int c = 0; c < 3; c++) addCell(tr2, c, 2, 1, 1, cellW, cellH, thinBf, "Thin");
    }

    private void buildColorAndBorder(Table table, ObjectList<BorderFill> bfs) {
        String blueBf = makeBfWithBg(bfs, "#DEEAF6", LineType2.SOLID, LineWidth.MM_0_5, "#2E74B5");
        String greenBf = makeBfWithBg(bfs, "#E2EFDA", LineType2.DASH, LineWidth.MM_0_3, "#548235");
        String redBf = makeBfWithBg(bfs, "#FCE4EC", LineType2.DOT, LineWidth.MM_0_4, "#C00000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, blueBf, true);
        String[] bfArr = {blueBf, greenBf, redBf};
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, cellH, bfArr[r], "Style " + (r + 1));
            }
        }
    }

    private void buildMergeAndGradient(Table table, ObjectList<BorderFill> bfs) {
        String gradBf = makeGradientBf(bfs, GradationType.LINEAR, 0, "#2E74B5", "#FFFFFF");
        String normalBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2500L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, normalBf, true);
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 3, 1, TABLE_WIDTH, cellH, gradBf, "Gradient Merged Header");
        for (int r = 1; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, normalBf, "D" + r + "C" + c);
        }
    }

    private void buildFullStyleMix(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeGradientBf(bfs, GradationType.LINEAR, 0, "#1F4E79", "#2E74B5");
        String altBf1 = makeBfWithBg(bfs, "#FFFFFF", LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        String altBf2 = makeBfWithBg(bfs, "#D6E4F0", LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 5, 4, TABLE_WIDTH, cellH, altBf1, true);
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 4, 1, TABLE_WIDTH, cellH, hdrBf, "Full Style Mix Table");
        for (int r = 1; r < 5; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 4; c++) {
                String bf = (r % 2 == 1) ? altBf1 : altBf2;
                addCell(tr, c, r, 1, 1, cellW, cellH, bf, "R" + r + "C" + c);
            }
        }
    }

    private void buildInvoiceLayout(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#1F4E79", LineType2.SOLID, LineWidth.MM_0_25, "#1F4E79");
        String bodyBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#808080");
        String totalBf = makeBfWithBg(bfs, "#D9E2F3", LineType2.SOLID, LineWidth.MM_0_25, "#1F4E79");
        long cellH = 2000L;
        long w1 = TABLE_WIDTH * 10 / 100; // No
        long w2 = TABLE_WIDTH * 50 / 100; // Item
        long w3 = TABLE_WIDTH * 20 / 100; // Qty
        long w4 = TABLE_WIDTH - w1 - w2 - w3; // Price
        setupTable(table, 6, 4, TABLE_WIDTH, cellH, bodyBf, true);
        // Header
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 1, 1, w1, cellH, hdrBf, "No");
        addCell(tr0, 1, 0, 1, 1, w2, cellH, hdrBf, "Item");
        addCell(tr0, 2, 0, 1, 1, w3, cellH, hdrBf, "Qty");
        addCell(tr0, 3, 0, 1, 1, w4, cellH, hdrBf, "Price");
        // Body
        String[][] data = {{"1", "Widget A", "10", "1,000"}, {"2", "Widget B", "5", "2,500"},
                {"3", "Widget C", "20", "500"}, {"4", "Widget D", "2", "10,000"}};
        for (int r = 0; r < 4; r++) {
            Tr tr = table.addNewTr();
            addCell(tr, 0, r + 1, 1, 1, w1, cellH, bodyBf, data[r][0]);
            addCell(tr, 1, r + 1, 1, 1, w2, cellH, bodyBf, data[r][1]);
            addCell(tr, 2, r + 1, 1, 1, w3, cellH, bodyBf, data[r][2]);
            addCell(tr, 3, r + 1, 1, 1, w4, cellH, bodyBf, data[r][3]);
        }
        // Total
        Tr trT = table.addNewTr();
        addCell(trT, 0, 5, 3, 1, w1 + w2 + w3, cellH, totalBf, "Total");
        addCell(trT, 3, 5, 1, 1, w4, cellH, totalBf, "33,500");
    }

    private void buildCalendarLayout(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#4472C4", LineType2.SOLID, LineWidth.MM_0_12, "#4472C4");
        String bodyBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#B4C6E7");
        long cellW = TABLE_WIDTH / 7;
        long cellH = 2000L;
        setupTable(table, 6, 7, TABLE_WIDTH, cellH, bodyBf, true);
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 7; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, days[c]);
        int day = 1;
        for (int r = 1; r < 6; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 7; c++) {
                String text = (r == 1 && c < 2) ? "" : (day <= 31) ? String.valueOf(day++) : "";
                addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, text);
            }
        }
    }

    private void buildReportHeader(Table table, ObjectList<BorderFill> bfs) {
        String titleBf = makeBfWithBg(bfs, "#1F4E79", LineType2.SOLID, LineWidth.MM_0_25, "#1F4E79");
        String labelBf = makeBfWithBg(bfs, "#D9E2F3", LineType2.SOLID, LineWidth.MM_0_12, "#1F4E79");
        String valueBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#1F4E79");
        long halfW = TABLE_WIDTH / 2;
        long cellH = 2000L;
        setupTable(table, 4, 2, TABLE_WIDTH, cellH, valueBf, true);
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 2, 1, TABLE_WIDTH, cellH, titleBf, "Report Title");
        Tr tr1 = table.addNewTr();
        addCell(tr1, 0, 1, 1, 1, halfW, cellH, labelBf, "Date:");
        addCell(tr1, 1, 1, 1, 1, halfW, cellH, valueBf, "2026-01-30");
        Tr tr2 = table.addNewTr();
        addCell(tr2, 0, 2, 1, 1, halfW, cellH, labelBf, "Author:");
        addCell(tr2, 1, 2, 1, 1, halfW, cellH, valueBf, "HWPX Team");
        Tr tr3 = table.addNewTr();
        addCell(tr3, 0, 3, 1, 1, halfW, cellH, labelBf, "Version:");
        addCell(tr3, 1, 3, 1, 1, halfW, cellH, valueBf, "1.0");
    }

    private void buildDataGrid(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#44546A", LineType2.SOLID, LineWidth.MM_0_12, "#44546A");
        String oddBf = makeBfWithBg(bfs, "#FFFFFF", LineType2.SOLID, LineWidth.MM_0_12, "#D5D5D5");
        String evenBf = makeBfWithBg(bfs, "#F2F2F2", LineType2.SOLID, LineWidth.MM_0_12, "#D5D5D5");
        long cellW = TABLE_WIDTH / 5;
        long cellH = 1800L;
        setupTable(table, 6, 5, TABLE_WIDTH, cellH, oddBf, true);
        Tr tr0 = table.addNewTr();
        String[] hdrs = {"ID", "Name", "Score", "Grade", "Status"};
        for (int c = 0; c < 5; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, hdrs[c]);
        for (int r = 1; r < 6; r++) {
            Tr tr = table.addNewTr();
            String bf = (r % 2 == 1) ? oddBf : evenBf;
            addCell(tr, 0, r, 1, 1, cellW, cellH, bf, String.valueOf(r));
            addCell(tr, 1, r, 1, 1, cellW, cellH, bf, "Item " + r);
            addCell(tr, 2, r, 1, 1, cellW, cellH, bf, String.valueOf(80 + r * 3));
            addCell(tr, 3, r, 1, 1, cellW, cellH, bf, r <= 2 ? "A" : r <= 4 ? "B" : "C");
            addCell(tr, 4, r, 1, 1, cellW, cellH, bf, r % 2 == 0 ? "Active" : "Pending");
        }
    }

    private void buildSummaryTable(Table table, ObjectList<BorderFill> bfs) {
        String titleBf = makeBfWithBg(bfs, "#548235", LineType2.SOLID, LineWidth.MM_0_25, "#375623");
        String labelBf = makeBfWithBg(bfs, "#E2EFDA", LineType2.SOLID, LineWidth.MM_0_12, "#548235");
        String valBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#548235");
        long w1 = TABLE_WIDTH / 3;
        long w2 = TABLE_WIDTH - w1;
        long cellH = 2000L;
        setupTable(table, 5, 2, TABLE_WIDTH, cellH, valBf, true);
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 2, 1, TABLE_WIDTH, cellH, titleBf, "Summary");
        String[] labels = {"Items", "Total", "Average", "Status"};
        String[] values = {"300", "45,000", "150.0", "Complete"};
        for (int r = 0; r < 4; r++) {
            Tr tr = table.addNewTr();
            addCell(tr, 0, r + 1, 1, 1, w1, cellH, labelBf, labels[r]);
            addCell(tr, 1, r + 1, 1, 1, w2, cellH, valBf, values[r]);
        }
    }

    // ══════════════════════════════════════════
    //  91~100: 특수 레이아웃
    // ══════════════════════════════════════════

    private void buildUnevenColumns(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long w1 = TABLE_WIDTH * 15 / 100;
        long w2 = TABLE_WIDTH * 50 / 100;
        long w3 = TABLE_WIDTH - w1 - w2;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            addCell(tr, 0, r, 1, 1, w1, cellH, bfId, "Narrow");
            addCell(tr, 1, r, 1, 1, w2, cellH, bfId, "Wide Center Column");
            addCell(tr, 2, r, 1, 1, w3, cellH, bfId, "Right");
        }
    }

    private void buildUnevenRows(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long[] heights = {1500L, 3000L, 1500L, 4000L};
        long totalH = 0;
        for (long h : heights) totalH += h;
        setupTable(table, 4, 3, TABLE_WIDTH, 2500L, bfId, true);
        table.sz().heightAnd(totalH);
        for (int r = 0; r < 4; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                addCell(tr, c, r, 1, 1, cellW, heights[r], bfId, "H=" + heights[r]);
            }
        }
    }

    private void buildWideMarginTable(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 2;
        long cellH = 3000L;
        setupTable(table, 2, 2, TABLE_WIDTH, cellH, bfId, true);
        table.inMargin().leftAnd(1500L).rightAnd(1500L).topAnd(800L).bottomAnd(800L);
        for (int r = 0; r < 2; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 2; c++) {
                Tc tc = addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "Wide Margin");
                tc.cellMargin().leftAnd(1500L).rightAnd(1500L).topAnd(800L).bottomAnd(800L);
            }
        }
    }

    private void buildZeroMarginTable(Table table, ObjectList<BorderFill> bfs) {
        String bfId = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#000000");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 3, 3, TABLE_WIDTH, cellH, bfId, true);
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        for (int r = 0; r < 3; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) {
                Tc tc = addCell(tr, c, r, 1, 1, cellW, cellH, bfId, "No Margin");
                tc.cellMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
            }
        }
    }

    private void buildHeaderRepeatTable(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#2E74B5", LineType2.SOLID, LineWidth.MM_0_25, "#2E74B5");
        String bodyBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#2E74B5");
        long cellW = TABLE_WIDTH / 3;
        long cellH = 2000L;
        setupTable(table, 5, 3, TABLE_WIDTH, cellH, bodyBf, true);
        table.repeatHeaderAnd(true);
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < 3; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, "Hdr " + (c + 1));
        for (int r = 1; r < 5; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < 3; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, "D" + r + "C" + c);
        }
    }

    private void buildLargeTable(Table table, ObjectList<BorderFill> bfs) {
        String hdrBf = makeBfWithBg(bfs, "#44546A", LineType2.SOLID, LineWidth.MM_0_12, "#44546A");
        String bodyBf = makeSolidBorderFill(bfs, LineType2.SOLID, LineWidth.MM_0_12, "#808080");
        int rows = 8, cols = 6;
        long cellW = TABLE_WIDTH / cols;
        long cellH = 1800L;
        setupTable(table, rows, cols, TABLE_WIDTH, cellH, bodyBf, true);
        Tr tr0 = table.addNewTr();
        for (int c = 0; c < cols; c++) addCell(tr0, c, 0, 1, 1, cellW, cellH, hdrBf, "H" + (c + 1));
        for (int r = 1; r < rows; r++) {
            Tr tr = table.addNewTr();
            for (int c = 0; c < cols; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bodyBf, r + "-" + (c + 1));
        }
    }

    private void buildFinaleTable(Table table, ObjectList<BorderFill> bfs) {
        // Grand finale: gradient header, alternating rows, merge, colored borders
        String gradHdr = makeGradientBf(bfs, GradationType.LINEAR, 0, "#1F4E79", "#4472C4");
        String alt1 = makeBfWithBg(bfs, "#FFFFFF", LineType2.SOLID, LineWidth.MM_0_12, "#4472C4");
        String alt2 = makeBfWithBg(bfs, "#D6E4F0", LineType2.SOLID, LineWidth.MM_0_12, "#4472C4");
        String totalBf = makeBfWithBg(bfs, "#BDD7EE", LineType2.SOLID, LineWidth.MM_0_25, "#1F4E79");
        long cellW = TABLE_WIDTH / 4;
        long cellH = 2000L;
        setupTable(table, 6, 4, TABLE_WIDTH, cellH, alt1, true);
        // Merged gradient header
        Tr tr0 = table.addNewTr();
        addCell(tr0, 0, 0, 4, 1, TABLE_WIDTH, cellH, gradHdr, "SHOWCASE FINALE TABLE");
        // Sub-header
        Tr tr1 = table.addNewTr();
        for (int c = 0; c < 4; c++) addCell(tr1, c, 1, 1, 1, cellW, cellH, gradHdr, "Col " + (c + 1));
        // Data rows
        for (int r = 2; r < 5; r++) {
            Tr tr = table.addNewTr();
            String bf = (r % 2 == 0) ? alt1 : alt2;
            for (int c = 0; c < 4; c++) addCell(tr, c, r, 1, 1, cellW, cellH, bf, "D" + (r - 1) + "C" + (c + 1));
        }
        // Merged total row
        Tr trT = table.addNewTr();
        addCell(trT, 0, 5, 3, 1, cellW * 3, cellH, totalBf, "Grand Total");
        addCell(trT, 3, 5, 1, 1, cellW, cellH, totalBf, "100");
    }
}
