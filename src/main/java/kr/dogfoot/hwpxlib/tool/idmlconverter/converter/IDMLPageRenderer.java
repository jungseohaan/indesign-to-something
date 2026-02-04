package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.imageinserter.DesignFileConverter;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * IDML 페이지를 PNG 이미지로 렌더링한다.
 * 벡터 도형과 이미지를 하나의 투명 PNG로 합성한다.
 */
public class IDMLPageRenderer {

    private final IDMLDocument idmlDoc;
    private final Map<String, String> colorMap;  // colorRef -> "#RRGGBB"
    private final int dpi;

    public IDMLPageRenderer(IDMLDocument idmlDoc, int dpi) {
        this.idmlDoc = idmlDoc;
        this.colorMap = idmlDoc.colors();
        this.dpi = dpi;
    }

    /**
     * 페이지를 PNG로 렌더링한다.
     *
     * @param spread 스프레드
     * @param page   페이지
     * @param linksDirectory 이미지 파일 검색 디렉토리 (옵션)
     * @return PNG 바이트 배열
     */
    public byte[] renderPage(IDMLSpread spread, IDMLPage page, String linksDirectory) throws IOException {
        // 페이지 크기 (points)
        double pageWidthPt = page.widthPoints();
        double pageHeightPt = page.heightPoints();

        // 픽셀 크기 (DPI 기준)
        int pixelWidth = (int) Math.ceil(pageWidthPt * dpi / 72.0);
        int pixelHeight = (int) Math.ceil(pageHeightPt * dpi / 72.0);

        // 스케일 (points → pixels)
        double scale = dpi / 72.0;

        // 투명 배경 이미지 생성
        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        // 안티앨리어싱 설정
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 페이지 좌표계 변환 설정
        // 페이지의 절대 좌표를 (0,0) 기준으로 변환
        double[] pageTransform = page.itemTransform();
        double pageTx = pageTransform != null ? pageTransform[4] : 0;
        double pageTy = pageTransform != null ? pageTransform[5] : 0;

        // 1. 벡터 도형 렌더링 (z-order 낮음)
        List<IDMLVectorShape> shapes = spread.getVectorShapesOnPage(page);
        for (IDMLVectorShape shape : shapes) {
            renderVectorShape(g, shape, pageTx, pageTy, scale);
        }

        // 2. 이미지 렌더링
        List<IDMLImageFrame> images = spread.getImageFramesOnPage(page);
        for (IDMLImageFrame imgFrame : images) {
            renderImage(g, imgFrame, pageTx, pageTy, scale, linksDirectory);
        }

        g.dispose();

        // PNG로 인코딩
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 렌더링 결과를 담는 클래스.
     * PNG 데이터와 페이지 내 위치/크기 정보를 포함한다.
     */
    public static class RenderResult {
        private final byte[] pngData;
        private final double x;      // 페이지 내 X 위치 (points)
        private final double y;      // 페이지 내 Y 위치 (points)
        private final double width;  // 너비 (points)
        private final double height; // 높이 (points)
        private final int pixelWidth;
        private final int pixelHeight;

        public RenderResult(byte[] pngData, double x, double y, double width, double height,
                            int pixelWidth, int pixelHeight) {
            this.pngData = pngData;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
        }

        public byte[] pngData() { return pngData; }
        public double x() { return x; }
        public double y() { return y; }
        public double width() { return width; }
        public double height() { return height; }
        public int pixelWidth() { return pixelWidth; }
        public int pixelHeight() { return pixelHeight; }
    }

    /**
     * 단일 벡터 도형을 투명 PNG로 렌더링한다.
     *
     * @param shape 벡터 도형
     * @param page  페이지 (좌표 변환용)
     * @return 렌더링 결과 (PNG + 위치 정보), 실패 시 null
     */
    public RenderResult renderVectorToPng(IDMLVectorShape shape, IDMLPage page) throws IOException {
        double[] bounds = shape.geometricBounds();
        double[] transform = shape.itemTransform();
        if (bounds == null || transform == null) return null;

        // 페이지 절대 좌표 계산 (전체 아핀 변환 적용)
        double[] pageBounds = page.geometricBounds();
        double[] pageTransform = page.itemTransform();
        double[] pageAbs = (pageBounds != null && pageTransform != null)
                ? applyTransform(pageTransform, pageBounds[1], pageBounds[0])
                : new double[]{0, 0};

        // 도형의 절대 좌표 계산 (전체 아핀 변환 적용)
        double[] shapeAbs = applyTransform(transform, bounds[1], bounds[0]);

        // 페이지 상대 좌표 = 도형 절대좌표 - 페이지 절대좌표
        double shapeX = shapeAbs[0] - pageAbs[0];
        double shapeY = shapeAbs[1] - pageAbs[1];
        double shapeW = bounds[3] - bounds[1];
        double shapeH = bounds[2] - bounds[0];

        // PathPoint 또는 SubPath가 있으면 실제 bounding box 계산
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean hasPoints = false;

        // SubPath가 있는 경우 (복잡한 글리프)
        if (shape.hasSubPaths()) {
            for (IDMLVectorShape.SubPath subPath : shape.subPaths()) {
                for (IDMLVectorShape.PathPoint pt : subPath.points()) {
                    double[] local = applyTransform(transform, pt.anchorX(), pt.anchorY());
                    double px = local[0] - pageAbs[0];
                    double py = local[1] - pageAbs[1];
                    minX = Math.min(minX, px);
                    minY = Math.min(minY, py);
                    maxX = Math.max(maxX, px);
                    maxY = Math.max(maxY, py);
                    hasPoints = true;

                    // 베지어 컨트롤 포인트도 고려 (곡선이 경계를 넘을 수 있음)
                    if (!pt.isStraight()) {
                        double[] left = applyTransform(transform, pt.leftX(), pt.leftY());
                        double[] right = applyTransform(transform, pt.rightX(), pt.rightY());
                        minX = Math.min(minX, Math.min(left[0], right[0]) - pageAbs[0]);
                        minY = Math.min(minY, Math.min(left[1], right[1]) - pageAbs[1]);
                        maxX = Math.max(maxX, Math.max(left[0], right[0]) - pageAbs[0]);
                        maxY = Math.max(maxY, Math.max(left[1], right[1]) - pageAbs[1]);
                    }
                }
            }
        }

        // 기본 PathPoint가 있는 경우
        List<IDMLVectorShape.PathPoint> points = shape.pathPoints();
        if (!points.isEmpty()) {
            for (IDMLVectorShape.PathPoint pt : points) {
                double[] local = applyTransform(transform, pt.anchorX(), pt.anchorY());
                double px = local[0] - pageAbs[0];
                double py = local[1] - pageAbs[1];
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
                hasPoints = true;

                // 베지어 컨트롤 포인트도 고려
                if (!pt.isStraight()) {
                    double[] left = applyTransform(transform, pt.leftX(), pt.leftY());
                    double[] right = applyTransform(transform, pt.rightX(), pt.rightY());
                    minX = Math.min(minX, Math.min(left[0], right[0]) - pageAbs[0]);
                    minY = Math.min(minY, Math.min(left[1], right[1]) - pageAbs[1]);
                    maxX = Math.max(maxX, Math.max(left[0], right[0]) - pageAbs[0]);
                    maxY = Math.max(maxY, Math.max(left[1], right[1]) - pageAbs[1]);
                }
            }
        }

        // 포인트가 있으면 계산된 bounding box 사용
        if (hasPoints && minX < Double.MAX_VALUE) {
            shapeX = minX;
            shapeY = minY;
            shapeW = maxX - minX;
            shapeH = maxY - minY;
        }

        // 크기가 너무 작으면 스킵 (단, 0이 아니면 최소 크기 보장)
        if (shapeW < 0.1 && shapeH < 0.1) return null;
        if (shapeW < 1) shapeW = 1;
        if (shapeH < 1) shapeH = 1;

        // 여백 추가 (stroke 등을 위해)
        double margin = Math.max(shape.strokeWeight() * 2, 2);
        double renderX = shapeX - margin;
        double renderY = shapeY - margin;
        double renderW = shapeW + margin * 2;
        double renderH = shapeH + margin * 2;

        // 픽셀 크기
        double scale = dpi / 72.0;
        int pixelWidth = (int) Math.ceil(renderW * scale);
        int pixelHeight = (int) Math.ceil(renderH * scale);

        if (pixelWidth < 1 || pixelHeight < 1) return null;

        // 투명 PNG 생성
        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 렌더링 좌표 변환: 도형을 (0,0) 기준으로 그리기
        // renderX, renderY를 기준점으로 사용
        double offsetX = pageAbs[0] + renderX;
        double offsetY = pageAbs[1] + renderY;

        renderVectorShape(g, shape, offsetX, offsetY, scale);
        g.dispose();

        // PNG 인코딩
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);

        return new RenderResult(baos.toByteArray(), renderX, renderY, renderW, renderH,
                pixelWidth, pixelHeight);
    }

    /**
     * 단일 이미지 프레임을 투명 PNG로 렌더링한다 (클리핑 적용).
     *
     * @param imgFrame 이미지 프레임
     * @param page     페이지 (좌표 변환용)
     * @param linksDirectory 이미지 파일 검색 디렉토리
     * @return 렌더링 결과 (PNG + 위치 정보), 실패 시 null
     */
    public RenderResult renderImageToPng(IDMLImageFrame imgFrame, IDMLPage page,
                                          String linksDirectory) throws IOException {
        BufferedImage srcImage = loadImage(imgFrame, linksDirectory);
        if (srcImage == null) return null;

        double[] frameBounds = imgFrame.geometricBounds();
        double[] frameTransform = imgFrame.itemTransform();
        double[] imageTransform = imgFrame.imageTransform();
        if (frameBounds == null || frameTransform == null) return null;

        // 페이지 절대 좌표 계산 (전체 아핀 변환 적용)
        double[] pageBounds = page.geometricBounds();
        double[] pageTransform = page.itemTransform();
        double[] pageAbs = (pageBounds != null && pageTransform != null)
                ? applyTransform(pageTransform, pageBounds[1], pageBounds[0])
                : new double[]{0, 0};

        // 프레임의 절대 좌표 계산 (전체 아핀 변환 적용)
        double[] frameAbs = applyTransform(frameTransform, frameBounds[1], frameBounds[0]);

        // 페이지 상대 좌표 = 프레임 절대좌표 - 페이지 절대좌표
        double frameX = frameAbs[0] - pageAbs[0];
        double frameY = frameAbs[1] - pageAbs[1];
        double frameW = frameBounds[3] - frameBounds[1];
        double frameH = frameBounds[2] - frameBounds[0];

        if (frameW < 0.5 || frameH < 0.5) return null;

        // 픽셀 크기
        double scale = dpi / 72.0;
        int pixelWidth = (int) Math.ceil(frameW * scale);
        int pixelHeight = (int) Math.ceil(frameH * scale);

        if (pixelWidth < 1 || pixelHeight < 1) return null;

        // 이미지 변환 정보
        double imgScaleX = 1.0, imgScaleY = 1.0;
        double imgTx = 0, imgTy = 0;
        if (imageTransform != null && imageTransform.length >= 6) {
            imgScaleX = imageTransform[0];
            imgScaleY = imageTransform[3];
            imgTx = imageTransform[4];
            imgTy = imageTransform[5];
        }

        // graphicBounds = [left, top, right, bottom] - 이미지 콘텐츠의 좌표계 범위 (points 단위)
        // imageTransform은 graphicBounds 원점을 (imgTx, imgTy)에 배치함
        double[] graphicBounds = imgFrame.graphicBounds();
        double gLeft = 0, gTop = 0, gRight = srcImage.getWidth(), gBottom = srcImage.getHeight();
        if (graphicBounds != null && graphicBounds.length >= 4) {
            gLeft = graphicBounds[0];
            gTop = graphicBounds[1];
            gRight = graphicBounds[2];
            gBottom = graphicBounds[3];
        }

        // 이미지 스케일 (source pixel -> points)
        double absScaleX = Math.abs(imgScaleX);
        double absScaleY = Math.abs(imgScaleY);
        if (absScaleX < 0.001) absScaleX = 1.0;
        if (absScaleY < 0.001) absScaleY = 1.0;

        // 그래픽 좌표 (points) → 소스 픽셀 변환 스케일
        // graphicBounds의 범위 (points)가 srcImage의 픽셀 범위에 해당
        double graphicW = gRight - gLeft;
        double graphicH = gBottom - gTop;
        double pointsToPixelX = (graphicW > 0) ? srcImage.getWidth() / graphicW : 1.0;
        double pointsToPixelY = (graphicH > 0) ? srcImage.getHeight() / graphicH : 1.0;

        // 프레임 로컬 좌표계에서 클리핑 영역 계산
        // frameBounds = [top, left, bottom, right] - 프레임 로컬 좌표
        // imageTransform의 tx, ty는 프레임 로컬 좌표계에서의 이미지 원점 위치
        // graphic_pos = (frame_local_pos - imgTx) / imgScale + gLeft
        double frameLeft = frameBounds[1];
        double frameTop = frameBounds[0];
        double frameRight = frameBounds[3];
        double frameBottom = frameBounds[2];

        double graphicLeft = (frameLeft - imgTx) / imgScaleX + gLeft;
        double graphicTop = (frameTop - imgTy) / imgScaleY + gTop;
        double graphicRight = (frameRight - imgTx) / imgScaleX + gLeft;
        double graphicBottom = (frameBottom - imgTy) / imgScaleY + gTop;

        // 그래픽 좌표 (points)를 소스 픽셀 좌표로 변환
        double srcLeft = (graphicLeft - gLeft) * pointsToPixelX;
        double srcTop = (graphicTop - gTop) * pointsToPixelY;
        double srcRight = (graphicRight - gLeft) * pointsToPixelX;
        double srcBottom = (graphicBottom - gTop) * pointsToPixelY;

        // 소스 이미지 범위로 클리핑
        int srcX1 = Math.max(0, (int) Math.floor(srcLeft));
        int srcY1 = Math.max(0, (int) Math.floor(srcTop));
        int srcX2 = Math.min(srcImage.getWidth(), (int) Math.ceil(srcRight));
        int srcY2 = Math.min(srcImage.getHeight(), (int) Math.ceil(srcBottom));

        // 유효한 영역이 있는지 확인
        if (srcX1 >= srcX2 || srcY1 >= srcY2) {
            return null;  // 이미지가 프레임 영역과 겹치지 않음
        }

        // 투명 PNG 생성
        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 대상 영역 계산 (출력 이미지 내 위치, 픽셀)
        // 수정: 그래픽 좌표 → 소스 픽셀 변환을 고려한 스케일
        double srcToOutputScaleX = absScaleX / pointsToPixelX * scale;
        double srcToOutputScaleY = absScaleY / pointsToPixelY * scale;
        double dstX1 = (srcX1 - srcLeft) * srcToOutputScaleX;
        double dstY1 = (srcY1 - srcTop) * srcToOutputScaleY;
        double dstX2 = dstX1 + (srcX2 - srcX1) * srcToOutputScaleX;
        double dstY2 = dstY1 + (srcY2 - srcY1) * srcToOutputScaleY;

        // 이미지 반전 처리
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

        // 이미지 그리기
        g.drawImage(srcImage,
                (int) dstX1, (int) dstY1, (int) dstX2, (int) dstY2,
                actualSrcX1, actualSrcY1, actualSrcX2, actualSrcY2, null);

        g.dispose();

        // PNG 인코딩
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);

        return new RenderResult(baos.toByteArray(), frameX, frameY, frameW, frameH,
                pixelWidth, pixelHeight);
    }

    /**
     * 벡터 도형을 렌더링한다.
     */
    private void renderVectorShape(Graphics2D g, IDMLVectorShape shape,
                                    double pageTx, double pageTy, double scale) {
        double[] transform = shape.itemTransform();

        // SubPath가 있으면 먼저 처리 (PathPoints보다 우선)
        if (shape.hasSubPaths()) {
            // 닫힌 SubPath들을 모아서 하나의 경로로 (Even-Odd fill rule 적용)
            GeneralPath closedPath = new GeneralPath(GeneralPath.WIND_EVEN_ODD);
            boolean hasClosedPaths = false;

            // 열린 SubPath들은 개별적으로 stroke만
            List<GeneralPath> openPaths = new java.util.ArrayList<>();

            for (IDMLVectorShape.SubPath subPath : shape.subPaths()) {
                List<IDMLVectorShape.PathPoint> subPoints = subPath.points();
                if (subPoints.isEmpty()) continue;

                GeneralPath targetPath;
                if (subPath.isOpen()) {
                    targetPath = new GeneralPath();
                    openPaths.add(targetPath);
                } else {
                    targetPath = closedPath;
                    hasClosedPaths = true;
                }

                boolean first = true;
                for (int i = 0; i < subPoints.size(); i++) {
                    IDMLVectorShape.PathPoint pt = subPoints.get(i);

                    double[] local = applyTransform(transform, pt.anchorX(), pt.anchorY());
                    double px = (local[0] - pageTx) * scale;
                    double py = (local[1] - pageTy) * scale;

                    if (first) {
                        targetPath.moveTo(px, py);
                        first = false;
                    } else {
                        IDMLVectorShape.PathPoint prevPt = subPoints.get(i - 1);
                        if (prevPt.isStraight() && pt.isStraight()) {
                            targetPath.lineTo(px, py);
                        } else {
                            double[] ctrl1 = applyTransform(transform, prevPt.rightX(), prevPt.rightY());
                            double[] ctrl2 = applyTransform(transform, pt.leftX(), pt.leftY());
                            double c1x = (ctrl1[0] - pageTx) * scale;
                            double c1y = (ctrl1[1] - pageTy) * scale;
                            double c2x = (ctrl2[0] - pageTx) * scale;
                            double c2y = (ctrl2[1] - pageTy) * scale;
                            targetPath.curveTo(c1x, c1y, c2x, c2y, px, py);
                        }
                    }
                }

                // 닫힌 SubPath인 경우 경로 닫기
                if (!subPath.isOpen() && subPoints.size() > 2) {
                    IDMLVectorShape.PathPoint lastPt = subPoints.get(subPoints.size() - 1);
                    IDMLVectorShape.PathPoint firstPt = subPoints.get(0);

                    if (lastPt.isStraight() && firstPt.isStraight()) {
                        targetPath.closePath();
                    } else {
                        double[] ctrl1 = applyTransform(transform, lastPt.rightX(), lastPt.rightY());
                        double[] ctrl2 = applyTransform(transform, firstPt.leftX(), firstPt.leftY());
                        double[] end = applyTransform(transform, firstPt.anchorX(), firstPt.anchorY());
                        double c1x = (ctrl1[0] - pageTx) * scale;
                        double c1y = (ctrl1[1] - pageTy) * scale;
                        double c2x = (ctrl2[0] - pageTx) * scale;
                        double c2y = (ctrl2[1] - pageTy) * scale;
                        double ex = (end[0] - pageTx) * scale;
                        double ey = (end[1] - pageTy) * scale;
                        targetPath.curveTo(c1x, c1y, c2x, c2y, ex, ey);
                        targetPath.closePath();
                    }
                }
            }

            // 닫힌 경로들 렌더링 (Even-Odd fill로 내부 구멍 처리)
            if (hasClosedPaths) {
                fillAndStroke(g, shape, closedPath, true);
            }

            // 열린 경로들 렌더링 (stroke만)
            for (GeneralPath openPath : openPaths) {
                fillAndStroke(g, shape, openPath, false);
            }

            return;  // SubPath 처리 완료 후 종료
        }

        // SubPath가 없으면 PathPoints로 처리
        List<IDMLVectorShape.PathPoint> points = shape.pathPoints();

        // 단일 점 도형은 스킵
        if (points.size() == 1) {
            return;
        }

        // PathPoints도 없으면 geometricBounds로 사각형 그리기
        if (points.isEmpty()) {
            double[] bounds = shape.geometricBounds();
            if (bounds == null) return;

            double boundsW = bounds[3] - bounds[1];
            double boundsH = bounds[2] - bounds[0];
            if (boundsW < 0.1 && boundsH < 0.1) return;

            double x = (bounds[1] + transform[4] - pageTx) * scale;
            double y = (bounds[0] + transform[5] - pageTy) * scale;
            double w = boundsW * scale;
            double h = boundsH * scale;

            Shape rect;
            if (shape.hasRoundedCorners()) {
                double r = shape.cornerRadius() * scale;
                rect = new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2);
            } else {
                rect = new Rectangle2D.Double(x, y, w, h);
            }

            fillAndStroke(g, shape, rect);
            return;
        }

        // PathPoints로 경로 생성
        GeneralPath path = new GeneralPath();
        if (points.size() > 0) {
            boolean first = true;
            for (int i = 0; i < points.size(); i++) {
                IDMLVectorShape.PathPoint pt = points.get(i);

                double[] local = applyTransform(transform, pt.anchorX(), pt.anchorY());
                double px = (local[0] - pageTx) * scale;
                double py = (local[1] - pageTy) * scale;

                if (first) {
                    path.moveTo(px, py);
                    first = false;
                } else {
                    IDMLVectorShape.PathPoint prevPt = points.get(i - 1);
                    if (prevPt.isStraight() && pt.isStraight()) {
                        path.lineTo(px, py);
                    } else {
                        double[] ctrl1 = applyTransform(transform, prevPt.rightX(), prevPt.rightY());
                        double[] ctrl2 = applyTransform(transform, pt.leftX(), pt.leftY());
                        double c1x = (ctrl1[0] - pageTx) * scale;
                        double c1y = (ctrl1[1] - pageTy) * scale;
                        double c2x = (ctrl2[0] - pageTx) * scale;
                        double c2y = (ctrl2[1] - pageTy) * scale;
                        path.curveTo(c1x, c1y, c2x, c2y, px, py);
                    }
                }
            }

            // 닫힌 경로인 경우
            if (!shape.pathOpen() && points.size() > 2) {
                IDMLVectorShape.PathPoint lastPt = points.get(points.size() - 1);
                IDMLVectorShape.PathPoint firstPt = points.get(0);

                if (lastPt.isStraight() && firstPt.isStraight()) {
                    path.closePath();
                } else {
                    double[] ctrl1 = applyTransform(transform, lastPt.rightX(), lastPt.rightY());
                    double[] ctrl2 = applyTransform(transform, firstPt.leftX(), firstPt.leftY());
                    double[] end = applyTransform(transform, firstPt.anchorX(), firstPt.anchorY());
                    double c1x = (ctrl1[0] - pageTx) * scale;
                    double c1y = (ctrl1[1] - pageTy) * scale;
                    double c2x = (ctrl2[0] - pageTx) * scale;
                    double c2y = (ctrl2[1] - pageTy) * scale;
                    double ex = (end[0] - pageTx) * scale;
                    double ey = (end[1] - pageTy) * scale;
                    path.curveTo(c1x, c1y, c2x, c2y, ex, ey);
                    path.closePath();
                }
            }
        }

        fillAndStroke(g, shape, path);
    }

    /**
     * 도형을 채우기와 선으로 그린다.
     */
    private void fillAndStroke(Graphics2D g, IDMLVectorShape shape, Shape path) {
        fillAndStroke(g, shape, path, true);  // 기본: fill 허용
    }

    /**
     * 도형을 채우기와 선으로 그린다.
     * @param allowFill false이면 fill을 하지 않음 (열린 경로용)
     */
    private void fillAndStroke(Graphics2D g, IDMLVectorShape shape, Shape path, boolean allowFill) {
        // 채우기 (닫힌 경로만)
        if (allowFill && shape.hasFill()) {
            Color fillColor = resolveColor(shape.fillColor());
            if (fillColor != null) {
                g.setColor(fillColor);
                g.fill(path);
            }
        }

        // 선
        if (shape.hasStroke()) {
            Color strokeColor = resolveColor(shape.strokeColor());
            if (strokeColor != null) {
                g.setColor(strokeColor);
                g.setStroke(createStroke(shape));
                g.draw(path);
            }
        }
    }

    /**
     * IDMLVectorShape의 stroke 속성에 따른 BasicStroke 생성.
     */
    private BasicStroke createStroke(IDMLVectorShape shape) {
        float width = (float) (shape.strokeWeight() * dpi / 72.0);

        // Line cap (Java2D: CAP_BUTT=0, CAP_ROUND=1, CAP_SQUARE=2)
        int cap;
        IDMLVectorShape.LineCap endCap = shape.endCap();
        if (endCap == IDMLVectorShape.LineCap.ROUND) {
            cap = BasicStroke.CAP_ROUND;
        } else if (endCap == IDMLVectorShape.LineCap.PROJECTING) {
            cap = BasicStroke.CAP_SQUARE;
        } else {
            cap = BasicStroke.CAP_BUTT;
        }

        // Line join (Java2D: JOIN_MITER=0, JOIN_ROUND=1, JOIN_BEVEL=2)
        int join;
        IDMLVectorShape.LineJoin lineJoin = shape.lineJoin();
        if (lineJoin == IDMLVectorShape.LineJoin.ROUND) {
            join = BasicStroke.JOIN_ROUND;
        } else if (lineJoin == IDMLVectorShape.LineJoin.BEVEL) {
            join = BasicStroke.JOIN_BEVEL;
        } else {
            join = BasicStroke.JOIN_MITER;
        }

        float miterLimit = (float) shape.miterLimit();

        // Dash pattern
        if (shape.hasDashPattern()) {
            double[] dashSrc = shape.dashPattern();
            float[] dash = new float[dashSrc.length];
            for (int i = 0; i < dashSrc.length; i++) {
                dash[i] = (float) (dashSrc[i] * dpi / 72.0);
            }
            return new BasicStroke(width, cap, join, miterLimit, dash, 0f);
        }

        return new BasicStroke(width, cap, join, miterLimit);
    }

    /**
     * 이미지를 렌더링한다.
     * 이미지 클리핑: 프레임 영역만 보이도록 소스 이미지의 일부만 그린다.
     */
    private void renderImage(Graphics2D g, IDMLImageFrame imgFrame,
                              double pageTx, double pageTy, double scale,
                              String linksDirectory) {
        // 이미지 파일 로드
        BufferedImage srcImage = loadImage(imgFrame, linksDirectory);
        if (srcImage == null) {
            System.err.println("  [WARN] Image not loaded: " + imgFrame.linkResourceURI());
            return;
        }

        double[] frameBounds = imgFrame.geometricBounds();
        double[] frameTransform = imgFrame.itemTransform();
        double[] imageTransform = imgFrame.imageTransform();

        if (frameBounds == null || frameTransform == null) return;

        // 프레임의 페이지 상대 위치 (픽셀)
        double frameX = (frameBounds[1] + frameTransform[4] - pageTx) * scale;
        double frameY = (frameBounds[0] + frameTransform[5] - pageTy) * scale;
        double frameW = (frameBounds[3] - frameBounds[1]) * scale;
        double frameH = (frameBounds[2] - frameBounds[0]) * scale;

        if (imageTransform != null && imageTransform.length >= 6) {
            // 이미지 변환 정보
            double imgScaleX = imageTransform[0];
            double imgScaleY = imageTransform[3];
            double imgTx = imageTransform[4];
            double imgTy = imageTransform[5];

            // graphicBounds = [left, top, right, bottom] - 이미지 콘텐츠의 좌표계 범위 (points 단위)
            double[] graphicBounds = imgFrame.graphicBounds();
            double gLeft = 0, gTop = 0, gRight = srcImage.getWidth(), gBottom = srcImage.getHeight();
            if (graphicBounds != null && graphicBounds.length >= 4) {
                gLeft = graphicBounds[0];
                gTop = graphicBounds[1];
                gRight = graphicBounds[2];
                gBottom = graphicBounds[3];
            }

            // 그래픽 좌표 (points) → 소스 픽셀 변환 스케일
            double graphicW = gRight - gLeft;
            double graphicH = gBottom - gTop;
            double pointsToPixelX = (graphicW > 0) ? srcImage.getWidth() / graphicW : 1.0;
            double pointsToPixelY = (graphicH > 0) ? srcImage.getHeight() / graphicH : 1.0;

            // 원본 이미지 픽셀당 화면 픽셀 수
            double pixelScaleX = Math.abs(imgScaleX) * pointsToPixelX * scale;
            double pixelScaleY = Math.abs(imgScaleY) * pointsToPixelY * scale;

            // 프레임 영역이 그래픽 좌표계에서 어디에 해당하는지 계산
            // frame_pos = (graphic_pos - gLeft) * imgScale + imgTx  (points 단위)
            // 따라서: graphic_pos = (frame_pos - imgTx) / imgScale + gLeft
            double framePosX = frameBounds[1] + frameTransform[4] - pageTx;  // frame left in page coords (points)
            double framePosY = frameBounds[0] + frameTransform[5] - pageTy;  // frame top in page coords (points)
            double graphicLeft = (framePosX - imgTx - frameTransform[4] + pageTx) / imgScaleX + gLeft;
            double graphicTop = (framePosY - imgTy - frameTransform[5] + pageTy) / imgScaleY + gTop;

            // 좌표 계산 단순화: frame bounds 기준
            graphicLeft = (frameBounds[1] - imgTx) / imgScaleX + gLeft;
            graphicTop = (frameBounds[0] - imgTy) / imgScaleY + gTop;
            double graphicRight = graphicLeft + (frameBounds[3] - frameBounds[1]) / Math.abs(imgScaleX);
            double graphicBottom = graphicTop + (frameBounds[2] - frameBounds[0]) / Math.abs(imgScaleY);

            // 그래픽 좌표 (points)를 소스 픽셀 좌표로 변환
            double srcLeft = (graphicLeft - gLeft) * pointsToPixelX;
            double srcTop = (graphicTop - gTop) * pointsToPixelY;
            double srcRight = (graphicRight - gLeft) * pointsToPixelX;
            double srcBottom = (graphicBottom - gTop) * pointsToPixelY;

            // 소스 이미지 범위로 클리핑
            int srcX1 = Math.max(0, (int) Math.floor(srcLeft));
            int srcY1 = Math.max(0, (int) Math.floor(srcTop));
            int srcX2 = Math.min(srcImage.getWidth(), (int) Math.ceil(srcRight));
            int srcY2 = Math.min(srcImage.getHeight(), (int) Math.ceil(srcBottom));

            // 유효한 영역이 있는지 확인
            if (srcX1 >= srcX2 || srcY1 >= srcY2) {
                return;  // 이미지가 프레임 영역과 겹치지 않음
            }

            // 대상 영역 계산 (프레임 내 위치)
            double dstX1 = frameX + (srcX1 - srcLeft) * pixelScaleX;
            double dstY1 = frameY + (srcY1 - srcTop) * pixelScaleY;
            double dstX2 = dstX1 + (srcX2 - srcX1) * pixelScaleX;
            double dstY2 = dstY1 + (srcY2 - srcY1) * pixelScaleY;

            // 이미지 반전 처리
            if (imgScaleX < 0) {
                int tmp = srcX1;
                srcX1 = srcImage.getWidth() - srcX2;
                srcX2 = srcImage.getWidth() - tmp;
            }
            if (imgScaleY < 0) {
                int tmp = srcY1;
                srcY1 = srcImage.getHeight() - srcY2;
                srcY2 = srcImage.getHeight() - tmp;
            }

            // 이미지 그리기 (소스 영역 -> 대상 영역)
            g.drawImage(srcImage,
                    (int) dstX1, (int) dstY1, (int) dstX2, (int) dstY2,
                    srcX1, srcY1, srcX2, srcY2, null);
        } else {
            // 변환 정보가 없으면 프레임에 맞춰 그리기
            g.drawImage(srcImage, (int) frameX, (int) frameY,
                    (int) (frameX + frameW), (int) (frameY + frameH),
                    0, 0, srcImage.getWidth(), srcImage.getHeight(), null);
        }
    }

    /**
     * 이미지 파일을 로드한다.
     */
    private BufferedImage loadImage(IDMLImageFrame imgFrame, String linksDirectory) {
        String uri = imgFrame.linkResourceURI();
        if (uri == null || uri.isEmpty()) return null;

        String path = stripFileUri(uri);
        String filename = extractFilename(path);

        // 검색 순서: linksDirectory → 절대경로 → basePath/Links/
        File imageFile = null;

        if (linksDirectory != null && filename != null) {
            File f = new File(linksDirectory, filename);
            if (f.exists()) imageFile = f;
        }

        if (imageFile == null) {
            File f = new File(path);
            if (f.exists()) imageFile = f;
        }

        if (imageFile == null && idmlDoc.basePath() != null && filename != null) {
            File linksDir = new File(idmlDoc.basePath(), "Links");
            File f = new File(linksDir, filename);
            if (f.exists()) imageFile = f;
        }

        if (imageFile == null) return null;

        try {
            String ext = getExtension(imageFile).toLowerCase();

            // 디자인 파일은 PNG로 변환
            if (isDesignFormat(ext)) {
                byte[] pngData = DesignFileConverter.convertToPng(imageFile, dpi);
                return ImageIO.read(new ByteArrayInputStream(pngData));
            } else {
                return ImageIO.read(imageFile);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 2D 아핀 변환을 적용한다.
     */
    private static double[] applyTransform(double[] transform, double x, double y) {
        if (transform == null || transform.length < 6) {
            return new double[]{x, y};
        }
        double a = transform[0], b = transform[1];
        double c = transform[2], d = transform[3];
        double tx = transform[4], ty = transform[5];

        return new double[]{
                a * x + c * y + tx,
                b * x + d * y + ty
        };
    }

    /**
     * 색상 참조를 Color 객체로 변환한다.
     */
    private Color resolveColor(String colorRef) {
        if (colorRef == null || colorRef.isEmpty()) return null;

        // 기본 색상 처리
        String colorName = colorRef;
        if (colorRef.startsWith("Color/")) {
            colorName = colorRef.substring("Color/".length());
        } else if (colorRef.startsWith("Swatch/")) {
            colorName = colorRef.substring("Swatch/".length());
        }

        // 기본 색상 폴백
        if ("Black".equalsIgnoreCase(colorName)) {
            return Color.BLACK;
        } else if ("Paper".equalsIgnoreCase(colorName) || "White".equalsIgnoreCase(colorName)) {
            return Color.WHITE;
        } else if ("None".equalsIgnoreCase(colorName) || "[None]".equals(colorName)) {
            return null;
        }

        // CMYK 형태 색상 파싱 (예: "C=80 M=15 Y=50 K=0")
        if (colorName.contains("C=") && colorName.contains("M=")) {
            return parseCMYKColor(colorName);
        }

        String hex = colorMap.get(colorRef);
        if (hex == null) {
            hex = colorMap.get(colorName);
        }

        if (hex != null && hex.startsWith("#") && hex.length() == 7) {
            try {
                int r = Integer.parseInt(hex.substring(1, 3), 16);
                int g = Integer.parseInt(hex.substring(3, 5), 16);
                int b = Integer.parseInt(hex.substring(5, 7), 16);
                return new Color(r, g, b);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        // colorMap에 없으면 기본 회색으로 폴백 (디버깅용)
        return null;
    }

    /**
     * CMYK 색상 문자열을 RGB Color로 변환한다.
     * 예: "C=80 M=15 Y=50 K=0"
     */
    private Color parseCMYKColor(String cmykStr) {
        try {
            double c = 0, m = 0, y = 0, k = 0;
            String[] parts = cmykStr.split("\\s+");
            for (String part : parts) {
                if (part.startsWith("C=")) {
                    c = Double.parseDouble(part.substring(2)) / 100.0;
                } else if (part.startsWith("M=")) {
                    m = Double.parseDouble(part.substring(2)) / 100.0;
                } else if (part.startsWith("Y=")) {
                    y = Double.parseDouble(part.substring(2)) / 100.0;
                } else if (part.startsWith("K=")) {
                    k = Double.parseDouble(part.substring(2)) / 100.0;
                }
            }
            // CMYK to RGB
            int r = (int) (255 * (1 - c) * (1 - k));
            int g = (int) (255 * (1 - m) * (1 - k));
            int b = (int) (255 * (1 - y) * (1 - k));
            return new Color(
                    Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, b))
            );
        } catch (Exception e) {
            return null;
        }
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
            // ignore
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

    private static String getExtension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    private static boolean isDesignFormat(String ext) {
        return "psd".equals(ext) || "ai".equals(ext) || "eps".equals(ext)
                || "pdf".equals(ext) || "tiff".equals(ext) || "tif".equals(ext);
    }
}
