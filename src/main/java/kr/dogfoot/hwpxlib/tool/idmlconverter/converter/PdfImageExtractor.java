package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 파일에서 임베딩된 이미지를 직접 추출.
 * InDesign PDF 내보내기는 배치 이미지를 원본 해상도로 임베딩하므로,
 * 래스터화 없이 원본 품질 이미지를 추출할 수 있다.
 */
public class PdfImageExtractor {

    /** 추출된 이미지 정보 */
    public static class ExtractedImage {
        public final byte[] data;       // PNG byte[]
        public final int width;         // 픽셀 폭
        public final int height;        // 픽셀 높이
        public final String format;     // "png" or "jpg"

        public ExtractedImage(byte[] data, int width, int height, String format) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.format = format;
        }
    }

    private PdfImageExtractor() {}

    /**
     * PDF 파일의 특정 페이지에서 모든 이미지를 추출.
     *
     * @param pdfFile   PDF 파일
     * @param pageIndex 0-based 페이지 인덱스
     * @return 추출된 이미지 리스트
     */
    public static List<ExtractedImage> extractImages(File pdfFile, int pageIndex) throws IOException {
        List<ExtractedImage> images = new ArrayList<ExtractedImage>();
        PDDocument doc = PDDocument.load(pdfFile);
        try {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return images;
            PDPage page = doc.getPage(pageIndex);
            PDResources resources = page.getResources();
            if (resources == null) return images;
            collectImages(resources, images);
        } finally {
            doc.close();
        }
        return images;
    }

    /**
     * PDF 파일의 모든 페이지에서 가장 큰 이미지를 페이지별로 추출.
     * 페이지 배경 용도: 각 페이지에서 면적이 가장 큰 이미지 1개를 선택.
     *
     * @param pdfFile PDF 파일
     * @return 페이지별 최대 이미지 (이미지 없는 페이지는 null)
     */
    public static List<ExtractedImage> extractLargestPerPage(File pdfFile) throws IOException {
        List<ExtractedImage> result = new ArrayList<ExtractedImage>();
        PDDocument doc = PDDocument.load(pdfFile);
        try {
            int pageCount = doc.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                PDPage page = doc.getPage(i);
                PDResources resources = page.getResources();
                if (resources == null) {
                    result.add(null);
                    continue;
                }

                List<ExtractedImage> pageImages = new ArrayList<ExtractedImage>();
                collectImages(resources, pageImages);

                // 면적이 가장 큰 이미지 선택
                ExtractedImage largest = null;
                long maxArea = 0;
                for (ExtractedImage img : pageImages) {
                    long area = (long) img.width * img.height;
                    if (area > maxArea) {
                        maxArea = area;
                        largest = img;
                    }
                }
                result.add(largest);
            }
        } finally {
            doc.close();
        }
        return result;
    }

    /**
     * PDF 리소스에서 이미지 XObject를 재귀적으로 수집.
     */
    private static void collectImages(PDResources resources, List<ExtractedImage> images) throws IOException {
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xobj = resources.getXObject(name);
            if (xobj instanceof PDImageXObject) {
                PDImageXObject pdImage = (PDImageXObject) xobj;
                int w = pdImage.getWidth();
                int h = pdImage.getHeight();

                // 이미지 데이터 추출 — PNG로 변환
                BufferedImage bimg = pdImage.getImage();
                if (bimg == null) continue;

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bimg, "png", baos);
                bimg.flush();

                images.add(new ExtractedImage(baos.toByteArray(), w, h, "png"));
            } else if (xobj instanceof PDFormXObject) {
                // Form XObject 내부 재귀 탐색
                PDFormXObject form = (PDFormXObject) xobj;
                PDResources formResources = form.getResources();
                if (formResources != null) {
                    collectImages(formResources, images);
                }
            }
        }
    }
}
