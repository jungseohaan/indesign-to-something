package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.HeightRelTo;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.LineWrapMethod;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.TextDirection;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VerticalAlign2;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.WidthRelTo;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;

import java.util.List;

/**
 * Single gateway for converting an already-placed rectangle into a HWPX
 * drawText text box.
 *
 * <p>Placement, wrap, z-order, and positioning stay with the caller because
 * inline and floating objects have genuinely different anchoring semantics.
 * Text/visual shell realization is centralized here so font-run content,
 * fill/stroke/image brush, margins, subList setup, and rounded-rectangle
 * geometry do not diverge by path.</p>
 */
final class DrawTextBoxComposer {
    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;
    private final HwpxTextBoxBuilder textBoxBuilder;

    DrawTextBoxComposer(HwpxConverterContext ctx,
                        HwpxParagraphBuilder paragraphBuilder,
                        HwpxTextBoxBuilder textBoxBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
        this.textBoxBuilder = textBoxBuilder;
    }

    static final class Spec {
        long width;
        long height;
        String strokeColor;
        double strokeWeight;
        String strokeType = "Solid";
        double strokeTint = 100;
        String fillColor;
        double fillTint = 100;
        byte[] imageFillData;
        boolean nativeGraphicsAllowed;
        boolean forceImageFill;
        long marginLeft;
        long marginRight;
        long marginTop;
        long marginBottom;
        TextDirection textDirection = TextDirection.HORIZONTAL;
        LineWrapMethod lineWrap = LineWrapMethod.BREAK;
        VerticalAlign2 verticalAlign = VerticalAlign2.TOP;
        List<ASTParagraph> paragraphs;
        List<ASTTable> inlineTables;
    }

    static Spec fromTextFrameBlock(ASTTextFrameBlock block, long width, long height) {
        Spec spec = new Spec();
        spec.width = width;
        spec.height = height;
        spec.strokeColor = block.strokeColor();
        spec.strokeWeight = block.strokeWeight();
        spec.strokeType = block.strokeType();
        spec.strokeTint = block.strokeTint();
        spec.fillColor = block.fillColor();
        spec.fillTint = block.fillTint();
        spec.imageFillData = block.imageFillData();
        // 통합 플로팅 배지: imageFill을 전역 native-graphics OFF와 무관하게 칠한다(텍스트는 검색
        // 가능 런 유지 → source ownership policy 위배 아님). 좁은 범위(forceImageFill 플래그)로 한정해 회귀 방지.
        spec.forceImageFill = block.forceImageFill();
        spec.nativeGraphicsAllowed = false;
        spec.marginLeft = block.insetLeft();
        spec.marginRight = block.insetRight();
        spec.marginTop = block.insetTop();
        spec.marginBottom = block.insetBottom();
        spec.textDirection = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        spec.lineWrap = HwpxTextBoxBuilder.textFrameLineWrap(block);
        spec.verticalAlign = HwpxEnumMapper.mapVerticalJustification(block.verticalJustification());
        spec.paragraphs = block.paragraphs();
        if (block.noAutoLineWrap()) {
            markParagraphLocalSqueeze(spec.paragraphs);
        }
        return spec;
    }

    static Spec fromInlineObject(ASTInlineObject obj, long width, long height) {
        Spec spec = new Spec();
        spec.width = width;
        spec.height = height;
        spec.strokeColor = obj.strokeColor();
        spec.strokeWeight = obj.strokeWeight();
        spec.strokeType = "Solid";
        spec.strokeTint = obj.strokeTint();
        spec.fillColor = obj.fillColor();
        spec.fillTint = obj.fillTint();
        spec.imageFillData = obj.imageFillData();
        spec.nativeGraphicsAllowed = false;
        spec.forceImageFill = obj.forceImageFill();
        spec.marginLeft = obj.textMarginLeft();
        spec.marginRight = obj.textMarginRight();
        spec.marginTop = obj.textMarginTop();
        spec.marginBottom = obj.textMarginBottom();
        spec.textDirection = TextDirection.HORIZONTAL;
        spec.lineWrap = HwpxTextBoxBuilder.inlineTextFrameLineWrap(obj);
        spec.verticalAlign = HwpxEnumMapper.mapVerticalJustification(obj.verticalJustification());
        spec.paragraphs = obj.paragraphs();
        spec.inlineTables = obj.inlineTables();
        if (obj.noAutoLineWrap()) {
            markParagraphLocalSqueeze(spec.paragraphs);
        }
        return spec;
    }

    private static void markParagraphLocalSqueeze(List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph != null) {
                paragraph.squeezeLineWrap(true);
            }
        }
    }

    SubList apply(Rectangle rect, Spec spec) {
        applyVisualShell(rect, spec);
        SubList subList = createDrawText(rect, spec);
        addContent(subList, spec);
        return subList;
    }

    void applyVisualShell(Rectangle rect, Spec spec) {
        textBoxBuilder.setupTextBoxLineShape(
                rect,
                spec.strokeColor,
                spec.strokeWeight,
                spec.strokeType,
                spec.strokeTint,
                spec.nativeGraphicsAllowed);

        boolean imgBrushSet = false;
        if (spec.imageFillData != null && spec.imageFillData.length > 0) {
            imgBrushSet = textBoxBuilder.setupTextBoxImgBrush(
                    rect, spec.imageFillData, spec.forceImageFill);
        }
        boolean fillBrushSet = false;
        if (!imgBrushSet) {
            fillBrushSet = textBoxBuilder.setupTextBoxFillBrush(
                    rect,
                    spec.fillColor,
                    spec.fillTint,
                    spec.nativeGraphicsAllowed);
        }
        if (!imgBrushSet && !fillBrushSet) {
            textBoxBuilder.setupTransparentTextBoxFillBrush(rect);
        }
    }

    private SubList createDrawText(Rectangle rect, Spec spec) {
        rect.createDrawText();
        DrawText dt = rect.drawText();
        dt.lastWidthAnd(spec.width).nameAnd("").editableAnd(false);

        dt.createTextMargin();
        dt.textMargin()
                .leftAnd(spec.marginLeft)
                .rightAnd(spec.marginRight)
                .topAnd(spec.marginTop)
                .bottomAnd(spec.marginBottom);

        dt.createSubList();
        SubList subList = dt.subList();
        subList.idAnd("").textDirectionAnd(spec.textDirection)
                .lineWrapAnd(spec.lineWrap)
                .vertAlignAnd(spec.verticalAlign)
                .linkListIDRefAnd("0")
                .linkListNextIDRefAnd("0")
                .textWidthAnd(0)
                .textHeightAnd(0)
                .hasTextRefAnd(false)
                .hasNumRefAnd(false);
        return subList;
    }

    private void addContent(SubList subList, Spec spec) {
        paragraphBuilder.fillSubListContent(subList, spec.paragraphs, spec.inlineTables, 0);
    }

    static void applyRectangleGeometry(
            Rectangle rect,
            long width,
            long height,
            double cornerRadius,
            long cornerReferenceHeight) {
        applyRectangleGeometry(rect, width, height, cornerRadius, cornerReferenceHeight, null);
    }

    static void applyRectangleGeometry(
            Rectangle rect,
            long width,
            long height,
            double cornerRadius,
            long cornerReferenceHeight,
            String shellShapeType) {
        applyRectangleGeometry(
                rect, width, height, cornerRadius, cornerReferenceHeight,
                shellShapeType, false);
    }

    static void applyRectangleGeometry(
            Rectangle rect,
            long width,
            long height,
            double cornerRadius,
            long cornerReferenceHeight,
            String shellShapeType,
            boolean forceNativeGraphics) {
        rect.ratioAnd(cornerRatioForShape(
                shellShapeType,
                cornerRadius,
                width,
                cornerReferenceHeight > 0 ? cornerReferenceHeight : height,
                forceNativeGraphics));
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(width, 0L);
        rect.createPt2();
        rect.pt2().set(width, height);
        rect.createPt3();
        rect.pt3().set(0L, height);

        rect.createSZ();
        rect.sz().widthAnd(width).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(height).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);
    }

    /**
     * Oval 셸은 완전 라운드(ratio=50, 타원/스타디움)로 렌더한다. computeCornerRatio의
     * 장축 둥글기 축소 보정은 사각형 라벨용이므로 Oval에는 적용하지 않는다.
     * Rectangle/Polygon/미지정은 기존 cornerRadius 기반 ratio를 그대로 쓴다.
     */
    private static short cornerRatioForShape(String shellShapeType,
                                             double cornerRadius,
                                             long width,
                                             long referenceHeight,
                                             boolean forceNativeGraphics) {
        if (shellShapeType != null
                && shellShapeType.toLowerCase().contains("oval")
                && (HwpxTextBoxBuilder.nativeTextBoxGraphicsEnabled() || forceNativeGraphics)) {
            return 50;
        }
        return HwpxTextBoxBuilder.computeCornerRatio(
                cornerRadius, width, referenceHeight, forceNativeGraphics);
    }
}
