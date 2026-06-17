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
 * 회전/라운드 코너 플로팅 글상자 변환 (W4 Step E).
 * HWPX Table은 회전 미지원, cornerRadius 직접 반영도 어려움 →
 * Rectangle(DrawTextBox)로 대체 변환.
 * HwpxTextBoxBuilder에서 분리됨.
 */
final class FrameTransformations {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;
    private final HwpxTextBoxBuilder textBoxBuilder;

    FrameTransformations(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder,
                          HwpxTextBoxBuilder textBoxBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
        this.textBoxBuilder = textBoxBuilder;
    }

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

    /**
     * 회전이 있는 플로팅 텍스트 프레임 → Rectangle(DrawTextBox)로 변환.
     * HWPX Table은 회전을 지원하지 않으므로, 회전이 필요한 블록은 DrawTextBox를 사용한다.
     */
    void convertRotatedFloatingBlock(Para framePara, ASTTextFrameBlock block,
                                              long w, long h, short rotAngle) {
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        TextWrapMethod wrapMethod = block.isBackgroundOnly()
                ? TextWrapMethod.BEHIND_TEXT
                : TextWrapMethod.IN_FRONT_OF_TEXT;
        int zOrder = block.isBackgroundOnly() ? 0 : block.zOrder();

        rect.idAnd(shapeId)
                .zOrderAnd(zOrder)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(wrapMethod)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(block.paragraphs()));

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, 0L);
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        // 텍스트 글상자는 회전 미적용 (HWPX 회전이 위치 오프셋 유발, 텍스트는 항상 정방향)
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        textBoxBuilder.drawTextBoxComposer().apply(
                rect,
                DrawTextBoxComposer.fromTextFrameBlock(block, w, h));

        // Rectangle 꼭짓점 (필수 요소)
        DrawTextBoxComposer.applyRectangleGeometry(rect, w, h, 0, h);

        rect.createPos();
        rect.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(block.anchoredFlowWithText())
                .allowOverlapAnd(!block.anchoredFlowWithText())
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(block.y())
                .horzOffset(block.x());

        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }

    /**
     * 라운드 코너가 있는 플로팅 블록 → Rectangle + DrawText로 변환.
     * Table 방식 대신 사용하여 cornerRadius가 직접 반영되도록 한다.
     */
    void convertRoundedFloatingBlock(Para framePara, ASTTextFrameBlock block,
                                              long w, long h) {
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Rectangle rect = anchorRun.addNewRectangle();
        String shapeId = HwpxUtil.nextShapeId();

        TextWrapMethod wrapMethod = block.isBackgroundOnly()
                ? TextWrapMethod.BEHIND_TEXT
                : TextWrapMethod.IN_FRONT_OF_TEXT;
        int zOrder = block.isBackgroundOnly() ? 0 : block.zOrder();

        rect.idAnd(shapeId)
                .zOrderAnd(zOrder)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(wrapMethod)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(block.paragraphs()));

        // ShapeComponent
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        // 라운드 모서리(stadium 등)가 정확히 그려지도록 실제 높이 사용 (height=0 auto 일 때 일부 렌더러가 ratio 무시)
        rect.curSz().set(w, h);
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        boolean textOnlyOverlay = block.plannedVisualTextOverlay();
        // FillBrush (배경색) — 래퍼에 둥근 모서리가 있으면 셀 배경 투명 (래퍼 사각형이 배경 역할)
        String fillColor = textOnlyOverlay ? null : block.fillColor();
        double fillTint = block.fillTint();
        if (!textOnlyOverlay && block.hasWrapperFill() && block.cornerRadius() > 0) {
            // 둥근 모서리 래퍼: 셀 fill 생략 → 래퍼 Rectangle의 둥근 배경이 보임
            fillColor = null;
        } else if (!textOnlyOverlay && (fillColor == null || !fillColor.startsWith("#")) && block.hasWrapperFill()) {
            fillColor = "#FFFFFF";
            fillTint = 100;
        }

        // 인라인 텍스트 프레임 균등 분배
        long roundedContentWidth = w - block.insetLeft() - block.insetRight();
        HwpxTextBoxBuilder.redistributeInlineTextFrameWidths(block.paragraphs(), roundedContentWidth);
        DrawTextBoxComposer.Spec spec = DrawTextBoxComposer.fromTextFrameBlock(block, w, h);
        spec.fillColor = fillColor;
        spec.fillTint = fillTint;
        if (textOnlyOverlay) {
            spec.strokeColor = null;
            spec.strokeWeight = 0;
            spec.imageFillData = null;
            spec.nativeGraphicsAllowed = false;
            spec.forceImageFill = false;
        }
        long savedContainerWidth = ctx.currentContainerWidth;
        ctx.currentContainerWidth = roundedContentWidth;
        textBoxBuilder.drawTextBoxComposer().apply(rect, spec);
        ctx.currentContainerWidth = savedContainerWidth;

        // Rectangle 꼭짓점 + 라운드 코너
        DrawTextBoxComposer.applyRectangleGeometry(
                rect,
                w,
                h,
                textOnlyOverlay ? 0 : block.cornerRadius(),
                h);

        rect.createPos();
        rect.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(block.anchoredFlowWithText())
                .allowOverlapAnd(!block.anchoredFlowWithText())
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(block.y())
                .horzOffset(block.x());

        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }
}
