package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
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

    /**
     * composedLines 문자 범위 기반 단락 분배.
     * 각 블록의 composedCharStart~composedCharEnd 범위에 해당하는 단락을 할당.
     */
    private static void distributeByComposedCharRange(ResolvedBuildContext ctx, List<ASTParagraph> paragraphs,
                                                List<ASTTextFrameBlock> blocks) {
        // 전체 단락 텍스트를 연속 문자열로 합침
        StringBuilder sb = new StringBuilder();
        List<int[]> paraRanges = new ArrayList<>();
        for (ASTParagraph p : paragraphs) {
            int s = sb.length();
            String pt = ParagraphTextHelpers.getParaPlainText(p);
            sb.append(pt != null ? pt : "");
            paraRanges.add(new int[]{s, sb.length()});
        }

        // 범위 겹침 기반 분배 (단락이 블록 경계를 걸치면 분할)
        for (ASTTextFrameBlock block : blocks) {
            int blockStart = block.composedCharStart();
            int blockEnd = block.composedCharEnd();
            if (blockStart < 0) continue;

            // YGapSplit 블록: composedCharStart/End가 paraIndex 범위를 의미
            boolean isYGapBlock = block.sourceId() != null && block.sourceId().contains("_g");
            if (isYGapBlock) {
                for (int i = 0; i < paragraphs.size(); i++) {
                    if (i >= blockStart && i <= blockEnd) {
                        block.addParagraph(paragraphs.get(i));
                    }
                }
                continue;
            }

            for (int i = 0; i < paragraphs.size(); i++) {
                int paraStart = paraRanges.get(i)[0];
                int paraEnd = paraRanges.get(i)[1];

                if (paraEnd <= blockStart) continue; // 단락이 블록 이전
                if (paraStart >= blockEnd) break;    // 단락이 블록 이후

                if (paraStart >= blockStart && paraEnd <= blockEnd) {
                    // 단락이 블록 안에 완전히 포함
                    block.addParagraph(paragraphs.get(i));
                } else if (paraStart < blockEnd && paraEnd > blockEnd) {
                    // 단락이 블록 끝을 넘김 → 앞부분만
                    int cutLen = blockEnd - paraStart;
                    ASTParagraph trimmed = ParagraphTextHelpers.createSplitParagraph(paragraphs.get(i),
                            ParagraphTextHelpers.getParaPlainText(paragraphs.get(i)) != null
                                    ? ParagraphTextHelpers.getParaPlainText(paragraphs.get(i)).substring(0, Math.min(cutLen, ParagraphTextHelpers.getParaPlainText(paragraphs.get(i)).length()))
                                    : "");
                    if (trimmed != null) block.addParagraph(trimmed);
                } else if (paraStart < blockStart && paraEnd > blockStart) {
                    // 이전 블록에서 시작된 단락의 나머지
                    int skipLen = blockStart - paraStart;
                    String fullText = ParagraphTextHelpers.getParaPlainText(paragraphs.get(i));
                    String contText = (fullText != null && skipLen < fullText.length())
                            ? fullText.substring(skipLen) : "";
                    ASTParagraph cont = ParagraphTextHelpers.createContinuationParagraph(paragraphs.get(i), skipLen, contText);
                    if (cont != null) block.addParagraph(cont);
                }
            }
        }
    }

    static void distributeParagraphs(ResolvedBuildContext ctx, List<ASTParagraph> paragraphs,
                                       List<ASTTextFrameBlock> blocks, String storyId) {
        // composedLines 분할 블록 감지: composedCharStart >= 0인 블록이 있으면
        boolean hasComposedBlocks = false;
        for (ASTTextFrameBlock b : blocks) {
            if (b.composedCharStart() >= 0) { hasComposedBlocks = true; break; }
        }
        if (hasComposedBlocks) {
            distributeByComposedCharRange(ctx, paragraphs, blocks);
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
            int skip1 = Math.max(0, block.skipParagraphs());
            java.util.Set<Integer> excl1 = block.excludedParagraphIndices();
            int idx1 = 0;
            for (ASTParagraph p : paragraphs) {
                int curIdx = idx1++;
                if (curIdx < skip1) continue;
                if (excl1 != null && excl1.contains(curIdx)) continue;
                block.addParagraph(p);
            }
            return;
        }

        // 다중 프레임: frameVisibleText 기반 분배
        List<ASTTextFrameBlock> ordered = InlineFrameHandler.orderByThreadChain(ctx, blocks);

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

        // 각 프레임의 첫 frameParaText를 storyText에서 검색하여 정확한 범위 결정
        // 프레임별 (startOffset, endOffset) 계산
        int[][] frameRanges = new int[ordered.size()][2];
        int searchFrom = 0;
        for (int fi = 0; fi < ordered.size(); fi++) {
            ASTTextFrameBlock block = ordered.get(fi);
            String sid2 = block.sourceId();
            String domId = kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers.domIdFromSourceId(sid2);
            if (domId == null) domId = sid2;
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId);
            // wrap 분할 블록은 블록 자체의 frameVisibleText 우선
            String visibleText = block.frameVisibleText();
            if (visibleText == null) {
                visibleText = (rtf != null) ? rtf.frameVisibleText() : null;
            }
            if (visibleText != null) {
                visibleText = visibleText.replace("\uFFFC", "").replace("\n", "");
            }

            if (visibleText == null || visibleText.isEmpty()) {
                // frameVisibleText가 없으면 frameParaTexts 폴백
                java.util.List<String> frameTexts = (rtf != null) ? rtf.frameParaTexts() : null;
                if (frameTexts != null && !frameTexts.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String ft : frameTexts) {
                        if (ft != null) sb.append(ft.replace("\uFFFC", ""));
                    }
                    visibleText = sb.toString();
                }
            }

            if (visibleText == null || visibleText.isEmpty()) {
                frameRanges[fi][0] = searchFrom;
                frameRanges[fi][1] = (fi == ordered.size() - 1) ? storyText.length() : searchFrom;
                continue;
            }

            // visibleText의 앞부분을 storyText에서 검색하여 시작 위치 결정
            String startKey = visibleText.length() > 20 ? visibleText.substring(0, 20) : visibleText;
            int foundStart = storyText.indexOf(startKey, searchFrom);
            if (foundStart < 0) foundStart = searchFrom;

            // visibleText의 끝부분을 storyText에서 검색하여 종료 위치 결정
            String endKey = visibleText.length() > 20 ? visibleText.substring(visibleText.length() - 20) : visibleText;
            int foundEnd = storyText.indexOf(endKey, foundStart);
            if (foundEnd >= 0) {
                foundEnd += endKey.length();
            } else {
                foundEnd = foundStart + visibleText.length();
            }

            frameRanges[fi][0] = foundStart;
            frameRanges[fi][1] = Math.min(foundEnd, storyText.length());
            searchFrom = frameRanges[fi][1];
        }

        // 프레임별 단락 할당
        for (int fi = 0; fi < ordered.size(); fi++) {
            ASTTextFrameBlock block = ordered.get(fi);
            int frameStart = frameRanges[fi][0];
            int frameEnd = frameRanges[fi][1];
            String sid3 = block.sourceId();
            String domId3 = kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers.domIdFromSourceId(sid3);
            if (domId3 == null) domId3 = sid3;
            ResolvedTextFrame rtf = ctx.resolvedData.getTextFrame(domId3);
            java.util.List<String> frameTexts = (rtf != null) ? rtf.frameParaTexts() : null;

            if (frameTexts == null || frameTexts.isEmpty()) {
                int start = (rtf != null) ? Math.max(0, rtf.paragraphStart()) : 0;
                int end = (rtf != null && rtf.paragraphEnd() >= 0) ? rtf.paragraphEnd() : paragraphs.size() - 1;
                start += Math.max(0, block.skipParagraphs());
                java.util.Set<Integer> excl2 = block.excludedParagraphIndices();
                for (int i = start; i <= end && i < paragraphs.size(); i++) {
                    if (excl2 != null && excl2.contains(i)) continue;
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
                    block.addParagraph(paragraphs.get(i));
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
            // 타이틀 오버레이로 첫 N 단락 숨기기
            int sk = block.skipParagraphs();
            if (sk > 0 && !block.paragraphs().isEmpty()) {
                int rem = Math.min(sk, block.paragraphs().size());
                for (int s = 0; s < rem; s++) {
                    block.paragraphs().remove(0);
                }
            }
        }
    }
}
