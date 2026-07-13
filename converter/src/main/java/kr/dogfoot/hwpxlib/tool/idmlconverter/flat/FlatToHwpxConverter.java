package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

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
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.FontRegistry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry.StyleRegistry;

import java.util.*;

/**
 * Flat AST → HWPX 변환기.
 * FlatDocumentGateway를 통해 페이지/노드를 순회하고,
 * FlatNodeAdapter로 AST 타입을 생성하여 기존 4대 빌더에 위임한다.
 *
 * semantic layer 기반 렌더링 순서:
 *   BACKGROUND → CONTENT → FOREGROUND → deferred overlays
 */
public class FlatToHwpxConverter {

    // ── 정적 팩토리 ──

    public static ConvertResult convert(FlatDocument flatDoc) throws ConvertException {
        return convert(flatDoc, ProgressReporter.NONE, null);
    }

    public static ConvertResult convert(FlatDocument flatDoc, ProgressReporter reporter,
                                         Map<String, String> customFontMap) throws ConvertException {
        return convert(flatDoc, reporter, customFontMap, null);
    }

    public static ConvertResult convert(FlatDocument flatDoc, ProgressReporter reporter,
                                         Map<String, String> customFontMap,
                                         kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper fontMapper) throws ConvertException {
        return convert(flatDoc, reporter, customFontMap, fontMapper, null);
    }

    public static ConvertResult convert(FlatDocument flatDoc, ProgressReporter reporter,
                                         Map<String, String> customFontMap,
                                         kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper fontMapper,
                                         kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig config) throws ConvertException {
        try {
            FlatToHwpxConverter converter = new FlatToHwpxConverter(flatDoc, reporter, customFontMap, fontMapper);
            converter.config = config;
            return converter.doConvert();
        } catch (ConvertException ce) {
            throw ce;
        } catch (Exception e) {
            throw new ConvertException(ConvertException.Phase.HWPX_GENERATION,
                    "Flat→HWPX conversion failed: " + e.getMessage(), e);
        }
    }

    // ── 인스턴스 필드 ──

    private final FlatDocumentGateway gateway;
    private final FlatNodeAdapter adapter;
    private final ConvertResult result;
    private final ProgressReporter reporter;
    private final Map<String, String> customFontMap;
    private final kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper fontMapper;
    private kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionConfig config;

    private int pagesConverted;

    private HwpxConverterContext ctx;
    private HwpxParagraphBuilder paragraphBuilder;
    private HwpxTextBoxBuilder textBoxBuilder;
    private HwpxTableBuilder tableBuilder;
    private HwpxImageBuilder imageBuilder;

    private FlatToHwpxConverter(FlatDocument flatDoc, ProgressReporter reporter,
                                 Map<String, String> customFontMap,
                                 kr.dogfoot.hwpxlib.tool.idmlconverter.converter.FontMapper fontMapper) {
        this.gateway = new FlatDocumentGateway(flatDoc);
        this.adapter = new FlatNodeAdapter(gateway);
        this.result = new ConvertResult();
        this.reporter = reporter;
        this.customFontMap = customFontMap;
        this.fontMapper = fontMapper;
    }

    // ── 변환 메인 ──

    private ConvertResult doConvert() throws ConvertException {
        System.err.println("[FlatToHwpxConverter] Starting conversion...");
        System.err.println("[FlatToHwpx] Pages: " + gateway.pageCount()
                + ", Nodes: " + gateway.totalNodeCount()
                + ", Components: " + gateway.totalComponentCount());

        // 1. 기본 HWPX 구조 생성
        HWPXFile hwpxFile = BlankFileMaker.make();

        // 2. 레지스트리 초기화
        FontRegistry fontRegistry = new FontRegistry(hwpxFile, customFontMap);
        if (fontMapper != null) {
            fontRegistry.setFontMapper(fontMapper);
        }
        StyleRegistry styleRegistry = new StyleRegistry(hwpxFile, fontRegistry);

        // 3. 컨텍스트 + 빌더 생성
        ctx = new HwpxConverterContext(hwpxFile, styleRegistry, fontRegistry,
                gateway.paragraphStyles());
        ctx.config = config;

        paragraphBuilder = new HwpxParagraphBuilder(ctx);
        textBoxBuilder = new HwpxTextBoxBuilder(ctx, paragraphBuilder);
        tableBuilder = new HwpxTableBuilder(ctx, paragraphBuilder);
        imageBuilder = new HwpxImageBuilder(ctx);

        // 순환 의존 해소 (setter 주입)
        paragraphBuilder.setBuilders(textBoxBuilder, tableBuilder, imageBuilder);
        ctx.tableBuilderRef = tableBuilder;

        // 4. 폰트 등록
        for (ASTFontDef fontDef : gateway.fonts()) {
            String fontFamily = fontDef.fontFamily();
            if (fontFamily != null) {
                ctx.fontRegistry.resolveFontId(fontFamily);
            }
        }

        // 5. 스타일 등록
        for (ASTStyleDef astStyle : gateway.paragraphStyles()) {
            ctx.styleRegistry.registerParagraphStyle(astStyle);
        }
        for (ASTStyleDef astStyle : gateway.characterStyles()) {
            ctx.styleRegistry.registerCharacterStyle(astStyle);
        }

        // 6. 연결 글상자 사전 스캔
        buildStoryLinkMap();

        // 7. 페이지 변환
        SectionXMLFile section0 = hwpxFile.sectionXMLFileList().get(0);
        section0.removeAllParas();

        int totalPages = gateway.pageCount();
        for (FlatPage page : gateway.pages()) {
            convertPage(section0, page);
            pagesConverted++;
            int progress = (int)(80.0 * pagesConverted / Math.max(totalPages, 1));
            reporter.reportProgress(progress, 100,
                    "페이지 변환 중... (" + pagesConverted + "/" + totalPages + ")");
        }

        // 8. 배경 PNG 배치
        for (ASTPageBackground bg : gateway.backgrounds()) {
            if (bg.pngData() != null && bg.pngData().length > 0) {
                imageBuilder.addBackgroundImage(section0, bg);
            }
        }

        // 9. 빈 section 방지
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

        // 변환 중 수집된 경고를 결과에 전달
        for (String w : ctx.warnings()) {
            result.addWarning(w);
        }

        System.err.println("[FlatToHwpxConverter] Done. " + result.summary());
        return result;
    }

    // ── 연결 글상자 스캔 ──

    private void buildStoryLinkMap() {
        int linkIdCounter = 1;
        for (String storyId : gateway.linkedStoryIds()) {
            List<FlatLayoutNode> chain = gateway.linkedFrameChain(storyId);
            List<String> linkIds = new ArrayList<String>();
            for (int i = 0; i < chain.size(); i++) {
                linkIds.add(String.valueOf(linkIdCounter++));
            }
            ctx.storyLinkIds.put(storyId, linkIds);
            ctx.storyLinkIndex.put(storyId, 0);
        }
    }

    // ── 페이지 변환 ──

    private void convertPage(SectionXMLFile sectionFile, FlatPage page) {
        // 현재 섹션의 컬럼 너비 계산
        long mLeft = page.marginLeft() > 0 ? page.marginLeft() : 1417;
        long mRight = page.marginRight() > 0 ? page.marginRight() : 1417;
        long mTop = page.marginTop() > 0 ? page.marginTop() : 1417;
        ctx.currentColumnWidth = page.pageWidth() - mLeft - mRight;
        ctx.pageMarginTop = mTop;
        ctx.pageMarginLeft = mLeft;

        // SecPr 단락 생성
        Para secPrPara = createSectionPara(sectionFile, pagesConverted > 0);

        // Layer 1: BACKGROUND (배경 도형, 배경 텍스트프레임)
        for (FlatLayoutNode node : gateway.backgroundNodes(page.pageId())) {
            dispatchNode(secPrPara, node);
        }

        // Layer 2: CONTENT (테이블, 텍스트프레임, 소형 그룹 도형)
        for (FlatLayoutNode node : gateway.contentNodes(page.pageId())) {
            dispatchNode(secPrPara, node);
        }

        // Layer 3: FOREGROUND (오버레이 등)
        for (FlatLayoutNode node : gateway.foregroundNodes(page.pageId())) {
            dispatchNode(secPrPara, node);
        }

        // Deferred overlays: 셀 내부에서 승격된 오버레이 텍스트박스
        if (!ctx.deferredOverlays.isEmpty()) {
            for (HwpxConverterContext.DeferredOverlay d : ctx.deferredOverlays) {
                textBoxBuilder.addPageLevelOverlay(secPrPara, d.overlay, d.pageX, d.pageY);
                ctx.framesConverted++;
            }
            ctx.deferredOverlays.clear();
        }

        // SecPr run 추가
        addSecPrRun(secPrPara, page);
    }

    // ── 노드 디스패치 ──

    private void dispatchNode(Para para, FlatLayoutNode node) {
        switch (node.nodeType()) {
            case TEXT_FRAME:
                ASTTextFrameBlock tfb = adapter.toTextFrameBlock(node);
                // Textless/background-only graphics are owned by extracted
                // InDesign PNG plans. The flat converter must not synthesize
                // HWP native rects or Java-rendered background PNGs.
                if (!gateway.isBackgroundOnly(node)) {
                    textBoxBuilder.convertTextFrameBlock(para, tfb);
                }
                ctx.framesConverted++;
                break;
            case TABLE:
                tableBuilder.convertTable(para, adapter.toTable(node));
                ctx.framesConverted++;
                break;
            case FIGURE:
                imageBuilder.convertFigure(para, adapter.toFigure(node));
                ctx.framesConverted++;
                break;
            case SPACER:
                // SPACER는 인라인 전용, 플로팅에서는 무시
                break;
        }
    }

    // ── SecPr 생성 ──

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

    private void addSecPrRun(Para para, FlatPage page) {
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
        PageDirection direction = page.pageWidth() > page.pageHeight()
                ? PageDirection.NARROWLY : PageDirection.WIDELY;
        int hwpxWidth, hwpxHeight;
        if (direction == PageDirection.NARROWLY) {
            hwpxWidth = (int) page.pageHeight();
            hwpxHeight = (int) page.pageWidth();
        } else {
            hwpxWidth = (int) page.pageWidth();
            hwpxHeight = (int) page.pageHeight();
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

        // ColPr — 페이지 레벨은 항상 1단
        Ctrl ctrl = run.addNewCtrl();
        ctrl.addNewColPr()
                .idAnd("").typeAnd(MultiColumnType.NEWSPAPER)
                .layoutAnd(ColumnDirection.LEFT)
                .colCountAnd(1).sameSzAnd(true).sameGap(0);

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

    // ── 유틸리티 ──

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

    private static String nextParaId() {
        return HwpxUtil.nextParaId();
    }
}
