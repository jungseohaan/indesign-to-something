package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.imageinserter.DesignFileConverter;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;

/**
 * AST 파이프라인용 이미지 로더.
 * 이미지 파일 경로 해석, 바이너리 로드, 플레이스홀더 생성을 담당한다.
 */
public class ASTImageLoader {

    private final IDMLDocument idmlDoc;
    private final ConvertOptions options;

    public ASTImageLoader(IDMLDocument idmlDoc, ConvertOptions options) {
        this.idmlDoc = idmlDoc;
        this.options = options;
    }

    /**
     * 이미지 로드 결과.
     */
    public static class ImageResult {
        public byte[] imageData;
        public String format;
        public int pixelWidth;
        public int pixelHeight;
        public boolean isPlaceholder;
    }

    /**
     * 이미지 URI에서 바이너리 데이터를 로드한다.
     * 파일을 찾을 수 없으면 플레이스홀더를 생성한다.
     *
     * @param linkResourceURI    이미지 링크 URI
     * @param displayWidthHwp    표시 너비 (HWPUNIT)
     * @param displayHeightHwp   표시 높이 (HWPUNIT)
     * @param imageTransform     이미지 내부 transform (클리핑용, null 가능)
     * @param frameBoundsPoints  프레임 bounds in points (클리핑용, null 가능)
     * @param graphicBounds      원본 이미지 크기 bounds (클리핑용, null 가능)
     * @return 이미지 로드 결과
     */
    public ImageResult loadImage(String linkResourceURI,
                                  long displayWidthHwp, long displayHeightHwp,
                                  double[] imageTransform, double[] frameBoundsPoints,
                                  double[] graphicBounds) {
        if (linkResourceURI == null || linkResourceURI.isEmpty()) {
            return createPlaceholderResult(displayWidthHwp, displayHeightHwp, null);
        }

        String path = stripFileUri(linkResourceURI);
        String filename = extractFilename(path);
        String format = detectFormat(path);

        String resolvedPath = resolveImagePath(path, filename);
        if (resolvedPath == null) {
            System.err.println("[ASTImageLoader] Image not found: " + filename);
            return createPlaceholderResult(displayWidthHwp, displayHeightHwp, filename);
        }

        try {
            File imageFile = new File(resolvedPath);
            byte[] imageData;
            String outputFormat = format;

            if (isDesignFormat(format)) {
                boolean isCacheable = "psd".equals(format) || "ai".equals(format) || "eps".equals(format);
                File cacheFile = isCacheable ? new File(imageFile.getAbsolutePath() + ".png") : null;

                if (isCacheable && cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
                    imageData = Files.readAllBytes(cacheFile.toPath());
                } else {
                    if ("ai".equals(format) || "pdf".equals(format) || "eps".equals(format)) {
                        imageData = DesignFileConverter.convertToPng(imageFile, options.imageDpi());
                    } else {
                        imageData = DesignFileConverter.convertToPng(imageFile);
                    }
                    if (isCacheable && cacheFile != null && imageData != null && imageData.length > 0) {
                        try {
                            Files.write(cacheFile.toPath(), imageData);
                        } catch (Exception ignored) {}
                    }
                }
                outputFormat = "png";
            } else {
                imageData = Files.readAllBytes(imageFile.toPath());
            }

            if (imageData == null || imageData.length == 0) {
                return createPlaceholderResult(displayWidthHwp, displayHeightHwp, filename);
            }

            // 클리핑 적용
            if (imageTransform != null && frameBoundsPoints != null) {
                imageData = applyClipping(imageData, imageTransform, frameBoundsPoints, graphicBounds);
                outputFormat = "png";
            }

            // 픽셀 크기 감지
            int pixelW, pixelH;
            try {
                int[] size = ImageInserter.detectPixelSize(imageData);
                pixelW = size[0];
                pixelH = size[1];
            } catch (IOException e) {
                pixelW = Math.max(10, (int)(displayWidthHwp / 75));
                pixelH = Math.max(10, (int)(displayHeightHwp / 75));
            }

            // DPI 기반 리사이즈
            int targetDpi = options.imageDpi();
            int targetW = Math.max(10, (int) Math.round(displayWidthHwp * targetDpi / 7200.0));
            int targetH = Math.max(10, (int) Math.round(displayHeightHwp * targetDpi / 7200.0));

            if (targetW < pixelW && targetH < pixelH
                    && (pixelW > targetW * 1.2 || pixelH > targetH * 1.2)) {
                imageData = resizeImage(imageData, targetW, targetH);
                pixelW = targetW;
                pixelH = targetH;
                outputFormat = "png";
            }

            ImageResult result = new ImageResult();
            result.imageData = imageData;
            result.format = outputFormat;
            result.pixelWidth = pixelW;
            result.pixelHeight = pixelH;
            result.isPlaceholder = false;
            return result;

        } catch (Exception e) {
            System.err.println("[ASTImageLoader] Failed to load: " + filename + " - " + e.getMessage());
            return createPlaceholderResult(displayWidthHwp, displayHeightHwp, filename);
        }
    }

    private String resolveImagePath(String path, String filename) {
        // 1. 절대 경로
        File absolute = new File(path);
        if (absolute.exists()) return absolute.getAbsolutePath();

        // 2. options.linksDirectory()
        if (options.linksDirectory() != null && filename != null) {
            File linksDir = new File(options.linksDirectory());
            if (linksDir.isDirectory()) {
                File inLinks = new File(linksDir, filename);
                if (inLinks.exists()) return inLinks.getAbsolutePath();
                String found = findFileIgnoreCase(linksDir, filename);
                if (found != null) return found;
            }
        }

        // 3. basePath 기준
        if (idmlDoc.basePath() != null) {
            File relative = new File(idmlDoc.basePath(), path);
            if (relative.exists()) return relative.getAbsolutePath();

            if (path.startsWith("Links/")) {
                File linksRelative = new File(idmlDoc.basePath(), path.substring("Links/".length()));
                if (linksRelative.exists()) return linksRelative.getAbsolutePath();
            }

            if (filename != null) {
                File inLinks = new File(new File(idmlDoc.basePath(), "Links"), filename);
                if (inLinks.exists()) return inLinks.getAbsolutePath();

                File inBase = new File(idmlDoc.basePath(), filename);
                if (inBase.exists()) return inBase.getAbsolutePath();

                File linksDir = new File(idmlDoc.basePath(), "Links");
                if (linksDir.isDirectory()) {
                    String found = findFileIgnoreCase(linksDir, filename);
                    if (found != null) return found;
                }
            }
        }

        return null;
    }

    private byte[] applyClipping(byte[] imageData, double[] imageTransform,
                                  double[] frameBounds, double[] graphicBounds) throws IOException {
        BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(imageData));
        if (srcImage == null) return imageData;

        double imgScaleX = imageTransform[0];
        double imgScaleY = imageTransform[3];
        double imgTx = imageTransform[4];
        double imgTy = imageTransform[5];

        double gLeft = 0, gTop = 0, gRight = srcImage.getWidth(), gBottom = srcImage.getHeight();
        if (graphicBounds != null && graphicBounds.length >= 4) {
            gLeft = graphicBounds[0];
            gTop = graphicBounds[1];
            gRight = graphicBounds[2];
            gBottom = graphicBounds[3];
        }

        double absScaleX = Math.abs(imgScaleX);
        double absScaleY = Math.abs(imgScaleY);
        if (absScaleX < 0.001) absScaleX = 1.0;
        if (absScaleY < 0.001) absScaleY = 1.0;

        double graphicW = gRight - gLeft;
        double graphicH = gBottom - gTop;
        double pxPerPtX = (graphicW > 0) ? srcImage.getWidth() / graphicW : 1.0;
        double pxPerPtY = (graphicH > 0) ? srcImage.getHeight() / graphicH : 1.0;

        double fLeft = frameBounds[1], fTop = frameBounds[0];
        double fRight = frameBounds[3], fBottom = frameBounds[2];
        double frameW = fRight - fLeft;
        double frameH = fBottom - fTop;

        double gL2 = (fLeft - imgTx) / imgScaleX + gLeft;
        double gT2 = (fTop - imgTy) / imgScaleY + gTop;
        double gR2 = (fRight - imgTx) / imgScaleX + gLeft;
        double gB2 = (fBottom - imgTy) / imgScaleY + gTop;

        double srcL = (gL2 - gLeft) * pxPerPtX;
        double srcT = (gT2 - gTop) * pxPerPtY;
        double srcR = (gR2 - gLeft) * pxPerPtX;
        double srcB = (gB2 - gTop) * pxPerPtY;

        int sx1 = Math.max(0, (int) Math.floor(Math.min(srcL, srcR)));
        int sy1 = Math.max(0, (int) Math.floor(Math.min(srcT, srcB)));
        int sx2 = Math.min(srcImage.getWidth(), (int) Math.ceil(Math.max(srcL, srcR)));
        int sy2 = Math.min(srcImage.getHeight(), (int) Math.ceil(Math.max(srcT, srcB)));

        if (sx1 >= sx2 || sy1 >= sy2) return imageData;

        int targetDpi = options.imageDpi();
        int pixW = Math.max(10, (int) Math.ceil(frameW * targetDpi / 72.0));
        int pixH = Math.max(10, (int) Math.ceil(frameH * targetDpi / 72.0));

        BufferedImage clipped = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clipped.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int ax1 = sx1, ax2 = sx2, ay1 = sy1, ay2 = sy2;
        if (imgScaleX < 0) { ax1 = srcImage.getWidth() - sx2; ax2 = srcImage.getWidth() - sx1; }
        if (imgScaleY < 0) { ay1 = srcImage.getHeight() - sy2; ay2 = srcImage.getHeight() - sy1; }

        g.drawImage(srcImage, 0, 0, pixW, pixH, ax1, ay1, ax2, ay2, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(clipped, "png", baos);
        return baos.toByteArray();
    }

    private ImageResult createPlaceholderResult(long displayWidthHwp, long displayHeightHwp, String filename) {
        int width = Math.max(50, (int)(displayWidthHwp / 75));
        int height = Math.max(50, (int)(displayHeightHwp / 75));
        if (width > 800) { height = height * 800 / width; width = 800; }
        if (height > 800) { width = width * 800 / height; height = 800; }

        try {
            byte[] pngData = createPlaceholderPng(width, height, filename);
            ImageResult result = new ImageResult();
            result.imageData = pngData;
            result.format = "png";
            result.pixelWidth = width;
            result.pixelHeight = height;
            result.isPlaceholder = true;
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] createPlaceholderPng(int width, int height, String filename) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        g.setColor(new Color(220, 220, 220));
        g.fillRect(0, 0, width, height);

        g.setColor(new Color(128, 128, 128));
        g.drawRect(0, 0, width - 1, height - 1);

        g.setColor(new Color(200, 50, 50));
        g.setStroke(new BasicStroke(2));
        int margin = Math.min(width, height) / 6;
        g.drawLine(margin, margin, width - margin, height - margin);
        g.drawLine(width - margin, margin, margin, height - margin);

        if (filename != null && !filename.isEmpty()) {
            g.setColor(new Color(80, 80, 80));
            Font font = new Font("SansSerif", Font.PLAIN, Math.max(10, Math.min(14, height / 10)));
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();

            String displayName = filename;
            if (fm.stringWidth(displayName) > width - 10) {
                while (displayName.length() > 3 && fm.stringWidth(displayName + "...") > width - 10) {
                    displayName = displayName.substring(0, displayName.length() - 1);
                }
                displayName = displayName + "...";
            }

            int textX = (width - fm.stringWidth(displayName)) / 2;
            int textY = height - margin / 2;
            g.drawString(displayName, textX, textY);
        }

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private byte[] resizeImage(byte[] imageData, int targetWidth, int targetHeight) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageData));
        if (original == null) return imageData;

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(resized, "png", baos);
        return baos.toByteArray();
    }

    private static String stripFileUri(String uri) {
        String path = uri;
        if (path.startsWith("file:///")) {
            path = path.substring("file://".length());
        } else if (path.startsWith("file:/")) {
            path = path.substring("file:".length());
        }
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (Exception ignored) {}
        return path;
    }

    private static String extractFilename(String path) {
        if (path == null || path.isEmpty()) return null;
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    private static String detectFormat(String filename) {
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) {
            String ext = lower.substring(dot + 1);
            switch (ext) {
                case "jpg": case "jpeg": return "jpeg";
                case "png": return "png";
                case "gif": return "gif";
                case "bmp": return "bmp";
                case "tiff": case "tif": return "tiff";
                case "psd": return "psd";
                case "ai": return "ai";
                default: return ext;
            }
        }
        return "png";
    }

    private static boolean isDesignFormat(String format) {
        if (format == null) return false;
        switch (format.toLowerCase()) {
            case "psd": case "ai": case "pdf": case "eps": case "tiff": case "tif":
                return true;
            default:
                return false;
        }
    }

    private static String findFileIgnoreCase(File directory, String filename) {
        File[] files = directory.listFiles();
        if (files == null) return null;
        String lowerTarget = filename.toLowerCase();
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().equals(lowerTarget)) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }
}
