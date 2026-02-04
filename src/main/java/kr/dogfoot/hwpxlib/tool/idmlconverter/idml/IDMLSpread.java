package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML 스프레드 (2페이지 단위 레이아웃).
 */
public class IDMLSpread {
    private String selfId;
    private List<IDMLPage> pages;
    private List<IDMLTextFrame> textFrames;
    private List<IDMLImageFrame> imageFrames;
    private List<IDMLVectorShape> vectorShapes;

    public IDMLSpread() {
        this.pages = new ArrayList<IDMLPage>();
        this.textFrames = new ArrayList<IDMLTextFrame>();
        this.imageFrames = new ArrayList<IDMLImageFrame>();
        this.vectorShapes = new ArrayList<IDMLVectorShape>();
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
        for (IDMLVectorShape shape : vectorShapes) {
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
}
