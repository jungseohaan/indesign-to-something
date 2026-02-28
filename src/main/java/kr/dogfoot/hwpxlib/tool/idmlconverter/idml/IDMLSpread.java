package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

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
        for (IDMLTextFrame frame : textFrames) {
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
                } else {
                    String uri = frame.linkResourceURI();
                    try { uri = java.net.URLDecoder.decode(uri, "UTF-8"); } catch (Exception ignored) {}
                    System.err.println("[IMG-NOPAGE] page=" + page.name()
                            + " frame=" + frame.selfId()
                            + " URI=" + uri
                            + " bounds=" + java.util.Arrays.toString(frame.geometricBounds())
                            + " transform=" + java.util.Arrays.toString(frame.itemTransform()));
                }
            } else {
                System.err.println("[IMG-NULL] frame=" + frame.selfId()
                        + " bounds=" + (frame.geometricBounds() != null)
                        + " transform=" + (frame.itemTransform() != null));
            }
        }
        return result;
    }

    /**
     * 특정 페이지에 속한 벡터 도형 목록.
     */
    public List<IDMLVectorShape> getVectorShapesOnPage(IDMLPage page) {
        List<IDMLVectorShape> result = new ArrayList<IDMLVectorShape>();
        for (IDMLVectorShape shape : vectorShapes) {
            if (shape.isInline()) continue;
            if (shape.geometricBounds() != null && shape.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
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
        return getRenderableItemsOnPage(page, false);
    }

    /**
     * 특정 페이지에 속한 모든 렌더링 항목 (이미지 + 벡터)을 z-order 순으로 반환.
     * @param includeGroupItems true이면 그룹에서 추출된 항목도 포함 (프리뷰 렌더링용)
     */
    public List<RenderableItem> getRenderableItemsOnPage(IDMLPage page, boolean includeGroupItems) {
        List<RenderableItem> result = new ArrayList<RenderableItem>();

        // 이미지 프레임 추가 (그룹에서 추출된 것은 제외 — 그룹 글상자로 별도 처리)
        for (IDMLImageFrame frame : imageFrames) {
            if (!includeGroupItems && frame.fromGroup()) continue;
            if (frame.geometricBounds() != null && frame.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
                if (IDMLGeometry.isFrameOnPage(
                        frame.geometricBounds(), frame.itemTransform(),
                        page.geometricBounds(), page.itemTransform())) {
                    result.add(new RenderableItem(frame));
                }
            }
        }

        // 벡터 도형 추가 (인라인 그래픽은 제외 - HWPX 네이티브 객체로 내보내기 위해)
        for (IDMLVectorShape shape : vectorShapes) {
            if (shape.isInline()) continue;
            if (!includeGroupItems && shape.fromGroup()) continue;
            if (shape.geometricBounds() != null && shape.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
                if (IDMLGeometry.isFrameOnPage(
                        shape.geometricBounds(), shape.itemTransform(),
                        page.geometricBounds(), page.itemTransform())) {
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
     * 특정 페이지에 속한 그룹 목록.
     */
    public List<IDMLGroup> getGroupsOnPage(IDMLPage page) {
        List<IDMLGroup> result = new ArrayList<IDMLGroup>();
        for (IDMLGroup group : groups) {
            if (group.geometricBounds() != null && group.itemTransform() != null
                    && page.geometricBounds() != null && page.itemTransform() != null) {
                if (IDMLGeometry.isFrameOnPage(
                        group.geometricBounds(), group.itemTransform(),
                        page.geometricBounds(), page.itemTransform())) {
                    result.add(group);
                }
            }
        }
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
