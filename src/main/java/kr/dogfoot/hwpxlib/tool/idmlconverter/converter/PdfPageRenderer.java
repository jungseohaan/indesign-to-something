package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * PDF 파일의 각 페이지를 지정 DPI로 래스터화하여 JPEG byte[]로 변환.
 * InDesign에서 내보낸 페이지 배경 PDF를 고해상도 이미지로 변환하는 데 사용.
 */
public class PdfPageRenderer {

    private static final float JPEG_QUALITY = 0.92f;

    private PdfPageRenderer() {}

    /**
     * PDF 파일의 모든 페이지를 래스터화하여 JPEG byte[] 리스트로 반환.
     */
    public static List<byte[]> renderAllPages(File pdfFile, int dpi) throws IOException {
        List<byte[]> pages = new ArrayList<byte[]>();
        PDDocument doc = PDDocument.load(pdfFile);
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            int pageCount = doc.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                pages.add(encodeJpeg(image));
                image.flush();
            }
        } finally {
            doc.close();
        }
        return pages;
    }

    /**
     * PDF 파일의 특정 페이지를 래스터화하여 JPEG byte[]로 반환.
     */
    public static byte[] renderPage(File pdfFile, int pageIndex, int dpi) throws IOException {
        PDDocument doc = PDDocument.load(pdfFile);
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
            byte[] result = encodeJpeg(image);
            image.flush();
            return result;
        } finally {
            doc.close();
        }
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            // JPEG writer 없으면 PNG 폴백
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
        writer.setOutput(ios);
        writer.write(null, new IIOImage(image, null, null), param);
        writer.dispose();
        ios.close();
        return baos.toByteArray();
    }
}
