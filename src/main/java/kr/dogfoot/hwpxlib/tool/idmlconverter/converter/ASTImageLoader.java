package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.imageinserter.DesignFileConverter;
import kr.dogfoot.hwpxlib.tool.imageinserter.ImageInserter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AST 파이프라인용 이미지 로더.
 * 이미지 파일 경로 해석, 바이너리 로드, 플레이스홀더 생성을 담당한다.
 */
public class ASTImageLoader {

    private final IDMLDocument idmlDoc;
    private final ConvertOptions options;

    public boolean drawMarginGuide() { return options.drawMarginGuide(); }

    // 경로 캐시: URI → 절대경로 (병렬 loadImage 대응)
    private final ConcurrentHashMap<String, String> resolvedPathCache = new ConcurrentHashMap<>();
    // 디렉토리 파일 목록 캐시: dirPath → {lowerName → File} (대소문자 무시 검색 O(1))
    private final ConcurrentHashMap<String, Map<String, File>> dirListingCache = new ConcurrentHashMap<>();

    // 이미지 로드 실패 경고 수집
    private final List<String> warnings = new ArrayList<>();
    public List<String> warnings() { return warnings; }

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
        public double widthPts;   // 원본 포인트 크기 (합성 래스터화 시 사용)
        public double heightPts;
    }

    /**
     * 벡터 도형 + 색상 정보 묶음 (합성 래스터화용).
     * accTransform: 도형의 로컬 좌표를 그룹 루트 좌표로 변환하는 누적 변환.
     * 중첩 그룹 내부의 도형이 서로 다른 로컬 좌표계를 가질 때 정확한 위치/크기를 계산한다.
     */
    public static class ShapeWithColor {
        public final IDMLVectorShape shape;
        public final String fillHex;
        public final String strokeHex;
        public final double[] accTransform; // null이면 변환 없음 (항등 변환)

        public ShapeWithColor(IDMLVectorShape shape, String fillHex, String strokeHex) {
            this(shape, fillHex, strokeHex, null);
        }

        public ShapeWithColor(IDMLVectorShape shape, String fillHex, String strokeHex,
                              double[] accTransform) {
            this.shape = shape;
            this.fillHex = fillHex;
            this.strokeHex = strokeHex;
            this.accTransform = accTransform;
        }

        /**
         * 도형의 geometricBounds를 누적 변환으로 변환한 결과를 반환한다.
         * [top, left, bottom, right] → 변환 후 [minY, minX, maxY, maxX]
         */
        public double[] transformedBounds() {
            double[] b = shape.geometricBounds();
            if (b == null || b.length < 4) return b;
            if (accTransform == null) return b;

            double a = accTransform[0], bv = accTransform[1];
            double c = accTransform[2], d = accTransform[3];
            double tx = accTransform[4], ty = accTransform[5];

            // 4 corners: (left, top), (right, top), (left, bottom), (right, bottom)
            double[] xs = new double[4];
            double[] ys = new double[4];
            xs[0] = a * b[1] + c * b[0] + tx;  ys[0] = bv * b[1] + d * b[0] + ty;
            xs[1] = a * b[3] + c * b[0] + tx;  ys[1] = bv * b[3] + d * b[0] + ty;
            xs[2] = a * b[1] + c * b[2] + tx;  ys[2] = bv * b[1] + d * b[2] + ty;
            xs[3] = a * b[3] + c * b[2] + tx;  ys[3] = bv * b[3] + d * b[2] + ty;

            double minX = xs[0], maxX = xs[0], minY = ys[0], maxY = ys[0];
            for (int i = 1; i < 4; i++) {
                if (xs[i] < minX) minX = xs[i];
                if (xs[i] > maxX) maxX = xs[i];
                if (ys[i] < minY) minY = ys[i];
                if (ys[i] > maxY) maxY = ys[i];
            }
            return new double[]{minY, minX, maxY, maxX};
        }
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
        return loadImage(linkResourceURI, displayWidthHwp, displayHeightHwp,
                imageTransform, frameBoundsPoints, graphicBounds, null, null);
    }

    /**
     * 이미지 URI에서 바이너리 데이터를 로드한다 (PSD 레이어 가시성 오버라이드 지원).
     *
     * @param linkResourceURI    이미지 링크 URI
     * @param displayWidthHwp    표시 너비 (HWPUNIT)
     * @param displayHeightHwp   표시 높이 (HWPUNIT)
     * @param imageTransform     이미지 내부 transform (클리핑용, null 가능)
     * @param frameBoundsPoints  프레임 bounds in points (클리핑용, null 가능)
     * @param graphicBounds      원본 이미지 크기 bounds (클리핑용, null 가능)
     * @param visibleLayerIndices PSD 가시 레이어의 ImageMagick 인덱스 목록 (null이면 컴포지트 사용)
     * @param layerSignature     캐시 키용 레이어 서명 (null이면 기본 캐시)
     * @return 이미지 로드 결과
     */
    public ImageResult loadImage(String linkResourceURI,
                                  long displayWidthHwp, long displayHeightHwp,
                                  double[] imageTransform, double[] frameBoundsPoints,
                                  double[] graphicBounds,
                                  List<Integer> visibleLayerIndices, String layerSignature) {
        return loadImage(linkResourceURI, displayWidthHwp, displayHeightHwp,
                imageTransform, frameBoundsPoints, graphicBounds,
                visibleLayerIndices, layerSignature, null);
    }

    /**
     * 이미지를 로드하고 프레임 클리핑을 적용한다.
     * @param framePath 비사각형 프레임 경로 (null이면 사각형 클리핑만 적용)
     */
    public ImageResult loadImage(String linkResourceURI,
                                  long displayWidthHwp, long displayHeightHwp,
                                  double[] imageTransform, double[] frameBoundsPoints,
                                  double[] graphicBounds,
                                  List<Integer> visibleLayerIndices, String layerSignature,
                                  List<double[]> framePath) {
        return loadImage(linkResourceURI, displayWidthHwp, displayHeightHwp,
                imageTransform, frameBoundsPoints, graphicBounds,
                visibleLayerIndices, layerSignature, framePath, 0, null);
    }

    /**
     * 이미지를 로드하고 프레임 클리핑 + 둥근 모서리를 적용한다.
     * @param framePath 비사각형 프레임 경로 (null이면 사각형 클리핑만 적용)
     * @param cornerRadius 둥근 모서리 반경 (points, 0이면 적용 안함)
     */
    public ImageResult loadImage(String linkResourceURI,
                                  long displayWidthHwp, long displayHeightHwp,
                                  double[] imageTransform, double[] frameBoundsPoints,
                                  double[] graphicBounds,
                                  List<Integer> visibleLayerIndices, String layerSignature,
                                  List<double[]> framePath, double cornerRadius,
                                  double[] cornerRadii) {
        if (linkResourceURI == null || linkResourceURI.isEmpty()) {
            return createPlaceholderResult(displayWidthHwp, displayHeightHwp, null);
        }

        String path = stripFileUri(linkResourceURI);
        String filename = extractFilename(path);
        String format = detectFormat(path);

        String resolvedPath = resolveImagePath(path, filename);
        if (resolvedPath == null) {
            System.err.println("[ASTImageLoader] Image not found: " + filename);
            warnings.add("[Image] 이미지 파일 없음: " + filename);
            return createPlaceholderResult(displayWidthHwp, displayHeightHwp, filename);
        }

        try {
            File imageFile = new File(resolvedPath);
            byte[] imageData;
            String outputFormat = format;

            if (isDesignFormat(format)) {
                boolean isPsd = "psd".equals(format);
                boolean hasLayerOverride = isPsd && visibleLayerIndices != null && !visibleLayerIndices.isEmpty();
                boolean isCacheable = isPsd || "ai".equals(format) || "eps".equals(format);

                // 레이어 오버라이드가 있으면 레이어 서명을 캐시 키에 포함
                String cacheFilename = hasLayerOverride && layerSignature != null
                        ? filename + "." + layerSignature : filename;
                File cacheFile = isCacheable ? resolveCacheFile(imageFile, cacheFilename) : null;

                if (isCacheable && cacheFile != null && cacheFile.exists() && cacheFile.length() > 0) {
                    imageData = Files.readAllBytes(cacheFile.toPath());
                } else {
                    if (hasLayerOverride) {
                        imageData = DesignFileConverter.convertPsdWithLayers(imageFile, visibleLayerIndices);
                    } else if ("ai".equals(format) || "pdf".equals(format) || "eps".equals(format)) {
                        imageData = DesignFileConverter.convertToPng(imageFile, options.imageDpi());
                    } else {
                        imageData = DesignFileConverter.convertToPng(imageFile);
                    }
                    if (isCacheable && cacheFile != null && imageData != null && imageData.length > 0) {
                        try {
                            cacheFile.getParentFile().mkdirs();
                            Files.write(cacheFile.toPath(), imageData);
                        } catch (Exception e) {
                            System.err.println("[ASTImageLoader] 캐시 쓰기 실패: " + cacheFile + " - " + e.getMessage());
                        }
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
                imageData = applyClipping(imageData, imageTransform, frameBoundsPoints, graphicBounds, framePath, cornerRadius, cornerRadii);
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
            warnings.add("[Image] 이미지 로드 실패: " + filename + " - " + e.getMessage());
            return createPlaceholderResult(displayWidthHwp, displayHeightHwp, filename);
        }
    }

    /**
     * IDML 내장(embedded/pasted) 이미지 데이터를 로드한다.
     * LinkResourceURI가 없는 이미지의 Contents 요소에 포함된 base64 데이터를 디코딩한다.
     *
     * @param base64Contents   base64 인코딩된 이미지 데이터 (줄바꿈 포함 가능)
     * @param displayWidthHwp  표시 너비 (HWPUNIT)
     * @param displayHeightHwp 표시 높이 (HWPUNIT)
     * @param imageTransform   이미지 내부 transform (클리핑용, null 가능)
     * @param frameBoundsPoints 프레임 bounds in points (클리핑용, null 가능)
     * @param graphicBounds    원본 이미지 크기 bounds (클리핑용, null 가능)
     * @param framePath        비사각형 프레임 경로 (null이면 사각형)
     * @param cornerRadius     둥근 모서리 반경 (0이면 없음)
     * @param cornerRadii      개별 모서리 반경 (null이면 균일)
     * @return 이미지 로드 결과
     */
    public ImageResult loadEmbeddedImage(String base64Contents,
                                          long displayWidthHwp, long displayHeightHwp,
                                          double[] imageTransform, double[] frameBoundsPoints,
                                          double[] graphicBounds,
                                          List<double[]> framePath, double cornerRadius,
                                          double[] cornerRadii) {
        if (base64Contents == null || base64Contents.isEmpty()) {
            return createPlaceholderResult(displayWidthHwp, displayHeightHwp, null);
        }

        try {
            // base64 디코딩 (줄바꿈/공백 제거)
            String cleaned = base64Contents.replaceAll("\\s+", "");
            byte[] imageData = java.util.Base64.getDecoder().decode(cleaned);

            if (imageData == null || imageData.length == 0) {
                return createPlaceholderResult(displayWidthHwp, displayHeightHwp, "embedded");
            }

            // 포맷 감지: JPEG (/9j/), TIFF (II*\0 = 49492A00 or MM\0* = 4D4D002A), PNG (89504E47)
            String outputFormat = detectEmbeddedFormat(imageData);

            // TIFF → PNG 변환 (HWPX는 TIFF를 지원하지 않음)
            if ("tiff".equals(outputFormat)) {
                imageData = convertTiffToPng(imageData);
                outputFormat = "png";
                if (imageData == null || imageData.length == 0) {
                    return createPlaceholderResult(displayWidthHwp, displayHeightHwp, "embedded");
                }
            }

            // 클리핑 적용
            if (imageTransform != null && frameBoundsPoints != null) {
                imageData = applyClipping(imageData, imageTransform, frameBoundsPoints,
                        graphicBounds, framePath, cornerRadius, cornerRadii);
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
            System.err.println("[ASTImageLoader] 내장 이미지 디코딩 실패: " + e.getMessage());
            warnings.add("[Image] 내장 이미지 디코딩 실패: " + e.getMessage());
            return createPlaceholderResult(displayWidthHwp, displayHeightHwp, "embedded");
        }
    }

    /**
     * InDesign에서 직접 렌더링된 PNG 이미지를 로드한다.
     * rendered_frames/ 디렉토리의 pre-rendered 이미지를 resolved.json 기준 상대경로로 로드.
     *
     * @param relativePath  resolved.json 기준 상대 경로 (예: "rendered_frames/pdf_12345.png")
     * @param displayWidthHwp  표시 너비 (HWPUNIT)
     * @param displayHeightHwp 표시 높이 (HWPUNIT)
     * @return 이미지 로드 결과, 파일 없으면 null
     */
    public ImageResult loadRenderedImage(String relativePath,
                                          long displayWidthHwp, long displayHeightHwp) {
        if (relativePath == null || relativePath.isEmpty()) return null;
        if (options.resolvedJsonPath() == null) return null;

        File resolvedFile = new File(options.resolvedJsonPath());
        if (!resolvedFile.isAbsolute()) resolvedFile = resolvedFile.getAbsoluteFile();
        File resolvedDir = resolvedFile.getParentFile();
        if (resolvedDir == null) return null;

        File pngFile = new File(resolvedDir, relativePath);
        if (!pngFile.exists()) return null;

        try {
            byte[] imageData = Files.readAllBytes(pngFile.toPath());
            if (imageData == null || imageData.length == 0) return null;

            int pixelW, pixelH;
            try {
                int[] size = ImageInserter.detectPixelSize(imageData);
                pixelW = size[0];
                pixelH = size[1];
            } catch (IOException e) {
                pixelW = Math.max(10, (int) (displayWidthHwp / 75));
                pixelH = Math.max(10, (int) (displayHeightHwp / 75));
            }

            ImageResult result = new ImageResult();
            result.imageData = imageData;
            result.format = "png";
            result.pixelWidth = pixelW;
            result.pixelHeight = pixelH;
            result.isPlaceholder = false;
            return result;
        } catch (Exception e) {
            System.err.println("[ASTImageLoader] 렌더링된 PDF 프레임 로드 실패: " + relativePath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 바이너리 데이터의 매직 바이트로 이미지 포맷을 감지한다.
     */
    private static String detectEmbeddedFormat(byte[] data) {
        if (data.length < 4) return "png";
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // PNG: 89 50 4E 47
        if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "png";
        }
        // TIFF: II*\0 (little-endian) or MM\0* (big-endian)
        if ((data[0] == 0x49 && data[1] == 0x49 && data[2] == 0x2A && data[3] == 0x00)
                || (data[0] == 0x4D && data[1] == 0x4D && data[2] == 0x00 && data[3] == 0x2A)) {
            return "tiff";
        }
        return "png";
    }

    /**
     * TIFF 바이너리를 PNG로 변환한다.
     */
    private static byte[] convertTiffToPng(byte[] tiffData) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(tiffData));
            if (img == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("[ASTImageLoader] TIFF→PNG 변환 실패: " + e.getMessage());
            return null;
        }
    }

    /**
     * 디자인 파일 PNG 변환 캐시 파일 경로를 결정한다.
     * imageCacheDir이 설정되어 있으면 그 디렉토리에, 아니면 원본 파일 옆에 저장.
     */
    private File resolveCacheFile(File imageFile, String filename) {
        String cacheDir = options.imageCacheDir();
        if (cacheDir != null) {
            return new File(cacheDir, filename + ".png");
        }
        return new File(imageFile.getAbsolutePath() + ".png");
    }

    private String resolveImagePath(String path, String filename) {
        // 캐시 히트 시 즉시 반환
        String cached = resolvedPathCache.get(path);
        if (cached != null) return cached;

        String result = resolveImagePathUncached(path, filename);
        if (result != null) {
            resolvedPathCache.put(path, result);
        }
        return result;
    }

    private String resolveImagePathUncached(String path, String filename) {
        // 1. 외부 Links 디렉토리 (INDD 원본 옆 Links 폴더 — 최우선)
        if (options.linksDirectory() != null && filename != null) {
            File extLinksDir = new File(options.linksDirectory());
            if (extLinksDir.isDirectory()) {
                File inLinks = new File(extLinksDir, filename);
                if (inLinks.exists()) return inLinks.getAbsolutePath();
                String found = findFileIgnoreCaseCached(extLinksDir, filename);
                if (found != null) return found;
            }
        }

        // 2. basePath/Links/ (IDML 파일 옆 Links 폴더)
        if (idmlDoc.basePath() != null && filename != null) {
            File linksDir = new File(idmlDoc.basePath(), "Links");
            if (linksDir.isDirectory()) {
                File inLinks = new File(linksDir, filename);
                if (inLinks.exists()) return inLinks.getAbsolutePath();
                String found = findFileIgnoreCaseCached(linksDir, filename);
                if (found != null) return found;
            }
        }

        // 3. 절대 경로 (같은 머신일 때 폴백)
        File absolute = new File(path);
        if (absolute.exists()) return absolute.getAbsolutePath();

        // 3. basePath 기준 (상대 경로 등)
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
                    String found = findFileIgnoreCaseCached(linksDir, filename);
                    if (found != null) return found;
                }
            }
        }

        return null;
    }

    private byte[] applyClipping(byte[] imageData, double[] imageTransform,
                                  double[] frameBounds, double[] graphicBounds,
                                  List<double[]> framePath, double cornerRadius,
                                  double[] cornerRadii) throws IOException {
        BufferedImage srcImage = ImageIO.read(new ByteArrayInputStream(imageData));
        if (srcImage == null) return imageData;

        double gLeft = 0, gTop = 0, gRight = srcImage.getWidth(), gBottom = srcImage.getHeight();
        if (graphicBounds != null && graphicBounds.length >= 4) {
            gLeft = graphicBounds[0];
            gTop = graphicBounds[1];
            gRight = graphicBounds[2];
            gBottom = graphicBounds[3];
        }

        double graphicW = gRight - gLeft;
        double graphicH = gBottom - gTop;
        double pxPerPtX = (graphicW > 0) ? srcImage.getWidth() / graphicW : 1.0;
        double pxPerPtY = (graphicH > 0) ? srcImage.getHeight() / graphicH : 1.0;

        // frameBounds: [top, left, bottom, right]
        double fLeft = frameBounds[1], fTop = frameBounds[0];
        double fRight = frameBounds[3], fBottom = frameBounds[2];
        double frameW = fRight - fLeft;
        double frameH = fBottom - fTop;

        // imageTransform: [a, b, c, d, tx, ty] (2D affine)
        boolean hasRotation = Math.abs(imageTransform[1]) > 0.001
                           || Math.abs(imageTransform[2]) > 0.001;

        byte[] clipped;
        if (hasRotation) {
            clipped = applyClippingRotated(srcImage, imageTransform,
                    fLeft, fTop, fRight, fBottom, frameW, frameH,
                    gLeft, gTop, pxPerPtX, pxPerPtY);
        } else {
            clipped = applyClippingSimple(srcImage, imageTransform,
                    fLeft, fTop, fRight, fBottom, frameW, frameH,
                    gLeft, gTop, pxPerPtX, pxPerPtY);
        }

        // 비사각형 프레임 경로가 있으면 알파 마스크 적용
        if (framePath != null && isNonRectangularPath(framePath)) {
            clipped = applyPathMask(clipped, framePath, fLeft, fTop, frameW, frameH);
        }
        // 둥근 모서리가 있으면 라운드 렉트 마스크 적용
        else if (cornerRadii != null && cornerRadii.length >= 4) {
            clipped = applyPerCornerMask(clipped, cornerRadii, frameW, frameH);
        } else if (cornerRadius > 0) {
            clipped = applyRoundedCornerMask(clipped, cornerRadius, frameW, frameH);
        }

        return clipped;
    }

    /**
     * PathPoint 목록이 비사각형(곡선 또는 5개 이상 점)인지 확인한다.
     */
    private boolean isNonRectangularPath(List<double[]> framePath) {
        if (framePath.size() > 4) return true;
        for (double[] pt : framePath) {
            double ax = pt[0], ay = pt[1];
            double lx = pt[2], ly = pt[3];
            double rx = pt[4], ry = pt[5];
            if (Math.abs(ax - lx) > 0.001 || Math.abs(ay - ly) > 0.001
                    || Math.abs(ax - rx) > 0.001 || Math.abs(ay - ry) > 0.001) {
                return true;
            }
        }
        return false;
    }

    /**
     * 비사각형 프레임 경로를 알파 마스크로 적용한다.
     * 경로 밖 영역을 투명하게 만든다.
     */
    private byte[] applyPathMask(byte[] imageData, List<double[]> framePath,
                                  double fLeft, double fTop,
                                  double frameW, double frameH) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
        if (img == null) return imageData;

        int pixW = img.getWidth();
        int pixH = img.getHeight();

        // 프레임 경로를 캔버스 픽셀 좌표로 변환하여 GeneralPath 생성
        java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath();
        double scaleX = pixW / frameW;
        double scaleY = pixH / frameH;

        for (int i = 0; i < framePath.size(); i++) {
            double[] pt = framePath.get(i);
            // pt: [anchorX, anchorY, leftDirX, leftDirY, rightDirX, rightDirY]
            double ax = (pt[0] - fLeft) * scaleX;
            double ay = (pt[1] - fTop) * scaleY;

            if (i == 0) {
                path.moveTo(ax, ay);
            } else {
                double[] prev = framePath.get(i - 1);
                double prevRx = (prev[4] - fLeft) * scaleX;
                double prevRy = (prev[5] - fTop) * scaleY;
                double curLx = (pt[2] - fLeft) * scaleX;
                double curLy = (pt[3] - fTop) * scaleY;

                // 이전 점의 rightDirection과 현재 점의 leftDirection이
                // 각각의 anchor와 같으면 직선, 다르면 베지어 곡선
                double prevAx = (prev[0] - fLeft) * scaleX;
                double prevAy = (prev[1] - fTop) * scaleY;
                boolean straightPrev = Math.abs(prevRx - prevAx) < 0.5
                        && Math.abs(prevRy - prevAy) < 0.5;
                boolean straightCur = Math.abs(curLx - ax) < 0.5
                        && Math.abs(curLy - ay) < 0.5;

                if (straightPrev && straightCur) {
                    path.lineTo(ax, ay);
                } else {
                    path.curveTo(prevRx, prevRy, curLx, curLy, ax, ay);
                }
            }
        }

        // 마지막 점 → 첫 점 닫기
        if (framePath.size() > 2) {
            double[] last = framePath.get(framePath.size() - 1);
            double[] first = framePath.get(0);
            double lastRx = (last[4] - fLeft) * scaleX;
            double lastRy = (last[5] - fTop) * scaleY;
            double firstLx = (first[2] - fLeft) * scaleX;
            double firstLy = (first[3] - fTop) * scaleY;
            double firstAx = (first[0] - fLeft) * scaleX;
            double firstAy = (first[1] - fTop) * scaleY;
            double lastAx = (last[0] - fLeft) * scaleX;
            double lastAy = (last[1] - fTop) * scaleY;

            boolean straightLast = Math.abs(lastRx - lastAx) < 0.5
                    && Math.abs(lastRy - lastAy) < 0.5;
            boolean straightFirst = Math.abs(firstLx - firstAx) < 0.5
                    && Math.abs(firstLy - firstAy) < 0.5;

            if (straightLast && straightFirst) {
                path.lineTo(firstAx, firstAy);
            } else {
                path.curveTo(lastRx, lastRy, firstLx, firstLy, firstAx, firstAy);
            }
        }
        path.closePath();

        // 새 이미지에 path 클리핑 후 원본 그리기
        BufferedImage masked = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = masked.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(path);
        g.drawImage(img, 0, 0, null);
        g.dispose();

        return encodePng(masked);
    }

    /**
     * 둥근 모서리 마스크를 적용한다.
     * 프레임의 cornerRadius를 RoundRectangle2D로 클리핑.
     */
    private byte[] applyRoundedCornerMask(byte[] imageData, double cornerRadius,
                                           double frameW, double frameH) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
        if (img == null) return imageData;

        int pixW = img.getWidth();
        int pixH = img.getHeight();

        // InDesign 클램핑: radius <= min(width,height)/2
        double maxR = Math.min(frameW, frameH) / 2.0;
        double r = Math.min(cornerRadius, maxR);

        double scaleX = pixW / frameW;
        double scaleY = pixH / frameH;
        double rPx = r * Math.min(scaleX, scaleY);

        java.awt.geom.RoundRectangle2D.Double clip =
                new java.awt.geom.RoundRectangle2D.Double(
                        0, 0, pixW, pixH, rPx * 2, rPx * 2);

        BufferedImage masked = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = masked.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(clip);
        g.drawImage(img, 0, 0, null);
        g.dispose();

        return encodePng(masked);
    }

    /**
     * Per-corner 둥근 모서리 마스크를 적용한다.
     * 각 모서리별로 다른 반경을 Path2D로 그려서 클리핑.
     * @param cornerRadii [topLeft, topRight, bottomLeft, bottomRight] (points)
     */
    private byte[] applyPerCornerMask(byte[] imageData, double[] cornerRadii,
                                       double frameW, double frameH) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageData));
        if (img == null) return imageData;

        int pixW = img.getWidth();
        int pixH = img.getHeight();

        double scaleX = pixW / frameW;
        double scaleY = pixH / frameH;
        double scale = Math.min(scaleX, scaleY);

        // InDesign 클램핑: radius <= min(width,height)/2
        double maxR = Math.min(frameW, frameH) / 2.0;
        double rTL = Math.min(cornerRadii[0], maxR) * scale;
        double rTR = Math.min(cornerRadii[1], maxR) * scale;
        double rBL = Math.min(cornerRadii[2], maxR) * scale;
        double rBR = Math.min(cornerRadii[3], maxR) * scale;

        // Path2D로 per-corner rounded rect 생성
        java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();

        // 왼쪽 위 모서리에서 시작 (TL arc 끝)
        path.moveTo(rTL, 0);

        // 상단 → 오른쪽 위 모서리
        path.lineTo(pixW - rTR, 0);
        if (rTR > 0) {
            path.quadTo(pixW, 0, pixW, rTR);
        }

        // 오른쪽 → 오른쪽 아래 모서리
        path.lineTo(pixW, pixH - rBR);
        if (rBR > 0) {
            path.quadTo(pixW, pixH, pixW - rBR, pixH);
        }

        // 하단 → 왼쪽 아래 모서리
        path.lineTo(rBL, pixH);
        if (rBL > 0) {
            path.quadTo(0, pixH, 0, pixH - rBL);
        }

        // 왼쪽 → 왼쪽 위 모서리
        path.lineTo(0, rTL);
        if (rTL > 0) {
            path.quadTo(0, 0, rTL, 0);
        }

        path.closePath();

        BufferedImage masked = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = masked.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(path);
        g.drawImage(img, 0, 0, null);
        g.dispose();

        return encodePng(masked);
    }

    /**
     * 비회전 이미지 클리핑 (기존 로직).
     * imageTransform이 scale+translate만 포함하는 경우.
     */
    private byte[] applyClippingSimple(BufferedImage srcImage, double[] imageTransform,
                                        double fLeft, double fTop, double fRight, double fBottom,
                                        double frameW, double frameH,
                                        double gLeft, double gTop,
                                        double pxPerPtX, double pxPerPtY) throws IOException {
        double imgScaleX = imageTransform[0];
        double imgScaleY = imageTransform[3];
        double imgTx = imageTransform[4];
        double imgTy = imageTransform[5];

        // 프레임 좌표를 그래픽 좌표로 역변환
        double gL2 = (fLeft - imgTx) / imgScaleX + gLeft;
        double gT2 = (fTop - imgTy) / imgScaleY + gTop;
        double gR2 = (fRight - imgTx) / imgScaleX + gLeft;
        double gB2 = (fBottom - imgTy) / imgScaleY + gTop;

        // 그래픽 좌표를 픽셀 좌표로 변환 (클램프 전 원본 좌표 보존)
        double rawSrcL = (Math.min(gL2, gR2) - gLeft) * pxPerPtX;
        double rawSrcT = (Math.min(gT2, gB2) - gTop) * pxPerPtY;
        double rawSrcR = (Math.max(gL2, gR2) - gLeft) * pxPerPtX;
        double rawSrcB = (Math.max(gT2, gB2) - gTop) * pxPerPtY;

        // 소스 영역 클램프
        int sx1 = Math.max(0, (int) Math.floor(rawSrcL));
        int sy1 = Math.max(0, (int) Math.floor(rawSrcT));
        int sx2 = Math.min(srcImage.getWidth(), (int) Math.ceil(rawSrcR));
        int sy2 = Math.min(srcImage.getHeight(), (int) Math.ceil(rawSrcB));

        if (sx1 >= sx2 || sy1 >= sy2) return safeEncodePng(srcImage);

        int targetDpi = options.imageDpi();
        int pixW = Math.max(10, (int) Math.ceil(frameW * targetDpi / 72.0));
        int pixH = Math.max(10, (int) Math.ceil(frameH * targetDpi / 72.0));

        // 클램프에 의해 잘린 영역을 반영한 목적지 좌표 계산
        // 소스가 이미지 범위 밖으로 나가면 해당 영역은 투명하게 유지
        double rawW = rawSrcR - rawSrcL;
        double rawH = rawSrcB - rawSrcT;
        int dx1 = (rawW > 0) ? (int) Math.round((sx1 - rawSrcL) / rawW * pixW) : 0;
        int dy1 = (rawH > 0) ? (int) Math.round((sy1 - rawSrcT) / rawH * pixH) : 0;
        int dx2 = (rawW > 0) ? (int) Math.round((sx2 - rawSrcL) / rawW * pixW) : pixW;
        int dy2 = (rawH > 0) ? (int) Math.round((sy2 - rawSrcT) / rawH * pixH) : pixH;

        BufferedImage clipped = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clipped.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Flip 처리: 음수 스케일 = 목적지 좌표를 반전하여 출력을 뒤집는다.
        // 소스 픽셀은 역변환으로 이미 올바른 영역이 선택되었으므로 변경하지 않는다.
        // (소스를 변경하면 다른 픽셀이 읽히는 이중 flip 버그 발생)
        int fdx1 = dx1, fdx2 = dx2, fdy1 = dy1, fdy2 = dy2;
        if (imgScaleX < 0) { fdx1 = dx2; fdx2 = dx1; }
        if (imgScaleY < 0) { fdy1 = dy2; fdy2 = dy1; }

        g.drawImage(srcImage, fdx1, fdy1, fdx2, fdy2, sx1, sy1, sx2, sy2, null);
        g.dispose();

        return encodePng(clipped);
    }

    /**
     * 회전된 이미지 클리핑.
     * imageTransform에 회전 성분(b, c ≠ 0)이 있는 경우 AffineTransform을 사용하여
     * 프레임 사각형 영역만 정확히 클리핑한다.
     *
     * 이전 방식(바운딩 박스)은 회전된 프레임 코너의 AABB를 추출하여 프레임 밖의
     * 이미지 영역까지 포함시키는 문제가 있었다. AffineTransform 방식은 프레임 클립을
     * 정확히 적용하여 원본과 동일한 영역만 렌더링한다.
     */
    private byte[] applyClippingRotated(BufferedImage srcImage, double[] imageTransform,
                                         double fLeft, double fTop, double fRight, double fBottom,
                                         double frameW, double frameH,
                                         double gLeft, double gTop,
                                         double pxPerPtX, double pxPerPtY) throws IOException {
        double a = imageTransform[0], b = imageTransform[1];
        double c = imageTransform[2], d = imageTransform[3];
        double tx = imageTransform[4], ty = imageTransform[5];

        int targetDpi = options.imageDpi();
        int pixW = Math.max(10, (int) Math.ceil(frameW * targetDpi / 72.0));
        int pixH = Math.max(10, (int) Math.ceil(frameH * targetDpi / 72.0));

        // Transform chain: source pixels → graphic pts → frame local → canvas pixels
        // Step 1: srcPx → graphicPt
        //   gx = srcPx / pxPerPtX + gLeft
        AffineTransform step1 = new AffineTransform();
        step1.translate(gLeft, gTop);
        step1.scale(1.0 / pxPerPtX, 1.0 / pxPerPtY);

        // Step 2: graphicPt → framePt (imageTransform)
        AffineTransform step2 = new AffineTransform(a, b, c, d, tx, ty);

        // Step 3: framePt → canvasPx
        //   cpx = (fx - fLeft) * pixW / frameW
        AffineTransform step3 = new AffineTransform();
        step3.scale((double) pixW / frameW, (double) pixH / frameH);
        step3.translate(-fLeft, -fTop);

        // Combined: step3 ∘ step2 ∘ step1
        AffineTransform combined = new AffineTransform();
        combined.concatenate(step3);
        combined.concatenate(step2);
        combined.concatenate(step1);

        BufferedImage clipped = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clipped.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // 캔버스 크기 = 프레임 크기이므로 자연스럽게 프레임 영역만 클리핑
        g.drawImage(srcImage, combined, null);
        g.dispose();

        return encodePng(clipped);
    }

    // ===== 벡터 도형 래스터화 =====

    /**
     * IDMLVectorShape를 PNG 이미지로 래스터화.
     * @param shape 벡터 도형
     * @param fillColorHex 채우기 색상 HEX (예: "#FF0000") 또는 null
     * @param strokeColorHex 선 색상 HEX 또는 null
     */
    public ImageResult rasterizeShape(IDMLVectorShape shape, String fillColorHex, String strokeColorHex) {
        double[] canvasBounds = shape.geometricBounds();
        double wPts = canvasBounds[3] - canvasBounds[1];
        double hPts = canvasBounds[2] - canvasBounds[0];

        // 선 도형(GraphicLine 등)은 한 축이 0일 수 있음: stroke weight를 최소 크기로 사용
        if ((wPts <= 0 || hPts <= 0) && shape.hasStroke() && shape.strokeWeight() > 0) {
            double minDim = shape.strokeWeight();
            if (wPts <= 0) wPts = minDim;
            if (hPts <= 0) hPts = minDim;
        }
        if (wPts <= 0 || hPts <= 0) return null;

        int targetDpi = options.imageDpi();
        double scale = targetDpi / 72.0;

        // 스트로크 패딩 (절반 선 두께 + 여유)
        double strokePad = shape.hasStroke() ? shape.strokeWeight() * scale / 2.0 + 1 : 0;

        int pixW = Math.max(10, (int) Math.ceil(wPts * scale + strokePad * 2));
        int pixH = Math.max(10, (int) Math.ceil(hPts * scale + strokePad * 2));

        BufferedImage image = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 스트로크 패딩만큼 오프셋
        g.translate(strokePad, strokePad);

        float alpha = (float) (shape.opacity() / 100.0);

        // 클리핑 자식이 있으면 자식을 렌더링
        IDMLVectorShape renderTarget = shape.hasClippedChild() ? shape.clippedChild() : shape;
        String actualFillHex = shape.hasClippedChild() ? null : fillColorHex;
        String actualStrokeHex = shape.hasClippedChild() ? null : strokeColorHex;

        Shape awtShape;
        if (shape.hasClippedChild()) {
            IDMLVectorShape child = shape.clippedChild();
            double renderLeft = canvasBounds[1];
            double renderTop = canvasBounds[0];

            // 클리핑 프레임의 Raw Path → 렌더링 좌표계
            AffineTransform clipAt = new AffineTransform();
            clipAt.scale(scale, scale);
            clipAt.translate(-renderLeft, -renderTop);
            GeneralPath clipRawPath = buildRawPath(shape);
            Shape clipShape;
            if (clipRawPath.getBounds2D().getWidth() > 0) {
                clipShape = clipAt.createTransformedShape(clipRawPath);
            } else {
                clipShape = new Rectangle2D.Double(0, 0, wPts * scale, hPts * scale);
            }

            // 외부 프레임에 fill이 있으면 클리핑 영역 내에 배경색 먼저 채우기
            if (shape.hasFill()) {
                Color frameFillColor = resolveFrameFillColor(shape.fillColor());
                if (frameFillColor != null) {
                    g.setClip(clipShape);
                    Color tintedFrame = applyTint(frameFillColor, shape.fillTint());
                    g.setColor(withAlpha(tintedFrame, alpha));
                    g.fill(clipShape);
                }
            }

            g.setClip(clipShape);

            // 자식 도형: 자식 ItemTransform 적용 → 렌더링 좌표계
            double[] ct = child.itemTransform();
            AffineTransform childAt = new AffineTransform();
            childAt.scale(scale, scale);
            childAt.translate(-renderLeft, -renderTop);
            if (ct != null) {
                childAt.concatenate(new AffineTransform(ct[0], ct[1], ct[2], ct[3], ct[4], ct[5]));
            }
            GeneralPath childRawPath = buildRawPath(child);
            if (childRawPath.getBounds2D().getWidth() > 0 || childRawPath.getBounds2D().getHeight() > 0) {
                awtShape = childAt.createTransformedShape(childRawPath);
            } else {
                Rectangle2D.Double childRect = new Rectangle2D.Double(
                        child.geometricBounds()[1], child.geometricBounds()[0],
                        child.widthPoints(), child.heightPoints());
                awtShape = childAt.createTransformedShape(childRect);
            }

            actualFillHex = fillColorHex;
            actualStrokeHex = strokeColorHex;
            renderTarget = child;
        } else {
            awtShape = createAwtShape(renderTarget, scale);
        }


        // 채우기 (tint는 색상 농도로 RGB에 적용, opacity만 alpha에 적용)
        if (actualFillHex != null && renderTarget.hasFill()) {
            Color fillColor = hexToColor(actualFillHex);
            if (fillColor != null) {
                Color tintedFill = applyTint(fillColor, renderTarget.fillTint());
                g.setColor(withAlpha(tintedFill, alpha));
                g.fill(awtShape);
            }
        }

        // 선
        if (actualStrokeHex != null && renderTarget.hasStroke()) {
            Color strokeColor = hexToColor(actualStrokeHex);
            if (strokeColor != null) {
                Color tintedStroke = applyTint(strokeColor, renderTarget.strokeTint());
                g.setColor(withAlpha(tintedStroke, alpha));

                int cap = BasicStroke.CAP_BUTT;
                int join = BasicStroke.JOIN_MITER;
                if (renderTarget.endCap() == IDMLVectorShape.LineCap.ROUND) cap = BasicStroke.CAP_ROUND;
                if (renderTarget.endCap() == IDMLVectorShape.LineCap.PROJECTING) cap = BasicStroke.CAP_SQUARE;
                if (renderTarget.lineJoin() == IDMLVectorShape.LineJoin.ROUND) join = BasicStroke.JOIN_ROUND;
                if (renderTarget.lineJoin() == IDMLVectorShape.LineJoin.BEVEL) join = BasicStroke.JOIN_BEVEL;

                float strokeW = (float) (renderTarget.strokeWeight() * scale);
                BasicStroke stroke;
                if (renderTarget.hasDashPattern()) {
                    float[] dashArr = new float[renderTarget.dashPattern().length];
                    for (int i = 0; i < dashArr.length; i++)
                        dashArr[i] = (float) (renderTarget.dashPattern()[i] * scale);
                    stroke = new BasicStroke(strokeW, cap, join, (float) renderTarget.miterLimit(), dashArr, 0);
                } else {
                    stroke = new BasicStroke(strokeW, cap, join, (float) renderTarget.miterLimit());
                }
                g.setStroke(stroke);
                g.draw(awtShape);

                // 선 끝 장식 (CircleArrowHead 등) — 열린 경로의 시작/끝점에 원 그리기
                drawLineEndDecorations(g, renderTarget, awtShape, strokeColor, tintedStroke, alpha, strokeW, scale);
            }
        }

        g.dispose();

        // GradientFeather 알파 마스크 적용
        if (shape.hasGradientFeather()) {
            applyGradientFeatherAlpha(image, shape, canvasBounds, scale, strokePad);
        }

        try {
            byte[] pngData = encodePng(image);
            ImageResult result = new ImageResult();
            result.imageData = pngData;
            result.format = "png";
            result.pixelWidth = pixW;
            result.pixelHeight = pixH;
            result.widthPts = wPts;
            result.heightPts = hPts;
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 이미지 바이너리에 GradientFeather 알파 마스크를 적용하여 PNG로 반환한다.
     * GradientStart는 이미지 로컬 좌표계(GraphicBounds 중심 기준)이므로
     * imageTransform을 통해 프레임 좌표로 변환한다.
     *
     * @param imageData      원본 이미지 바이너리 (이미 프레임에 클리핑됨)
     * @param angle          그라디언트 각도 (degrees, InDesign: 0°=오른쪽, 반시계)
     * @param length         그라디언트 길이 (points, 이미지 좌표계)
     * @param start          그라디언트 시작점 [x, y] (이미지 중심 기준 오프셋, null이면 중심)
     * @param frameBounds    프레임 bounds [top, left, bottom, right] (로컬 좌표, points)
     * @param imageTransform 이미지→프레임 변환 [a, b, c, d, tx, ty] (null 가능)
     * @param graphicBounds  원본 이미지 크기 [left, top, right, bottom] (null 가능)
     * @return 알파 마스크 적용된 PNG, 실패 시 null
     */
    public static byte[] applyGradientFeatherToImage(byte[] imageData, double angle,
                                                      double length, double[] start,
                                                      double[] frameBounds,
                                                      double[] imageTransform,
                                                      double[] graphicBounds) {
        if (imageData == null || length <= 0) return null;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageData));
            if (src == null) return null;

            // ARGB로 변환
            BufferedImage image = new BufferedImage(src.getWidth(), src.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();

            double frameW = frameBounds[3] - frameBounds[1];
            double frameH = frameBounds[2] - frameBounds[0];
            double frameLeft = frameBounds[1];
            double frameTop = frameBounds[0];

            // GradientStart를 이미지 좌표 → 프레임 좌표로 변환
            // GradientStart는 이미지 GraphicBounds 내 절대 좌표
            double startImgX = start != null ? start[0] : 0;
            double startImgY = start != null ? start[1] : 0;

            // imageTransform으로 프레임 로컬 좌표로 변환
            double frameStartX, frameStartY;
            if (imageTransform != null) {
                double sx = imageTransform[0], sy = imageTransform[3];
                double tx = imageTransform[4], ty = imageTransform[5];
                frameStartX = sx * startImgX + tx;
                frameStartY = sy * startImgY + ty;
            } else {
                frameStartX = startImgX;
                frameStartY = startImgY;
            }

            // 프레임 원점(top-left) 기준으로 조정
            frameStartX -= frameLeft;
            frameStartY -= frameTop;

            // 그라디언트 길이도 이미지 스케일 적용
            double scaledLength = length;
            if (imageTransform != null) {
                double sx = Math.abs(imageTransform[0]);
                double sy = Math.abs(imageTransform[3]);
                double rad0 = Math.toRadians(angle);
                double dx0 = Math.cos(rad0);
                double dy0 = Math.sin(rad0);
                double effectiveScale = Math.sqrt(dx0 * dx0 * sx * sx + dy0 * dy0 * sy * sy);
                scaledLength = length * effectiveScale;
            }

            // 각도 → 방향 벡터 (InDesign: 0°=오른쪽, 반시계)
            double rad = Math.toRadians(angle);
            double dirX = Math.cos(rad);
            double dirY = -Math.sin(rad);  // Y축 반전 (화면 좌표계)

            int pixW = image.getWidth();
            int pixH = image.getHeight();
            double pxPerPtX = frameW > 0 ? pixW / frameW : 1;
            double pxPerPtY = frameH > 0 ? pixH / frameH : 1;

            for (int py = 0; py < pixH; py++) {
                for (int px = 0; px < pixW; px++) {
                    // 픽셀 → 프레임 좌표 (points, 프레임 원점 기준)
                    double ptX = px / pxPerPtX;
                    double ptY = py / pxPerPtY;

                    // 시작점으로부터 그라디언트 방향 투영
                    double dx = ptX - frameStartX;
                    double dy = ptY - frameStartY;
                    double proj = dx * dirX + dy * dirY;

                    double t = proj / scaledLength;
                    t = Math.max(0, Math.min(1, t));

                    double alphaFactor = 1.0 - t;

                    int argb = image.getRGB(px, py);
                    int a = (argb >> 24) & 0xFF;
                    int newA = (int) Math.round(a * alphaFactor);
                    image.setRGB(px, py, (newA << 24) | (argb & 0x00FFFFFF));
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            System.err.println("[GradientFeather] 이미지 알파 마스크 적용 실패: " + e.getMessage());
            return null;
        }
    }

    /**
     * GradientFeather 알파 마스크를 이미지에 적용한다.
     * InDesign의 GradientFeather 효과는 도형의 투명도를 선형 그라디언트로 페이드한다.
     */
    private void applyGradientFeatherAlpha(BufferedImage image, IDMLVectorShape shape,
                                            double[] canvasBounds, double scale, double strokePad) {
        double angle = shape.gradientFeatherAngle();
        double length = shape.gradientFeatherLength();
        double[] start = shape.gradientFeatherStart();

        if (length <= 0) return;

        double canvasW = canvasBounds[3] - canvasBounds[1];
        double canvasH = canvasBounds[2] - canvasBounds[0];

        // 그라디언트 시작점 (도형 중심 기준 → 캔버스 좌표)
        double startX, startY;
        if (start != null) {
            startX = start[0] + canvasW / 2;
            startY = start[1] + canvasH / 2;
        } else {
            startX = canvasW / 2;
            startY = canvasH / 2;
        }

        // 각도 → 방향 벡터 (InDesign: 0°=오른쪽, 반시계)
        double rad = Math.toRadians(angle);
        double dirX = Math.cos(rad);
        double dirY = -Math.sin(rad);  // Y축 반전 (화면 좌표계)

        int pixW = image.getWidth();
        int pixH = image.getHeight();

        for (int py = 0; py < pixH; py++) {
            for (int px = 0; px < pixW; px++) {
                // 픽셀 → 포인트 좌표
                double ptX = (px - strokePad) / scale;
                double ptY = (py - strokePad) / scale;

                // 시작점으로부터 그라디언트 방향 투영
                double dx = ptX - startX;
                double dy = ptY - startY;
                double proj = dx * dirX + dy * dirY;

                // 0 → length 범위에서 t 계산 (0=불투명, 1=투명)
                double t = proj / length;
                t = Math.max(0, Math.min(1, t));

                // 알파 = 1 - t (100% → 0%)
                double alphaFactor = 1.0 - t;

                int argb = image.getRGB(px, py);
                int a = (argb >> 24) & 0xFF;
                int newA = (int) Math.round(a * alphaFactor);
                image.setRGB(px, py, (newA << 24) | (argb & 0x00FFFFFF));
            }
        }
    }

    /**
     * 여러 벡터 도형을 하나의 캔버스에 합성 래스터화한다.
     * 인라인 그룹 내부의 글리프 아웃라인 등, 여러 Polygon으로 구성된 벡터 그래픽용.
     *
     * @param shapes         도형 + 색상 목록
     * @param groupTransform 부모 Group의 ItemTransform (null 가능)
     * @return 래스터화 결과 (widthPts, heightPts 포함)
     */
    public ImageResult rasterizeShapes(List<ShapeWithColor> shapes, double[] groupTransform) {
        return rasterizeShapes(shapes, groupTransform, null);
    }

    /**
     * 여러 벡터 도형을 하나의 캔버스에 합성 래스터화한다.
     * pageClipBounds가 지정되면 페이지 경계로 클리핑하여 도형이 페이지 밖으로 넘치지 않도록 한다.
     *
     * @param shapes         도형 + 색상 목록
     * @param groupTransform 부모 Group의 ItemTransform (null 가능)
     * @param pageClipBounds 페이지 절대좌표 [top, left, bottom, right] (null이면 클리핑 없음)
     * @return 래스터화 결과 (widthPts, heightPts 포함)
     */
    public ImageResult rasterizeShapes(List<ShapeWithColor> shapes, double[] groupTransform,
                                       double[] pageClipBounds) {
        if (shapes == null || shapes.isEmpty()) return null;

        // 1. 모든 도형의 합산 bounding box 계산 (변환 적용된 좌표)
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (ShapeWithColor sc : shapes) {
            double[] b = sc.transformedBounds();
            if (b == null || b.length < 4) continue;
            if (b[1] < minX) minX = b[1];
            if (b[0] < minY) minY = b[0];
            if (b[3] > maxX) maxX = b[3];
            if (b[2] > maxY) maxY = b[2];
        }
        // 수평/수직 GraphicLine 등 한 축이 0인 경우 stroke width만큼 확장
        if (minY >= maxY || minX >= maxX) {
            double maxStroke = 0;
            for (ShapeWithColor sc : shapes) {
                if (sc.shape.strokeWeight() > 0 && sc.strokeHex != null) {
                    maxStroke = Math.max(maxStroke, sc.shape.strokeWeight());
                }
            }
            if (maxStroke > 0) {
                // 점선 패턴이 화면에서 보이려면 최소 3pt 높이 필요 (96DPI에서 4px)
                boolean hasDash = false;
                for (ShapeWithColor sc : shapes) {
                    if (sc.shape.hasDashPattern()) { hasDash = true; break; }
                }
                double minPad = hasDash ? 1.5 : (maxStroke / 2.0 + 0.5);
                double pad = Math.max(maxStroke / 2.0 + 0.5, minPad);
                if (minY >= maxY) { minY -= pad; maxY += pad; }
                if (minX >= maxX) { minX -= pad; maxX += pad; }
            }
        }
        if (minX >= maxX || minY >= maxY) return null;

        // 페이지 클리핑: 합산 bounding box를 페이지 경계로 제한
        if (pageClipBounds != null) {
            double pageTop = pageClipBounds[0], pageLeft = pageClipBounds[1];
            double pageBottom = pageClipBounds[2], pageRight = pageClipBounds[3];
            minX = Math.max(minX, pageLeft);
            minY = Math.max(minY, pageTop);
            maxX = Math.min(maxX, pageRight);
            maxY = Math.min(maxY, pageBottom);
            if (minX >= maxX || minY >= maxY) return null;
        }

        double wPts = maxX - minX;
        double hPts = maxY - minY;

        int targetDpi = options.imageDpi();
        double scale = targetDpi / 72.0;
        double strokePad = 1;

        int pixW = Math.max(10, (int) Math.ceil(wPts * scale + strokePad * 2));
        int pixH = Math.max(10, (int) Math.ceil(hPts * scale + strokePad * 2));

        BufferedImage image = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.translate(strokePad, strokePad);

        // 페이지 클리핑 영역 설정: 도형이 페이지 밖으로 넘치는 스트로크를 잘라냄
        if (pageClipBounds != null) {
            double clipX = (pageClipBounds[1] - minX) * scale;
            double clipY = (pageClipBounds[0] - minY) * scale;
            double clipW = (pageClipBounds[3] - pageClipBounds[1]) * scale;
            double clipH = (pageClipBounds[2] - pageClipBounds[0]) * scale;
            g.setClip((int) Math.floor(clipX), (int) Math.floor(clipY),
                    (int) Math.ceil(clipW) + 1, (int) Math.ceil(clipH) + 1);
        }

        // 2. 각 도형을 공통 좌표 기준으로 렌더링
        for (ShapeWithColor sc : shapes) {
            double[] tb = sc.transformedBounds();
            if (tb == null || tb.length < 4) continue;

            double offX = tb[1] - minX;
            double offY = tb[0] - minY;
            double sw = tb[3] - tb[1];
            double sh = tb[2] - tb[0];
            // 변환 적용된 도형은 PathPoints에서 직접 경로를 빌드하므로 zero-dimension 허용
            // (예: 수평/수직 GraphicLine은 한 축이 0)
            if ((sw <= 0 || sh <= 0) && sc.accTransform == null) continue;

            Shape awtShape;
            // 둥근 사각형은 PathPoints에 코너 정보가 없으므로 RoundRectangle2D 사용
            if ((sc.shape.shapeType() == IDMLVectorShape.ShapeType.RECTANGLE
                    || sc.shape.shapeType() == IDMLVectorShape.ShapeType.POLYGON)
                    && sc.shape.hasRoundedCorners()
                    && !hasNonRectangularPath(sc.shape)) {
                // InDesign은 cornerRadius를 min(width,height)/2로 클램핑 (원형 모서리)
                double maxR = Math.min(sw, sh) / 2.0;
                double r = Math.min(effectiveCornerRadius(sc.shape), maxR) * scale;
                awtShape = new RoundRectangle2D.Double(
                        offX * scale, offY * scale, sw * scale, sh * scale, r * 2, r * 2);
            } else if (sc.accTransform != null) {
                awtShape = buildTransformedPath(sc.shape, sc.accTransform, scale, minX, minY);
            } else {
                awtShape = buildPathFromPoints(sc.shape, scale, offX, offY);
            }
            if (awtShape == null) continue;

            // 도형별 불투명도
            float alpha = (float) (sc.shape.opacity() / 100.0);

            // 채우기 (tint는 색상 농도로 RGB에 적용, opacity만 alpha에 적용)
            if (sc.fillHex != null) {
                Color fillColor = hexToColor(sc.fillHex);
                if (fillColor != null) {
                    Color tintedFill = applyTint(fillColor, sc.shape.fillTint());
                    g.setColor(withAlpha(tintedFill, alpha));
                    g.fill(awtShape);
                }
            }

            // 선 (strokeHex가 Group 폴백으로 설정된 경우 shape 자체에 색상이 없을 수 있으므로
            // hasStroke() 대신 strokeWeight > 0만 확인)
            if (sc.strokeHex != null && sc.shape.strokeWeight() > 0) {
                Color strokeColor = hexToColor(sc.strokeHex);
                if (strokeColor != null) {
                    Color tintedStroke = applyTint(strokeColor, sc.shape.strokeTint());
                    g.setColor(withAlpha(tintedStroke, alpha));
                    float strokeW = (float) (sc.shape.strokeWeight() * scale);
                    // 점선 패턴이 있는 얇은 선은 최소 1pt로 렌더링 (화면 96DPI에서 1px 보장)
                    if (sc.shape.hasDashPattern() && sc.shape.strokeWeight() < 1.0) {
                        strokeW = (float) (1.0 * scale);
                    }

                    int cap = java.awt.BasicStroke.CAP_BUTT;
                    int join = java.awt.BasicStroke.JOIN_MITER;
                    if (sc.shape.endCap() == IDMLVectorShape.LineCap.ROUND) cap = java.awt.BasicStroke.CAP_ROUND;
                    else if (sc.shape.endCap() == IDMLVectorShape.LineCap.PROJECTING) cap = java.awt.BasicStroke.CAP_SQUARE;
                    if (sc.shape.lineJoin() == IDMLVectorShape.LineJoin.ROUND) join = java.awt.BasicStroke.JOIN_ROUND;
                    else if (sc.shape.lineJoin() == IDMLVectorShape.LineJoin.BEVEL) join = java.awt.BasicStroke.JOIN_BEVEL;

                    BasicStroke stroke;
                    if (sc.shape.hasDashPattern()) {
                        float[] dashArr = new float[sc.shape.dashPattern().length];
                        for (int di = 0; di < dashArr.length; di++)
                            dashArr[di] = (float) (sc.shape.dashPattern()[di] * scale);
                        stroke = new BasicStroke(strokeW, cap, join,
                                (float) sc.shape.miterLimit(), dashArr, 0);
                    } else {
                        stroke = new BasicStroke(strokeW, cap, join,
                                (float) sc.shape.miterLimit());
                    }
                    g.setStroke(stroke);
                    g.draw(awtShape);

                    // 선 끝 장식 (CircleArrowHead 등)
                    drawLineEndDecorations(g, sc.shape, awtShape, strokeColor, tintedStroke, 1.0f, strokeW, scale);
                }
            }
        }

        g.dispose();

        try {
            byte[] pngData = encodePng(image);
            ImageResult result = new ImageResult();
            result.imageData = pngData;
            result.format = "png";
            result.pixelWidth = pixW;
            result.pixelHeight = pixH;
            result.widthPts = wPts;
            result.heightPts = hPts;
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 벡터 도형의 PathPoints를 AWT Shape으로 변환 (합성 렌더링용).
     * 지정된 오프셋을 적용하여 공통 캔버스 좌표계로 변환한다.
     */
    private Shape buildPathFromPoints(IDMLVectorShape shape, double scale,
                                       double offX, double offY) {
        double[] bounds = shape.geometricBounds();
        double baseOffX = (bounds != null) ? -bounds[1] + offX : offX;
        double baseOffY = (bounds != null) ? -bounds[0] + offY : offY;

        int windingRule = shape.hasSubPaths()
                ? GeneralPath.WIND_EVEN_ODD : GeneralPath.WIND_NON_ZERO;
        GeneralPath path = new GeneralPath(windingRule);

        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath sub : shape.subPaths()) {
                appendSubPath(path, sub.points(), sub.isOpen(), scale, baseOffX, baseOffY);
            }
        } else if (!shape.pathPoints().isEmpty()) {
            appendSubPath(path, shape.pathPoints(), shape.pathOpen(), scale, baseOffX, baseOffY);
        }

        return path;
    }

    /**
     * 누적 변환을 적용하여 path points를 그룹 좌표계로 변환한 AWT Shape을 생성한다.
     * 중첩 그룹 내부의 도형을 공통 캔버스에 정확히 배치하기 위해 사용.
     *
     * @param shape        벡터 도형
     * @param accTransform 누적 아핀 변환 [a, b, c, d, tx, ty]
     * @param scale        DPI 스케일
     * @param canvasMinX   캔버스 원점 X (그룹 좌표계)
     * @param canvasMinY   캔버스 원점 Y (그룹 좌표계)
     */
    private Shape buildTransformedPath(IDMLVectorShape shape, double[] accTransform,
                                        double scale, double canvasMinX, double canvasMinY) {
        double ta = accTransform[0], tb = accTransform[1];
        double tc = accTransform[2], td = accTransform[3];
        double ttx = accTransform[4], tty = accTransform[5];

        int windingRule = shape.hasSubPaths()
                ? GeneralPath.WIND_EVEN_ODD : GeneralPath.WIND_NON_ZERO;
        GeneralPath path = new GeneralPath(windingRule);

        java.util.List<java.util.List<IDMLVectorShape.PathPoint>> allPointSets = new java.util.ArrayList<>();
        java.util.List<Boolean> allOpenFlags = new java.util.ArrayList<>();

        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath sub : shape.subPaths()) {
                allPointSets.add(sub.points());
                allOpenFlags.add(sub.isOpen());
            }
        } else if (!shape.pathPoints().isEmpty()) {
            allPointSets.add(shape.pathPoints());
            allOpenFlags.add(shape.pathOpen());
        }

        for (int s = 0; s < allPointSets.size(); s++) {
            java.util.List<IDMLVectorShape.PathPoint> points = allPointSets.get(s);
            boolean isOpen = allOpenFlags.get(s);
            if (points.isEmpty()) continue;

            for (int i = 0; i < points.size(); i++) {
                IDMLVectorShape.PathPoint pt = points.get(i);
                // 로컬 좌표에 누적 변환 적용 → 그룹 좌표 → 캔버스 좌표
                double ax = (ta * pt.anchorX() + tc * pt.anchorY() + ttx - canvasMinX) * scale;
                double ay = (tb * pt.anchorX() + td * pt.anchorY() + tty - canvasMinY) * scale;

                if (i == 0) {
                    path.moveTo((float) ax, (float) ay);
                } else {
                    IDMLVectorShape.PathPoint prev = points.get(i - 1);
                    double prx = (ta * prev.rightX() + tc * prev.rightY() + ttx - canvasMinX) * scale;
                    double pry = (tb * prev.rightX() + td * prev.rightY() + tty - canvasMinY) * scale;
                    double lx = (ta * pt.leftX() + tc * pt.leftY() + ttx - canvasMinX) * scale;
                    double ly = (tb * pt.leftX() + td * pt.leftY() + tty - canvasMinY) * scale;

                    boolean isBezier = (Math.abs(prx - ((ta * prev.anchorX() + tc * prev.anchorY() + ttx - canvasMinX) * scale)) > 0.01)
                            || (Math.abs(lx - ax) > 0.01)
                            || (Math.abs(pry - ((tb * prev.anchorX() + td * prev.anchorY() + tty - canvasMinY) * scale)) > 0.01)
                            || (Math.abs(ly - ay) > 0.01);

                    if (isBezier) {
                        path.curveTo((float) prx, (float) pry, (float) lx, (float) ly, (float) ax, (float) ay);
                    } else {
                        path.lineTo((float) ax, (float) ay);
                    }
                }
            }

            // 닫힌 경로 처리
            if (!isOpen && points.size() > 1) {
                IDMLVectorShape.PathPoint last = points.get(points.size() - 1);
                IDMLVectorShape.PathPoint first = points.get(0);
                double lrx = (ta * last.rightX() + tc * last.rightY() + ttx - canvasMinX) * scale;
                double lry = (tb * last.rightX() + td * last.rightY() + tty - canvasMinY) * scale;
                double flx = (ta * first.leftX() + tc * first.leftY() + ttx - canvasMinX) * scale;
                double fly = (tb * first.leftX() + td * first.leftY() + tty - canvasMinY) * scale;
                double fax = (ta * first.anchorX() + tc * first.anchorY() + ttx - canvasMinX) * scale;
                double fay = (tb * first.anchorX() + td * first.anchorY() + tty - canvasMinY) * scale;

                path.curveTo((float) lrx, (float) lry, (float) flx, (float) fly, (float) fax, (float) fay);
                path.closePath();
            }
        }

        return path;
    }

    /**
     * 회전/반전이 사전 렌더링된 벡터 도형 래스터화.
     * composedTransform의 회전/반전 성분을 AWT Shape에 적용하여
     * 이미 회전된 상태로 래스터화한다. HWPX rotation/flip이 불필요.
     *
     * @param composedTransform 합성된 아핀 변환 [a, b, c, d, tx, ty]
     */
    public ImageResult rasterizeShape(IDMLVectorShape shape, String fillColorHex,
                                       String strokeColorHex, double[] composedTransform) {
        double wPts = shape.widthPoints();
        double hPts = shape.heightPoints();
        if ((wPts <= 0 || hPts <= 0) && shape.hasStroke() && shape.strokeWeight() > 0) {
            double minDim = shape.strokeWeight();
            if (wPts <= 0) wPts = minDim;
            if (hPts <= 0) hPts = minDim;
        }
        if (wPts <= 0 || hPts <= 0) return null;

        int targetDpi = options.imageDpi();
        double scale = targetDpi / 72.0;

        // composedTransform에서 순수 회전/반전 행렬 추출 (스케일 분리)
        double a = composedTransform[0], b = composedTransform[1];
        double c = composedTransform[2], d = composedTransform[3];
        double scaleX = Math.sqrt(a * a + b * b);
        double scaleY = Math.sqrt(c * c + d * d);
        if (scaleX < 1e-10 || scaleY < 1e-10) {
            return rasterizeShape(shape, fillColorHex, strokeColorHex);
        }
        AffineTransform rotFlip = new AffineTransform(
                a / scaleX, b / scaleX, c / scaleY, d / scaleY, 0, 0);

        float alpha = (float) (shape.opacity() / 100.0);
        IDMLVectorShape renderTarget = shape.hasClippedChild() ? shape.clippedChild() : shape;
        String actualFillHex = shape.hasClippedChild() ? fillColorHex : fillColorHex;
        String actualStrokeHex = shape.hasClippedChild() ? strokeColorHex : strokeColorHex;

        // 로컬 좌표 AWT Shape 생성 후 회전/반전 적용
        Shape awtShape = createAwtShape(renderTarget, scale);
        Shape transformedShape = rotFlip.createTransformedShape(awtShape);

        // 클리핑 처리
        Shape transformedClip = null;
        if (shape.hasClippedChild()) {
            Shape clipShape = createAwtShape(shape, scale);
            transformedClip = rotFlip.createTransformedShape(clipShape);
        }

        // 변환된 Shape의 바운딩 박스
        Rectangle2D allBounds = transformedShape.getBounds2D();
        if (transformedClip != null) {
            allBounds = allBounds.createUnion(transformedClip.getBounds2D());
        }

        double strokePad = shape.hasStroke() ? shape.strokeWeight() * scale / 2.0 + 1 : 0;
        if (renderTarget.hasStroke()) {
            strokePad = Math.max(strokePad, renderTarget.strokeWeight() * scale / 2.0 + 1);
        }

        int pixW = Math.max(10, (int) Math.ceil(allBounds.getWidth() + strokePad * 2));
        int pixH = Math.max(10, (int) Math.ceil(allBounds.getHeight() + strokePad * 2));

        BufferedImage image = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 변환된 Shape을 (0,0) 기준으로 오프셋
        g.translate(-allBounds.getMinX() + strokePad, -allBounds.getMinY() + strokePad);

        // 클리핑 설정
        if (transformedClip != null) {
            // 외부 프레임에 fill이 있으면 클리핑 영역 내에 배경색 먼저 채우기
            if (shape.hasFill()) {
                Color frameFillColor = resolveFrameFillColor(shape.fillColor());
                if (frameFillColor != null) {
                    g.setClip(transformedClip);
                    Color tintedFrame = applyTint(frameFillColor, shape.fillTint());
                    g.setColor(withAlpha(tintedFrame, alpha));
                    g.fill(transformedClip);
                }
            }
            g.setClip(transformedClip);
        }

        // 채우기 (tint는 색상 농도로 RGB에 적용, opacity만 alpha에 적용)
        if (actualFillHex != null && renderTarget.hasFill()) {
            Color fillColor = hexToColor(actualFillHex);
            if (fillColor != null) {
                Color tintedFill = applyTint(fillColor, renderTarget.fillTint());
                g.setColor(withAlpha(tintedFill, alpha));
                g.fill(transformedShape);
            }
        }

        // 선
        if (actualStrokeHex != null && renderTarget.hasStroke()) {
            Color strokeColor = hexToColor(actualStrokeHex);
            if (strokeColor != null) {
                Color tintedStroke = applyTint(strokeColor, renderTarget.strokeTint());
                g.setColor(withAlpha(tintedStroke, alpha));

                int cap = BasicStroke.CAP_BUTT;
                int join = BasicStroke.JOIN_MITER;
                if (renderTarget.endCap() == IDMLVectorShape.LineCap.ROUND) cap = BasicStroke.CAP_ROUND;
                if (renderTarget.endCap() == IDMLVectorShape.LineCap.PROJECTING) cap = BasicStroke.CAP_SQUARE;
                if (renderTarget.lineJoin() == IDMLVectorShape.LineJoin.ROUND) join = BasicStroke.JOIN_ROUND;
                if (renderTarget.lineJoin() == IDMLVectorShape.LineJoin.BEVEL) join = BasicStroke.JOIN_BEVEL;

                float strokeW = (float) (renderTarget.strokeWeight() * scale);
                BasicStroke stroke;
                if (renderTarget.hasDashPattern()) {
                    float[] dashArr = new float[renderTarget.dashPattern().length];
                    for (int i = 0; i < dashArr.length; i++)
                        dashArr[i] = (float) (renderTarget.dashPattern()[i] * scale);
                    stroke = new BasicStroke(strokeW, cap, join, (float) renderTarget.miterLimit(), dashArr, 0);
                } else {
                    stroke = new BasicStroke(strokeW, cap, join, (float) renderTarget.miterLimit());
                }
                g.setStroke(stroke);
                g.draw(transformedShape);

                // 선 끝 장식 (CircleArrowHead 등)
                drawLineEndDecorations(g, renderTarget, transformedShape, strokeColor, tintedStroke, alpha, strokeW, scale);
            }
        }

        g.dispose();

        // GradientFeather 알파 마스크 비활성 (위 주석 참조)

        try {
            byte[] pngData = encodePng(image);
            ImageResult result = new ImageResult();
            result.imageData = pngData;
            result.format = "png";
            result.pixelWidth = pixW;
            result.pixelHeight = pixH;
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 클리핑 프레임 + 복수 자식 도형을 합성 래스터화.
     * 유효 영역(자식 바운딩 박스 ∩ 클리핑 프레임)만 렌더링하여 효율적으로 처리한다.
     *
     * @param clipFrame     클리핑 프레임 (외부 도형, 채우기 없음)
     * @param colorResolver 색상 해석기
     * @param renderLeft    렌더링 영역 좌측 (클리핑 프레임 로컬 좌표, points)
     * @param renderTop     렌더링 영역 상단 (클리핑 프레임 로컬 좌표, points)
     * @param renderW       렌더링 영역 너비 (points)
     * @param renderH       렌더링 영역 높이 (points)
     */
    public ImageResult rasterizeClippedGroup(IDMLVectorShape clipFrame,
                                              kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver,
                                              double renderLeft, double renderTop,
                                              double renderW, double renderH) {
        if (renderW <= 0 || renderH <= 0) return null;
        if (!clipFrame.hasClippedChildren()) return null;

        int targetDpi = options.imageDpi();
        double scale = targetDpi / 72.0;

        double strokePad = 1;
        for (IDMLVectorShape child : clipFrame.clippedChildren()) {
            if (child.hasStroke()) {
                strokePad = Math.max(strokePad, child.strokeWeight() * scale / 2.0 + 1);
            }
        }

        int pixW = Math.max(10, (int) Math.ceil(renderW * scale + strokePad * 2));
        int pixH = Math.max(10, (int) Math.ceil(renderH * scale + strokePad * 2));

        BufferedImage image = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.translate(strokePad, strokePad);

        // 클리핑 프레임의 경로를 렌더링 좌표계에서 구축
        AffineTransform clipAt = new AffineTransform();
        clipAt.scale(scale, scale);
        clipAt.translate(-renderLeft, -renderTop);
        GeneralPath clipRawPath = buildRawPath(clipFrame);
        Shape clipShape;
        if (clipRawPath.getBounds2D().getWidth() > 0) {
            clipShape = clipAt.createTransformedShape(clipRawPath);
        } else {
            // path points가 없으면 clip frame bounds를 사용
            double cw = clipFrame.widthPoints() * scale;
            double ch = clipFrame.heightPoints() * scale;
            double cx = (clipFrame.geometricBounds()[1] - renderLeft) * scale;
            double cy = (clipFrame.geometricBounds()[0] - renderTop) * scale;
            clipShape = new Rectangle2D.Double(cx, cy, cw, ch);
        }
        g.setClip(clipShape);

        // 클리핑 프레임 자체의 채우기 (배경색)
        if (clipFrame.hasFill()) {
            String fillRef = clipFrame.fillColor();
            if (fillRef != null) {
                String fillHex = colorResolver.resolve(fillRef);
                Color frameFillColor = hexToColor(fillHex);
                if (frameFillColor != null) {
                    float frameAlpha = (float) (clipFrame.opacity() / 100.0);
                    Color tintedFrame = applyTint(frameFillColor, clipFrame.fillTint());
                    g.setColor(withAlpha(tintedFrame, frameAlpha));
                    g.fill(clipShape);
                }
            }
        }

        // 각 자식 도형을 렌더링 좌표계에서 개별 렌더링
        for (IDMLVectorShape child : clipFrame.clippedChildren()) {
            double[] ct = child.itemTransform();
            AffineTransform childAt = new AffineTransform();
            childAt.scale(scale, scale);
            childAt.translate(-renderLeft, -renderTop);
            childAt.concatenate(new AffineTransform(ct[0], ct[1], ct[2], ct[3], ct[4], ct[5]));

            GeneralPath rawPath = buildRawPath(child);
            Shape childShape = childAt.createTransformedShape(rawPath);

            // 부모 클리핑 프레임의 opacity를 자식에 합성
            float parentAlpha = (float) (clipFrame.opacity() / 100.0);
            float alpha = (float) (child.opacity() / 100.0) * parentAlpha;

            // 채우기
            String fillRef = child.fillColor();
            if (fillRef != null && child.hasFill()) {
                String fillHex = colorResolver.resolve(fillRef);
                Color fillColor = hexToColor(fillHex);
                if (fillColor != null) {
                    Color tintedFill = applyTint(fillColor, child.fillTint());
                    g.setColor(withAlpha(tintedFill, alpha));
                    g.fill(childShape);
                }
            }

            // 선
            String strokeRef = child.strokeColor();
            if (strokeRef != null && child.hasStroke()) {
                String strokeHex = colorResolver.resolve(strokeRef);
                Color strokeColor = hexToColor(strokeHex);
                if (strokeColor != null) {
                    Color tintedStroke = applyTint(strokeColor, child.strokeTint());
                    g.setColor(withAlpha(tintedStroke, alpha));

                    int cap = BasicStroke.CAP_BUTT;
                    int join = BasicStroke.JOIN_MITER;
                    if (child.endCap() == IDMLVectorShape.LineCap.ROUND) cap = BasicStroke.CAP_ROUND;
                    if (child.endCap() == IDMLVectorShape.LineCap.PROJECTING) cap = BasicStroke.CAP_SQUARE;
                    if (child.lineJoin() == IDMLVectorShape.LineJoin.ROUND) join = BasicStroke.JOIN_ROUND;
                    if (child.lineJoin() == IDMLVectorShape.LineJoin.BEVEL) join = BasicStroke.JOIN_BEVEL;

                    float strokeW = (float) (child.strokeWeight() * scale);
                    BasicStroke stroke;
                    if (child.hasDashPattern()) {
                        float[] dashArr = new float[child.dashPattern().length];
                        for (int di = 0; di < dashArr.length; di++)
                            dashArr[di] = (float) (child.dashPattern()[di] * scale);
                        stroke = new BasicStroke(strokeW, cap, join, (float) child.miterLimit(), dashArr, 0);
                    } else {
                        stroke = new BasicStroke(strokeW, cap, join, (float) child.miterLimit());
                    }
                    g.setStroke(stroke);
                    g.draw(childShape);
                }
            }
        }

        g.dispose();

        try {
            byte[] pngData = encodePng(image);
            ImageResult result = new ImageResult();
            result.imageData = pngData;
            result.format = "png";
            result.pixelWidth = pixW;
            result.pixelHeight = pixH;
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    // ===== 목표 크기 래스터화 (resolved geometry 통합용) =====

    /**
     * 단일 벡터 도형을 목표 크기(points)로 래스터화.
     * IDML path 데이터를 목표 비율에 맞춰 비균일 스케일링하여 캔버스를 생성한다.
     * resolved geometricBounds의 w/h와 일치하는 PNG를 생성하므로 찌그러짐이 없다.
     *
     * @param shape           벡터 도형
     * @param fillColorHex    채우기 색상 HEX 또는 null
     * @param strokeColorHex  선 색상 HEX 또는 null
     * @param targetWidthPts  목표 너비 (points)
     * @param targetHeightPts 목표 높이 (points)
     */
    public ImageResult rasterizeShapeAtSize(IDMLVectorShape shape,
                                             String fillColorHex, String strokeColorHex,
                                             double targetWidthPts, double targetHeightPts) {
        if (targetWidthPts <= 0 || targetHeightPts <= 0) return null;

        // IDML 네이티브 비율로 래스터화 후, resolved 목표 크기로 리사이즈.
        // 비균일 스케일(path 왜곡)을 피하기 위해 render-then-resize 전략 사용.
        ImageResult idmlResult = rasterizeShape(shape, fillColorHex, strokeColorHex);
        if (idmlResult == null) return null;

        double srcW = shape.widthPoints();
        double srcH = shape.heightPoints();

        // IDML 크기와 목표 크기가 거의 같으면 리사이즈 불필요
        if (Math.abs(targetWidthPts - srcW) < 0.5 && Math.abs(targetHeightPts - srcH) < 0.5) {
            return idmlResult;
        }

        // 목표 크기로 리사이즈
        return resizeResult(idmlResult, targetWidthPts, targetHeightPts);
    }

    /**
     * 클리핑 프레임 + 복수 자식 도형을 목표 크기(points)로 래스터화.
     * IDML 크기로 렌더링 후 목표 크기로 리사이즈 (복합 렌더링의 비균일 스케일은 복잡).
     *
     * @param clipFrame        클리핑 프레임
     * @param colorResolver    색상 해석기
     * @param renderLeft       렌더링 영역 좌측 (points)
     * @param renderTop        렌더링 영역 상단 (points)
     * @param renderW          렌더링 영역 너비 (points)
     * @param renderH          렌더링 영역 높이 (points)
     * @param targetWidthPts   목표 너비 (points)
     * @param targetHeightPts  목표 높이 (points)
     */
    public ImageResult rasterizeClippedGroupAtSize(IDMLVectorShape clipFrame,
                                                    kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver,
                                                    double renderLeft, double renderTop,
                                                    double renderW, double renderH,
                                                    double targetWidthPts, double targetHeightPts) {
        // IDML 크기로 렌더링
        ImageResult idmlResult = rasterizeClippedGroup(clipFrame, colorResolver,
                renderLeft, renderTop, renderW, renderH);
        if (idmlResult == null) return null;

        // 목표 크기와 IDML 크기가 거의 같으면 리사이즈 불필요
        if (Math.abs(targetWidthPts - renderW) < 0.5 && Math.abs(targetHeightPts - renderH) < 0.5) {
            return idmlResult;
        }

        // 목표 크기로 리사이즈
        return resizeResult(idmlResult, targetWidthPts, targetHeightPts);
    }

    /**
     * 벡터 그룹(복수 도형)을 목표 크기(points)로 래스터화.
     * IDML 크기로 렌더링 후 목표 크기로 리사이즈.
     *
     * @param shapes           도형 + 색상 목록
     * @param groupTransform   부모 Group의 ItemTransform (null 가능)
     * @param pageClipBounds   페이지 절대좌표 [top, left, bottom, right] (null이면 클리핑 없음)
     * @param targetWidthPts   목표 너비 (points)
     * @param targetHeightPts  목표 높이 (points)
     */
    public ImageResult rasterizeShapesAtSize(List<ShapeWithColor> shapes,
                                              double[] groupTransform, double[] pageClipBounds,
                                              double targetWidthPts, double targetHeightPts) {
        // IDML 크기로 렌더링
        ImageResult idmlResult = rasterizeShapes(shapes, groupTransform, pageClipBounds);
        if (idmlResult == null) return null;

        // 목표 크기와 IDML 크기가 거의 같으면 리사이즈 불필요
        if (idmlResult.widthPts > 0 && idmlResult.heightPts > 0
                && Math.abs(targetWidthPts - idmlResult.widthPts) < 0.5
                && Math.abs(targetHeightPts - idmlResult.heightPts) < 0.5) {
            return idmlResult;
        }

        // 목표 크기로 리사이즈
        return resizeResult(idmlResult, targetWidthPts, targetHeightPts);
    }

    /**
     * ImageResult의 PNG를 목표 크기(points)로 리사이즈.
     */
    private ImageResult resizeResult(ImageResult src, double targetWidthPts, double targetHeightPts) {
        if (src == null || src.imageData == null) return src;
        try {
            BufferedImage srcImg = ImageIO.read(new ByteArrayInputStream(src.imageData));
            if (srcImg == null) return src;

            int targetDpi = options.imageDpi();
            double dpiScale = targetDpi / 72.0;
            int newPixW = Math.max(10, (int) Math.ceil(targetWidthPts * dpiScale));
            int newPixH = Math.max(10, (int) Math.ceil(targetHeightPts * dpiScale));

            BufferedImage resized = new BufferedImage(newPixW, newPixH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(srcImg, 0, 0, newPixW, newPixH, null);
            g.dispose();

            byte[] pngData = encodePng(resized);
            ImageResult result = new ImageResult();
            result.imageData = pngData;
            result.format = "png";
            result.pixelWidth = newPixW;
            result.pixelHeight = newPixH;
            result.widthPts = targetWidthPts;
            result.heightPts = targetHeightPts;
            return result;
        } catch (IOException e) {
            return src;
        }
    }

    // ==================== 다중 이미지 그룹 합성 래스터화 ====================

    /**
     * 다중 이미지/벡터 그룹의 모든 비-텍스트 자식을 하나의 캔버스에 합성 래스터화.
     * 이미지(EPS/PSD)는 loadImage로 로드, 벡터 도형은 Java2D로 직접 렌더링.
     *
     * @param ig            루트 인라인 그래픽 (Group)
     * @param colorResolver 색상 해석기
     * @return 합성 래스터화 결과 (widthPts, heightPts 포함) 또는 실패 시 null
     */
    public ImageResult compositeGroupChildren(
            IDMLCharacterRun.InlineGraphic ig,
            ColorResolver colorResolver) {
        // 1. 전체 비-텍스트 자식의 바운딩 박스 계산 (flip/rotation 포함)
        double[] bounds = computeNonTextVisualBounds(ig, 0, 0);
        if (bounds == null || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]) {
            return null;
        }
        double canvasLeft = bounds[0];
        double canvasTop = bounds[1];
        double canvasW = bounds[2] - bounds[0];
        double canvasH = bounds[3] - bounds[1];

        int targetDpi = options.imageDpi();
        double scale = targetDpi / 72.0;
        int pixW = Math.max(10, (int) Math.ceil(canvasW * scale));
        int pixH = Math.max(10, (int) Math.ceil(canvasH * scale));

        // 캔버스가 너무 크면 안전 제한
        if (pixW > 4000 || pixH > 4000) {
            double shrink = Math.min(4000.0 / pixW, 4000.0 / pixH);
            scale *= shrink;
            pixW = (int) Math.ceil(canvasW * scale);
            pixH = (int) Math.ceil(canvasH * scale);
        }

        BufferedImage canvas = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);

        // 2. 자식을 z-order(리스트 순서)로 순회하며 렌더링
        drawCompositeChildren(g, ig, colorResolver, canvasLeft, canvasTop, scale, 0, 0);

        g.dispose();

        try {
            byte[] pngData = encodePng(canvas);
            ImageResult result = new ImageResult();
            result.imageData = pngData;
            result.format = "png";
            result.pixelWidth = pixW;
            result.pixelHeight = pixH;
            result.widthPts = canvasW;
            result.heightPts = canvasH;
            return result;
        } catch (IOException e) {
            System.err.println("[COMPOSITE] PNG encode failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 비-텍스트 자식의 그룹좌표 바운드 (flip/rotation 포함 4-corner 변환).
     * @return [minX, minY, maxX, maxY] 그룹 좌표계 (points)
     */
    private double[] computeNonTextVisualBounds(
            IDMLCharacterRun.InlineGraphic ig, double accTx, double accTy) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            double[] gb = child.geometricBounds();
            double[] ct = child.itemTransform();
            // gb=[0,0,0,0] (그룹 등 PathGeometry 없는 요소)는 건너뜀
            // — zero-area 점이 바운드를 잘못 확장하는 것을 방지
            if (gb != null && ct != null
                    && (gb[0] != gb[2] || gb[1] != gb[3])) {
                double[] aabb = transformBoundsAABB(gb, ct, accTx, accTy);
                minX = Math.min(minX, aabb[0]);
                minY = Math.min(minY, aabb[1]);
                maxX = Math.max(maxX, aabb[2]);
                maxY = Math.max(maxY, aabb[3]);
            }
            // 하위 그룹 재귀
            double childAccTx = accTx + (ct != null ? ct[4] : 0);
            double childAccTy = accTy + (ct != null ? ct[5] : 0);
            double[] sub = computeNonTextVisualBounds(child, childAccTx, childAccTy);
            if (sub != null && sub[0] < Double.MAX_VALUE) {
                minX = Math.min(minX, sub[0]);
                minY = Math.min(minY, sub[1]);
                maxX = Math.max(maxX, sub[2]);
                maxY = Math.max(maxY, sub[3]);
            }
        }

        if (minX >= Double.MAX_VALUE) return null;
        return new double[]{minX, minY, maxX, maxY};
    }

    /**
     * geometricBounds [top,left,bottom,right]에 itemTransform + 누적 오프셋을 적용.
     * @return [minX, minY, maxX, maxY]
     */
    private static double[] transformBoundsAABB(double[] gb, double[] ct,
                                                 double accTx, double accTy) {
        double a = ct[0], bv = ct[1], c = ct[2], d = ct[3];
        double tx = ct[4] + accTx, ty = ct[5] + accTy;
        // 단순 translation 최적화
        if (a == 1 && bv == 0 && c == 0 && d == 1) {
            return new double[]{gb[1] + tx, gb[0] + ty, gb[3] + tx, gb[2] + ty};
        }
        // 4 corners
        double[] xs = {
                a * gb[1] + c * gb[0] + tx, a * gb[3] + c * gb[0] + tx,
                a * gb[1] + c * gb[2] + tx, a * gb[3] + c * gb[2] + tx};
        double[] ys = {
                bv * gb[1] + d * gb[0] + ty, bv * gb[3] + d * gb[0] + ty,
                bv * gb[1] + d * gb[2] + ty, bv * gb[3] + d * gb[2] + ty};
        double mnX = xs[0], mxX = xs[0], mnY = ys[0], mxY = ys[0];
        for (int i = 1; i < 4; i++) {
            if (xs[i] < mnX) mnX = xs[i];
            if (xs[i] > mxX) mxX = xs[i];
            if (ys[i] < mnY) mnY = ys[i];
            if (ys[i] > mxY) mxY = ys[i];
        }
        return new double[]{mnX, mnY, mxX, mxY};
    }

    /**
     * 그룹 내 자식을 재귀적으로 순회하며 캔버스에 렌더링.
     */
    private void drawCompositeChildren(Graphics2D g,
                                        IDMLCharacterRun.InlineGraphic ig,
                                        ColorResolver colorResolver,
                                        double canvasLeft, double canvasTop,
                                        double scale,
                                        double accTx, double accTy) {
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            double[] ct = child.itemTransform();
            double childTx = accTx + (ct != null ? ct[4] : 0);
            double childTy = accTy + (ct != null ? ct[5] : 0);

            if (child.hasImage()) {
                drawCompositeImageChild(g, child, canvasLeft, canvasTop, scale, accTx, accTy);
            } else if (child.hasVectorShape()) {
                drawCompositeVectorChild(g, child, colorResolver, canvasLeft, canvasTop, scale, accTx, accTy);
            }

            // 하위 그룹 재귀
            if (!child.childGraphics().isEmpty()) {
                drawCompositeChildren(g, child, colorResolver, canvasLeft, canvasTop, scale,
                        childTx, childTy);
            }
        }
    }

    /**
     * 이미지 자식을 로드하여 합성 캔버스에 그린다.
     */
    private void drawCompositeImageChild(Graphics2D g,
                                          IDMLCharacterRun.InlineGraphic child,
                                          double canvasLeft, double canvasTop,
                                          double scale,
                                          double accTx, double accTy) {
        double[] frameBounds = child.geometricBounds();
        if (frameBounds == null) return;

        double frameW = frameBounds[3] - frameBounds[1];
        double frameH = frameBounds[2] - frameBounds[0];
        if (frameW <= 0 || frameH <= 0) return;

        long displayW = CoordinateConverter.pointsToHwpunits(frameW);
        long displayH = CoordinateConverter.pointsToHwpunits(frameH);

        // 이미지 로드 (클리핑 적용됨)
        ImageResult loaded = loadImage(child.linkResourceURI(),
                displayW, displayH,
                child.imageTransform(), frameBounds, child.graphicBounds());
        if (loaded == null || loaded.imageData == null || loaded.isPlaceholder) return;

        try {
            BufferedImage childImg = ImageIO.read(new ByteArrayInputStream(loaded.imageData));
            if (childImg == null) return;

            // 프레임의 그룹 좌표 위치 계산
            double[] ct = child.itemTransform();
            double[] aabb = transformBoundsAABB(frameBounds, ct, accTx, accTy);
            double destX = (aabb[0] - canvasLeft) * scale;
            double destY = (aabb[1] - canvasTop) * scale;
            double destW = (aabb[2] - aabb[0]) * scale;
            double destH = (aabb[3] - aabb[1]) * scale;

            boolean flipH = ct != null && ct[0] < 0;
            boolean flipV = ct != null && ct[3] < 0;

            if (flipH || flipV) {
                AffineTransform at = new AffineTransform();
                at.translate(destX, destY);
                if (flipH) {
                    at.translate(destW, 0);
                    at.scale(-1, 1);
                }
                if (flipV) {
                    at.translate(0, destH);
                    at.scale(1, -1);
                }
                at.scale(destW / childImg.getWidth(), destH / childImg.getHeight());
                g.drawImage(childImg, at, null);
            } else {
                g.drawImage(childImg,
                        (int) Math.round(destX), (int) Math.round(destY),
                        (int) Math.round(destW), (int) Math.round(destH), null);
            }
        } catch (IOException e) {
            System.err.println("[COMPOSITE] drawImageChild failed: " + e.getMessage());
        }
    }

    /**
     * 벡터 도형 자식을 합성 캔버스에 그린다 (fill/stroke).
     * FillColor 미지정 + FillTint > 0 → Paper(흰색) 기본값 사용.
     */
    private void drawCompositeVectorChild(Graphics2D g,
                                           IDMLCharacterRun.InlineGraphic child,
                                           ColorResolver colorResolver,
                                           double canvasLeft, double canvasTop,
                                           double scale,
                                           double accTx, double accTy) {
        IDMLVectorShape shape = child.vectorShape();
        if (shape == null) return;

        String fillRef = shape.fillColor();
        String strokeRef = shape.strokeColor();
        String fillHex = resolveColor(fillRef, colorResolver);
        String strokeHex = resolveColor(strokeRef, colorResolver);

        // FillTint만 있고 FillColor 없는 경우 → Paper(흰색) 기본값
        if (fillHex == null && shape.fillTint() > 0 && shape.fillTint() < 100) {
            fillHex = "#FFFFFF";
        }

        if (fillHex == null && strokeHex == null) return;

        // 누적 변환 계산
        double[] ct = child.itemTransform();
        double[] accTransform = null;
        if (ct != null) {
            accTransform = new double[]{ct[0], ct[1], ct[2], ct[3],
                    ct[4] + accTx, ct[5] + accTy};
        }

        Shape awtShape;
        if (accTransform != null &&
                (accTransform[0] != 1 || accTransform[1] != 0 ||
                        accTransform[2] != 0 || accTransform[3] != 1)) {
            awtShape = buildTransformedPath(shape, accTransform, scale, canvasLeft, canvasTop);
        } else {
            double[] gb = shape.geometricBounds();
            if (gb == null) return;
            double offX = gb[1] + (accTransform != null ? accTransform[4] : accTx) - canvasLeft;
            double offY = gb[0] + (accTransform != null ? accTransform[5] : accTy) - canvasTop;
            awtShape = buildPathFromPoints(shape, scale, offX, offY);
        }
        if (awtShape == null) return;

        if (fillHex != null) {
            Color fillColor = hexToColor(fillHex);
            if (fillColor != null) {
                Color tintedFill = applyTint(fillColor, shape.fillTint());
                g.setColor(tintedFill);
                g.fill(awtShape);
            }
        }
        if (strokeHex != null && shape.strokeWeight() > 0) {
            Color strokeColor = hexToColor(strokeHex);
            if (strokeColor != null) {
                Color tintedStroke = applyTint(strokeColor, shape.strokeTint());
                g.setColor(tintedStroke);
                float strokeW = (float) (shape.strokeWeight() * scale);
                g.setStroke(new BasicStroke(strokeW));
                g.draw(awtShape);
            }
        }
    }

    /**
     * 색상 참조를 hex 문자열로 변환. "None"/null이면 null 반환.
     */
    private static String resolveColor(String colorRef, ColorResolver colorResolver) {
        if (colorRef == null || "None".equals(colorRef) || colorRef.contains("[None]")) return null;
        String hex = colorResolver.resolve(colorRef);
        return (hex != null && !hex.isEmpty()) ? hex : null;
    }

    // ==================== 끝: 다중 이미지 그룹 합성 ====================

    /**
     * GradientFeather 알파 마스크를 이미지에 적용 (비회전 버전).
     */
    private void applyGradientFeatherMask(BufferedImage image, IDMLVectorShape shape,
                                           double scale, double strokePad) {
        applyGradientFeatherMask(image, shape, scale, strokePad, null, null);
    }

    /**
     * GradientFeather 알파 마스크를 이미지에 적용.
     * IDML GradientFeatherSetting의 각도, 길이, 시작점을 기반으로
     * 선형 투명도 그라디언트를 기존 알파 채널에 곱한다.
     *
     * @param image         래스터화된 도형 이미지 (TYPE_INT_ARGB)
     * @param shape         원본 벡터 도형 (GradientFeather 속성 포함)
     * @param scale         DPI 스케일 (targetDpi / 72.0)
     * @param strokePad     스트로크 패딩 (픽셀)
     * @param rotFlip       회전/반전 변환 (composedTransform 사용 시), null이면 비회전
     * @param allBounds     회전된 Shape의 바운딩 박스 (composedTransform 사용 시)
     */
    private void applyGradientFeatherMask(BufferedImage image, IDMLVectorShape shape,
                                           double scale, double strokePad,
                                           AffineTransform rotFlip, Rectangle2D allBounds) {
        double angleDeg = shape.gradientFeatherAngle();
        double lengthPts = shape.gradientFeatherLength();
        double[] gs = shape.gradientFeatherStart();
        double[] bounds = shape.geometricBounds();

        if (bounds == null || lengthPts <= 0) return;

        // 그라디언트 방향 벡터 (IDML: 0°=right, 90°=bottom-to-top, -90°=top-to-bottom)
        // IDML은 수학 좌표계(Y↑)를 사용하므로 화면 좌표계(Y↓)에서는 Y축 반전 필요
        double angleRad = Math.toRadians(angleDeg);
        double dirX = Math.cos(angleRad);
        double dirY = -Math.sin(angleRad);

        // GradientStart를 도형 로컬 좌표에서 스케일된 로컬 좌표로 변환
        double gsLocalX, gsLocalY;
        if (gs != null && gs.length >= 2) {
            gsLocalX = (gs[0] - bounds[1]) * scale;
            gsLocalY = (gs[1] - bounds[0]) * scale;
        } else {
            gsLocalX = (bounds[3] - bounds[1]) * scale / 2.0;
            gsLocalY = (bounds[2] - bounds[0]) * scale / 2.0;
        }

        double gsPixX, gsPixY;
        double dirPixX = dirX, dirPixY = dirY;

        if (rotFlip != null && allBounds != null) {
            // composedTransform 적용: 로컬 좌표를 회전/반전하여 이미지 좌표로 변환
            double[] gsTransformed = new double[2];
            rotFlip.transform(new double[]{gsLocalX, gsLocalY}, 0, gsTransformed, 0, 1);
            gsPixX = gsTransformed[0] - allBounds.getMinX() + strokePad;
            gsPixY = gsTransformed[1] - allBounds.getMinY() + strokePad;

            // 방향 벡터도 회전/반전 적용
            double[] dirTransformed = new double[2];
            rotFlip.deltaTransform(new double[]{dirX, dirY}, 0, dirTransformed, 0, 1);
            dirPixX = dirTransformed[0];
            dirPixY = dirTransformed[1];
        } else {
            // 비회전: 단순 오프셋
            gsPixX = gsLocalX + strokePad;
            gsPixY = gsLocalY + strokePad;
        }

        double lengthPx = lengthPts * scale;
        if (lengthPx <= 0) return;

        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int argb = pixels[idx];
                int currentAlpha = (argb >>> 24) & 0xFF;
                if (currentAlpha == 0) continue;

                // GradientStart에서 현재 픽셀까지 벡터를 그라디언트 방향에 투영
                double dx = x - gsPixX;
                double dy = y - gsPixY;
                double projection = dx * dirPixX + dy * dirPixY;

                // 0 = 시작점(불투명), 1 = 끝점(투명)
                double fraction = Math.max(0, Math.min(1, projection / lengthPx));

                int newAlpha = (int) Math.round(currentAlpha * (1.0 - fraction));
                pixels[idx] = (Math.max(0, Math.min(255, newAlpha)) << 24) | (argb & 0x00FFFFFF);
            }
        }

        image.setRGB(0, 0, w, h, pixels, 0, w);
    }

    private GeneralPath buildRawPath(IDMLVectorShape shape) {
        // 복합 경로(compound path)는 EVEN_ODD 규칙 사용 (IDML 기본)
        int windingRule = shape.hasSubPaths()
                ? GeneralPath.WIND_EVEN_ODD : GeneralPath.WIND_NON_ZERO;
        GeneralPath path = new GeneralPath(windingRule);

        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath sub : shape.subPaths()) {
                appendRawSubPath(path, sub.points(), sub.isOpen());
            }
        } else if (!shape.pathPoints().isEmpty()) {
            appendRawSubPath(path, shape.pathPoints(), shape.pathOpen());
        }

        return path;
    }

    private void appendRawSubPath(GeneralPath path, java.util.List<IDMLVectorShape.PathPoint> points,
                                   boolean open) {
        if (points.isEmpty()) return;

        IDMLVectorShape.PathPoint first = points.get(0);
        path.moveTo((float) first.anchorX(), (float) first.anchorY());

        for (int i = 1; i < points.size(); i++) {
            IDMLVectorShape.PathPoint prev = points.get(i - 1);
            IDMLVectorShape.PathPoint curr = points.get(i);

            if (prev.isStraight() && curr.isStraight()) {
                path.lineTo((float) curr.anchorX(), (float) curr.anchorY());
            } else {
                path.curveTo(
                    (float) prev.rightX(), (float) prev.rightY(),
                    (float) curr.leftX(), (float) curr.leftY(),
                    (float) curr.anchorX(), (float) curr.anchorY());
            }
        }

        if (!open && points.size() > 1) {
            IDMLVectorShape.PathPoint last = points.get(points.size() - 1);
            if (last.isStraight() && first.isStraight()) {
                path.closePath();
            } else {
                path.curveTo(
                    (float) last.rightX(), (float) last.rightY(),
                    (float) first.leftX(), (float) first.leftY(),
                    (float) first.anchorX(), (float) first.anchorY());
                path.closePath();
            }
        }
    }

    private Shape createAwtShape(IDMLVectorShape shape, double scale) {
        double w = shape.widthPoints() * scale;
        double h = shape.heightPoints() * scale;

        if (shape.shapeType() == null) {
            return new Rectangle2D.Double(0, 0, w, h);
        }

        switch (shape.shapeType()) {
            case RECTANGLE:
                // IDML Rectangle can have non-rectangular PathGeometry (parallelogram, trapezoid, etc.)
                if (hasNonRectangularPath(shape)) {
                    return buildPathFromPoints(shape, scale);
                }
                if (shape.hasRoundedCorners()) {
                    double maxRPts = Math.min(shape.widthPoints(), shape.heightPoints()) / 2.0;
                    double r = Math.min(effectiveCornerRadius(shape), maxRPts) * scale;
                    return new RoundRectangle2D.Double(0, 0, w, h, r * 2, r * 2);
                }
                return new Rectangle2D.Double(0, 0, w, h);

            case OVAL:
                // IDML Oval can have non-standard PathGeometry (modified anchors, partial arcs, etc.)
                if (hasNonEllipticalPath(shape)) {
                    return buildPathFromPoints(shape, scale);
                }
                return new Ellipse2D.Double(0, 0, w, h);

            case POLYGON:
                // 직선 4점 + 둥근 모서리 Polygon은 RoundRectangle2D 처리
                if (shape.hasRoundedCorners() && !hasNonRectangularPath(shape)) {
                    double maxRPts = Math.min(shape.widthPoints(), shape.heightPoints()) / 2.0;
                    double r = Math.min(effectiveCornerRadius(shape), maxRPts) * scale;
                    return new RoundRectangle2D.Double(0, 0, w, h, r * 2, r * 2);
                }
                return buildPathFromPoints(shape, scale);
            case GRAPHIC_LINE:
                return buildPathFromPoints(shape, scale);

            default:
                return new Rectangle2D.Double(0, 0, w, h);
        }
    }

    /**
     * per-corner radii가 있으면 최소값 반환, 없으면 uniform cornerRadius 반환.
     */
    private static double effectiveCornerRadius(IDMLVectorShape shape) {
        double[] radii = shape.cornerRadii();
        if (radii != null && radii.length >= 4) {
            double min = Double.MAX_VALUE;
            for (double r : radii) {
                if (r >= 0 && r < min) min = r;
            }
            if (min < Double.MAX_VALUE) return min;
        }
        return shape.cornerRadius();
    }

    /**
     * IDML Rectangle가 실제로는 비직사각형 PathGeometry를 가지는지 검사.
     * (InDesign에서 Rectangle의 앵커를 이동하면 사다리꼴/평행사변형이 됨)
     */
    private boolean hasNonRectangularPath(IDMLVectorShape shape) {
        List<IDMLVectorShape.PathPoint> points = shape.pathPoints();
        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath sub : shape.subPaths()) {
                if (sub.points().size() >= 3) return true;
            }
        }
        if (points == null || points.size() < 3) return false;

        double[] bounds = shape.geometricBounds();
        if (bounds == null) return false;

        double top = bounds[0], left = bounds[1], bottom = bounds[2], right = bounds[3];
        double tol = 0.5; // 0.5pt 허용 오차

        for (IDMLVectorShape.PathPoint pt : points) {
            // 곡선 핸들이 있으면 비직사각형
            if (!pt.isStraight()) return true;

            // 앵커가 바운딩 박스 모서리가 아니면 비직사각형
            boolean xAtEdge = Math.abs(pt.anchorX() - left) < tol
                    || Math.abs(pt.anchorX() - right) < tol;
            boolean yAtEdge = Math.abs(pt.anchorY() - top) < tol
                    || Math.abs(pt.anchorY() - bottom) < tol;
            if (!xAtEdge || !yAtEdge) return true;
        }

        // 꼭짓점이 4개가 아니면 비직사각형 (삼각형 등)
        if (points.size() != 4) return true;

        return false;
    }

    /**
     * IDML Oval이 표준 타원이 아닌 수정된 PathGeometry를 가지는지 검사.
     * 표준 Oval은 정확히 4개의 PathPoint가 cardinal position(상하좌우 중점)에 위치.
     * InDesign에서 별/반짝거림 등 커스텀 도형은 Oval 프리미티브에 변형된 앵커를 적용.
     */
    private boolean hasNonEllipticalPath(IDMLVectorShape shape) {
        if (shape.hasSubPaths()) return true;
        List<IDMLVectorShape.PathPoint> points = shape.pathPoints();
        if (points == null || points.isEmpty()) return false;
        if (points.size() != 4) return true;

        // 4점이라도 앵커가 표준 타원의 cardinal position에서 벗어나면 비표준
        double[] bounds = shape.geometricBounds();
        if (bounds == null) return false;

        double top = bounds[0], left = bounds[1], bottom = bounds[2], right = bounds[3];
        double cx = (left + right) / 2.0;
        double cy = (top + bottom) / 2.0;
        double tol = 0.5; // 0.5pt 허용 오차

        // 표준 타원의 4개 cardinal point: (cx,top), (right,cy), (cx,bottom), (left,cy)
        boolean[] matched = new boolean[4];
        for (IDMLVectorShape.PathPoint pt : points) {
            double ax = pt.anchorX(), ay = pt.anchorY();
            if (Math.abs(ax - cx) < tol && Math.abs(ay - top) < tol) matched[0] = true;
            else if (Math.abs(ax - right) < tol && Math.abs(ay - cy) < tol) matched[1] = true;
            else if (Math.abs(ax - cx) < tol && Math.abs(ay - bottom) < tol) matched[2] = true;
            else if (Math.abs(ax - left) < tol && Math.abs(ay - cy) < tol) matched[3] = true;
            else return true; // cardinal position 아님 → 비표준
        }
        return false;
    }

    private Shape buildPathFromPoints(IDMLVectorShape shape, double scale) {
        double[] bounds = shape.geometricBounds();
        double offX = (bounds != null) ? -bounds[1] : 0;
        double offY = (bounds != null) ? -bounds[0] : 0;

        // 복합 경로(compound path)는 EVEN_ODD 규칙 사용 (IDML 기본)
        int windingRule = shape.hasSubPaths()
                ? GeneralPath.WIND_EVEN_ODD : GeneralPath.WIND_NON_ZERO;
        GeneralPath path = new GeneralPath(windingRule);

        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath sub : shape.subPaths()) {
                appendSubPath(path, sub.points(), sub.isOpen(), scale, offX, offY);
            }
        } else if (!shape.pathPoints().isEmpty()) {
            appendSubPath(path, shape.pathPoints(), shape.pathOpen(), scale, offX, offY);
        }

        return path;
    }

    private void appendSubPath(GeneralPath path, List<IDMLVectorShape.PathPoint> points,
                                boolean open, double scale, double offX, double offY) {
        if (points.isEmpty()) return;

        IDMLVectorShape.PathPoint first = points.get(0);
        path.moveTo((float) ((first.anchorX() + offX) * scale),
                     (float) ((first.anchorY() + offY) * scale));

        for (int i = 1; i < points.size(); i++) {
            IDMLVectorShape.PathPoint prev = points.get(i - 1);
            IDMLVectorShape.PathPoint curr = points.get(i);

            if (prev.isStraight() && curr.isStraight()) {
                path.lineTo((float) ((curr.anchorX() + offX) * scale),
                             (float) ((curr.anchorY() + offY) * scale));
            } else {
                path.curveTo(
                    (float) ((prev.rightX() + offX) * scale), (float) ((prev.rightY() + offY) * scale),
                    (float) ((curr.leftX() + offX) * scale), (float) ((curr.leftY() + offY) * scale),
                    (float) ((curr.anchorX() + offX) * scale), (float) ((curr.anchorY() + offY) * scale));
            }
        }

        if (!open && points.size() > 1) {
            IDMLVectorShape.PathPoint last = points.get(points.size() - 1);
            if (last.isStraight() && first.isStraight()) {
                path.closePath();
            } else {
                path.curveTo(
                    (float) ((last.rightX() + offX) * scale), (float) ((last.rightY() + offY) * scale),
                    (float) ((first.leftX() + offX) * scale), (float) ((first.leftY() + offY) * scale),
                    (float) ((first.anchorX() + offX) * scale), (float) ((first.anchorY() + offY) * scale));
                path.closePath();
            }
        }
    }

    /**
     * IDML fillColor 문자열에서 Color 객체를 직접 해석한다 (ColorResolver 없이).
     * 클리핑 프레임의 배경색 렌더링용.
     */
    private static Color resolveFrameFillColor(String idmlFillColor) {
        if (idmlFillColor == null) return null;
        if ("Color/Black".equals(idmlFillColor)) return Color.BLACK;
        if ("Color/Paper".equals(idmlFillColor) || "Color/White".equals(idmlFillColor))
            return Color.WHITE;
        // CMYK 패턴: "Color/C=0 M=80 Y=100 K=0"
        if (idmlFillColor.startsWith("Color/C=")) {
            try {
                String[] parts = idmlFillColor.substring("Color/".length()).split("\\s+");
                double c = 0, m = 0, y = 0, k = 0;
                for (String part : parts) {
                    if (part.startsWith("C=")) c = Double.parseDouble(part.substring(2)) / 100.0;
                    else if (part.startsWith("M=")) m = Double.parseDouble(part.substring(2)) / 100.0;
                    else if (part.startsWith("Y=")) y = Double.parseDouble(part.substring(2)) / 100.0;
                    else if (part.startsWith("K=")) k = Double.parseDouble(part.substring(2)) / 100.0;
                }
                return kr.dogfoot.hwpxlib.tool.idmlconverter.util.CMYKColorConverter.cmykToColor(c, m, y, k);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static Color hexToColor(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Color withAlpha(Color c, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /**
     * tint를 RGB에 적용 (흰색 블렌딩). tint=100→원색, tint=0→흰색.
     * InDesign의 tint는 투명도(alpha)가 아닌 색상 농도로, 흰색(Paper)과 블렌딩하여 연한 색을 만든다.
     */
    private static Color applyTint(Color c, double tint) {
        if (tint >= 100) return c;
        double f = tint / 100.0;
        int r = (int) Math.round(255 + (c.getRed() - 255) * f);
        int g = (int) Math.round(255 + (c.getGreen() - 255) * f);
        int b = (int) Math.round(255 + (c.getBlue() - 255) * f);
        return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b)));
    }

    /**
     * PNG 이미지 데이터를 수평 반전(좌우 미러).
     * itemTransform에 flip이 포함된 경우, HWPX flip 속성 대신 픽셀 레벨에서 처리.
     */
    public static byte[] flipHorizontally(byte[] pngData) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngData));
            if (img == null) return pngData;
            int w = img.getWidth(), h = img.getHeight();
            BufferedImage flipped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = flipped.createGraphics();
            g.drawImage(img, w, 0, 0, h, 0, 0, w, h, null);
            g.dispose();
            return encodePng(flipped);
        } catch (IOException e) {
            return pngData;
        }
    }

    public static byte[] flipVertically(byte[] pngData) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngData));
            if (img == null) return pngData;
            int w = img.getWidth(), h = img.getHeight();
            BufferedImage flipped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = flipped.createGraphics();
            g.drawImage(img, 0, h, w, 0, 0, 0, w, h, null);
            g.dispose();
            return encodePng(flipped);
        } catch (IOException e) {
            return pngData;
        }
    }

    /**
     * 이미지 데이터에 회전/반전을 사전 렌더링.
     * composedTransform의 회전/반전 성분을 픽셀 레벨에서 적용하여
     * HWPX rotationInfo 없이 올바르게 표시되도록 한다.
     *
     * @param imageData  원본 이미지 바이트 (PNG/JPEG)
     * @param composedTransform 합성된 아핀 변환 [a, b, c, d, tx, ty]
     * @return 회전된 이미지와 새 치수를 포함한 ImageResult, 또는 실패 시 null
     */
    public static ImageResult preRenderRotation(byte[] imageData, double[] composedTransform) {
        if (imageData == null || composedTransform == null) return null;

        try {
            BufferedImage srcImg = ImageIO.read(new ByteArrayInputStream(imageData));
            if (srcImg == null) return null;

            double a = composedTransform[0], b = composedTransform[1];
            double c = composedTransform[2], d = composedTransform[3];
            double scaleX = Math.sqrt(a * a + b * b);
            double scaleY = Math.sqrt(c * c + d * d);
            if (scaleX < 1e-10 || scaleY < 1e-10) return null;

            // 정규화된 회전/반전 행렬 (스케일 제거)
            AffineTransform rotFlip = new AffineTransform(
                    a / scaleX, b / scaleX, c / scaleY, d / scaleY, 0, 0);

            int srcW = srcImg.getWidth();
            int srcH = srcImg.getHeight();

            // 4코너를 변환하여 새 바운딩 박스 계산
            double[] corners = new double[]{0, 0, srcW, 0, srcW, srcH, 0, srcH};
            double[] transformed = new double[8];
            rotFlip.transform(corners, 0, transformed, 0, 4);

            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i < 8; i += 2) {
                minX = Math.min(minX, transformed[i]);
                maxX = Math.max(maxX, transformed[i]);
                minY = Math.min(minY, transformed[i + 1]);
                maxY = Math.max(maxY, transformed[i + 1]);
            }

            int newW = Math.max(1, (int) Math.ceil(maxX - minX));
            int newH = Math.max(1, (int) Math.ceil(maxY - minY));

            BufferedImage dstImg = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = dstImg.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            // 원점 보정 후 회전/반전 적용
            g.translate(-minX, -minY);
            g.transform(rotFlip);
            g.drawImage(srcImg, 0, 0, null);
            g.dispose();

            byte[] pngData = encodePng(dstImg);
            ImageResult result = new ImageResult();
            result.imageData = pngData;
            result.format = "png";
            result.pixelWidth = newW;
            result.pixelHeight = newH;
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 열린 경로의 시작/끝점에 선 끝 장식(CircleArrowHead 등)을 그린다.
     */
    private void drawLineEndDecorations(Graphics2D g, IDMLVectorShape shape,
                                         Shape awtShape, Color baseColor,
                                         Color tintedColor, float alpha,
                                         float strokeW, double scale) {
        String leftEnd = shape.leftLineEnd();
        String rightEnd = shape.rightLineEnd();
        if (leftEnd == null && rightEnd == null) return;
        if (!shape.pathOpen()) return;

        // 경로의 시작점/끝점 추출
        PathIterator pi = awtShape.getPathIterator(null);
        double[] firstPt = null;
        double[] lastPt = null;
        double[] coords = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    if (firstPt == null) firstPt = new double[]{coords[0], coords[1]};
                    lastPt = new double[]{coords[0], coords[1]};
                    break;
                case PathIterator.SEG_LINETO:
                    lastPt = new double[]{coords[0], coords[1]};
                    break;
                case PathIterator.SEG_CUBICTO:
                    lastPt = new double[]{coords[4], coords[5]};
                    break;
                case PathIterator.SEG_QUADTO:
                    lastPt = new double[]{coords[2], coords[3]};
                    break;
            }
            pi.next();
        }

        // CircleArrowHead: 스트로크 두께의 약 3배 직경의 채워진 원
        double circleR = strokeW * 1.5;

        if (leftEnd != null && leftEnd.contains("Circle") && firstPt != null) {
            g.setColor(withAlpha(tintedColor, alpha));
            g.fill(new Ellipse2D.Double(
                    firstPt[0] - circleR, firstPt[1] - circleR,
                    circleR * 2, circleR * 2));
        }
        if (rightEnd != null && rightEnd.contains("Circle") && lastPt != null) {
            g.setColor(withAlpha(tintedColor, alpha));
            g.fill(new Ellipse2D.Double(
                    lastPt[0] - circleR, lastPt[1] - circleR,
                    circleR * 2, circleR * 2));
        }
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /**
     * CMYK 등 PNG 직접 인코딩이 실패하는 이미지를 ARGB로 변환 후 재시도.
     */
    private static byte[] safeEncodePng(BufferedImage image) throws IOException {
        byte[] data = encodePng(image);
        if (data.length > 0) return data;

        // CMYK 등 비표준 색공간 → ARGB 변환 후 재시도
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return encodePng(argb);
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
        } catch (Exception e) {
            System.err.println("[ASTImageLoader] URL 디코딩 실패: " + path + " - " + e.getMessage());
        }
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

    private String findFileIgnoreCaseCached(File directory, String filename) {
        String dirKey = directory.getAbsolutePath();
        Map<String, File> listing = dirListingCache.computeIfAbsent(dirKey, k -> {
            File[] files = directory.listFiles();
            if (files == null) return new HashMap<>();
            Map<String, File> map = new HashMap<>(files.length);
            for (File f : files) {
                if (f.isFile()) {
                    map.put(f.getName().toLowerCase(), f);
                }
            }
            return map;
        });
        File found = listing.get(filename.toLowerCase());
        return found != null ? found.getAbsolutePath() : null;
    }

    /**
     * 그레이스케일 이미지를 InDesign 스타일로 채색한다.
     * InDesign에서 그레이스케일 이미지에 FillColor를 지정하면:
     * - 흰색(255) → 투명
     * - 검정(0) → FillTint% 불투명의 FillColor
     * - 중간 회색 → 비례적 알파
     *
     * @param pngData    원본 PNG 데이터
     * @param fillColor  IDML FillColor 문자열 (예: "Color/Black")
     * @param fillTint   FillTint 0~100 (100=완전 불투명)
     * @return 채색된 PNG 데이터, 실패 시 원본 반환
     */
    /**
     * 그레이스케일 이미지를 InDesign 스타일로 채색한다.
     * InDesign에서 그레이스케일 이미지에 FillColor를 지정하면:
     * - 흰색(255) → 투명 (흰 배경에 합성)
     * - 검정(0) → FillTint% 불투명의 FillColor (흰 배경에 합성)
     * - 중간 회색 → 비례적 블렌딩
     *
     * HWPX(한글)에서 PNG alpha 투명도를 지원하지 않을 수 있으므로,
     * 흰 배경 위에 합성한 불투명 이미지를 출력한다.
     *
     * @param pngData    원본 PNG 데이터
     * @param fillColor  IDML FillColor 문자열 (예: "Color/Black")
     * @param fillTint   FillTint 0~100 (100=완전 불투명)
     * @return 채색된 PNG 데이터, 실패 시 원본 반환
     */
    public static byte[] colorizeGrayscaleImage(byte[] pngData, String fillColor, double fillTint) {
        if (pngData == null || fillColor == null) return pngData;

        Color color = resolveFrameFillColor(fillColor);
        if (color == null) return pngData;

        double tintFactor = Math.max(0, Math.min(1, fillTint / 100.0));

        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngData));
            if (src == null) return pngData;

            int w = src.getWidth();
            int h = src.getHeight();
            // 흰 배경 위에 합성 (한글이 alpha 투명도를 무시할 수 있으므로)
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

            int cr = color.getRed();
            int cg = color.getGreen();
            int cb = color.getBlue();

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pixel = src.getRGB(x, y);
                    int srcAlpha = (pixel >> 24) & 0xFF;
                    // 그레이스케일 값 (R=G=B)
                    int gray = (pixel >> 16) & 0xFF;
                    // InDesign 규칙: 흰색=투명, 검정=불투명
                    double opacity = (255.0 - gray) / 255.0 * tintFactor * (srcAlpha / 255.0);
                    // 흰 배경(255) 위에 fillColor를 opacity로 합성
                    int outR = (int) Math.round(cr * opacity + 255 * (1 - opacity));
                    int outG = (int) Math.round(cg * opacity + 255 * (1 - opacity));
                    int outB = (int) Math.round(cb * opacity + 255 * (1 - opacity));
                    outR = Math.max(0, Math.min(255, outR));
                    outG = Math.max(0, Math.min(255, outG));
                    outB = Math.max(0, Math.min(255, outB));
                    // 불투명 픽셀로 출력 (흰 부분은 완전 투명으로 처리)
                    int outAlpha = opacity > 0.001 ? 255 : 0;
                    dst.setRGB(x, y, (outAlpha << 24) | (outR << 16) | (outG << 8) | outB);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(dst, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            return pngData;
        }
    }
}
