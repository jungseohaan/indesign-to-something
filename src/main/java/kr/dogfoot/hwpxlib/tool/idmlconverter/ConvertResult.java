package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML -> HWPX 변환 결과.
 */
public class ConvertResult {
    private HWPXFile hwpxFile;
    private final List<String> warnings;
    private int pagesConverted;
    private int framesConverted;
    private int equationsConverted;
    private int imagesConverted;
    private int imagesSkipped;
    private int imagesPsdConverted;
    private int imagesAiConverted;
    private int imagesTiffConverted;
    private int stylesConverted;

    public ConvertResult() {
        this.warnings = new ArrayList<String>();
    }

    public HWPXFile hwpxFile() {
        return hwpxFile;
    }

    public void hwpxFile(HWPXFile hwpxFile) {
        this.hwpxFile = hwpxFile;
    }

    public List<String> warnings() {
        return warnings;
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public int pagesConverted() {
        return pagesConverted;
    }

    public void pagesConverted(int count) {
        this.pagesConverted = count;
    }

    public int framesConverted() {
        return framesConverted;
    }

    public void framesConverted(int count) {
        this.framesConverted = count;
    }

    public int equationsConverted() {
        return equationsConverted;
    }

    public void equationsConverted(int count) {
        this.equationsConverted = count;
    }

    public int imagesConverted() {
        return imagesConverted;
    }

    public void imagesConverted(int count) {
        this.imagesConverted = count;
    }

    public int imagesSkipped() {
        return imagesSkipped;
    }

    public void imagesSkipped(int count) {
        this.imagesSkipped = count;
    }

    public int imagesPsdConverted() {
        return imagesPsdConverted;
    }

    public void imagesPsdConverted(int count) {
        this.imagesPsdConverted = count;
    }

    public int imagesAiConverted() {
        return imagesAiConverted;
    }

    public void imagesAiConverted(int count) {
        this.imagesAiConverted = count;
    }

    public int imagesTiffConverted() {
        return imagesTiffConverted;
    }

    public void imagesTiffConverted(int count) {
        this.imagesTiffConverted = count;
    }

    public int stylesConverted() {
        return stylesConverted;
    }

    public void stylesConverted(int count) {
        this.stylesConverted = count;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ConvertResult{pages=").append(pagesConverted);
        sb.append(", frames=").append(framesConverted);
        sb.append(", equations=").append(equationsConverted);
        sb.append(", images=").append(imagesConverted);
        if (imagesPsdConverted > 0 || imagesAiConverted > 0 || imagesTiffConverted > 0) {
            sb.append(" (psd=").append(imagesPsdConverted);
            sb.append(", ai=").append(imagesAiConverted);
            sb.append(", tiff=").append(imagesTiffConverted).append(")");
        }
        if (imagesSkipped > 0) {
            sb.append(", imagesSkipped=").append(imagesSkipped);
        }
        sb.append(", styles=").append(stylesConverted);
        sb.append(", warnings=").append(warnings.size()).append("}");
        return sb.toString();
    }
}
