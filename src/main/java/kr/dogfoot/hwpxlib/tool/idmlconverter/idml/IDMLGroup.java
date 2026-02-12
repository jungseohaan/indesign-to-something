package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML Group — 여러 프레임을 묶는 그룹 객체.
 */
public class IDMLGroup {
    private String selfId;
    private double[] geometricBounds;
    private double[] itemTransform;
    private List<IDMLTextFrame> textFrames;
    private List<IDMLImageFrame> imageFrames;
    private List<IDMLVectorShape> vectorShapes;

    public IDMLGroup() {
        this.textFrames = new ArrayList<IDMLTextFrame>();
        this.imageFrames = new ArrayList<IDMLImageFrame>();
        this.vectorShapes = new ArrayList<IDMLVectorShape>();
    }

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public double[] geometricBounds() { return geometricBounds; }
    public void geometricBounds(double[] v) { this.geometricBounds = v; }

    public double[] itemTransform() { return itemTransform; }
    public void itemTransform(double[] v) { this.itemTransform = v; }

    public List<IDMLTextFrame> textFrames() { return textFrames; }
    public void addTextFrame(IDMLTextFrame frame) { textFrames.add(frame); }

    public List<IDMLImageFrame> imageFrames() { return imageFrames; }
    public void addImageFrame(IDMLImageFrame frame) { imageFrames.add(frame); }

    public List<IDMLVectorShape> vectorShapes() { return vectorShapes; }
    public void addVectorShape(IDMLVectorShape shape) { vectorShapes.add(shape); }
}
