package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
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
     * 오버레이 위치 계산: 텍스트프레임의 루트 그룹 내 상대 위치를 ASTInlineObject에 설정.
     * 누적 번역 오프셋(accTx, accTy)으로 중첩 Group 내 텍스트프레임도 처리.
     * 이미지 표시 크기와 그룹 바운드의 비율로 스케일링.
     */
    static void applyOverlayPosition(ASTInlineObject obj, IDMLTextFrame tf,
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

        // 콘텐츠 bbox 원점(minX, minY) 기준 상대 위치 (points)
        // 음수 클램핑 제거 — 이미지보다 왼쪽/위에 있는 텍스트프레임도 올바르게 배치
        double relX = tfX - rootLeft;
        double relY = tfY - rootTop;

        // 콘텐츠 bbox 크기(points) → 컨테이너 표시 크기(HWPUNIT) 비율로 스케일
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
                                                          ASTImageLoader imageLoader) {
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
        collectTextFramesWithPosition(ig, idmlDoc, childFrames, 0, 0);
        System.err.println("[BG-WRAPPER] childFrames found: " + childFrames.size()
                + " ig.type=" + ig.type() + " ig.childGraphics=" + ig.childGraphics().size()
                + " ig.childTFs=" + ig.childTextFrames().size());
        for (TextFrameWithPosition tfp : childFrames) {
            System.err.println("[BG-WRAPPER]   TF: storyId=" + tfp.tf.parentStoryId()
                    + " x=" + tfp.x + " y=" + tfp.y + " w=" + tfp.w + " h=" + tfp.h);
        }

        // Y 좌표 → X 좌표 순서로 정렬
        Collections.sort(childFrames, new Comparator<TextFrameWithPosition>() {
            public int compare(TextFrameWithPosition a, TextFrameWithPosition b) {
                int cmp = Double.compare(a.y, b.y);
                return cmp != 0 ? cmp : Double.compare(a.x, b.x);
            }
        });

        // 텍스트 여백 계산: 배경 사각형과 자식 텍스트프레임들의 전체 바운드 차이
        if (bg.hasBounds && !childFrames.isEmpty()) {
            double tfMinX = Double.MAX_VALUE, tfMinY = Double.MAX_VALUE;
            double tfMaxX = -Double.MAX_VALUE, tfMaxY = -Double.MAX_VALUE;
            for (TextFrameWithPosition tfp : childFrames) {
                tfMinX = Math.min(tfMinX, tfp.x);
                tfMinY = Math.min(tfMinY, tfp.y);
                tfMaxX = Math.max(tfMaxX, tfp.x + tfp.w);
                tfMaxY = Math.max(tfMaxY, tfp.y + tfp.h);
            }
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

        // 각 텍스트프레임의 단락을 래퍼에 병합
        FlattenedObjectPool emptyPool = new FlattenedObjectPool();
        for (TextFrameWithPosition tfp : childFrames) {
            IDMLStory story = idmlDoc.getStory(tfp.tf.parentStoryId());
            if (story == null) continue;
            for (IDMLParagraph idmlPara : story.paragraphs()) {
                ASTParagraph astPara = ASTStoryConverter.convertParagraph(
                        idmlPara, emptyPool, idmlDoc, colorResolver, imageLoader, false, null);
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
                                                        double accTx, double accTy) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
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
            result.add(new TextFrameWithPosition(childTf, x, y, w, h));
        }
        // 중첩 그래픽(Group 등)도 재귀 탐색
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            double[] ct = childIg.itemTransform();
            double childAccTx = accTx + (ct != null ? ct[4] : 0);
            double childAccTy = accTy + (ct != null ? ct[5] : 0);
            collectTextFramesWithPosition(childIg, idmlDoc, result, childAccTx, childAccTy);
        }
    }
}
