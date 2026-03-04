package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.secpr.SecPr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertResult;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ProgressReporter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * 실제 변환 로직은 HwpxParagraphBuilder, HwpxTextBoxBuilder,
 * HwpxTableBuilder, HwpxImageBuilder에 위임한다.
 */
public class ASTToHwpxConverter {

    private static final AtomicLong PARA_ID_COUNTER = new AtomicLong(2000000000L);
    private static final AtomicLong SHAPE_ID_COUNTER = new AtomicLong(8000000L);

    // ── 정적 팩토리 ──

    public static ConvertResult convert(ASTDocument doc) throws ConvertException {
        return convert(doc, ProgressReporter.NONE, 0, 0);
    }

    public static ConvertResult convert(ASTDocument doc, ProgressReporter reporter,
                                         int progressOffset, int progressTotal) throws ConvertException {
        try {
            return new ASTToHwpxConverter(doc, reporter, progressOffset, progressTotal).doConvert();
        } catch (ConvertException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.HWPX_GENERATION,
                    "AST→HWPX conversion failed: " + e.getMessage(), e);
        }
    }

    // ── 인스턴스 필드 ──

    private final ASTDocument doc;
    private final ConvertResult result;
    private final ProgressReporter reporter;
    private final int progressOffset;
    private final int progressTotal;

    // 통계
    private int pagesConverted;

    // 빌더 (doConvert에서 초기화)
    private HwpxConverterContext ctx;
    private HwpxParagraphBuilder paragraphBuilder;
    private HwpxTextBoxBuilder textBoxBuilder;
    private HwpxTableBuilder tableBuilder;
    private HwpxImageBuilder imageBuilder;

    private ASTToHwpxConverter(ASTDocument doc, ProgressReporter reporter,
                                int progressOffset, int progressTotal) {
        this.doc = doc;
        this.result = new ConvertResult();
        this.reporter = reporter;
        this.progressOffset = progressOffset;
        this.progressTotal = progressTotal;
    }

    // ── 변환 메인 ──

    private ConvertResult doConvert() throws ConvertException {
        System.err.println("[ASTToHwpxConverter] Starting conversion...");

        // 1. 기본 HWPX 구조 생성
        HWPXFile hwpxFile = BlankFileMaker.make();

        // 2. 레지스트리 초기화
        FontRegistry fontRegistry = new FontRegistry(hwpxFile);
        StyleRegistry styleRegistry = new StyleRegistry(hwpxFile, fontRegistry);

        // 3. 컨텍스트 + 빌더 생성
        ctx = new HwpxConverterContext(hwpxFile, styleRegistry, fontRegistry, doc);

        paragraphBuilder = new HwpxParagraphBuilder(ctx);
        textBoxBuilder = new HwpxTextBoxBuilder(ctx, paragraphBuilder);
        tableBuilder = new HwpxTableBuilder(ctx, paragraphBuilder);
        imageBuilder = new HwpxImageBuilder(ctx);

        // 순환 의존 해소 (setter 주입)
        paragraphBuilder.setBuilders(textBoxBuilder, tableBuilder, imageBuilder);
        ctx.tableBuilderRef = tableBuilder;

        // 4. 폰트 등록
        registerFonts();

        // 5. 스타일 등록
        registerStyles();

        // 6. 연결 글상자 사전 스캔 — 같은 storyId를 공유하는 블록들에 linkId 사전 할당
        buildStoryLinkMap();

        // 7. 섹션/블록 변환
        SectionXMLFile section0 = hwpxFile.sectionXMLFileList().get(0);
        section0.removeAllParas();

        int totalSections = doc.sections().size();
        for (ASTSection section : doc.sections()) {
            convertSection(section0, section);
            pagesConverted++;
            // 진행률: progressOffset~(progressOffset+80)% 구간에 페이지 진행률 매핑
            int progress = progressOffset + (int)(80.0 * pagesConverted / Math.max(progressTotal, 1));
            reporter.reportProgress(progress, 100,
                    "페이지 변환 중... (" + pagesConverted + "/" + totalSections + ")");
        }

        // 7. 배경 PNG 배치
        for (ASTPageBackground bg : doc.backgrounds()) {
            if (bg.pngData() != null && bg.pngData().length > 0) {
                imageBuilder.addBackgroundImage(section0, bg);
            }
        }

        // 8. 빈 section 방지
        if (section0.countOfPara() == 0) {
            addEmptyPara(section0);
        }

        hwpxFile.headerXMLFile().secCnt((short) 1);

        result.hwpxFile(hwpxFile);
        result.pagesConverted(pagesConverted);
        result.framesConverted(ctx.framesConverted);
        result.imagesConverted(ctx.imagesConverted);
        result.equationsConverted(ctx.equationsConverted);
        result.stylesConverted(ctx.styleRegistry.totalStyleCount());

        System.err.println("[ASTToHwpxConverter] Done. " + result.summary());
        return result;
    }

    // ── 폰트 등록 ──

    private void registerFonts() {
        for (ASTFontDef fontDef : doc.fonts()) {
            String fontFamily = fontDef.fontFamily();
            if (fontFamily != null) {
                ctx.fontRegistry.resolveFontId(fontFamily);
            }
        }
    }

    // ── 스타일 등록 ──

    private void registerStyles() {
        for (ASTStyleDef astStyle : doc.paragraphStyles()) {
            ctx.styleRegistry.registerParagraphStyle(astStyle);
        }
        for (ASTStyleDef astStyle : doc.characterStyles()) {
            ctx.styleRegistry.registerCharacterStyle(astStyle);
        }
    }

    /**
     * 연결 글상자 사전 스캔.
     * 같은 storyId를 공유하는 TextFrameBlock이 2개 이상이면 linkId를 할당한다.
     */
    private void buildStoryLinkMap() {
        // storyId → 블록 수 카운트
        Map<String, Integer> storyBlockCount = new LinkedHashMap<>();
        for (ASTSection section : doc.sections()) {
            for (ASTBlock block : section.blocks()) {
                if (block.blockType() == ASTBlock.BlockType.TEXT_FRAME_BLOCK) {
                    String sid = ((ASTTextFrameBlock) block).storyId();
                    if (sid != null) {
                        storyBlockCount.merge(sid, 1, Integer::sum);
                    }
                }
            }
        }

        // 2개 이상인 storyId에 대해 linkId 사전 할당
        int linkIdCounter = 1;
        for (Map.Entry<String, Integer> entry : storyBlockCount.entrySet()) {
            if (entry.getValue() > 1) {
                List<String> linkIds = new ArrayList<>();
                for (int i = 0; i < entry.getValue(); i++) {
                    linkIds.add(String.valueOf(linkIdCounter++));
                }
                ctx.storyLinkIds.put(entry.getKey(), linkIds);
                ctx.storyLinkIndex.put(entry.getKey(), 0);
            }
        }
    }

    // ── 섹션 변환 ──

    private void convertSection(SectionXMLFile sectionFile, ASTSection section) {
        ASTPageLayout layout = section.layout();
        if (layout == null) return;

        // 현재 섹션의 컬럼 너비 계산 (오버레이 위치 계산용)
        long mLeft = layout.marginLeft() > 0 ? layout.marginLeft() : 1417;
        long mRight = layout.marginRight() > 0 ? layout.marginRight() : 1417;
        ctx.currentColumnWidth = layout.pageWidth() - mLeft - mRight;

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
        List<ASTTextFrameBlock> inlineBlocks = new ArrayList<>();
        List<ASTTextFrameBlock> floatingBlocks = new ArrayList<>();
        List<ASTTextFrameBlock> backgroundBlocks = new ArrayList<>();
        for (ASTTextFrameBlock block : textFrameBlocks) {
            if (!needsFloatingPosition(block, layout)) {
                inlineBlocks.add(block);
            } else if (block.isBackgroundOnly()) {
                backgroundBlocks.add(block);
            } else {
                floatingBlocks.add(block);
            }
        }

        // 인라인 글상자: 읽기 순서 정렬 후 변환
        sortInReadingOrder(inlineBlocks);
        for (ASTTextFrameBlock block : inlineBlocks) {
            textBoxBuilder.addTextBox(sectionFile, block);
            ctx.framesConverted++;
        }

        // SecPr 단락 생성 — 이 단락 하나에 모든 플로팅 객체 + secPr을 넣는다.
        Para secPrPara = createSectionPara(sectionFile);

        // 1) FIGURE 먼저: BEHIND_TEXT 이미지를 XML에서 먼저 배치하여
        //    동일 z-order인 배경 블록보다 아래 레이어로 렌더링되게 한다.
        for (ASTBlock block : otherBlocks) {
            if (block.blockType() == ASTBlock.BlockType.FIGURE) {
                ASTFigure fig = (ASTFigure) block;
                imageBuilder.convertFigure(secPrPara, fig);
                ctx.framesConverted++;
            }
        }

        // 2) 배경 전용 블록: BEHIND_TEXT, z-order=0 — FIGURE 위에 렌더링됨
        for (ASTTextFrameBlock block : backgroundBlocks) {
            if (block.hasNonRectPath()) {
                // 비사각형 폴리곤 → PNG 래스터화 후 이미지로 배치
                imageBuilder.convertNonRectBackground(secPrPara, block);
            } else {
                textBoxBuilder.convertTextFrameBlock(secPrPara, block);
            }
            ctx.framesConverted++;
        }

        // 3) TABLE 플로팅 처리
        for (ASTBlock block : otherBlocks) {
            if (block.blockType() == ASTBlock.BlockType.TABLE) {
                tableBuilder.convertTable(secPrPara, (ASTTable) block);
                ctx.framesConverted++;
            }
        }

        // 일반 플로팅 테이블: IN_FRONT_OF_TEXT
        for (ASTTextFrameBlock block : floatingBlocks) {
            textBoxBuilder.convertTextFrameBlock(secPrPara, block);
            ctx.framesConverted++;
        }

        // 4) 셀 내부에서 승격된 오버레이 텍스트박스: PAPER 기준 IN_FRONT_OF_TEXT
        if (!ctx.deferredOverlays.isEmpty()) {
            for (HwpxConverterContext.DeferredOverlay d : ctx.deferredOverlays) {
                textBoxBuilder.addPageLevelOverlay(secPrPara, d.overlay, d.pageX, d.pageY);
                ctx.framesConverted++;
            }
            ctx.deferredOverlays.clear();
        }

        // SecPr run을 마지막에 추가 (페이지 레이아웃 정의)
        addSecPrRun(secPrPara, layout);
    }

    // ── 프레임 배치 판별 / 정렬 ──

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

    // ── SecPr 생성 ──

    /**
     * 섹션 단락 생성 (플로팅 객체 + secPr을 담을 단일 단락).
     */
    private Para createSectionPara(SectionXMLFile sectionFile) {
        Para para = sectionFile.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        return para;
    }

    /**
     * SecPr run을 단락의 마지막에 추가하여 섹션을 완성한다.
     */
    private void addSecPrRun(Para para, ASTPageLayout layout) {
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

        // 페이지 크기 및 방향
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

        // ColPr (다단 설정) — 페이지 레벨은 항상 1단
        int colCount = 1;
        int colGutter = 0;
        Ctrl ctrl = run.addNewCtrl();
        ctrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(colCount).sameSzAnd(true).sameGap(colGutter);

        run.addNewT();
        paragraphBuilder.addLineSegArray(para);
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

    // ── 유틸리티 (section-level) ──

    static Para createFloatingObjectPara(SectionXMLFile section) {
        Para para = section.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT();
        para.createLineSegArray();
        kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.LineSeg lineSeg =
                para.lineSegArray().addNew();
        lineSeg.textposAnd(0).vertposAnd(0).vertsizeAnd(1000)
                .textheightAnd(1000).baselineAnd(850).spacingAnd(600)
                .horzposAnd(0).horzsizeAnd(42520).flagsAnd(393216);
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

    // ── 정적 유틸리티 (다른 Builder에서 호출) ──

    static String nextParaId() {
        return String.valueOf(PARA_ID_COUNTER.incrementAndGet());
    }

    static String nextShapeId() {
        return String.valueOf(SHAPE_ID_COUNTER.incrementAndGet());
    }

    /**
     * AST 단락의 스타일 참조를 StyleRegistry 키로 변환.
     * AST에서는 "01_발문" 형태이고, StyleRegistry는 "ParagraphStyle/01_발문"으로 등록됨.
     */
    static String resolveStyleRef(String ref, StyleRegistry styleRegistry) {
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

    static String sanitizeText(String text) {
        if (text == null) return null;
        // 제어 문자 및 XML 허용 범위 밖 문자 제거 (탭, 줄바꿈은 유지)
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // PUA → 표준 유니코드 (윤고딕 폰트 전용 글리프)
            if (c == '\uE288') { c = '\u25A1'; } // □ 네모 기호
            if (c == '\t' || c == '\n' || c == '\r'
                    || (c >= 0x20 && c <= 0xD7FF)
                    || (c >= 0xE000 && c <= 0xFFFD)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static HorizontalAlign2 mapAlignment(String alignment) {
        return HwpxEnumMapper.mapAlignment(alignment);
    }
}
