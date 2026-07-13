package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 오버레이 좌표 계산 및 배경 그룹 래퍼 생성.
 * ASTInlineObjectBuilder에서 분리됨.
 */
class ASTOverlayBuilder {

    /**
     * 이미지 프레임 + 텍스트 프레임만으로 콘텐츠 바운딩 박스를 계산.
     * 배경 사각형 등 장식용 그래픽은 제외하여 컨테이너가 불필요하게 커지는 것을 방지.
     * @param imgPos 이미지 프레임의 그룹 내 위치 [left, top, width, height] (null 가능)
     * @return [minX, minY, maxX, maxY] (points, 그룹 로컬 좌표계)
     */
    static double[] computeContentBounds(IDMLCharacterRun.InlineGraphic ig,
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
    static double[] findImagePositionInGroup(
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
    static double[] computeGroupVisualBounds(IDMLCharacterRun.InlineGraphic ig) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            double[] cb = child.geometricBounds();
            double[] ct = child.itemTransform();
            // gb=[0,0,0,0] (그룹 등 PathGeometry 없는 요소)는 건너뜀
            if (cb != null && ct != null
                    && (cb[0] != cb[2] || cb[1] != cb[3])) {
                double[] aabb = transformBoundsAABB(cb, ct);
                minX = Math.min(minX, aabb[0]);
                minY = Math.min(minY, aabb[1]);
                maxX = Math.max(maxX, aabb[2]);
                maxY = Math.max(maxY, aabb[3]);
            }
        }
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            double[] tb = childTf.geometricBounds();
            double[] tt = childTf.itemTransform();
            if (tb != null && tt != null) {
                double[] aabb = transformBoundsAABB(tb, tt);
                minX = Math.min(minX, aabb[0]);
                minY = Math.min(minY, aabb[1]);
                maxX = Math.max(maxX, aabb[2]);
                maxY = Math.max(maxY, aabb[3]);
            }
        }

        if (minX == Double.MAX_VALUE) {
            return new double[]{0, 0, 0, 0};
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /**
     * 그룹의 비-텍스트 자식만의 시각적 바운딩 박스를 계산.
     * 합성 이미지 캔버스(ASTImageLoader.computeNonTextVisualBounds)와 동일한 범위.
     * @return [minX, minY, maxX, maxY] (points, 그룹 로컬 좌표계) 또는 null
     */
    static double[] computeNonTextVisualBounds(IDMLCharacterRun.InlineGraphic ig) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            double[] cb = child.geometricBounds();
            double[] ct = child.itemTransform();
            // gb=[0,0,0,0] (그룹 등 PathGeometry 없는 요소)는 건너뜀
            if (cb != null && ct != null
                    && (cb[0] != cb[2] || cb[1] != cb[3])) {
                double[] aabb = transformBoundsAABB(cb, ct);
                minX = Math.min(minX, aabb[0]);
                minY = Math.min(minY, aabb[1]);
                maxX = Math.max(maxX, aabb[2]);
                maxY = Math.max(maxY, aabb[3]);
            }
        }

        if (minX == Double.MAX_VALUE) return null;
        return new double[]{minX, minY, maxX, maxY};
    }

    /**
     * 그룹의 자식 그래픽 중에서 주어진 점을 포함하는 가장 작은 사각형의 바운드를 찾는다.
     * 텍스트프레임의 중심점으로 검색하여 해당 텍스트프레임이 속한 배경 그래픽을 식별한다.
     * @return [left, top, right, bottom] (points, group coordinate) 또는 null
     */
    static double[] findEnclosingGraphicBounds(IDMLCharacterRun.InlineGraphic group,
                                                double pointX, double pointY) {
        double[] best = null;
        double bestArea = Double.MAX_VALUE;

        for (IDMLCharacterRun.InlineGraphic child : group.childGraphics()) {
            double[] cb = child.geometricBounds();
            double[] ct = child.itemTransform();
            if (cb == null || ct == null) continue;

            double[] aabb = transformBoundsAABB(cb, ct);
            double left = aabb[0], top = aabb[1], right = aabb[2], bottom = aabb[3];

            if (pointX >= left && pointX <= right && pointY >= top && pointY <= bottom) {
                double area = (right - left) * (bottom - top);
                if (area < bestArea) {
                    bestArea = area;
                    best = new double[]{left, top, right, bottom};
                }
            }
        }
        return best;
    }

    /**
     * geometricBounds [top,left,bottom,right]에 아핀 변환을 적용하여
     * 축 정렬 바운딩 박스를 반환. flip/rotation/scale을 올바르게 처리.
     * @return [minX, minY, maxX, maxY]
     */
    static double[] transformBoundsAABB(double[] bounds, double[] transform) {
        double a = transform[0], bv = transform[1];
        double c = transform[2], d = transform[3];
        double tx = transform[4], ty = transform[5];
        // 단순 translation 최적화
        if (a == 1 && bv == 0 && c == 0 && d == 1) {
            return new double[]{bounds[1] + tx, bounds[0] + ty,
                    bounds[3] + tx, bounds[2] + ty};
        }
        // 4 corners: (left,top), (right,top), (left,bottom), (right,bottom)
        double[] xs = new double[4];
        double[] ys = new double[4];
        xs[0] = a * bounds[1] + c * bounds[0] + tx;
        ys[0] = bv * bounds[1] + d * bounds[0] + ty;
        xs[1] = a * bounds[3] + c * bounds[0] + tx;
        ys[1] = bv * bounds[3] + d * bounds[0] + ty;
        xs[2] = a * bounds[1] + c * bounds[2] + tx;
        ys[2] = bv * bounds[1] + d * bounds[2] + ty;
        xs[3] = a * bounds[3] + c * bounds[2] + tx;
        ys[3] = bv * bounds[3] + d * bounds[2] + ty;
        double minXr = xs[0], maxXr = xs[0], minYr = ys[0], maxYr = ys[0];
        for (int i = 1; i < 4; i++) {
            if (xs[i] < minXr) minXr = xs[i];
            if (xs[i] > maxXr) maxXr = xs[i];
            if (ys[i] < minYr) minYr = ys[i];
            if (ys[i] > maxYr) maxYr = ys[i];
        }
        return new double[]{minXr, minYr, maxXr, maxYr};
    }

    /**
     * 오버레이 위치 계산: 텍스트프레임을 감싸는 배경 그래픽의 중앙에 오버레이를 배치.
     * 감싸는 그래픽이 없으면 텍스트프레임의 원래 위치를 사용한다.
     *
     * overlayX/Y: 이미지 캔버스 기준 상대 좌표 (폴백 경로용)
     * overlayCenterDeltaX/Y: IDML 좌표계에서 텍스트프레임→배경 중앙 이동량 (resolved 경로용)
     *
     * @param rootGroup 루트 그룹 (배경 그래픽 검색용, null 가능)
     */
    static void applyOverlayPosition(ASTInlineObject obj, IDMLTextFrame tf,
                                       IDMLCharacterRun.InlineGraphic rootGroup,
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
        double tfW = tfBounds[3] - tfBounds[1];
        double tfH = tfBounds[2] - tfBounds[0];
        double tfCenterX = tfX + tfW / 2;
        double tfCenterY = tfY + tfH / 2;

        long overlayX, overlayY;

        // 텍스트프레임을 감싸는 배경 그래픽 탐색
        double[] enclosing = rootGroup != null
                ? findEnclosingGraphicBounds(rootGroup, tfCenterX, tfCenterY) : null;

        if (enclosing != null && groupWidthPts > 0 && groupHeightPts > 0) {
            double bgCenterX = (enclosing[0] + enclosing[2]) / 2;
            double bgCenterY = (enclosing[1] + enclosing[3]) / 2;

            // 폴백 경로: 캔버스 기준 배경 중앙에 오버레이 배치
            double relCenterX = bgCenterX - rootLeft;
            double relCenterY = bgCenterY - rootTop;

            long centerInDisplayX = Math.round(relCenterX / groupWidthPts * imageDisplayWidth);
            long centerInDisplayY = Math.round(relCenterY / groupHeightPts * imageDisplayHeight);

            overlayX = centerInDisplayX - obj.width() / 2;
            overlayY = centerInDisplayY - obj.height() / 2;

            // resolved 경로용: IDML 좌표계에서의 센터링 델타 (points → HWPUNIT)
            // 텍스트프레임 중심 → 배경 그래픽 중심 이동량
            double deltaPtsX = bgCenterX - tfCenterX;
            double deltaPtsY = bgCenterY - tfCenterY;
            obj.overlayCenterDeltaX(CoordinateConverter.pointsToHwpunits(deltaPtsX));
            obj.overlayCenterDeltaY(CoordinateConverter.pointsToHwpunits(deltaPtsY));
        } else {
            // 폴백: 텍스트프레임의 원래 위치
            double relX = tfX - rootLeft;
            double relY = tfY - rootTop;

            if (groupWidthPts > 0 && groupHeightPts > 0) {
                overlayX = Math.round(relX / groupWidthPts * imageDisplayWidth);
                overlayY = Math.round(relY / groupHeightPts * imageDisplayHeight);
            } else {
                overlayX = CoordinateConverter.pointsToHwpunits(relX);
                overlayY = CoordinateConverter.pointsToHwpunits(relY);
            }
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
    static void applyImplicitTextMargin(ASTInlineObject obj, IDMLTextFrame tf,
                                          ASTInlineObjectBuilder.GroupBackground bg) {
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
     * 배경 사각형 + 자식 텍스트프레임 그룹 → 단일 래퍼 글상자(INLINE_TEXT_FRAME)로 변환.
     * Group의 배경 스타일(fill, stroke, cornerRadius)을 글상자에 적용하고,
     * 자식 텍스트프레임들의 단락을 Y 위치 순서로 병합한다.
     * 벡터 화살표 등 장식 요소는 소실되지만 텍스트+배경 스타일은 보존된다.
     */
    static ASTInlineObject createBackgroundGroupWrapper(IDMLCharacterRun.InlineGraphic ig,
                                                          ASTInlineObjectBuilder.GroupBackground bg,
                                                          IDMLDocument idmlDoc,
                                                          ColorResolver colorResolver,
                                                          ASTImageLoader imageLoader,
                                                          ResolvedData resolvedData) {
        ASTInlineObject wrapper = new ASTInlineObject();
        wrapper.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        wrapper.sourceId(ig.selfId());

        // 크기: 배경 사각형 크기가 있으면 우선, 없으면 Group 크기
        long wrapperW, wrapperH;
        if (bg.hasBounds) {
            double bgW = bg.bgRight - bg.bgLeft;
            double bgH = bg.bgBottom - bg.bgTop;
            wrapperW = CoordinateConverter.pointsToHwpunits(bgW);
            wrapperH = CoordinateConverter.pointsToHwpunits(bgH);
        } else {
            wrapperW = CoordinateConverter.pointsToHwpunits(ig.widthPoints());
            wrapperH = CoordinateConverter.pointsToHwpunits(ig.heightPoints());
        }
        wrapper.width(wrapperW);
        wrapper.height(wrapperH);

        // 배경 스타일 적용
        wrapper.fillColor(bg.fillHex);
        wrapper.fillTint(bg.fillTint);
        if (bg.strokeHex != null) {
            wrapper.strokeColor(bg.strokeHex);
            wrapper.strokeWeight(bg.strokeWeight);
            wrapper.strokeTint(bg.strokeTint);
        }
        if (bg.cornerRadius > 0) {
            wrapper.cornerRadius(bg.cornerRadius);
        }

        // 자식 텍스트프레임을 Y 위치 순서로 수집
        List<TextFrameWithPosition> childFrames = new ArrayList<>();
        collectTextFramesWithPosition(ig, idmlDoc, childFrames, 0, 0, resolvedData);

        // Y 좌표 → X 좌표 순서로 정렬
        Collections.sort(childFrames, new Comparator<TextFrameWithPosition>() {
            public int compare(TextFrameWithPosition a, TextFrameWithPosition b) {
                int cmp = Double.compare(a.y, b.y);
                return cmp != 0 ? cmp : Double.compare(a.x, b.x);
            }
        });

        // 자식 텍스트프레임들의 전체 바운드 계산
        if (!childFrames.isEmpty()) {
            double tfMinX = Double.MAX_VALUE, tfMinY = Double.MAX_VALUE;
            double tfMaxX = -Double.MAX_VALUE, tfMaxY = -Double.MAX_VALUE;
            for (TextFrameWithPosition tfp : childFrames) {
                tfMinX = Math.min(tfMinX, tfp.x);
                tfMinY = Math.min(tfMinY, tfp.y);
                tfMaxX = Math.max(tfMaxX, tfp.x + tfp.w);
                tfMaxY = Math.max(tfMaxY, tfp.y + tfp.h);
            }

            // 래퍼 크기가 자식 텍스트프레임보다 작으면 확장
            // (배경 사각형이 실제로는 장식 요소일 경우 — 자식 TF 크기 기준으로 보정)
            double contentW = tfMaxX - tfMinX;
            double contentH = tfMaxY - tfMinY;
            long contentWHwp = CoordinateConverter.pointsToHwpunits(contentW);
            long contentHHwp = CoordinateConverter.pointsToHwpunits(contentH);
            if (contentWHwp > wrapperW) {
                wrapperW = contentWHwp;
                wrapper.width(wrapperW);
            }
            if (contentHHwp > wrapperH) {
                wrapperH = contentHHwp;
                wrapper.height(wrapperH);
            }

            // 텍스트 여백 계산: 배경 사각형과 자식 텍스트프레임들의 전체 바운드 차이
            if (bg.hasBounds) {
                double insetLeft = tfMinX - bg.bgLeft;
                double insetTop = tfMinY - bg.bgTop;
                double insetRight = bg.bgRight - tfMaxX;
                double insetBottom = bg.bgBottom - tfMaxY;
                if (insetLeft < 0) insetLeft = 0;
                if (insetTop < 0) insetTop = 0;
                if (insetRight < 0) insetRight = 0;
                if (insetBottom < 0) insetBottom = 0;
                if (insetLeft + insetTop + insetRight + insetBottom >= 1.0) {
                    wrapper.textMarginLeft(CoordinateConverter.pointsToHwpunits(insetLeft));
                    wrapper.textMarginTop(CoordinateConverter.pointsToHwpunits(insetTop));
                    wrapper.textMarginRight(CoordinateConverter.pointsToHwpunits(insetRight));
                    wrapper.textMarginBottom(CoordinateConverter.pointsToHwpunits(insetBottom));
                }
            }
        }

        // 각 텍스트프레임의 단락을 래퍼에 병합
        FlattenedObjectPool emptyPool = new FlattenedObjectPool();
        for (TextFrameWithPosition tfp : childFrames) {
            IDMLStory story = idmlDoc.getStory(tfp.tf.parentStoryId());
            if (story == null) continue;
            for (IDMLParagraph idmlPara : story.paragraphs()) {
                ASTParagraph astPara = ASTStoryConverter.convertParagraph(
                        idmlPara, emptyPool, idmlDoc, colorResolver, imageLoader, false, resolvedData);
                if (astPara != null && !astPara.items().isEmpty()) {
                    wrapper.addParagraph(astPara);
                }
            }
        }

        // 앵커/래핑 속성 복사
        wrapper.anchoredPosition(ig.anchoredPosition());
        wrapper.textWrapMode(ig.textWrapMode());
        wrapper.textWrapSide(ig.textWrapSide());
        wrapper.textWrapTop(CoordinateConverter.pointsToHwpunits(ig.textWrapTop()));
        wrapper.textWrapLeft(CoordinateConverter.pointsToHwpunits(ig.textWrapLeft()));
        wrapper.textWrapBottom(CoordinateConverter.pointsToHwpunits(ig.textWrapBottom()));
        wrapper.textWrapRight(CoordinateConverter.pointsToHwpunits(ig.textWrapRight()));

        // 단락이 하나도 없으면 래핑 실패
        boolean hasParagraphs = wrapper.paragraphs() != null && !wrapper.paragraphs().isEmpty();
        return hasParagraphs ? wrapper : null;
    }

    /**
     * 자식 텍스트프레임의 위치 정보를 담는 구조체.
     */
    static class TextFrameWithPosition {
        IDMLTextFrame tf;
        double x, y, w, h;  // Group 로컬 좌표 (points)
        TextFrameWithPosition(IDMLTextFrame tf, double x, double y, double w, double h) {
            this.tf = tf;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /**
     * 그룹 내 모든 자식 텍스트프레임을 위치 정보와 함께 재귀적으로 수집한다.
     * 수식 폰트 전용 스토리는 건너뛴다.
     */
    private static void collectTextFramesWithPosition(IDMLCharacterRun.InlineGraphic ig,
                                                        IDMLDocument idmlDoc,
                                                        List<TextFrameWithPosition> result,
                                                        double accTx, double accTy,
                                                        ResolvedData resolvedData) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            if (isOwnedByTextlessShell(childTf, resolvedData)) continue;
            if (ASTInlineObjectBuilder.isMathFontOnlyStory(childTf, idmlDoc)) continue;
            if (childTf.parentStoryId() == null) continue;
            IDMLStory story = idmlDoc.getStory(childTf.parentStoryId());
            if (story == null) continue;

            // 텍스트 내용 확인
            boolean hasContent = false;
            for (IDMLParagraph para : story.paragraphs()) {
                for (IDMLCharacterRun run : para.characterRuns()) {
                    if (run.content() != null && !run.content().trim().isEmpty()) {
                        hasContent = true;
                        break;
                    }
                }
                if (hasContent) break;
            }
            if (!hasContent) continue;

            double[] tb = childTf.geometricBounds();
            double[] tt = childTf.itemTransform();
            double x = accTx + (tt != null ? tt[4] : 0) + (tb != null ? tb[1] : 0);
            double y = accTy + (tt != null ? tt[5] : 0) + (tb != null ? tb[0] : 0);
            double w = tb != null ? tb[3] - tb[1] : 0;
            double h = tb != null ? tb[2] - tb[0] : 0;

            // 부모 그래픽 영역 밖 자식 TextFrame 클리핑 (InDesign에서 숨김 처리되는 요소)
            double[] igBounds = ig.geometricBounds();
            if (igBounds != null && igBounds.length >= 4) {
                double parentH = igBounds[2] - igBounds[0];
                double parentW = igBounds[3] - igBounds[1];
                if (y + h < 0 || y > parentH || x + w < 0 || x > parentW) {
                    continue;
                }
            }

            result.add(new TextFrameWithPosition(childTf, x, y, w, h));
        }
        // 중첩 그래픽(Group 등)도 재귀 탐색
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            double[] ct = childIg.itemTransform();
            double childAccTx = accTx + (ct != null ? ct[4] : 0);
            double childAccTy = accTy + (ct != null ? ct[5] : 0);
            collectTextFramesWithPosition(childIg, idmlDoc, result, childAccTx, childAccTy, resolvedData);
        }
    }

    private static boolean isOwnedByTextlessShell(IDMLTextFrame tf, ResolvedData resolvedData) {
        if (tf == null || resolvedData == null || tf.selfId() == null
                || resolvedData.allRenderedFloatingItems() == null) {
            return false;
        }
        String domId = ParagraphTextHelpers.domIdFromSourceId(tf.selfId());
        if (domId == null) return false;
        if (hasStage1ObjectPlans(resolvedData)) {
            return resolvedData.isHwpxOwnedTextFrame(domId);
        }
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup rg
                : resolvedData.allRenderedFloatingItems()) {
            if (rg == null || !rg.hasEditableTextHiddenFromPng()) continue;
            if (!"hwpx_tf".equals(rg.textOwner())) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            String[] ids = rg.editableTextFrameIds();
            if (ids == null) continue;
            for (String id : ids) {
                if (domId.equals(id)) return true;
            }
        }
        return false;
    }

    private static boolean hasStage1ObjectPlans(ResolvedData resolvedData) {
        return resolvedData != null
                && resolvedData.ownershipPlans() != null
                && !resolvedData.ownershipPlans().isEmpty();
    }
}
