package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConvertOptions;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape;
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
import java.util.List;

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

        if (hasRotation) {
            return applyClippingRotated(srcImage, imageTransform,
                    fLeft, fTop, fRight, fBottom, frameW, frameH,
                    gLeft, gTop, pxPerPtX, pxPerPtY);
        } else {
            return applyClippingSimple(srcImage, imageTransform,
                    fLeft, fTop, fRight, fBottom, frameW, frameH,
                    gLeft, gTop, pxPerPtX, pxPerPtY);
        }
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

        if (sx1 >= sx2 || sy1 >= sy2) return encodePng(srcImage);

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

        // Flip 처리: 음수 스케일 = 이미지 반전
        int ax1 = sx1, ax2 = sx2, ay1 = sy1, ay2 = sy2;
        if (imgScaleX < 0) { ax1 = srcImage.getWidth() - sx2; ax2 = srcImage.getWidth() - sx1; }
        if (imgScaleY < 0) { ay1 = srcImage.getHeight() - sy2; ay2 = srcImage.getHeight() - sy1; }

        g.drawImage(srcImage, dx1, dy1, dx2, dy2, ax1, ay1, ax2, ay2, null);
        g.dispose();

        return encodePng(clipped);
    }

    /**
     * 회전된 이미지 클리핑.
     * imageTransform에 회전 성분(b, c ≠ 0)이 있는 경우 2×2 역행렬을 사용.
     */
    private byte[] applyClippingRotated(BufferedImage srcImage, double[] imageTransform,
                                         double fLeft, double fTop, double fRight, double fBottom,
                                         double frameW, double frameH,
                                         double gLeft, double gTop,
                                         double pxPerPtX, double pxPerPtY) throws IOException {
        double a = imageTransform[0], b = imageTransform[1];
        double c = imageTransform[2], d = imageTransform[3];
        double tx = imageTransform[4], ty = imageTransform[5];

        // 2×2 역행렬: [a b; c d]^-1 = (1/det) * [d -b; -c a]
        double det = a * d - b * c;
        if (Math.abs(det) < 1e-10) return encodePng(srcImage);

        double invA = d / det, invB = -b / det;
        double invC = -c / det, invD = a / det;

        // 프레임 4코너를 그래픽 좌표로 역변환
        // graphicPt = inv * (framePt - [tx, ty]) + [gLeft, gTop]
        double[][] frameCorners = {
            {fLeft, fTop}, {fRight, fTop}, {fLeft, fBottom}, {fRight, fBottom}
        };
        double minGX = Double.MAX_VALUE, maxGX = -Double.MAX_VALUE;
        double minGY = Double.MAX_VALUE, maxGY = -Double.MAX_VALUE;
        for (double[] corner : frameCorners) {
            double dx = corner[0] - tx;
            double dy = corner[1] - ty;
            double gx = invA * dx + invC * dy + gLeft;
            double gy = invB * dx + invD * dy + gTop;
            minGX = Math.min(minGX, gx);
            maxGX = Math.max(maxGX, gx);
            minGY = Math.min(minGY, gy);
            maxGY = Math.max(maxGY, gy);
        }

        // 그래픽 좌표를 픽셀 좌표로 변환 (클램프 전 원본 보존)
        double rawSrcL = (minGX - gLeft) * pxPerPtX;
        double rawSrcT = (minGY - gTop) * pxPerPtY;
        double rawSrcR = (maxGX - gLeft) * pxPerPtX;
        double rawSrcB = (maxGY - gTop) * pxPerPtY;

        int sx1 = Math.max(0, (int) Math.floor(rawSrcL));
        int sy1 = Math.max(0, (int) Math.floor(rawSrcT));
        int sx2 = Math.min(srcImage.getWidth(), (int) Math.ceil(rawSrcR));
        int sy2 = Math.min(srcImage.getHeight(), (int) Math.ceil(rawSrcB));

        if (sx1 >= sx2 || sy1 >= sy2) return encodePng(srcImage);

        int targetDpi = options.imageDpi();
        int pixW = Math.max(10, (int) Math.ceil(frameW * targetDpi / 72.0));
        int pixH = Math.max(10, (int) Math.ceil(frameH * targetDpi / 72.0));

        // 클램프에 의해 잘린 영역을 반영한 목적지 좌표 계산
        double rawW = rawSrcR - rawSrcL;
        double rawH = rawSrcB - rawSrcT;
        int dx1 = (rawW > 0) ? (int) Math.round((sx1 - rawSrcL) / rawW * pixW) : 0;
        int dy1 = (rawH > 0) ? (int) Math.round((sy1 - rawSrcT) / rawH * pixH) : 0;
        int dx2 = (rawW > 0) ? (int) Math.round((sx2 - rawSrcL) / rawW * pixW) : pixW;
        int dy2 = (rawH > 0) ? (int) Math.round((sy2 - rawSrcT) / rawH * pixH) : pixH;

        BufferedImage clipped = new BufferedImage(pixW, pixH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clipped.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(srcImage, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null);
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
        double wPts = shape.widthPoints();
        double hPts = shape.heightPoints();
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
        String actualFillHex = shape.hasClippedChild() ? null : fillColorHex; // 클리핑 프레임은 채우기 없음
        String actualStrokeHex = shape.hasClippedChild() ? null : strokeColorHex;
        if (shape.hasClippedChild()) {
            // 클리핑: 부모 경계로 클립 설정
            Shape clipShape = createAwtShape(shape, scale);
            g.setClip(clipShape);
            // 자식의 색상 사용 — Stage4에서 전달된 것이 아닌 자식 자체의 색상은 여기서 처리 불가
            // → fillColorHex/strokeColorHex에 자식 색상이 이미 전달된다고 가정
            actualFillHex = fillColorHex;
            actualStrokeHex = strokeColorHex;
            renderTarget = shape.clippedChild();
        }

        Shape awtShape = createAwtShape(renderTarget, scale);

        // 채우기
        if (actualFillHex != null && renderTarget.hasFill()) {
            Color fillColor = hexToColor(actualFillHex);
            if (fillColor != null) {
                float fillAlpha = alpha * (float) (renderTarget.fillTint() / 100.0);
                g.setColor(withAlpha(fillColor, fillAlpha));
                g.fill(awtShape);
            }
        }

        // 선
        if (actualStrokeHex != null && renderTarget.hasStroke()) {
            Color strokeColor = hexToColor(actualStrokeHex);
            if (strokeColor != null) {
                float strokeAlpha = alpha * (float) (renderTarget.strokeTint() / 100.0);
                g.setColor(withAlpha(strokeColor, strokeAlpha));

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
            g.setClip(transformedClip);
        }

        // 채우기
        if (actualFillHex != null && renderTarget.hasFill()) {
            Color fillColor = hexToColor(actualFillHex);
            if (fillColor != null) {
                float fillAlpha = alpha * (float) (renderTarget.fillTint() / 100.0);
                g.setColor(withAlpha(fillColor, fillAlpha));
                g.fill(transformedShape);
            }
        }

        // 선
        if (actualStrokeHex != null && renderTarget.hasStroke()) {
            Color strokeColor = hexToColor(actualStrokeHex);
            if (strokeColor != null) {
                float strokeAlpha = alpha * (float) (renderTarget.strokeTint() / 100.0);
                g.setColor(withAlpha(strokeColor, strokeAlpha));

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

    private Shape createAwtShape(IDMLVectorShape shape, double scale) {
        double w = shape.widthPoints() * scale;
        double h = shape.heightPoints() * scale;

        if (shape.shapeType() == null) {
            return new Rectangle2D.Double(0, 0, w, h);
        }

        switch (shape.shapeType()) {
            case RECTANGLE:
                if (shape.hasRoundedCorners()) {
                    double r = shape.cornerRadius() * scale;
                    return new RoundRectangle2D.Double(0, 0, w, h, r * 2, r * 2);
                }
                return new Rectangle2D.Double(0, 0, w, h);

            case OVAL:
                return new Ellipse2D.Double(0, 0, w, h);

            case POLYGON:
            case GRAPHIC_LINE:
                return buildPathFromPoints(shape, scale);

            default:
                return new Rectangle2D.Double(0, 0, w, h);
        }
    }

    private Shape buildPathFromPoints(IDMLVectorShape shape, double scale) {
        double[] bounds = shape.geometricBounds();
        double offX = (bounds != null) ? -bounds[1] : 0;
        double offY = (bounds != null) ? -bounds[0] : 0;

        GeneralPath path = new GeneralPath();

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

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
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
