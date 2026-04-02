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
import java.util.concurrent.*;

/**
 * PDF 파일의 각 페이지를 지정 DPI로 래스터화하여 JPEG byte[]로 변환.
 * InDesign에서 내보낸 페이지 배경 PDF를 고해상도 이미지로 변환하는 데 사용.
 */
public class PdfPageRenderer {

    private static final float JPEG_QUALITY = 0.92f;
    private static final int PARALLEL_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    private PdfPageRenderer() {}

    /**
     * PDF 파일의 모든 페이지를 병렬 래스터화하여 JPEG byte[] 리스트로 반환.
     */
    public static List<byte[]> renderAllPages(File pdfFile, int dpi) throws IOException {
        PDDocument doc = PDDocument.load(pdfFile);
        try {
            int pageCount = doc.getNumberOfPages();
            if (pageCount <= 2) {
                // 페이지 수가 적으면 순차 처리 (스레드 오버헤드 방지)
                return renderSequential(doc, pageCount, dpi);
            }
            return renderParallel(doc, pageCount, dpi);
        } finally {
            doc.close();
        }
    }

    private static List<byte[]> renderSequential(PDDocument doc, int pageCount, int dpi) throws IOException {
        List<byte[]> pages = new ArrayList<byte[]>();
        PDFRenderer renderer = new PDFRenderer(doc);
        for (int i = 0; i < pageCount; i++) {
            BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
            pages.add(encodeJpeg(image));
            image.flush();
        }
        return pages;
    }

    private static List<byte[]> renderParallel(PDDocument doc, int pageCount, int dpi) throws IOException {
        int threads = Math.min(PARALLEL_THREADS, pageCount);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            // PDFRenderer는 thread-safe하지 않으므로 synchronized 사용
            final PDFRenderer renderer = new PDFRenderer(doc);
            final Object renderLock = new Object();

            List<Future<byte[]>> futures = new ArrayList<Future<byte[]>>();
            for (int i = 0; i < pageCount; i++) {
                final int pageIndex = i;
                final int renderDpi = dpi;
                futures.add(executor.submit(new Callable<byte[]>() {
                    public byte[] call() throws Exception {
                        BufferedImage image;
                        synchronized (renderLock) {
                            image = renderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB);
                        }
                        // JPEG 인코딩은 병렬 수행 (락 밖)
                        byte[] result = encodeJpeg(image);
                        image.flush();
                        return result;
                    }
                }));
            }

            List<byte[]> pages = new ArrayList<byte[]>();
            for (Future<byte[]> future : futures) {
                try {
                    pages.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("PDF rendering interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException) throw (IOException) cause;
                    throw new IOException("PDF rendering failed", cause);
                }
            }
            return pages;
        } finally {
            executor.shutdown();
        }
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
