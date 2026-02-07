package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLGeometry;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLImageFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateImage;
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
import java.util.Base64;
import java.util.List;

/**
 * IDML 이미지 프레임을 IntermediateFrame으로 변환한다.
 */
public class ImageFrameConverter {

    private final IDMLDocument idmlDoc;
    private final ConvertOptions options;
    private final List<String> warnings;

    public ImageFrameConverter(IDMLDocument idmlDoc, ConvertOptions options, List<String> warnings) {
        this.idmlDoc = idmlDoc;
        this.options = options;
        this.warnings = warnings;
    }

    /**
     * 이미지 프레임을 IntermediateFrame으로 변환한다.
     */
    public IntermediateFrame convert(IDMLImageFrame imgFrame, IDMLPage page, int zOrder) {
        IntermediateFrame iFrame = new IntermediateFrame();
        iFrame.frameId("frame_" + imgFrame.selfId());
        iFrame.frameType("image");
        iFrame.zOrder(zOrder);

        setFramePosition(iFrame, imgFrame.geometricBounds(), imgFrame.itemTransform(),
                page.geometricBounds(), page.itemTransform());

        // 배경 이미지 판별: 페이지 면적의 80% 이상을 차지하면 배경으로 간주
        long pageWidth = page.widthHwpunits();
        long pageHeight = page.heightHwpunits();
        long pageArea = pageWidth * pageHeight;
        long frameArea = iFrame.width() * iFrame.height();
        if (pageArea > 0 && frameArea >= pageArea * 0.8) {
            iFrame.isBackgroundImage(true);
        }

        IntermediateImage iImage = new IntermediateImage();
        iImage.imageId("img_" + imgFrame.selfId());
        iImage.originalPath(imgFrame.linkResourceURI());
        iImage.displayWidth(iFrame.width());
        iImage.displayHeight(iFrame.height());

        String uri = imgFrame.linkResourceURI();
        if (uri != null) {
            iImage.format(detectFormat(stripFileUri(uri)));
        }

        // 이미지 파일 로드 및 필요 시 PNG로 변환
        if (options.includeImages()) {
            loadAndConvertImage(imgFrame, iImage);
        }

        iFrame.image(iImage);
        return iFrame;
    }

    private void setFramePosition(IntermediateFrame iFrame,
                                   double[] frameBounds, double[] frameTransform,
                                   double[] pageBounds, double[] pageTransform) {
        double[] frameAbs = IDMLGeometry.absoluteTopLeft(frameBounds, frameTransform);
        double[] pageAbs = IDMLGeometry.absoluteTopLeft(pageBounds, pageTransform);

        double relX = frameAbs[0] - pageAbs[0];
        double relY = frameAbs[1] - pageAbs[1];
        double w = IDMLGeometry.width(frameBounds);
        double h = IDMLGeometry.height(frameBounds);

        iFrame.x(CoordinateConverter.pointsToHwpunits(relX));
        iFrame.y(CoordinateConverter.pointsToHwpunits(relY));
        iFrame.width(CoordinateConverter.pointsToHwpunits(w));
        iFrame.height(CoordinateConverter.pointsToHwpunits(h));
    }

    /**
     * 이미지 파일 경로를 해석한다.
     */
    private String resolveImagePath(IDMLImageFrame imgFrame) {
        String uri = imgFrame.linkResourceURI();
        if (uri == null || uri.isEmpty()) return null;

        String path = stripFileUri(uri);
        String filename = extractFilename(path);

        // 1. 절대 경로 시도
        File absolute = new File(path);
        if (absolute.exists()) return absolute.getAbsolutePath();

        // 2. options.linksDirectory()에서 검색
        if (options.linksDirectory() != null && filename != null) {
            File linksDir = new File(options.linksDirectory());
            if (linksDir.isDirectory()) {
                File inLinks = new File(linksDir, filename);
                if (inLinks.exists()) return inLinks.getAbsolutePath();

                String found = findFileIgnoreCase(linksDir, filename);
                if (found != null) return found;
            }
        }

        // 3. 상대 경로 (basePath 기준)
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

    private static String extractFilename(String path) {
        if (path == null || path.isEmpty()) return null;
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
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

    private static String stripFileUri(String uri) {
        String path = uri;
        if (path.startsWith("file:///")) {
            path = path.substring("file://".length());
        } else if (path.startsWith("file:/")) {
            path = path.substring("file:".length());
        }
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (Exception e) {
            // 디코딩 실패 시 원본 사용
        }
        return path;
    }

    private static String detectFormat(String filename) {
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1) {
            String ext = lower.substring(dot + 1);
            switch (ext) {
                case "jpg":
                case "jpeg":
                    return "jpeg";
                case "png":
                    return "png";
                case "gif":
                    return "gif";
                case "bmp":
                    return "bmp";
                case "tiff":
                case "tif":
                    return "tiff";
                case "psd":
                    return "psd";
                case "ai":
                    return "ai";
                default:
                    return ext;
            }
        }
        return "png";
    }

    /**
     * 이미지 파일을 로드하고, 필요 시 PNG로 변환하여 base64Data를 설정한다.
     */
    private void loadAndConvertImage(IDMLImageFrame imgFrame, IntermediateImage iImage) {
        String resolvedPath = resolveImagePath(imgFrame);
        if (resolvedPath == null) {
            String uri = imgFrame.linkResourceURI();
            String filename = extractFilename(stripFileUri(uri != null ? uri : ""));
            StringBuilder msg = new StringBuilder("Image not found: ");
            msg.append(filename != null ? filename : uri);
            if (idmlDoc.basePath() != null) {
                File linksDir = new File(idmlDoc.basePath(), "Links");
                if (!linksDir.isDirectory()) {
                    msg.append(" (IDML package has no Links/ folder - images not embedded)");
                } else {
                    msg.append(" (not found in IDML Links/ folder)");
                }
            }
            warnings.add(msg.toString());
            createPlaceholderImage(iImage, filename);
            return;
        }

        File imageFile = new File(resolvedPath);
        String format = iImage.format();

        try {
            byte[] imageData;
            String outputFormat = format;

            if (isDesignFormat(format)) {
                boolean isCacheable = "psd".equals(format) || "ai".equals(format) || "eps".equals(format);
                File cacheFile = isCacheable ? new File(imageFile.getAbsolutePath() + ".png") : null;

                if (isCacheable && cacheFile.exists() && cacheFile.length() > 0) {
                    System.err.println("[INFO]     캐시된 PNG 사용: " + cacheFile.getName());
                    imageData = Files.readAllBytes(cacheFile.toPath());
                } else {
                    if ("ai".equals(format) || "pdf".equals(format) || "eps".equals(format)) {
                        imageData = DesignFileConverter.convertToPng(imageFile, options.imageDpi());
                    } else {
                        imageData = DesignFileConverter.convertToPng(imageFile);
                    }

                    if (isCacheable && imageData != null && imageData.length > 0) {
                        try {
                            Files.write(cacheFile.toPath(), imageData);
                            System.err.println("[INFO]     캐시 저장: " + cacheFile.getName());
                        } catch (Exception ce) {
                            System.err.println("[WARN]     캐시 저장 실패: " + ce.getMessage());
                        }
                    }
                }
                outputFormat = "png";
            } else {
                imageData = Files.readAllBytes(imageFile.toPath());
            }

            // 클리핑이 필요한 경우
            double[] imageTransform = imgFrame.imageTransform();
            double[] frameBounds = imgFrame.geometricBounds();
            if (imageTransform != null && frameBounds != null) {
                imageData = applyImageClipping(imageData, imgFrame, iImage);
                outputFormat = "png";
            } else {
                byte[] originalData = imageData;
                imageData = processImageWithoutClipping(imageData, iImage);
                if (imageData != originalData) {
                    outputFormat = "png";
                }
            }

            iImage.base64Data(Base64.getEncoder().encodeToString(imageData));
            iImage.format(outputFormat);

        } catch (IOException e) {
            warnings.add("Image conversion failed: " + imageFile.getName()
                    + " (" + format + ") - " + e.getMessage());
            createPlaceholderImage(iImage, imageFile.getName());
        }
    }

    private byte[] processImageWithoutClipping(byte[] imageData, IntermediateImage iImage) throws IOException {
        int originalWidth, originalHeight;
        try {
            int[] pixelSize = ImageInserter.detectPixelSize(imageData);
            originalWidth = pixelSize[0];
            originalHeight = pixelSize[1];
        } catch (IOException e) {
            originalWidth = (int)(iImage.displayWidth() / 24);
            originalHeight = (int)(iImage.displayHeight() / 24);
        }

        int targetDpi = options.imageDpi();
        int targetWidth = (int) Math.round(iImage.displayWidth() * targetDpi / 7200.0);
        int targetHeight = (int) Math.round(iImage.displayHeight() * targetDpi / 7200.0);

        targetWidth = Math.max(10, targetWidth);
        targetHeight = Math.max(10, targetHeight);

        if (targetWidth > originalWidth || targetHeight > originalHeight) {
            targetWidth = originalWidth;
            targetHeight = originalHeight;
        }

        boolean needsResize = originalWidth > targetWidth * 1.2 || originalHeight > targetHeight * 1.2;

        if (needsResize) {
            byte[] resizedData = resizeImage(imageData, targetWidth, targetHeight);
            iImage.pixelWidth(targetWidth);
            iImage.pixelHeight(targetHeight);
            return resizedData;
        } else {
            iImage.pixelWidth(originalWidth);
            iImage.pixelHeight(originalHeight);
            return imageData;
        }
    }

    private byte[] applyImageClipping(byte[] imageData, IDMLImageFrame imgFrame,
                                       IntermediateImage iImage) throws IOException {
        BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(imageData));
        if (srcImage == null) {
            warnings.add("Failed to decode image for clipping");
            return imageData;
        }

        double[] frameBounds = imgFrame.geometricBounds();
        double[] imageTransform = imgFrame.imageTransform();
        double[] graphicBounds = imgFrame.graphicBounds();

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
        double pointsToPixelX = (graphicW > 0) ? srcImage.getWidth() / graphicW : 1.0;
        double pointsToPixelY = (graphicH > 0) ? srcImage.getHeight() / graphicH : 1.0;

        double frameLeft = frameBounds[1];
        double frameTop = frameBounds[0];
        double frameRight = frameBounds[3];
        double frameBottom = frameBounds[2];
        double frameW = frameRight - frameLeft;
        double frameH = frameBottom - frameTop;

        double graphicLeft = (frameLeft - imgTx) / imgScaleX + gLeft;
        double graphicTop = (frameTop - imgTy) / imgScaleY + gTop;
        double graphicRight = (frameRight - imgTx) / imgScaleX + gLeft;
        double graphicBottom = (frameBottom - imgTy) / imgScaleY + gTop;

        double srcLeft = (graphicLeft - gLeft) * pointsToPixelX;
        double srcTop = (graphicTop - gTop) * pointsToPixelY;
        double srcRight = (graphicRight - gLeft) * pointsToPixelX;
        double srcBottom = (graphicBottom - gTop) * pointsToPixelY;

        int srcX1 = Math.max(0, (int) Math.floor(Math.min(srcLeft, srcRight)));
        int srcY1 = Math.max(0, (int) Math.floor(Math.min(srcTop, srcBottom)));
        int srcX2 = Math.min(srcImage.getWidth(), (int) Math.ceil(Math.max(srcLeft, srcRight)));
        int srcY2 = Math.min(srcImage.getHeight(), (int) Math.ceil(Math.max(srcTop, srcBottom)));

        if (srcX1 >= srcX2 || srcY1 >= srcY2) {
            warnings.add("Image clipping resulted in empty area");
            return imageData;
        }

        int targetDpi = options.imageDpi();
        int pixelWidth = (int) Math.ceil(frameW * targetDpi / 72.0);
        int pixelHeight = (int) Math.ceil(frameH * targetDpi / 72.0);
        pixelWidth = Math.max(10, pixelWidth);
        pixelHeight = Math.max(10, pixelHeight);

        BufferedImage clippedImage = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clippedImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int actualSrcX1 = srcX1, actualSrcX2 = srcX2;
        int actualSrcY1 = srcY1, actualSrcY2 = srcY2;
        if (imgScaleX < 0) {
            actualSrcX1 = srcImage.getWidth() - srcX2;
            actualSrcX2 = srcImage.getWidth() - srcX1;
        }
        if (imgScaleY < 0) {
            actualSrcY1 = srcImage.getHeight() - srcY2;
            actualSrcY2 = srcImage.getHeight() - srcY1;
        }

        g.drawImage(srcImage,
                0, 0, pixelWidth, pixelHeight,
                actualSrcX1, actualSrcY1, actualSrcX2, actualSrcY2, null);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(clippedImage, "png", baos);

        iImage.pixelWidth(pixelWidth);
        iImage.pixelHeight(pixelHeight);

        return baos.toByteArray();
    }

    private byte[] resizeImage(byte[] imageData, int targetWidth, int targetHeight) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageData));
        if (original == null) {
            return imageData;
        }

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(resized, "png", baos);
        return baos.toByteArray();
    }

    private void createPlaceholderImage(IntermediateImage iImage, String filename) {
        int width = Math.max(100, (int)(iImage.displayWidth() / 75));
        int height = Math.max(100, (int)(iImage.displayHeight() / 75));

        if (width > 800) {
            height = height * 800 / width;
            width = 800;
        }
        if (height > 800) {
            width = width * 800 / height;
            height = 800;
        }

        try {
            byte[] pngData = createPlaceholderPng(width, height, filename);
            iImage.base64Data(Base64.getEncoder().encodeToString(pngData));
            iImage.format("png");
            iImage.pixelWidth(width);
            iImage.pixelHeight(height);
        } catch (Exception e) {
            // placeholder 생성 실패 시 무시
        }
    }

    private static byte[] createPlaceholderPng(int width, int height, String filename) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // 배경: 연한 회색
        g.setColor(new Color(220, 220, 220));
        g.fillRect(0, 0, width, height);

        // 테두리: 진한 회색
        g.setColor(new Color(128, 128, 128));
        g.drawRect(0, 0, width - 1, height - 1);

        // X 표시: 빨간색
        g.setColor(new Color(200, 50, 50));
        g.setStroke(new BasicStroke(2));
        int margin = Math.min(width, height) / 6;
        g.drawLine(margin, margin, width - margin, height - margin);
        g.drawLine(width - margin, margin, margin, height - margin);

        // 파일명 표시
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

    private static boolean isDesignFormat(String format) {
        if (format == null) return false;
        switch (format.toLowerCase()) {
            case "psd":
            case "ai":
            case "pdf":
            case "eps":
            case "tiff":
            case "tif":
                return true;
            default:
                return false;
        }
    }
}
