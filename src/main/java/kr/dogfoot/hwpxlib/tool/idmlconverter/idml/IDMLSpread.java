package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * IDML 스프레드 (2페이지 단위 레이아웃).
 */
public class IDMLSpread {

    /**
     * 렌더링 가능한 항목 (이미지 또는 벡터).
     */
    public static class RenderableItem {
        public enum Type { IMAGE, VECTOR }

        private final Type type;
        private final IDMLImageFrame imageFrame;
        private final IDMLVectorShape vectorShape;
        private final int zOrder;

        public RenderableItem(IDMLImageFrame frame) {
            this.type = Type.IMAGE;
            this.imageFrame = frame;
            this.vectorShape = null;
            this.zOrder = frame.zOrder();
        }

        public RenderableItem(IDMLVectorShape shape) {
            this.type = Type.VECTOR;
            this.imageFrame = null;
            this.vectorShape = shape;
            this.zOrder = shape.zOrder();
        }

        public Type type() { return type; }
        public IDMLImageFrame imageFrame() { return imageFrame; }
        public IDMLVectorShape vectorShape() { return vectorShape; }
        public int zOrder() { return zOrder; }
    }

    private String selfId;
    private List<IDMLPage> pages;
    private List<IDMLTextFrame> textFrames;
    private List<IDMLImageFrame> imageFrames;
    private List<IDMLVectorShape> vectorShapes;
    private List<IDMLGroup> groups;

    public IDMLSpread() {
        this.pages = new ArrayList<IDMLPage>();
        this.textFrames = new ArrayList<IDMLTextFrame>();
        this.imageFrames = new ArrayList<IDMLImageFrame>();
        this.vectorShapes = new ArrayList<IDMLVectorShape>();
        this.groups = new ArrayList<IDMLGroup>();
    }

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public List<IDMLPage> pages() { return pages; }
    public void addPage(IDMLPage page) { pages.add(page); }

    public List<IDMLTextFrame> textFrames() { return textFrames; }
    public void addTextFrame(IDMLTextFrame frame) { textFrames.add(frame); }

    public List<IDMLImageFrame> imageFrames() { return imageFrames; }
    public void addImageFrame(IDMLImageFrame frame) { imageFrames.add(frame); }

    public List<IDMLVectorShape> vectorShapes() { return vectorShapes; }
    public void addVectorShape(IDMLVectorShape shape) { vectorShapes.add(shape); }

    public List<IDMLGroup> groups() { return groups; }
    public void addGroup(IDMLGroup group) { groups.add(group); }

    /**
     * ID로 텍스트 프레임을 찾는다.
     */
    public IDMLTextFrame findTextFrameById(String id) {
        if (id == null || "n".equals(id)) return null;
        for (IDMLTextFrame frame : textFrames) {
            if (id.equals(frame.selfId())) {
                return frame;
            }
        }
        return null;
    }

    /**
     * 특정 페이지에 속한 텍스트 프레임 목록.
     */
    public List<IDMLTextFrame> getTextFramesOnPage(IDMLPage page) {
        List<IDMLTextFrame> result = new ArrayList<IDMLTextFrame>();

        // 페이지 좌표 범위 계산
        double[] pageBounds = page.geometricBounds();
        double[] pageTransform = page.itemTransform();
        double pageMinX = 0, pageMaxX = 0, pageMinY = 0, pageMaxY = 0;
        if (pageBounds != null && pageTransform != null) {
            double[] pageTopLeft = CoordinateConverter.applyTransform(pageTransform, pageBounds[1], pageBounds[0]);
            double[] pageBottomRight = CoordinateConverter.applyTransform(pageTransform, pageBounds[3], pageBounds[2]);
            pageMinX = Math.min(pageTopLeft[0], pageBottomRight[0]);
            pageMaxX = Math.max(pageTopLeft[0], pageBottomRight[0]);
            pageMinY = Math.min(pageTopLeft[1], pageBottomRight[1]);
            pageMaxY = Math.max(pageTopLeft[1], pageBottomRight[1]);
        }

        for (IDMLTextFrame frame : textFrames) {
            if (frame.geometricBounds() != null && frame.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {

                // 큰 Y 오프셋인 경우 디버그 로그
                double ty = frame.itemTransform()[5];
                if (ty > 1000 || ty < -1000) {
                    double[] frameBounds = frame.geometricBounds();
                    double frameCenterX = (frameBounds[1] + frameBounds[3]) / 2.0;
                    double frameCenterY = (frameBounds[0] + frameBounds[2]) / 2.0;
                    double[] frameCenter = CoordinateConverter.applyTransform(frame.itemTransform(),
                            frameCenterX, frameCenterY);

                    boolean isOnPage = IDMLGeometry.isFrameOnPage(
                            frame.geometricBounds(), frame.itemTransform(),
                            page.geometricBounds(), page.itemTransform());

                    System.err.println("[DEBUG] 텍스트 페이지 할당: " + frame.selfId()
                            + " | 페이지 " + page.pageNumber()
                            + " | 페이지X=[" + CoordinateConverter.fmtInt(pageMinX) + "," + CoordinateConverter.fmtInt(pageMaxX) + "]"
                            + " Y=[" + CoordinateConverter.fmtInt(pageMinY) + "," + CoordinateConverter.fmtInt(pageMaxY) + "]"
                            + " | 텍스트(" + CoordinateConverter.fmtInt(frameCenter[0]) + "," + CoordinateConverter.fmtInt(frameCenter[1]) + ")"
                            + " | 포함=" + isOnPage);

                    if (isOnPage) {
                        result.add(frame);
                    }
                    continue;
                }

                if (IDMLGeometry.isFrameOnPage(
                        frame.geometricBounds(), frame.itemTransform(),
                        page.geometricBounds(), page.itemTransform())) {
                    result.add(frame);
                }
            }
        }
        return result;
    }

    /**
     * 특정 페이지에 속한 이미지 프레임 목록.
     */
    public List<IDMLImageFrame> getImageFramesOnPage(IDMLPage page) {
        List<IDMLImageFrame> result = new ArrayList<IDMLImageFrame>();
        for (IDMLImageFrame frame : imageFrames) {
            if (frame.geometricBounds() != null && frame.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
                if (IDMLGeometry.isFrameOnPage(
                        frame.geometricBounds(), frame.itemTransform(),
                        page.geometricBounds(), page.itemTransform())) {
                    result.add(frame);
                }
            }
        }
        return result;
    }

    /**
     * 특정 페이지에 속한 벡터 도형 목록.
     */
    public List<IDMLVectorShape> getVectorShapesOnPage(IDMLPage page) {
        List<IDMLVectorShape> result = new ArrayList<IDMLVectorShape>();

        // 페이지 좌표 범위 계산
        double[] pageBounds = page.geometricBounds();
        double[] pageTransform = page.itemTransform();
        double pageMinX = 0, pageMaxX = 0, pageMinY = 0, pageMaxY = 0;
        if (pageBounds != null && pageTransform != null) {
            double[] pageTopLeft = CoordinateConverter.applyTransform(pageTransform, pageBounds[1], pageBounds[0]);
            double[] pageBottomRight = CoordinateConverter.applyTransform(pageTransform, pageBounds[3], pageBounds[2]);
            pageMinX = Math.min(pageTopLeft[0], pageBottomRight[0]);
            pageMaxX = Math.max(pageTopLeft[0], pageBottomRight[0]);
            pageMinY = Math.min(pageTopLeft[1], pageBottomRight[1]);
            pageMaxY = Math.max(pageTopLeft[1], pageBottomRight[1]);
        }

        for (IDMLVectorShape shape : vectorShapes) {
            if (shape.geometricBounds() != null && shape.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {

                // 큰 Y 오프셋인 경우 디버그 로그
                double ty = shape.itemTransform()[5];
                if (ty > 1000 || ty < -1000) {
                    double[] shapeBounds = shape.geometricBounds();
                    double shapeCenterX = (shapeBounds[1] + shapeBounds[3]) / 2.0;
                    double shapeCenterY = (shapeBounds[0] + shapeBounds[2]) / 2.0;
                    double[] shapeCenter = CoordinateConverter.applyTransform(shape.itemTransform(),
                            shapeCenterX, shapeCenterY);

                    boolean isOnPage = IDMLGeometry.isFrameOnPage(
                            shape.geometricBounds(), shape.itemTransform(),
                            page.geometricBounds(), page.itemTransform());

                    System.err.println("[DEBUG] 벡터 페이지 할당: " + shape.selfId()
                            + " | 페이지 " + page.pageNumber()
                            + " | 페이지X=[" + CoordinateConverter.fmtInt(pageMinX) + "," + CoordinateConverter.fmtInt(pageMaxX) + "]"
                            + " Y=[" + CoordinateConverter.fmtInt(pageMinY) + "," + CoordinateConverter.fmtInt(pageMaxY) + "]"
                            + " | 벡터(" + CoordinateConverter.fmtInt(shapeCenter[0]) + "," + CoordinateConverter.fmtInt(shapeCenter[1]) + ")"
                            + " | 포함=" + isOnPage);

                    if (isOnPage) {
                        result.add(shape);
                    }
                    continue;
                }

                if (IDMLGeometry.isFrameOnPage(
                        shape.geometricBounds(), shape.itemTransform(),
                        page.geometricBounds(), page.itemTransform())) {
                    result.add(shape);
                }
            }
        }
        return result;
    }

    /**
     * 특정 페이지에 속한 모든 렌더링 항목 (이미지 + 벡터)을 z-order 순으로 반환.
     */
    public List<RenderableItem> getRenderableItemsOnPage(IDMLPage page) {
        List<RenderableItem> result = new ArrayList<RenderableItem>();

        // 이미지 프레임 추가
        for (IDMLImageFrame frame : imageFrames) {
            if (frame.geometricBounds() != null && frame.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
                if (IDMLGeometry.isFrameOnPage(
                        frame.geometricBounds(), frame.itemTransform(),
                        page.geometricBounds(), page.itemTransform())) {
                    result.add(new RenderableItem(frame));
                }
            }
        }

        // 페이지 범위 계산 (디버그용)
        double[] pageBounds = page.geometricBounds();
        double[] pageTransform = page.itemTransform();
        double pageMinX = 0, pageMaxX = 0, pageMinY = 0, pageMaxY = 0;
        if (pageBounds != null && pageTransform != null) {
            double[] pageTopLeft = CoordinateConverter.applyTransform(pageTransform, pageBounds[1], pageBounds[0]);
            double[] pageBottomRight = CoordinateConverter.applyTransform(pageTransform, pageBounds[3], pageBounds[2]);
            pageMinX = Math.min(pageTopLeft[0], pageBottomRight[0]);
            pageMaxX = Math.max(pageTopLeft[0], pageBottomRight[0]);
            pageMinY = Math.min(pageTopLeft[1], pageBottomRight[1]);
            pageMaxY = Math.max(pageTopLeft[1], pageBottomRight[1]);
        }

        // 벡터 도형 추가 (인라인 그래픽은 제외 - HWPX 네이티브 객체로 내보내기 위해)
        int vectorTotal = 0, vectorOnPage = 0;
        for (IDMLVectorShape shape : vectorShapes) {
            vectorTotal++;
            // 인라인 그래픽은 PNG 배경 렌더링에서 제외 (별도 HWPX 객체로 내보냄)
            if (shape.isInline()) {
                continue;
            }
            if (shape.geometricBounds() != null && shape.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {

                boolean onPage = IDMLGeometry.isFrameOnPage(
                        shape.geometricBounds(), shape.itemTransform(),
                        page.geometricBounds(), page.itemTransform());

                // 특정 인라인 그래픽 디버그 (u38a3 등)
                String selfId = shape.selfId();
                if (selfId != null && (selfId.startsWith("u38a") || selfId.startsWith("u38b") || selfId.startsWith("u38d"))) {
                    double[] shapeBounds = shape.geometricBounds();
                    double shapeCenterX = (shapeBounds[1] + shapeBounds[3]) / 2.0;
                    double shapeCenterY = (shapeBounds[0] + shapeBounds[2]) / 2.0;
                    double[] shapeCenter = CoordinateConverter.applyTransform(shape.itemTransform(),
                            shapeCenterX, shapeCenterY);
                    System.err.println("[DEBUG] 렌더링 체크: " + selfId
                            + " | 페이지 " + page.pageNumber()
                            + " | 벡터중심(" + CoordinateConverter.fmtInt(shapeCenter[0]) + "," + CoordinateConverter.fmtInt(shapeCenter[1]) + ")"
                            + " | 페이지범위 X=[" + CoordinateConverter.fmtInt(pageMinX) + "," + CoordinateConverter.fmtInt(pageMaxX) + "]"
                            + " Y=[" + CoordinateConverter.fmtInt(pageMinY) + "," + CoordinateConverter.fmtInt(pageMaxY) + "]"
                            + " | onPage=" + onPage);
                }

                if (onPage) {
                    vectorOnPage++;
                    result.add(new RenderableItem(shape));
                }
            }
        }

        // z-order로 정렬
        Collections.sort(result, new Comparator<RenderableItem>() {
            public int compare(RenderableItem a, RenderableItem b) {
                return Integer.compare(a.zOrder(), b.zOrder());
            }
        });

        return result;
    }

    /**
     * 특정 페이지에 속한 인라인 벡터 그래픽 목록을 반환한다.
     * 이 그래픽들은 PNG 배경이 아닌 HWPX 네이티브 객체로 내보내야 한다.
     */
    public List<IDMLVectorShape> getInlineVectorShapesOnPage(IDMLPage page) {
        List<IDMLVectorShape> result = new ArrayList<IDMLVectorShape>();
        for (IDMLVectorShape shape : vectorShapes) {
            if (!shape.isInline()) {
                continue;
            }
            if (shape.geometricBounds() != null && shape.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
                boolean onPage = IDMLGeometry.isFrameOnPage(
                        shape.geometricBounds(), shape.itemTransform(),
                        page.geometricBounds(), page.itemTransform());
                if (onPage) {
                    result.add(shape);
                }
            }
        }
        // z-order로 정렬
        Collections.sort(result, new Comparator<IDMLVectorShape>() {
            public int compare(IDMLVectorShape a, IDMLVectorShape b) {
                return Integer.compare(a.zOrder(), b.zOrder());
            }
        });
        return result;
    }
}
