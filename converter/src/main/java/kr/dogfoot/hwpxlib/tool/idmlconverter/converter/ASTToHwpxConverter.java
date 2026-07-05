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
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ProgressReporter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualPlanePolicy;

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
        return convert(doc, reporter, customFontMap, null, null);
    }

    /**
     * FontMapper + ConversionConfig를 지원하는 팩토리 메서드.
     * FlatToHwpxConverter를 대체하는 메인 엔트리포인트.
     */
    public static ConvertResult convert(ASTDocument doc, ProgressReporter reporter,
                                         Map<String, String> customFontMap,
                                         FontMapper fontMapper,
                                         kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig config) throws ConvertException {
        try {
            ASTToHwpxConverter converter = new ASTToHwpxConverter(doc, reporter, 0, 0, customFontMap);
            converter.fontMapper = fontMapper;
            converter.config = config;
            return converter.doConvert();
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
    private FontMapper fontMapper;
    private kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig config;

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
        ConversionTiming.Scope totalScope = ConversionTiming.time("phase3.astToHwpx.internalTotal");
        try {
        System.err.println("[ASTToHwpxConverter] Starting conversion...");
        recordInputSummary();

        // 1. 기본 HWPX 구조 생성
        HWPXFile hwpxFile;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3.astToHwpx.blankFile")) {
            hwpxFile = BlankFileMaker.make();
        }

        // 2. 레지스트리 초기화
        FontRegistry fontRegistry;
        StyleRegistry styleRegistry;
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3.astToHwpx.registryInit")) {
            fontRegistry = new FontRegistry(hwpxFile, customFontMap);
            if (fontMapper != null) {
                fontRegistry.setFontMapper(fontMapper);
            }
            styleRegistry = new StyleRegistry(hwpxFile, fontRegistry);
        }

        // 3. 컨텍스트 + 빌더 생성
        ctx = new HwpxConverterContext(hwpxFile, styleRegistry, fontRegistry, doc.paragraphStyles());
        ctx.config = config;

        paragraphBuilder = new HwpxParagraphBuilder(ctx);
        textBoxBuilder = new HwpxTextBoxBuilder(ctx, paragraphBuilder);
        tableBuilder = new HwpxTableBuilder(ctx, paragraphBuilder);
        imageBuilder = new HwpxImageBuilder(ctx);

        // 순환 의존 해소 (setter 주입)
        paragraphBuilder.setBuilders(textBoxBuilder, tableBuilder, imageBuilder);
        ctx.tableBuilderRef = tableBuilder;

        // 4. 폰트 등록
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3.astToHwpx.registerFonts")) {
            registerFonts();
        }

        // 5. 스타일 등록
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3.astToHwpx.registerStyles")) {
            registerStyles();
        }

        // 6. 연결 글상자 사전 스캔 — 같은 storyId를 공유하는 블록들에 linkId 사전 할당
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3.astToHwpx.storyLinkScan")) {
            buildStoryLinkMap();
        }

        // 7. 섹션/블록 변환
        SectionXMLFile section0 = hwpxFile.sectionXMLFileList().get(0);
        section0.removeAllParas();

        int totalSections = doc.sections().size();
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3.astToHwpx.convertSections")) {
            for (ASTSection section : doc.sections()) {
                convertSection(section0, section);
                pagesConverted++;
                // 진행률: progressOffset~(progressOffset+80)% 구간에 페이지 진행률 매핑
                int progress = progressOffset + (int)(80.0 * pagesConverted / Math.max(progressTotal, 1));
                reporter.reportProgress(progress, 100,
                        "페이지 변환 중... (" + pagesConverted + "/" + totalSections + ")");
            }
        }

        // 7. 배경 PNG 배치
        try (ConversionTiming.Scope ignored = ConversionTiming.time("phase3.astToHwpx.addBackgrounds")) {
            for (ASTPageBackground bg : doc.backgrounds()) {
                if (bg.pngData() != null && bg.pngData().length > 0) {
                    imageBuilder.addBackgroundImage(section0, bg);
                }
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
        } finally {
            totalScope.close();
        }
    }

    private void recordInputSummary() {
        int blocks = 0;
        int textFrames = 0;
        int figures = 0;
        int tables = 0;
        for (ASTSection section : doc.sections()) {
            for (ASTBlock block : section.blocks()) {
                blocks++;
                if (block instanceof ASTTextFrameBlock) textFrames++;
                else if (block instanceof ASTFigure) figures++;
                else if (block instanceof ASTTable) tables++;
            }
        }
        ConversionTiming.metric("hwpxInput.sections", doc.sections().size());
        ConversionTiming.metric("hwpxInput.blocks", blocks);
        ConversionTiming.metric("hwpxInput.textFrameBlocks", textFrames);
        ConversionTiming.metric("hwpxInput.figures", figures);
        ConversionTiming.metric("hwpxInput.tables", tables);
        ConversionTiming.metric("hwpxInput.backgrounds", doc.backgrounds().size());
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
        // source ownership policy: source TextFrames must remain independently owned visible
        // outputs.  Do not create HWPX linked text boxes by storyId.
        ctx.storyLinkIds.clear();
        ctx.storyLinkIndex.clear();
    }

    // ── 섹션 변환 ──

    private void convertSection(SectionXMLFile sectionFile, ASTSection section) {
        ASTPageLayout layout = section.layout();
        if (layout == null) return;

        // 현재 섹션의 컬럼 너비 = 페이지 전체 (마진 0)
        ctx.currentColumnWidth = Math.max(100, layout.pageWidth());
        ctx.pageMarginTop = 0;
        ctx.pageMarginLeft = 0;

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

        // 텍스트 프레임 → 플로팅 테이블로 변환 (모든 프레임은 절대 좌표 기반)
        List<ASTTextFrameBlock> floatingBlocks = new ArrayList<>();
        List<ASTTextFrameBlock> backgroundBlocks = new ArrayList<>();
        for (ASTTextFrameBlock block : textFrameBlocks) {
            if (block.isBackgroundOnly() && !isInFrontPlannedTextFrameVisual(block)) {
                backgroundBlocks.add(block);
            } else {
                floatingBlocks.add(block);
            }
        }

        // SecPr 단락 생성 — 이 단락 하나에 모든 플로팅 객체 + secPr을 넣는다.
        // 첫 페이지 이후에는 pageBreak=true로 설정 → 플로팅 객체가 올바른 페이지에 위치
        Para secPrPara = createSectionPara(sectionFile, pagesConverted > 0);

        // 1) BEHIND_TEXT FIGURE: Stage 1 visualLayer가 behind plane으로 정한 그림을 먼저 배치
        //
        // HWPX에서는 같은 BEHIND_TEXT 평면 안에서 XML 출력 순서가 겹침 결과에 영향을 준다.
        // Stage 1 zOrder is the source stacking contract, and larger values are
        // visually in front. Emit back-to-front so XML order cannot invert
        // same-plane source depth.
        List<ASTFigure> behindFigures = new ArrayList<>();
        for (ASTBlock block : otherBlocks) {
            if (block.blockType() == ASTBlock.BlockType.FIGURE) {
                ASTFigure fig = (ASTFigure) block;
                if (isBehindTextPlaneFigure(fig)) {
                    behindFigures.add(fig);
                }
            }
        }
        Collections.sort(behindFigures, new Comparator<ASTFigure>() {
            @Override
            public int compare(ASTFigure a, ASTFigure b) {
                int z = Integer.compare(textlessGraphicZOrderOf(a), textlessGraphicZOrderOf(b));
                if (z != 0) return z;

                int area = Long.compare(figureArea(b), figureArea(a));
                if (area != 0) return area;

                int y = Long.compare(a.y(), b.y());
                if (y != 0) return y;
                return Long.compare(a.x(), b.x());
            }
        });
        for (ASTFigure fig : behindFigures) {
            imageBuilder.convertFigure(secPrPara, fig);
            ctx.framesConverted++;
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

        // 일반 플로팅 텍스트 프레임 + TABLE + 그룹 내부 FIGURE: z-order 순으로 인터리빙
        // TABLE도 플로팅 객체이므로 먼저 모두 배치하면 뒤늦게 나온 그림이 표 텍스트를 덮을 수 있다.
        List<ASTBlock> inFrontBlocks = new ArrayList<>();
        for (ASTTextFrameBlock block : floatingBlocks) {
            inFrontBlocks.add(block);
        }
        for (ASTBlock block : otherBlocks) {
            if (block.blockType() == ASTBlock.BlockType.TABLE) {
                inFrontBlocks.add(block);
                continue;
            }
            if (block.blockType() == ASTBlock.BlockType.FIGURE) {
                ASTFigure fig = (ASTFigure) block;
                if (!isBehindTextPlaneFigure(fig)) {
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
                textBoxBuilder.convertTextFrameBlock(secPrPara, (ASTTextFrameBlock) block);
            } else if (block.blockType() == ASTBlock.BlockType.TABLE) {
                tableBuilder.convertTable(secPrPara, (ASTTable) block);
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
        if (block instanceof ASTTable) return ((ASTTable) block).zOrder();
        return 0;
    }

    private static int textlessGraphicZOrderOf(ASTFigure fig) {
        if (fig == null) return 0;
        return VisualPlanePolicy.textlessGraphicZOrderName(fig.visualLayer(), fig.zOrder());
    }

    private static boolean isBehindTextPlaneFigure(ASTFigure fig) {
        if (fig == null) return false;
        String layer = fig.visualLayer();
        if (VisualPlanePolicy.isBehindTextLayerName(layer)) {
            return true;
        }
        if (isTextShellVisualLayer(layer)) return false;
        if (fig.fromGroup()) return false;
        return layer == null || layer.isEmpty();
    }

    private static boolean isTextShellVisualLayer(String layer) {
        return "CONTAINER_BACKDROP".equals(layer)
                || "CONTENT_BACKDROP".equals(layer)
                || "TEXT_CARD_BACKDROP".equals(layer)
                || "LABEL_CONNECTOR_BACKDROP".equals(layer)
                || "LABEL_BACKDROP".equals(layer)
                || "LABEL_OVERLAY_BACKDROP".equals(layer)
                || "CONTAINER_OUTLINE".equals(layer)
                || "FOREGROUND_MASK".equals(layer);
    }

    private static boolean isInFrontPlannedTextFrameVisual(ASTTextFrameBlock block) {
        if (block == null) return false;
        return VisualPlanePolicy.isInFrontLayerName(block.plannedShellVisualLayer());
    }

    private static long figureArea(ASTFigure fig) {
        long width = Math.max(0L, fig.width());
        long height = Math.max(0L, fig.height());
        if (width == 0L || height == 0L) return 0L;
        if (width > Long.MAX_VALUE / height) return Long.MAX_VALUE;
        return width * height;
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
                .tabStopAnd(ConverterConstants.TAB_STOP)
                .tabStopValAnd(ConverterConstants.TAB_STOP_VAL)
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

        // 마진: 모든 콘텐츠가 절대 좌표(PAPER 기준)로 배치되므로 0으로 설정.
        // 한글 뷰어가 BEHIND_TEXT 이미지를 본문 영역으로 클리핑하기 때문에
        // 마진이 있으면 페이지 배경 PNG가 잘림.
        int mTop = 0;
        int mBottom = 0;
        int mLeft = 0;
        int mRight = 0;
        int headerFooter = 0;

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
        secPr.footNotePr().noteSpacing()
                .betweenNotesAnd(ConverterConstants.FOOTNOTE_BETWEEN_NOTES)
                .belowLineAnd(ConverterConstants.FOOTNOTE_BELOW_LINE)
                .aboveLine(ConverterConstants.FOOTNOTE_ABOVE_LINE);
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
        secPr.endNotePr().noteSpacing()
                .betweenNotesAnd(0)
                .belowLineAnd(ConverterConstants.FOOTNOTE_BELOW_LINE)
                .aboveLine(ConverterConstants.FOOTNOTE_ABOVE_LINE);
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
