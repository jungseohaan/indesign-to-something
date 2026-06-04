package kr.dogfoot.hwpxlib.tool.table;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit1;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.notepr.EndNotePr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.notepr.FootNotePr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.pageborder.PageBorderFill;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

/**
 * 서로 텍스트가 흐르지 않는 4개의 독립 구역(Section)으로 구성된 샘플 HWPX 생성.
 *
 * <p>하나의 section XML 안에서 SecPr(구역 나누기)을 사용하여 4개의 독립 구역을 만든다.
 * 각 구역에는 헤밍웨이 원작 소설의 한국어 번역 텍스트를 넣는다.</p>
 * <ul>
 *   <li>구역 1: 노인과 바다</li>
 *   <li>구역 2: 무기여 잘 있거라</li>
 *   <li>구역 3: 태양은 다시 떠오른다</li>
 *   <li>구역 4: 킬리만자로의 눈</li>
 * </ul>
 */
public class SampleMultiSectionWriter {

    private int paraIdCounter = 0;

    @Test
    public void createMultiSectionDocument() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        // 기존 blank section의 빈 문단 제거
        section.removeAllParas();

        // ── 구역 1: 노인과 바다 (첫 구역 - 초기 SecPr) ──
        addSectionBreakPara(section);
        fillSection_TheOldManAndTheSea(section);

        // ── 구역 2: 무기여 잘 있거라 (새 구역 - SecPr로 구역 나누기) ──
        addSectionBreakPara(section);
        fillSection_AFarewellToArms(section);

        // ── 구역 3: 태양은 다시 떠오른다 ──
        addSectionBreakPara(section);
        fillSection_TheSunAlsoRises(section);

        // ── 구역 4: 킬리만자로의 눈 ──
        addSectionBreakPara(section);
        fillSection_TheSnowsOfKilimanjaro(section);

        // 저장
        String filepath = "testFile/tool/sample_multi_section.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Multi-Section Sample Result ===");
        System.out.println("File: " + filepath);
        System.out.println("Total paragraphs: " + section.countOfPara());

        // 라운드트립 검증
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("문단이 있어야 함", readSection.countOfPara() > 0);

        // SecPr 개수 확인 (4개의 구역 = 4개의 SecPr)
        int secPrCount = 0;
        for (int i = 0; i < readSection.countOfPara(); i++) {
            Para p = readSection.getPara(i);
            for (int r = 0; r < p.countOfRun(); r++) {
                Run run = p.getRun(r);
                if (run.secPr() != null) {
                    secPrCount++;
                }
            }
        }
        Assert.assertEquals("4개의 구역(SecPr)이 있어야 함", 4, secPrCount);

        System.out.println("SecPr count: " + secPrCount);
        System.out.println("Total paragraphs: " + readSection.countOfPara());
        System.out.println("Round-trip verification passed!");
    }

    // ── 구역 나누기 문단 (SecPr + ColPr) ──

    /**
     * 구역 나누기를 위한 문단을 추가한다.
     * ForSection0XMLFile.make()와 동일한 패턴으로 SecPr과 ColPr을 설정.
     */
    private void addSectionBreakPara(SectionXMLFile section) {
        Para para = section.addNewPara();
        para.idAnd(nextParaId()).paraPrIDRefAnd("3").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        run.createSecPr();
        setupSecPr(run.secPr());

        Ctrl ctrl = run.addNewCtrl();
        ctrl.addNewColPr()
                .idAnd("")
                .typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(1)
                .sameSzAnd(true)
                .sameGap(0);

        run.addNewT();
    }

    private void setupSecPr(SecPr secPr) {
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
                .borderAnd(VisibilityOption.SHOW_ALL)
                .fillAnd(VisibilityOption.SHOW_ALL)
                .hideFirstPageNumAnd(false).hideFirstEmptyLineAnd(false)
                .showLineNumber(false);

        secPr.createLineNumberShape();
        secPr.lineNumberShape()
                .restartTypeAnd(LineNumberRestartType.Unknown)
                .countByAnd(0).distanceAnd(0).startNumber(0);

        secPr.createPagePr();
        secPr.pagePr()
                .landscapeAnd(PageDirection.WIDELY)
                .widthAnd(59528).heightAnd(84188)
                .gutterType(GutterMethod.LEFT_ONLY);

        secPr.pagePr().createMargin();
        secPr.pagePr().margin()
                .headerAnd(4252).footerAnd(4252).gutterAnd(0)
                .leftAnd(8504).rightAnd(8504).topAnd(5668).bottom(4252);

        secPr.createFootNotePr();
        setupFootNotePr(secPr.footNotePr());

        secPr.createEndNotePr();
        setupEndNotePr(secPr.endNotePr());

        setupPageBorderFill(secPr.addNewPageBorderFill(), ApplyPageType.BOTH);
        setupPageBorderFill(secPr.addNewPageBorderFill(), ApplyPageType.EVEN);
        setupPageBorderFill(secPr.addNewPageBorderFill(), ApplyPageType.ODD);
    }

    private void setupFootNotePr(FootNotePr fp) {
        fp.createAutoNumFormat();
        fp.autoNumFormat().typeAnd(NumberType2.DIGIT)
                .userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        fp.createNoteLine();
        fp.noteLine().lengthAnd(-1).typeAnd(LineType2.SOLID)
                .widthAnd(LineWidth.MM_0_12).color("#000000");
        fp.createNoteSpacing();
        fp.noteSpacing().betweenNotesAnd(283).belowLineAnd(567).aboveLine(850);
        fp.createNumbering();
        fp.numbering().typeAnd(FootNoteNumberingType.CONTINUOUS).newNum(1);
        fp.createPlacement();
        fp.placement().placeAnd(FootNotePlace.EACH_COLUMN).beneathText(false);
    }

    private void setupEndNotePr(EndNotePr ep) {
        ep.createAutoNumFormat();
        ep.autoNumFormat().typeAnd(NumberType2.DIGIT)
                .userCharAnd("").prefixCharAnd("").suffixCharAnd(")").supscript(false);
        ep.createNoteLine();
        ep.noteLine().lengthAnd(14692344).typeAnd(LineType2.SOLID)
                .widthAnd(LineWidth.MM_0_12).color("#000000");
        ep.createNoteSpacing();
        ep.noteSpacing().betweenNotesAnd(0).belowLineAnd(567).aboveLine(850);
        ep.createNumbering();
        ep.numbering().typeAnd(EndNoteNumberingType.CONTINUOUS).newNum(1);
        ep.createPlacement();
        ep.placement().placeAnd(EndNotePlace.END_OF_DOCUMENT).beneathText(false);
    }

    private void setupPageBorderFill(PageBorderFill pbf, ApplyPageType type) {
        pbf.typeAnd(type).borderFillIDRefAnd("1")
                .textBorderAnd(PageBorderPositionCriterion.PAPER)
                .headerInsideAnd(false).footerInsideAnd(false)
                .fillArea(PageFillArea.PAPER);
        pbf.createOffset();
        pbf.offset().leftAnd(1417L).rightAnd(1417L).topAnd(1417L).bottom(1417L);
    }

    // ── 노인과 바다 (The Old Man and the Sea) ──
    private void fillSection_TheOldManAndTheSea(SectionXMLFile section) {
        addTextPara(section, "노인과 바다");
        addTextPara(section, "어니스트 헤밍웨이");
        addEmptyPara(section);

        addTextPara(section, "그는 멕시코 만류에서 작은 배를 타고 혼자 고기잡이를 하는 노인이었다. " +
                "벌써 팔십사 일이나 한 마리의 고기도 낚지 못했다. " +
                "처음 사십 일 동안은 한 소년이 그와 함께 있었다. " +
                "그러나 사십 일이 지나도록 한 마리도 잡지 못하자 소년의 부모는 " +
                "그 노인이 이제 완전히 운이 다한 사람이라고 말했다.");

        addEmptyPara(section);

        addTextPara(section, "소년은 노인을 사랑했다. 노인이 자기에게 고기잡이를 가르쳐 주었기 때문이다. " +
                "소년은 노인의 몸에서 풍기는 바다 냄새가 좋았고, " +
                "주름진 손이 낚싯줄을 다루는 솜씨를 좋아했다.");

        addEmptyPara(section);

        addTextPara(section, "\"늙은이, 내일은 나와 함께 나가자.\" 소년이 말했다. " +
                "\"아니, 너는 운이 좋은 배에 타고 있잖아. 거기 있어라.\"");

        addEmptyPara(section);

        addTextPara(section, "그 노인은 마르고 여위었으며 목덜미에는 깊은 주름이 패여 있었다. " +
                "열대의 바다 위에 반사되는 햇빛 때문에 뺨에는 피부암에 걸린 갈색 반점이 있었다. " +
                "반점은 얼굴 양쪽 옆에까지 번져 있었고, " +
                "손에는 큰 고기를 낚싯줄로 다루느라 생긴 깊은 상처가 있었다. " +
                "그러나 그 상처 가운데 새로 생긴 것은 하나도 없었다. " +
                "물고기가 없는 사막처럼 오래된 상처들이었다.");

        addEmptyPara(section);

        addTextPara(section, "그의 몸 어디를 보아도 늙었지만, 눈만은 달랐다. " +
                "눈은 바다와 같은 빛깔이었으며, 활기차고 불굴의 의지를 보여주고 있었다.");

        addEmptyPara(section);

        addTextPara(section, "\"내일은 좋은 날이 될 거야.\" 노인이 말했다. " +
                "\"바람이 좋거든.\"");

        addEmptyPara(section);

        addTextPara(section, "팔십오 일째 되는 날, 노인은 해가 뜨기 전에 배를 타고 나갔다. " +
                "항구를 나서자 돛을 펴고 멕시코 만류의 깊은 물살을 향해 나아갔다. " +
                "그의 미끼는 한 길 깊이에서 물살을 타고 흐르고 있었다. " +
                "아무도 보이지 않는 넓은 바다 한가운데서, 그는 혼자였다.");

        addEmptyPara(section);

        addTextPara(section, "정오가 되자 큰 고기가 미끼를 물었다. " +
                "노인은 낚싯줄을 잡고 버텼다. " +
                "그것은 지금까지 걸어본 적이 없는 거대한 물고기였다. " +
                "물고기는 배를 끌고 먼 바다로 나아갔다. " +
                "노인은 줄을 놓지 않았다.");
    }

    // ── 무기여 잘 있거라 (A Farewell to Arms) ──
    private void fillSection_AFarewellToArms(SectionXMLFile section) {
        addTextPara(section, "무기여 잘 있거라");
        addTextPara(section, "어니스트 헤밍웨이");
        addEmptyPara(section);

        addTextPara(section, "그해 늦여름 우리는 강 건너 평야를 바라보는 집에 살고 있었다. " +
                "강바닥에는 자갈과 굵은 돌이 깔려 있었는데, " +
                "햇볕을 받으면 하얗게 말라 버렸다. " +
                "물은 맑고 빠르게 흐르고 있었으며, 물 깊은 곳은 푸르게 보였다.");

        addEmptyPara(section);

        addTextPara(section, "군대가 집 앞을 지나가곤 했는데, 먼지가 나뭇잎 위에 앉아 " +
                "그해는 나뭇잎도 일찍 졌다. " +
                "군인들은 행군하면서 먼지를 일으켰고, " +
                "나뭇잎은 바람에 흔들리다가 떨어졌다. " +
                "군대가 지나간 뒤에 길은 텅 비었고, " +
                "나뭇잎만 흩날리고 있었다.");

        addEmptyPara(section);

        addTextPara(section, "나는 이탈리아 전선에서 구급차 부대에 소속된 미국인 중위였다. " +
                "전쟁은 끝없이 계속되었고, " +
                "병원에서 캐서린 바클리라는 영국인 간호사를 만났다. " +
                "처음에 나는 그것이 게임이라고 생각했다. " +
                "그러나 그것은 게임이 아니었다.");

        addEmptyPara(section);

        addTextPara(section, "\"당신은 왜 이탈리아 군대에 있는 거예요?\" 캐서린이 물었다. " +
                "\"잘 모르겠어요.\" 내가 말했다. " +
                "\"전쟁이 없었으면 좋겠어요.\" 그녀가 말했다.");

        addEmptyPara(section);

        addTextPara(section, "포탄이 떨어지는 소리가 들렸고, 그 뒤에 폭발음이 뒤따랐다. " +
                "나는 다리에 부상을 입었다. " +
                "들것에 실려 갈 때, 머리 위에 별이 빛나고 있었다. " +
                "고통 속에서도 나는 캐서린의 얼굴을 떠올렸다.");

        addEmptyPara(section);

        addTextPara(section, "밀라노 병원에서 우리는 다시 만났다. " +
                "그곳에서 나는 그녀를 진정으로 사랑하게 되었다. " +
                "창밖으로 밤나무가 보였고, 지붕 너머로 대성당의 첨탑이 솟아 있었다. " +
                "우리는 전쟁을 잊으려 했지만, 전쟁은 우리를 잊지 않았다.");

        addEmptyPara(section);

        addTextPara(section, "세상은 사람들을 부러뜨린다. " +
                "그리고 부러진 자리가 강해지는 사람도 있다. " +
                "그러나 부러지지 않는 사람들은, " +
                "세상이 죽인다. " +
                "세상은 아주 착한 사람, 아주 온순한 사람, " +
                "아주 용감한 사람을 가리지 않고 죽인다.");
    }

    // ── 태양은 다시 떠오른다 (The Sun Also Rises) ──
    private void fillSection_TheSunAlsoRises(SectionXMLFile section) {
        addTextPara(section, "태양은 다시 떠오른다");
        addTextPara(section, "어니스트 헤밍웨이");
        addEmptyPara(section);

        addTextPara(section, "로버트 콘은 프린스턴을 졸업한 미들급 권투 선수 출신이었다. " +
                "나는 그를 별로 좋아하지 않았지만, " +
                "그가 사람들에게 매력적이라는 것은 인정해야 했다. " +
                "그는 잘 생겼고 돈도 있었다.");

        addEmptyPara(section);

        addTextPara(section, "우리는 파리의 카페에 앉아 있었다. " +
                "제이크 반스, 그것이 나였다. " +
                "전쟁에서 돌아온 뒤, " +
                "우리 세대의 사람들은 뭔가를 잃어버린 것 같았다. " +
                "무엇을 잃어버렸는지 정확히 말할 수는 없었지만, " +
                "확실히 뭔가가 사라진 뒤였다.");

        addEmptyPara(section);

        addTextPara(section, "브렛 애슐리가 들어왔다. " +
                "그녀는 짧은 머리에 아름다운 얼굴을 가지고 있었다. " +
                "방 안의 모든 남자들이 그녀를 쳐다보았다. " +
                "\"이봐, 제이크.\" 그녀가 말했다. " +
                "\"한잔하자.\"");

        addEmptyPara(section);

        addTextPara(section, "우리는 스페인으로 갔다. " +
                "팜플로나에서는 산페르민 축제가 열리고 있었다. " +
                "황소가 거리를 달렸고, 사람들은 황소 앞에서 뛰었다. " +
                "투우장에서 로메로라는 젊은 투우사가 싸웠다. " +
                "그의 동작은 아름다웠다. " +
                "황소와 사람 사이의 거리가 그의 용기를 보여주었다.");

        addEmptyPara(section);

        addTextPara(section, "\"모든 것이 변하지만, 아무것도 변하지 않아.\" " +
                "빌이 말했다. " +
                "우리는 강에서 송어를 낚았다. " +
                "물은 차갑고 맑았으며, 나무 그늘 아래에서 와인을 마셨다. " +
                "그것이 좋은 시절이었다. " +
                "하지만 좋은 시절은 오래가지 않는다.");

        addEmptyPara(section);

        addTextPara(section, "축제가 끝난 뒤 모든 것이 허망했다. " +
                "사람들은 흩어졌고, 거리에는 쓰레기만 남았다. " +
                "브렛은 마드리드에서 전보를 보내왔다. " +
                "나는 택시를 타고 그녀에게 갔다.");

        addEmptyPara(section);

        addTextPara(section, "\"우리 함께 있었으면 정말 좋았을 텐데.\" 브렛이 말했다. " +
                "\"그래, 그렇게 생각하면 좋지 않겠어.\" 내가 말했다.");
    }

    // ── 킬리만자로의 눈 (The Snows of Kilimanjaro) ──
    private void fillSection_TheSnowsOfKilimanjaro(SectionXMLFile section) {
        addTextPara(section, "킬리만자로의 눈");
        addTextPara(section, "어니스트 헤밍웨이");
        addEmptyPara(section);

        addTextPara(section, "킬리만자로는 높이 일만 구천 칠백열 피트의, " +
                "눈으로 덮인 산으로 아프리카의 최고봉이라고 한다. " +
                "서쪽 봉우리의 꼭대기 가까이에 말라붙어 얼어 있는 " +
                "표범의 사체가 있다. " +
                "그 높은 곳에서 표범이 무엇을 찾고 있었는지 아무도 설명한 사람이 없다.");

        addEmptyPara(section);

        addTextPara(section, "\"이 냄새가 정말 지긋지긋해.\" 남자가 말했다. " +
                "\"어쩔 수가 없잖아요.\" 여자가 말했다. " +
                "\"미안해. 짜증이 나서 그래.\"");

        addEmptyPara(section);

        addTextPara(section, "그는 아프리카의 평원에 누워 있었다. " +
                "오른쪽 다리의 괴저가 그를 죽이고 있었다. " +
                "가시덤불에 긁힌 상처가 감염된 것이다. " +
                "처음에는 대수롭지 않게 생각했다. " +
                "그러나 이제 다리 전체가 아팠고 고통은 사라졌지만, " +
                "그것은 좋은 징조가 아니었다.");

        addEmptyPara(section);

        addTextPara(section, "그는 작가였다. 아니, 작가였어야 했다. " +
                "그는 쓸 수 있었던 것들에 대해 생각했다. " +
                "파리에서의 겨울, 지붕 위의 눈, " +
                "나무꾼이 포도주를 가지고 오던 일, " +
                "그리고 카페에서 보낸 저녁들. " +
                "그 모든 것을 쓸 수 있었는데 쓰지 않았다.");

        addEmptyPara(section);

        addTextPara(section, "콘스탄티노폴에서의 일도 있었다. " +
                "그곳에서의 전쟁, 비를 맞으며 후퇴하던 일, " +
                "짐마차에 실린 부상병들, " +
                "그리고 다리 위에서의 경험. " +
                "그는 그것들을 쓰려고 항상 마음먹었다. " +
                "그러나 매일 쓰기를 미루었다.");

        addEmptyPara(section);

        addTextPara(section, "독수리 떼가 하늘에서 내려왔다. " +
                "해가 지고 있었다. " +
                "밤이 되면 하이에나가 올 것이다. " +
                "그는 죽음이 가까이 오는 것을 느꼈다.");

        addEmptyPara(section);

        addTextPara(section, "그때 비행기가 왔다. " +
                "콤피라는 조종사가 그를 태우고 날아올랐다. " +
                "비행기는 높이 올라갔고, " +
                "갑자기 그는 킬리만자로의 정상을 보았다. " +
                "넓고 높고 믿을 수 없이 하얀 킬리만자로의 정상이 " +
                "햇빛 속에 빛나고 있었다. " +
                "그는 그곳이 자기가 가는 곳이라는 것을 알았다.");
    }

    // ── 유틸리티 ──

    private Para addTextPara(SectionXMLFile section, String text) {
        Para p = section.addNewPara();
        p.idAnd(nextParaId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");
        r.addNewT().addText(text);
        return p;
    }

    private Para addEmptyPara(SectionXMLFile section) {
        Para p = section.addNewPara();
        p.idAnd(nextParaId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        return p;
    }

    private String nextParaId() {
        return String.valueOf(paraIdCounter++);
    }
}
