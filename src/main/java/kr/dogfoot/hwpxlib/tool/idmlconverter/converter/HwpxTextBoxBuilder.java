package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * ASTTextFrameBlock / ASTInlineObject(INLINE_TEXT_FRAME) → HWPX 글상자(Rectangle+DrawText)
 * 또는 1x1 테이블(플로팅 텍스트 프레임)로 변환한다.
 */
class HwpxTextBoxBuilder {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;

    HwpxTextBoxBuilder(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
    }

    // ── 인라인 글상자 (treatAsChar=true) ──

    /**
     * ASTTextFrameBlock → hp:rect + hp:drawText (글상자, treatAsChar="1")
     */
    void addTextBox(SectionXMLFile sectionFile, ASTTextFrameBlock block) {
        // 앵커 단락
        Para framePara = sectionFile.addNewPara();
        framePara.idAnd(ASTToHwpxConverter.nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        long w = block.width();
        if (w < ConverterConstants.MIN_TEXT_BOX_WIDTH) w = ConverterConstants.MIN_TEXT_BOX_WIDTH;

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = ASTToHwpxConverter.nextShapeId();

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
        rect.instidAnd(ASTToHwpxConverter.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        long textBoxMinH = ConverterConstants.MIN_TEXT_BOX_HEIGHT;
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
            paragraphBuilder.addParagraphToSubList(subList, para);
        }
        HwpxParagraphBuilder.removeTrailingEmptyHwpxPara(subList);

        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
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

    // ── 글상자 테두리/배경 ──

    /**
     * 글상자 테두리 설정
     */
    void setupTextBoxLineShape(Rectangle rect, String strokeColor, double strokeWeightPt,
                                String strokeType, double strokeTint) {
        rect.createLineShape();
        boolean hasStroke = strokeColor != null && strokeColor.startsWith("#") && strokeWeightPt > 0;

        if (hasStroke) {
            // points → hwpunit (1pt = 약 100 hwpunit for line width)
            int strokeW = (int) Math.round(strokeWeightPt * 100);
            if (strokeW < 14) strokeW = 14;
            float alpha = (float) ((100.0 - strokeTint) / 100.0);
            LineType2 lineType = HwpxParagraphBuilder.strokeTypeToLineType(strokeType);

            rect.lineShape().colorAnd(strokeColor).widthAnd(strokeW)
                    .styleAnd(lineType)
                    .endCapAnd(LineCap.FLAT)
                    .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                    .headfillAnd(true).tailfillAnd(true)
                    .headSzAnd(ArrowSize.SMALL_SMALL).tailSzAnd(ArrowSize.SMALL_SMALL)
                    .outlineStyleAnd(OutlineStyle.NORMAL).alphaAnd(alpha);
        } else {
            rect.lineShape().colorAnd("#000000").widthAnd(0)
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
    void setupTextBoxFillBrush(Rectangle rect, String fillColor, double fillTint) {
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
    VerticalAlign2 mapVerticalJustification(String vj) {
        return HwpxEnumMapper.mapVerticalJustification(vj);
    }

    // ── 플로팅 텍스트 프레임 블록 변환 (1x1 테이블) ──

    /**
     * 플로팅 TEXT_FRAME_BLOCK → 1x1 Table (PAPER 기준 절대 좌표).
     * 글상자(rect+drawText) 대신 1x1 테이블을 사용하여 클릭만으로 텍스트 편집 가능.
     */
    void convertTextFrameBlock(Para framePara, ASTTextFrameBlock block) {
        long x = block.x();
        long y = block.y();
        long w = block.width();
        long h = block.height();

        // 음수 또는 0 크기 블록 건너뜀 (페이지 밖 객체)
        if (w <= 0 || h <= 0) return;

        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Table table = anchorRun.addNewTable();

        // ShapeObject — 배경 전용 블록은 BEHIND_TEXT + z-order=0, 일반은 IN_FRONT_OF_TEXT
        TextWrapMethod wrapMethod = block.isBackgroundOnly()
                ? TextWrapMethod.BEHIND_TEXT
                : TextWrapMethod.IN_FRONT_OF_TEXT;
        int zOrder = block.isBackgroundOnly() ? 0 : block.zOrder();
        String tableId = ASTToHwpxConverter.nextShapeId();
        table.idAnd(tableId)
                .zOrderAnd(zOrder)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(wrapMethod)
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
        VerticalAlign2 cellVAlign = mapVerticalJustification(block.verticalJustification());
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(cellVAlign);

        // 단락 추가
        for (ASTParagraph para : block.paragraphs()) {
            paragraphBuilder.addParagraphToSubList(subList, para);
        }
        HwpxParagraphBuilder.removeTrailingEmptyHwpxPara(subList);

        // 빈 텍스트 프레임 방지
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }
    }

    /**
     * 텍스트 프레임 블록의 테두리/배경을 BorderFill로 생성.
     * 테두리가 없는 경우 NONE으로, 배경이 없는 경우 투명으로 설정.
     */
    String createTextFrameBorderFill(ASTTextFrameBlock block) {
        String bfId = String.valueOf(ctx.borderFillIdCounter.getAndIncrement());
        BorderFill bf = ctx.hwpxFile.headerXMLFile().refList().borderFills().addNew();

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
            lineWidth = HwpxParagraphBuilder.hwpunitToLineWidth((long) Math.round(block.strokeWeight()));
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

    // ── 인라인 텍스트 프레임 (글상자, treatAsChar=true) ──

    /**
     * 인라인 텍스트 프레임 / 그룹 → hp:rect + hp:drawText (글상자, treatAsChar="1")
     */
    void addInlineTextFrame(Para para, ASTInlineObject obj) {
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return;

        long w = obj.width() > 0 ? obj.width() : 5000;
        if (w < ConverterConstants.MIN_TEXT_BOX_WIDTH) w = ConverterConstants.MIN_TEXT_BOX_WIDTH;
        long inlineMinH = ConverterConstants.MIN_TEXT_BOX_HEIGHT;
        long h = obj.height() > inlineMinH ? obj.height() : inlineMinH;

        // IDML 속성 기반 래핑 모드 결정 (크기 기반 폴백은 이미지에만 적용, 텍스트프레임은 인라인 유지)
        boolean isAnchored = "Anchored".equals(obj.anchoredPosition());
        String wrapMode = obj.textWrapMode();
        boolean useWrapping = isAnchored && wrapMode != null && !"None".equals(wrapMode);

        TextWrapMethod twm;
        TextFlowSide tfs;
        if (obj.isOverlay()) {
            // 오버레이: 이미지 위에 겹쳐서 표시, 텍스트 흐름에 영향 없음
            twm = TextWrapMethod.IN_FRONT_OF_TEXT;
            tfs = TextFlowSide.BOTH_SIDES;
        } else if (useWrapping) {
            twm = HwpxImageBuilder.mapTextWrapMethod(wrapMode);
            tfs = HwpxImageBuilder.mapTextFlowSide(obj.textWrapSide());
        } else {
            twm = TextWrapMethod.TOP_AND_BOTTOM;
            tfs = TextFlowSide.BOTH_SIDES;
        }

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Rectangle rect = run.addNewRectangle();
        String shapeId = ASTToHwpxConverter.nextShapeId();

        // ShapeObject
        rect.idAnd(shapeId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(twm)
                .textFlowAnd(tfs)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(ASTToHwpxConverter.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, 0L); // height=0: 내용에 맞게 자동 확장
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // LineShape — 부모 Group 배경 사각형의 테두리
        setupTextBoxLineShape(rect, obj.strokeColor(), obj.strokeWeight(), "Solid", obj.strokeTint());

        // FillBrush — 부모 Group 배경 사각형의 채우기 색상
        setupTextBoxFillBrush(rect, obj.fillColor(), obj.fillTint());

        // DrawText
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);
        dt.createTextMargin();
        dt.textMargin().leftAnd(obj.textMarginLeft()).rightAnd(obj.textMarginRight())
                .topAnd(obj.textMarginTop()).bottomAnd(obj.textMarginBottom());

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
            paragraphBuilder.addParagraphToSubList(subList, astPara);
        }

        // 인라인 테이블 → SubList 내 인라인 테이블
        if (obj.inlineTables() != null && ctx.tableBuilderRef != null) {
            for (ASTTable astTable : obj.inlineTables()) {
                ctx.tableBuilderRef.addInlineTableToSubList(subList, astTable);
            }
        }
        HwpxParagraphBuilder.removeTrailingEmptyHwpxPara(subList);

        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }

        // Rectangle 꼭짓점
        rect.ratioAnd((short) 0);
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        // ShapeSize
        rect.createSZ();
        rect.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition
        rect.createPos();
        if (obj.isOverlay()) {
            // 오버레이 모드 — 이미지 컨테이너(rect+imgBrush) 내부의 drawText 단락 기준
            // PARA 기준 상대 좌표로 배치 (컨테이너 내부이므로 PARA = 이미지 영역)
            rect.pos().treatAsCharAnd(false)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(true)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.TOP)
                    .horzAlignAnd(HorzAlign.LEFT)
                    .vertOffsetAnd(obj.overlayY())
                    .horzOffset(obj.overlayX());
        } else if (useWrapping) {
            // 어울리기/자리차지 — 단락 기준 플로팅
            rect.pos().treatAsCharAnd(false)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(false)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.TOP)
                    .horzAlignAnd(HorzAlign.CENTER)
                    .vertOffsetAnd(0L)
                    .horzOffset(0L);
        } else {
            // 기존 인라인 (글자처럼 취급)
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
        }

        // OutMargin — IDML TextWrapOffset 반영
        rect.createOutMargin();
        if (obj.isOverlay()) {
            // 오버레이: 겹침 허용, 마진 없음
            rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        } else if (useWrapping) {
            rect.outMargin().leftAnd(obj.textWrapLeft()).rightAnd(obj.textWrapRight())
                    .topAnd(obj.textWrapTop()).bottomAnd(obj.textWrapBottom());
        } else {
            rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        }
    }
}
