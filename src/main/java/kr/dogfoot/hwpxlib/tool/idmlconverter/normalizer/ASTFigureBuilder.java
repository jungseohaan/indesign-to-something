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

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
                        double[] rel = resolvedPage.spreadBoundsToPageRelative(rb);
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
                        double[] rel = resolvedPage.spreadBoundsToPageRelative(rb);
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

                        // 부모 컨테이너 클리핑
                        double[] pcb = shape.parentClipBounds();
                        if (pcb != null) {
                            double origW = bbox[2] - bbox[0];
                            double origH = bbox[3] - bbox[1];
                            double clLeft  = Math.max(bbox[0], pcb[1]);
                            double clTop   = Math.max(bbox[1], pcb[0]);
                            double clRight = Math.min(bbox[2], pcb[3]);
                            double clBot   = Math.min(bbox[3], pcb[2]);
                            if (clRight > clLeft && clBot > clTop && origW > 0 && origH > 0) {
                                // pre-rendered PNG에 bleed가 있으므로,
                                // 단순 채우기 도형은 단색 PNG를 직접 생성
                                String fillHex = ASTInlineObjectBuilder.resolveColorHex(
                                        shape.fillColor(), colorResolver);
                                if (fillHex != null && !shape.hasStroke()
                                        && !shape.hasClippedChildren()) {
                                    byte[] solidPng = createSolidColorPng(fillHex,
                                            shape.fillTint(), clRight - clLeft, clBot - clTop,
                                            shape.cornerRadius(), shape.cornerRadii());
                                    if (solidPng != null) {
                                        imgResult.imageData = solidPng;
                                        imgResult.format = "png";
                                    }
                                }
                                bbox = new double[]{clLeft, clTop, clRight, clBot};
                            }
                        }

                        figX = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
                        figY = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);
                        figW = CoordinateConverter.pointsToHwpunits(bbox[2] - bbox[0]);
                        figH = CoordinateConverter.pointsToHwpunits(bbox[3] - bbox[1]);
                        // 선 도형(수평/수직 GraphicLine): 한 축이 0이면 strokeWeight로 확장
                        if (figW <= 0 && figH > 0 && shape.strokeWeight() > 0) {
                            figW = Math.max(CoordinateConverter.pointsToHwpunits(
                                    shape.strokeWeight()), 100);
                        }
                        if (figH <= 0 && figW > 0 && shape.strokeWeight() > 0) {
                            figH = Math.max(CoordinateConverter.pointsToHwpunits(
                                    shape.strokeWeight()), 100);
                        }
                    }
                    // PNG 비율로 높이 보정 (한 축이 0인 선 도형은 건너뜀)
                    // 부모 클리핑된 도형은 이미 crop되었으므로 비율 보정 생략
                    if (shape.parentClipBounds() == null
                            && imgResult.pixelWidth > 0 && figW > 0 && figH > 0) {
                        long geoBottom = figY + figH;
                        long pngH = Math.round(figW * ((double) imgResult.pixelHeight / imgResult.pixelWidth));
                        // 선 도형(한 축이 매우 작은 경우)은 비율 보정 적용하지 않음
                        if (pngH > 0) {
                            figH = pngH;
                        }
                        // 음수 Y (페이지 위 확장) 시 바닥 가장자리 기하학적 위치 유지
                        if (figY < 0) {
                            figY = geoBottom - figH;
                        }
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
                    fig.parentGroupId(shape.parentGroupId());
                    fig.sourceId(shape.selfId());
                    return fig;
                }
            }
        }

        // 복수 클리핑 자식 처리
        if (shape.hasClippedChildren()) {
            return createFigureFromClippedGroup(shape, page, imageLoader, colorResolver,
                    resolvedData, resolvedPage);
        }

        // pre-rendered PNG 없는 단순 채우기 도형은 단색 PNG 직접 생성
        // fromGroup 도형은 그룹 합성이 필요하므로, 배경용으로 마킹된 것만 허용
        String fillHex = ASTInlineObjectBuilder.resolveColorHex(shape.fillColor(), colorResolver);
        boolean allowSolidFallback = !shape.fromGroup()
                || shape.parentClipBounds() != null
                || shape.keepAsBackground();
        if (fillHex != null && allowSolidFallback) {
            double[] bbox = IDMLGeometry.getTransformedBoundingBox(
                    shape.geometricBounds(), shape.itemTransform());
            double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                    page.geometricBounds(), page.itemTransform());
            long figX = CoordinateConverter.pointsToHwpunits(bbox[0] - pageAbs[0]);
            long figY = CoordinateConverter.pointsToHwpunits(bbox[1] - pageAbs[1]);
            long figW = CoordinateConverter.pointsToHwpunits(bbox[2] - bbox[0]);
            long figH = CoordinateConverter.pointsToHwpunits(bbox[3] - bbox[1]);
            if (figW > 0 && figH > 0) {
                double[] cornerRadii = shape.cornerRadii();
                double cornerR = shape.cornerRadius();
                byte[] solidPng = createSolidColorPng(fillHex, shape.fillTint(),
                        bbox[2] - bbox[0], bbox[3] - bbox[1],
                        cornerR, cornerRadii);
                if (solidPng != null) {
                    ASTFigure fig = new ASTFigure();
                    fig.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
                    fig.x(figX);
                    fig.y(figY);
                    fig.width(figW);
                    fig.height(figH);
                    fig.zOrder(shape.zOrder());
                    fig.imageData(solidPng);
                    fig.imageFormat("png");
                    fig.fromGroup(shape.fromGroup());
                    fig.parentGroupId(shape.parentGroupId());
                    fig.sourceId(shape.selfId());
                    return fig;
                }
            }
        }

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
                    double[] rel = resolvedPage.spreadBoundsToPageRelative(rb);
                    long figX = CoordinateConverter.pointsToHwpunits(rel[0]);
                    long figY = CoordinateConverter.pointsToHwpunits(rel[1]);
                    long figW = CoordinateConverter.pointsToHwpunits(rW);
                    long figH = CoordinateConverter.pointsToHwpunits(rH);
                    // PNG 비율로 높이 보정
                    if (imgResult.pixelWidth > 0) {
                        long geoBottom = figY + figH;
                        figH = Math.round(figW * ((double) imgResult.pixelHeight / imgResult.pixelWidth));
                        // 음수 Y (페이지 위 확장) 시 바닥 가장자리 기하학적 위치 유지
                        if (figY < 0) {
                            figY = geoBottom - figH;
                        }
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

    /**
     * 단색 채우기 PNG 생성. parentClipBounds로 클리핑된 도형에 사용.
     * pre-rendered PNG의 bleed 문제를 회피하기 위해 직접 생성.
     */
    private static byte[] createSolidColorPng(String fillHex, double tint,
                                                double widthPt, double heightPt,
                                                double cornerRadius, double[] cornerRadii) {
        try {
            if (fillHex == null || fillHex.length() < 7) return null;
            int r = Integer.parseInt(fillHex.substring(1, 3), 16);
            int g = Integer.parseInt(fillHex.substring(3, 5), 16);
            int b = Integer.parseInt(fillHex.substring(5, 7), 16);
            if (tint >= 0 && tint < 100) {
                double t = tint / 100.0;
                r = (int) (255 + (r - 255) * t);
                g = (int) (255 + (g - 255) * t);
                b = (int) (255 + (b - 255) * t);
            }
            // 적절한 해상도 (2x for retina-like quality)
            int pw = Math.max(4, (int) Math.round(widthPt * 2));
            int ph = Math.max(4, (int) Math.round(heightPt * 2));
            if (pw > 2000) pw = 2000;
            if (ph > 2000) ph = 2000;

            double scaleX = pw / widthPt;
            double scaleY = ph / heightPt;

            BufferedImage img = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB);
            java.awt.Color color = new java.awt.Color(r, g, b, 255);
            Graphics2D gfx = img.createGraphics();
            gfx.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            gfx.setColor(color);

            // per-corner radii 지원 (TL, TR, BL, BR)
            double rTL = 0, rTR = 0, rBL = 0, rBR = 0;
            if (cornerRadii != null && cornerRadii.length >= 4) {
                rTL = cornerRadii[0]; rTR = cornerRadii[1];
                rBL = cornerRadii[2]; rBR = cornerRadii[3];
            } else if (cornerRadius > 0) {
                rTL = rTR = rBL = rBR = cornerRadius;
            }

            boolean hasRoundCorners = rTL > 0 || rTR > 0 || rBL > 0 || rBR > 0;
            if (!hasRoundCorners) {
                gfx.fillRect(0, 0, pw, ph);
            } else {
                // GeneralPath로 per-corner rounded rect 생성
                java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath();
                double sTL = rTL * Math.min(scaleX, scaleY);
                double sTR = rTR * Math.min(scaleX, scaleY);
                double sBL = rBL * Math.min(scaleX, scaleY);
                double sBR = rBR * Math.min(scaleX, scaleY);

                path.moveTo(sTL, 0);
                path.lineTo(pw - sTR, 0);
                if (sTR > 0) path.quadTo(pw, 0, pw, sTR);
                else path.lineTo(pw, 0);
                path.lineTo(pw, ph - sBR);
                if (sBR > 0) path.quadTo(pw, ph, pw - sBR, ph);
                else path.lineTo(pw, ph);
                path.lineTo(sBL, ph);
                if (sBL > 0) path.quadTo(0, ph, 0, ph - sBL);
                else path.lineTo(0, ph);
                path.lineTo(0, sTL);
                if (sTL > 0) path.quadTo(0, 0, sTL, 0);
                else path.lineTo(0, 0);
                path.closePath();
                gfx.fill(path);
            }
            gfx.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

}
