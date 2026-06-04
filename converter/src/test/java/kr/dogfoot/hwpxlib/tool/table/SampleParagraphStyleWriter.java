package kr.dogfoot.hwpxlib.tool.table;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.TabPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

/**
 * 단락스타일(ParaPr)의 다양한 옵션을 7페이지에 걸쳐 시연하는 샘플 HWPX 생성.
 *
 * <ul>
 *   <li>Page 1: 수평 정렬 (Horizontal Alignment)</li>
 *   <li>Page 2: 줄간격 (Line Spacing)</li>
 *   <li>Page 3: 들여쓰기와 여백 (Indents &amp; Margins)</li>
 *   <li>Page 4: 탭 (Tab Stops)</li>
 *   <li>Page 5: 개요와 글머리기호 (Outline &amp; Bullets)</li>
 *   <li>Page 6: 줄 바꿈과 페이지 나누기 (Break Settings)</li>
 *   <li>Page 7: 기타 옵션 (Other Options)</li>
 * </ul>
 */
public class SampleParagraphStyleWriter {

    private int paraPrIdCounter = 16;   // BlankFileMaker uses 0-15
    private int tabPrIdCounter = 2;     // BlankFileMaker uses 0-1
    private int borderFillIdCounter = 3; // BlankFileMaker uses 1-2
    private int paraIdCounter = 0;

    @Test
    public void createParagraphStyleShowcase() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        page1HorizontalAlignment(hwpxFile, section);
        page2LineSpacing(hwpxFile, section);
        page3IndentsAndMargins(hwpxFile, section);
        page4TabStops(hwpxFile, section);
        page5OutlineAndBullets(hwpxFile, section);
        page6BreakSettings(hwpxFile, section);
        page7OtherOptions(hwpxFile, section);

        String filepath = "testFile/tool/sample_paragraph_styles.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Paragraph Style Showcase Result ===");
        System.out.println("File: " + filepath);
        System.out.println("Paragraphs: " + section.countOfPara());

        // 라운드트립 검증
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs",
                readSection.countOfPara() > 50);

        System.out.println("Round-trip verification passed!");
        System.out.println("Read-back paragraphs: " + readSection.countOfPara());
    }

    // ── Page 1: 수평 정렬 ──

    private void page1HorizontalAlignment(HWPXFile hwpxFile, SectionXMLFile section) {
        ObjectList<ParaPr> pps = hwpxFile.headerXMLFile().refList().paraProperties();

        addTitlePara(section, "1. 수평 정렬 (Horizontal Alignment)", false);

        HorizontalAlign2[] aligns = {
                HorizontalAlign2.LEFT, HorizontalAlign2.CENTER, HorizontalAlign2.RIGHT,
                HorizontalAlign2.JUSTIFY, HorizontalAlign2.DISTRIBUTE, HorizontalAlign2.DISTRIBUTE_SPACE
        };
        String[] names = {
                "왼쪽 정렬 (LEFT)", "가운데 정렬 (CENTER)", "오른쪽 정렬 (RIGHT)",
                "양쪽 정렬 (JUSTIFY)", "배분 정렬 (DISTRIBUTE)", "나눔 정렬 (DISTRIBUTE_SPACE)"
        };
        String sampleText = "대한민국 헌법 제1조: 대한민국은 민주공화국이다. "
                + "대한민국의 주권은 국민에게 있고, 모든 권력은 국민으로부터 나온다. "
                + "이 문장은 정렬 효과를 확인하기 위해 충분한 길이로 작성되었습니다.";

        for (int i = 0; i < aligns.length; i++) {
            ParaPr pp = createDefaultParaPr(pps);
            pp.align().horizontal(aligns[i]);

            addSubTitlePara(section, "▶ " + names[i]);
            addPara(section, pp.id(), sampleText, false);
            addBlankPara(section);
        }
    }

    // ── Page 2: 줄간격 ──

    private void page2LineSpacing(HWPXFile hwpxFile, SectionXMLFile section) {
        ObjectList<ParaPr> pps = hwpxFile.headerXMLFile().refList().paraProperties();

        addTitlePara(section, "2. 줄간격 (Line Spacing)", true);

        String sampleText = "이 문단은 줄간격 설정을 시연합니다. "
                + "줄과 줄 사이의 간격이 어떻게 변하는지 확인하세요. "
                + "여러 줄에 걸쳐 표시되어야 차이를 알 수 있습니다.";

        // PERCENT 변형
        int[] percentValues = {100, 130, 160, 200};
        for (int pv : percentValues) {
            ParaPr pp = createDefaultParaPr(pps);
            pp.lineSpacing().typeAnd(LineSpacingType.PERCENT).valueAnd(pv).unit(ValueUnit2.HWPUNIT);

            addSubTitlePara(section, "▶ 글자에 따라 (PERCENT) " + pv + "%");
            addPara(section, pp.id(), sampleText, false);
            addBlankPara(section);
        }

        // FIXED
        ParaPr ppFixed = createDefaultParaPr(pps);
        ppFixed.lineSpacing().typeAnd(LineSpacingType.FIXED).valueAnd(600).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 고정 값 (FIXED) 600 hwpunit");
        addPara(section, ppFixed.id(), sampleText, false);
        addBlankPara(section);

        // BETWEEN_LINES
        ParaPr ppBetween = createDefaultParaPr(pps);
        ppBetween.lineSpacing().typeAnd(LineSpacingType.BETWEEN_LINES).valueAnd(300).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 여백만 지정 (BETWEEN_LINES) 300 hwpunit");
        addPara(section, ppBetween.id(), sampleText, false);
        addBlankPara(section);

        // AT_LEAST
        ParaPr ppAtLeast = createDefaultParaPr(pps);
        ppAtLeast.lineSpacing().typeAnd(LineSpacingType.AT_LEAST).valueAnd(400).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 최소 (AT_LEAST) 400 hwpunit");
        addPara(section, ppAtLeast.id(), sampleText, false);
    }

    // ── Page 3: 들여쓰기와 여백 ──

    private void page3IndentsAndMargins(HWPXFile hwpxFile, SectionXMLFile section) {
        ObjectList<ParaPr> pps = hwpxFile.headerXMLFile().refList().paraProperties();

        addTitlePara(section, "3. 들여쓰기와 여백 (Indents & Margins)", true);

        String longText = "이 문단은 들여쓰기와 여백을 시연하기 위한 예제입니다. "
                + "충분히 긴 텍스트가 필요하므로 여러 줄에 걸쳐 표시됩니다. "
                + "문단의 첫 줄 들여쓰기, 내어쓰기, 왼쪽/오른쪽 여백, "
                + "문단 앞/뒤 간격 등을 확인할 수 있습니다.";

        // 첫 줄 들여쓰기
        ParaPr ppIndent = createDefaultParaPr(pps);
        ppIndent.margin().intent().valueAnd(800).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 첫 줄 들여쓰기 (intent=800)");
        addPara(section, ppIndent.id(), longText, false);
        addBlankPara(section);

        // 내어쓰기 (Hanging indent)
        ParaPr ppHanging = createDefaultParaPr(pps);
        ppHanging.margin().intent().valueAnd(-800).unit(ValueUnit2.HWPUNIT);
        ppHanging.margin().left().valueAnd(800).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 내어쓰기 (intent=-800, left=800)");
        addPara(section, ppHanging.id(), longText, false);
        addBlankPara(section);

        // 왼쪽 여백
        ParaPr ppLeft = createDefaultParaPr(pps);
        ppLeft.margin().left().valueAnd(2000).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 왼쪽 여백 (left=2000)");
        addPara(section, ppLeft.id(), longText, false);
        addBlankPara(section);

        // 오른쪽 여백
        ParaPr ppRight = createDefaultParaPr(pps);
        ppRight.margin().right().valueAnd(2000).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 오른쪽 여백 (right=2000)");
        addPara(section, ppRight.id(), longText, false);
        addBlankPara(section);

        // 문단 앞/뒤 간격
        ParaPr ppSpacing = createDefaultParaPr(pps);
        ppSpacing.margin().prev().valueAnd(800).unit(ValueUnit2.HWPUNIT);
        ppSpacing.margin().next().valueAnd(800).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 문단 앞/뒤 간격 (prev=800, next=800)");
        addPara(section, ppSpacing.id(),
                "이 문단은 위아래에 넓은 간격이 있습니다.", false);
        addPara(section, ppSpacing.id(),
                "이 문단도 같은 설정이므로 위아래 간격이 동일합니다.", false);
        addBlankPara(section);

        // 복합 여백
        ParaPr ppCombined = createDefaultParaPr(pps);
        ppCombined.margin().intent().valueAnd(500).unit(ValueUnit2.HWPUNIT);
        ppCombined.margin().left().valueAnd(1500).unit(ValueUnit2.HWPUNIT);
        ppCombined.margin().right().valueAnd(1500).unit(ValueUnit2.HWPUNIT);
        ppCombined.margin().prev().valueAnd(400).unit(ValueUnit2.HWPUNIT);
        ppCombined.margin().next().valueAnd(400).unit(ValueUnit2.HWPUNIT);
        addSubTitlePara(section, "▶ 복합 여백 (indent=500, left/right=1500, prev/next=400)");
        addPara(section, ppCombined.id(), longText, false);
    }

    // ── Page 4: 탭 ──

    private void page4TabStops(HWPXFile hwpxFile, SectionXMLFile section) {
        ObjectList<ParaPr> pps = hwpxFile.headerXMLFile().refList().paraProperties();
        ObjectList<TabPr> tps = hwpxFile.headerXMLFile().refList().tabProperties();

        addTitlePara(section, "4. 탭 (Tab Stops)", true);

        // 왼쪽 탭
        TabPr tp1 = tps.addNew();
        tp1.idAnd(nextTabPrId()).autoTabLeftAnd(false).autoTabRightAnd(false);
        tp1.addNewTabItem().posAnd(8000).typeAnd(TabItemType.LEFT)
                .leaderAnd(LineType2.NONE).unitAnd(ValueUnit2.HWPUNIT);

        ParaPr pp1 = createDefaultParaPr(pps);
        pp1.tabPrIDRef(tp1.id());
        addSubTitlePara(section, "▶ 왼쪽 탭 (LEFT) at 8000");
        addParaWithTabs(section, pp1.id(), new String[]{"항목", "왼쪽 탭 위치에 정렬됩니다"});
        addParaWithTabs(section, pp1.id(), new String[]{"이름", "홍길동"});
        addParaWithTabs(section, pp1.id(), new String[]{"날짜", "2026년 1월 31일"});
        addBlankPara(section);

        // 가운데 탭
        TabPr tp2 = tps.addNew();
        tp2.idAnd(nextTabPrId()).autoTabLeftAnd(false).autoTabRightAnd(false);
        tp2.addNewTabItem().posAnd(21260).typeAnd(TabItemType.CENTER)
                .leaderAnd(LineType2.NONE).unitAnd(ValueUnit2.HWPUNIT);

        ParaPr pp2 = createDefaultParaPr(pps);
        pp2.tabPrIDRef(tp2.id());
        addSubTitlePara(section, "▶ 가운데 탭 (CENTER) at 21260");
        addParaWithTabs(section, pp2.id(), new String[]{"", "가운데 정렬 텍스트"});
        addParaWithTabs(section, pp2.id(), new String[]{"", "제목"});
        addBlankPara(section);

        // 오른쪽 탭
        TabPr tp3 = tps.addNew();
        tp3.idAnd(nextTabPrId()).autoTabLeftAnd(false).autoTabRightAnd(false);
        tp3.addNewTabItem().posAnd(42520).typeAnd(TabItemType.RIGHT)
                .leaderAnd(LineType2.NONE).unitAnd(ValueUnit2.HWPUNIT);

        ParaPr pp3 = createDefaultParaPr(pps);
        pp3.tabPrIDRef(tp3.id());
        addSubTitlePara(section, "▶ 오른쪽 탭 (RIGHT) at 42520");
        addParaWithTabs(section, pp3.id(), new String[]{"왼쪽 텍스트", "오른쪽 정렬"});
        addParaWithTabs(section, pp3.id(), new String[]{"이름", "페이지 번호"});
        addBlankPara(section);

        // 소수점 탭
        TabPr tp4 = tps.addNew();
        tp4.idAnd(nextTabPrId()).autoTabLeftAnd(false).autoTabRightAnd(false);
        tp4.addNewTabItem().posAnd(25000).typeAnd(TabItemType.DECIMAL)
                .leaderAnd(LineType2.NONE).unitAnd(ValueUnit2.HWPUNIT);

        ParaPr pp4 = createDefaultParaPr(pps);
        pp4.tabPrIDRef(tp4.id());
        addSubTitlePara(section, "▶ 소수점 탭 (DECIMAL) at 25000");
        addParaWithTabs(section, pp4.id(), new String[]{"가격", "1,234.56"});
        addParaWithTabs(section, pp4.id(), new String[]{"합계", "99.9"});
        addParaWithTabs(section, pp4.id(), new String[]{"평균", "567.123"});
        addBlankPara(section);

        // 점선 채움 (DOT leader)
        TabPr tp5 = tps.addNew();
        tp5.idAnd(nextTabPrId()).autoTabLeftAnd(false).autoTabRightAnd(false);
        tp5.addNewTabItem().posAnd(35000).typeAnd(TabItemType.RIGHT)
                .leaderAnd(LineType2.DOT).unitAnd(ValueUnit2.HWPUNIT);

        ParaPr pp5 = createDefaultParaPr(pps);
        pp5.tabPrIDRef(tp5.id());
        addSubTitlePara(section, "▶ 탭 채움선 - 점선 (DOT leader)");
        addParaWithTabs(section, pp5.id(), new String[]{"제1장 서론", "1"});
        addParaWithTabs(section, pp5.id(), new String[]{"제2장 본론", "15"});
        addParaWithTabs(section, pp5.id(), new String[]{"제3장 결론", "42"});
        addBlankPara(section);

        // 실선 채움 (SOLID leader)
        TabPr tp6 = tps.addNew();
        tp6.idAnd(nextTabPrId()).autoTabLeftAnd(false).autoTabRightAnd(false);
        tp6.addNewTabItem().posAnd(35000).typeAnd(TabItemType.RIGHT)
                .leaderAnd(LineType2.SOLID).unitAnd(ValueUnit2.HWPUNIT);

        ParaPr pp6 = createDefaultParaPr(pps);
        pp6.tabPrIDRef(tp6.id());
        addSubTitlePara(section, "▶ 탭 채움선 - 실선 (SOLID leader)");
        addParaWithTabs(section, pp6.id(), new String[]{"이름", "서명"});
        addBlankPara(section);

        // 긴 점선 채움 (DASH leader)
        TabPr tp7 = tps.addNew();
        tp7.idAnd(nextTabPrId()).autoTabLeftAnd(false).autoTabRightAnd(false);
        tp7.addNewTabItem().posAnd(35000).typeAnd(TabItemType.RIGHT)
                .leaderAnd(LineType2.DASH).unitAnd(ValueUnit2.HWPUNIT);

        ParaPr pp7 = createDefaultParaPr(pps);
        pp7.tabPrIDRef(tp7.id());
        addSubTitlePara(section, "▶ 탭 채움선 - 긴 점선 (DASH leader)");
        addParaWithTabs(section, pp7.id(), new String[]{"참고 문헌", "56"});
    }

    // ── Page 5: 개요와 글머리기호 ──

    private void page5OutlineAndBullets(HWPXFile hwpxFile, SectionXMLFile section) {
        ObjectList<ParaPr> pps = hwpxFile.headerXMLFile().refList().paraProperties();

        addTitlePara(section, "5. 개요와 글머리기호 (Outline & Bullets)", true);

        // 기존 개요 수준 1-7 활용 (ParaPr ID "10"=level0, "9"=level1, ..., "4"=level6)
        addSubTitlePara(section, "▶ 개요 수준 (Outline Levels 1-7)");
        String[] outlineIds = {"10", "9", "8", "7", "6", "5", "4"};
        for (int i = 0; i < outlineIds.length; i++) {
            addPara(section, outlineIds[i],
                    "개요 " + (i + 1) + "수준 - 이 문단은 개요 수준 " + (i + 1) + "입니다", false);
        }
        addBlankPara(section);

        // NUMBER heading
        addSubTitlePara(section, "▶ 번호 문단 (NUMBER heading)");
        for (byte level = 0; level < 3; level++) {
            ParaPr ppNum = createDefaultParaPr(pps);
            ppNum.heading().typeAnd(ParaHeadingType.NUMBER).idRefAnd("1").level(level);
            ppNum.margin().left().valueAnd(800 * (level + 1)).unit(ValueUnit2.HWPUNIT);
            addPara(section, ppNum.id(),
                    "번호 문단 수준 " + (level + 1) + " - 이 문단에 번호가 자동으로 매겨집니다", false);
        }
        addBlankPara(section);

        // BULLET heading
        addSubTitlePara(section, "▶ 글머리표 문단 (BULLET heading)");
        ParaPr ppBullet = createDefaultParaPr(pps);
        ppBullet.heading().typeAnd(ParaHeadingType.BULLET).idRefAnd("1").level((byte) 0);
        ppBullet.margin().left().valueAnd(800).unit(ValueUnit2.HWPUNIT);
        addPara(section, ppBullet.id(), "글머리표 첫 번째 항목입니다.", false);
        addPara(section, ppBullet.id(), "글머리표 두 번째 항목입니다.", false);
        addPara(section, ppBullet.id(), "글머리표 세 번째 항목입니다.", false);
    }

    // ── Page 6: 줄 바꿈과 페이지 나누기 ──

    private void page6BreakSettings(HWPXFile hwpxFile, SectionXMLFile section) {
        ObjectList<ParaPr> pps = hwpxFile.headerXMLFile().refList().paraProperties();

        addTitlePara(section, "6. 줄 바꿈과 페이지 나누기 (Break Settings)", true);

        String longText = "이 문단은 줄 바꿈과 페이지 나누기 설정을 시연합니다. "
                + "충분한 길이의 텍스트가 필요합니다. 문단 보호, 다음 문단과 함께, "
                + "외톨이줄 보호 등의 옵션을 확인할 수 있습니다. "
                + "한글 문서에서 이러한 설정은 문서의 가독성을 높이는 데 중요합니다.";

        // 외톨이줄 보호
        ParaPr ppWO = createDefaultParaPr(pps);
        ppWO.breakSetting().widowOrphan(true);
        addSubTitlePara(section, "▶ 외톨이줄 보호 (widowOrphan=true)");
        addPara(section, ppWO.id(), longText, false);
        addBlankPara(section);

        // 다음 문단과 함께
        ParaPr ppKWN = createDefaultParaPr(pps);
        ppKWN.breakSetting().keepWithNext(true);
        addSubTitlePara(section, "▶ 다음 문단과 함께 (keepWithNext=true)");
        addPara(section, ppKWN.id(),
                "이 문단은 다음 문단과 같은 페이지에 유지됩니다.", false);
        addPara(section, "0", "← 위 문단과 함께 유지됩니다.", false);
        addBlankPara(section);

        // 문단 보호
        ParaPr ppKL = createDefaultParaPr(pps);
        ppKL.breakSetting().keepLines(true);
        addSubTitlePara(section, "▶ 문단 보호 (keepLines=true)");
        addPara(section, ppKL.id(), longText, false);
        addBlankPara(section);

        // 문단 앞에서 쪽 나눔
        ParaPr ppPBB = createDefaultParaPr(pps);
        ppPBB.breakSetting().pageBreakBefore(true);
        addSubTitlePara(section, "▶ 문단 앞에서 항상 쪽 나눔 (pageBreakBefore=true)");
        addPara(section, ppPBB.id(),
                "이 문단은 항상 새 페이지에서 시작됩니다.", false);
        addBlankPara(section);

        // 줄 바꿈 모드
        LineWrap[] wraps = {LineWrap.BREAK, LineWrap.SQUEEZE, LineWrap.KEEP};
        String[] wrapNames = {"일반 줄 바꿈 (BREAK)", "자간 조절 (SQUEEZE)", "폭 늘어남 (KEEP)"};
        for (int i = 0; i < wraps.length; i++) {
            ParaPr ppWrap = createDefaultParaPr(pps);
            ppWrap.breakSetting().lineWrap(wraps[i]);
            addSubTitlePara(section, "▶ " + wrapNames[i]);
            addPara(section, ppWrap.id(),
                    "줄 바꿈 모드: " + wrapNames[i] + " - 한 줄의 끝에서 동작 방식을 결정합니다.", false);
        }
        addBlankPara(section);

        // 영어 줄나눔
        LineBreakForLatin[] latins = {
                LineBreakForLatin.KEEP_WORD, LineBreakForLatin.HYPHENATION, LineBreakForLatin.BREAK_WORD
        };
        String[] latinNames = {"단어 유지 (KEEP_WORD)", "하이픈 (HYPHENATION)", "글자 단위 (BREAK_WORD)"};
        for (int i = 0; i < latins.length; i++) {
            ParaPr ppLatin = createDefaultParaPr(pps);
            ppLatin.breakSetting().breakLatinWord(latins[i]);
            addSubTitlePara(section, "▶ 영어 줄나눔: " + latinNames[i]);
            addPara(section, ppLatin.id(),
                    "This is a demonstration of Latin word breaking with supercalifragilisticexpialidocious "
                            + "and other extraordinarily long English words that may need breaking.", false);
        }
        addBlankPara(section);

        // 한글 줄나눔
        LineBreakForNonLatin[] nonLatins = {
                LineBreakForNonLatin.KEEP_WORD, LineBreakForNonLatin.BREAK_WORD
        };
        String[] nonLatinNames = {"어절 유지 (KEEP_WORD)", "글자 단위 (BREAK_WORD)"};
        for (int i = 0; i < nonLatins.length; i++) {
            ParaPr ppNL = createDefaultParaPr(pps);
            ppNL.breakSetting().breakNonLatinWord(nonLatins[i]);
            addSubTitlePara(section, "▶ 한글 줄나눔: " + nonLatinNames[i]);
            addPara(section, ppNL.id(), longText, false);
        }
    }

    // ── Page 7: 기타 옵션 ──

    private void page7OtherOptions(HWPXFile hwpxFile, SectionXMLFile section) {
        ObjectList<ParaPr> pps = hwpxFile.headerXMLFile().refList().paraProperties();
        ObjectList<BorderFill> bfs = hwpxFile.headerXMLFile().refList().borderFills();

        addTitlePara(section, "7. 기타 옵션 (Other Options)", true);

        // 자동 간격
        addSubTitlePara(section, "▶ 자동 간격 조정 (AutoSpacing)");

        ParaPr ppAS1 = createDefaultParaPr(pps);
        ppAS1.autoSpacing().eAsianEng(true);
        ppAS1.autoSpacing().eAsianNum(true);
        addPara(section, ppAS1.id(),
                "한글English혼합 텍스트에서 자동간격 ON. 숫자123도 자동간격 적용.", false);

        ParaPr ppAS2 = createDefaultParaPr(pps);
        ppAS2.autoSpacing().eAsianEng(false);
        ppAS2.autoSpacing().eAsianNum(false);
        addPara(section, ppAS2.id(),
                "한글English혼합 텍스트에서 자동간격 OFF. 숫자123도 간격없음.", false);
        addBlankPara(section);

        // 공백 최소값 (Condense)
        addSubTitlePara(section, "▶ 공백 최소값 (Condense)");
        byte[] condenseValues = {0, 25, 50, 75};
        for (byte cv : condenseValues) {
            ParaPr ppC = createDefaultParaPr(pps);
            ppC.condense(cv);
            addPara(section, ppC.id(),
                    "공백 최소값 " + cv + "% 설정. 공백 문자의 최소 너비가 제한됩니다. "
                            + "이 문장에서 공백의 차이를 확인하세요.", false);
        }
        addBlankPara(section);

        // 글꼴에 어울리는 줄 높이
        addSubTitlePara(section, "▶ 글꼴에 어울리는 줄 높이 (fontLineHeight)");
        ParaPr ppFLH1 = createDefaultParaPr(pps);
        ppFLH1.fontLineHeight(true);
        addPara(section, ppFLH1.id(),
                "fontLineHeight=true: 글꼴에 어울리는 줄 높이가 적용됩니다.", false);

        ParaPr ppFLH2 = createDefaultParaPr(pps);
        ppFLH2.fontLineHeight(false);
        addPara(section, ppFLH2.id(),
                "fontLineHeight=false: 글꼴에 어울리는 줄 높이가 적용되지 않습니다.", false);
        addBlankPara(section);

        // 줄 격자
        addSubTitlePara(section, "▶ 편집 용지의 줄 격자 사용 (snapToGrid)");
        ParaPr ppSG1 = createDefaultParaPr(pps);
        ppSG1.snapToGrid(true);
        addPara(section, ppSG1.id(),
                "snapToGrid=true: 편집 용지의 줄 격자에 맞춥니다.", false);

        ParaPr ppSG2 = createDefaultParaPr(pps);
        ppSG2.snapToGrid(false);
        addPara(section, ppSG2.id(),
                "snapToGrid=false: 줄 격자를 무시합니다.", false);
        addBlankPara(section);

        // 문단 테두리
        addSubTitlePara(section, "▶ 문단 테두리 (ParaBorder)");

        // 새 BorderFill 생성 (실선 테두리)
        String bfId = nextBorderFillId();
        BorderFill bf = bfs.addNew();
        bf.idAnd(bfId).threeDAnd(false).shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE).breakCellSeparateLineAnd(false);
        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounterAnd(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounterAnd(false);
        bf.createLeftBorder();
        bf.leftBorder().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_4).colorAnd("#000000");
        bf.createRightBorder();
        bf.rightBorder().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_4).colorAnd("#000000");
        bf.createTopBorder();
        bf.topBorder().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_4).colorAnd("#000000");
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(LineType2.SOLID).widthAnd(LineWidth.MM_0_4).colorAnd("#000000");

        ParaPr ppBorder = createDefaultParaPr(pps);
        ppBorder.border().borderFillIDRefAnd(bfId)
                .offsetLeftAnd(200).offsetRightAnd(200)
                .offsetTopAnd(100).offsetBottomAnd(100)
                .connectAnd(false).ignoreMargin(false);
        addPara(section, ppBorder.id(),
                "이 문단에는 실선 테두리가 적용되어 있습니다. "
                        + "테두리와 텍스트 사이에 간격이 있습니다.", false);
        addBlankPara(section);

        // 테두리 연결
        ParaPr ppBorderConn = createDefaultParaPr(pps);
        ppBorderConn.border().borderFillIDRefAnd(bfId)
                .offsetLeftAnd(200).offsetRightAnd(200)
                .offsetTopAnd(100).offsetBottomAnd(100)
                .connectAnd(true).ignoreMargin(false);
        addPara(section, ppBorderConn.id(),
                "이 문단의 테두리는 연결(connect=true) 설정입니다.", false);
        addPara(section, ppBorderConn.id(),
                "같은 설정의 다음 문단과 테두리가 연결됩니다.", false);
        addBlankPara(section);

        // 여백 무시
        ParaPr ppBorderIM = createDefaultParaPr(pps);
        ppBorderIM.border().borderFillIDRefAnd(bfId)
                .offsetLeftAnd(200).offsetRightAnd(200)
                .offsetTopAnd(100).offsetBottomAnd(100)
                .connectAnd(false).ignoreMargin(true);
        addPara(section, ppBorderIM.id(),
                "이 문단의 테두리는 여백 무시(ignoreMargin=true) 설정입니다.", false);
    }

    // ── 헬퍼 메서드 ──

    /**
     * 기본 ParaPr을 생성한다.
     * ForParaProperties.java의 paraPr1 패턴을 따르되, Switch/Case 없이 직접 설정.
     */
    private ParaPr createDefaultParaPr(ObjectList<ParaPr> pps) {
        ParaPr pp = pps.addNew();
        pp.idAnd(nextParaPrId())
                .tabPrIDRefAnd("0")
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        pp.createAlign();
        pp.align().horizontalAnd(HorizontalAlign2.JUSTIFY).vertical(VerticalAlign1.BASELINE);

        pp.createHeading();
        pp.heading().typeAnd(ParaHeadingType.NONE).idRefAnd("0").level((byte) 0);

        pp.createBreakSetting();
        pp.breakSetting()
                .breakLatinWordAnd(LineBreakForLatin.KEEP_WORD)
                .breakNonLatinWordAnd(LineBreakForNonLatin.BREAK_WORD)
                .widowOrphanAnd(false)
                .keepWithNextAnd(false)
                .keepLinesAnd(false)
                .pageBreakBeforeAnd(false)
                .lineWrap(LineWrap.BREAK);

        pp.createAutoSpacing();
        pp.autoSpacing().eAsianEngAnd(false).eAsianNum(false);

        pp.createMargin();
        pp.margin().createIntent();
        pp.margin().intent().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        pp.margin().createLeft();
        pp.margin().left().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        pp.margin().createRight();
        pp.margin().right().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        pp.margin().createPrev();
        pp.margin().prev().valueAnd(0).unit(ValueUnit2.HWPUNIT);
        pp.margin().createNext();
        pp.margin().next().valueAnd(0).unit(ValueUnit2.HWPUNIT);

        pp.createLineSpacing();
        pp.lineSpacing().typeAnd(LineSpacingType.PERCENT).valueAnd(160).unit(ValueUnit2.HWPUNIT);

        pp.createBorder();
        pp.border()
                .borderFillIDRefAnd("2")
                .offsetLeftAnd(0).offsetRightAnd(0)
                .offsetTopAnd(0).offsetBottomAnd(0)
                .connectAnd(false).ignoreMargin(false);

        return pp;
    }

    private Para addTitlePara(SectionXMLFile section, String title, boolean pageBreak) {
        Para p = section.addNewPara();
        p.idAnd(nextParaId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(pageBreak).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");
        r.addNewT().addText(title);
        return p;
    }

    private Para addSubTitlePara(SectionXMLFile section, String title) {
        Para p = section.addNewPara();
        p.idAnd(nextParaId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");
        r.addNewT().addText(title);
        return p;
    }

    private Para addPara(SectionXMLFile section, String paraPrId, String text, boolean pageBreak) {
        Para p = section.addNewPara();
        p.idAnd(nextParaId()).paraPrIDRefAnd(paraPrId).styleIDRefAnd("0")
                .pageBreakAnd(pageBreak).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");
        r.addNewT().addText(text);
        return p;
    }

    private Para addParaWithTabs(SectionXMLFile section, String paraPrId, String[] segments) {
        Para p = section.addNewPara();
        p.idAnd(nextParaId()).paraPrIDRefAnd(paraPrId).styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");
        T t = r.addNewT();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                t.addNewTab();
            }
            t.addText(segments[i]);
        }
        return p;
    }

    private Para addBlankPara(SectionXMLFile section) {
        return addPara(section, "0", "", false);
    }

    private String nextParaPrId() {
        return String.valueOf(paraPrIdCounter++);
    }

    private String nextTabPrId() {
        return String.valueOf(tabPrIdCounter++);
    }

    private String nextBorderFillId() {
        return String.valueOf(borderFillIdCounter++);
    }

    private String nextParaId() {
        return String.valueOf(1000000 + (paraIdCounter++));
    }
}
