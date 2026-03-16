package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
     * IDMLImageFrame → ASTFigure 변환 (플로팅 이미지, 기존 호환용 오버로드).
     */
    static ASTFigure createFigureFromImageFrame(IDMLImageFrame imgFrame,
                                                 IDMLPage page,
                                                 ASTImageLoader imageLoader,
                                                 ColorResolver colorResolver) {
        return createFigureFromImageFrame(imgFrame, page, imageLoader, colorResolver, null, null);
    }

    /**
     * IDMLImageFrame → ASTFigure 변환 (플로팅 이미지).
     * resolved 좌표가 있으면 우선 사용, 없으면 IDML 변환행렬 fallback.
     */
    static ASTFigure createFigureFromImageFrame(IDMLImageFrame imgFrame,
                                                 IDMLPage page,
                                                 ASTImageLoader imageLoader,
                                                 ColorResolver colorResolver,
                                                 ResolvedData resolvedData,
                                                 ResolvedPage resolvedPage) {
        double[] t = imgFrame.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001
                || t[0] < 0 || t[3] < 0);

        long wHwp = 0, hHwp = 0, xHwp = 0, yHwp = 0;

        // Resolved geometry path — 우선 사용
        ResolvedPageItem resolvedItem = null;
        if (resolvedData != null && imgFrame.selfId() != null) {
            resolvedItem = resolvedData.getPageItemByIdmlId(imgFrame.selfId());
        }
        boolean usedResolved = false;
        if (resolvedItem != null && resolvedItem.geometricBounds() != null
                && resolvedPage != null && resolvedPage.bounds() != null) {
            double[] gb = resolvedItem.geometricBounds();
            double rW = gb[3] - gb[1];
            double rH = gb[2] - gb[0];
            if (rW > 0 && rH > 0) {
                // resolved geometry가 페이지 폭의 1.5배를 초과하면 스프레드 전체를 덮는
                // 컨테이너일 수 있음 → 이미지 클리핑과 표시 크기 불일치 방지를 위해 IDML 폴백
                double pageW = IDMLGeometry.width(page.geometricBounds());
                double pageH = IDMLGeometry.height(page.geometricBounds());
                boolean spreadSpanning = rW > pageW * 1.5 || rH > pageH * 1.5;
                if (!spreadSpanning) {
                    double[] rel = resolvedPage.toPageRelative(gb);
                    xHwp = CoordinateConverter.pointsToHwpunits(rel[0]);
                    yHwp = CoordinateConverter.pointsToHwpunits(rel[1]);
                    wHwp = CoordinateConverter.pointsToHwpunits(rW);
                    hHwp = CoordinateConverter.pointsToHwpunits(rH);
                    usedResolved = true;
                }
            }
        }

        // IDML fallback
        if (!usedResolved)
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

        double cornerR = imgFrame.hasRoundedCorners() ? imgFrame.cornerRadius() : 0;
        double[] cornerRadii = imgFrame.hasRoundedCorners() ? imgFrame.cornerRadii() : null;

        ASTImageLoader.ImageResult result = null;
        boolean usedRenderedImage = false;

        // 1) InDesign에서 직접 렌더링된 PDF 프레임 PNG가 있으면 우선 사용
        if (resolvedData != null && imgFrame.selfId() != null) {
            RenderedGroup pdfFrame = resolvedData.getRenderedPdfFrameByIdmlId(imgFrame.selfId());
            if (pdfFrame != null && pdfFrame.file() != null) {
                result = imageLoader.loadRenderedImage(pdfFrame.file(), wHwp, hHwp);
            }
        }

        // 2) 그룹 렌더링된 이미지 (PSD 스프라이트 등 — 자식 이미지 프레임이 그룹으로 렌더링된 경우)
        if (result == null && resolvedData != null && imgFrame.selfId() != null) {
            RenderedGroup imgRendered = resolvedData.getRenderedImageFrameByIdmlId(imgFrame.selfId());
            if (imgRendered != null && imgRendered.file() != null
                    && imgRendered.childImageIds() != null && imgRendered.childImageIds().length > 0) {
                // 같은 그룹의 두 번째 이상 자식이면 건너뛰기 (중복 방지)
                if (resolvedData.markImageGroupProcessed(imgRendered.id())) {
                    return null;
                }
                // 렌더링된 이미지의 bounds로 위치/크기 보정
                if (imgRendered.bounds() != null && resolvedPage != null && resolvedPage.bounds() != null) {
                    double[] rb = imgRendered.bounds();
                    double rW = rb[3] - rb[1];
                    double rH = rb[2] - rb[0];
                    if (rW > 0 && rH > 0) {
                        double[] rel = resolvedPage.toPageRelative(rb);
                        xHwp = CoordinateConverter.pointsToHwpunits(rel[0]);
                        yHwp = CoordinateConverter.pointsToHwpunits(rel[1]);
                        wHwp = CoordinateConverter.pointsToHwpunits(rW);
                        hHwp = CoordinateConverter.pointsToHwpunits(rH);
                    }
                }
                result = imageLoader.loadRenderedImage(imgRendered.file(), wHwp, hHwp);
                if (result != null) {
                    usedRenderedImage = true;
                }
            }
        }

        // 3) 내장(붙여넣기) 이미지: Contents base64 데이터 사용
        if (result == null
                && (imgFrame.linkResourceURI() == null || imgFrame.linkResourceURI().isEmpty())
                && imgFrame.hasEmbeddedContents()) {
            result = imageLoader.loadEmbeddedImage(
                    imgFrame.embeddedContents(), wHwp, hHwp,
                    imgFrame.imageTransform(), frameBounds, imgFrame.graphicBounds(),
                    imgFrame.framePath(), cornerR, cornerRadii);
        }

        // 4) 링크된 이미지 파일 로드 (Ghostscript PDF 변환 포함)
        if (result == null) {
            result = imageLoader.loadImage(
                    imgFrame.linkResourceURI(), wHwp, hHwp,
                    imgFrame.imageTransform(), frameBounds, imgFrame.graphicBounds(),
                    visibleLayers, layerSig, imgFrame.framePath(), cornerR, cornerRadii);
        }

        // 5) 폴백: InDesign에서 렌더링된 단독 이미지 프레임 PNG (Links 로드 실패 시)
        if ((result == null || result.imageData == null)
                && resolvedData != null && imgFrame.selfId() != null) {
            RenderedGroup imgRendered = resolvedData.getRenderedImageFrameByIdmlId(imgFrame.selfId());
            if (imgRendered != null && imgRendered.file() != null && !usedRenderedImage) {
                // rendered bounds로 위치/크기 보정
                if (imgRendered.bounds() != null && resolvedPage != null && resolvedPage.bounds() != null) {
                    double[] rb = imgRendered.bounds();
                    double rW = rb[3] - rb[1];
                    double rH = rb[2] - rb[0];
                    if (rW > 0 && rH > 0) {
                        double[] rel = resolvedPage.toPageRelative(rb);
                        xHwp = CoordinateConverter.pointsToHwpunits(rel[0]);
                        yHwp = CoordinateConverter.pointsToHwpunits(rel[1]);
                        wHwp = CoordinateConverter.pointsToHwpunits(rW);
                        hHwp = CoordinateConverter.pointsToHwpunits(rH);
                    }
                }
                result = imageLoader.loadRenderedImage(imgRendered.file(), wHwp, hHwp);
                if (result != null) {
                    usedRenderedImage = true;
                }
            }
        }

        if (result == null || result.imageData == null) return null;

        // 렌더링된 이미지는 InDesign이 모든 효과를 적용했으므로 후처리 불필요
        if (usedRenderedImage) {
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
            figure.sourceId(imgFrame.selfId());
            figure.fromGroup(imgFrame.fromGroup());
            return figure;
        }

        // 그레이스케일 모노톤 이미지: FillColor로 채색 (QR코드 등 검정 그레이스케일도 포함)
        if (imgFrame.needsGrayscaleColorization() && colorResolver != null) {
            String fillHex = colorResolver.resolve(imgFrame.imageFillColor());
            double tint = imgFrame.imageFillTint();
            byte[] colorized = ASTImageLoader.colorizeGrayscaleImage(
                    result.imageData, fillHex, tint);
            if (colorized != null) {
                result.imageData = colorized;
                result.format = "png";
            }
        }

        // 그라디언트 페더 알파 마스크 적용
        if (imgFrame.hasGradientFeather()) {
            byte[] feathered = ASTImageLoader.applyGradientFeatherToImage(
                    result.imageData,
                    imgFrame.gradientFeatherAngle(),
                    imgFrame.gradientFeatherLength(),
                    imgFrame.gradientFeatherStart(),
                    frameBounds,
                    imgFrame.imageTransform(),
                    imgFrame.graphicBounds());
            if (feathered != null) {
                result.imageData = feathered;
                result.format = "png";
            }
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

        // 페이지에 10% 미만만 보이는 이미지는 건너뛰기 (스프레드 경계 슬리버 방지)
        double visibleFracW = 1.0 - figure.cropLeftFraction() - figure.cropRightFraction();
        double visibleFracH = 1.0 - figure.cropTopFraction() - figure.cropBottomFraction();
        if (visibleFracW < 0.10 || visibleFracH < 0.10) return null;

        figure.fromGroup(imgFrame.fromGroup());
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
        // Pre-rendered 복합 그래픽 프레임이 있으면 우선 사용
        // 단, resolved bounds(visibleBounds)가 페이지의 80% 이상이면 배경으로 간주하여 폴백
        if (resolvedData != null && shape.selfId() != null) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup renderedGraphic =
                    resolvedData.getRenderedGraphicFrameByIdmlId(shape.selfId());
            // 배경 필터 제거 — InDesign 렌더링 PNG를 그대로 사용
            // (이전에는 Java 래스터라이즈 폴백이 있었으나 제거됨)
            if (renderedGraphic != null && renderedGraphic.file() != null) {
                ASTImageLoader.ImageResult imgResult = imageLoader.loadRenderedImage(
                        renderedGraphic.file(),
                        CoordinateConverter.pointsToHwpunits(
                                IDMLGeometry.width(shape.geometricBounds())),
                        CoordinateConverter.pointsToHwpunits(
                                IDMLGeometry.height(shape.geometricBounds())));
                if (imgResult != null) {
                    long figX, figY, figW, figH;
                    // IDML 좌표 사용 (facing pages에서 resolved bounds 좌표 오류 방지)
                    {
                        double[] bbox = IDMLGeometry.getTransformedBoundingBox(
                                shape.geometricBounds(), shape.itemTransform());
                        double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                                page.geometricBounds(), page.itemTransform());
                        figX = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
                        figY = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);
                        figW = CoordinateConverter.pointsToHwpunits(bbox[2] - bbox[0]);
                        figH = CoordinateConverter.pointsToHwpunits(bbox[3] - bbox[1]);
                    }
                    // PNG 비율로 높이 보정
                    if (imgResult.pixelWidth > 0) {
                        figH = Math.round(figW * ((double) imgResult.pixelHeight / imgResult.pixelWidth));
                    }
                    ASTFigure fig = new ASTFigure();
                    fig.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
                    fig.x(figX);
                    fig.y(figY);
                    fig.width(figW);
                    fig.height(figH);
                    fig.zOrder(shape.zOrder());
                    fig.imageData(imgResult.imageData);
                    fig.imageFormat(imgResult.format);
                    fig.pixelWidth(imgResult.pixelWidth);
                    fig.pixelHeight(imgResult.pixelHeight);
                    fig.fromGroup(shape.fromGroup());
                    fig.sourceId(shape.selfId());
                    System.out.println("[RenderedGraphic] " + shape.selfId()
                            + " → " + renderedGraphic.file());
                    return fig;
                }
            }
        }

        // 복수 클리핑 자식 처리
        if (shape.hasClippedChildren()) {
            return createFigureFromClippedGroup(shape, page, imageLoader, colorResolver,
                    resolvedData, resolvedPage);
        }

        // pre-rendered PNG 없으면 표시하지 않음 (Java 래스터라이즈 제거됨)
        System.out.println("[VectorShape] " + shape.selfId() + " — no pre-rendered PNG, skipping");
        return null;
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
        figure.fromGroup(clipFrame.fromGroup());
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
        figure.fromGroup(clipFrame.fromGroup());
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

        String groupId = shapes.get(0).parentGroupId();

        // Pre-rendered 벡터 그룹 PNG 확인 (InDesign에서 렌더링한 이미지)
        if (resolvedData != null && groupId != null) {
            RenderedGroup renderedGroup = resolvedData.getRenderedGraphicFrameByIdmlId(groupId);
            if (renderedGroup != null && renderedGroup.file() != null
                    && renderedGroup.bounds() != null && resolvedPage != null
                    && resolvedPage.bounds() != null) {
                double[] rb = renderedGroup.bounds();
                double rW = rb[3] - rb[1];
                double rH = rb[2] - rb[0];
                double[] rpb = resolvedPage.bounds();
                double pageW = rpb[3] - rpb[1];
                double pageH = rpb[2] - rpb[0];
                // 배경 프레임(>80% 페이지) 건너뜀
                if (rW > pageW * 0.8 && rH > pageH * 0.8) {
                    return null;
                }

                ASTImageLoader.ImageResult imgResult = imageLoader.loadRenderedImage(
                        renderedGroup.file(),
                        CoordinateConverter.pointsToHwpunits(rW),
                        CoordinateConverter.pointsToHwpunits(rH));
                if (imgResult != null) {
                    double[] pb = resolvedPage.bounds();
                    long figX = CoordinateConverter.pointsToHwpunits(rb[1] - pb[1]);
                    long figY = CoordinateConverter.pointsToHwpunits(rb[0] - pb[0]);
                    long figW = CoordinateConverter.pointsToHwpunits(rW);
                    long figH = CoordinateConverter.pointsToHwpunits(rH);
                    // PNG 비율로 높이 보정
                    if (imgResult.pixelWidth > 0) {
                        figH = Math.round(figW * ((double) imgResult.pixelHeight / imgResult.pixelWidth));
                    }

                    ASTFigure figure = new ASTFigure();
                    figure.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
                    figure.x(figX);
                    figure.y(figY);
                    figure.width(figW);
                    figure.height(figH);
                    // z-order: 그룹 내 최대값
                    int maxZ = shapes.get(0).zOrder();
                    for (IDMLVectorShape s : shapes) {
                        if (s.zOrder() > maxZ) maxZ = s.zOrder();
                    }
                    figure.zOrder(maxZ);
                    figure.imageData(imgResult.imageData);
                    figure.imageFormat(imgResult.format);
                    figure.pixelWidth(imgResult.pixelWidth);
                    figure.pixelHeight(imgResult.pixelHeight);
                    figure.fromGroup(shapes.get(0).fromGroup());
                    figure.sourceId(groupId);
                    System.out.println("[RenderedVectorGroup] " + groupId
                            + " → " + renderedGroup.file());
                    return figure;
                }
            }
        }

        // pre-rendered PNG 없으면 표시하지 않음 (Java 래스터라이즈 제거됨)
        System.out.println("[VectorGroup] " + groupId + " — no pre-rendered PNG, skipping");
        return null;
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
