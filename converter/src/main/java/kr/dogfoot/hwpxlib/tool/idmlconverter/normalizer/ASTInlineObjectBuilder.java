package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 인라인 그래픽, 이미지, 벡터 도형, 테이블 변환 로직.
 * Stage4_BuildAST에서 분리됨.
 */
class ASTInlineObjectBuilder {

    /**
     * InlineGraphic 내부의 TextFrame을 재귀적으로 수집하여 ASTParagraph에 추가.
     * 중첩 Group 구조에서도 모든 TextFrame을 찾아낸다.
     */
    static void collectChildTextFrames(IDMLCharacterRun.InlineGraphic ig,
                                        ASTParagraph para,
                                        IDMLDocument idmlDoc,
                                        ColorResolver colorResolver,
                                        ASTImageLoader imageLoader,
                                        GroupBackground bg,
                                        ResolvedData resolvedData) {
        // 자식 TextFrame이 여러 개이면 bg fill 전파 안 함 (컨테이너 배경이지 개별 자식 배경이 아님)
        GroupBackground effectiveBg = bg;
        if (bg != null && bg.fillHex != null && countChildTextFramesRecursive(ig) > 1) {
            effectiveBg = new GroupBackground();
            effectiveBg.strokeHex = bg.strokeHex;
            effectiveBg.strokeWeight = bg.strokeWeight;
            effectiveBg.strokeTint = bg.strokeTint;
            effectiveBg.cornerRadius = bg.cornerRadius;
            effectiveBg.bgLeft = bg.bgLeft;
            effectiveBg.bgTop = bg.bgTop;
            effectiveBg.bgRight = bg.bgRight;
            effectiveBg.bgBottom = bg.bgBottom;
            effectiveBg.hasBounds = bg.hasBounds;
        }
        collectChildTextFramesInternal(ig, para, null, idmlDoc, colorResolver, imageLoader, effectiveBg,
                false, 0, 0, 0, 0, 0, 0, 0, 0, null, resolvedData);
    }

    private static int countChildTextFramesRecursive(IDMLCharacterRun.InlineGraphic ig) {
        int count = ig.childTextFrames().size();
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            count += countChildTextFramesRecursive(child);
        }
        return count;
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
                                      GroupBackground bg,
                                      ResolvedData resolvedData) {
        double[] canvasBounds;
        if (countImageChildren(ig) >= 2) {
            // 합성 이미지 그룹: 비-텍스트 자식의 바운드 (합성 캔버스와 동일)
            canvasBounds = ASTOverlayBuilder.computeNonTextVisualBounds(ig);
        } else {
            // 단일 이미지: 이미지 프레임의 그룹 내 위치
            IDMLCharacterRun.InlineGraphic imageFrame = findImageFrame(ig);
            double[] imgPos = ASTOverlayBuilder.findImagePositionInGroup(ig, imageFrame);
            if (imgPos != null) {
                canvasBounds = new double[]{imgPos[0], imgPos[1],
                        imgPos[0] + imgPos[2], imgPos[1] + imgPos[3]};
            } else {
                canvasBounds = null;
            }
        }

        if (canvasBounds == null || canvasBounds[0] >= canvasBounds[2]
                || canvasBounds[1] >= canvasBounds[3]) {
            // 폴백: contentBounds 사용
            double[] imgPos;
            if (countImageChildren(ig) >= 2) {
                double[] vb = ASTOverlayBuilder.computeGroupVisualBounds(ig);
                imgPos = new double[]{vb[0], vb[1], vb[2] - vb[0], vb[3] - vb[1]};
            } else {
                imgPos = null;
            }
            double[] contentBBox = ASTOverlayBuilder.computeContentBounds(ig, imgPos);
            canvasBounds = contentBBox;
        }

        double rootLeft = canvasBounds[0];
        double rootTop = canvasBounds[1];
        double canvasWidthPts = canvasBounds[2] - canvasBounds[0];
        double canvasHeightPts = canvasBounds[3] - canvasBounds[1];

        if (canvasWidthPts <= 0 || canvasHeightPts <= 0) return;

        // 오버레이 위치는 이미지 기준 — 컨테이너 확장 불필요
        imageObj.containerWidth(imageObj.width());
        imageObj.containerHeight(imageObj.height());
        imageObj.imageOffsetX(0);
        imageObj.imageOffsetY(0);

        collectChildTextFramesInternal(ig, null, imageObj, idmlDoc, colorResolver, imageLoader, bg,
                true, imageObj.width(), imageObj.height(),
                canvasWidthPts, canvasHeightPts, rootLeft, rootTop, 0, 0, ig, resolvedData);
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
                                                         double accTy,
                                                         IDMLCharacterRun.InlineGraphic rootGroup,
                                                         ResolvedData resolvedData) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            if (isHwpxOwnedChildTextFrame(childTf, resolvedData)) {
                continue;
            }
            // 수식 폰트 전용 TextFrame 건너뛰기 (괄호/중괄호 장식 — HWPX에서 표현 불가)
            if (isMathFontOnlyStory(childTf, idmlDoc)) continue;

            ASTInlineObject childObj = ASTStoryConverter.createInlineObjectFromTextFrame(childTf, idmlDoc, colorResolver, imageLoader, resolvedData);
            if (childObj != null) {
                // 부모 그룹의 anchoredPosition을 자식에 전달
                if (childObj.anchoredPosition() == null && ig.anchoredPosition() != null) {
                    childObj.anchoredPosition(ig.anchoredPosition());
                }
                if (bg != null && childObj.fillColor() == null) {
                    childObj.fillColor(bg.fillHex);
                    childObj.fillTint(bg.fillTint);
                    if (bg.strokeHex != null && childObj.strokeColor() == null) {
                        childObj.strokeColor(bg.strokeHex);
                        childObj.strokeWeight(bg.strokeWeight);
                        childObj.strokeTint(bg.strokeTint);
                    }
                    if (bg.cornerRadius > 0) {
                        childObj.cornerRadius(bg.cornerRadius);
                    }
                    // 배경 사각형과 텍스트 프레임의 위치 차이 → 암묵적 텍스트 여백
                    if (bg.hasBounds) {
                        ASTOverlayBuilder.applyImplicitTextMargin(childObj, childTf, bg);
                    }
                }

                if (isOverlay && targetImageObj != null) {
                    // 오버레이 모드: 부모 이미지 내 상대 위치 계산 후 이미지 객체에 첨부
                    ASTOverlayBuilder.applyOverlayPosition(childObj, childTf, rootGroup, accTx, accTy,
                            imageDisplayWidth, imageDisplayHeight,
                            groupWidthPts, groupHeightPts, rootLeft, rootTop);
                    targetImageObj.addOverlayFrame(childObj);
                } else {
                    // 자식 TextFrame의 누적 위치 오프셋이 유의미하면 플로팅 배치
                    double totalTx = accTx;
                    double totalTy = accTy;
                    double[] childTransform = childTf.itemTransform();
                    if (childTransform != null) {
                        totalTx += childTransform[4];
                        totalTy += childTransform[5];
                    }
                    if (Math.abs(totalTx) > 5 || Math.abs(totalTy) > 5) {
                        childObj.isOverlay(true);
                        double overlayTx = totalTx;
                        double overlayTy = totalTy;
                        // IDML 좌표계 아티팩트 보정: 부모 InlineGraphic의 PathPoint가
                        // 원점에서 멀리 떨어진 경우 (예: pathMinY=8000), 자식 ItemTransform도
                        // 이를 반영하여 큰 값을 가짐. 시각적 오프셋은 작지만 원시 값이 크므로
                        // 부모/자식의 geometricBounds(PathPoint 경계)를 사용하여 정규화.
                        double[] parentGB = ig.geometricBounds();
                        if (parentGB != null) {
                            double parentH = Math.abs(parentGB[2] - parentGB[0]);
                            double parentW = Math.abs(parentGB[3] - parentGB[1]);
                            if (parentH > 0 && parentW > 0
                                    && (Math.abs(overlayTy) > parentH * 2
                                        || Math.abs(overlayTx) > parentW * 2)) {
                                double[] childGB = childTf.geometricBounds();
                                if (childGB != null) {
                                    overlayTx = (overlayTx + childGB[1]) - parentGB[1];
                                    overlayTy = (overlayTy + childGB[0]) - parentGB[0];
                                }
                            }
                        }
                        childObj.overlayX(CoordinateConverter.pointsToHwpunits(overlayTx));
                        childObj.overlayY(CoordinateConverter.pointsToHwpunits(overlayTy));
                    }
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
                    childAccTx, childAccTy, rootGroup, resolvedData);
        }
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

    static boolean hasHwpxOwnedChildTextFrameRecursive(IDMLCharacterRun.InlineGraphic ig,
                                                       ResolvedData resolvedData) {
        if (ig == null || resolvedData == null) return false;
        if (ig.childTextFrames() != null) {
            for (IDMLTextFrame tf : ig.childTextFrames()) {
                if (isHwpxOwnedChildTextFrame(tf, resolvedData)) {
                    return true;
                }
            }
        }
        if (ig.childGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
                if (hasHwpxOwnedChildTextFrameRecursive(child, resolvedData)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHwpxOwnedChildTextFrame(IDMLTextFrame tf, ResolvedData resolvedData) {
        if (tf == null || resolvedData == null || tf.selfId() == null) return false;
        String domId = ParagraphTextHelpers.domIdFromSourceId(tf.selfId());
        return domId != null && resolvedData.isHwpxOwnedTextFrame(domId);
    }

    /**
     * 자식 TextFrame 중 실제 텍스트 콘텐츠가 있는지 확인.
     * 텍스트 없는 TextFrame(체크박스 채움용 등)은 false 반환.
     */
    static boolean hasChildTextFramesWithContent(IDMLCharacterRun.InlineGraphic ig,
                                                  IDMLDocument idmlDoc) {
        if (idmlDoc == null) return hasChildTextFramesRecursive(ig);
        return hasChildTextFramesWithContentRecursive(ig, idmlDoc);
    }

    private static boolean hasChildTextFramesWithContentRecursive(IDMLCharacterRun.InlineGraphic ig,
                                                                    IDMLDocument idmlDoc) {
        if (ig.childTextFrames() != null) {
            for (IDMLTextFrame tf : ig.childTextFrames()) {
                String storyId = tf.parentStoryId();
                if (storyId == null) continue;
                IDMLStory story = idmlDoc.getStory(storyId);
                if (story == null) continue;
                if (story.hasTables()) return true;
                for (IDMLParagraph p : story.paragraphs()) {
                    for (IDMLCharacterRun run : p.characterRuns()) {
                        String c = run.content();
                        if (c != null && !c.trim().isEmpty()) return true;
                    }
                }
            }
        }
        if (ig.childGraphics() != null) {
            for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
                if (hasChildTextFramesWithContentRecursive(child, idmlDoc)) return true;
            }
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
     * 인라인 Group의 직접 자식 그래픽에서 배경 사각형의 전체 스타일을 추출.
     * fill, stroke, cornerRadius 등을 포함.
     * 깊은 중첩 Group은 resolved 데이터로 처리하므로 직접 자식만 검색.
     */
    static GroupBackground extractGroupBackground(IDMLCharacterRun.InlineGraphic ig,
                                                   ColorResolver colorResolver) {
        if (!"group".equals(ig.type())) {
            // 비-그룹(Rectangle/Oval 등)이 자식 TextFrame을 포함하면 자체 vectorShape에서 배경 추출
            if (ig.hasVectorShape() && ig.childTextFrames() != null && !ig.childTextFrames().isEmpty()) {
                IDMLVectorShape shape = ig.vectorShape();
                if (shape.fillColor() != null) {
                    String hex = resolveColorHex(shape.fillColor(), colorResolver);
                    if (hex != null) {
                        GroupBackground bg = new GroupBackground();
                        bg.fillHex = blendColorWithWhite(hex, shape.fillTint() / 100.0);
                        bg.fillTint = 100;
                        String sHex = resolveColorHex(shape.strokeColor(), colorResolver);
                        bg.strokeHex = sHex != null
                                ? blendColorWithWhite(sHex, shape.strokeTint() / 100.0) : null;
                        bg.strokeWeight = shape.strokeWeight();
                        bg.strokeTint = 100;
                        bg.cornerRadius = effectiveCornerRadius(shape);
                        // 비-그룹: hasBounds 설정 안 함 (좌표계가 자식 TF와 다르므로 마진 계산 불가)
                        return bg;
                    }
                }
            }
            return null;
        }
        // 1. 직접 자식 그래픽에서 배경 벡터 도형 찾기
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasVectorShape()) {
                IDMLVectorShape shape = child.vectorShape();
                if (shape.fillColor() != null) {
                    String hex = resolveColorHex(shape.fillColor(), colorResolver);
                    if (hex != null) {
                        GroupBackground bg = new GroupBackground();
                        bg.fillHex = blendColorWithWhite(hex, shape.fillTint() / 100.0);
                        bg.fillTint = 100;
                        String sHex = resolveColorHex(shape.strokeColor(), colorResolver);
                        bg.strokeHex = sHex != null
                                ? blendColorWithWhite(sHex, shape.strokeTint() / 100.0) : null;
                        bg.strokeWeight = shape.strokeWeight();
                        bg.strokeTint = 100;
                        bg.cornerRadius = effectiveCornerRadius(shape);
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
        // 2. stroke-only 배경 사각형 감지 (fill 없이 테두리만 있는 경우)
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasVectorShape()) {
                IDMLVectorShape shape = child.vectorShape();
                String sHex = resolveColorHex(shape.strokeColor(), colorResolver);
                if (sHex != null && shape.strokeWeight() > 0) {
                    GroupBackground bg = new GroupBackground();
                    bg.strokeHex = blendColorWithWhite(sHex, shape.strokeTint() / 100.0);
                    bg.strokeWeight = shape.strokeWeight();
                    bg.strokeTint = 100;
                    bg.cornerRadius = effectiveCornerRadius(shape);
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
        // 3. 폴백: Group 자체의 FillColor 확인 (자식 사각형 없이 그룹에 직접 fill이 설정된 경우)
        String groupFillHex = resolveColorHex(ig.groupFillColor(), colorResolver);
        if (groupFillHex != null) {
            GroupBackground bg = new GroupBackground();
            bg.fillHex = blendColorWithWhite(groupFillHex, ig.groupFillTint() / 100.0);
            bg.fillTint = 100;
            String groupStrokeHex = resolveColorHex(ig.groupStrokeColor(), colorResolver);
            bg.strokeHex = groupStrokeHex != null
                    ? blendColorWithWhite(groupStrokeHex, ig.groupStrokeTint() / 100.0) : null;
            bg.strokeWeight = ig.groupStrokeWeight();
            bg.strokeTint = 100;
            double[] gb = ig.geometricBounds();
            if (gb != null) {
                bg.bgLeft = gb[1];
                bg.bgTop = gb[0];
                bg.bgRight = gb[3];
                bg.bgBottom = gb[2];
                bg.hasBounds = true;
            }
            return bg;
        }
        return null;
    }

    /**
     * resolved 데이터에서 인라인 Group의 fill 색상을 조회하여 GroupBackground 생성.
     * 깊은 중첩 Group 구조에서 IDML 직접 탐색으로 못 찾는 경우,
     * 플랫화된 resolved.json 데이터에서 fill 정보를 가져온다.
     */
    static GroupBackground extractGroupBackgroundFromResolved(IDMLCharacterRun.InlineGraphic ig,
                                                               ResolvedData resolvedData) {
        if (resolvedData == null || ig.selfId() == null) return null;
        ResolvedPageItem item = resolvedData.getPageItemByIdmlId(ig.selfId());
        if (item == null || item.fillColorName() == null) return null;
        // "[None]" 또는 빈 문자열 제외
        String colorName = item.fillColorName();
        if (colorName.isEmpty() || colorName.contains("None")) return null;
        String hex = resolvedData.resolveColorHex(colorName);
        if (hex == null) return null;

        GroupBackground bg = new GroupBackground();
        bg.fillHex = blendColorWithWhite(hex, item.fillTint() / 100.0);
        bg.fillTint = 100;
        // stroke 정보
        if (item.strokeColorName() != null && !item.strokeColorName().contains("None")) {
            String strokeHex = resolvedData.resolveColorHex(item.strokeColorName());
            if (strokeHex != null) {
                bg.strokeHex = blendColorWithWhite(strokeHex, item.strokeTint() / 100.0);
                bg.strokeWeight = item.strokeWeight();
                bg.strokeTint = 100;
            }
        }
        // 바운드는 ig의 geometricBounds 사용
        double[] gb = ig.geometricBounds();
        if (gb != null) {
            bg.bgLeft = gb[1];
            bg.bgTop = gb[0];
            bg.bgRight = gb[3];
            bg.bgBottom = gb[2];
            bg.hasBounds = true;
        }
        return bg;
    }

    /**
     * 색상 hex를 fraction 비율로 흰색과 블렌딩.
     * fraction=1.0 → 원래 색상, fraction=0.0 → 흰색.
     */
    static String blendColorWithWhite(String hex, double fraction) {
        return ColorResolver.blendColorWithWhite(hex, fraction);
    }

    /**
     * IDML 색상 참조를 hex 문자열로 변환.
     * "None" 또는 null이면 null 반환.
     */
    /** ItemTransform에 회전 성분이 있는지 확인 (b 또는 c가 0이 아닌 경우) */
    private static boolean hasRotation(double[] transform) {
        if (transform == null || transform.length < 4) return false;
        return Math.abs(transform[1]) > 0.001 || Math.abs(transform[2]) > 0.001;
    }

    static String resolveColorHex(String colorRef, ColorResolver colorResolver) {
        if (colorRef == null || "None".equals(colorRef) || colorRef.contains("[None]")) return null;
        String hex = colorResolver.resolve(colorRef);
        return (hex != null && !hex.isEmpty()) ? hex : null;
    }

    /**
     * HWPX는 uniform corner ratio만 지원하므로,
     * per-corner radii가 있으면 최소값을 사용하여 과도한 둥글기를 방지한다.
     */
    static double effectiveCornerRadius(IDMLVectorShape shape) {
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
     * 인라인 그래픽 → ASTInlineObject 변환.
     * 이미지 링크가 있으면 이미지 데이터를 로드한다.
     */
    static ASTInlineObject createInlineObjectFromGraphic(IDMLCharacterRun.InlineGraphic ig,
                                                          ASTImageLoader imageLoader,
                                                          ColorResolver colorResolver,
                                                          IDMLDocument idmlDoc) {
        ASTInlineObject obj = new ASTInlineObject();
        obj.sourceId(ig.selfId());

        long w = CoordinateConverter.pointsToHwpunits(ig.widthPoints());
        long h = CoordinateConverter.pointsToHwpunits(ig.heightPoints());
        obj.width(w);
        obj.height(h);

        // 다중 이미지 그룹 (2+개 이미지 자식) → 합성 래스터화
        int imageChildCount = countImageChildren(ig);
        if (imageChildCount >= 2 && imageLoader != null) {
            ASTImageLoader.ImageResult compositeResult =
                    imageLoader.compositeGroupChildren(ig, colorResolver);
            if (compositeResult != null && compositeResult.imageData != null) {
                obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                obj.imageData(compositeResult.imageData);
                obj.imageFormat(compositeResult.format);
                obj.pixelWidth(compositeResult.pixelWidth);
                obj.pixelHeight(compositeResult.pixelHeight);
                if (compositeResult.widthPts > 0 && compositeResult.heightPts > 0) {
                    obj.width(CoordinateConverter.pointsToHwpunits(compositeResult.widthPts));
                    obj.height(CoordinateConverter.pointsToHwpunits(compositeResult.heightPts));
                }
                // 앵커/래핑 속성 복사
                obj.anchoredPosition(ig.anchoredPosition());
                obj.textWrapMode(ig.textWrapMode());
                obj.textWrapSide(ig.textWrapSide());
                obj.textWrapTop(CoordinateConverter.pointsToHwpunits(ig.textWrapTop()));
                obj.textWrapLeft(CoordinateConverter.pointsToHwpunits(ig.textWrapLeft()));
                obj.textWrapBottom(CoordinateConverter.pointsToHwpunits(ig.textWrapBottom()));
                obj.textWrapRight(CoordinateConverter.pointsToHwpunits(ig.textWrapRight()));
                return obj;
            }
            // 합성 실패 시 기존 단일 이미지 경로로 폴백
        }

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

            // 내장 이미지 데이터 확인
            String embeddedData = imgSrc.hasEmbeddedContents()
                    ? imgSrc.embeddedContents()
                    : (ig.hasEmbeddedContents() ? ig.embeddedContents() : null);

            ASTImageLoader.ImageResult result;
            if ((linkURI == null || linkURI.isEmpty()) && embeddedData != null) {
                result = imageLoader.loadEmbeddedImage(
                        embeddedData, displayW, displayH,
                        imgTransform, frameBounds, graphicBounds, null, 0, null);
            } else {
                result = imageLoader.loadImage(
                        linkURI, displayW, displayH,
                        imgTransform, frameBounds, graphicBounds);
            }

            if (result != null) {
                obj.imageData(result.imageData);
                obj.imageFormat(result.format);
                obj.pixelWidth(result.pixelWidth);
                obj.pixelHeight(result.pixelHeight);
            }
        } else if (ig.hasVectorShape() && imageLoader != null
                && !hasChildTextFramesWithContent(ig, idmlDoc)) {
            // 자식 TextFrame에 실제 텍스트가 있으면 래스터화 건너뜀 (배경+텍스트 배지 패턴은 래퍼 글상자로 처리)
            // 텍스트 없는 자식 TextFrame만 있으면 (체크박스 등) 벡터 래스터화 진행
            IDMLVectorShape shape = ig.vectorShape();

            // 벡터 도형 (글리프 아웃라인 등) → PNG 래스터화
            String fillHex = resolveColorHex(shape.fillColor(), colorResolver);
            String strokeHex = resolveColorHex(shape.strokeColor(), colorResolver);

            // 자식 그래픽 중 벡터 도형이 있으면 합성 래스터화 (체크박스+체크마크 등)
            List<ASTImageLoader.ShapeWithColor> childVectorShapes = collectChildVectorShapes(ig, colorResolver);

            if (fillHex != null || strokeHex != null || !childVectorShapes.isEmpty()) {
                obj.kind(ASTInlineObject.ObjectKind.IMAGE);

                ASTImageLoader.ImageResult result;
                if (!childVectorShapes.isEmpty()) {
                    List<ASTImageLoader.ShapeWithColor> allShapes = new ArrayList<>();
                    if (fillHex != null || strokeHex != null) {
                        allShapes.add(new ASTImageLoader.ShapeWithColor(shape, fillHex, strokeHex, null));
                    }
                    allShapes.addAll(childVectorShapes);
                    result = imageLoader.rasterizeShapes(allShapes, ig.itemTransform());
                } else if (hasRotation(ig.itemTransform())) {
                    // ItemTransform에 회전이 포함된 인라인 도형 → 변환 적용하여 래스터화
                    // (InDesign은 미회전 폭으로 텍스트 흐름을 계산하므로 표시 크기는 원본 유지)
                    List<ASTImageLoader.ShapeWithColor> single = new ArrayList<>();
                    single.add(new ASTImageLoader.ShapeWithColor(shape, fillHex, strokeHex, ig.itemTransform()));
                    result = imageLoader.rasterizeShapes(single, null);
                    // 래스터화 결과의 widthPts/heightPts를 미회전 크기로 복원
                    if (result != null) {
                        result.widthPts = ig.widthPoints();
                        result.heightPts = ig.heightPoints();
                    }
                } else {
                    result = imageLoader.rasterizeShape(shape, fillHex, strokeHex);
                }

                if (result != null && result.imageData != null) {
                    obj.imageData(result.imageData);
                    obj.imageFormat(result.format);
                    obj.pixelWidth(result.pixelWidth);
                    obj.pixelHeight(result.pixelHeight);
                    if (result.widthPts > 0 && result.heightPts > 0) {
                        obj.width(CoordinateConverter.pointsToHwpunits(result.widthPts));
                        obj.height(CoordinateConverter.pointsToHwpunits(result.heightPts));
                    }
                } else {
                    obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
                }
            } else {
                obj.kind(ASTInlineObject.ObjectKind.RENDERED_GROUP);
            }
        } else if (imageLoader != null) {
            // 그룹 내 자식 텍스트프레임에 실제 텍스트가 있으면 래스터화하지 않고 텍스트 우선 처리
            // (배경 사각형 + 텍스트프레임 구조는 processInlineGraphic에서 래퍼 글상자로 변환)
            boolean hasChildTextContent = hasChildTextFramesWithContent(ig, idmlDoc);
            List<ASTImageLoader.ShapeWithColor> childShapes = collectChildVectorShapes(ig, colorResolver);
            if (!childShapes.isEmpty() && !hasChildTextContent) {
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
            } else if (hasChildTextContent && countLeafChildren(ig) >= 5) {
                // 복잡 그룹 (리프 ≥5 + 텍스트) → 이미지+벡터 합성, 텍스트는 오버레이
                obj.kind(ASTInlineObject.ObjectKind.IMAGE);
                ASTImageLoader.ImageResult result = imageLoader.compositeGroupChildren(ig, colorResolver);
                if (result != null && result.imageData != null) {
                    obj.imageData(result.imageData);
                    obj.imageFormat(result.format);
                    obj.pixelWidth(result.pixelWidth);
                    obj.pixelHeight(result.pixelHeight);
                    if (result.widthPts > 0 && result.heightPts > 0) {
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
     * 인라인 그래픽 그룹의 리프 자식 수를 재귀적으로 센다.
     * 벡터 도형 + 텍스트 프레임 합산. 복잡도 판별에 사용.
     */
    static int countLeafChildren(IDMLCharacterRun.InlineGraphic ig) {
        int count = 0;
        if (ig.hasVectorShape()) count++;
        count += ig.childTextFrames().size();
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            count += countLeafChildren(child);
        }
        return count;
    }

    /**
     * 인라인 그룹의 자식 그래픽에서 벡터 도형(VectorShape)을 재귀적으로 수집한다.
     * 각 도형에 누적 ItemTransform을 부여하여 그룹 루트 좌표계 기준으로 정확한
     * 위치/크기를 계산할 수 있게 한다.
     */
    static List<ASTImageLoader.ShapeWithColor> collectChildVectorShapes(
            IDMLCharacterRun.InlineGraphic ig, ColorResolver colorResolver) {
        List<ASTImageLoader.ShapeWithColor> result = new ArrayList<>();
        // Group의 자체 회전 변환을 자식들에게 누적 (예: 45° 회전 그룹 → + 도형이 × 로 렌더링)
        double[] initialParent = "group".equals(ig.type()) ? ig.itemTransform() : null;
        collectChildVectorShapesRecursive(ig, colorResolver, result, initialParent);
        return result;
    }

    static void collectChildVectorShapesRecursive(
            IDMLCharacterRun.InlineGraphic ig, ColorResolver colorResolver,
            List<ASTImageLoader.ShapeWithColor> result, double[] parentTransform) {
        // Group 레벨 색상 폴백 (자식에 명시적 색상 없을 때 사용)
        String groupStrokeFallback = resolveColorHex(ig.groupStrokeColor(), colorResolver);
        String groupFillFallback = resolveColorHex(ig.groupFillColor(), colorResolver);

        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            // 자식의 ItemTransform을 부모 누적 변환과 결합
            double[] childTransform = child.itemTransform();
            double[] accTransform = combineInlineTransforms(parentTransform, childTransform);

            if (child.hasVectorShape()) {
                IDMLVectorShape shape = child.vectorShape();
                String fillHex = resolveColorHex(shape.fillColor(), colorResolver);
                String strokeHex = resolveColorHex(shape.strokeColor(), colorResolver);
                // 자식에 명시적 색상 없으면 부모 Group 색상 폴백
                if (fillHex == null) fillHex = groupFillFallback;
                if (strokeHex == null) strokeHex = groupStrokeFallback;
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
     * 인라인 그래픽 계층에서 이미지를 포함하는 자식 프레임의 수를 재귀적으로 세기.
     * 2+개이면 다중 이미지 그룹으로 합성 래스터화 대상.
     */
    static int countImageChildren(IDMLCharacterRun.InlineGraphic ig) {
        int count = 0;
        for (IDMLCharacterRun.InlineGraphic child : ig.childGraphics()) {
            if (child.hasImage() && child.childGraphics().isEmpty()) {
                count++;
            }
            count += countImageChildren(child);
        }
        return count;
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
     * TextFrame의 스토리가 수식 폰트(NP 또는 BT) 전용인지 확인.
     * 수식 폰트 전용 TextFrame은 괄호/중괄호 장식이므로 인라인 텍스트로 변환하면 안 된다.
     */
    static boolean isMathFontOnlyStory(IDMLTextFrame tf, IDMLDocument idmlDoc) {
        if (tf.parentStoryId() == null) return false;
        IDMLStory story = idmlDoc.getStory(tf.parentStoryId());
        if (story == null) return false;
        boolean hasAnyContent = false;
        for (IDMLParagraph para : story.paragraphs()) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                String text = run.content();
                if (text == null || text.trim().isEmpty()) continue;
                hasAnyContent = true;
                if (!run.isMathFont()) return false;
            }
        }
        return hasAnyContent;
    }
}
