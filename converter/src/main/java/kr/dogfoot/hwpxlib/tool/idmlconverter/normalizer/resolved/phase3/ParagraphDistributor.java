package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextFrameRangeOwnershipPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow.TextFlowDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 단락 분배 — 글상자별 단락 할당 (W3 Step D).
 * composedLines의 문자 범위 또는 텍스트 길이 기반으로
 * 연결 글상자 체인에 단락을 배분.
 * StoryConverter에서 분리됨.
 */
class ParagraphDistributor {

    private ParagraphDistributor() {}

    static void distributeParagraphs(ResolvedBuildContext ctx, List<ASTParagraph> paragraphs,
                                       List<ASTTextFrameBlock> blocks, String storyId) {
        if (assignExplicitParagraphRanges(paragraphs, blocks)) {
            return;
        }
        // 단일 프레임: frameVisibleText와 Story 텍스트 길이 비교
        if (blocks.size() == 1) {
            ASTTextFrameBlock block = blocks.get(0);
            // Story 텍스트가 길고 frameVisibleText가 거의 비어있으면 할당하지 않음
            // (다른 페이지의 프레임에서 실제로 표시되는 텍스트가 이 프레임에 잘못 할당되는 것 방지)
            int storyLen = 0;
            for (ASTParagraph p : paragraphs) {
                String pt = ParagraphTextHelpers.getParaPlainText(p);
                if (pt != null) storyLen += pt.length();
            }
            int visLen = block.frameVisibleTextLength();
            if (storyLen > 20 && visLen <= 1) {
                // Story가 20자 이상인데 프레임에 보이는 텍스트가 0~1자 → 오버플로우/미표시 프레임
                return;
            }
            for (ASTParagraph p : paragraphs) {
                block.addParagraph(p);
            }
            return;
        }

        // 다중 프레임: frameVisibleText 기반 분배
        List<ASTTextFrameBlock> ordered = InlineFrameHandler.orderByThreadChain(ctx, blocks);
        repairInlineOnlyTextFlowParagraphs(ctx, storyId, paragraphs);

        // 전체 IDML 단락 텍스트를 하나의 연속 문자열로 합침
        StringBuilder storyTextBuilder = new StringBuilder();
        List<int[]> paraRanges = new ArrayList<>(); // [startCharIdx, endCharIdx]
        for (ASTParagraph p : paragraphs) {
            int s = storyTextBuilder.length();
            String pt = ParagraphTextHelpers.getParaPlainText(p);
            storyTextBuilder.append(pt != null ? pt : "");
            paraRanges.add(new int[]{s, storyTextBuilder.length()});
        }
        String storyText = storyTextBuilder.toString();

        TextFrameRangeOwnershipPlanner.FrameRangePlan frameRangePlan =
                TextFrameRangeOwnershipPlanner.plan(ctx, ordered, storyText);

        // 프레임별 단락 할당
        for (int fi = 0; fi < ordered.size(); fi++) {
            ASTTextFrameBlock block = ordered.get(fi);
            int frameStart = frameRangePlan.start(fi);
            int frameEnd = frameRangePlan.end(fi);
            String sid3 = block.sourceId();
            String domId3 = kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers.domIdFromSourceId(sid3);
            if (domId3 == null) domId3 = sid3;
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId3);
            java.util.List<String> frameTexts = (rtf != null) ? rtf.frameParaTexts() : null;

            if (frameTexts == null || frameTexts.isEmpty()) {
                // paragraphStart/End 모두 -1 이고 lineCount==0: InDesign에서 내용 없는 continuation 프레임
                // → 단락을 할당하지 않음 (중복 TF 방지)
                if (rtf != null && rtf.paragraphStart() < 0 && rtf.paragraphEnd() < 0 && rtf.lineCount() == 0) {
                    continue;
                }
                int start = (rtf != null) ? Math.max(0, rtf.paragraphStart()) : 0;
                int end = (rtf != null && rtf.paragraphEnd() >= 0) ? rtf.paragraphEnd() : paragraphs.size() - 1;
                for (int i = start; i <= end && i < paragraphs.size(); i++) {
                    block.addParagraph(paragraphs.get(i));
                }
                continue;
            }

            for (int i = 0; i < paragraphs.size(); i++) {
                int paraStart = paraRanges.get(i)[0];
                int paraEnd = paraRanges.get(i)[1];

                if (paraEnd <= frameStart) continue;
                if (paraStart >= frameEnd) break;

                if (paraStart >= frameStart && paraEnd <= frameEnd) {
                    // 단락이 프레임 안에 완전히 포함
                    ASTParagraph paragraph = paragraphs.get(i);
                    ASTTextFrameBlock targetBlock =
                            sourceFrameForInlineOnlyParagraph(ctx, paragraph, ordered, block);
                    targetBlock.addParagraph(paragraph);
                } else if (paraStart < frameEnd && paraEnd > frameEnd) {
                    // 단락이 프레임 경계에 걸침 → 앞부분만 (cutLen 글자)
                    int cutLen = frameEnd - paraStart;
                    String fullText = ParagraphTextHelpers.getParaPlainText(paragraphs.get(i));
                    String cutText = (fullText != null && cutLen < fullText.length()) ? fullText.substring(0, cutLen) : fullText;
                    ASTParagraph trimmed = ParagraphTextHelpers.createSplitParagraph(paragraphs.get(i), cutText);
                    if (trimmed != null) {
                        block.addParagraph(trimmed);
                    }
                } else if (paraStart < frameStart && paraEnd > frameStart) {
                    // 이전 프레임에서 시작된 단락의 나머지
                    int skipLen = frameStart - paraStart;
                    String fullText = ParagraphTextHelpers.getParaPlainText(paragraphs.get(i));
                    String contText = (fullText != null && skipLen < fullText.length()) ? fullText.substring(skipLen) : "";
                    ASTParagraph continuation = ParagraphTextHelpers.createContinuationParagraph(paragraphs.get(i), skipLen, contText);
                    if (continuation != null) {
                        block.addParagraph(continuation);
                    }
                }
            }
        }
    }

    private static boolean assignExplicitParagraphRanges(
            List<ASTParagraph> paragraphs,
            List<ASTTextFrameBlock> blocks) {
        if (paragraphs == null || blocks == null || blocks.isEmpty()) return false;
        boolean anyRange = false;
        for (ASTTextFrameBlock block : blocks) {
            if (block != null && block.hasParagraphRange()) {
                anyRange = true;
            } else {
                return false;
            }
        }
        if (!anyRange) return false;

        for (ASTTextFrameBlock block : blocks) {
            if (block == null || !block.hasParagraphRange()) continue;
            int start = Math.max(0, block.paragraphRangeStart());
            int end = Math.min(paragraphs.size() - 1, block.paragraphRangeEnd());
            for (int i = start; i <= end; i++) {
                block.addParagraph(paragraphs.get(i));
            }
        }
        return true;
    }

    private static ASTTextFrameBlock sourceFrameForInlineOnlyParagraph(
            ResolvedBuildContext ctx,
            ASTParagraph paragraph,
            List<ASTTextFrameBlock> ordered,
            ASTTextFrameBlock fallback) {
        if (ctx == null || ctx.resolvedData == null || paragraph == null
                || ordered == null || ordered.isEmpty()) {
            return fallback;
        }
        ObjectPlan plan = inlineOnlyObjectPlan(ctx, paragraph);
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return fallback;
        double cy = (plan.bounds[0] + plan.bounds[2]) / 2.0;
        double cx = (plan.bounds[1] + plan.bounds[3]) / 2.0;
        ASTTextFrameBlock best = null;
        double bestArea = Double.MAX_VALUE;
        double bestOverlap = 0.0;
        for (ASTTextFrameBlock block : ordered) {
            ResolvedTextFrame tf = resolvedTextFrame(ctx, block);
            if (tf == null) continue;
            if (plan.pageIndex >= 0 && tf.pageIndex() >= 0 && plan.pageIndex != tf.pageIndex()) continue;
            double[] bounds = tf.pageRelativeBounds();
            if (bounds == null || bounds.length < 4) bounds = tf.geometricBounds();
            if (bounds == null || bounds.length < 4) continue;
            double area = Math.max(0.1, Math.abs(bounds[2] - bounds[0]) * Math.abs(bounds[3] - bounds[1]));
            if (containsPoint(bounds, cy, cx)) {
                if (area < bestArea) {
                    best = block;
                    bestArea = area;
                }
                continue;
            }
            double overlap = overlapArea(bounds, plan.bounds);
            if (best == null && overlap > bestOverlap) {
                best = block;
                bestOverlap = overlap;
            }
        }
        return best != null ? best : fallback;
    }

    private static ObjectPlan inlineOnlyObjectPlan(ResolvedBuildContext ctx, ASTParagraph paragraph) {
        if (paragraph.inlineTable() != null || paragraph.items() == null || paragraph.items().isEmpty()) return null;
        if (isSimpleInlineMarkerParagraph(paragraph)) {
            return firstPlannedInlineObjectPlan(ctx, paragraph);
        }
        ObjectPlan plan = null;
        boolean sawInline = false;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null
                        && !text.replace("\uFFFC", "")
                        .replace("\r", "")
                        .replace("\n", "")
                        .trim().isEmpty()) {
                    return null;
                }
                continue;
            }
            if (!(item instanceof ASTInlineObject)) return null;
            ASTInlineObject obj = (ASTInlineObject) item;
            Integer domId = sourceIdToDomId(obj.sourceId());
            if (domId == null) return null;
            ObjectPlan itemPlan = ctx.findOwnershipPlanForDomId(domId);
            if (itemPlan == null || itemPlan.bounds == null || itemPlan.bounds.length < 4) return null;
            if (plan == null) {
                plan = itemPlan;
            } else if (plan.pageIndex != itemPlan.pageIndex) {
                return null;
            }
            sawInline = true;
        }
        return sawInline ? plan : null;
    }

    private static boolean isSimpleInlineMarkerParagraph(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return false;
        boolean hasInline = false;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTInlineObject) {
                hasInline = true;
            }
        }
        if (!hasInline) return false;
        String text = ParagraphTextHelpers.getParaPlainText(paragraph);
        if (text == null) return false;
        String compact = text.replace("\uFFFC", "")
                .replace("\r", "")
                .replace("\n", "")
                .replaceAll("\\s+", "");
        if (compact.isEmpty() || compact.length() > 16) return false;
        return compact.matches("[0-9pP.]+");
    }

    private static ObjectPlan firstPlannedInlineObjectPlan(ResolvedBuildContext ctx, ASTParagraph paragraph) {
        if (ctx == null || paragraph == null || paragraph.items() == null) return null;
        for (ASTInlineItem item : paragraph.items()) {
            if (!(item instanceof ASTInlineObject)) continue;
            ASTInlineObject obj = (ASTInlineObject) item;
            Integer domId = sourceIdToDomId(obj.sourceId());
            if (domId == null) continue;
            ObjectPlan plan = ctx.findOwnershipPlanForDomId(domId);
            if (plan != null && plan.bounds != null && plan.bounds.length >= 4) return plan;
        }
        return null;
    }

    private static ResolvedTextFrame resolvedTextFrame(ResolvedBuildContext ctx, ASTTextFrameBlock block) {
        if (ctx == null || ctx.resolvedData == null || block == null) return null;
        String domId = ParagraphTextHelpers.domIdFromSourceId(block.sourceId());
        if (domId == null) domId = block.sourceId();
        return ctx.resolvedData.getTextFrame(domId);
    }

    private static Integer sourceIdToDomId(String sourceId) {
        String domId = ParagraphTextHelpers.domIdFromSourceId(sourceId);
        if (domId == null || domId.isEmpty()) return null;
        try {
            return Integer.parseInt(domId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean containsPoint(double[] bounds, double y, double x) {
        double tolerance = 0.5;
        return y >= Math.min(bounds[0], bounds[2]) - tolerance
                && y <= Math.max(bounds[0], bounds[2]) + tolerance
                && x >= Math.min(bounds[1], bounds[3]) - tolerance
                && x <= Math.max(bounds[1], bounds[3]) + tolerance;
    }

    private static double overlapArea(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double top = Math.max(Math.min(a[0], a[2]), Math.min(b[0], b[2]));
        double left = Math.max(Math.min(a[1], a[3]), Math.min(b[1], b[3]));
        double bottom = Math.min(Math.max(a[0], a[2]), Math.max(b[0], b[2]));
        double right = Math.min(Math.max(a[1], a[3]), Math.max(b[1], b[3]));
        return Math.max(0.0, bottom - top) * Math.max(0.0, right - left);
    }

    static void repairInlineOnlyTextFlowParagraphs(
            ResolvedBuildContext ctx,
            String storyId,
            List<ASTParagraph> paragraphs) {
        if (ctx == null || ctx.textFlowDocument == null || storyId == null || paragraphs == null) return;
        TextFlowDocument.TextFlowUnit unit = ctx.textFlowDocument.byStoryId(storyId);
        if (unit == null || unit.paragraphs == null || unit.paragraphs.isEmpty()) return;
        List<TextFlowDocument.TextFlowParagraph> inlineOnlyFlowParagraphs =
                inlineOnlyFlowParagraphs(unit);
        int inlineOnlyIndex = 0;
        int count = Math.min(paragraphs.size(), unit.paragraphs.size());
        for (int i = 0; i < paragraphs.size(); i++) {
            ASTParagraph paragraph = paragraphs.get(i);
            if (!isObjectReplacementOnlyParagraph(paragraph)) continue;
            TextFlowDocument.TextFlowParagraph flowParagraph = i < count ? unit.paragraphs.get(i) : null;
            List<ASTInlineItem> repaired = plannedInlineItemsForFlowParagraph(ctx, flowParagraph);
            if ((repaired == null || repaired.isEmpty())
                    && inlineOnlyIndex < inlineOnlyFlowParagraphs.size()) {
                repaired = plannedInlineItemsForFlowParagraph(
                        ctx, inlineOnlyFlowParagraphs.get(inlineOnlyIndex++));
            }
            if (repaired == null || repaired.isEmpty()) continue;
            paragraph.items().clear();
            for (ASTInlineItem item : repaired) {
                if (item == null) continue;
                paragraph.addItem(item);
            }
        }
    }

    private static boolean isObjectReplacementOnlyParagraph(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return false;
        String text = ParagraphTextHelpers.getParaPlainText(paragraph);
        if (text == null || text.isEmpty()) return false;
        String normalized = text
                .replace("\uFFFC", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        return normalized.isEmpty() && text.indexOf('\uFFFC') >= 0;
    }

    private static List<TextFlowDocument.TextFlowParagraph> inlineOnlyFlowParagraphs(
            TextFlowDocument.TextFlowUnit unit) {
        List<TextFlowDocument.TextFlowParagraph> out = new ArrayList<>();
        if (unit == null || unit.paragraphs == null) return out;
        for (TextFlowDocument.TextFlowParagraph paragraph : unit.paragraphs) {
            if (isInlineOnlyFlowParagraph(paragraph)) out.add(paragraph);
        }
        return out;
    }

    private static boolean isInlineOnlyFlowParagraph(TextFlowDocument.TextFlowParagraph paragraph) {
        if (paragraph == null || paragraph.atoms == null || paragraph.atoms.isEmpty()) return false;
        boolean hasInline = false;
        for (TextFlowDocument.TextFlowAtom atom : paragraph.atoms) {
            if (atom instanceof TextFlowDocument.InlineSlotAtom) {
                hasInline = true;
                continue;
            }
            if (atom instanceof TextFlowDocument.TextAtom) {
                String text = ((TextFlowDocument.TextAtom) atom).text;
                if (text != null
                        && !text.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().isEmpty()) {
                    return false;
                }
            }
        }
        return hasInline;
    }

    private static List<ASTInlineItem> plannedInlineItemsForFlowParagraph(
            ResolvedBuildContext ctx,
            TextFlowDocument.TextFlowParagraph flowParagraph) {
        if (flowParagraph == null || flowParagraph.atoms == null) return null;
        List<ASTInlineItem> out = new ArrayList<>();
        boolean sawInlineSlot = false;
        for (TextFlowDocument.TextFlowAtom atom : flowParagraph.atoms) {
            if (atom instanceof TextFlowDocument.TextAtom) {
                String text = ((TextFlowDocument.TextAtom) atom).text;
                if (text != null
                        && !text.replace("\uFFFC", "").replace("\r", "").replace("\n", "").trim().isEmpty()) {
                    return null;
                }
                continue;
            }
            if (!(atom instanceof TextFlowDocument.InlineSlotAtom)) continue;
            TextFlowDocument.InlineSlotAtom slot = (TextFlowDocument.InlineSlotAtom) atom;
            if (slot.anchoredObjectId == null) continue;
            sawInlineSlot = true;
            List<ASTInlineItem> items = InlineFrameHandler.loadPlannedInlineAnchorItems(
                    ctx, slot.anchoredObjectId, null, null);
            if (items != null) out.addAll(items);
        }
        return sawInlineSlot ? out : null;
    }

}
