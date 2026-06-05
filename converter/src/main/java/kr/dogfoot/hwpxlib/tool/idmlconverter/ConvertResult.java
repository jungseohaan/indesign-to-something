package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private String htmlPath;

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

    /**
     * 카테고리별로 중복을 압축한 경고 목록을 반환한다.
     * 예: "[Image] 이미지 파일 없음: photo1.psd" 외 99건
     */
    public List<String> summarizedWarnings() {
        // 카테고리([] 내용) 별로 그룹핑
        Map<String, List<String>> grouped = new LinkedHashMap<String, List<String>>();
        for (String w : warnings) {
            String category = extractCategory(w);
            List<String> list = grouped.get(category);
            if (list == null) {
                list = new ArrayList<String>();
                grouped.put(category, list);
            }
            list.add(w);
        }

        List<String> result = new ArrayList<String>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            List<String> list = entry.getValue();
            result.add(list.get(0));
            if (list.size() > 1) {
                result.add("  ... 외 " + (list.size() - 1) + "건");
            }
        }
        return result;
    }

    private static String extractCategory(String warning) {
        if (warning.startsWith("[")) {
            int end = warning.indexOf(']');
            if (end > 0) return warning.substring(0, end + 1);
        }
        return "";
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

    public String htmlPath() {
        return htmlPath;
    }

    public void htmlPath(String path) {
        this.htmlPath = path;
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
