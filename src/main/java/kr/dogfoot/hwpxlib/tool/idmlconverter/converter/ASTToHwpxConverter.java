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

import java.util.*;


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

    // ID 카운터는 HwpxUtil로 이전됨

    // ── 정적 팩토리 ──

    public static ConvertResult convert(ASTDocument doc) throws ConvertException {
        return convert(doc, ProgressReporter.NONE, 0, 0, null);
    }

    public static ConvertResult convert(ASTDocument doc, ProgressReporter reporter,
                                         int progressOffset, int progressTotal) throws ConvertException {
        return convert(doc, reporter, progressOffset, progressTotal, null);
    }

    public static ConvertResult convert(ASTDocument doc, ProgressReporter reporter,
                                         int progressOffset, int progressTotal,
                                         Map<String, String> customFontMap) throws ConvertException {
        try {
            return new ASTToHwpxConverter(doc, reporter, progressOffset, progressTotal, customFontMap).doConvert();
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
    private final Map<String, String> customFontMap;

    // 통계
    private int pagesConverted;

    // 빌더 (doConvert에서 초기화)
    private HwpxConverterContext ctx;
    private HwpxParagraphBuilder paragraphBuilder;
    private HwpxTextBoxBuilder textBoxBuilder;
    private HwpxTableBuilder tableBuilder;
    private HwpxImageBuilder imageBuilder;

    private ASTToHwpxConverter(ASTDocument doc, ProgressReporter reporter,
                                int progressOffset, int progressTotal,
                                Map<String, String> customFontMap) {
        this.doc = doc;
        this.result = new ConvertResult();
        this.reporter = reporter;
        this.progressOffset = progressOffset;
        this.progressTotal = progressTotal;
        this.customFontMap = customFontMap;
    }

    // ── 변환 메인 ──

    private ConvertResult doConvert() throws ConvertException {
        System.err.println("[ASTToHwpxConverter] Starting conversion...");

        // 1. 기본 HWPX 구조 생성
        HWPXFile hwpxFile = BlankFileMaker.make();

        // 2. 레지스트리 초기화
        FontRegistry fontRegistry = new FontRegistry(hwpxFile, customFontMap);
        StyleRegistry styleRegistry = new StyleRegistry(hwpxFile, fontRegistry);

        // 3. 컨텍스트 + 빌더 생성
        ctx = new HwpxConverterContext(hwpxFile, styleRegistry, fontRegistry, doc.paragraphStyles());

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
                    ASTTextFrameBlock tfb = (ASTTextFrameBlock) block;
                    // distributed 블록은 독립 프레임이므로 링크 체인에서 제외
                    if (!tfb.distributed() && tfb.storyId() != null) {
                        storyBlockCount.merge(tfb.storyId(), 1, Integer::sum);
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
        ctx.currentColumnWidth = Math.max(100, layout.pageWidth() - mLeft - mRight);

        // TEXT_FRAME_BLOCK 수집
        System.out.println("[CVT-SEC] page=" + section.pageNumber() + " blocks=" + section.blocks().size());
        List<ASTTextFrameBlock> textFrameBlocks = new ArrayList<>();
        List<ASTBlock> otherBlocks = new ArrayList<>();
        for (ASTBlock block : section.blocks()) {
            if (block.blockType() == ASTBlock.BlockType.TEXT_FRAME_BLOCK) {
                textFrameBlocks.add((ASTTextFrameBlock) block);
                if ("u3547f".equals(block.sourceId())) {
                    ASTTextFrameBlock tfb = (ASTTextFrameBlock) block;
                    System.out.println("[CVT-DBG] u3547f in blocks: x=" + tfb.effectiveX() + " y=" + tfb.y() + " paras=" + tfb.paragraphs().size() + " bgOnly=" + tfb.isBackgroundOnly());
                }
            } else {
                otherBlocks.add(block);
            }
        }

        // 텍스트 프레임 → 플로팅 테이블로 변환 (모든 프레임은 절대 좌표 기반)
        List<ASTTextFrameBlock> floatingBlocks = new ArrayList<>();
        List<ASTTextFrameBlock> backgroundBlocks = new ArrayList<>();
        for (ASTTextFrameBlock block : textFrameBlocks) {
            if (block.isBackgroundOnly()) {
                backgroundBlocks.add(block);
            } else {
                floatingBlocks.add(block);
            }
        }

        // SecPr 단락 생성 — 이 단락 하나에 모든 플로팅 객체 + secPr을 넣는다.
        // 첫 페이지 이후에는 pageBreak=true로 설정 → 플로팅 객체가 올바른 페이지에 위치
        Para secPrPara = createSectionPara(sectionFile, pagesConverted > 0);

        // 1) BEHIND_TEXT FIGURE: 배경 이미지 (그룹 외부)를 먼저 배치
        for (ASTBlock block : otherBlocks) {
            if (block.blockType() == ASTBlock.BlockType.FIGURE) {
                ASTFigure fig = (ASTFigure) block;
                if (!fig.fromGroup()) {
                    imageBuilder.convertFigure(secPrPara, fig);
                    ctx.framesConverted++;
                }
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
                ASTTable table = (ASTTable) block;
                tableBuilder.convertTable(secPrPara, table);
                ctx.framesConverted++;
            }
        }

        // 일반 플로팅 텍스트 프레임 + 그룹 내부 FIGURE: z-order 순으로 인터리빙
        // (동일 그룹 내 텍스트 프레임과 벡터 셰이프 간 올바른 겹침 순서 보장)
        List<ASTBlock> inFrontBlocks = new ArrayList<>();
        for (ASTTextFrameBlock block : floatingBlocks) {
            inFrontBlocks.add(block);
        }
        for (ASTBlock block : otherBlocks) {
            if (block.blockType() == ASTBlock.BlockType.FIGURE) {
                ASTFigure fig = (ASTFigure) block;
                if (fig.fromGroup()) {
                    inFrontBlocks.add(fig);
                }
            }
        }
        Collections.sort(inFrontBlocks, new Comparator<ASTBlock>() {
            @Override
            public int compare(ASTBlock a, ASTBlock b) {
                return Integer.compare(zOrderOf(a), zOrderOf(b));
            }
        });
        for (ASTBlock block : inFrontBlocks) {
            if (block.blockType() == ASTBlock.BlockType.TEXT_FRAME_BLOCK) {
                ASTTextFrameBlock _dbgTfb = (ASTTextFrameBlock) block;
                if ("u3547f".equals(_dbgTfb.sourceId())) {
                    System.out.println("[CVT-DBG] u3547f: x=" + _dbgTfb.effectiveX() + " y=" + _dbgTfb.y() + " w=" + _dbgTfb.effectiveWidth() + " h=" + _dbgTfb.height() + " paras=" + _dbgTfb.paragraphs().size() + " bgOnly=" + _dbgTfb.isBackgroundOnly());
                }
                textBoxBuilder.convertTextFrameBlock(secPrPara, (ASTTextFrameBlock) block);
            } else if (block.blockType() == ASTBlock.BlockType.FIGURE) {
                imageBuilder.convertFigure(secPrPara, (ASTFigure) block);
            }
            ctx.framesConverted++;
        }

        // 4) 셀 내부에서 승격된 오버레이 텍스트박스: PAPER 기준 IN_FRONT_OF_TEXT
        try {
            for (HwpxConverterContext.DeferredOverlay d : ctx.deferredOverlays) {
                textBoxBuilder.addPageLevelOverlay(secPrPara, d.overlay, d.pageX, d.pageY);
                ctx.framesConverted++;
            }
        } finally {
            ctx.deferredOverlays.clear();
        }

        // SecPr run을 마지막에 추가 (페이지 레이아웃 정의)
        addSecPrRun(secPrPara, layout);
    }

    private static int zOrderOf(ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) return ((ASTTextFrameBlock) block).zOrder();
        if (block instanceof ASTFigure) return ((ASTFigure) block).zOrder();
        return 0;
    }

    // ── 프레임 배치 판별 / 정렬 ──

    // ── SecPr 생성 ──

    /**
     * 섹션 단락 생성 (플로팅 객체 + secPr을 담을 단일 단락).
     */
    private Para createSectionPara(SectionXMLFile sectionFile, boolean pageBreak) {
        Para para = sectionFile.addNewPara();
        para.idAnd(nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(pageBreak)
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
        // HWPX에서 가로 모드(NARROWLY)는 width=짧은변, height=긴변으로 저장
        PageDirection direction = layout.pageWidth() > layout.pageHeight()
                ? PageDirection.NARROWLY : PageDirection.WIDELY;
        int hwpxWidth, hwpxHeight;
        if (direction == PageDirection.NARROWLY) {
            hwpxWidth = (int) layout.pageHeight();
            hwpxHeight = (int) layout.pageWidth();
        } else {
            hwpxWidth = (int) layout.pageWidth();
            hwpxHeight = (int) layout.pageHeight();
        }

        // 마진
        int mTop = layout.marginTop() > 0 ? (int) layout.marginTop() : 1417;
        int mBottom = layout.marginBottom() > 0 ? (int) layout.marginBottom() : 1417;
        int mLeft = layout.marginLeft() > 0 ? (int) layout.marginLeft() : 1417;
        int mRight = layout.marginRight() > 0 ? (int) layout.marginRight() : 1417;
        int headerFooter = 1417;

        secPr.createPagePr();
        secPr.pagePr()
                .landscapeAnd(direction)
                .widthAnd(hwpxWidth)
                .heightAnd(hwpxHeight)
                .gutterType(GutterMethod.LEFT_ONLY);

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
        return HwpxUtil.createFloatingObjectPara(section);
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

    // ── 정적 유틸리티 (Builder + FlatToHwpxConverter에서 호출) ──

    public static String nextParaId() {
        return HwpxUtil.nextParaId();
    }

    public static String nextShapeId() {
        return HwpxUtil.nextShapeId();
    }

    /**
     * AST 단락의 스타일 참조를 StyleRegistry 키로 변환.
     * AST에서는 "01_발문" 형태이고, StyleRegistry는 "ParagraphStyle/01_발문"으로 등록됨.
     */
    static String resolveStyleRef(String ref, StyleRegistry styleRegistry) {
        return HwpxUtil.resolveStyleRef(ref, styleRegistry);
    }

    static String sanitizeText(String text) {
        return HwpxUtil.sanitizeText(text);
    }

    static HorizontalAlign2 mapAlignment(String alignment) {
        return HwpxUtil.mapAlignment(alignment);
    }
}
