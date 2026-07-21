package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;

import java.util.*;

/**
 * resolved.json의 textFrame 문단 범위 정보를 이용하여,
 * 연결 텍스트프레임(linked text frames)의 문단을 재배치한다.
 *
 * 현재 파이프라인은 Story의 모든 문단을 첫 프레임에 넣고
 * 나머지 연결 프레임은 빈 블록으로 생성한다.
 * 이 클래스는 InDesign이 실제로 배치한 프레임별 문단 범위를 반영한다.
 *
 * 핵심: IDML(AST)과 InDesign DOM(resolved)의 문단 인덱스가 다를 수 있으므로,
 * 텍스트 내용 기반 순차 매칭으로 resolved 인덱스를 AST 인덱스로 변환한다.
 *
 * 호출 시점: ResolvedMerger.enrich() 이후
 */
public class ResolvedFrameDistributor {

    /**
     * AST 문서의 연결 텍스트프레임에 문단을 재배치한다.
     *
     * @param astDoc   AST 문서
     * @param resolved resolved.json 데이터 (textFrames 포함)
     */
    public static void distribute(ASTDocument astDoc, ResolvedData resolved) {
        if (resolved.textFrameCount() == 0) return;

        // storyId별 ASTTextFrameBlock 수집 (섹션 순서대로)
        Map<String, List<ASTTextFrameBlock>> blocksByStory = collectBlocksByStory(astDoc);

        for (Map.Entry<String, List<ASTTextFrameBlock>> entry : blocksByStory.entrySet()) {
            String idmlStoryId = entry.getKey();       // IDML hex: "u1735"
            List<ASTTextFrameBlock> blocks = entry.getValue();

            // 배경 전용 블록을 분배 대상에서 제외 (fillColor만 있고 실질 텍스트 없는 장식 블록)
            int beforeSize = blocks.size();
            blocks.removeIf(ASTTextFrameBlock::isBackgroundOnly);
            if (blocks.size() < beforeSize) {
                System.out.println("[FrameDistributor] Story " + idmlStoryId
                        + ": filtered " + (beforeSize - blocks.size()) + " background-only block(s)");
            }
            if (blocks.size() < 2) continue;           // 단독 프레임은 건너뜀

            // IDML hex storyId → decimal storyId for resolved lookup
            String decimalStoryId = idmlHexToDecimal(idmlStoryId);
            List<ResolvedTextFrame> resolvedFrames = resolved.getTextFramesForStory(decimalStoryId);
            if (resolvedFrames.isEmpty()) continue;

            // 문단이 있는 소스 블록 찾기 (첫 프레임에 모든 문단이 모여 있음)
            ASTTextFrameBlock sourceBlock = findSourceBlock(blocks);
            if (sourceBlock == null) continue;

            List<ASTParagraph> allParas = new ArrayList<>(sourceBlock.paragraphs());
            if (allParas.isEmpty()) continue;

            ResolvedStory resolvedStory = resolved.getStory(decimalStoryId);
            if (shouldUseVisibleTextRangeDistribution(resolvedStory, resolvedFrames)) {
                boolean distributed = distributeByVisibleTextRanges(allParas, blocks, resolvedFrames);
                if (distributed) {
                    System.out.println("[FrameDistributor] Story " + idmlStoryId
                            + ": " + allParas.size() + " AST paras → "
                            + blocks.size() + " frames (visible-text ranges)");
                }
                continue;
            }

            // resolved→AST 문단 인덱스 매핑 구축 (텍스트 내용 기반)
            int[] idxMap = null;
            if (resolvedStory != null && !resolvedStory.paragraphs().isEmpty()) {
                idxMap = buildIndexMapping(resolvedStory, allParas);
                if (idxMap != null) {
                    System.out.println("[FrameDistributor] Index mapping for story " + idmlStoryId
                            + ": " + resolvedStory.paragraphs().size() + " resolved → "
                            + allParas.size() + " AST paragraphs");
                }
            }

            // resolved 프레임을 paragraphStart 순으로 정렬 (경계 계산용)
            List<ResolvedTextFrame> sortedFrames = new ArrayList<>(resolvedFrames);
            sortedFrames.sort(Comparator.comparingInt(ResolvedTextFrame::paragraphStart));

            // paragraphStart 순으로 블록 정렬 (첫 프레임부터 문단 배정)
            List<ASTTextFrameBlock> sortedBlocks = new ArrayList<>(blocks);
            final List<ResolvedTextFrame> rfList = resolvedFrames;
            sortedBlocks.sort(new Comparator<ASTTextFrameBlock>() {
                public int compare(ASTTextFrameBlock a, ASTTextFrameBlock b) {
                    ResolvedTextFrame ra = matchFrame(a.sourceId(), rfList);
                    ResolvedTextFrame rb = matchFrame(b.sourceId(), rfList);
                    int sa = ra != null ? ra.paragraphStart() : Integer.MAX_VALUE;
                    int sb = rb != null ? rb.paragraphStart() : Integer.MAX_VALUE;
                    return Integer.compare(sa, sb);
                }
            });

            // 각 블록에 resolved 범위에 따라 문단 분배
            // paragraphStart 순으로 처리하여 겹치는 문단은 첫 프레임이 소유
            boolean distributed = false;
            Set<Integer> claimedParaIndices = new HashSet<>();
            Map<Integer, ASTParagraph> splitParaMap = new HashMap<>(); // 분할된 후반부
            List<ASTTextFrameBlock> unmatchedBlocks = new ArrayList<>();

            for (ASTTextFrameBlock block : sortedBlocks) {
                ResolvedTextFrame rtf = matchFrame(block.sourceId(), resolvedFrames);
                if (rtf == null || rtf.paragraphStart() < 0) {
                    unmatchedBlocks.add(block);
                    continue;
                }

                int astStart, astEnd;
                if (idxMap != null) {
                    astStart = mapToAst(idxMap, rtf.paragraphStart());
                    // 프레임 자체의 paragraphEnd 사용 (겹치는 문단도 포함)
                    astEnd = mapToAst(idxMap, rtf.paragraphEnd());

                    // 마지막 프레임이 아닌 경우에도, 다음 프레임 시작과 자체 pEnd 중 큰 값 사용
                    int nextResStart = findNextFrameResolvedStart(rtf, sortedFrames);
                    if (nextResStart < 0) {
                        // 마지막 프레임: 나머지 전부
                        astEnd = allParas.size() - 1;
                    }
                } else {
                    astStart = rtf.paragraphStart();
                    astEnd = rtf.paragraphEnd();
                }

                astStart = Math.max(0, astStart);
                astEnd = Math.min(astEnd, allParas.size() - 1);
                if (astStart > astEnd) continue;

                block.paragraphs().clear();
                for (int i = astStart; i <= astEnd; i++) {
                    if (claimedParaIndices.contains(i)) {
                        // 이전 프레임이 이미 가져간 문단이 이 프레임에서도 시작하면
                        // → 문단이 두 프레임에 걸쳐있음. 이전 프레임에서 U+2028로 분할된 후반부를 가져옴.
                        ASTParagraph splitTail = splitParaMap.get(i);
                        if (splitTail != null) {
                            block.addParagraph(splitTail);
                        }
                        continue;
                    }

                    // 이 문단이 다음 프레임에서도 시작하면 → 프레임 경계에서 분할
                    boolean nextFrameAlsoStarts = false;
                    if (i == astEnd) {
                        int nextResStart = findNextFrameResolvedStart(rtf, sortedFrames);
                        if (nextResStart >= 0) {
                            int nextAstStart = mapToAst(idxMap, nextResStart);
                            if (nextAstStart == i) {
                                nextFrameAlsoStarts = true;
                            }
                        }
                    }

                    if (nextFrameAlsoStarts) {
                        // U+2028 (LINE SEPARATOR) 또는 \n에서 분할
                        ASTParagraph[] halves = splitParagraphAtLineSeparator(allParas.get(i));
                        if (halves != null) {
                            block.addParagraph(halves[0]);
                            splitParaMap.put(i, halves[1]);
                            claimedParaIndices.add(i);
                        } else {
                            // 분할점 없으면 전체를 이 프레임에 배정
                            block.addParagraph(allParas.get(i));
                            claimedParaIndices.add(i);
                        }
                    } else {
                        block.addParagraph(allParas.get(i));
                        claimedParaIndices.add(i);
                    }
                }

                // resolved Y좌표를 각 단락에 전파
                double[] yOffsets = rtf.paragraphYOffsets();
                if (yOffsets != null) {
                    for (int i = astStart; i <= astEnd; i++) {
                        int frameParaIdx = i - astStart;
                        if (frameParaIdx < yOffsets.length && yOffsets[frameParaIdx] >= 0) {
                            allParas.get(i).yOffsetInFrame(yOffsets[frameParaIdx]);
                        }
                    }
                }

                block.distributed(true);
                distributed = true;
            }

            // resolved에 없는 블록에는 다른 프레임이 가져가지 않은 문단만 배정
            if (distributed && !unmatchedBlocks.isEmpty()) {
                List<ASTParagraph> unclaimed = new ArrayList<>();
                for (int i = 0; i < allParas.size(); i++) {
                    if (!claimedParaIndices.contains(i)) {
                        unclaimed.add(allParas.get(i));
                    }
                }

                if (!unclaimed.isEmpty()) {
                    ASTTextFrameBlock firstUnmatched = unmatchedBlocks.get(0);
                    firstUnmatched.paragraphs().clear();
                    for (ASTParagraph para : unclaimed) {
                        firstUnmatched.addParagraph(para);
                    }
                    firstUnmatched.distributed(true);
                }

                for (int i = 1; i < unmatchedBlocks.size(); i++) {
                    unmatchedBlocks.get(i).paragraphs().clear();
                    unmatchedBlocks.get(i).distributed(true);
                }
            }

            if (distributed) {
                System.out.println("[FrameDistributor] Story " + idmlStoryId
                        + ": " + allParas.size() + " AST paras → "
                        + blocks.size() + " frames"
                        + (idxMap != null ? " (index-mapped)" : " (direct)"));
            }
        }

        // source ownership policy: source TextFrames remain independently owned visible
        // outputs. Same-page frames must not be merged by story/bounds.
        annotateParagraphPageBounds(astDoc, resolved);
    }

    private static final int FRAME_RANGE_REWIND_TOLERANCE = 32;

    /**
     * Some resolved.json versions expose paragraphStart/paragraphEnd as story
     * character offsets, despite the field names. In that shape, treating the
     * numbers as paragraph indices moves the next frame's first paragraph back
     * into the previous linked frame.
     */
    private static boolean shouldUseVisibleTextRangeDistribution(
            ResolvedStory story,
            List<ResolvedTextFrame> frames) {
        int paragraphCount = story != null && story.paragraphs() != null
                ? story.paragraphs().size()
                : 0;
        if (paragraphCount <= 0 || frames == null) return false;
        for (ResolvedTextFrame frame : frames) {
            if (frame == null) continue;
            if (frame.paragraphStart() >= paragraphCount || frame.paragraphEnd() >= paragraphCount) {
                return true;
            }
        }
        return false;
    }

    private static boolean distributeByVisibleTextRanges(
            List<ASTParagraph> paragraphs,
            List<ASTTextFrameBlock> blocks,
            List<ResolvedTextFrame> resolvedFrames) {
        if (paragraphs == null || paragraphs.isEmpty()
                || blocks == null || blocks.isEmpty()
                || resolvedFrames == null || resolvedFrames.isEmpty()) {
            return false;
        }

        List<ResolvedTextFrame> sortedFrames = new ArrayList<>(resolvedFrames);
        sortedFrames.sort(Comparator.comparingInt(ResolvedTextFrame::paragraphStart));

        List<ASTTextFrameBlock> orderedBlocks = new ArrayList<>();
        for (ResolvedTextFrame frame : sortedFrames) {
            ASTTextFrameBlock block = findBlockForFrame(blocks, frame);
            if (block != null && !orderedBlocks.contains(block)) {
                orderedBlocks.add(block);
            }
        }
        if (orderedBlocks.size() < 2) return false;

        StringBuilder storyTextBuilder = new StringBuilder();
        List<int[]> paraRanges = new ArrayList<>();
        for (ASTParagraph paragraph : paragraphs) {
            int start = storyTextBuilder.length();
            String text = ParagraphTextHelpers.getParaStoryFlowText(paragraph);
            storyTextBuilder.append(text != null ? text : "");
            paraRanges.add(new int[] { start, storyTextBuilder.length() });
        }
        String storyText = storyTextBuilder.toString();
        if (storyText.isEmpty()) return false;

        int[][] frameRanges = new int[orderedBlocks.size()][2];
        int searchFrom = 0;
        for (int i = 0; i < orderedBlocks.size(); i++) {
            ASTTextFrameBlock block = orderedBlocks.get(i);
            ResolvedTextFrame frame = matchFrame(block.sourceId(), resolvedFrames);
            String visibleText = normalizeFrameTextForRangeMatching(
                    frame != null ? frame.frameVisibleText() : null);
            if (visibleText == null || visibleText.isEmpty()) {
                visibleText = normalizeFrameTextForRangeMatching(joinFrameParaTexts(frame));
            }

            if (visibleText == null || visibleText.isEmpty()) {
                frameRanges[i][0] = searchFrom;
                frameRanges[i][1] = i == orderedBlocks.size() - 1 ? storyText.length() : searchFrom;
                continue;
            }

            int foundStart = findFrameStart(storyText, visibleText, searchFrom);
            if (foundStart < 0) {
                foundStart = clamp(frame != null ? frame.paragraphStart() : searchFrom, 0, storyText.length());
            }

            String endKey = visibleText.length() > 20
                    ? visibleText.substring(visibleText.length() - 20)
                    : visibleText;
            int foundEnd = storyText.indexOf(endKey, foundStart);
            if (foundEnd >= 0) {
                foundEnd += endKey.length();
            } else {
                foundEnd = foundStart + visibleText.length();
            }

            frameRanges[i][0] = clamp(foundStart, 0, storyText.length());
            frameRanges[i][1] = clamp(foundEnd, frameRanges[i][0], storyText.length());
            searchFrom = frameRanges[i][1];
        }
        closeThreadedStoryRangeGaps(frameRanges, storyText.length());

        for (ASTTextFrameBlock block : orderedBlocks) {
            block.paragraphs().clear();
        }
        for (int i = 0; i < orderedBlocks.size(); i++) {
            ASTTextFrameBlock block = orderedBlocks.get(i);
            int frameStart = frameRanges[i][0];
            int frameEnd = frameRanges[i][1];
            for (int pi = 0; pi < paragraphs.size(); pi++) {
                int paraStart = paraRanges.get(pi)[0];
                int paraEnd = paraRanges.get(pi)[1];
                if (paraEnd <= frameStart) continue;
                if (paraStart >= frameEnd) break;

                ASTParagraph paragraph = paragraphs.get(pi);
                if (paraStart >= frameStart && paraEnd <= frameEnd) {
                    block.addParagraph(paragraph);
                    continue;
                }

                int localStart = Math.max(0, frameStart - paraStart);
                int localEnd = Math.min(paraEnd - paraStart, frameEnd - paraStart);
                ASTParagraph slice = sliceParagraph(paragraph, localStart, localEnd);
                if (slice != null) {
                    block.addParagraph(slice);
                }
            }
            block.distributed(true);
        }
        return true;
    }

    private static ASTTextFrameBlock findBlockForFrame(List<ASTTextFrameBlock> blocks, ResolvedTextFrame frame) {
        if (blocks == null || frame == null) return null;
        for (ASTTextFrameBlock block : blocks) {
            if (frame.equals(matchFrame(block.sourceId(), Collections.singletonList(frame)))) {
                return block;
            }
        }
        return null;
    }

    private static ASTParagraph sliceParagraph(ASTParagraph original, int start, int end) {
        if (original == null) return null;
        String text = ParagraphTextHelpers.getParaStoryFlowText(original);
        if (text == null) text = "";
        start = clamp(start, 0, text.length());
        end = clamp(end, start, text.length());
        if (start == 0 && end >= text.length()) return original;
        if (start >= end) return null;
        if (start == 0) {
            return ParagraphTextHelpers.createSplitParagraph(original, text.substring(0, end));
        }
        ASTParagraph continuation = ParagraphTextHelpers.createContinuationParagraph(
                original, start, text.substring(start, end));
        if (continuation == null || end >= text.length()) {
            return continuation;
        }
        return ParagraphTextHelpers.createSplitParagraph(continuation, text.substring(start, end));
    }

    private static String joinFrameParaTexts(ResolvedTextFrame frame) {
        if (frame == null || frame.frameParaTexts() == null || frame.frameParaTexts().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String text : frame.frameParaTexts()) {
            if (text != null) sb.append(text);
        }
        return sb.toString();
    }

    private static void closeThreadedStoryRangeGaps(int[][] frameRanges, int storyLength) {
        if (frameRanges == null || frameRanges.length <= 1 || storyLength <= 0) return;
        for (int i = 0; i < frameRanges.length - 1; i++) {
            int currentEnd = clamp(frameRanges[i][1], 0, storyLength);
            int nextStart = clamp(frameRanges[i + 1][0], 0, storyLength);
            if (nextStart > currentEnd) {
                frameRanges[i][1] = nextStart;
            }
            if (nextStart < frameRanges[i][1]) {
                frameRanges[i][1] = nextStart;
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int findFrameStart(String storyText, String visibleText, int searchFrom) {
        if (storyText == null || visibleText == null || visibleText.isEmpty()) return -1;
        int from = Math.max(0, searchFrom - FRAME_RANGE_REWIND_TOLERANCE);
        int maxLen = Math.min(20, visibleText.length());
        for (int len = maxLen; len >= 4; len--) {
            String key = trimTrailingFrameBoundaryChars(visibleText.substring(0, len));
            if (key.length() < 4) continue;
            int found = storyText.indexOf(key, from);
            if (found >= 0) return found;
        }
        return -1;
    }

    private static String trimTrailingFrameBoundaryChars(String text) {
        if (text == null || text.isEmpty()) return text;
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (Character.isWhitespace(c) || Character.isISOControl(c)
                    || c == '\u2003' || c == '\u2007' || c == '\u2009' || c == '\u00A0') {
                end--;
            } else {
                break;
            }
        }
        return text.substring(0, end);
    }

    private static String normalizeFrameTextForRangeMatching(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFC' || c == '\n' || c == '\r') continue;
            if (Character.isISOControl(c) && c != '\t' && c != '\u0007' && c != '\u0008') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    // ─── 인덱스 매핑 ────────────────────────────────────────

    /**
     * resolved 문단 인덱스 → AST 문단 인덱스 매핑을 구축한다.
     * IDML은 InDesign DOM과 다른 문단 분할을 할 수 있으므로 (추가 빈 문단 등),
     * 텍스트 내용 기반으로 순차 매칭한다.
     *
     * @return 매핑 배열 (mapping[resolvedIdx] = astIdx), 실패 시 null
     */
    private static int[] buildIndexMapping(ResolvedStory resolvedStory,
                                            List<ASTParagraph> astParas) {
        List<ResolvedParagraph> resParas = resolvedStory.paragraphs();
        int[] mapping = new int[resParas.size()];
        Arrays.fill(mapping, -1);

        int astCursor = 0;
        int matchCount = 0;

        for (int ri = 0; ri < resParas.size(); ri++) {
            ResolvedParagraph resPara = resParas.get(ri);
            String resNorm = normalizeText(extractResolvedText(resPara));
            boolean resHasInline = resolvedHasInline(resPara);

            for (int ai = astCursor; ai < astParas.size(); ai++) {
                ASTParagraph astPara = astParas.get(ai);
                String astNorm = normalizeText(extractAstText(astPara));
                boolean astHasInline = astHasInlineObject(astPara);

                if (paragraphsMatch(resNorm, astNorm, resHasInline, astHasInline)) {
                    mapping[ri] = ai;
                    astCursor = ai + 1;
                    matchCount++;
                    break;
                }
            }
        }

        // 매칭률이 너무 낮으면 매핑 실패로 간주
        if (matchCount < resParas.size() / 2) {
            System.err.println("[FrameDistributor] Index mapping failed: only "
                    + matchCount + "/" + resParas.size() + " matched");
            return null;
        }

        // 매핑 안 된 항목을 보간 (앞뒤 매핑값으로 추정)
        interpolateMapping(mapping);

        return mapping;
    }

    /**
     * 매핑 안 된 항목(-1)을 앞뒤 매핑값으로 보간한다.
     */
    private static void interpolateMapping(int[] mapping) {
        for (int i = 0; i < mapping.length; i++) {
            if (mapping[i] >= 0) continue;

            // 이전 매핑값 찾기
            int prevIdx = -1;
            int prevVal = -1;
            for (int j = i - 1; j >= 0; j--) {
                if (mapping[j] >= 0) {
                    prevIdx = j;
                    prevVal = mapping[j];
                    break;
                }
            }

            if (prevVal >= 0) {
                mapping[i] = prevVal + (i - prevIdx);
            } else {
                // 앞쪽 매핑 없으면 다음 매핑에서 역산
                int nextIdx = -1;
                int nextVal = -1;
                for (int j = i + 1; j < mapping.length; j++) {
                    if (mapping[j] >= 0) {
                        nextIdx = j;
                        nextVal = mapping[j];
                        break;
                    }
                }
                if (nextVal >= 0) {
                    mapping[i] = Math.max(0, nextVal - (nextIdx - i));
                }
            }
        }
    }

    /**
     * resolved 문단과 AST 문단이 매치되는지 판별.
     */
    private static boolean paragraphsMatch(String resNorm, String astNorm,
                                            boolean resHasInline, boolean astHasInline) {
        boolean resEmpty = resNorm.isEmpty();
        boolean astEmpty = astNorm.isEmpty() && !astHasInline;

        // 둘 다 빈 문단 (인라인 오브젝트 없음)
        if (resEmpty && !resHasInline && astEmpty) return true;

        // 둘 다 인라인 오브젝트 보유
        if (resHasInline && astHasInline) return true;

        // 둘 다 텍스트 보유 → 첫 15자 비교
        if (!resNorm.isEmpty() && !astNorm.isEmpty()) {
            int len = Math.min(15, Math.min(resNorm.length(), astNorm.length()));
            return resNorm.substring(0, len).equals(astNorm.substring(0, len));
        }

        return false;
    }

    /**
     * resolved 인덱스를 AST 인덱스로 변환.
     */
    private static int mapToAst(int[] idxMap, int resolvedIdx) {
        if (idxMap != null && resolvedIdx >= 0 && resolvedIdx < idxMap.length
                && idxMap[resolvedIdx] >= 0) {
            return idxMap[resolvedIdx];
        }
        return resolvedIdx; // fallback: 직접 인덱스
    }

    /**
     * 정렬된 프레임 리스트에서 현재 프레임 다음 프레임의 paragraphStart를 찾는다.
     * 마지막 프레임이면 -1 반환.
     */
    private static int findNextFrameResolvedStart(ResolvedTextFrame current,
                                                    List<ResolvedTextFrame> sorted) {
        for (int i = 0; i < sorted.size() - 1; i++) {
            if (sorted.get(i).id().equals(current.id())) {
                return sorted.get(i + 1).paragraphStart();
            }
        }
        return -1;
    }

    // ─── 텍스트 추출 ────────────────────────────────────────

    private static String extractResolvedText(ResolvedParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (ResolvedRun run : para.runs()) {
            if (run.text() != null) sb.append(run.text());
        }
        return sb.toString();
    }

    private static boolean resolvedHasInline(ResolvedParagraph para) {
        for (ResolvedRun run : para.runs()) {
            if (run.text() != null && run.text().contains("\uFFFC")) return true;
        }
        return false;
    }

    private static String extractAstText(ASTParagraph para) {
        StringBuilder sb = new StringBuilder();
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.TEXT_RUN) {
                String text = ((ASTTextRun) item).text();
                if (text != null) sb.append(text);
            }
        }
        return sb.toString();
    }

    private static boolean astHasInlineObject(ASTParagraph para) {
        for (ASTInlineItem item : para.items()) {
            if (item.itemType() == ASTInlineItem.ItemType.INLINE_OBJECT) return true;
        }
        return false;
    }

    /**
     * 비교용 텍스트 정규화: 특수 유니코드 제거, 트림.
     */
    private static String normalizeText(String text) {
        return text.replace("\uFFFC", "")   // Object Replacement Character
                   .replace("\u2007", "")   // Figure Space
                   .replace("\u2009", "")   // Thin Space
                   .replace("\u0008", "")   // Backspace
                   .replace("\u00A0", "")   // Non-breaking Space
                   .replace("\r", "")
                   .replace("\n", "")
                   .trim();
    }

    private static void annotateParagraphPageBounds(ASTDocument astDoc, ResolvedData resolved) {
        if (astDoc == null || resolved == null || resolved.textFrameCount() == 0) return;
        List<ResolvedTextFrame> allFrames = resolved.textFrames();
        for (ASTSection section : astDoc.sections()) {
            for (ASTBlock block : section.blocks()) {
                if (!(block instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tfb = (ASTTextFrameBlock) block;
                if (tfb.paragraphs() == null || tfb.paragraphs().isEmpty()) continue;
                ResolvedTextFrame rtf = matchFrame(tfb.sourceId(), allFrames);
                if (rtf == null || rtf.composedLines() == null || rtf.composedLines().isEmpty()) continue;
                Map<Integer, List<ResolvedTextFrame.ComposedLine>> byPara = composedLinesByParagraph(rtf);
                Set<ASTParagraph> processed = new HashSet<>();
                for (List<ResolvedTextFrame.ComposedLine> lines : byPara.values()) {
                    ASTParagraph para = findParagraphForComposedLines(tfb.paragraphs(), lines, processed);
                    if (para == null) continue;
                    long[] bounds = pageRelativeLineBounds(rtf, lines, resolved.scaleFactor());
                    if (bounds == null) continue;
                    para.pageX(bounds[0]);
                    para.pageY(bounds[1]);
                    para.pageWidth(Math.max(1, bounds[2] - bounds[0]));
                    para.pageHeight(Math.max(1, bounds[3] - bounds[1]));
                    processed.add(para);
                }
            }
        }
    }

    private static Map<Integer, List<ResolvedTextFrame.ComposedLine>> composedLinesByParagraph(
            ResolvedTextFrame tf) {
        Map<Integer, List<ResolvedTextFrame.ComposedLine>> result = new LinkedHashMap<>();
        if (tf == null || tf.composedLines() == null) return result;
        for (ResolvedTextFrame.ComposedLine line : tf.composedLines()) {
            if (line == null || line.paraIndex() < 0) continue;
            result.computeIfAbsent(line.paraIndex(), k -> new ArrayList<>()).add(line);
        }
        return result;
    }

    private static ASTParagraph findParagraphForComposedLines(
            List<ASTParagraph> paragraphs,
            List<ResolvedTextFrame.ComposedLine> lines,
            Set<ASTParagraph> processed) {
        String expected = normalizeText(combinedComposedLineText(lines));
        if (expected.isEmpty() || paragraphs == null) return null;
        for (ASTParagraph para : paragraphs) {
            if (para == null || processed.contains(para)) continue;
            String actual = normalizeText(extractAstText(para));
            if (actual.isEmpty()) continue;
            if (actual.equals(expected)
                    || actual.contains(expected)
                    || expected.contains(actual)) {
                return para;
            }
        }
        return null;
    }

    private static String combinedComposedLineText(List<ResolvedTextFrame.ComposedLine> lines) {
        StringBuilder sb = new StringBuilder();
        if (lines == null) return "";
        for (ResolvedTextFrame.ComposedLine line : lines) {
            if (line != null && line.text() != null) sb.append(line.text());
        }
        return sb.toString();
    }

    private static long[] pageRelativeLineBounds(
            ResolvedTextFrame tf,
            List<ResolvedTextFrame.ComposedLine> lines,
            double outputScale) {
        if (tf == null || lines == null || lines.isEmpty()
                || tf.geometricBounds() == null || tf.pageRelativeBounds() == null) return null;
        double[] gb = tf.geometricBounds();
        double[] pb = tf.pageRelativeBounds();
        if (gb.length < 4 || pb.length < 4) return null;
        double scaleY = coordinateScale(gb[2] - gb[0], pb[2] - pb[0]);
        double scaleX = coordinateScale(gb[3] - gb[1], pb[3] - pb[1]);
        double top = Double.POSITIVE_INFINITY;
        double left = Double.POSITIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        for (ResolvedTextFrame.ComposedLine line : lines) {
            if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
            double[] b = line.bounds();
            top = Math.min(top, pb[0] + (b[0] - gb[0]) / scaleY);
            left = Math.min(left, pb[1] + (b[1] - gb[1]) / scaleX);
            bottom = Math.max(bottom, pb[0] + (b[2] - gb[0]) / scaleY);
            right = Math.max(right, pb[1] + (b[3] - gb[1]) / scaleX);
        }
        if (!Double.isFinite(top) || !Double.isFinite(left)
                || !Double.isFinite(bottom) || !Double.isFinite(right)
                || bottom <= top || right <= left) {
            return null;
        }
        return new long[] {
                CoordinateConverter.pointsToHwpunits(left * outputScale),
                CoordinateConverter.pointsToHwpunits(top * outputScale),
                CoordinateConverter.pointsToHwpunits(right * outputScale),
                CoordinateConverter.pointsToHwpunits(bottom * outputScale)
        };
    }

    private static double coordinateScale(double sourceSize, double targetSize) {
        if (!Double.isFinite(sourceSize) || !Double.isFinite(targetSize)
                || sourceSize <= 0 || targetSize <= 0) return 1.0;
        return sourceSize / targetSize;
    }

    // ─── 문단 분할 (프레임 경계) ─────────────────────────────

    /**
     * U+2028 (LINE SEPARATOR) 마지막 위치에서 문단을 앞/뒤 두 개로 분할한다.
     * InDesign에서 연결 텍스트프레임 경계가 문단 중간에 올 때,
     * forced line break (U+2028)가 분할점이 된다.
     *
     * @return [앞 문단, 뒷 문단] 또는 분할점 없으면 null
     */
    private static ASTParagraph[] splitParagraphAtLineSeparator(ASTParagraph original) {
        // U+2028이 포함된 마지막 텍스트런 찾기
        int splitRunIdx = -1;
        int splitCharIdx = -1;
        List<ASTInlineItem> items = original.items();

        for (int ri = items.size() - 1; ri >= 0; ri--) {
            ASTInlineItem item = items.get(ri);
            if (item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) continue;
            String text = ((ASTTextRun) item).text();
            if (text == null) continue;

            int idx = text.lastIndexOf('\u2028');
            if (idx < 0) idx = text.lastIndexOf('\n');
            if (idx >= 0) {
                splitRunIdx = ri;
                splitCharIdx = idx;
                break;
            }
        }

        if (splitRunIdx < 0) return null;

        ASTParagraph head = new ASTParagraph();
        ASTParagraph tail = new ASTParagraph();

        // 문단 속성 복사
        copyParagraphProps(original, head);
        copyParagraphProps(original, tail);

        // 분할점 이전 아이템 → head
        for (int i = 0; i < splitRunIdx; i++) {
            head.addItem(items.get(i));
        }

        // 분할점이 있는 런을 앞/뒤로 분리
        ASTTextRun splitRun = (ASTTextRun) items.get(splitRunIdx);
        String fullText = splitRun.text();
        String beforeText = fullText.substring(0, splitCharIdx); // U+2028 제외
        String afterText = fullText.substring(splitCharIdx + 1); // U+2028 이후

        if (!beforeText.isEmpty()) {
            ASTTextRun headRun = cloneTextRun(splitRun);
            headRun.text(beforeText);
            head.addItem(headRun);
        }
        if (!afterText.isEmpty()) {
            ASTTextRun tailRun = cloneTextRun(splitRun);
            tailRun.text(afterText);
            tail.addItem(tailRun);
        }

        // 분할점 이후 아이템 → tail
        for (int i = splitRunIdx + 1; i < items.size(); i++) {
            tail.addItem(items.get(i));
        }

        return new ASTParagraph[] { head, tail };
    }

    private static void copyParagraphProps(ASTParagraph src, ASTParagraph dst) {
        dst.paragraphStyleRef(src.paragraphStyleRef());
        dst.alignment(src.alignment());
        dst.firstLineIndent(src.firstLineIndent());
        dst.leftMargin(src.leftMargin());
        dst.rightMargin(src.rightMargin());
        dst.spaceBefore(src.spaceBefore());
        dst.spaceAfter(src.spaceAfter());
        dst.lineSpacing(src.lineSpacing());
        dst.lineSpacingType(src.lineSpacingType());
        dst.letterSpacing(src.letterSpacing());
        dst.shadingOn(src.shadingOn());
        dst.shadingColor(src.shadingColor());
        dst.shadingTint(src.shadingTint());
        dst.keepWithNext(src.keepWithNext());
        dst.keepLinesTogether(src.keepLinesTogether());
    }

    private static ASTTextRun cloneTextRun(ASTTextRun src) {
        ASTTextRun r = new ASTTextRun();
        r.characterStyleRef(src.characterStyleRef());
        r.fontFamily(src.fontFamily());
        r.fontStyle(src.fontStyle());
        r.fontSizeHwpunits(src.fontSizeHwpunits());
        r.textColor(src.textColor());
        r.letterSpacing(src.letterSpacing());
        r.subscript(src.subscript());
        r.superscript(src.superscript());
        r.underline(src.underline());
        r.underlineColor(src.underlineColor());
        r.underlineShape(src.underlineShape());
        r.strikeThrough(src.strikeThrough());
        r.horizontalScale(src.horizontalScale());
        r.verticalScale(src.verticalScale());
        r.baselineShift(src.baselineShift());
        r.grepMathFont(src.grepMathFont());
        r.grepStyleApplied(src.grepStyleApplied());
        return r;
    }

    // ─── 같은 페이지 프레임 병합 ────────────────────────────

    /**
     * 같은 섹션(페이지) 내에서 같은 storyId를 공유하는 distributed 프레임을 병합한다.
     * InDesign에서 같은 페이지의 연결 프레임은 하나의 연속된 텍스트 영역으로 보이므로,
     * HWPX에서도 하나의 글상자로 합쳐야 한다.
     */
    private static void mergeSamePageFrames(ASTDocument astDoc) {
        // Deprecated by source ownership policy. Same-page TextFrames keep independent source
        // ownership; no story/bounds based merge is allowed here.
    }

    private static boolean shouldUnionMergedBounds(ASTTextFrameBlock target, ASTTextFrameBlock src) {
        if (target == null || src == null) return true;
        if (isSameLinePrefixMerge(target, src) || isSameLinePrefixMerge(src, target)) {
            return false;
        }
        return true;
    }

    private static boolean shouldKeepSeparateFlowBlocks(
            ASTSection section,
            List<ASTTextFrameBlock> blocks) {
        if (section == null || blocks == null || blocks.size() < 2) return false;
        if (blocksContainInlineTable(blocks)) return true;
        return hasTableBetweenTextFrames(section, blocks);
    }

    private static boolean blocksContainInlineTable(List<ASTTextFrameBlock> blocks) {
        for (ASTTextFrameBlock block : blocks) {
            if (block == null || block.paragraphs() == null) continue;
            for (ASTParagraph paragraph : block.paragraphs()) {
                if (paragraph == null) continue;
                if (paragraph.inlineTable() != null) return true;
                if (itemsContainInlineTable(paragraph.items())) return true;
            }
        }
        return false;
    }

    private static boolean itemsContainInlineTable(List<ASTInlineItem> items) {
        if (items == null) return false;
        for (ASTInlineItem item : items) {
            if (!(item instanceof ASTInlineObject)) continue;
            ASTInlineObject obj = (ASTInlineObject) item;
            if (obj.inlineTables() != null && !obj.inlineTables().isEmpty()) return true;
            if (paragraphsContainInlineTable(obj.paragraphs())) return true;
        }
        return false;
    }

    private static boolean paragraphsContainInlineTable(List<ASTParagraph> paragraphs) {
        if (paragraphs == null) return false;
        for (ASTParagraph paragraph : paragraphs) {
            if (paragraph == null) continue;
            if (paragraph.inlineTable() != null) return true;
            if (itemsContainInlineTable(paragraph.items())) return true;
        }
        return false;
    }

    private static boolean hasTableBetweenTextFrames(ASTSection section, List<ASTTextFrameBlock> blocks) {
        long minTop = Long.MAX_VALUE;
        long maxBottom = Long.MIN_VALUE;
        long minLeft = Long.MAX_VALUE;
        long maxRight = Long.MIN_VALUE;
        for (ASTTextFrameBlock block : blocks) {
            if (block == null) continue;
            minTop = Math.min(minTop, block.y());
            maxBottom = Math.max(maxBottom, block.y() + block.height());
            minLeft = Math.min(minLeft, block.x());
            maxRight = Math.max(maxRight, block.x() + block.width());
        }
        if (minTop == Long.MAX_VALUE || maxBottom == Long.MIN_VALUE) return false;

        for (ASTBlock block : section.blocks()) {
            if (!(block instanceof ASTTable)) continue;
            ASTTable table = (ASTTable) block;
            long tableTop = table.y();
            long tableBottom = table.y() + table.height();
            long tableLeft = table.x();
            long tableRight = table.x() + table.width();
            boolean verticallyBetween = tableBottom >= minTop && tableTop <= maxBottom;
            boolean horizontallyRelated = tableRight >= minLeft && tableLeft <= maxRight;
            if (verticallyBetween && horizontallyRelated) return true;
        }
        return false;
    }

    private static boolean isSameLinePrefixMerge(ASTTextFrameBlock prefix, ASTTextFrameBlock body) {
        if (prefix == null || body == null) return false;
        String text = normalizeText(extractBlockText(prefix));
        if (!isSimplePrefixMarker(text)) return false;
        long overlap = Math.min(prefix.y() + prefix.height(), body.y() + body.height())
                - Math.max(prefix.y(), body.y());
        if (overlap <= 0) return false;
        long minHeight = Math.max(1L, Math.min(prefix.height(), body.height()));
        if (overlap * 100L < minHeight * 25L) return false;
        return prefix.x() <= body.x() + Math.max(1L, body.width() / 8L);
    }

    private static boolean isSimplePrefixMarker(String text) {
        if (text == null) return false;
        String t = text.replaceAll("\\s+", "");
        return t.matches("[0-9]{1,2}")
                || t.matches("[가-힣]")
                || t.matches("[ㄱ-ㅎ]")
                || t.matches("\\([0-9]{1,2}\\)")
                || t.matches("[0-9]{1,2}[.)]");
    }

    private static String extractBlockText(ASTTextFrameBlock block) {
        StringBuilder sb = new StringBuilder();
        if (block == null || block.paragraphs() == null) return "";
        for (ASTParagraph para : block.paragraphs()) {
            sb.append(extractAstText(para));
        }
        return sb.toString();
    }

    // ─── 프레임/블록 수집 ────────────────────────────────────

    private static Map<String, List<ASTTextFrameBlock>> collectBlocksByStory(ASTDocument astDoc) {
        Map<String, List<ASTTextFrameBlock>> result = new LinkedHashMap<>();
        for (ASTSection section : astDoc.sections()) {
            for (ASTBlock block : section.blocks()) {
                if (block.blockType() == ASTBlock.BlockType.TEXT_FRAME_BLOCK) {
                    ASTTextFrameBlock tfb = (ASTTextFrameBlock) block;
                    String sid = tfb.storyId();
                    if (sid != null) {
                        result.computeIfAbsent(sid, k -> new ArrayList<>()).add(tfb);
                    }
                }
            }
        }
        return result;
    }

    private static ASTTextFrameBlock findSourceBlock(List<ASTTextFrameBlock> blocks) {
        for (ASTTextFrameBlock block : blocks) {
            if (!block.paragraphs().isEmpty()) {
                return block;
            }
        }
        return null;
    }

    private static ResolvedTextFrame matchFrame(String sourceId,
                                                 List<ResolvedTextFrame> resolvedFrames) {
        if (sourceId == null) return null;
        String decimalId = idmlHexToDecimal(sourceId);
        for (ResolvedTextFrame rtf : resolvedFrames) {
            if (decimalId.equals(rtf.id())) {
                return rtf;
            }
        }
        return null;
    }

    /**
     * IDML hex ID → InDesign decimal ID 변환.
     * "u1747" → "5959", "u1735" → "5941"
     */
    private static String idmlHexToDecimal(String idmlId) {
        if (idmlId != null && idmlId.startsWith("u")) {
            try {
                return String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
            } catch (NumberFormatException e) {
                // hex 파싱 실패 시 원본 반환
            }
        }
        return idmlId;
    }
}
