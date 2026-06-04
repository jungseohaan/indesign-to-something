package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * 인라인 텍스트 프레임 변환 (W4 Step C).
 * ASTInlineObject(INLINE_TEXT_FRAME) → hp:rect + hp:drawText (treatAsChar="1").
 * HwpxTextBoxBuilder에서 분리됨.
 */
final class InlineFrameBuilder {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;
    private final HwpxTextBoxBuilder textBoxBuilder;

    InlineFrameBuilder(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder,
                       HwpxTextBoxBuilder textBoxBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
        this.textBoxBuilder = textBoxBuilder;
    }

    /** resolveDropCapStyle 복제 (paragraphBuilder 의존). */
    private DropCapStyle resolveDropCapStyle(java.util.List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return DropCapStyle.None;
        ASTParagraph firstPara = paragraphs.get(0);
        String styleRef = firstPara.paragraphStyleRef();
        if (styleRef == null) return DropCapStyle.None;
        ASTStyleDef style = paragraphBuilder.findParagraphStyle(styleRef);
        if (style == null || style.dropCapLines() == null) return DropCapStyle.None;
        if (style.dropCapLines() >= 3) return DropCapStyle.TripleLine;
        if (style.dropCapLines() >= 2) return DropCapStyle.DoubleLine;
        return DropCapStyle.None;
    }

    // ── 인라인 텍스트 프레임 (글상자, treatAsChar=true) ──

    /**
     * 인라인 텍스트 프레임 / 그룹 → hp:rect + hp:drawText (글상자, treatAsChar="1")
     */
    void addInlineTextFrame(Para para, ASTInlineObject obj) {
        boolean hasParagraphs = obj.paragraphs() != null && !obj.paragraphs().isEmpty();
        boolean hasInlineTables = obj.inlineTables() != null && !obj.inlineTables().isEmpty();
        if (!hasParagraphs && !hasInlineTables) return;

        // 테이블 셀 내부 오버레이 → 페이지 레벨로 승격
        // 한글(HWPX 렌더러)이 테이블 셀 SubList 내부의 플로팅 객체를 지원하지 않으므로
        // 페이지 레벨 PAPER 기준 절대 좌표로 변환한다.
        if (obj.isOverlay() && ctx.insideTableCell) {
            HwpxConverterContext.DeferredOverlay d = new HwpxConverterContext.DeferredOverlay();
            d.overlay = obj;
            d.pageX = ctx.blockPageX + ctx.blockInsetLeft + obj.overlayX();
            d.pageY = ctx.blockPageY + ctx.blockInsetTop + obj.overlayY();
            ctx.deferredOverlays.add(d);
            return;
        }

        long w = obj.width() > 0 ? obj.width() : 5000;
        if (w < ConverterConstants.MIN_TEXT_BOX_WIDTH) w = ConverterConstants.MIN_TEXT_BOX_WIDTH;

        // 단일 단락 안에 2+ ITF 자식 → 다단 래퍼 → 부모 폭으로 확장
        // (InDesign에서 나란히 배치되는 다단 레이아웃)
        // 복수 단락 ITF(각 행이 독립 단락인 배지 그룹의 RIGHT 컬럼)는 확장하지 않음
        if (ctx.currentContainerWidth > w && obj.paragraphs() != null && obj.paragraphs().size() == 1) {
            int innerCount = 0;
            for (ASTParagraph p : obj.paragraphs()) {
                for (ASTInlineItem it : p.items()) {
                    if (it.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT
                            && ((ASTInlineObject) it).kind() == ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) {
                        innerCount++;
                    }
                }
            }
            if (innerCount >= 2) {
                w = ctx.currentContainerWidth;
            }
        }

        // IDML에서 높이가 명시된 경우 그대로 사용 (최소값 강제 안 함)
        long h = obj.height() > 0 ? obj.height() : ConverterConstants.MIN_TEXT_BOX_HEIGHT;

        // IDML 속성 기반 래핑 모드 결정 (크기 기반 폴백은 이미지에만 적용, 텍스트프레임은 인라인 유지)
        boolean isAnchored = "Anchored".equals(obj.anchoredPosition());
        boolean isAboveLine = obj.anchoredPosition() != null
                && obj.anchoredPosition().startsWith("AboveLine");
        String wrapMode = obj.textWrapMode();
        boolean hasExplicitWrap = wrapMode != null && !"None".equals(wrapMode);
        // anchoredPosition이 있는 경우에만 어울림 적용
        // anchoredPosition 없는 순수 인라인 TextFrame은 treatAsChar=true로 유지 (나란히 배치)
        boolean useWrapping = isAboveLine
                || (isAnchored && hasExplicitWrap);

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
            // 인라인(treatAsChar=true) rect: SQUARE로 같은 줄에 텍스트 배치
            // TOP_AND_BOTTOM은 rect 뒤 텍스트를 다음 줄로 밀어냄
            twm = TextWrapMethod.SQUARE;
            tfs = TextFlowSide.BOTH_SIDES;
        }

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Rectangle rect = run.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        // ShapeObject
        rect.idAnd(shapeId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(twm)
                .textFlowAnd(tfs)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(obj.paragraphs()));

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, h); // treatAsChar=true일 때 줄 높이에 반영되도록 명시적 높이 설정
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
        textBoxBuilder.setupTextBoxLineShape(rect, obj.strokeColor(), obj.strokeWeight(), "Solid", obj.strokeTint());

        // FillBrush — 배경 PNG가 있으면 imgBrush, 없으면 winBrush(solid color)
        boolean imgBrushSet = false;
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) {
            imgBrushSet = textBoxBuilder.setupTextBoxImgBrush(rect, obj.imageFillData());
        }
        if (!imgBrushSet) {
            textBoxBuilder.setupTextBoxFillBrush(rect, obj.fillColor(), obj.fillTint());
        }

        // DrawText
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(w).nameAnd("").editableAnd(false);
        dt.createTextMargin();
        dt.textMargin().leftAnd(obj.textMarginLeft()).rightAnd(obj.textMarginRight())
                .topAnd(obj.textMarginTop()).bottomAnd(obj.textMarginBottom());

        dt.createSubList();
        SubList subList = dt.subList();
        VerticalAlign2 inlineVAlign = HwpxEnumMapper.mapVerticalJustification(obj.verticalJustification());
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(inlineVAlign)
                .linkListIDRefAnd("0")
                .linkListNextIDRefAnd("0")
                .textWidthAnd(0)
                .textHeightAnd(0)
                .hasTextRefAnd(false)
                .hasNumRefAnd(false);

        // 인라인 텍스트 프레임 균등 분배 (재귀: 인라인 프레임 안에 또 인라인 프레임)
        // 이 ITF 자신의 폭을 기준으로 분배 (부모 폭 사용 시 2-column 분할된 rightITF 내부에서 오버플로 발생)
        long savedContainerWidth = ctx.currentContainerWidth;
        ctx.currentContainerWidth = w;
        if (obj.paragraphs() != null) {
            long redistWidth = w - obj.textMarginLeft() - obj.textMarginRight();
            if (redistWidth <= 0) redistWidth = w;
            HwpxTextBoxBuilder.redistributeInlineTextFrameWidths(obj.paragraphs(), redistWidth);
        }

        // 내용 단락 (풀 버전 — 인라인 객체도 재귀 처리)
        if (obj.paragraphs() != null) {
            for (ASTParagraph astPara : obj.paragraphs()) {
                paragraphBuilder.addParagraphToSubList(subList, astPara);
            }
        }
        ctx.currentContainerWidth = savedContainerWidth;

        // 인라인 테이블 → SubList 내 인라인 테이블
        if (obj.inlineTables() != null && ctx.tableBuilderRef != null) {
            for (ASTTable astTable : obj.inlineTables()) {
                ctx.tableBuilderRef.addInlineTableToSubList(subList, astTable);
            }
        }
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }

        // Rectangle 꼭짓점
        rect.ratioAnd(HwpxTextBoxBuilder.computeCornerRatio(obj.cornerRadius(), w, h));
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
            // 인라인 (글자처럼 취급): 배지 등 인라인 도형은 행간을 팽창시키지 않도록 affectLSpacing=false
            // vertAlign=CENTER로 줄 내 수직 중앙 배치
            rect.pos().treatAsCharAnd(true)
                    .affectLSpacingAnd(false)
                    .flowWithTextAnd(true)
                    .allowOverlapAnd(false)
                    .holdAnchorAndSOAnd(false)
                    .vertRelToAnd(VertRelTo.PARA)
                    .horzRelToAnd(HorzRelTo.PARA)
                    .vertAlignAnd(VertAlign.CENTER)
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
