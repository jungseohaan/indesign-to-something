package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.hwpx;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Ellipse;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Polygon;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawingObject;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapecomponent.ShapeComponent;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapeobject.ShapeObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateFrame;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * IntermediateFrame(shape)을 HWPX Rectangle/Ellipse/Polygon으로 변환한다.
 */
public class HwpxShapeWriter {

    private final AtomicLong shapeIdCounter;

    public HwpxShapeWriter(AtomicLong shapeIdCounter) {
        this.shapeIdCounter = shapeIdCounter;
    }

    /**
     * 인라인 벡터 도형을 HWPX 도형으로 변환한다.
     */
    public void write(Run anchorRun, IntermediateFrame frame) {
        long x = frame.x();
        long y = frame.y();
        long w = frame.width();
        long h = frame.height();

        String shapeType = frame.shapeType();
        if (shapeType == null) shapeType = "rectangle";

        switch (shapeType) {
            case "oval":
                writeEllipse(anchorRun, frame, x, y, w, h);
                break;
            case "polygon":
                writePolygon(anchorRun, frame, x, y, w, h);
                break;
            default:
                writeRectangle(anchorRun, frame, x, y, w, h);
                break;
        }
    }

    private void writeRectangle(Run anchorRun, IntermediateFrame frame,
                                 long x, long y, long w, long h) {
        Rectangle rect = anchorRun.addNewRectangle();
        setupShapeCommon(rect, frame, x, y, w, h);

        // Rectangle 고유 속성 — 4 코너
        rect.ratioAnd(frame.cornerRatio());
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(w, 0L);
        rect.createPt2();
        rect.pt2().set(w, h);
        rect.createPt3();
        rect.pt3().set(0L, h);

        setupLineAndFill(rect, frame);
    }

    private void writeEllipse(Run anchorRun, IntermediateFrame frame,
                               long x, long y, long w, long h) {
        Ellipse ellipse = anchorRun.addNewEllipse();
        setupShapeCommon(ellipse, frame, x, y, w, h);

        // Ellipse 고유 속성 - 중심, 축1, 축2
        ellipse.createCenter();
        ellipse.center().set(w / 2, h / 2);
        ellipse.createAx1();
        ellipse.ax1().set(w, h / 2);
        ellipse.createAx2();
        ellipse.ax2().set(w / 2, h);

        setupLineAndFill(ellipse, frame);
    }

    private void writePolygon(Run anchorRun, IntermediateFrame frame,
                               long x, long y, long w, long h) {
        Polygon polygon = anchorRun.addNewPolygon();
        setupShapeCommon(polygon, frame, x, y, w, h);

        // Polygon 경로 점 설정
        List<double[]> pathPoints = frame.pathPoints();
        if (pathPoints != null && !pathPoints.isEmpty()) {
            // 상대 좌표로 변환 (HWPX는 도형 내부 상대 좌표 사용)
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            for (double[] pt : pathPoints) {
                minX = Math.min(minX, pt[0]);
                minY = Math.min(minY, pt[1]);
            }
            for (double[] pt : pathPoints) {
                long ptX = CoordinateConverter.pointsToHwpunits(pt[0] - minX);
                long ptY = CoordinateConverter.pointsToHwpunits(pt[1] - minY);
                polygon.addNewPt().set(ptX, ptY);
            }
        } else {
            // 경로 점이 없으면 사각형으로 대체
            polygon.addNewPt().set(0L, 0L);
            polygon.addNewPt().set(w, 0L);
            polygon.addNewPt().set(w, h);
            polygon.addNewPt().set(0L, h);
        }

        setupLineAndFill(polygon, frame);
    }

    @SuppressWarnings("unchecked")
    private <T extends DrawingObject<T>> void setupShapeCommon(T shape, IntermediateFrame frame,
                                                                 long x, long y, long w, long h) {
        // ShapeObject 기본 속성
        ((ShapeObject<T>) shape)
                .idAnd(nextShapeId())
                .zOrderAnd(frame.zOrder())
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        ShapeComponent<T> sc = (ShapeComponent<T>) shape;
        sc.hrefAnd("");
        sc.groupLevelAnd((short) 0);
        sc.instidAnd(nextShapeId());

        sc.createOffset();
        sc.offset().set(0L, 0L);

        sc.createOrgSz();
        sc.orgSz().set(w, h);

        sc.createCurSz();
        sc.curSz().set(w, h);

        sc.createFlip();
        sc.flip().horizontalAnd(false).verticalAnd(false);

        sc.createRotationInfo();
        sc.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2).rotateimageAnd(true);

        sc.createRenderingInfo();
        sc.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        sc.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        sc.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        ShapeObject<T> so = (ShapeObject<T>) shape;
        so.createSZ();
        so.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — 인라인 객체는 글자처럼 취급
        so.createPos();
        if (frame.isInline()) {
            so.pos().treatAsCharAnd(true)
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
        } else {
            so.pos().treatAsCharAnd(false)
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
        }

        // OutMargin
        so.createOutMargin();
        so.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);
    }

    private <T extends DrawingObject<T>> void setupLineAndFill(T shape, IntermediateFrame frame) {
        // LineShape (테두리)
        boolean hasStroke = frame.strokeColor() != null && frame.strokeWeight() > 0;
        String strokeColor = frame.strokeColor() != null ? frame.strokeColor() : "#000000";
        int strokeWidthHwp = hasStroke ? (int) (frame.strokeWeight() * 100) : 0;
        if (hasStroke && strokeWidthHwp < 14) strokeWidthHwp = 14;

        shape.createLineShape();
        shape.lineShape().colorAnd(strokeColor).widthAnd(strokeWidthHwp)
                .styleAnd(hasStroke ? LineType2.SOLID : LineType2.NONE)
                .endCapAnd(LineCap.FLAT)
                .headStyleAnd(ArrowType.NORMAL).tailStyleAnd(ArrowType.NORMAL)
                .headfillAnd(true).tailfillAnd(true)
                .headSzAnd(ArrowSize.MEDIUM_MEDIUM).tailSzAnd(ArrowSize.MEDIUM_MEDIUM)
                .outlineStyleAnd(OutlineStyle.NORMAL).alpha(0f);

        // FillBrush (채우기)
        if (frame.fillColor() != null) {
            shape.createFillBrush();
            shape.fillBrush().createWinBrush();
            shape.fillBrush().winBrush().faceColorAnd(frame.fillColor())
                    .hatchColorAnd(frame.fillColor())
                    .alpha(0f);
        }

        // Shadow (필수 요소)
        shape.createShadow();
        shape.shadow().typeAnd(DrawingShadowType.NONE)
                .colorAnd("#B2B2B2")
                .offsetXAnd(0L).offsetYAnd(0L)
                .alpha(0f);
    }

    private String nextShapeId() {
        return String.valueOf(shapeIdCounter.getAndIncrement());
    }
}
