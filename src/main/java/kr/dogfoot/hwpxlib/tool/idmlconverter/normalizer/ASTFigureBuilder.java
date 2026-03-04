package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 플로팅 Figure(이미지, 벡터 도형, 클리핑 그룹) 변환.
 * ASTInlineObjectBuilder에서 분리됨.
 */
class ASTFigureBuilder {

    private static String decodeURI(String uri) {
        if (uri == null) return null;
        try {
            return URLDecoder.decode(uri, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return uri;
        }
    }

    /**
     * IDMLImageFrame → ASTFigure 변환 (플로팅 이미지).
     */
    static ASTFigure createFigureFromImageFrame(IDMLImageFrame imgFrame,
                                                 IDMLPage page,
                                                 ASTImageLoader imageLoader) {
        double[] t = imgFrame.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001
                || t[0] < 0 || t[3] < 0);

        long wHwp, hHwp, xHwp, yHwp;

        if (hasRotOrFlip) {
            double w = IDMLGeometry.transformedWidth(imgFrame.geometricBounds(), imgFrame.itemTransform());
            double h = IDMLGeometry.transformedHeight(imgFrame.geometricBounds(), imgFrame.itemTransform());
            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);

            double[] bbox = IDMLGeometry.getTransformedBoundingBox(
                    imgFrame.geometricBounds(), imgFrame.itemTransform());
            double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                    page.geometricBounds(), page.itemTransform());
            xHwp = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
            yHwp = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);
        } else {
            double w = IDMLGeometry.scaledWidth(imgFrame.geometricBounds(), imgFrame.itemTransform());
            double h = IDMLGeometry.scaledHeight(imgFrame.geometricBounds(), imgFrame.itemTransform());

            double[] relCenter = IDMLGeometry.pageRelativeCenter(
                    imgFrame.geometricBounds(), imgFrame.itemTransform(),
                    page.geometricBounds(), page.itemTransform());

            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);
            xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
            yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;
        }

        if (wHwp <= 0 || hHwp <= 0) return null;

        // 프레임 bounds (points) — 클리핑용
        double[] frameBounds = imgFrame.geometricBounds();

        // PSD 레이어 가시성 오버라이드
        java.util.List<Integer> visibleLayers = imgFrame.hasLayerOverrides()
                ? imgFrame.visibleLayerIndices() : null;
        String layerSig = imgFrame.hasLayerOverrides()
                ? imgFrame.layerSignature() : null;

        ASTImageLoader.ImageResult result = imageLoader.loadImage(
                imgFrame.linkResourceURI(), wHwp, hHwp,
                imgFrame.imageTransform(), frameBounds, imgFrame.graphicBounds(),
                visibleLayers, layerSig);

        if (result == null || result.imageData == null) return null;

        // 그레이스케일 모노톤 이미지 스킵
        if (imgFrame.needsGrayscaleColorization()) {
            return null;
        }

        // 회전/반전이 있으면 이미지를 픽셀 레벨에서 회전
        if (hasRotOrFlip) {
            ASTImageLoader.ImageResult rotated =
                    ASTImageLoader.preRenderRotation(result.imageData, imgFrame.itemTransform());
            if (rotated != null) {
                result = rotated;
            }
        }

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.IMAGE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(imgFrame.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);
        figure.imagePath(decodeURI(imgFrame.linkResourceURI()));

        // textWrap 속성 전파
        figure.textWrapMode(imgFrame.textWrapMode());
        figure.textWrapSide(imgFrame.textWrapSide());
        figure.textWrapTop(CoordinateConverter.pointsToHwpunits(imgFrame.textWrapTop()));
        figure.textWrapLeft(CoordinateConverter.pointsToHwpunits(imgFrame.textWrapLeft()));
        figure.textWrapBottom(CoordinateConverter.pointsToHwpunits(imgFrame.textWrapBottom()));
        figure.textWrapRight(CoordinateConverter.pointsToHwpunits(imgFrame.textWrapRight()));

        // 페이지 경계를 벗어나는 이미지의 크롭 비율 계산
        long pageW = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.width(page.geometricBounds()));
        long pageH = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.height(page.geometricBounds()));
        double minCropThreshold = 0.05;

        if (xHwp < 0) {
            double frac = (double) -xHwp / wHwp;
            if (frac > minCropThreshold) figure.cropLeftFraction(frac);
        }
        if (yHwp < 0) {
            double frac = (double) -yHwp / hHwp;
            if (frac > minCropThreshold) figure.cropTopFraction(frac);
        }
        if (xHwp + wHwp > pageW) {
            double frac = (double) (xHwp + wHwp - pageW) / wHwp;
            if (frac > minCropThreshold) figure.cropRightFraction(frac);
        }
        if (yHwp + hHwp > pageH) {
            double frac = (double) (yHwp + hHwp - pageH) / hHwp;
            if (frac > minCropThreshold) figure.cropBottomFraction(frac);
        }

        figure.sourceId(imgFrame.selfId());
        return figure;
    }

    /**
     * IDMLVectorShape → ASTFigure 변환 (기존 호환용 오버로드).
     */
    static ASTFigure createFigureFromVectorShape(IDMLVectorShape shape,
                                                  IDMLPage page,
                                                  ASTImageLoader imageLoader,
                                                  ColorResolver colorResolver) {
        return createFigureFromVectorShape(shape, page, imageLoader, colorResolver, null, null);
    }

    /**
     * IDMLVectorShape → ASTFigure 변환 (플로팅 벡터 도형 → PNG 래스터화).
     */
    static ASTFigure createFigureFromVectorShape(IDMLVectorShape shape,
                                                  IDMLPage page,
                                                  ASTImageLoader imageLoader,
                                                  ColorResolver colorResolver,
                                                  ResolvedData resolvedData,
                                                  ResolvedPage resolvedPage) {
        // 복수 클리핑 자식 처리
        if (shape.hasClippedChildren()) {
            return createFigureFromClippedGroup(shape, page, imageLoader, colorResolver,
                    resolvedData, resolvedPage);
        }

        // 채우기/선 색상 해석
        IDMLVectorShape renderTarget = shape.hasClippedChild() ? shape.clippedChild() : shape;
        String fillHex = ASTInlineObjectBuilder.resolveColorHex(renderTarget.fillColor(), colorResolver);
        String strokeHex = ASTInlineObjectBuilder.resolveColorHex(renderTarget.strokeColor(), colorResolver);

        if (fillHex == null && strokeHex == null) {
            return null;
        }

        // Resolved geometry path
        ResolvedPageItem resolvedItem = null;
        if (resolvedData != null && shape.selfId() != null) {
            resolvedItem = resolvedData.getPageItemByIdmlId(shape.selfId());
        }
        if (resolvedItem != null && resolvedItem.geometricBounds() != null
                && resolvedPage != null && resolvedPage.bounds() != null) {
            ASTFigure resolved = createFigureResolvedShape(shape, resolvedItem, resolvedPage,
                    imageLoader, fillHex, strokeHex);
            if (resolved != null) return resolved;
        }

        // IDML fallback
        double[] effectiveBounds = shape.geometricBounds();
        double[] t = shape.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001
                || t[0] < 0 || t[3] < 0);

        long wHwp, hHwp, xHwp, yHwp;
        ASTImageLoader.ImageResult result;

        if (hasRotOrFlip) {
            double w = IDMLGeometry.transformedWidth(effectiveBounds, shape.itemTransform());
            double h = IDMLGeometry.transformedHeight(effectiveBounds, shape.itemTransform());
            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);

            double[] bbox = IDMLGeometry.getTransformedBoundingBox(
                    effectiveBounds, shape.itemTransform());
            double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                    page.geometricBounds(), page.itemTransform());
            xHwp = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
            yHwp = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);

            if (wHwp <= 0 || hHwp <= 0) {
                if (shape.hasStroke() && shape.strokeWeight() > 0) {
                    long minDim = CoordinateConverter.pointsToHwpunits(shape.strokeWeight());
                    if (minDim < 100) minDim = 100;
                    if (wHwp <= 0) wHwp = minDim;
                    if (hHwp <= 0) hHwp = minDim;
                } else {
                    return null;
                }
            }

            long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
            long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
            if (wHwp > pageW * 3 || hHwp > pageH * 3) {
                return null;
            }

            result = imageLoader.rasterizeShape(shape, fillHex, strokeHex, shape.itemTransform());

            if (result != null && result.pixelWidth > 0 && result.pixelHeight > 0) {
                double pixelAR = (double) result.pixelWidth / result.pixelHeight;
                double displayAR = (double) wHwp / Math.max(1, hHwp);
                if (Math.abs(pixelAR - displayAR) / Math.max(0.001, displayAR) > 0.03) {
                    double area = (double) wHwp * hHwp;
                    long newH = (long) Math.sqrt(area / pixelAR);
                    long newW = (long) (newH * pixelAR);
                    xHwp += (wHwp - newW) / 2;
                    yHwp += (hHwp - newH) / 2;
                    wHwp = newW;
                    hHwp = newH;
                }
            }
        } else {
            double w = IDMLGeometry.scaledWidth(effectiveBounds, shape.itemTransform());
            double h = IDMLGeometry.scaledHeight(effectiveBounds, shape.itemTransform());

            double[] relCenter = IDMLGeometry.pageRelativeCenter(
                    effectiveBounds, shape.itemTransform(),
                    page.geometricBounds(), page.itemTransform());

            wHwp = CoordinateConverter.pointsToHwpunits(w);
            hHwp = CoordinateConverter.pointsToHwpunits(h);
            xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
            yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;

            if (wHwp <= 0 || hHwp <= 0) {
                if (shape.hasStroke() && shape.strokeWeight() > 0) {
                    long minDim = CoordinateConverter.pointsToHwpunits(shape.strokeWeight());
                    if (minDim < 100) minDim = 100;
                    if (wHwp <= 0) wHwp = minDim;
                    if (hHwp <= 0) hHwp = minDim;
                    xHwp = CoordinateConverter.pointsToHwpunits(relCenter[0]) - wHwp / 2;
                    yHwp = CoordinateConverter.pointsToHwpunits(relCenter[1]) - hHwp / 2;
                } else {
                    return null;
                }
            }

            long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
            long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
            if (wHwp > pageW * 3 || hHwp > pageH * 3) {
                return null;
            }

            result = imageLoader.rasterizeShape(shape, fillHex, strokeHex);
        }

        if (result == null || result.imageData == null) {
            return null;
        }

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(shape.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);

        if (!hasRotOrFlip) {
            if (IDMLGeometry.hasFlip(shape.itemTransform())) {
                double rotation = IDMLGeometry.extractRotation(shape.itemTransform());
                if (Math.abs(Math.abs(rotation) - 180) < 0.5) {
                    result.imageData = ASTImageLoader.flipVertically(result.imageData);
                    figure.imageData(result.imageData);
                } else if (Math.abs(rotation) < 0.5) {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                } else {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                    figure.rotationAngle(rotation);
                }
            } else {
                double rotation = IDMLGeometry.extractRotation(shape.itemTransform());
                if (Math.abs(rotation) > 0.1) {
                    figure.rotationAngle(rotation);
                }
            }
        }

        figure.sourceId(shape.selfId());
        return figure;
    }

    /**
     * Resolved geometry로 단일 벡터 도형 → ASTFigure 변환.
     */
    private static ASTFigure createFigureResolvedShape(IDMLVectorShape shape,
                                                         ResolvedPageItem ri,
                                                         ResolvedPage resolvedPage,
                                                         ASTImageLoader imageLoader,
                                                         String fillHex, String strokeHex) {
        double[] gb = ri.geometricBounds();

        double rW = gb[3] - gb[1];
        double rH = gb[2] - gb[0];
        if (rW <= 0 || rH <= 0) return null;

        double[] rel = resolvedPage.toPageRelative(gb);
        double rLeft = rel[0];
        double rTop = rel[1];

        double pageW = resolvedPage.width();
        double pageH = resolvedPage.height();

        long wHwp = CoordinateConverter.pointsToHwpunits(rW);
        long hHwp = CoordinateConverter.pointsToHwpunits(rH);
        long xHwp = CoordinateConverter.pointsToHwpunits(rLeft);
        long yHwp = CoordinateConverter.pointsToHwpunits(rTop);

        long pageWHwp = CoordinateConverter.pointsToHwpunits(pageW);
        long pageHHwp = CoordinateConverter.pointsToHwpunits(pageH);
        if (wHwp > pageWHwp * 3 || hHwp > pageHHwp * 3) return null;

        ASTImageLoader.ImageResult result = imageLoader.rasterizeShapeAtSize(
                shape, fillHex, strokeHex, rW, rH);
        if (result == null || result.imageData == null) return null;

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(shape.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);
        figure.sourceId(shape.selfId());
        return figure;
    }

    /**
     * 클리핑 프레임 변환 (기존 호환용 오버로드).
     */
    static ASTFigure createFigureFromClippedGroup(IDMLVectorShape clipFrame,
                                                   IDMLPage page,
                                                   ASTImageLoader imageLoader,
                                                   ColorResolver colorResolver) {
        return createFigureFromClippedGroup(clipFrame, page, imageLoader, colorResolver, null, null);
    }

    /**
     * 클리핑 프레임 + 복수 자식 도형을 합성 래스터화하여 ASTFigure로 변환.
     */
    static ASTFigure createFigureFromClippedGroup(IDMLVectorShape clipFrame,
                                                   IDMLPage page,
                                                   ASTImageLoader imageLoader,
                                                   ColorResolver colorResolver,
                                                   ResolvedData resolvedData,
                                                   ResolvedPage resolvedPage) {
        boolean anyVisible = false;
        String clipFillHex = ASTInlineObjectBuilder.resolveColorHex(clipFrame.fillColor(), colorResolver);
        if (clipFillHex != null) anyVisible = true;
        if (!anyVisible) {
            for (IDMLVectorShape child : clipFrame.clippedChildren()) {
                String fh = ASTInlineObjectBuilder.resolveColorHex(child.fillColor(), colorResolver);
                String sh = ASTInlineObjectBuilder.resolveColorHex(child.strokeColor(), colorResolver);
                if (fh != null || sh != null) { anyVisible = true; break; }
            }
        }
        if (!anyVisible) return null;

        // Resolved geometry path
        ResolvedPageItem resolvedItem = null;
        if (resolvedData != null && clipFrame.selfId() != null) {
            resolvedItem = resolvedData.getPageItemByIdmlId(clipFrame.selfId());
        }
        if (resolvedItem != null && resolvedItem.geometricBounds() != null
                && resolvedPage != null && resolvedPage.bounds() != null) {
            ASTFigure resolved = createFigureResolvedClippedGroup(clipFrame, resolvedItem,
                    resolvedPage, imageLoader, colorResolver, clipFillHex);
            if (resolved != null) return resolved;
        }

        // IDML fallback
        double[] clipBounds = clipFrame.geometricBounds();
        double clipTop = clipBounds[0], clipLeft = clipBounds[1];
        double clipBottom = clipBounds[2], clipRight = clipBounds[3];

        double uLeft = Double.MAX_VALUE, uTop = Double.MAX_VALUE;
        double uRight = -Double.MAX_VALUE, uBottom = -Double.MAX_VALUE;
        for (IDMLVectorShape child : clipFrame.clippedChildren()) {
            double[] ct = child.itemTransform();
            double[] cb = child.geometricBounds();
            double[][] corners = {{cb[1], cb[0]}, {cb[3], cb[0]}, {cb[1], cb[2]}, {cb[3], cb[2]}};
            for (double[] c : corners) {
                double tx = ct[0] * c[0] + ct[2] * c[1] + ct[4];
                double ty = ct[1] * c[0] + ct[3] * c[1] + ct[5];
                uLeft = Math.min(uLeft, tx);
                uTop = Math.min(uTop, ty);
                uRight = Math.max(uRight, tx);
                uBottom = Math.max(uBottom, ty);
            }
        }

        double eLeft, eTop, eRight, eBottom;
        if (clipFillHex != null) {
            eLeft = clipLeft; eTop = clipTop;
            eRight = clipRight; eBottom = clipBottom;
        } else {
            eLeft = Math.max(uLeft, clipLeft);
            eTop = Math.max(uTop, clipTop);
            eRight = Math.min(uRight, clipRight);
            eBottom = Math.min(uBottom, clipBottom);
        }

        if (eRight <= eLeft || eBottom <= eTop) return null;

        double effectiveW = eRight - eLeft;
        double effectiveH = eBottom - eTop;
        double effectiveCX = (eLeft + eRight) / 2.0;
        double effectiveCY = (eTop + eBottom) / 2.0;

        double[] tt = clipFrame.itemTransform();
        double[] absCenter = CoordinateConverter.applyTransform(tt, effectiveCX, effectiveCY);
        double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                page.geometricBounds(), page.itemTransform());

        long wHwp = CoordinateConverter.pointsToHwpunits(effectiveW);
        long hHwp = CoordinateConverter.pointsToHwpunits(effectiveH);
        long xHwp = CoordinateConverter.pointsToHwpunits(absCenter[0] - pageAbs[0]) - wHwp / 2;
        long yHwp = CoordinateConverter.pointsToHwpunits(absCenter[1] - pageAbs[1]) - hHwp / 2;

        if (wHwp <= 0 || hHwp <= 0) return null;

        long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
        long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
        if (wHwp > pageW * 3 || hHwp > pageH * 3) return null;

        ASTImageLoader.ImageResult result = imageLoader.rasterizeClippedGroup(
                clipFrame, colorResolver, eLeft, eTop, effectiveW, effectiveH);
        if (result == null || result.imageData == null) return null;

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(clipFrame.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);
        figure.sourceId(clipFrame.selfId());

        return figure;
    }

    /**
     * Resolved geometry로 클리핑 그룹 → ASTFigure 변환.
     */
    private static ASTFigure createFigureResolvedClippedGroup(IDMLVectorShape clipFrame,
                                                                ResolvedPageItem ri,
                                                                ResolvedPage resolvedPage,
                                                                ASTImageLoader imageLoader,
                                                                ColorResolver colorResolver,
                                                                String clipFillHex) {
        double[] gb = ri.geometricBounds();

        double rW = gb[3] - gb[1];
        double rH = gb[2] - gb[0];
        if (rW <= 0 || rH <= 0) return null;

        double pageW = resolvedPage.width();
        double pageH = resolvedPage.height();

        double[] clipBounds = clipFrame.geometricBounds();
        double clipTop = clipBounds[0], clipLeft = clipBounds[1];
        double clipBottom = clipBounds[2], clipRight = clipBounds[3];
        double clipW = clipRight - clipLeft;
        double clipH = clipBottom - clipTop;

        double uLeft = Double.MAX_VALUE, uTop = Double.MAX_VALUE;
        double uRight = -Double.MAX_VALUE, uBottom = -Double.MAX_VALUE;
        for (IDMLVectorShape child : clipFrame.clippedChildren()) {
            double[] ct = child.itemTransform();
            double[] cb = child.geometricBounds();
            double[][] corners = {{cb[1], cb[0]}, {cb[3], cb[0]}, {cb[1], cb[2]}, {cb[3], cb[2]}};
            for (double[] c : corners) {
                double tx = ct[0] * c[0] + ct[2] * c[1] + ct[4];
                double ty = ct[1] * c[0] + ct[3] * c[1] + ct[5];
                uLeft = Math.min(uLeft, tx);
                uTop = Math.min(uTop, ty);
                uRight = Math.max(uRight, tx);
                uBottom = Math.max(uBottom, ty);
            }
        }

        double eLeft, eTop, eRight, eBottom;
        if (clipFillHex != null) {
            eLeft = clipLeft; eTop = clipTop;
            eRight = clipRight; eBottom = clipBottom;
        } else {
            eLeft = Math.max(uLeft, clipLeft);
            eTop = Math.max(uTop, clipTop);
            eRight = Math.min(uRight, clipRight);
            eBottom = Math.min(uBottom, clipBottom);
        }
        if (eRight <= eLeft || eBottom <= eTop) return null;

        double effectiveW = eRight - eLeft;
        double effectiveH = eBottom - eTop;

        double scaleW = (clipW > 0) ? effectiveW / clipW : 1.0;
        double scaleH = (clipH > 0) ? effectiveH / clipH : 1.0;
        double adjW = rW * scaleW;
        double adjH = rH * scaleH;

        double offsetFracX = (clipW > 0) ? (eLeft - clipLeft) / clipW : 0;
        double offsetFracY = (clipH > 0) ? (eTop - clipTop) / clipH : 0;
        double[] rel = resolvedPage.toPageRelative(gb);
        double rLeft = rel[0] + rW * offsetFracX;
        double rTop = rel[1] + rH * offsetFracY;

        long wHwp = CoordinateConverter.pointsToHwpunits(adjW);
        long hHwp = CoordinateConverter.pointsToHwpunits(adjH);
        long xHwp = CoordinateConverter.pointsToHwpunits(rLeft);
        long yHwp = CoordinateConverter.pointsToHwpunits(rTop);

        long pageWHwp = CoordinateConverter.pointsToHwpunits(pageW);
        long pageHHwp = CoordinateConverter.pointsToHwpunits(pageH);
        if (wHwp > pageWHwp * 3 || hHwp > pageHHwp * 3) return null;

        ASTImageLoader.ImageResult result = imageLoader.rasterizeClippedGroupAtSize(
                clipFrame, colorResolver, eLeft, eTop, effectiveW, effectiveH, adjW, adjH);
        if (result == null || result.imageData == null) return null;

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(clipFrame.zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);
        figure.sourceId(clipFrame.selfId());
        return figure;
    }

    /**
     * 벡터 그룹 변환 (기존 호환용 오버로드).
     */
    static ASTFigure createFigureFromVectorGroup(List<IDMLVectorShape> shapes,
                                                   IDMLPage page,
                                                   ASTImageLoader imageLoader,
                                                   ColorResolver colorResolver) {
        return createFigureFromVectorGroup(shapes, page, imageLoader, colorResolver, null, null);
    }

    /**
     * 같은 그룹에 속한 벡터 도형들을 하나의 복합 이미지로 래스터화.
     */
    static ASTFigure createFigureFromVectorGroup(List<IDMLVectorShape> shapes,
                                                   IDMLPage page,
                                                   ASTImageLoader imageLoader,
                                                   ColorResolver colorResolver,
                                                   ResolvedData resolvedData,
                                                   ResolvedPage resolvedPage) {
        if (shapes == null || shapes.isEmpty()) return null;

        List<IDMLVectorShape> sorted = new ArrayList<>(shapes);
        Collections.sort(sorted, new Comparator<IDMLVectorShape>() {
            public int compare(IDMLVectorShape a, IDMLVectorShape b) {
                return Integer.compare(a.zOrder(), b.zOrder());
            }
        });

        List<ASTImageLoader.ShapeWithColor> swcList = new ArrayList<>();
        for (IDMLVectorShape s : sorted) {
            String fillHex = ASTInlineObjectBuilder.resolveColorHex(s.fillColor(), colorResolver);
            String strokeHex = ASTInlineObjectBuilder.resolveColorHex(s.strokeColor(), colorResolver);
            // resolved data 폴백: IDML 색상이 없으면 InDesign DOM의 색상 이름으로 시도
            if (fillHex == null && strokeHex == null && resolvedData != null && s.selfId() != null) {
                ResolvedPageItem ri = resolvedData.getPageItemByIdmlId(s.selfId());
                if (ri != null) {
                    if (ri.fillColorName() != null) {
                        fillHex = resolvedData.resolveColorHex(ri.fillColorName());
                    }
                    if (ri.strokeColorName() != null) {
                        strokeHex = resolvedData.resolveColorHex(ri.strokeColorName());
                    }
                }
            }
            if (fillHex == null && strokeHex == null) {
                continue;
            }
            swcList.add(new ASTImageLoader.ShapeWithColor(s, fillHex, strokeHex, s.itemTransform()));
        }
        if (swcList.isEmpty()) {
            return null;
        }

        double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                page.geometricBounds(), page.itemTransform());
        double pageW = IDMLGeometry.width(page.geometricBounds());
        double pageH = IDMLGeometry.height(page.geometricBounds());
        double[] pageClipBounds = {pageAbs[1], pageAbs[0],
                pageAbs[1] + pageH, pageAbs[0] + pageW};

        String groupId = shapes.get(0).parentGroupId();

        ASTImageLoader.ImageResult result = imageLoader.rasterizeShapes(swcList, null, pageClipBounds);
        if (result == null || result.imageData == null) return null;

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (ASTImageLoader.ShapeWithColor sc : swcList) {
            double[] tb = sc.transformedBounds();
            if (tb == null) {
                continue;
            }
            if (tb[1] < minX) minX = tb[1];
            if (tb[0] < minY) minY = tb[0];
            if (tb[3] > maxX) maxX = tb[3];
            if (tb[2] > maxY) maxY = tb[2];
        }
        minX = Math.max(minX, pageAbs[0]);
        minY = Math.max(minY, pageAbs[1]);
        maxX = Math.min(maxX, pageAbs[0] + pageW);
        maxY = Math.min(maxY, pageAbs[1] + pageH);

        double groupCX = (minX + maxX) / 2.0;
        double groupCY = (minY + maxY) / 2.0;

        double relCX = groupCX - pageAbs[0];
        double relCY = groupCY - pageAbs[1];

        long wHwp = CoordinateConverter.pointsToHwpunits(result.widthPts);
        long hHwp = CoordinateConverter.pointsToHwpunits(result.heightPts);
        long xHwp = CoordinateConverter.pointsToHwpunits(relCX) - wHwp / 2;
        long yHwp = CoordinateConverter.pointsToHwpunits(relCY) - hHwp / 2;

        if (wHwp <= 0 || hHwp <= 0) return null;

        long pageWHwp = CoordinateConverter.pointsToHwpunits(pageW);
        long pageHHwp = CoordinateConverter.pointsToHwpunits(pageH);
        if (wHwp > pageWHwp * 3 || hHwp > pageHHwp * 3) return null;

        ASTFigure figure = new ASTFigure();
        figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        figure.x(xHwp);
        figure.y(yHwp);
        figure.width(wHwp);
        figure.height(hHwp);
        figure.zOrder(shapes.get(0).zOrder());
        figure.imageData(result.imageData);
        figure.imageFormat(result.format);
        figure.pixelWidth(result.pixelWidth);
        figure.pixelHeight(result.pixelHeight);
        figure.sourceId(groupId);
        return figure;
    }

    /**
     * 이미지 프레임 중복 제거 키 생성.
     */
    static String buildImageFrameDedupKey(IDMLImageFrame frame) {
        String uri = frame.linkResourceURI();
        if (uri == null || uri.isEmpty()) return null;

        double[] t = frame.itemTransform();
        double[] b = frame.geometricBounds();
        if (t == null || b == null) return null;

        long tx = Math.round(t[4] * 10);
        long ty = Math.round(t[5] * 10);
        long w = Math.round((b[3] - b[1]) * 10);
        long h = Math.round((b[2] - b[0]) * 10);

        return uri + "|" + tx + "," + ty + "|" + w + "x" + h;
    }
}
