package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase7;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
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
        for (RenderedGroup rt : ctx.resolvedData.allRenderedTextFrames()) {
            if (rt.file() == null) continue;
            String dedupKey = rt.pageIndex() + "|" + rt.file();
            if (!placedKeys.add(dedupKey)) continue; // 이미 배치된 동일 파일/페이지
            // badge_group은 인라인 앵커(inline_object)로 배치된 경우에만 건너뜀.
            // 인라인 참조가 없는 독립 badge는 여기서 플로팅으로 배치해야 함.
            if (rt.isBadgeGroup()) {
                boolean alsoInline = false;
                for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                    if (rg.id() == rt.id() && "inline_object".equals(rg.itemType())) {
                        alsoInline = true;
                        break;
                    }
                }
                // 배지 자신이 inline_object가 아니더라도 조상 그룹이 inline_object이면
                // 해당 조상 PNG에 시각이 이미 포함됨 → 중복 배치 방지
                if (!alsoInline) {
                    ResolvedPageItem badgeItem = ctx.resolvedData.getPageItem(String.valueOf(rt.id()));
                    if (badgeItem != null) {
                        String ancestorId = badgeItem.parentId();
                        int hops = 0;
                        outer:
                        while (ancestorId != null && hops < 5) {
                            for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                                if (String.valueOf(rg.id()).equals(ancestorId)
                                        && "inline_object".equals(rg.itemType())) {
                                    alsoInline = true;
                                    break outer;
                                }
                            }
                            ResolvedPageItem anc = ctx.resolvedData.getPageItem(ancestorId);
                            if (anc == null) break;
                            ancestorId = anc.parentId();
                            hops++;
                        }
                    }
                }
                if (alsoInline) continue;
                // SPEC-025: 단순 scribble 배지는 editable TextFrame 으로 visual 전체를 흡수 → PNG 건너뜀.
                // 일러스트 배지(예: 선인장 + 작은 라벨)는 PNG 유지하고 라벨에만 흰 텍스트박스를 오버레이.
                boolean anySimpleChild = false;
                if (rt.childTextFrameIds() != null) {
                    for (int cid : rt.childTextFrameIds()) {
                        if (ctx.resolvedData.isSimpleBadgeChild(String.valueOf(cid))) {
                            anySimpleChild = true;
                            break;
                        }
                    }
                }
                if (anySimpleChild) continue;
            }

            File pngFile = new File(ctx.basePath, rt.file());
            if (!pngFile.exists()) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(rt.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            try {
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null || img.getWidth() <= 2) continue;

                double[] bounds = rt.bounds();
                if (bounds == null || bounds.length < 4) continue;

                // bounds는 normalizeToPoints()에서 이미 pt 단위로 변환됨
                double bw = Math.abs(bounds[3] - bounds[1]);
                double bh = Math.abs(bounds[2] - bounds[0]);
                if (bw <= 0 || bh <= 0) continue;

                // PNG 비율 보정 — 보정 후 원본 bounds 의 중심을 유지하도록 x/y 재계산
                double bwOrig = bw, bhOrig = bh;
                double pngRatio = (double) img.getWidth() / img.getHeight();
                double boundsRatio = bw / bh;
                if (Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
                    if (pngRatio < 1.0) { bw = bh * pngRatio; } else { bh = bw / pngRatio; }
                }

                // 원본 중심을 유지하면서 좌상단(x,y) 재산출
                double centerX = bounds[1] + bwOrig / 2.0;
                double centerY = bounds[0] + bhOrig / 2.0;
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
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame otf : ctx.resolvedData.textFrames()) {
                        if (otf == null) continue;
                        if (otf.pageIndex() != rt.pageIndex()) continue;
                        if (!ctx.resolvedData.isEditableTextFrame(otf.id())) continue;
                        if (otf.composedLines() == null || otf.composedLines().isEmpty()) continue;
                        double[] cl0 = otf.composedLines().get(0).bounds();
                        if (cl0 == null || cl0.length < 4) continue;
                        double clTop = cl0[0] - rtPageTop;
                        double clLeft = cl0[1] - rtPageLeft;
                        double clBottom = cl0[2] - rtPageTop;
                        double clRight = cl0[3] - rtPageLeft;
                        boolean overlap = pngLeft < clRight && pngRight > clLeft
                                && pngTop < clBottom && pngBottom > clTop;
                        if (!overlap) continue;
                        double gap = 2.0;
                        double newRight = clLeft - gap;
                        double shiftAmount = pngRight - newRight;
                        if (shiftAmount > 0) {
                            x -= shiftAmount;
                        }
                        break;
                    }
                }

                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame rtTf
                        = ctx.resolvedData.getTextFrame(String.valueOf(rt.id()));
                // null-type inline TF: Phase 3 가 inline 앵커에서 PNG로 직접 임베드 →
                // 여기서 floating 배치하면 중복. Phase 3 에 위임.
                if (rtTf != null && rtTf.isInline() && rt.itemType() == null) continue;
                // SPEC-025: renderable inline TF 가 짧은 단일 텍스트 (≤3자) 면 PNG 대신
                // TextFrameBlock 으로 변환 → 텍스트로 검색 가능 + 폰트 매핑/스케일 자유.
                // (예: 페이지 32 "1" 큰 번호 라벨)
                boolean convertedToText = false;
                if (rtTf != null && rtTf.isInline() && !rt.isBadgeGroup()) {
                    String visText = rtTf.frameVisibleText();
                    if (visText != null) {
                        String cleaned = visText.replace("￼", "").replace("\r", "").replace("\n", "").trim();
                        if (!cleaned.isEmpty() && cleaned.length() <= 3) {
                            // 짧은 텍스트 → TextFrameBlock 으로 변환
                            // 텍스트 블록은 PNG 비율 보정 / overlap shift 가 불필요 → 원본 bounds 사용
                            double tx = bounds[1];
                            double ty = bounds[0];
                            double tw = Math.abs(bounds[3] - bounds[1]);
                            double th = Math.abs(bounds[2] - bounds[0]);
                            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock block =
                                    new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock();
                            block.sourceId("renderable_text_" + rt.id());
                            block.x(CoordinateConverter.pointsToHwpunits(tx));
                            block.y(CoordinateConverter.pointsToHwpunits(ty));
                            block.width(CoordinateConverter.pointsToHwpunits(tw));
                            block.height(CoordinateConverter.pointsToHwpunits(th));
                            int idesignIdx2 = rt.zOrder();
                            int hwpxZ2 = (idesignIdx2 > 0) ? Math.max(10000 - idesignIdx2, 10) : 10;
                            block.zOrder(hwpxZ2);
                            block.verticalJustification("CENTER_ALIGN");
                            // 텍스트 런 생성 (resolved story 에서 폰트/사이즈 가져오기)
                            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph paraT =
                                    new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph();
                            paraT.alignment("CENTER_ALIGN");
                            kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun trRun =
                                    new kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun();
                            trRun.text(cleaned);
                            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory rs2 =
                                    rtTf.storyId() != null ? ctx.resolvedData.getStory(rtTf.storyId()) : null;
                            if (rs2 != null && !rs2.paragraphs().isEmpty()
                                    && rs2.paragraphs().get(0).runs() != null
                                    && !rs2.paragraphs().get(0).runs().isEmpty()) {
                                kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun rr2 =
                                        rs2.paragraphs().get(0).runs().get(0);
                                if (rr2.fontFamily() != null) trRun.fontFamily(rr2.fontFamily());
                                if (rr2.fontSize() != null && rr2.fontSize() > 0) {
                                    trRun.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(rr2.fontSize()));
                                }
                                if (rr2.fontStyle() != null) trRun.fontStyle(rr2.fontStyle());
                                if (rr2.fillColor() != null) {
                                    String hex = ctx.resolvedData.resolveColorHex(rr2.fillColor());
                                    if (hex != null) trRun.textColor(hex);
                                }
                            }
                            paraT.addItem(trRun);
                            block.addParagraph(paraT);
                            sections.get(pageIdx).addBlock(block);
                            count++;
                            convertedToText = true;
                        }
                    }
                }
                if (convertedToText) continue;

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
                // HWPX zOrder: 큰 값 = 앞. → 역매핑하여 겹침 순서 보존.
                int indesignIdx = rt.zOrder();
                int hwpxZ = (indesignIdx > 0) ? Math.max(10000 - indesignIdx, 10) : 10;
                fig.zOrder(hwpxZ);
                fig.fromGroup(true); // IN_FRONT_OF_TEXT
                sections.get(pageIdx).addBlock(fig);
                count++;
            } catch (Exception e) { /* skip */ }
        }
        // Phase 7b: inline_object items that were suppressed from inline placement (Phase 3)
        // and need to be re-placed as floating ASTFigures behind their inlineToFloating TF.
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!"inline_object".equals(rg.itemType())) continue;
            if (!ctx.inlineObjectsToConvertToFloating.contains(rg.id())) continue;
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

        if (count > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 7: " + count + " renderable frames placed");
        }
    }
}
