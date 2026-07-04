package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * 페이지 레벨 오버레이 변환 (W4 Step B).
 * 한글이 테이블 셀 SubList 내부 플로팅 객체를 지원하지 않으므로,
 * 셀 내부 오버레이를 페이지 PAPER 기준 절대 좌표 객체로 승격한다.
 *
 * <p>원본 shell(fill/stroke/image)을 가진 overlay shell+TF는 atomic visual label로 보고
 * hp:rect + drawText carrier로 만든다. shell 없는 legacy text-only overlay만 마지막 폴백으로
 * 1x1 table carrier를 사용한다.</p>
 *
 * HwpxTextBoxBuilder에서 분리됨. ctx + paragraphBuilder에 의존하므로 인스턴스 클래스.
 */
final class PageOverlayBuilder {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;
    private final HwpxTextBoxBuilder textBoxBuilder;

    PageOverlayBuilder(
            HwpxConverterContext ctx,
            HwpxParagraphBuilder paragraphBuilder,
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
     * 셀 내부 오버레이를 페이지 레벨 PAPER 기준 절대 좌표 rect로 변환한다.
     * 한글(HWPX 렌더러)이 테이블 셀 SubList 내부의 플로팅 객체를 지원하지 않으므로,
     * 오버레이 텍스트프레임을 페이지 레벨 IN_FRONT_OF_TEXT로 승격한다.
     */
    public void addPageLevelOverlay(Para anchorPara, ASTInlineObject obj, long pageX, long pageY) {
        if (obj.paragraphs() == null || obj.paragraphs().isEmpty()) return;

        long w = obj.width() > 0 ? obj.width() : 5000;
        if (w < ConverterConstants.MIN_TEXT_BOX_WIDTH) w = ConverterConstants.MIN_TEXT_BOX_WIDTH;
        long h = obj.height() > 0 ? obj.height() : 1000;

        if (shouldUseDrawTextOverlay(obj)) {
            addDrawTextOverlay(anchorPara, obj, pageX, pageY, w, h);
            return;
        }

        Run run = anchorPara.addNewRun();
        run.charPrIDRef("0");

        // hp:tbl (1x1 table) — 한글에서 hp:rect의 IN_FRONT_OF_TEXT가
        // 이미지 위에 올바르게 렌더링되지 않으므로 hp:tbl 사용
        Table table = run.addNewTable();
        String tableId = HwpxUtil.nextShapeId();

        table.idAnd(tableId)
                .zOrderAnd(1000)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(obj.paragraphs()));

        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) 1)
                .colCntAnd((short) 1)
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        table.createSZ();
        table.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

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
                .vertOffsetAnd(pageY)
                .horzOffset(pageX);

        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 1행 1열 셀
        Tr tr = table.addNewTr();
        Tc tc = tr.addNewTc();

        String cellBfId = createOverlayBorderFill(obj);

        tc.nameAnd("")
                .headerAnd(false)
                .hasMarginAnd(true)
                .protectAnd(false)
                .editableAnd(true)
                .dirtyAnd(false)
                .borderFillIDRefAnd(cellBfId);

        tc.createCellAddr();
        tc.cellAddr().colAddrAnd((short) 0).rowAddrAnd((short) 0);
        tc.createCellSpan();
        tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);
        tc.createCellSz();
        tc.cellSz().widthAnd(w).heightAnd(h);
        // 셀 여백: 페이지 레벨 승격 오버레이는 위치 기반 implicit margin은 쓰지 않되,
        // 원본 TextFrame의 insetSpacing에서 온 명시적 텍스트 여백은 보존한다.
        tc.createCellMargin();
        tc.cellMargin()
                .leftAnd(obj.textMarginLeft())
                .rightAnd(obj.textMarginRight())
                .topAnd(obj.textMarginTop())
                .bottomAnd(obj.textMarginBottom());

        tc.createSubList();
        SubList subList = tc.subList();
        VerticalAlign2 vAlign = HwpxEnumMapper.mapVerticalJustification(obj.verticalJustification());
        subList.idAnd("").textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(HwpxTextBoxBuilder.inlineTextFrameLineWrap(obj))
                .vertAlignAnd(vAlign)
                .linkListIDRefAnd("0").linkListNextIDRefAnd("0");

        for (ASTParagraph astPara : obj.paragraphs()) {
            paragraphBuilder.addParagraphToSubList(subList, astPara);
        }
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }
    }

    /**
     * 오버레이 ASTInlineObject의 fill/stroke를 반영하는 BorderFill을 생성한다.
     */
    private String createOverlayBorderFill(ASTInlineObject obj) {
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

        // 테두리
        String stroke = obj.strokeColor();
        boolean hasStroke = HwpxTextBoxBuilder.nativeTextBoxGraphicsEnabled()
                && stroke != null && stroke.startsWith("#") && obj.strokeWeight() > 0;

        LineType2 lineType = LineType2.NONE;
        LineWidth lineWidth = LineWidth.MM_0_1;
        String borderColor = "#000000";

        if (hasStroke) {
            lineType = LineType2.SOLID;
            lineWidth = HwpxParagraphBuilder.hwpunitToLineWidth((long) Math.round(obj.strokeWeight()));
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

        // 배경 채우기 (fillTint는 색상 농도로 RGB에 적용, 불투명 처리)
        String fill = obj.fillColor();
        if (HwpxTextBoxBuilder.nativeTextBoxGraphicsEnabled()
                && fill != null && fill.startsWith("#")) {
            String tinted = HwpxTextBoxBuilder.blendColorWithWhite(fill, obj.fillTint() / 100.0);
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush()
                    .faceColorAnd(tinted)
                    .hatchColorAnd("#FF000000")
                    .alphaAnd(0f);
        }

        return bfId;
    }

    private boolean shouldUseDrawTextOverlay(ASTInlineObject obj) {
        if (obj == null) return false;
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) return true;
        String fill = obj.fillColor();
        if (fill != null && fill.startsWith("#")) return true;
        String stroke = obj.strokeColor();
        return stroke != null && stroke.startsWith("#") && obj.strokeWeight() > 0;
    }

    private void addDrawTextOverlay(
            Para anchorPara,
            ASTInlineObject obj,
            long pageX,
            long pageY,
            long w,
            long h) {
        Run run = anchorPara.addNewRun();
        run.charPrIDRef("0");

        Rectangle rect = run.addNewRectangle();
        rect.idAnd(HwpxUtil.nextShapeId())
                .zOrderAnd(1000)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(obj.paragraphs()));

        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(HwpxUtil.nextShapeId());
        applyOverlayGeometry(rect, w, h);

        DrawTextBoxComposer.Spec spec = DrawTextBoxComposer.fromInlineObject(obj, w, h);
        if (obj.imageFillData() != null && obj.imageFillData().length > 0) {
            spec.imageFillData = obj.imageFillData();
            spec.forceImageFill = true;
        }
        spec.nativeGraphicsAllowed = true;
        textBoxBuilder.drawTextBoxComposer().apply(rect, spec);
        if (rect.drawText() != null) {
            rect.drawText().editableAnd(true);
        }
        DrawTextBoxComposer.applyRectangleGeometry(
                rect,
                w,
                h,
                obj.cornerRadius(),
                h,
                obj.shellShapeType());

        rect.createPos();
        rect.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(pageY)
                .horzOffset(pageX);

        rect.createOutMargin();
        rect.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }

    private static void applyOverlayGeometry(Rectangle rect, long w, long h) {
        rect.createOffset();
        rect.offset().set(0L, 0L);
        rect.createOrgSz();
        rect.orgSz().set(w, h);
        rect.createCurSz();
        rect.curSz().set(w, h);
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);
        rect.createRotationInfo();
        rect.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2)
                .centerYAnd(h / 2)
                .rotateimageAnd(true);
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.createSZ();
        rect.sz().widthAnd(w)
                .widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h)
                .heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);
    }
}
