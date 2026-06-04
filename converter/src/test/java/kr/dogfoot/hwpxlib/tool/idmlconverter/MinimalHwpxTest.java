package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Test;

public class MinimalHwpxTest {

    private static final String HOME = System.getProperty("user.home");
    private static final String IDML = HOME + "/Desktop/1.limit.idml";
    private static final String OUT = HOME + "/Desktop/test_hwpx/";

    @org.junit.Before
    public void setUp() {
        new java.io.File(OUT).mkdirs();
    }

    // 1) BlankFileMaker + 30단락 텍스트 (이것이 열리는지 확인)
    @Test
    public void test1_blank30paras() throws Exception {
        HWPXFile hwpx = BlankFileMaker.make();
        SectionXMLFile section = hwpx.sectionXMLFileList().get(0);
        // 기존 빈 단락에 텍스트 추가
        Para p0 = section.getPara(0);
        p0.getRun(0).addNewT().addText("첫 번째 단락입니다.");
        // 29개 단락 추가
        for (int i = 1; i < 30; i++) {
            Para para = section.addNewPara();
            para.idAnd(String.valueOf(1000000000L + i))
                    .paraPrIDRefAnd("3").styleIDRefAnd("0")
                    .pageBreakAnd(false).columnBreakAnd(false).merged(false);
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT().addText("단락 " + (i + 1) + ": 테스트 텍스트입니다. 수열의 극한값을 구하시오.");
        }
        HWPXWriter.toFilepath(hwpx, OUT + "1_blank30.hwpx");
        System.out.println("1_blank30: " + new java.io.File(OUT + "1_blank30.hwpx").length());
    }

    // 2) IDML 변환 - 8~11페이지 (수식 없이)
    @Test
    public void test2_idml_p8_11() throws Exception {
        HWPXFile hwpx = IDMLToHwpxConverter.convertToHwpxFile(IDML,
                ConvertOptions.defaults().startPage(8).endPage(11).includeEquations(false));
        HWPXWriter.toFilepath(hwpx, OUT + "2_idml_p8_11.hwpx");
        System.out.println("2_idml_p8_11: " + new java.io.File(OUT + "2_idml_p8_11.hwpx").length());
    }

    // 3) IDML 변환 - 8~9페이지 (수식 없이)
    @Test
    public void test3_idml_p8_9() throws Exception {
        HWPXFile hwpx = IDMLToHwpxConverter.convertToHwpxFile(IDML,
                ConvertOptions.defaults().startPage(8).endPage(9).includeEquations(false));
        HWPXWriter.toFilepath(hwpx, OUT + "3_idml_p8_9.hwpx");
        System.out.println("3_idml_p8_9: " + new java.io.File(OUT + "3_idml_p8_9.hwpx").length());
    }

    // 4) IDML 변환 - 8페이지만 (수식 없이)
    @Test
    public void test4_idml_p8() throws Exception {
        HWPXFile hwpx = IDMLToHwpxConverter.convertToHwpxFile(IDML,
                ConvertOptions.defaults().startPage(8).endPage(8).includeEquations(false));
        HWPXWriter.toFilepath(hwpx, OUT + "4_idml_p8.hwpx");
        System.out.println("4_idml_p8: " + new java.io.File(OUT + "4_idml_p8.hwpx").length());
    }

    // 5) IDML 1페이지 + 수동 SecPr 추가 (header는 동일, section만 확장)
    @Test
    public void test5_idml_1page_plus_secpr() throws Exception {
        HWPXFile hwpx = IDMLToHwpxConverter.convertToHwpxFile(IDML,
                ConvertOptions.defaults().startPage(8).endPage(8).includeEquations(false));
        SectionXMLFile section = hwpx.sectionXMLFileList().get(0);

        // 2번째 SecPr 단락 추가 (BlankFileMaker 스타일)
        Para secPara = section.addNewPara();
        secPara.idAnd("9000000001")
                .paraPrIDRefAnd("3").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run secRun = secPara.addNewRun();
        secRun.charPrIDRef("0");

        secRun.createSecPr();
        kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr secPr = secRun.secPr();
        secPr.idAnd("")
                .textDirectionAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.TextDirection.HORIZONTAL)
                .spaceColumnsAnd(1134).tabStopAnd(8000).tabStopValAnd(4000)
                .tabStopUnitAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit1.HWPUNIT)
                .outlineShapeIDRefAnd("1").memoShapeIDRefAnd("0").textVerticalWidthHeadAnd(false);
        secPr.createGrid();
        secPr.grid().lineGridAnd(0).charGridAnd(0).wonggojiFormat(false);
        secPr.createStartNum();
        secPr.startNum().pageStartsOnAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageStartON.BOTH)
                .pageAnd(0).picAnd(0).tblAnd(0).equation(0);
        secPr.createVisibility();
        secPr.visibility()
                .hideFirstHeaderAnd(false).hideFirstFooterAnd(false).hideFirstMasterPageAnd(false)
                .borderAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VisibilityOption.SHOW_ALL)
                .fillAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VisibilityOption.SHOW_ALL)
                .hideFirstPageNumAnd(false).hideFirstEmptyLineAnd(false).showLineNumber(false);
        secPr.createLineNumberShape();
        secPr.lineNumberShape().restartTypeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.LineNumberRestartType.Unknown)
                .countByAnd(0).distanceAnd(0).startNumber(0);
        secPr.createPagePr();
        secPr.pagePr().landscapeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageDirection.WIDELY)
                .widthAnd(59528).heightAnd(84188)
                .gutterType(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.GutterMethod.LEFT_ONLY);
        secPr.pagePr().createMargin();
        secPr.pagePr().margin().headerAnd(4252).footerAnd(4252).gutterAnd(0)
                .leftAnd(8504).rightAnd(8504).topAnd(5668).bottom(4252);
        secPr.createFootNotePr();
        secPr.footNotePr().createAutoNumFormat();
        secPr.footNotePr().autoNumFormat().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.NumberType2.DIGIT)
                .userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        secPr.footNotePr().createNoteLine();
        secPr.footNotePr().noteLine().lengthAnd(-1)
                .typeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2.SOLID)
                .widthAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth.MM_0_12).color("#000000");
        secPr.footNotePr().createNoteSpacing();
        secPr.footNotePr().noteSpacing().betweenNotesAnd(283).belowLineAnd(567).aboveLine(850);
        secPr.footNotePr().createNumbering();
        secPr.footNotePr().numbering().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FootNoteNumberingType.CONTINUOUS).newNum(1);
        secPr.footNotePr().createPlacement();
        secPr.footNotePr().placement().placeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FootNotePlace.EACH_COLUMN).beneathText(false);
        secPr.createEndNotePr();
        secPr.endNotePr().createAutoNumFormat();
        secPr.endNotePr().autoNumFormat().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.NumberType2.DIGIT)
                .userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        secPr.endNotePr().createNoteLine();
        secPr.endNotePr().noteLine().lengthAnd(14692344)
                .typeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2.SOLID)
                .widthAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth.MM_0_12).color("#000000");
        secPr.endNotePr().createNoteSpacing();
        secPr.endNotePr().noteSpacing().betweenNotesAnd(0).belowLineAnd(567).aboveLine(850);
        secPr.endNotePr().createNumbering();
        secPr.endNotePr().numbering().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EndNoteNumberingType.CONTINUOUS).newNum(1);
        secPr.endNotePr().createPlacement();
        secPr.endNotePr().placement().placeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EndNotePlace.END_OF_DOCUMENT).beneathText(false);

        kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.pageborder.PageBorderFill pbf;
        for (kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType apt :
                new kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType[]{
                        kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.BOTH,
                        kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.EVEN,
                        kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.ODD}) {
            pbf = secPr.addNewPageBorderFill();
            pbf.typeAnd(apt).borderFillIDRefAnd("1")
                    .textBorderAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageBorderPositionCriterion.PAPER)
                    .headerInsideAnd(false).footerInsideAnd(false)
                    .fillArea(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageFillArea.PAPER);
            pbf.createOffset();
            pbf.offset().leftAnd(1417L).rightAnd(1417L).topAnd(1417L).bottom(1417L);
        }

        kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl ctrl = secRun.addNewCtrl();
        ctrl.addNewColPr().idAnd("")
                .typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.MultiColumnType.NEWSPAPER)
                .layoutAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ColumnDirection.LEFT)
                .colCountAnd(1).sameSzAnd(true).sameGap(0);
        secRun.addNewT();

        // 간단한 텍스트 단락
        Para textPara = section.addNewPara();
        textPara.idAnd("9000000002")
                .paraPrIDRefAnd("3").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run textRun = textPara.addNewRun();
        textRun.charPrIDRef("0");
        textRun.addNewT().addText("2페이지 테스트 텍스트");

        String path = OUT + "5_idml_1p_plus_secpr.hwpx";
        HWPXWriter.toFilepath(hwpx, path);
        System.out.println("5_idml_1p_plus_secpr: " + new java.io.File(path).length());
    }

    // 7) pageBreak 동작 확인용 최소 테스트
    @Test
    public void test7_pageBreak_minimal() throws Exception {
        HWPXFile hwpx = BlankFileMaker.make();
        SectionXMLFile section = hwpx.sectionXMLFileList().get(0);

        // 기존 빈 단락 수정 - 페이지 1 텍스트
        Para p0 = section.getPara(0);
        p0.getRun(0).addNewT().addText("페이지 1 텍스트");

        // 페이지 2 - pageBreak=1 단락
        Para p1 = section.addNewPara();
        p1.idAnd("8000000001")
                .paraPrIDRefAnd("3").styleIDRefAnd("0")
                .pageBreakAnd(true).columnBreakAnd(false).merged(false);
        Run r1 = p1.addNewRun();
        r1.charPrIDRef("0");
        r1.addNewT().addText("페이지 2 텍스트");

        // 페이지 3 - pageBreak=1 단락
        Para p2 = section.addNewPara();
        p2.idAnd("8000000002")
                .paraPrIDRefAnd("3").styleIDRefAnd("0")
                .pageBreakAnd(true).columnBreakAnd(false).merged(false);
        Run r2 = p2.addNewRun();
        r2.charPrIDRef("0");
        r2.addNewT().addText("페이지 3 텍스트");

        HWPXWriter.toFilepath(hwpx, OUT + "7_pageBreak_text.hwpx");
        System.out.println("7_pageBreak_text: " + new java.io.File(OUT + "7_pageBreak_text.hwpx").length());
    }

    // 8) pageBreak + SecPr 패턴 테스트 (참조 파일 구조 모방)
    @Test
    public void test8_pageBreak_secpr_pattern() throws Exception {
        // 참조 파일 패턴: SecPr단락 + floating 객체 단락들 + pageBreak=1 SecPr단락 + floating 객체 단락들
        HWPXFile hwpx = BlankFileMaker.make();
        SectionXMLFile section = hwpx.sectionXMLFileList().get(0);
        section.removeAllParas();

        // === 페이지 1 ===
        // SecPr 단락 (pageBreak=0)
        {
            Para para = section.addNewPara();
            para.idAnd("7000000001").paraPrIDRefAnd("3").styleIDRefAnd("0")
                    .pageBreakAnd(false).columnBreakAnd(false).merged(false);
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.createSecPr();
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr secPr = run.secPr();
            secPr.idAnd("").textDirectionAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.TextDirection.HORIZONTAL)
                    .spaceColumnsAnd(1134).tabStopAnd(8000).tabStopValAnd(4000)
                    .tabStopUnitAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit1.HWPUNIT)
                    .outlineShapeIDRefAnd("1").memoShapeIDRefAnd("0").textVerticalWidthHeadAnd(false);
            secPr.createGrid(); secPr.grid().lineGridAnd(0).charGridAnd(0).wonggojiFormat(false);
            secPr.createStartNum(); secPr.startNum().pageStartsOnAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageStartON.BOTH).pageAnd(0).picAnd(0).tblAnd(0).equation(0);
            secPr.createVisibility(); secPr.visibility().hideFirstHeaderAnd(false).hideFirstFooterAnd(false).hideFirstMasterPageAnd(false).borderAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VisibilityOption.SHOW_ALL).fillAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VisibilityOption.SHOW_ALL).hideFirstPageNumAnd(false).hideFirstEmptyLineAnd(false).showLineNumber(false);
            secPr.createLineNumberShape(); secPr.lineNumberShape().restartTypeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.LineNumberRestartType.Unknown).countByAnd(0).distanceAnd(0).startNumber(0);
            secPr.createPagePr(); secPr.pagePr().landscapeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageDirection.WIDELY).widthAnd(59528).heightAnd(84188).gutterType(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.GutterMethod.LEFT_ONLY);
            secPr.pagePr().createMargin(); secPr.pagePr().margin().headerAnd(4252).footerAnd(4252).gutterAnd(0).leftAnd(8504).rightAnd(8504).topAnd(5668).bottom(4252);
            secPr.createFootNotePr(); secPr.footNotePr().createAutoNumFormat(); secPr.footNotePr().autoNumFormat().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
            secPr.footNotePr().createNoteLine(); secPr.footNotePr().noteLine().lengthAnd(-1).typeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2.SOLID).widthAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth.MM_0_12).color("#000000");
            secPr.footNotePr().createNoteSpacing(); secPr.footNotePr().noteSpacing().betweenNotesAnd(283).belowLineAnd(567).aboveLine(850);
            secPr.footNotePr().createNumbering(); secPr.footNotePr().numbering().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FootNoteNumberingType.CONTINUOUS).newNum(1);
            secPr.footNotePr().createPlacement(); secPr.footNotePr().placement().placeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FootNotePlace.EACH_COLUMN).beneathText(false);
            secPr.createEndNotePr(); secPr.endNotePr().createAutoNumFormat(); secPr.endNotePr().autoNumFormat().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
            secPr.endNotePr().createNoteLine(); secPr.endNotePr().noteLine().lengthAnd(14692344).typeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2.SOLID).widthAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth.MM_0_12).color("#000000");
            secPr.endNotePr().createNoteSpacing(); secPr.endNotePr().noteSpacing().betweenNotesAnd(0).belowLineAnd(567).aboveLine(850);
            secPr.endNotePr().createNumbering(); secPr.endNotePr().numbering().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EndNoteNumberingType.CONTINUOUS).newNum(1);
            secPr.endNotePr().createPlacement(); secPr.endNotePr().placement().placeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EndNotePlace.END_OF_DOCUMENT).beneathText(false);
            for (kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType apt : new kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType[]{kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.BOTH, kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.EVEN, kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.ODD}) {
                kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.pageborder.PageBorderFill pbf = secPr.addNewPageBorderFill();
                pbf.typeAnd(apt).borderFillIDRefAnd("1").textBorderAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageBorderPositionCriterion.PAPER).headerInsideAnd(false).footerInsideAnd(false).fillArea(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageFillArea.PAPER);
                pbf.createOffset(); pbf.offset().leftAnd(1417L).rightAnd(1417L).topAnd(1417L).bottom(1417L);
            }
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl ctrl = run.addNewCtrl();
            ctrl.addNewColPr().idAnd("").typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.MultiColumnType.NEWSPAPER).layoutAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ColumnDirection.LEFT).colCountAnd(1).sameSzAnd(true).sameGap(0);
            run.addNewT();
            para.createLineSegArray();
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg ls = para.lineSegArray().addNew();
            ls.textposAnd(0).vertposAnd(0).vertsizeAnd(1000).textheightAnd(1000).baselineAnd(850).spacingAnd(600).horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
        }

        // 페이지 1 텍스트 단락
        {
            Para para = section.addNewPara();
            para.idAnd("7000000002").paraPrIDRefAnd("3").styleIDRefAnd("0")
                    .pageBreakAnd(false).columnBreakAnd(false).merged(false);
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT().addText("페이지 1 - 텍스트 내용");
            para.createLineSegArray();
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg ls = para.lineSegArray().addNew();
            ls.textposAnd(0).vertposAnd(0).vertsizeAnd(1000).textheightAnd(1000).baselineAnd(850).spacingAnd(600).horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
        }

        // === 페이지 2 ===
        // SecPr 단락 (pageBreak=1)
        {
            Para para = section.addNewPara();
            para.idAnd("7000000003").paraPrIDRefAnd("3").styleIDRefAnd("0")
                    .pageBreakAnd(true).columnBreakAnd(false).merged(false);
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.createSecPr();
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr secPr = run.secPr();
            secPr.idAnd("").textDirectionAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.TextDirection.HORIZONTAL)
                    .spaceColumnsAnd(1134).tabStopAnd(8000).tabStopValAnd(4000)
                    .tabStopUnitAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit1.HWPUNIT)
                    .outlineShapeIDRefAnd("1").memoShapeIDRefAnd("0").textVerticalWidthHeadAnd(false);
            secPr.createGrid(); secPr.grid().lineGridAnd(0).charGridAnd(0).wonggojiFormat(false);
            secPr.createStartNum(); secPr.startNum().pageStartsOnAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageStartON.BOTH).pageAnd(0).picAnd(0).tblAnd(0).equation(0);
            secPr.createVisibility(); secPr.visibility().hideFirstHeaderAnd(false).hideFirstFooterAnd(false).hideFirstMasterPageAnd(false).borderAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VisibilityOption.SHOW_ALL).fillAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VisibilityOption.SHOW_ALL).hideFirstPageNumAnd(false).hideFirstEmptyLineAnd(false).showLineNumber(false);
            secPr.createLineNumberShape(); secPr.lineNumberShape().restartTypeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.LineNumberRestartType.Unknown).countByAnd(0).distanceAnd(0).startNumber(0);
            secPr.createPagePr(); secPr.pagePr().landscapeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageDirection.WIDELY).widthAnd(59528).heightAnd(84188).gutterType(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.GutterMethod.LEFT_ONLY);
            secPr.pagePr().createMargin(); secPr.pagePr().margin().headerAnd(4252).footerAnd(4252).gutterAnd(0).leftAnd(8504).rightAnd(8504).topAnd(5668).bottom(4252);
            secPr.createFootNotePr(); secPr.footNotePr().createAutoNumFormat(); secPr.footNotePr().autoNumFormat().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
            secPr.footNotePr().createNoteLine(); secPr.footNotePr().noteLine().lengthAnd(-1).typeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2.SOLID).widthAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth.MM_0_12).color("#000000");
            secPr.footNotePr().createNoteSpacing(); secPr.footNotePr().noteSpacing().betweenNotesAnd(283).belowLineAnd(567).aboveLine(850);
            secPr.footNotePr().createNumbering(); secPr.footNotePr().numbering().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FootNoteNumberingType.CONTINUOUS).newNum(1);
            secPr.footNotePr().createPlacement(); secPr.footNotePr().placement().placeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FootNotePlace.EACH_COLUMN).beneathText(false);
            secPr.createEndNotePr(); secPr.endNotePr().createAutoNumFormat(); secPr.endNotePr().autoNumFormat().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.NumberType2.DIGIT).userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
            secPr.endNotePr().createNoteLine(); secPr.endNotePr().noteLine().lengthAnd(14692344).typeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2.SOLID).widthAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth.MM_0_12).color("#000000");
            secPr.endNotePr().createNoteSpacing(); secPr.endNotePr().noteSpacing().betweenNotesAnd(0).belowLineAnd(567).aboveLine(850);
            secPr.endNotePr().createNumbering(); secPr.endNotePr().numbering().typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EndNoteNumberingType.CONTINUOUS).newNum(1);
            secPr.endNotePr().createPlacement(); secPr.endNotePr().placement().placeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EndNotePlace.END_OF_DOCUMENT).beneathText(false);
            for (kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType apt : new kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType[]{kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.BOTH, kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.EVEN, kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ApplyPageType.ODD}) {
                kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.pageborder.PageBorderFill pbf = secPr.addNewPageBorderFill();
                pbf.typeAnd(apt).borderFillIDRefAnd("1").textBorderAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageBorderPositionCriterion.PAPER).headerInsideAnd(false).footerInsideAnd(false).fillArea(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.PageFillArea.PAPER);
                pbf.createOffset(); pbf.offset().leftAnd(1417L).rightAnd(1417L).topAnd(1417L).bottom(1417L);
            }
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl ctrl = run.addNewCtrl();
            ctrl.addNewColPr().idAnd("").typeAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.MultiColumnType.NEWSPAPER).layoutAnd(kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.ColumnDirection.LEFT).colCountAnd(1).sameSzAnd(true).sameGap(0);
            run.addNewT();
            para.createLineSegArray();
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg ls = para.lineSegArray().addNew();
            ls.textposAnd(0).vertposAnd(0).vertsizeAnd(1000).textheightAnd(1000).baselineAnd(850).spacingAnd(600).horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
        }

        // 페이지 2 텍스트 단락
        {
            Para para = section.addNewPara();
            para.idAnd("7000000004").paraPrIDRefAnd("3").styleIDRefAnd("0")
                    .pageBreakAnd(false).columnBreakAnd(false).merged(false);
            Run run = para.addNewRun();
            run.charPrIDRef("0");
            run.addNewT().addText("페이지 2 - 텍스트 내용");
            para.createLineSegArray();
            kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg ls = para.lineSegArray().addNew();
            ls.textposAnd(0).vertposAnd(0).vertsizeAnd(1000).textheightAnd(1000).baselineAnd(850).spacingAnd(600).horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
        }

        HWPXWriter.toFilepath(hwpx, OUT + "8_pageBreak_secpr.hwpx");
        System.out.println("8_pageBreak_secpr: " + new java.io.File(OUT + "8_pageBreak_secpr.hwpx").length());
    }

    // 10) IDML 변환 - 1~20페이지
    @Test
    public void test10_idml_p10_14() throws Exception {
        ConvertResult result = IDMLToHwpxConverter.convert(IDML, OUT + "10_idml_p10_14.hwpx",
                ConvertOptions.defaults().startPage(1).endPage(20));
        System.out.println("10_idml_p10_14: " + new java.io.File(OUT + "10_idml_p10_14.hwpx").length());
        System.out.println("  " + result.summary());
        if (result.hasWarnings()) {
            System.out.println("  Warnings:");
            for (String w : result.warnings()) {
                System.out.println("    - " + w);
            }
        }
    }

    // 13) 중등국어교과서 IDML 변환 테스트
    @Test
    public void test13_korean_textbook() throws Exception {
        String idmlPath = HOME + "/Documents/중등국어교과서 2-1학년_1단원(008~023)OK.idml";
        java.io.File idmlFile = new java.io.File(idmlPath);
        if (!idmlFile.exists()) {
            System.out.println("Skipping test13: IDML file not found at " + idmlPath);
            return;
        }
        String outPath = OUT + "korean_textbook.hwpx";
        ConvertResult result = IDMLToHwpxConverter.convert(idmlPath, outPath,
                ConvertOptions.defaults());
        System.out.println("korean_textbook: " + new java.io.File(outPath).length());
        System.out.println("  " + result.summary());
        if (result.hasWarnings()) {
            System.out.println("  Warnings:");
            for (String w : result.warnings()) {
                System.out.println("    - " + w);
            }
        }
    }

    // 6) IDML 2페이지 - 2번째 페이지 단락을 N개만 남기는 테스트
    @Test
    public void test6_trim_page2() throws Exception {
        // 2번째 페이지의 단락을 점점 늘려가며 테스트
        // SecPr 단락(idx=8) 이후 단락을 0, 1, 3, 5, 7개만 남김
        int[] keepCounts = {0, 1, 3, 4, 5, 7};
        for (int keep : keepCounts) {
            HWPXFile hwpx = IDMLToHwpxConverter.convertToHwpxFile(IDML,
                    ConvertOptions.defaults().startPage(8).endPage(9).includeEquations(false));
            SectionXMLFile section = hwpx.sectionXMLFileList().get(0);

            // 2번째 SecPr 찾기 (첫번째 이후)
            int secondSecPrIdx = -1;
            int secPrCount = 0;
            for (int i = 0; i < section.countOfPara(); i++) {
                Para p = section.getPara(i);
                if (p.getRun(0) != null && p.getRun(0).secPr() != null) {
                    secPrCount++;
                    if (secPrCount == 2) {
                        secondSecPrIdx = i;
                        break;
                    }
                }
            }

            if (secondSecPrIdx >= 0) {
                // SecPr 이후 keep개만 남기고 삭제
                int firstContentIdx = secondSecPrIdx + 1;
                int totalAfterSecPr = section.countOfPara() - firstContentIdx;
                int toRemove = totalAfterSecPr - keep;
                for (int r = 0; r < toRemove; r++) {
                    section.removePara(section.countOfPara() - 1);
                }
            }

            String name = String.format("6_%d_paras.hwpx", keep);
            String path = OUT + name;
            HWPXWriter.toFilepath(hwpx, path);
            System.out.println(name + ": paras=" + section.countOfPara()
                    + " size=" + new java.io.File(path).length());
        }
    }
}
