package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase7;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import java.io.File;
import java.util.List;

/**
 * SPEC-013 Phase 7: renderable TF(배지)를 플로팅 이미지로 배치.
 *
 * <p>resolved.allRenderedTextFrames() 중 type이 없는(일반 renderable) 항목의 PNG를
 * ASTFigure로 변환해 배경(zOrder=0) 위에 배치한다. badge_group은 인라인으로 이미
 * 처리되었으므로 건너뛴다.</p>
 *
 * <p>{@code ResolvedToASTBuilder.placeRenderableFrames}에서 stateless static helper로
 * 발췌. 동작은 동일하며 ResolvedToASTBuilder는 위임만 한다.</p>
 */
public final class RenderableFramePlacer {

    private RenderableFramePlacer() {}

    public static void place(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.basePath == null) return;
        if (ctx.resolvedData == null) return;
        int count = 0;
        // 같은 PNG 파일을 여러 ID로 등록한 경우 중복 배치 방지 (페이지+파일 단위)
        java.util.Set<String> placedKeys = new java.util.HashSet<>();

        // 사전 계산: inline_object(Phase 3가 inline PNG로 처리하는 오발/그룹)와
        // 동일 파일/페이지 그룹에 속하는 renderedTextFrame dedupKey 세트.
        // 이 그룹에 속한 항목(부모 오발 + 자식 TF)은 모두 배치 스킵 — Phase 3가 처리.
        // (HashMap 순서 비결정성으로 자식 TF가 먼저 처리되면 부모 inline_object 체크를
        // 건너뛰고 TextFrameBlock이 생성되는 버그 방지.)
        java.util.Set<String> inlineObjDedupKeys = new java.util.HashSet<>();
        for (RenderedGroup rt2 : ctx.resolvedData.allRenderedTextFrames()) {
            if (rt2.file() == null) continue;
            String dk2 = rt2.pageIndex() + "|" + rt2.file();
            if (inlineObjDedupKeys.contains(dk2)) continue;
            for (RenderedGroup flt2 : ctx.resolvedData.allRenderedFloatingItems()) {
                if (flt2.id() == rt2.id() && "inline_object".equals(flt2.itemType())) {
                    inlineObjDedupKeys.add(dk2);
                    break;
                }
            }
        }

        for (RenderedGroup rt : ctx.resolvedData.allRenderedTextFrames()) {
            if (rt.file() == null) continue;
            // badge_group_child: Phase 2 가 이미 TextFrameBlock 으로 배치.
            // Phase 7 이 재처리하면 dedupKey 경합으로 부모 badge_group PNG 가 skip 될 수 있고,
            // "≤20자 non-inline float" 경로로 중복 TextFrameBlock 이 생성됨 → 무조건 skip.
            if (rt.isBadgeGroupChild()) continue;
            // Phase 2 가 텍스트 글상자로 배치한 TF → PNG 건너뜀 (dedupKey 선점 전에 체크).
            // 부모 Rectangle 항목은 별도로 같은 PNG 를 배치 → 배경 도형으로 남음.
            if (ctx.isDisposed(rt.id(), FrameDisposition.TEXT_BLOCK_PLACED)) continue;
            String dedupKey = rt.pageIndex() + "|" + rt.file();
            if (!placedKeys.add(dedupKey)) {
                continue; // 이미 배치된 동일 파일/페이지
            }
            // inline_object 그룹: Phase 3가 inline PNG로 처리 → 배치 스킵
            if (inlineObjDedupKeys.contains(dedupKey)) {
                continue;
            }
            // badge_group은 인라인 앵커(inline_object)로 배치된 경우에만 건너뜀.
            // 인라인 참조가 없는 독립 badge는 여기서 플로팅으로 배치해야 함.
            if (rt.isBadgeGroup()) {
                // 직접 inline_object 연결: O(1) 인덱스 조회
                boolean alsoInline = ctx.resolvedData.isBadgeGroupAlsoInline(rt.id());
                // 조상이 inline_object인 경우: 해당 조상 PNG에 배지 시각이 이미 포함됨 → 중복 배치 방지
                if (!alsoInline) {
                    ResolvedPageItem badgeItem = ctx.resolvedData.getPageItem(String.valueOf(rt.id()));
                    if (badgeItem != null) {
                        String ancestorId = badgeItem.parentId();
                        int hops = 0;
                        while (ancestorId != null && hops < 5 && !alsoInline) {
                            try { alsoInline = ctx.resolvedData.isInlineObjectId(Integer.parseInt(ancestorId)); }
                            catch (NumberFormatException ignored) {}
                            if (!alsoInline) {
                                ResolvedPageItem anc = ctx.resolvedData.getPageItem(ancestorId);
                                if (anc == null) break;
                                ancestorId = anc.parentId();
                            }
                            hops++;
                        }
                    }
                }
                if (alsoInline) continue;
                // 비-인라인 배지: badge PNG를 항상 배치. Phase 2의 skipAsBadgeChild가 text box 중복 방지.
            }

            File pngFile = new File(ctx.basePath, rt.file());

            int pageIdx = ctx.toSectionIndex.applyAsInt(rt.pageIndex());

            try {
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null) continue;

                double[] bounds = rt.bounds();
                // bounds는 normalizeToPoints()에서 이미 pt 단위로 변환됨
                double rawLeft = bounds[1], rawTop = bounds[0];
                double rawRight = bounds[3], rawBottom = bounds[2];
                double fullW = rawRight - rawLeft;
                double fullH = rawBottom - rawTop;

                // 페이지 경계 밖으로 넘치는 PNG를 가시 영역으로 크롭
                double pageWidthPt = 1e9, pageHeightPt = 1e9;
                if (ctx.resolvedData.pages() != null
                        && rt.pageIndex() >= 0
                        && rt.pageIndex() < ctx.resolvedData.pages().size()) {
                    double[] pgB = ctx.resolvedData.pages().get(rt.pageIndex()).bounds();
                    if (pgB != null && pgB.length >= 4) {
                        pageWidthPt = pgB[3] - pgB[1];
                        pageHeightPt = pgB[2] - pgB[0];
                    }
                }
                double visLeft = Math.max(0.0, rawLeft);
                double visTop = Math.max(0.0, rawTop);
                double visRight = Math.min(rawRight, pageWidthPt);
                double visBottom = Math.min(rawBottom, pageHeightPt);
                if (visLeft >= visRight || visTop >= visBottom) continue;
                if (fullW > 1.0 && fullH > 1.0
                        && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                            || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5)) {
                    int pxX = (int) Math.round((visLeft - rawLeft) / fullW * img.getWidth());
                    int pxY = (int) Math.round((visTop - rawTop) / fullH * img.getHeight());
                    int pxW = (int) Math.round((visRight - rawLeft) / fullW * img.getWidth()) - pxX;
                    int pxH = (int) Math.round((visBottom - rawTop) / fullH * img.getHeight()) - pxY;
                    pxX = Math.max(0, Math.min(pxX, img.getWidth() - 1));
                    pxY = Math.max(0, Math.min(pxY, img.getHeight() - 1));
                    pxW = Math.max(1, Math.min(img.getWidth() - pxX, pxW));
                    pxH = Math.max(1, Math.min(img.getHeight() - pxY, pxH));
                    try {
                        img = img.getSubimage(pxX, pxY, pxW, pxH);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        javax.imageio.ImageIO.write(img, "png", baos);
                        imageData = baos.toByteArray();
                    } catch (Exception cropEx) { /* keep original on crop failure */ }
                }

                double bw = visRight - visLeft;
                double bh = visBottom - visTop;

                // PNG 비율 보정 — 보정 후 가시 영역 중심을 유지하도록 x/y 재계산
                // badge_group은 Phase 2의 TextFrameBlock과 정확히 같은 위치에 배치해야 하므로
                // 비율 보정으로 y가 이동하면 텍스트와 배경이 어긋남 → badge_group은 보정 생략.
                double bwOrig = bw, bhOrig = bh;
                double pngRatio = (double) img.getWidth() / img.getHeight();
                double boundsRatio = bw / bh;
                if (!rt.isBadgeGroup()
                        && Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
                    if (pngRatio < 1.0) { bw = bh * pngRatio; } else { bh = bw / pngRatio; }
                }

                // 가시 영역 중심을 유지하면서 좌상단(x,y) 재산출
                double centerX = visLeft + bwOrig / 2.0;
                double centerY = visTop + bhOrig / 2.0;
                double x = centerX - bw / 2.0;
                double y = centerY - bh / 2.0;

                // SPEC-025: 인라인 앵커 PNG (예: 번호 라벨 "1") 가 텍스트프레임 첫 줄과 겹치면
                // PNG 를 좌측으로 shift 하여 텍스트와 겹치지 않게 한다.
                // InDesign 에서는 text wrap 으로 자동 회피되지만 HWPX 는 wrap 미지원 → 수동 보정.
                // 단, badge_group PNG 와 인라인 앵커가 아닌 일반 플로팅 PNG(배경 박스 등)는 shift 금지.
                // (일반 PNG에 shift를 적용하면 배경 박스가 제 위치를 벗어남 — frame_257021 사례)
                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame rtTfForShift
                        = ctx.resolvedData.getTextFrame(String.valueOf(rt.id()));
                boolean isInlineAnchorPng = rtTfForShift != null && rtTfForShift.isInline();
                if (!rt.isBadgeGroup() && isInlineAnchorPng) {
                    // PNG bounds 는 page-relative pt. composedLine bounds 는 spread pt → page 의 left/top 차감 필요.
                    double rtPageLeft = 0, rtPageTop = 0;
                    if (ctx.resolvedData.pages() != null
                            && rt.pageIndex() >= 0 && rt.pageIndex() < ctx.resolvedData.pages().size()) {
                        double[] pgB = ctx.resolvedData.pages().get(rt.pageIndex()).bounds();
                        if (pgB != null && pgB.length >= 4) {
                            rtPageTop = pgB[0];
                            rtPageLeft = pgB[1];
                        }
                    }
                    double pngLeft = x, pngRight = x + bw, pngTop = y, pngBottom = y + bh;
                    double maxShift = 0;
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame otf : ctx.resolvedData.textFrames()) {
                        if (otf == null) continue;
                        if (otf.pageIndex() != rt.pageIndex()) continue;
                        if (!ctx.resolvedData.isEditableTextFrame(otf.id())) continue;
                        if (otf.composedLines() == null || otf.composedLines().isEmpty()) continue;
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame.ComposedLine cl : otf.composedLines()) {
                            double[] clB = cl.bounds();
                            if (clB == null || clB.length < 4) continue;
                            double clTop = clB[0] - rtPageTop;
                            double clLeft = clB[1] - rtPageLeft;
                            double clBottom = clB[2] - rtPageTop;
                            double clRight = clB[3] - rtPageLeft;
                            boolean overlap = pngLeft < clRight && pngRight > clLeft
                                    && pngTop < clBottom && pngBottom > clTop;
                            if (!overlap) continue;
                            double gap = 2.0;
                            double shift = pngRight - (clLeft - gap);
                            if (shift > maxShift) maxShift = shift;
                        }
                    }
                    if (maxShift > 0) x -= maxShift;
                }

                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame rtTf
                        = ctx.resolvedData.getTextFrame(String.valueOf(rt.id()));
                // SPEC-025: renderable inline TF 가 짧은 단일 텍스트 (≤3자) 면 PNG 대신
                // TextFrameBlock 으로 변환 → 텍스트로 검색 가능 + 폰트 매핑/스케일 자유.
                // (예: 페이지 32 "1" 큰 번호 라벨)
                // null-type inline TF (예: 가/나 배지)는 Phase 3 가 ASTTextRun 으로 처리 → Phase 7 건너뜀.
                if (rt.itemType() == null && rtTf != null && rtTf.isInline()) {
                    continue;
                }
                boolean convertedToText = false;
                if (rtTf != null && rtTf.isInline() && !rt.isBadgeGroup()) {
                    String visText = rtTf.frameVisibleText();
                    if (visText != null) {
                        String cleaned = visText.replace("￼", "").replace("\r", "").replace("\n", "").trim();
                        if (!cleaned.isEmpty() && cleaned.length() <= 3) {
                            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock block =
                                    buildShortTextBlock(ctx, "renderable_text_", rt.id(), rtTf, bounds, cleaned, rt.zOrder());
                            sections.get(pageIdx).addBlock(block);
                            count++;
                            convertedToText = true;
                        }
                    }
                }
                // SPEC-025 확장: 비-인라인 플로팅 renderable TF 도 짧은 단일 텍스트(≤20자, 인라인 객체 없음)면
                // PNG 대신 TextFrameBlock 으로 변환. 예: "한글 맞춤법의 원리" (소단원명 데코 TF)
                // 단, 비-편집 가능 TF는 PNG로 처리 (시각 스타일 보존 우선 — 예: 보기 박스 내 자식 TF).
                // 비-편집 TF가 TextFrameBlock을 먼저 생성하면 dedupKey를 선점 → 부모 컨테이너 ASTFigure가 skip됨.
                if (!convertedToText && rtTf != null && !rtTf.isInline() && !rt.isBadgeGroup()
                        && ctx.resolvedData.isEditableTextFrame(rtTf.id())) {
                    String visText2 = rtTf.frameVisibleText();
                    if (visText2 != null && !visText2.contains("￼")) {
                        String cleaned2 = visText2.replace("\r", "").replace("\n", "").trim();
                        if (!cleaned2.isEmpty() && cleaned2.length() <= 20) {
                            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock block2 =
                                    buildShortTextBlock(ctx, "renderable_text_float_", rt.id(), rtTf, bounds, cleaned2, rt.zOrder());
                            sections.get(pageIdx).addBlock(block2);
                            count++;
                            convertedToText = true;
                        }
                    }
                }
                // IDML 폴백: resolved.json textFrames에 없는 렌더러블 TF (rtTf==null) 처리.
                // IDML 스프레드에서 hex domId로 TextFrame을 찾아 ParentStory → IDMLStory 텍스트 추출.
                // 예: "한글 맞춤법의 원리" (domId=3072, 소단원명 데코 TF)
                if (!convertedToText && rtTf == null && !rt.isBadgeGroup()
                        && ctx.idmlDocumentSupplier != null) {
                    try {
                        ctx.ensureIdmlInfra.run();
                        kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument idmlDoc =
                                ctx.idmlDocumentSupplier.get();
                        if (idmlDoc != null) {
                            String hexSelf = "u" + Integer.toHexString(rt.id());
                            String parentStoryId = null;
                            outer3:
                            for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLSpread sp : idmlDoc.spreads()) {
                                for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame itf : sp.textFrames()) {
                                    if (hexSelf.equals(itf.selfId())) {
                                        parentStoryId = itf.parentStoryId();
                                        break outer3;
                                    }
                                }
                            }
                            if (parentStoryId != null) {
                                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory idmlStory =
                                        idmlDoc.getStory(parentStoryId);
                                if (idmlStory != null && !idmlStory.isEmpty()) {
                                    StringBuilder storyTextBuf = new StringBuilder();
                                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph para
                                            : idmlStory.paragraphs()) {
                                        String ptext = para.getPlainText();
                                        if (ptext != null) storyTextBuf.append(ptext);
                                    }
                                    String storyText = storyTextBuf.toString().trim();
                                    if (!storyText.isEmpty() && !storyText.contains("￼")
                                            && storyText.length() <= 20) {
                                        double tx3 = bounds[1];
                                        double ty3 = bounds[0];
                                        double tw3 = Math.abs(bounds[3] - bounds[1]);
                                        double th3 = Math.abs(bounds[2] - bounds[0]);
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock block3 =
                                                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock();
                                        block3.sourceId("renderable_text_idml_" + rt.id());
                                        block3.x(CoordinateConverter.pointsToHwpunits(tx3));
                                        block3.y(CoordinateConverter.pointsToHwpunits(ty3));
                                        block3.width(CoordinateConverter.pointsToHwpunits(tw3));
                                        block3.height(CoordinateConverter.pointsToHwpunits(th3));
                                        int hwpxZ4 = rt.zOrder() > 0 ? Math.max(10000 - rt.zOrder(), 10) : 10;
                                        block3.zOrder(hwpxZ4);
                                        block3.verticalJustification("CENTER_ALIGN");
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph paraT4 =
                                                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph();
                                        paraT4.alignment("CENTER_ALIGN");
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun trRun4 =
                                                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun();
                                        trRun4.text(storyText);
                                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph para
                                                : idmlStory.paragraphs()) {
                                            if (!para.characterRuns().isEmpty()) {
                                                kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun cr =
                                                        para.characterRuns().get(0);
                                                if (cr.fontFamily() != null) trRun4.fontFamily(cr.fontFamily());
                                                if (cr.fontSize() != null && cr.fontSize() > 0)
                                                    trRun4.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(cr.fontSize()));
                                                if (cr.fontStyle() != null) trRun4.fontStyle(cr.fontStyle());
                                                if (cr.fillColor() != null) {
                                                    String hex5 = ctx.resolvedData.resolveColorHex(cr.fillColor());
                                                    if (hex5 != null) trRun4.textColor(hex5);
                                                }
                                                break;
                                            }
                                        }
                                        paraT4.addItem(trRun4);
                                        block3.addParagraph(paraT4);
                                        sections.get(pageIdx).addBlock(block3);
                                        count++;
                                        convertedToText = true;
                                    }
                                }
                            }
                        }
                    } catch (Exception e3) { /* skip IDML fallback */ }
                }
                if (convertedToText) continue;
                // renderedFloatingItems에 동일 id의 inline_object 항목이 있으면
                // Phase 3이 인라인 PNG로 처리 → floating ASTFigure 중복 금지
                {
                    boolean hasInlineObjectInFloating = false;
                    for (RenderedGroup flt : ctx.resolvedData.allRenderedFloatingItems()) {
                        if (flt.id() == rt.id() && "inline_object".equals(flt.itemType())) {
                            hasInlineObjectInFloating = true;
                            break;
                        }
                    }
                    if (hasInlineObjectInFloating) {
                        continue;
                    }
                }

                ASTFigure fig = new ASTFigure();
                fig.sourceId("renderable_" + rt.id());
                fig.x(CoordinateConverter.pointsToHwpunits(x));
                fig.y(CoordinateConverter.pointsToHwpunits(y));
                fig.width(CoordinateConverter.pointsToHwpunits(bw));
                fig.height(CoordinateConverter.pointsToHwpunits(bh));
                fig.imageData(imageData);
                fig.imageFormat("png");
                fig.pixelWidth(img.getWidth());
                fig.pixelHeight(img.getHeight());
                // InDesign allPageItems: index 0 = 맨 앞, 큰 값 = 뒤.
                // HWPX zOrder: 큰 값 = 앞. → 역매핑.
                // 배지/인라인 앵커 PNG: 편집 가능 TF 앞에 와야 하므로 높은 값 유지.
                // 비-배지/비-인라인 배경 PNG: Phase 7 역매핑값(~9000)이 FramePlacer raw zOrder(100~300)보다
                // 훨씬 커서 편집 가능 TF 앞으로 나오는 문제 방지 → 낮은 고정값(10).
                int indesignIdx = rt.zOrder();
                boolean isInlineAnchorForZ = rtTf != null && rtTf.isInline();
                int hwpxZ;
                if (rt.isBadgeGroup() || isInlineAnchorForZ) {
                    hwpxZ = (indesignIdx > 0) ? Math.max(10000 - indesignIdx, 10) : 10;
                } else {
                    hwpxZ = 10;
                }
                fig.zOrder(hwpxZ);

                // 비-인라인 badge_group: child TF 텍스트 → 독립 플로팅 ASTTextFrameBlock으로 오버레이
                // hp:container 방식 대신 PAPER 절대좌표 텍스트박스(투명 배경)를 배지 PNG 위에 얹는다.
                if (rt.isBadgeGroup() && rt.childTextFrameIds() != null
                        && rt.childTextFrameIds().length > 0) {
                    double pgTop = 0, pgLeft = 0;
                    if (ctx.resolvedData.pages() != null
                            && rt.pageIndex() >= 0
                            && rt.pageIndex() < ctx.resolvedData.pages().size()) {
                        double[] pgBB = ctx.resolvedData.pages().get(rt.pageIndex()).bounds();
                        if (pgBB != null && pgBB.length >= 4) {
                            pgTop = pgBB[0]; pgLeft = pgBB[1];
                        }
                    }
                    for (int childTfId : rt.childTextFrameIds()) {
                        // editableTextFrame은 Phase 2가 이미 TextFrameBlock으로 배치 → 중복 방지
                        if (ctx.resolvedData.isEditableTextFrame(String.valueOf(childTfId))) continue;
                        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem cpi =
                                ctx.resolvedData.getPageItem(String.valueOf(childTfId));
                        if (cpi == null) continue;
                        double[] cgb = cpi.geometricBounds(); // spread-relative pt (post-normalize)
                        if (cgb == null || cgb.length < 4) continue;
                        // child TF를 page-relative 절대 좌표로 변환
                        double txtX = cgb[1] - pgLeft;
                        double txtY = cgb[0] - pgTop;
                        double txtW = Math.abs(cgb[3] - cgb[1]);
                        double txtH = Math.abs(cgb[2] - cgb[0]);
                        if (txtW <= 0 || txtH <= 0) continue;
                        // child TF story에서 텍스트/폰트 정보 추출
                        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame cTf =
                                ctx.resolvedData.getTextFrame(String.valueOf(childTfId));
                        if (cTf == null || cTf.storyId() == null) continue;
                        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory cStory =
                                ctx.resolvedData.getStory(cTf.storyId());
                        if (cStory == null || cStory.paragraphs() == null
                                || cStory.paragraphs().isEmpty()) continue;
                        StringBuilder sbT = new StringBuilder();
                        String ff = null; int fsHwp = 0; String tc = null;
                        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph cp
                                : cStory.paragraphs()) {
                            if (cp.runs() == null) continue;
                            for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun cr
                                    : cp.runs()) {
                                if (cr.text() != null) sbT.append(cr.text());
                                if (ff == null && cr.fontFamily() != null) ff = cr.fontFamily();
                                if (fsHwp == 0 && cr.fontSize() != null && cr.fontSize() > 0)
                                    fsHwp = (int) CoordinateConverter.pointsToHwpunits(cr.fontSize());
                                if (tc == null && cr.fillColor() != null) {
                                    String h = ctx.resolvedData.resolveColorHex(cr.fillColor());
                                    if (h != null) tc = h;
                                }
                            }
                        }
                        String oText = sbT.toString().replace("\r","").replace("\n","").trim();
                        if (oText.isEmpty()) continue;
                        // 독립 플로팅 텍스트박스: 투명 배경, CENTER 세로 정렬, 배지 PNG보다 높은 z-order
                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock overlay =
                                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock();
                        overlay.sourceId("badge_text_" + childTfId);
                        overlay.x(CoordinateConverter.pointsToHwpunits(txtX));
                        overlay.y(CoordinateConverter.pointsToHwpunits(txtY));
                        overlay.width(Math.max(100L, CoordinateConverter.pointsToHwpunits(txtW)));
                        overlay.height(Math.max(100L, CoordinateConverter.pointsToHwpunits(txtH)));
                        overlay.zOrder(hwpxZ + 1);
                        overlay.verticalJustification("CENTER_ALIGN");
                        // inlineToFloating=true → HwpxTextBoxBuilder가 투명 DrawText 경로(hp:tbl 흰 배경 회피) 사용
                        overlay.inlineToFloating(true);
                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph overlayPara =
                                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph();
                        overlayPara.alignment("CENTER_ALIGN");
                        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun overlayRun =
                                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun();
                        overlayRun.text(oText);
                        overlayRun.fontFamily(ff);
                        overlayRun.fontSizeHwpunits(fsHwp > 0 ? fsHwp : null);
                        overlayRun.textColor(tc);
                        overlayPara.addItem(overlayRun);
                        overlay.addParagraph(overlayPara);
                        sections.get(pageIdx).addBlock(overlay);
                    }
                }

                fig.fromGroup(true); // IN_FRONT_OF_TEXT
                sections.get(pageIdx).addBlock(fig);
                count++;
            } catch (Exception e) { /* skip */ }
        }
        // Phase 7b: inline_object items that were suppressed from inline placement (Phase 3)
        // and need to be re-placed as floating ASTFigures behind their inlineToFloating TF.
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!"inline_object".equals(rg.itemType())) continue;
            if (!ctx.isDisposed(rg.id(), FrameDisposition.PNG_CONVERT_TO_FLOATING)) continue;
            if (rg.file() == null) continue;
            String dedupKey2 = rg.pageIndex() + "|" + rg.file();
            if (!placedKeys.add(dedupKey2)) continue;

            File pngFile2 = new File(ctx.basePath, rg.file());
            if (!pngFile2.exists()) continue;

            // TF 의 pageIndex 를 우선 사용 (rg.pageIndex()는 앵커 텍스트 기준으로 TF 섹션과 다를 수 있음)
            Integer tfPi = ctx.inlineObjectTfPageIndex.get(rg.id());
            int pageIdx2 = ctx.toSectionIndex.applyAsInt(tfPi != null ? tfPi : rg.pageIndex());
            if (pageIdx2 < 0 || pageIdx2 >= sections.size()) continue;

            try {
                byte[] imageData2 = java.nio.file.Files.readAllBytes(pngFile2.toPath());
                java.awt.image.BufferedImage img2 = javax.imageio.ImageIO.read(pngFile2);
                if (img2 == null || img2.getWidth() <= 2) continue;

                double[] bounds2 = rg.bounds();
                if (bounds2 == null || bounds2.length < 4) continue;
                // renderedFloatingItems bounds는 normalizeToPoints() 미적용 (mm단위, spread 절대좌표)
                // → TF 의 page offset(page bounds top/left)을 빼서 page-relative 로 변환 후 scaleFactor로 pt 환산
                double sf2 = ctx.scaleFactor;
                double pageTop2 = 0, pageLeft2 = 0;
                int boundsPageIdx = tfPi != null ? tfPi : rg.pageIndex();
                if (ctx.resolvedData.pages() != null
                        && boundsPageIdx >= 0 && boundsPageIdx < ctx.resolvedData.pages().size()) {
                    double[] pgB2 = ctx.resolvedData.pages().get(boundsPageIdx).bounds();
                    if (pgB2 != null && pgB2.length >= 4) {
                        pageTop2 = pgB2[0];
                        pageLeft2 = pgB2[1];
                    }
                }
                // bounds2는 mm (spread 절대), pages()는 pt (normalizeToPoints 적용됨)
                // → bounds2를 pt로 변환 후 page offset(pt) 차감하여 page-relative pt 좌표로
                double bw2 = Math.abs(bounds2[3] - bounds2[1]) * sf2;
                double bh2 = Math.abs(bounds2[2] - bounds2[0]) * sf2;
                if (bw2 <= 0 || bh2 <= 0) continue;
                double x2 = bounds2[1] * sf2 - pageLeft2;
                double y2 = bounds2[0] * sf2 - pageTop2;

                ASTFigure fig2 = new ASTFigure();
                fig2.sourceId("inline_float_" + rg.id());
                fig2.x(CoordinateConverter.pointsToHwpunits(x2));
                fig2.y(CoordinateConverter.pointsToHwpunits(y2));
                fig2.width(CoordinateConverter.pointsToHwpunits(bw2));
                fig2.height(CoordinateConverter.pointsToHwpunits(bh2));
                fig2.imageData(imageData2);
                fig2.imageFormat("png");
                fig2.pixelWidth(img2.getWidth());
                fig2.pixelHeight(img2.getHeight());
                // inline_object는 inlineToFloating TF 의 배경 컨테이너 → TF 보다 뒤에 놓여야 함.
                // InDesign z-order 는 inline anchor 기준이라 변환 불가 → 낮은 고정값 사용.
                fig2.zOrder(10);
                fig2.fromGroup(true);
                sections.get(pageIdx2).addBlock(fig2);
                count++;
            } catch (Exception e2) { /* skip */ }
        }

        // Phase 7c: page_object 항목 (스프레드 걸침 배경/장식 PNG) 배치
        // 주 페이지 가시 영역 + 좌/우 오버플로우를 인접 페이지에 크롭해서 배치
        for (RenderedGroup rg3 : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!"page_object".equals(rg3.itemType())) {
                continue;
            }
            if (rg3.file() == null) continue;
            // Phase 6(BackgroundInjector)이 이미 배치한 항목은 중복 배치 방지
            if (ctx.phase6PlacedIds.contains(rg3.id())) continue;
            String dedupKey3 = rg3.pageIndex() + "|" + rg3.file();
            if (!placedKeys.add(dedupKey3)) continue;

            File pngFile3 = new File(ctx.basePath, rg3.file());
            if (!pngFile3.exists()) continue;

            int secIdx3 = ctx.toSectionIndex.applyAsInt(rg3.pageIndex());
            if (secIdx3 < 0 || secIdx3 >= sections.size()) continue;

            try {
                double[] bounds3 = rg3.bounds();
                if (bounds3 == null || bounds3.length < 4) continue;

                // renderedFloatingItems(page_object) bounds: page-relative mm → sf 곱해 page-relative pt
                double sf3 = ctx.scaleFactor;
                double pageWidthPt3 = 1e9, pageHeightPt3 = 1e9;
                double currPageLeftSpread3 = 0;
                if (ctx.resolvedData.pages() != null
                        && rg3.pageIndex() >= 0
                        && rg3.pageIndex() < ctx.resolvedData.pages().size()) {
                    double[] pgB3 = ctx.resolvedData.pages().get(rg3.pageIndex()).bounds();
                    if (pgB3 != null && pgB3.length >= 4) {
                        pageWidthPt3      = pgB3[3] - pgB3[1];
                        pageHeightPt3     = pgB3[2] - pgB3[0];
                        currPageLeftSpread3 = pgB3[1];
                    }
                }
                double rawLeft3   = bounds3[1] * sf3;
                double rawTop3    = bounds3[0] * sf3;
                double rawRight3  = bounds3[3] * sf3;
                double rawBottom3 = bounds3[2] * sf3;
                double fullW3 = rawRight3 - rawLeft3;
                double fullH3 = rawBottom3 - rawTop3;
                if (fullW3 <= 0 || fullH3 <= 0) continue;

                java.awt.image.BufferedImage origImg3 = javax.imageio.ImageIO.read(pngFile3);
                if (origImg3 == null || origImg3.getWidth() <= 2) continue;


                // 주 페이지 가시 영역 크롭
                double visLeft3   = Math.max(0.0, rawLeft3);
                double visTop3    = Math.max(0.0, rawTop3);
                double visRight3  = Math.min(rawRight3, pageWidthPt3);
                double visBottom3 = Math.min(rawBottom3, pageHeightPt3);

                if (visLeft3 < visRight3 && visTop3 < visBottom3) {
                    int pxX3 = (int) Math.round((visLeft3 - rawLeft3) / fullW3 * origImg3.getWidth());
                    int pxY3 = (int) Math.round((visTop3 - rawTop3) / fullH3 * origImg3.getHeight());
                    int pxW3 = (int) Math.round((visRight3 - rawLeft3) / fullW3 * origImg3.getWidth()) - pxX3;
                    int pxH3 = (int) Math.round((visBottom3 - rawTop3) / fullH3 * origImg3.getHeight()) - pxY3;
                    pxX3 = Math.max(0, Math.min(pxX3, origImg3.getWidth()  - 1));
                    pxY3 = Math.max(0, Math.min(pxY3, origImg3.getHeight() - 1));
                    pxW3 = Math.max(1, Math.min(origImg3.getWidth()  - pxX3, pxW3));
                    pxH3 = Math.max(1, Math.min(origImg3.getHeight() - pxY3, pxH3));
                    java.awt.image.BufferedImage img3 = origImg3.getSubimage(pxX3, pxY3, pxW3, pxH3);
                    java.io.ByteArrayOutputStream baos3 = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(img3, "png", baos3);
                    ASTFigure fig3 = new ASTFigure();
                    fig3.sourceId("page_object_" + rg3.id());
                    fig3.x(CoordinateConverter.pointsToHwpunits(visLeft3));
                    fig3.y(CoordinateConverter.pointsToHwpunits(visTop3));
                    fig3.width(CoordinateConverter.pointsToHwpunits(visRight3 - visLeft3));
                    fig3.height(CoordinateConverter.pointsToHwpunits(visBottom3 - visTop3));
                    fig3.imageData(baos3.toByteArray());
                    fig3.imageFormat("png");
                    fig3.pixelWidth(img3.getWidth());
                    fig3.pixelHeight(img3.getHeight());
                    fig3.zOrder(rg3.zOrder() > 0 ? rg3.zOrder() : 5);
                    fig3.fromGroup(true);
                    sections.get(secIdx3).addBlock(fig3);
                    count++;
                }

                // 우측 오버플로우: 다음 페이지(pageIndex+1)에 넘친 부분 배치
                if (rawRight3 > pageWidthPt3 + 1.0) {
                    int nextPi = rg3.pageIndex() + 1;
                    int nextSec = ctx.toSectionIndex.applyAsInt(nextPi);
                    if (nextSec >= 0 && nextSec < sections.size()
                            && !placedKeys.contains(nextPi + "|" + rg3.file())) {
                        double nextPageW = 1e9, nextPageH = 1e9;
                        if (ctx.resolvedData.pages() != null && nextPi < ctx.resolvedData.pages().size()) {
                            double[] npB = ctx.resolvedData.pages().get(nextPi).bounds();
                            if (npB != null && npB.length >= 4) {
                                nextPageW = npB[3] - npB[1];
                                nextPageH = npB[2] - npB[0];
                            }
                        }
                        double ovLeft  = 0.0;
                        double ovRight = Math.min(rawRight3 - pageWidthPt3, nextPageW);
                        double ovTop   = Math.max(0.0, rawTop3);
                        double ovBot   = Math.min(rawBottom3, nextPageH);
                        if (ovLeft < ovRight && ovTop < ovBot) {
                            int pxXov = (int) Math.round((pageWidthPt3 - rawLeft3) / fullW3 * origImg3.getWidth());
                            int pxYov = (int) Math.round((ovTop - rawTop3) / fullH3 * origImg3.getHeight());
                            int pxWov = (int) Math.round((rawRight3 - rawLeft3) / fullW3 * origImg3.getWidth()) - pxXov;
                            int pxHov = (int) Math.round((ovBot - rawTop3) / fullH3 * origImg3.getHeight()) - pxYov;
                            pxXov = Math.max(0, Math.min(pxXov, origImg3.getWidth()  - 1));
                            pxYov = Math.max(0, Math.min(pxYov, origImg3.getHeight() - 1));
                            pxWov = Math.max(1, Math.min(origImg3.getWidth()  - pxXov, pxWov));
                            pxHov = Math.max(1, Math.min(origImg3.getHeight() - pxYov, pxHov));
                            try {
                                java.awt.image.BufferedImage ovImg = origImg3.getSubimage(pxXov, pxYov, pxWov, pxHov);
                                java.io.ByteArrayOutputStream baosOv = new java.io.ByteArrayOutputStream();
                                javax.imageio.ImageIO.write(ovImg, "png", baosOv);
                                ASTFigure figOv = new ASTFigure();
                                figOv.sourceId("page_object_ov_" + rg3.id());
                                figOv.x(CoordinateConverter.pointsToHwpunits(ovLeft));
                                figOv.y(CoordinateConverter.pointsToHwpunits(ovTop));
                                figOv.width(CoordinateConverter.pointsToHwpunits(ovRight - ovLeft));
                                figOv.height(CoordinateConverter.pointsToHwpunits(ovBot - ovTop));
                                figOv.imageData(baosOv.toByteArray());
                                figOv.imageFormat("png");
                                figOv.pixelWidth(ovImg.getWidth());
                                figOv.pixelHeight(ovImg.getHeight());
                                figOv.zOrder(1); // overflow 배경은 항상 최하단
                                figOv.fromGroup(true);
                                sections.get(nextSec).addBlock(figOv);
                                count++;
                                placedKeys.add(nextPi + "|" + rg3.file());
                            } catch (Exception ovEx) { /* skip */ }
                        }
                    }
                }

                // 좌측 오버플로우: 이전 페이지(pageIndex-1)에 넘친 부분 배치
                if (rawLeft3 < -1.0 && rg3.pageIndex() > 0) {
                    int prevPi = rg3.pageIndex() - 1;
                    int prevSec = ctx.toSectionIndex.applyAsInt(prevPi);
                    if (prevSec >= 0 && prevSec < sections.size()
                            && !placedKeys.contains(prevPi + "|" + rg3.file())) {
                        double prevPageLeftSpread = 0;
                        double prevPageW = 1e9, prevPageH = 1e9;
                        if (ctx.resolvedData.pages() != null && prevPi < ctx.resolvedData.pages().size()) {
                            double[] ppB = ctx.resolvedData.pages().get(prevPi).bounds();
                            if (ppB != null && ppB.length >= 4) {
                                prevPageLeftSpread = ppB[1];
                                prevPageW = ppB[3] - ppB[1];
                                prevPageH = ppB[2] - ppB[0];
                            }
                        }
                        // item의 스프레드 기준 절대 left, 이전 페이지 기준 상대 x
                        double itemSpreadLeft = currPageLeftSpread3 + rawLeft3;
                        double ovXonPrev = itemSpreadLeft - prevPageLeftSpread;
                        double ovLeft2  = Math.max(0.0, ovXonPrev);
                        double ovRight2 = Math.min(prevPageW, ovXonPrev + (-rawLeft3));
                        double ovTop2   = Math.max(0.0, rawTop3);
                        double ovBot2   = Math.min(rawBottom3, prevPageH);
                        if (ovLeft2 < ovRight2 && ovTop2 < ovBot2) {
                            // PNG 크롭: item 내 x = 0 ~ (-rawLeft3) 범위 (item 왼쪽 끝부터 주 페이지 시작까지)
                            double itemRelStart = Math.max(0.0, ovLeft2 - ovXonPrev);
                            int pxXleft = (int) Math.round(itemRelStart / fullW3 * origImg3.getWidth());
                            int pxYleft = (int) Math.round((ovTop2 - rawTop3) / fullH3 * origImg3.getHeight());
                            int pxWleft = (int) Math.round((-rawLeft3 - itemRelStart) / fullW3 * origImg3.getWidth());
                            int pxHleft = (int) Math.round((ovBot2 - rawTop3) / fullH3 * origImg3.getHeight()) - pxYleft;
                            pxXleft = Math.max(0, Math.min(pxXleft, origImg3.getWidth()  - 1));
                            pxYleft = Math.max(0, Math.min(pxYleft, origImg3.getHeight() - 1));
                            pxWleft = Math.max(1, Math.min(origImg3.getWidth()  - pxXleft, pxWleft));
                            pxHleft = Math.max(1, Math.min(origImg3.getHeight() - pxYleft, pxHleft));
                            try {
                                java.awt.image.BufferedImage leftImg = origImg3.getSubimage(pxXleft, pxYleft, pxWleft, pxHleft);
                                java.io.ByteArrayOutputStream baosLeft = new java.io.ByteArrayOutputStream();
                                javax.imageio.ImageIO.write(leftImg, "png", baosLeft);
                                ASTFigure figLeft = new ASTFigure();
                                figLeft.sourceId("page_object_lv_" + rg3.id());
                                figLeft.x(CoordinateConverter.pointsToHwpunits(ovLeft2));
                                figLeft.y(CoordinateConverter.pointsToHwpunits(ovTop2));
                                figLeft.width(CoordinateConverter.pointsToHwpunits(ovRight2 - ovLeft2));
                                figLeft.height(CoordinateConverter.pointsToHwpunits(ovBot2 - ovTop2));
                                figLeft.imageData(baosLeft.toByteArray());
                                figLeft.imageFormat("png");
                                figLeft.pixelWidth(leftImg.getWidth());
                                figLeft.pixelHeight(leftImg.getHeight());
                                figLeft.zOrder(rg3.zOrder() > 0 ? rg3.zOrder() : 5);
                                figLeft.fromGroup(true);
                                sections.get(prevSec).addBlock(figLeft);
                                count++;
                                placedKeys.add(prevPi + "|" + rg3.file());
                                if (rg3.file().contains("deco_3317"))
                                    System.err.println("[Phase7c] deco_3317 left-ov placed on sec=" + prevSec + " x=" + ovLeft2 + " w=" + (ovRight2-ovLeft2) + " pxW=" + leftImg.getWidth());
                            } catch (Exception lvEx) {
                                if (rg3.file().contains("deco_3317"))
                                    System.err.println("[Phase7c] deco_3317 left-ov EXCEPTION: " + lvEx);
                            }
                        }
                    }
                }
            } catch (Exception e3) {
                if (rg3.file() != null && rg3.file().contains("deco_3317"))
                    System.err.println("[Phase7c] deco_3317 OUTER EXCEPTION: " + e3);
            }
        }

        if (count > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 7: " + count + " renderable frames placed");
        }
    }

    /**
     * Phase 3 실행 전 호출: inline anchor이면서 짧은 단문(≤3자, ￼ 없음)인 renderable 항목을
     * PNG_CONVERT_TO_FLOATING disposition으로 미리 등록한다.
     * Phase 7이 이 항목들을 floating TextFrameBlock으로 배치하므로,
     * Phase 3의 loadInlineObject가 이를 건너뛰어 중복 렌더링을 방지한다.
     */
    /**
     * renderable TF의 짧은 가시 텍스트를 ASTTextFrameBlock으로 변환하는 공통 헬퍼.
     * 인라인(≤3자)과 비-인라인 플로팅(≤20자) 경로의 중복 50행을 통합.
     *
     * @param sourceIdPrefix  "renderable_text_" 또는 "renderable_text_float_"
     * @param rtTf            resolved TextFrame (storyId 조회 소스)
     * @param bounds          [top, left, bottom, right] pt 단위 page-relative bounds
     * @param cleaned         공백/제어문자 제거된 가시 텍스트
     * @param zOrder          InDesign zOrder (hwpxZ = max(10000-zOrder, 10))
     */
    private static kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock buildShortTextBlock(
            ResolvedBuildContext ctx,
            String sourceIdPrefix, int rtId,
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame rtTf,
            double[] bounds, String cleaned, int zOrder) {
        double tx = bounds[1], ty = bounds[0];
        double tw = Math.abs(bounds[3] - bounds[1]);
        double th = Math.abs(bounds[2] - bounds[0]);
        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock block =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock();
        block.sourceId(sourceIdPrefix + rtId);
        block.x(CoordinateConverter.pointsToHwpunits(tx));
        block.y(CoordinateConverter.pointsToHwpunits(ty));
        block.width(CoordinateConverter.pointsToHwpunits(tw));
        block.height(CoordinateConverter.pointsToHwpunits(th));
        block.zOrder(zOrder > 0 ? Math.max(10000 - zOrder, 10) : 10);
        block.verticalJustification("CENTER_ALIGN");
        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph paraT =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph();
        paraT.alignment("CENTER_ALIGN");
        kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun trRun =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun();
        trRun.text(cleaned);
        kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory rs =
                (rtTf != null && rtTf.storyId() != null)
                        ? ctx.resolvedData.getStory(rtTf.storyId()) : null;
        if (rs != null && !rs.paragraphs().isEmpty()
                && rs.paragraphs().get(0).runs() != null
                && !rs.paragraphs().get(0).runs().isEmpty()) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun rr =
                    rs.paragraphs().get(0).runs().get(0);
            if (rr.fontFamily() != null) trRun.fontFamily(rr.fontFamily());
            if (rr.fontSize() != null && rr.fontSize() > 0) {
                trRun.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr.fontSize()));
            }
            if (rr.fontStyle() != null) trRun.fontStyle(rr.fontStyle());
            if (rr.fillColor() != null) {
                String hex = ctx.resolvedData.resolveColorHex(rr.fillColor());
                if (hex != null) trRun.textColor(hex);
            }
        }
        paraT.addItem(trRun);
        block.addParagraph(paraT);
        return block;
    }

    public static void preRegisterInlineShortTextItems(ResolvedBuildContext ctx) {
        if (ctx.resolvedData == null) return;
        for (RenderedGroup rt : ctx.resolvedData.allRenderedTextFrames()) {
            if (rt.isBadgeGroup()) continue;
            if ("inline_object".equals(rt.type())) continue; // Phase 7 floating 건너뜀 — Phase 3가 처리
            if (rt.itemType() == null) continue; // null-type inline TF는 Phase 3가 ASTTextRun으로 처리
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame rtTf =
                    ctx.resolvedData.getTextFrame(String.valueOf(rt.id()));
            if (rtTf == null || !rtTf.isInline()) continue;
            String visText = rtTf.frameVisibleText();
            if (visText == null) continue;
            String cleaned = visText.replace("￼", "").replace("\r", "").replace("\n", "").trim();
            if (!cleaned.isEmpty() && cleaned.length() <= 3) {
                ctx.setDisposition(rt.id(), FrameDisposition.PNG_CONVERT_TO_FLOATING);
            }
        }
    }
}
