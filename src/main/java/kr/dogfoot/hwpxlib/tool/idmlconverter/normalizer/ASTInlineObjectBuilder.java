package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 인라인 그래픽, 이미지, 벡터 도형, 테이블 변환 로직.
 * Stage4_BuildAST에서 분리됨.
 */
class ASTInlineObjectBuilder {

    private static String decodeURI(String uri) {
        if (uri == null) return null;
        try {
            return URLDecoder.decode(uri, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return uri;
        }
    }

    /**
     * InlineGraphic 내부의 TextFrame을 재귀적으로 수집하여 ASTParagraph에 추가.
     * 중첩 Group 구조에서도 모든 TextFrame을 찾아낸다.
     */
    static void collectChildTextFrames(IDMLCharacterRun.InlineGraphic ig,
                                        ASTParagraph para,
                                        IDMLDocument idmlDoc,
                                        ColorResolver colorResolver,
                                        ASTImageLoader imageLoader,
                                        GroupBackground bg) {
        collectChildTextFramesInternal(ig, para, null, idmlDoc, colorResolver, imageLoader, bg,
                false, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * IMAGE 그룹 내부의 TextFrame을 오버레이 프레임으로 수집하여 이미지 객체에 첨부.
     * 각 텍스트프레임은 이미지 컨테이너 내부에 중첩되어 이미지 위에 올바르게 배치된다.
     *
     * @param imageObj 오버레이 프레임을 첨부할 IMAGE 인라인 객체
     */
    static void collectOverlayFrames(IDMLCharacterRun.InlineGraphic ig,
                                      ASTInlineObject imageObj,
                                      IDMLDocument idmlDoc,
                                      ColorResolver colorResolver,
                                      ASTImageLoader imageLoader,
                                      GroupBackground bg) {
        // 이미지 프레임을 찾고 그룹 내 위치 계산
        IDMLCharacterRun.InlineGraphic imageFrame = findImageFrame(ig);
        double[] imgPos = findImagePositionInGroup(ig, imageFrame);

        // 콘텐츠 바운딩 박스: 이미지 프레임 + 텍스트 프레임만 (배경 사각형 등 장식용 그래픽 제외)
        // computeGroupVisualBounds()는 모든 자식을 포함하여 컨테이너가 불필요하게 커지는 문제가 있음
        double[] contentBBox = computeContentBounds(ig, imgPos);
        double rootLeft = contentBBox[0];   // minX
        double rootTop = contentBBox[1];    // minY
        double contentWidthPts = contentBBox[2] - contentBBox[0];
        double contentHeightPts = contentBBox[3] - contentBBox[1];

        long containerW, containerH, imgOffX, imgOffY;
        if (imgPos != null && imgPos[2] > 0 && imgPos[3] > 0
                && contentWidthPts > 0 && contentHeightPts > 0) {
            double imgFrameWPts = imgPos[2];
            double imgFrameHPts = imgPos[3];
            // 컨테이너 = 콘텐츠 바운딩 박스 표시 크기 (이미지 표시 크기에서 비율로 확대)
            containerW = Math.round((double) imageObj.width() * contentWidthPts / imgFrameWPts);
            containerH = Math.round((double) imageObj.height() * contentHeightPts / imgFrameHPts);
            // 이미지의 컨테이너 내 오프셋
            double imgOffXPts = imgPos[0] - rootLeft;
            double imgOffYPts = imgPos[1] - rootTop;
            imgOffX = Math.round((double) containerW * imgOffXPts / contentWidthPts);
            imgOffY = Math.round((double) containerH * imgOffYPts / contentHeightPts);
        } else {
            // 폴백: 이미지 프레임 위치를 찾을 수 없으면 기존 동작 유지
            containerW = imageObj.width();
            containerH = imageObj.height();
            imgOffX = 0;
            imgOffY = 0;
        }

        imageObj.containerWidth(containerW);
        imageObj.containerHeight(containerH);
        imageObj.imageOffsetX(imgOffX);
        imageObj.imageOffsetY(imgOffY);

        // 오버레이 위치 스케일링은 컨테이너(콘텐츠 bbox) 표시 크기 기준으로 수행
        collectChildTextFramesInternal(ig, null, imageObj, idmlDoc, colorResolver, imageLoader, bg,
                true, containerW, containerH,
                contentWidthPts, contentHeightPts, rootLeft, rootTop, 0, 0);
    }

    /**
     * 이미지 프레임 + 텍스트 프레임만으로 콘텐츠 바운딩 박스를 계산.
     * 배경 사각형 등 장식용 그래픽은 제외하여 컨테이너가 불필요하게 커지는 것을 방지.
     * @param imgPos 이미지 프레임의 그룹 내 위치 [left, top, width, height] (null 가능)
     * @return [minX, minY, maxX, maxY] (points, 그룹 로컬 좌표계)
     */
    private static double[] computeContentBounds(IDMLCharacterRun.InlineGraphic ig,
                                                   double[] imgPos) {
        double[] bounds = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};

        // 1. 이미지 프레임
        if (imgPos != null) {
            bounds[0] = Math.min(bounds[0], imgPos[0]);
            bounds[1] = Math.min(bounds[1], imgPos[1]);
            bounds[2] = Math.max(bounds[2], imgPos[0] + imgPos[2]);
            bounds[3] = Math.max(bounds[3], imgPos[1] + imgPos[3]);
        }

        // 2. 텍스트 프레임 (직접 + 중첩 그룹 내 재귀)
        includeTextFrameBoundsRecursive(ig, bounds, 0, 0);

        if (bounds[0] == Double.MAX_VALUE) {
            // 폴백: 콘텐츠가 없으면 전체 그룹 바운드 사용
            return computeGroupVisualBounds(ig);
        }
        return bounds;
    }

    private static void includeTextFrameBoundsRecursive(IDMLCharacterRun.InlineGraphic ig,
                                                          double[] bounds,
                                                          double accTx, double accTy) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            double[] tb = childTf.geometricBounds();
            double[] tt = childTf.itemTransform();
            if (tb != null && tt != null) {
                double left = tb[1] + tt[4] + accTx;
                double top = tb[0] + tt[5] + accTy;
                double right = tb[3] + tt[4] + accTx;
                double bottom = tb[2] + tt[5] + accTy;
                bounds[0] = Math.min(bounds[0], left);
                bounds[1] = Math.min(bounds[1], top);
                bounds[2] = Math.max(bounds[2], right);
                bounds[3] = Math.max(bounds[3], bottom);
            }
        }
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            double[] ct = childIg.itemTransform();
            double childAccTx = accTx + (ct != null ? ct[4] : 0);
            double childAccTy = accTy + (ct != null ? ct[5] : 0);
            includeTextFrameBoundsRecursive(childIg, bounds, childAccTx, childAccTy);
        }
    }

    /**
     * 이미지 프레임의 루트 그룹 좌표계 내 위치를 재귀적으로 찾는다.
     * @return [left, top, width, height] (points, 그룹 로컬 좌표계) 또는 null
     */
    private static double[] findImagePositionInGroup(
            IDMLCharacterRun.InlineGraphic group,
            IDMLCharacterRun.InlineGraphic target) {
        if (target == null) return null;
        return findImagePosRecursive(group, target, 0, 0);
    }

    private static double[] findImagePosRecursive(
            IDMLCharacterRun.InlineGraphic current,
            IDMLCharacterRun.InlineGraphic target,
            double accTx, double accTy) {
        for (IDMLCharacterRun.InlineGraphic child : current.childGraphics()) {
            double[] ct = child.itemTransform();
            double childAccTx = accTx + (ct != null ? ct[4] : 0);
            double childAccTy = accTy + (ct != null ? ct[5] : 0);

            if (child == target) {
                double[] cb = child.geometricBounds();
                if (cb != null) {
                    return new double[]{
                            cb[1] + childAccTx,   // left in group space
                            cb[0] + childAccTy,   // top in group space
                            cb[3] - cb[1],         // width
                            cb[2] - cb[0]          // height
                    };
                }
                return new double[]{childAccTx, childAccTy,
                        child.widthPoints(), child.heightPoints()};
            }

            double[] found = findImagePosRecursive(child, target, childAccTx, childAccTy);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 그룹의 시각적 바운딩 박스를 모든 직접 자식에서 계산.
     * @return [minX, minY, maxX, maxY] (points, 그룹 로컬 좌표계)
     */
    private static double[] computeGroupVisualBounds(IDMLCharacterRun.InlineGraphic ig) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            double[] cb = child.geometricBounds();
            double[] ct = child.itemTransform();
            if (cb != null && ct != null) {
                double left = cb[1] + ct[4];
                double top = cb[0] + ct[5];
                double right = cb[3] + ct[4];
                double bottom = cb[2] + ct[5];
                minX = Math.min(minX, left);
                minY = Math.min(minY, top);
                maxX = Math.max(maxX, right);
                maxY = Math.max(maxY, bottom);
            }
        }
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            double[] tb = childTf.geometricBounds();
            double[] tt = childTf.itemTransform();
            if (tb != null && tt != null) {
                double left = tb[1] + tt[4];
                double top = tb[0] + tt[5];
                double right = tb[3] + tt[4];
                double bottom = tb[2] + tt[5];
                minX = Math.min(minX, left);
                minY = Math.min(minY, top);
                maxX = Math.max(maxX, right);
                maxY = Math.max(maxY, bottom);
            }
        }

        if (minX == Double.MAX_VALUE) {
            return new double[]{0, 0, 0, 0};
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /**
     * @param para            비-오버레이 모드에서 결과를 추가할 단락 (오버레이 시 null)
     * @param targetImageObj  오버레이 모드에서 결과를 첨부할 이미지 객체 (비-오버레이 시 null)
     */
    private static void collectChildTextFramesInternal(IDMLCharacterRun.InlineGraphic ig,
                                                         ASTParagraph para,
                                                         ASTInlineObject targetImageObj,
                                                         IDMLDocument idmlDoc,
                                                         ColorResolver colorResolver,
                                                         ASTImageLoader imageLoader,
                                                         GroupBackground bg,
                                                         boolean isOverlay,
                                                         long imageDisplayWidth,
                                                         long imageDisplayHeight,
                                                         double groupWidthPts,
                                                         double groupHeightPts,
                                                         double rootLeft,
                                                         double rootTop,
                                                         double accTx,
                                                         double accTy) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            ASTInlineObject childObj = ASTStoryConverter.createInlineObjectFromTextFrame(childTf, idmlDoc, colorResolver, imageLoader);
            if (childObj != null) {
                // 부모 그룹의 anchoredPosition을 자식에 전달
                if (childObj.anchoredPosition() == null && ig.anchoredPosition() != null) {
                    childObj.anchoredPosition(ig.anchoredPosition());
                }
                if (bg != null && childObj.fillColor() == null) {
                    childObj.fillColor(bg.fillHex);
                    childObj.fillTint(bg.fillTint);
                    if (bg.strokeHex != null) {
                        childObj.strokeColor(bg.strokeHex);
                        childObj.strokeWeight(bg.strokeWeight);
                        childObj.strokeTint(bg.strokeTint);
                    }
                    if (bg.cornerRadius > 0) {
                        childObj.cornerRadius(bg.cornerRadius);
                    }
                    // 배경 사각형과 텍스트 프레임의 위치 차이 → 암묵적 텍스트 여백
                    if (bg.hasBounds) {
                        applyImplicitTextMargin(childObj, childTf, bg);
                    }
                }

                if (isOverlay && targetImageObj != null) {
                    // 오버레이 모드: 부모 이미지 내 상대 위치 계산 후 이미지 객체에 첨부
                    applyOverlayPosition(childObj, childTf, accTx, accTy,
                            imageDisplayWidth, imageDisplayHeight,
                            groupWidthPts, groupHeightPts, rootLeft, rootTop);
                    targetImageObj.addOverlayFrame(childObj);
                } else {
                    para.addItem(childObj);
                }
            }
        }
        // 중첩 그래픽(Group 등) 내부의 TextFrame도 재귀적으로 처리
        // 자식 그래픽의 itemTransform 번역 오프셋을 누적
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            double childAccTx = accTx;
            double childAccTy = accTy;
            double[] childTransform = childIg.itemTransform();
            if (childTransform != null) {
                childAccTx += childTransform[4];
                childAccTy += childTransform[5];
            }
            collectChildTextFramesInternal(childIg, para, targetImageObj, idmlDoc, colorResolver, imageLoader, bg,
                    isOverlay, imageDisplayWidth, imageDisplayHeight,
                    groupWidthPts, groupHeightPts, rootLeft, rootTop,
                    childAccTx, childAccTy);
        }
    }

    /**
     * 오버레이 위치 계산: 텍스트프레임의 루트 그룹 내 상대 위치를 ASTInlineObject에 설정.
     * 누적 번역 오프셋(accTx, accTy)으로 중첩 Group 내 텍스트프레임도 처리.
     * 이미지 표시 크기와 그룹 바운드의 비율로 스케일링.
     */
    private static void applyOverlayPosition(ASTInlineObject obj, IDMLTextFrame tf,
                                               double accTx, double accTy,
                                               long imageDisplayWidth, long imageDisplayHeight,
                                               double groupWidthPts, double groupHeightPts,
                                               double rootLeft, double rootTop) {
        double[] tfBounds = tf.geometricBounds();
        double[] tfTransform = tf.itemTransform();
        if (tfBounds == null || tfTransform == null) return;

        // 텍스트프레임의 루트 그룹 좌표계 위치 (비회전 가정)
        double tfX = tfBounds[1] + tfTransform[4] + accTx;
        double tfY = tfBounds[0] + tfTransform[5] + accTy;

        // 루트 그룹의 시각적 원점(minX, minY) 기준 상대 위치 (points)
        double relX = tfX - rootLeft;
        double relY = tfY - rootTop;
        if (relX < 0) relX = 0;
        if (relY < 0) relY = 0;

        // 그룹 바운드(points) → 이미지 표시 크기(HWPUNIT) 비율로 스케일
        long overlayX, overlayY;
        if (groupWidthPts > 0 && groupHeightPts > 0) {
            overlayX = Math.round(relX / groupWidthPts * imageDisplayWidth);
            overlayY = Math.round(relY / groupHeightPts * imageDisplayHeight);
        } else {
            overlayX = CoordinateConverter.pointsToHwpunits(relX);
            overlayY = CoordinateConverter.pointsToHwpunits(relY);
        }

        obj.isOverlay(true);
        obj.overlayX(overlayX);
        obj.overlayY(overlayY);
        obj.overlayParentWidth(imageDisplayWidth);
        obj.overlayParentHeight(imageDisplayHeight);
    }

    /**
     * 배경 사각형과 텍스트 프레임의 위치 차이를 암묵적 텍스트 여백으로 적용.
     * InDesign에서는 배경 사각형 안에 텍스트 프레임이 offset으로 배치되어 시각적 여백을 만들지만,
     * HWPX에서는 글상자 크기를 배경 크기로 확장하고 textMargin으로 여백을 표현한다.
     */
    private static void applyImplicitTextMargin(ASTInlineObject obj, IDMLTextFrame tf,
                                                 GroupBackground bg) {
        double[] tfBounds = tf.geometricBounds();
        double[] tfTransform = tf.itemTransform();
        if (tfBounds == null || tfTransform == null) return;

        // 텍스트 프레임의 Group 내 바운드 (비회전 가정)
        double tfLeft = tfBounds[1] + tfTransform[4];
        double tfTop = tfBounds[0] + tfTransform[5];
        double tfRight = tfBounds[3] + tfTransform[4];
        double tfBottom = tfBounds[2] + tfTransform[5];

        double insetLeft = tfLeft - bg.bgLeft;
        double insetTop = tfTop - bg.bgTop;
        double insetRight = bg.bgRight - tfRight;
        double insetBottom = bg.bgBottom - tfBottom;

        // 음수 여백은 0으로 보정 (텍스트 프레임이 배경보다 클 수 없으므로)
        if (insetLeft < 0) insetLeft = 0;
        if (insetTop < 0) insetTop = 0;
        if (insetRight < 0) insetRight = 0;
        if (insetBottom < 0) insetBottom = 0;

        // 유의미한 여백이 있을 때만 적용 (1pt 이상)
        if (insetLeft + insetTop + insetRight + insetBottom < 1.0) return;

        obj.textMarginLeft(CoordinateConverter.pointsToHwpunits(insetLeft));
        obj.textMarginTop(CoordinateConverter.pointsToHwpunits(insetTop));
        obj.textMarginRight(CoordinateConverter.pointsToHwpunits(insetRight));
        obj.textMarginBottom(CoordinateConverter.pointsToHwpunits(insetBottom));

        // 글상자 크기를 배경 사각형 크기로 확장 (축소는 하지 않음)
        double bgW = bg.bgRight - bg.bgLeft;
        double bgH = bg.bgBottom - bg.bgTop;
        long bgWHwp = CoordinateConverter.pointsToHwpunits(bgW);
        long bgHHwp = CoordinateConverter.pointsToHwpunits(bgH);
        if (bgWHwp > obj.width()) obj.width(bgWHwp);
        if (bgHHwp > obj.height()) obj.height(bgHHwp);
    }

    /**
     * 인라인 그래픽 계층에 자식 텍스트프레임이 있는지 재귀적으로 확인.
     */
    static boolean hasChildTextFramesRecursive(IDMLCharacterRun.InlineGraphic ig) {
        if (!ig.childTextFrames().isEmpty()) return true;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (hasChildTextFramesRecursive(child)) return true;
        }
        return false;
    }

    /**
     * 배경 사각형 스타일 정보.
     */
    static class GroupBackground {
        String fillHex;
        double fillTint = 100;
        String strokeHex;
        double strokeWeight;
        double strokeTint = 100;
        double cornerRadius;
        // 배경 사각형의 Group 내 변환 후 바운드 (points)
        double bgLeft, bgTop, bgRight, bgBottom;
        boolean hasBounds;
    }

    /**
     * 인라인 Group의 자식 그래픽에서 배경 사각형의 전체 스타일을 추출.
     * fill, stroke, cornerRadius 등을 포함.
     */
    static GroupBackground extractGroupBackground(IDMLCharacterRun.InlineGraphic ig,
                                                   ColorResolver colorResolver) {
        if (!"group".equals(ig.type())) return null;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasVectorShape()) {
                IDMLVectorShape shape = child.vectorShape();
                if (shape.fillColor() != null) {
                    String hex = resolveColorHex(shape.fillColor(), colorResolver);
                    if (hex != null) {
                        GroupBackground bg = new GroupBackground();
                        // 틴트를 색상에 사전 블렌딩 (HWPX alpha 비호환 방지)
                        bg.fillHex = blendColorWithWhite(hex, shape.fillTint() / 100.0);
                        bg.fillTint = 100; // 이미 블렌딩됨
                        String sHex = resolveColorHex(shape.strokeColor(), colorResolver);
                        bg.strokeHex = sHex != null
                                ? blendColorWithWhite(sHex, shape.strokeTint() / 100.0) : null;
                        bg.strokeWeight = shape.strokeWeight();
                        bg.strokeTint = 100;
                        bg.cornerRadius = shape.cornerRadius();
                        // 배경 사각형의 Group 내 바운드 계산 (비회전 가정)
                        double[] gb = child.geometricBounds();
                        double[] ct = child.itemTransform();
                        if (gb != null && ct != null) {
                            bg.bgLeft = gb[1] + ct[4];
                            bg.bgTop = gb[0] + ct[5];
                            bg.bgRight = gb[3] + ct[4];
                            bg.bgBottom = gb[2] + ct[5];
                            bg.hasBounds = true;
                        }
                        return bg;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 색상 hex를 fraction 비율로 흰색과 블렌딩.
     * fraction=1.0 → 원래 색상, fraction=0.0 → 흰색.
     */
    static String blendColorWithWhite(String hex, double fraction) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return hex;
        try {
            int rgb = Integer.parseInt(hex.substring(1, 7), 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            r = (int) Math.round(255 + (r - 255) * fraction);
            g = (int) Math.round(255 + (g - 255) * fraction);
            b = (int) Math.round(255 + (b - 255) * fraction);
            return String.format("#%02X%02X%02X",
                    Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, b)));
        } catch (Exception e) {
            return hex;
        }
    }

    /**
     * 인라인 그래픽 → ASTInlineObject 변환.
     * 이미지 링크가 있으면 이미지 데이터를 로드한다.
     */
    static ASTInlineObject createInlineObjectFromGraphic(IDMLCharacterRun.InlineGraphic ig,
                                                          ASTImageLoader imageLoader,
                                                          ColorResolver colorResolver) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.sourceId(ig.selfId());

        long w = CoordinateConverter.pointsToHwpunits(ig.widthPoints());
        long h = CoordinateConverter.pointsToHwpunits(ig.heightPoints());
        obj.width(w);
        obj.height(h);

        // 이미지 링크가 있거나, 자식에 이미지가 있으면 IMAGE로 처리
        IDMLCharacterRun.InlineGraphic imageFrame = findImageFrame(ig);
        boolean hasDirectImage = ig.hasImage();
        boolean hasChildImage = imageFrame != null && imageFrame.hasImage();

        if ((hasDirectImage || hasChildImage) && imageLoader != null) {
            obj.kind(ASTInlineObject.ObjectKind.IMAGE);

            // 이미지 표시 크기 계산: 계층의 스케일 팩터를 누적 적용
            IDMLCharacterRun.InlineGraphic sizeFrame = hasChildImage ? imageFrame : ig;
            double imgW = sizeFrame.widthPoints();
            double imgH = sizeFrame.heightPoints();
            double cumulativeScale = computeCumulativeScale(ig, hasChildImage ? imageFrame : null);
            imgW *= cumulativeScale;
            imgH *= cumulativeScale;

            long displayW = CoordinateConverter.pointsToHwpunits(imgW);
            long displayH = CoordinateConverter.pointsToHwpunits(imgH);
            if (displayW > 0 && displayH > 0) {
                obj.width(displayW);
                obj.height(displayH);
            }

            // 프레임 bounds (points) — 클리핑용, 실제 로컬 좌표 사용
            IDMLCharacterRun.InlineGraphic frameForBounds = hasChildImage ? imageFrame : ig;
            double[] frameBounds = frameForBounds.geometricBounds();
            if (frameBounds == null) {
                double frameW = frameForBounds.widthPoints();
                double frameH = frameForBounds.heightPoints();
                if (frameW > 0 && frameH > 0) {
                    frameBounds = new double[]{0, 0, frameH, frameW};
                }
            }

            // 이미지 transform/graphicBounds는 이미지 프레임의 것을 우선 사용
            IDMLCharacterRun.InlineGraphic imgSrc = hasChildImage ? imageFrame : ig;
            double[] imgTransform = imgSrc.imageTransform() != null
                    ? imgSrc.imageTransform() : ig.imageTransform();
            double[] graphicBounds = imgSrc.graphicBounds() != null
                    ? imgSrc.graphicBounds() : ig.graphicBounds();
            String linkURI = imgSrc.linkResourceURI() != null
                    ? imgSrc.linkResourceURI() : ig.linkResourceURI();

            ASTImageLoader.ImageResult result = imageLoader.loadImage(
                    linkURI, displayW, displayH,
                    imgTransform, frameBounds, graphicBounds);

            if (result != null) {
                obj.imageData(result.imageData);
                obj.imageFormat(result.format);
                obj.pixelWidth(result.pixelWidth);
                obj.pixelHeight(result.pixelHeight);
            }
        } else if (ig.hasVectorShape() && imageLoader != null) {
            // 벡터 도형 (글리프 아웃라인 등) → PNG 래스터화
            IDMLVectorShape shape = ig.vectorShape();
            String fillHex = resolveColorHex(shape.fillColor(), colorResolver);
            String strokeHex = resolveColorHex(shape.strokeColor(), colorResolver);

            if (fillHex != null || strokeHex != null) {
                obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                ASTImageLoader.ImageResult result = imageLoader.rasterizeShape(shape, fillHex, strokeHex);
                if (result != null && result.imageData != null) {
                    obj.imageData(result.imageData);
                    obj.imageFormat(result.format);
                    obj.pixelWidth(result.pixelWidth);
                    obj.pixelHeight(result.pixelHeight);
                } else {
                    obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
                }
            } else {
                obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
            }
        } else if (imageLoader != null) {
            // 그룹 내 자식 텍스트프레임이 있으면 래스터화하지 않고 텍스트 우선 처리
            // (배경 사각형 + 텍스트프레임 구조는 스타일 적용된 글상자로 변환)
            boolean hasChildTextFrames = hasChildTextFramesRecursive(ig);
            List<ASTImageLoader.ShapeWithColor> childShapes = collectChildVectorShapes(ig, colorResolver);
            if (!childShapes.isEmpty() && !hasChildTextFrames) {
                obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                ASTImageLoader.ImageResult result = imageLoader.rasterizeShapes(childShapes, ig.itemTransform());
                if (result != null && result.imageData != null) {
                    obj.imageData(result.imageData);
                    obj.imageFormat(result.format);
                    obj.pixelWidth(result.pixelWidth);
                    obj.pixelHeight(result.pixelHeight);
                    if (result.pixelWidth > 0 && result.pixelHeight > 0) {
                        obj.width(CoordinateConverter.pointsToHwpunits(result.widthPts));
                        obj.height(CoordinateConverter.pointsToHwpunits(result.heightPts));
                    }
                } else {
                    obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
                }
            } else {
                obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
            }
        } else {
            obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
        }

        // 앵커/래핑 속성 복사 (IDML → AST)
        obj.anchoredPosition(ig.anchoredPosition());
        obj.textWrapMode(ig.textWrapMode());
        obj.textWrapSide(ig.textWrapSide());
        obj.textWrapTop(CoordinateConverter.pointsToHwpunits(ig.textWrapTop()));
        obj.textWrapLeft(CoordinateConverter.pointsToHwpunits(ig.textWrapLeft()));
        obj.textWrapBottom(CoordinateConverter.pointsToHwpunits(ig.textWrapBottom()));
        obj.textWrapRight(CoordinateConverter.pointsToHwpunits(ig.textWrapRight()));

        return obj;
    }

    /**
     * 인라인 그룹의 자식 그래픽에서 벡터 도형(VectorShape)을 재귀적으로 수집한다.
     * 각 도형에 누적 ItemTransform을 부여하여 그룹 루트 좌표계 기준으로 정확한
     * 위치/크기를 계산할 수 있게 한다.
     */
    static List<ASTImageLoader.ShapeWithColor> collectChildVectorShapes(
            IDMLCharacterRun.InlineGraphic ig, ColorResolver colorResolver) {
        List<ASTImageLoader.ShapeWithColor> result = new ArrayList<>();
        collectChildVectorShapesRecursive(ig, colorResolver, result, null);
        return result;
    }

    static void collectChildVectorShapesRecursive(
            IDMLCharacterRun.InlineGraphic ig, ColorResolver colorResolver,
            List<ASTImageLoader.ShapeWithColor> result, double[] parentTransform) {
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            // 자식의 ItemTransform을 부모 누적 변환과 결합
            double[] childTransform = child.itemTransform();
            double[] accTransform = combineInlineTransforms(parentTransform, childTransform);

            if (child.hasVectorShape()) {
                IDMLVectorShape shape = child.vectorShape();
                String fillHex = resolveColorHex(shape.fillColor(), colorResolver);
                String strokeHex = resolveColorHex(shape.strokeColor(), colorResolver);
                if (fillHex != null || strokeHex != null) {
                    result.add(new ASTImageLoader.ShapeWithColor(shape, fillHex, strokeHex, accTransform));
                }
            }
            // 재귀적으로 하위 그룹도 수집 (누적 변환 전달)
            collectChildVectorShapesRecursive(child, colorResolver, result, accTransform);
        }
    }

    /**
     * 인라인 그래픽 계층의 누적 변환을 결합한다.
     * parent가 null이면 child만 반환. child가 null이면 parent만 반환.
     * 둘 다 null이면 null 반환 (변환 없음 = 항등 변환).
     */
    static double[] combineInlineTransforms(double[] parent, double[] child) {
        if (child == null && parent == null) return null;
        if (child == null) return parent;
        if (parent == null) {
            // 항등 변환 여부 확인 — 항등이면 null 반환하여 불필요한 변환 비용 회피
            if (child[0] == 1 && child[1] == 0 && child[2] == 0 && child[3] == 1
                    && child[4] == 0 && child[5] == 0) {
                return null;
            }
            return child;
        }
        return CoordinateConverter.combineTransforms(parent, child);
    }

    /**
     * 인라인 그래픽 계층에서 이미지를 직접 포함하는 자식 프레임을 찾는다.
     * 부모 Group의 linkResourceURI와 일치하는 자식을 우선 선택한다.
     * (collectInlineImageLink가 Image→PDF→EPS 순서로 검색하므로,
     * Group의 URI가 주요 콘텐츠 이미지를 가리킨다.)
     * 일치하는 자식이 없으면 가장 큰 이미지 자식을 선택한다.
     */
    static IDMLCharacterRun.InlineGraphic findImageFrame(IDMLCharacterRun.InlineGraphic ig) {
        // 1. 부모의 linkResourceURI와 일치하는 자식을 우선 검색
        String parentURI = ig.linkResourceURI();
        if (parentURI != null && !parentURI.isEmpty()) {
            IDMLCharacterRun.InlineGraphic match = findImageFrameByURI(ig, parentURI);
            if (match != null) return match;
        }
        // 2. 일치하는 자식이 없으면 가장 큰 이미지 자식 선택
        return findLargestImageFrame(ig);
    }

    static IDMLCharacterRun.InlineGraphic findImageFrameByURI(
            IDMLCharacterRun.InlineGraphic ig, String targetURI) {
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasImage() && child.childGraphics().isEmpty()
                    && targetURI.equals(child.linkResourceURI())) {
                return child;
            }
            IDMLCharacterRun.InlineGraphic found = findImageFrameByURI(child, targetURI);
            if (found != null) return found;
        }
        return null;
    }

    static IDMLCharacterRun.InlineGraphic findLargestImageFrame(IDMLCharacterRun.InlineGraphic ig) {
        IDMLCharacterRun.InlineGraphic best = null;
        double bestArea = 0;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasImage() && child.childGraphics().isEmpty()) {
                double area = child.widthPoints() * child.heightPoints();
                if (area > bestArea) {
                    bestArea = area;
                    best = child;
                }
            }
            IDMLCharacterRun.InlineGraphic found = findLargestImageFrame(child);
            if (found != null) {
                double area = found.widthPoints() * found.heightPoints();
                if (area > bestArea) {
                    bestArea = area;
                    best = found;
                }
            }
        }
        return best;
    }

    /**
     * 인라인 그래픽 계층에서 루트부터 이미지 프레임까지의 누적 스케일 팩터를 계산한다.
     * 각 계층의 itemTransform[0] (scaleX)을 곱해서 반환한다 (scaleX ≈ scaleY 가정).
     */
    static double computeCumulativeScale(IDMLCharacterRun.InlineGraphic root,
                                          IDMLCharacterRun.InlineGraphic target) {
        if (target == null) {
            // target이 없으면 루트 자체의 스케일만
            return extractScale(root);
        }
        // 루트 → target 경로의 스케일을 누적
        double scale = extractScale(root);
        scale *= computeScaleToTarget(root, target);
        return scale;
    }

    static double computeScaleToTarget(IDMLCharacterRun.InlineGraphic current,
                                        IDMLCharacterRun.InlineGraphic target) {
        for (IDMLCharacterRun.InlineGraphic child : current.childGraphics()) {
            if (child == target) {
                return extractScale(child);
            }
            if (containsTarget(child, target)) {
                return extractScale(child) * computeScaleToTarget(child, target);
            }
        }
        return 1.0;
    }

    static boolean containsTarget(IDMLCharacterRun.InlineGraphic node,
                                   IDMLCharacterRun.InlineGraphic target) {
        if (node == target) return true;
        for (IDMLCharacterRun.InlineGraphic child : node.childGraphics()) {
            if (containsTarget(child, target)) return true;
        }
        return false;
    }

    static double extractScale(IDMLCharacterRun.InlineGraphic ig) {
        if (ig.itemTransform() == null) return 1.0;
        double scaleX = Math.abs(ig.itemTransform()[0]);
        return (scaleX > 0.01 && scaleX != 1.0) ? scaleX : 1.0;
    }

    /**
     * IDMLTable → ASTTable 변환 (플로팅 스토리 레벨 테이블).
     */
    static ASTTable convertTable(IDMLTable idmlTable, IDMLTextFrame tf,
                                  IDMLPage page, int zOrder,
                                  IDMLDocument idmlDoc, ColorResolver colorResolver,
                                  ASTImageLoader imageLoader) {
        ASTTable table = new ASTTable();
        table.sourceId(idmlTable.selfId());
        table.zOrder(zOrder);

        // 테이블 위치 (텍스트 프레임 기준)
        double[] relPos = IDMLGeometry.pageRelativePosition(
                tf.geometricBounds(), tf.itemTransform(),
                page.geometricBounds(), page.itemTransform());
        table.x(CoordinateConverter.pointsToHwpunits(relPos[0]));
        table.y(CoordinateConverter.pointsToHwpunits(relPos[1]));

        // 컬럼 너비
        for (double cw : idmlTable.columnWidths()) {
            table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
        }
        table.colCount(idmlTable.columnWidths().size());

        // 행 변환
        long totalHeight = 0;
        int rowIdx = 0;
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            // 셀 변환
            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                int colIdx = idmlCell.columnIndex();
                ASTTableCell cell = convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader);
                row.addCell(cell);
            }

            table.addRow(row);
            rowIdx++;
        }
        table.rowCount(rowIdx);

        // 테이블 크기
        long totalWidth = 0;
        for (long cw : table.columnWidths()) {
            totalWidth += cw;
        }
        table.width(totalWidth);
        table.height(totalHeight);

        // 셀 크기 계산 (columnWidths + rowHeights 기반)
        List<Long> colWidths = table.columnWidths();
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                // 셀 너비 = 시작 컬럼부터 colSpan만큼 합산
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + cell.columnSpan(), colWidths.size());
                for (int c = startCol; c < endCol; c++) {
                    cellWidth += colWidths.get(c);
                }
                cell.width(cellWidth);

                // 셀 높이 = 시작 행부터 rowSpan만큼 합산
                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + cell.rowSpan(), table.rows().size());
                for (int r = startRow; r < endRow; r++) {
                    cellHeight += table.rows().get(r).rowHeight();
                }
                cell.height(cellHeight);
            }
        }

        ASTTableSpacerMerger.merge(table);
        return table;
    }

    /**
     * IDMLTableCell → ASTTableCell 변환 (미니 문서).
     */
    static ASTTableCell convertTableCell(IDMLTableCell idmlCell,
                                          int rowIdx, int colIdx,
                                          IDMLDocument idmlDoc,
                                          ColorResolver colorResolver,
                                          ASTImageLoader imageLoader) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(idmlCell.rowSpan());
        cell.columnSpan(idmlCell.columnSpan());

        // 셀 스타일
        if (idmlCell.fillColor() != null) {
            cell.fillColor(colorResolver.resolve(idmlCell.fillColor()));
        }
        cell.verticalAlign(idmlCell.verticalJustification());

        // 셀 여백
        cell.marginTop(CoordinateConverter.pointsToHwpunits(idmlCell.topInset()));
        cell.marginBottom(CoordinateConverter.pointsToHwpunits(idmlCell.bottomInset()));
        cell.marginLeft(CoordinateConverter.pointsToHwpunits(idmlCell.leftInset()));
        cell.marginRight(CoordinateConverter.pointsToHwpunits(idmlCell.rightInset()));

        // 셀 테두리 (IDMLTableCell.CellBorder → ASTTableCell.CellBorder)
        cell.topBorder(convertCellBorder(idmlCell.topBorder(), colorResolver));
        cell.bottomBorder(convertCellBorder(idmlCell.bottomBorder(), colorResolver));
        cell.leftBorder(convertCellBorder(idmlCell.leftBorder(), colorResolver));
        cell.rightBorder(convertCellBorder(idmlCell.rightBorder(), colorResolver));

        // 대각선
        cell.topLeftDiagonalLine(idmlCell.topLeftDiagonalLine());
        cell.topRightDiagonalLine(idmlCell.topRightDiagonalLine());

        // 셀 내용 → 미니 문서 (재귀)
        FlattenedObjectPool emptyPool = new FlattenedObjectPool(); // 셀 내 인라인은 별도 처리
        for (IDMLParagraph cellPara : idmlCell.paragraphs()) {
            ASTParagraph astPara = ASTStoryConverter.convertParagraph(cellPara, emptyPool, idmlDoc, colorResolver, imageLoader, false);
            if (astPara != null) {
                cell.addParagraph(astPara);
            }
        }

        // 마지막 빈 단락 제거
        Stage4_BuildAST.removeTrailingEmptyParagraphs(cell.paragraphs());

        return cell;
    }

    /**
     * IDMLTableCell.CellBorder → ASTTableCell.CellBorder 변환.
     */
    static ASTTableCell.CellBorder convertCellBorder(IDMLTableCell.CellBorder src,
                                                      ColorResolver colorResolver) {
        if (src == null || src.strokeWeight <= 0) return null;
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.weight(src.strokeWeight);
        border.strokeType(src.strokeType);
        border.tint(src.strokeTint);
        if (src.strokeColor != null) {
            border.color(colorResolver.resolve(src.strokeColor));
        }
        return border;
    }

    /**
     * IDMLImageFrame → ASTFigure 변환 (플로팅 이미지).
     */
    static ASTFigure createFigureFromImageFrame(IDMLImageFrame imgFrame,
                                                 IDMLPage page,
                                                 ASTImageLoader imageLoader) {
        double[] t = imgFrame.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001);

        long wHwp, hHwp, xHwp, yHwp;

        if (hasRotOrFlip) {
            // === 회전/반전 사전 렌더링 경로 (벡터 도형과 동일) ===
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
            // === 기존 비회전 경로 ===
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

        ASTImageLoader.ImageResult result = imageLoader.loadImage(
                imgFrame.linkResourceURI(), wHwp, hHwp,
                imgFrame.imageTransform(), frameBounds, imgFrame.graphicBounds());

        if (result == null || result.imageData == null) return null;

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

        // textWrap 속성 전파 (IDMLImageFrame → ASTFigure)
        figure.textWrapMode(imgFrame.textWrapMode());
        figure.textWrapSide(imgFrame.textWrapSide());
        figure.textWrapTop(CoordinateConverter.pointsToHwpunits(imgFrame.textWrapTop()));
        figure.textWrapLeft(CoordinateConverter.pointsToHwpunits(imgFrame.textWrapLeft()));
        figure.textWrapBottom(CoordinateConverter.pointsToHwpunits(imgFrame.textWrapBottom()));
        figure.textWrapRight(CoordinateConverter.pointsToHwpunits(imgFrame.textWrapRight()));

        if (!hasRotOrFlip) {
            // 비회전 경로에서만 기존 flip 처리
            // 프레임과 이미지 양쪽 flip을 XOR: 둘 다 flip이면 상쇄
            boolean frameFlip = IDMLGeometry.hasFlip(imgFrame.itemTransform());
            boolean imageFlip = IDMLGeometry.hasFlip(imgFrame.imageTransform());
            boolean netFlip = frameFlip ^ imageFlip;
            if (netFlip) {
                double rotation = IDMLGeometry.extractRotation(imgFrame.itemTransform());
                if (Math.abs(Math.abs(rotation) - 180) < 0.5) {
                    result.imageData = ASTImageLoader.flipVertically(result.imageData);
                    figure.imageData(result.imageData);
                } else if (Math.abs(rotation) < 0.5) {
                    result.imageData = ASTImageLoader.flipHorizontally(result.imageData);
                    figure.imageData(result.imageData);
                }
            }
        }
        // hasRotOrFlip: 회전/반전이 preRenderRotation에서 이미 처리됨

        // 페이지 경계를 벗어나는 이미지의 크롭 비율 계산 (스프레드 걸침 이미지 처리)
        // bleed(인쇄 여백 넘침, ~1%)는 무시하고, 실제 페이지 걸침(>5%)만 크롭
        long pageW = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.width(page.geometricBounds()));
        long pageH = CoordinateConverter.pointsToHwpunits(
                IDMLGeometry.height(page.geometricBounds()));
        double minCropThreshold = 0.05;  // 5% 미만은 bleed로 간주, 크롭하지 않음

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

        return figure;
    }

    /**
     * IDMLVectorShape → ASTFigure 변환 (플로팅 벡터 도형 → PNG 래스터화).
     */
    static ASTFigure createFigureFromVectorShape(IDMLVectorShape shape,
                                                  IDMLPage page,
                                                  ASTImageLoader imageLoader,
                                                  ColorResolver colorResolver) {
        // === 복수 클리핑 자식 (clippedChildren) 처리 ===
        if (shape.hasClippedChildren()) {
            return createFigureFromClippedGroup(shape, page, imageLoader, colorResolver);
        }

        // 채우기/선 색상 해석
        IDMLVectorShape renderTarget = shape.hasClippedChild() ? shape.clippedChild() : shape;
        String fillHex = resolveColorHex(renderTarget.fillColor(), colorResolver);
        String strokeHex = resolveColorHex(renderTarget.strokeColor(), colorResolver);

        // 보이지 않는 도형 스킵
        if (fillHex == null && strokeHex == null) return null;

        double[] effectiveBounds = shape.geometricBounds();
        double[] t = shape.itemTransform();
        boolean hasRotOrFlip = t != null && (Math.abs(t[1]) > 0.001 || Math.abs(t[2]) > 0.001);

        long wHwp, hHwp, xHwp, yHwp;
        ASTImageLoader.ImageResult result;

        if (hasRotOrFlip) {
            // === 회전/반전 사전 렌더링 경로 ===
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

            // 선 도형(GraphicLine 등)은 한 축이 0일 수 있음: stroke weight를 최소 크기로 사용
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

            // 페이지보다 훨씬 큰 도형은 스프레드 배경이므로 스킵
            long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
            long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
            if (wHwp > pageW * 2 || hHwp > pageH * 2) return null;

            // 회전 사전 렌더링된 래스터
            result = imageLoader.rasterizeShape(shape, fillHex, strokeHex, shape.itemTransform());
        } else {
            // === 기존 비회전 경로 ===
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

            // 페이지보다 훨씬 큰 도형은 스프레드 배경이므로 스킵
            long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
            long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
            if (wHwp > pageW * 2 || hHwp > pageH * 2) return null;

            result = imageLoader.rasterizeShape(shape, fillHex, strokeHex);
        }

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

        if (!hasRotOrFlip) {
            // 비회전 경로에서만 HWPX rotation/flip 처리
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
        // hasRotOrFlip인 경우: 회전/반전이 이미 래스터에 포함됨 → rotationAngle 기본값 0

        return figure;
    }

    /**
     * 클리핑 프레임 + 복수 자식 도형을 합성 래스터화하여 ASTFigure로 변환.
     * 유효 영역(자식 바운딩 박스 ∩ 클리핑 프레임)만 렌더링하고 배치한다.
     */
    static ASTFigure createFigureFromClippedGroup(IDMLVectorShape clipFrame,
                                                   IDMLPage page,
                                                   ASTImageLoader imageLoader,
                                                   ColorResolver colorResolver) {
        // 자식 중 하나라도 보이는 색상이 있는지 확인
        boolean anyVisible = false;
        for (IDMLVectorShape child : clipFrame.clippedChildren()) {
            String fh = resolveColorHex(child.fillColor(), colorResolver);
            String sh = resolveColorHex(child.strokeColor(), colorResolver);
            if (fh != null || sh != null) { anyVisible = true; break; }
        }
        if (!anyVisible) return null;

        double[] clipBounds = clipFrame.geometricBounds(); // [top, left, bottom, right]
        double clipTop = clipBounds[0], clipLeft = clipBounds[1];
        double clipBottom = clipBounds[2], clipRight = clipBounds[3];

        // 자식들의 바운딩 박스를 클리핑 프레임 로컬 좌표계에서 계산
        double uLeft = Double.MAX_VALUE, uTop = Double.MAX_VALUE;
        double uRight = -Double.MAX_VALUE, uBottom = -Double.MAX_VALUE;
        for (IDMLVectorShape child : clipFrame.clippedChildren()) {
            double[] ct = child.itemTransform();
            double[] cb = child.geometricBounds();
            // 자식 bounds의 4개 꼭짓점을 클리핑 프레임 공간으로 변환
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

        // 클리핑 프레임과 교차 → 유효 영역
        double eLeft = Math.max(uLeft, clipLeft);
        double eTop = Math.max(uTop, clipTop);
        double eRight = Math.min(uRight, clipRight);
        double eBottom = Math.min(uBottom, clipBottom);

        if (eRight <= eLeft || eBottom <= eTop) return null;

        double effectiveW = eRight - eLeft;
        double effectiveH = eBottom - eTop;
        double effectiveCX = (eLeft + eRight) / 2.0;
        double effectiveCY = (eTop + eBottom) / 2.0;

        // 유효 영역 중심을 절대 좌표로 변환
        double[] tt = clipFrame.itemTransform();
        double[] absCenter = CoordinateConverter.applyTransform(tt, effectiveCX, effectiveCY);
        double[] pageAbs = IDMLGeometry.absoluteTopLeft(
                page.geometricBounds(), page.itemTransform());

        long wHwp = CoordinateConverter.pointsToHwpunits(effectiveW);
        long hHwp = CoordinateConverter.pointsToHwpunits(effectiveH);
        long xHwp = CoordinateConverter.pointsToHwpunits(absCenter[0] - pageAbs[0]) - wHwp / 2;
        long yHwp = CoordinateConverter.pointsToHwpunits(absCenter[1] - pageAbs[1]) - hHwp / 2;

        if (wHwp <= 0 || hHwp <= 0) return null;

        // 페이지보다 훨씬 큰 도형은 스프레드 배경이므로 스킵
        long pageW = CoordinateConverter.pointsToHwpunits(IDMLGeometry.width(page.geometricBounds()));
        long pageH = CoordinateConverter.pointsToHwpunits(IDMLGeometry.height(page.geometricBounds()));
        if (wHwp > pageW * 2 || hHwp > pageH * 2) return null;

        // 유효 영역만 합성 래스터화
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

        return figure;
    }

    static String resolveColorHex(String colorRef, ColorResolver colorResolver) {
        if (colorRef == null || "None".equals(colorRef) || colorRef.contains("[None]")) return null;
        String hex = colorResolver.resolve(colorRef);
        return (hex != null && !hex.isEmpty()) ? hex : null;
    }

    /**
     * 이미지 프레임 중복 제거 키 생성.
     * 같은 이미지 URI + 같은 위치(tx, ty) + 같은 프레임 크기인 경우 같은 키를 반환.
     * PSD 레이어 가시성이 다른 중복 배치를 하나로 합치기 위함.
     */
    static String buildImageFrameDedupKey(IDMLImageFrame frame) {
        String uri = frame.linkResourceURI();
        if (uri == null || uri.isEmpty()) return null;

        double[] t = frame.itemTransform();
        double[] b = frame.geometricBounds();
        if (t == null || b == null) return null;

        // 위치(tx, ty)와 프레임 크기를 0.1pt 정밀도로 반올림하여 키 생성
        long tx = Math.round(t[4] * 10);
        long ty = Math.round(t[5] * 10);
        long w = Math.round((b[3] - b[1]) * 10);
        long h = Math.round((b[2] - b[0]) * 10);

        return uri + "|" + tx + "," + ty + "|" + w + "x" + h;
    }
}
