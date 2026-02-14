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
import java.util.ArrayList;
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
        return renderPage(spread, page, linksDirectory, false);
    }

    /**
     * 페이지를 PNG로 렌더링한다.
     *
     * @param spread 스프레드
     * @param page   페이지
     * @param linksDirectory 이미지 파일 검색 디렉토리 (옵션)
     * @param drawPageBoundary 페이지 경계선 그리기 여부
     * @return PNG 바이트 배열
     */
    public byte[] renderPage(IDMLSpread spread, IDMLPage page, String linksDirectory,
                              boolean drawPageBoundary) throws IOException {
        return renderPage(spread, page, linksDirectory, drawPageBoundary, false);
    }

    /**
     * 페이지를 PNG로 렌더링한다.
     *
     * @param spread 스프레드
     * @param page   페이지
     * @param linksDirectory 이미지 파일 검색 디렉토리 (옵션)
     * @param drawPageBoundary 페이지 경계선 그리기 여부
     * @param includeGroupItems 그룹에서 추출된 항목도 포함 (프리뷰 렌더링용)
     * @return PNG 바이트 배열
     */
    public byte[] renderPage(IDMLSpread spread, IDMLPage page, String linksDirectory,
                              boolean drawPageBoundary, boolean includeGroupItems) throws IOException {
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
        // geometricBounds + itemTransform을 적용하여 실제 절대 좌표 계산
        double[] pageBounds = page.geometricBounds();
        double[] pageTransform = page.itemTransform();
        double[] pageAbs = IDMLGeometry.absoluteTopLeft(pageBounds, pageTransform);
        double pageTx = pageAbs[0];
        double pageTy = pageAbs[1];

        // 페이지 경계로 클리핑 (페이지 밖으로 확장되는 stroke 처리)
        g.setClip(0, 0, pixelWidth, pixelHeight);

        // 마스터 페이지 아이템을 먼저 렌더링 (배경 레이어)
        String masterSpreadId = page.appliedMasterSpread();
        if (masterSpreadId != null) {
            IDMLSpread masterSpread = idmlDoc.getMasterSpread(masterSpreadId);
            if (masterSpread != null && !masterSpread.pages().isEmpty()) {
                int pageIdx = findPageIndexInSpread(spread, page);
                int masterPageIdx = Math.min(pageIdx, masterSpread.pages().size() - 1);
                IDMLPage masterPage = masterSpread.pages().get(masterPageIdx);

                double[] masterPageAbs = IDMLGeometry.absoluteTopLeft(
                        masterPage.geometricBounds(), masterPage.itemTransform());

                List<IDMLSpread.RenderableItem> masterItems =
                        masterSpread.getRenderableItemsOnPage(masterPage, true);
                for (IDMLSpread.RenderableItem item : masterItems) {
                    if (item.type() == IDMLSpread.RenderableItem.Type.IMAGE) {
                        renderImage(g, item.imageFrame(),
                                masterPageAbs[0], masterPageAbs[1], scale, linksDirectory);
                    } else {
                        renderVectorShape(g, item.vectorShape(),
                                masterPageAbs[0], masterPageAbs[1], scale);
                    }
                }
            }
        }

        // z-order 순서로 모든 렌더링 항목 (이미지 + 벡터) 가져오기
        List<IDMLSpread.RenderableItem> items = spread.getRenderableItemsOnPage(page, includeGroupItems);
        for (IDMLSpread.RenderableItem item : items) {
            if (item.type() == IDMLSpread.RenderableItem.Type.IMAGE) {
                renderImage(g, item.imageFrame(), pageTx, pageTy, scale, linksDirectory);
            } else {
                renderVectorShape(g, item.vectorShape(), pageTx, pageTy, scale);
            }
        }

        // 페이지 경계선 그리기 (옵션)
        if (drawPageBoundary) {
            drawPageBoundaryLines(g, pixelWidth, pixelHeight, scale);
        }

        g.dispose();

        // PNG로 인코딩
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    private static int findPageIndexInSpread(IDMLSpread spread, IDMLPage page) {
        List<IDMLPage> pages = spread.pages();
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).selfId().equals(page.selfId())) {
                return i;
            }
        }
        return 0;
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

        // 페이지 크기 계산
        double pageWidth = pageBounds != null ? pageBounds[3] - pageBounds[1] : Double.MAX_VALUE;
        double pageHeight = pageBounds != null ? pageBounds[2] - pageBounds[0] : Double.MAX_VALUE;

        // 페이지 경계로 클리핑 - 음수 좌표나 페이지 밖 부분 제외
        double clipOffsetX = 0;
        double clipOffsetY = 0;

        // 왼쪽/위쪽 경계 클리핑
        if (renderX < 0) {
            clipOffsetX = -renderX;
            renderW -= clipOffsetX;
            renderX = 0;
        }
        if (renderY < 0) {
            clipOffsetY = -renderY;
            renderH -= clipOffsetY;
            renderY = 0;
        }

        // 오른쪽/아래쪽 경계 클리핑
        if (renderX + renderW > pageWidth) {
            renderW = pageWidth - renderX;
        }
        if (renderY + renderH > pageHeight) {
            renderH = pageHeight - renderY;
        }

        // 클리핑 후 유효한 영역이 없으면 스킵
        if (renderW <= 0 || renderH <= 0) return null;

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
        // 클리핑 오프셋을 적용하여 보이는 부분만 그리기
        double offsetX = pageAbs[0] + renderX - clipOffsetX;
        double offsetY = pageAbs[1] + renderY - clipOffsetY;

        renderVectorShape(g, shape, offsetX, offsetY, scale);
        g.dispose();

        // PNG 인코딩
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);

        return new RenderResult(baos.toByteArray(), renderX, renderY, renderW, renderH,
                pixelWidth, pixelHeight);
    }

    /**
     * 단일 이미지 프레임을 투명 PNG로 렌더링한다 (스케일, 회전, 클리핑 적용).
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

        // 페이지 절대 좌표 계산
        double[] pageBounds = page.geometricBounds();
        double[] pageTransform = page.itemTransform();
        double[] pageAbs = (pageBounds != null && pageTransform != null)
                ? applyTransform(pageTransform, pageBounds[1], pageBounds[0])
                : new double[]{0, 0};

        // 프레임의 4개 코너를 변환하여 실제 bounding box 계산
        double top = frameBounds[0], left = frameBounds[1];
        double bottom = frameBounds[2], right = frameBounds[3];

        double[] tl = applyTransform(frameTransform, left, top);
        double[] tr = applyTransform(frameTransform, right, top);
        double[] bl = applyTransform(frameTransform, left, bottom);
        double[] br = applyTransform(frameTransform, right, bottom);

        // 변환된 bounding box (페이지 상대)
        double minX = Math.min(Math.min(tl[0], tr[0]), Math.min(bl[0], br[0])) - pageAbs[0];
        double maxX = Math.max(Math.max(tl[0], tr[0]), Math.max(bl[0], br[0])) - pageAbs[0];
        double minY = Math.min(Math.min(tl[1], tr[1]), Math.min(bl[1], br[1])) - pageAbs[1];
        double maxY = Math.max(Math.max(tl[1], tr[1]), Math.max(bl[1], br[1])) - pageAbs[1];

        double frameX = minX;
        double frameY = minY;
        double frameW = maxX - minX;
        double frameH = maxY - minY;

        if (frameW < 0.5 || frameH < 0.5) return null;

        // 픽셀 크기
        double scale = dpi / 72.0;
        int pixelWidth = (int) Math.ceil(frameW * scale);
        int pixelHeight = (int) Math.ceil(frameH * scale);

        if (pixelWidth < 1 || pixelHeight < 1) return null;

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

        // 투명 PNG 생성
        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // 출력 좌표계로 변환:
        // 1. 페이지 원점 기준으로 이동
        // 2. frameTransform 적용 (스케일, 회전, 이동)
        // 3. imageTransform 적용 (이미지 배치)
        // 4. graphicBounds → 픽셀 변환

        AffineTransform outputTransform = new AffineTransform();
        // 출력 이미지 원점으로 이동
        outputTransform.translate(-minX * scale, -minY * scale);
        // 페이지 절대 좌표 → 픽셀 좌표
        outputTransform.scale(scale, scale);
        outputTransform.translate(-pageAbs[0], -pageAbs[1]);
        // 프레임 변환 적용 (a, b, c, d, tx, ty)
        outputTransform.concatenate(new AffineTransform(
                frameTransform[0], frameTransform[1],
                frameTransform[2], frameTransform[3],
                frameTransform[4], frameTransform[5]));
        // 이미지 변환 적용 (imageTransform이 있는 경우)
        if (imageTransform != null && imageTransform.length >= 6) {
            outputTransform.concatenate(new AffineTransform(
                    imageTransform[0], imageTransform[1],
                    imageTransform[2], imageTransform[3],
                    imageTransform[4], imageTransform[5]));
        }
        // graphicBounds 원점에서 픽셀 좌표로 변환
        outputTransform.translate(-gLeft, -gTop);
        outputTransform.scale(1.0 / pointsToPixelX, 1.0 / pointsToPixelY);

        // 프레임 클리핑 경로 설정 (변환된 프레임 영역만 보이도록)
        GeneralPath clipPath = new GeneralPath();
        double[] c1 = new double[]{(tl[0] - pageAbs[0] - minX) * scale, (tl[1] - pageAbs[1] - minY) * scale};
        double[] c2 = new double[]{(tr[0] - pageAbs[0] - minX) * scale, (tr[1] - pageAbs[1] - minY) * scale};
        double[] c3 = new double[]{(br[0] - pageAbs[0] - minX) * scale, (br[1] - pageAbs[1] - minY) * scale};
        double[] c4 = new double[]{(bl[0] - pageAbs[0] - minX) * scale, (bl[1] - pageAbs[1] - minY) * scale};
        clipPath.moveTo(c1[0], c1[1]);
        clipPath.lineTo(c2[0], c2[1]);
        clipPath.lineTo(c3[0], c3[1]);
        clipPath.lineTo(c4[0], c4[1]);
        clipPath.closePath();
        g.setClip(clipPath);

        // 이미지 그리기
        g.drawImage(srcImage, outputTransform, null);

        g.dispose();

        // PNG 인코딩
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);

        return new RenderResult(baos.toByteArray(), frameX, frameY, frameW, frameH,
                pixelWidth, pixelHeight);
    }

    /**
     * shape의 pathPoints를 GeneralPath로 변환한다 (클리핑 경로 생성용).
     */
    private GeneralPath buildPathFromShape(IDMLVectorShape shape, double[] transform,
                                            double pageTx, double pageTy, double scale) {
        List<IDMLVectorShape.PathPoint> points = shape.pathPoints();
        if (points.isEmpty()) return null;

        GeneralPath path = new GeneralPath();
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
                    path.curveTo((ctrl1[0] - pageTx) * scale, (ctrl1[1] - pageTy) * scale,
                                 (ctrl2[0] - pageTx) * scale, (ctrl2[1] - pageTy) * scale, px, py);
                }
            }
        }
        if (!shape.pathOpen() && points.size() > 2) {
            path.closePath();
        }
        return path;
    }

    /**
     * 벡터 도형을 렌더링한다.
     */
    private void renderVectorShape(Graphics2D g, IDMLVectorShape shape,
                                    double pageTx, double pageTy, double scale) {
        double[] transform = shape.itemTransform();

        // 클리핑 프레임 패턴: 부모를 클립으로, 자식을 실제 렌더링
        if (shape.hasClippedChild()) {
            // 1. 부모의 pathPoints로 클리핑 경로 생성
            GeneralPath clipPath = buildPathFromShape(shape, transform, pageTx, pageTy, scale);
            if (clipPath != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setClip(clipPath);

                // 2. 합성 변환: parent * child
                IDMLVectorShape child = shape.clippedChild();
                double[] childT = child.itemTransform();
                double[] pT = transform;
                double[] compositeT = {
                    pT[0]*childT[0] + pT[2]*childT[1], pT[1]*childT[0] + pT[3]*childT[1],
                    pT[0]*childT[2] + pT[2]*childT[3], pT[1]*childT[2] + pT[3]*childT[3],
                    pT[0]*childT[4] + pT[2]*childT[5] + pT[4], pT[1]*childT[4] + pT[3]*childT[5] + pT[5]
                };

                // 3. 자식 도형 렌더링 (합성 변환 적용)
                double[] origTransform = child.itemTransform();
                child.itemTransform(compositeT);
                renderVectorShape(g2, child, pageTx, pageTy, scale);
                child.itemTransform(origTransform);  // 원복

                g2.dispose();
            }
            return;
        }

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
            // 디버그: 인라인 그래픽 렌더링 위치 확인
            String selfId = shape.selfId();
            if (selfId != null && (selfId.startsWith("u38a") || selfId.startsWith("u38b") || selfId.startsWith("u38d"))) {
                IDMLVectorShape.PathPoint firstPt = points.get(0);
                double[] firstLocal = applyTransform(transform, firstPt.anchorX(), firstPt.anchorY());
                double firstPx = (firstLocal[0] - pageTx) * scale;
                double firstPy = (firstLocal[1] - pageTy) * scale;
                System.err.println("[DEBUG] 벡터 렌더링: " + selfId
                        + " | PathPoints=" + points.size()
                        + " | 첫점원본=(" + String.format("%.1f,%.1f", firstPt.anchorX(), firstPt.anchorY()) + ")"
                        + " | 변환후=(" + String.format("%.1f,%.1f", firstLocal[0], firstLocal[1]) + ")"
                        + " | 픽셀=(" + String.format("%.1f,%.1f", firstPx, firstPy) + ")"
                        + " | pageTxTy=(" + String.format("%.1f,%.1f", pageTx, pageTy) + ")"
                        + " | fillColor=" + shape.fillColor());
            }

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
                // 투명도 적용 (fillTint: 0~100)
                fillColor = applyTint(fillColor, shape.fillTint(), shape.opacity());
                g.setColor(fillColor);
                g.fill(path);
            }
        }

        // 선
        if (shape.hasStroke()) {
            Color strokeColor = resolveColor(shape.strokeColor());
            if (strokeColor != null) {
                // 투명도 적용 (strokeTint: 0~100)
                strokeColor = applyTint(strokeColor, shape.strokeTint(), shape.opacity());
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
     * 이미지를 렌더링한다 (스케일, 회전, 클리핑 적용).
     * AffineTransform을 사용하여 정확한 변환을 적용.
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

        // 프레임의 4개 코너를 변환하여 클리핑 경로 생성
        double top = frameBounds[0], left = frameBounds[1];
        double bottom = frameBounds[2], right = frameBounds[3];

        double[] tl = applyTransform(frameTransform, left, top);
        double[] tr = applyTransform(frameTransform, right, top);
        double[] bl = applyTransform(frameTransform, left, bottom);
        double[] br = applyTransform(frameTransform, right, bottom);

        // 페이지 상대 좌표로 변환 (픽셀)
        double tlX = (tl[0] - pageTx) * scale, tlY = (tl[1] - pageTy) * scale;
        double trX = (tr[0] - pageTx) * scale, trY = (tr[1] - pageTy) * scale;
        double blX = (bl[0] - pageTx) * scale, blY = (bl[1] - pageTy) * scale;
        double brX = (br[0] - pageTx) * scale, brY = (br[1] - pageTy) * scale;

        // 클리핑 경로 생성 (변환된 프레임 영역)
        GeneralPath clipPath = new GeneralPath();
        clipPath.moveTo(tlX, tlY);
        clipPath.lineTo(trX, trY);
        clipPath.lineTo(brX, brY);
        clipPath.lineTo(blX, blY);
        clipPath.closePath();

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

        // 현재 클립 저장
        Shape oldClip = g.getClip();

        // 프레임 클리핑 적용
        g.setClip(clipPath);

        // AffineTransform 구성:
        // 1. 페이지 좌표 → 픽셀 좌표 (scale)
        // 2. 페이지 원점 이동 (-pageTx, -pageTy)
        // 3. 프레임 변환 적용 (frameTransform)
        // 4. 이미지 변환 적용 (imageTransform)
        // 5. graphicBounds 원점 → 픽셀 좌표
        AffineTransform imgTransform = new AffineTransform();
        imgTransform.scale(scale, scale);
        imgTransform.translate(-pageTx, -pageTy);
        imgTransform.concatenate(new AffineTransform(
                frameTransform[0], frameTransform[1],
                frameTransform[2], frameTransform[3],
                frameTransform[4], frameTransform[5]));

        if (imageTransform != null && imageTransform.length >= 6) {
            imgTransform.concatenate(new AffineTransform(
                    imageTransform[0], imageTransform[1],
                    imageTransform[2], imageTransform[3],
                    imageTransform[4], imageTransform[5]));
        }

        // graphicBounds 원점에서 픽셀 좌표로 변환
        imgTransform.translate(-gLeft, -gTop);
        imgTransform.scale(1.0 / pointsToPixelX, 1.0 / pointsToPixelY);

        // 이미지 그리기
        g.drawImage(srcImage, imgTransform, null);

        // 클립 복원
        g.setClip(oldClip);
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

        if (imageFile == null) {
            System.err.println("  [WARN] Image file not found: " + uri);
            if (linksDirectory != null) {
                System.err.println("         Searched in: " + linksDirectory);
            }
            if (idmlDoc.basePath() != null) {
                System.err.println("         Searched in: " + new File(idmlDoc.basePath(), "Links").getAbsolutePath());
            }
            return null;
        }

        try {
            String ext = getExtension(imageFile).toLowerCase();
            System.err.println("[INFO]     이미지 로드: " + imageFile.getName());

            // 디자인 파일은 PNG로 변환 (PSD, AI, EPS만 캐싱 대상)
            if (isDesignFormat(ext)) {
                // 캐시 파일 확인 (원본파일.png)
                if (isCacheableDesignFormat(ext)) {
                    File cacheFile = new File(imageFile.getAbsolutePath() + ".png");
                    if (cacheFile.exists() && cacheFile.length() > 0) {
                        System.err.println("[INFO]       → 캐시된 PNG 사용: " + cacheFile.getName());
                        return ImageIO.read(cacheFile);
                    }
                }

                System.err.println("[INFO]       → 디자인 파일 변환 중 (" + ext.toUpperCase() + ")...");
                byte[] pngData = DesignFileConverter.convertToPng(imageFile, dpi);
                if (pngData == null || pngData.length == 0) {
                    System.err.println("  [WARN] Design file conversion returned empty data: " + imageFile.getAbsolutePath());
                    return null;
                }

                // 캐시 파일 저장 (PSD, AI, EPS만)
                if (isCacheableDesignFormat(ext)) {
                    try {
                        File cacheFile = new File(imageFile.getAbsolutePath() + ".png");
                        java.nio.file.Files.write(cacheFile.toPath(), pngData);
                        System.err.println("[INFO]       → 캐시 저장: " + cacheFile.getName());
                    } catch (Exception ce) {
                        System.err.println("[WARN]       캐시 저장 실패: " + ce.getMessage());
                    }
                }

                return ImageIO.read(new ByteArrayInputStream(pngData));
            } else {
                return ImageIO.read(imageFile);
            }
        } catch (IOException e) {
            System.err.println("  [ERROR] Failed to load image: " + imageFile.getAbsolutePath());
            System.err.println("          Reason: " + e.getMessage());
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

    /**
     * 색상에 투명도(tint)와 전체 불투명도(opacity)를 적용한다.
     * @param color 원본 색상
     * @param tint 색 농도 (0~100, 100=원본 색상)
     * @param opacity 전체 불투명도 (0~100, 100=불투명)
     * @return 투명도가 적용된 색상
     */
    private Color applyTint(Color color, double tint, double opacity) {
        if (color == null) return null;

        // tint와 opacity를 0~1 범위로 변환
        double tintFactor = Math.max(0, Math.min(100, tint)) / 100.0;
        double opacityFactor = Math.max(0, Math.min(100, opacity)) / 100.0;

        // 최종 알파값 계산 (tint * opacity)
        int alpha = (int) (255 * tintFactor * opacityFactor);

        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
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

    /**
     * 캐싱 대상 디자인 파일 형식인지 확인 (PSD, AI, EPS만 캐싱)
     */
    private static boolean isCacheableDesignFormat(String ext) {
        return "psd".equals(ext) || "ai".equals(ext) || "eps".equals(ext);
    }

    /**
     * 페이지 경계선을 그린다.
     * InDesign 스타일의 페이지 경계선: 얇은 검정색 실선
     */
    private void drawPageBoundaryLines(Graphics2D g, int pixelWidth, int pixelHeight, double scale) {
        // 페이지 경계선 스타일: 0.5pt 검정색 실선
        float strokeWidth = (float) (0.5 * scale);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(strokeWidth));

        // 페이지 바운딩 박스 (사각형)
        g.drawRect(0, 0, pixelWidth - 1, pixelHeight - 1);
    }

    /**
     * 스프레드의 모든 페이지를 나란히 배치하여 하나의 PNG로 렌더링한다.
     *
     * @param spread         스프레드
     * @param linksDirectory 이미지 파일 검색 디렉토리 (옵션)
     * @param drawPageBoundary 페이지 경계선 그리기 여부
     * @param drawBackground   배경 그리기 여부
     * @return PNG 바이트 배열
     */
    public byte[] renderSpreadPages(IDMLSpread spread, String linksDirectory,
                                     boolean drawPageBoundary, boolean drawBackground) throws IOException {
        List<IDMLPage> pages = spread.pages();
        if (pages.isEmpty()) return new byte[0];

        double scale = dpi / 72.0;
        int gap = (int) Math.ceil(2 * scale);  // 페이지 간 2pt 간격

        // 전체 이미지 크기 계산
        int totalWidth = 0;
        int maxHeight = 0;
        int[] pageWidths = new int[pages.size()];
        int[] pageHeights = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            IDMLPage page = pages.get(i);
            pageWidths[i] = (int) Math.ceil(page.widthPoints() * scale);
            pageHeights[i] = (int) Math.ceil(page.heightPoints() * scale);
            totalWidth += pageWidths[i];
            maxHeight = Math.max(maxHeight, pageHeights[i]);
        }
        totalWidth += gap * (pages.size() - 1);

        // 투명 배경 이미지 생성
        BufferedImage compositeImage = new BufferedImage(totalWidth, maxHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D compositeG = compositeImage.createGraphics();
        compositeG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        compositeG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        if (drawBackground) {
            compositeG.setColor(Color.WHITE);
            compositeG.fillRect(0, 0, totalWidth, maxHeight);
        }

        // 각 페이지를 렌더링하여 합성
        int xOffset = 0;
        for (int i = 0; i < pages.size(); i++) {
            byte[] pageData = renderPage(spread, pages.get(i), linksDirectory, drawPageBoundary, true);
            if (pageData != null && pageData.length > 0) {
                BufferedImage pageImage = ImageIO.read(new ByteArrayInputStream(pageData));
                if (pageImage != null) {
                    compositeG.drawImage(pageImage, xOffset, 0, null);
                }
            }
            xOffset += pageWidths[i] + gap;
        }

        compositeG.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(compositeImage, "png", baos);
        return baos.toByteArray();
    }

    /**
     * 인라인 TextFrame과 그 안의 그래픽을 PNG로 렌더링한다.
     *
     * @param textFrame 인라인 텍스트 프레임
     * @param graphics  텍스트 프레임 내 인라인 그래픽 목록
     * @return 렌더링 결과
     */
    public RenderResult renderInlineTextFrameToPng(IDMLTextFrame textFrame,
                                                    List<IDMLCharacterRun.InlineGraphic> graphics) throws IOException {
        double[] bounds = textFrame.geometricBounds();
        if (bounds == null || bounds.length < 4) return null;

        double widthPts = bounds[3] - bounds[1];
        double heightPts = bounds[2] - bounds[0];
        if (widthPts <= 0 || heightPts <= 0) return null;

        double scale = dpi / 72.0;
        int pixelWidth = (int) Math.ceil(widthPts * scale);
        int pixelHeight = (int) Math.ceil(heightPts * scale);

        if (pixelWidth <= 0 || pixelHeight <= 0) return null;

        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 각 인라인 그래픽을 프레임 내 상대 좌표로 렌더링
        // (간단한 플레이스홀더 렌더링 - 추후 벡터 렌더링으로 확장 가능)
        g.setColor(new Color(0xEE, 0xEE, 0xEE));
        g.fillRect(0, 0, pixelWidth, pixelHeight);

        if (graphics != null) {
            for (IDMLCharacterRun.InlineGraphic graphic : graphics) {
                int gw = (int) Math.ceil(graphic.widthPoints() * scale);
                int gh = (int) Math.ceil(graphic.heightPoints() * scale);
                g.setColor(new Color(0xDD, 0xDD, 0xDD));
                g.fillRect(0, 0, Math.min(gw, pixelWidth), Math.min(gh, pixelHeight));
            }
        }

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] pngData = baos.toByteArray();

        return new RenderResult(pngData, 0, 0, widthPts, heightPts, pixelWidth, pixelHeight);
    }

    /**
     * 단일 인라인 그래픽을 PNG로 렌더링한다.
     *
     * @param graphic 인라인 그래픽
     * @return 렌더링 결과
     */
    public RenderResult renderInlineGraphicToPng(IDMLCharacterRun.InlineGraphic graphic) throws IOException {
        double widthPts = graphic.widthPoints();
        double heightPts = graphic.heightPoints();
        if (widthPts <= 0 || heightPts <= 0) return null;

        double scale = dpi / 72.0;
        int pixelWidth = (int) Math.ceil(widthPts * scale);
        int pixelHeight = (int) Math.ceil(heightPts * scale);
        if (pixelWidth <= 0 || pixelHeight <= 0) return null;

        BufferedImage image = new BufferedImage(pixelWidth, pixelHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 플레이스홀더 렌더링 (추후 실제 벡터 렌더링으로 확장)
        g.setColor(new Color(0xEE, 0xEE, 0xEE));
        g.fillRect(0, 0, pixelWidth, pixelHeight);
        g.setColor(new Color(0xCC, 0xCC, 0xCC));
        g.drawRect(0, 0, pixelWidth - 1, pixelHeight - 1);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] pngData = baos.toByteArray();

        return new RenderResult(pngData, 0, 0, widthPts, heightPts, pixelWidth, pixelHeight);
    }
}
