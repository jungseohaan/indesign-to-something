package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.extract;

import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticNode;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticTypes;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.StructuralFeatures;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.ASTAdapter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.BlockInfo;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.InlineItemInfo;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.PageInfo;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.ParagraphInfo;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.StoryInfo;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.StyleInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * FeatureExtractor — ASTAdapter 를 통해 구조적 특징을 추출하고
 * SemanticNode 리스트를 만든다.
 *
 * <p>TypeScript {@code packages/semantic-layer/src/core/feature-extractor.ts}
 * 와 1:1 포팅. 같은 입력에 같은 결과.</p>
 */
public final class FeatureExtractor {

    private FeatureExtractor() {}

    /** 진입점. */
    public static List<SemanticNode> extractFeatures(ASTAdapter adapter) {
        List<PageInfo> pages = adapter.getPages();
        List<StoryInfo> stories = adapter.getStories();
        Map<String, StoryInfo> storyMap = new HashMap<>();
        for (StoryInfo s : stories) storyMap.put(s.storyId, s);

        List<SemanticNode> nodes = new ArrayList<>();
        Map<Integer, List<BlockInfo>> blocksByPage = new HashMap<>();

        for (int pi = 0; pi < pages.size(); pi++) {
            PageInfo page = pages.get(pi);
            List<BlockInfo> blocks = adapter.getBlocks(page.pageNumber);
            blocksByPage.put(page.pageNumber, blocks);

            for (int i = 0; i < blocks.size(); i++) {
                BlockInfo block = blocks.get(i);
                List<ParagraphInfo> paragraphs = adapter.getParagraphs(block.id);
                StoryInfo story = block.storyId != null ? storyMap.get(block.storyId) : null;

                int frameIndex = 0;
                if (story != null) {
                    int idx = story.linkedFrameIds.indexOf(block.id);
                    if (idx >= 0) frameIndex = idx;
                }

                StructuralFeatures features = buildFeatures(block, page, paragraphs, story, frameIndex, adapter);

                SemanticNode node = new SemanticNode();
                node.id = "sn-" + block.id;
                node.astPath = "sections[" + pi + "].blocks[" + i + "]";
                node.nodeType = mapNodeType(block.blockType);
                node.features = features;
                node.label = SemanticTypes.LABEL_UNKNOWN;
                node.confidence = 0;
                node.appliedRule = null;
                node.manualOverride = false;
                node.children = new ArrayList<>();
                node.storyId = block.storyId;
                node.metadata = new java.util.LinkedHashMap<>();
                nodes.add(node);
            }
        }

        // 공간 근접도 (모든 블록 필요 → 두 번째 패스)
        for (SemanticNode node : nodes) {
            List<BlockInfo> pageBlocks = blocksByPage.get(node.features.pageNumber);
            if (pageBlocks == null) continue;
            node.features.spatial = computeSpatialProximity(node.features, pageBlocks, node.id);
        }

        return nodes;
    }

    // ─── Features 빌드 ─────────────────────────────────────

    private static StructuralFeatures buildFeatures(
            BlockInfo block,
            PageInfo page,
            List<ParagraphInfo> paragraphs,
            StoryInfo story,
            int frameIndex,
            ASTAdapter adapter) {

        // 텍스트/런 수집
        List<InlineItemInfo> allItems = new ArrayList<>();
        for (ParagraphInfo p : paragraphs) {
            if (p.items != null) allItems.addAll(p.items);
        }
        List<InlineItemInfo> textRuns = new ArrayList<>();
        for (InlineItemInfo item : allItems) {
            if (item.itemType == InlineItemInfo.ItemType.TEXT_RUN) textRuns.add(item);
        }

        StringBuilder textSb = new StringBuilder();
        for (InlineItemInfo r : textRuns) {
            if (r.text != null) textSb.append(r.text);
        }
        String fullText = textSb.toString();
        String firstLineText = getFirstLineText(paragraphs);

        // 폰트 분석
        List<Integer> fontSizes = new ArrayList<>();
        for (InlineItemInfo r : textRuns) {
            if (r.fontSize != null) fontSizes.add(r.fontSize);
        }
        List<String> fontFamilies = new ArrayList<>();
        for (InlineItemInfo r : textRuns) {
            if (r.fontFamily != null) fontFamilies.add(r.fontFamily);
        }

        // 단락 스타일 (이름)
        List<String> paraStyleNames = new ArrayList<>();
        for (ParagraphInfo p : paragraphs) {
            if (p.paragraphStyleRef != null) {
                StyleInfo style = adapter.getStyleByRef(p.paragraphStyleRef);
                String name = (style != null && style.styleName != null) ? style.styleName : p.paragraphStyleRef;
                paraStyleNames.add(name);
            }
        }
        // 문자 스타일 (Set로 dedup, 삽입 순서 유지)
        LinkedHashSet<String> charStyleNamesSet = new LinkedHashSet<>();
        for (InlineItemInfo r : textRuns) {
            if (r.characterStyleRef != null) {
                StyleInfo style = adapter.getStyleByRef(r.characterStyleRef);
                String name = (style != null && style.styleName != null) ? style.styleName : r.characterStyleRef;
                charStyleNamesSet.add(name);
            }
        }

        // 번호 접두사
        NumberPrefix np = detectNumberPrefix(firstLineText);

        // 정렬 분석
        List<String> alignments = new ArrayList<>();
        for (ParagraphInfo p : paragraphs) {
            if (p.alignment != null) alignments.add(p.alignment);
        }

        StructuralFeatures f = new StructuralFeatures();

        // A. 위치
        f.pageNumber = page.pageNumber;
        f.x = block.x;
        f.y = block.y;
        f.width = block.width;
        f.height = block.height;
        f.zOrder = block.zOrder;
        f.regionTag = computeRegion(block, page);
        f.columnIndex = computeColumnIndex(block, page);
        f.relativeYInPage = page.height > 0 ? block.y / page.height : 0;

        // B. 스토리
        f.storyId = block.storyId;
        f.storyFrameCount = story != null ? story.linkedFrameIds.size() : 0;
        f.storyPageSpan = story != null ? story.pages.size() : 0;
        f.frameIndexInStory = frameIndex;
        f.isStoryStart = frameIndex == 0;
        f.isStoryEnd = story != null ? frameIndex == story.linkedFrameIds.size() - 1 : true;

        // C. 텍스트 속성
        f.textContent = fullText;
        f.textLength = fullText.length();
        f.paragraphCount = paragraphs.size();
        Integer dominantFontSize = modeInt(fontSizes);
        f.dominantFontSize = dominantFontSize != null ? dominantFontSize : 0;
        int maxFs = 0;
        for (Integer s : fontSizes) if (s != null && s > maxFs) maxFs = s;
        f.maxFontSize = maxFs;
        String dominantFontFamily = mode(fontFamilies);
        f.dominantFontFamily = dominantFontFamily != null ? dominantFontFamily : "";

        boolean hasBold = false;
        for (InlineItemInfo r : textRuns) {
            if (r.bold != null && r.bold) { hasBold = true; break; }
        }
        f.hasBoldText = hasBold;
        f.dominantAlignment = mode(alignments);
        f.hasNumberPrefix = np.hasPrefix;
        f.numberPrefixPattern = np.pattern;
        f.firstLineText = firstLineText;

        // D. 스타일 (paragraphStyleNames 는 dedup, 순서 유지)
        LinkedHashSet<String> paraStyleNameSet = new LinkedHashSet<>(paraStyleNames);
        f.paragraphStyleNames = new ArrayList<>(paraStyleNameSet);
        f.characterStyleNames = new ArrayList<>(charStyleNamesSet);
        f.dominantParagraphStyle = mode(paraStyleNames);

        // E. 프레임
        f.hasFill = block.hasFill;
        f.fillColor = block.fillColor;
        f.hasStroke = block.hasStroke;
        f.isBackgroundOnly = block.isBackgroundOnly;
        f.columnCount = block.columnCount;
        f.rotationAngle = block.rotation;

        // F. 콘텐츠 구성
        f.hasTable = block.blockType == BlockInfo.BlockType.TABLE;
        boolean hasImage = block.blockType == BlockInfo.BlockType.FIGURE;
        if (!hasImage) {
            for (InlineItemInfo item : allItems) {
                if (item.objectKind == InlineItemInfo.InlineObjectKind.IMAGE) { hasImage = true; break; }
            }
        }
        f.hasImage = hasImage;
        boolean hasEq = false;
        for (InlineItemInfo item : allItems) {
            if (item.itemType == InlineItemInfo.ItemType.EQUATION) { hasEq = true; break; }
        }
        f.hasEquation = hasEq;
        boolean hasInlineFrame = false;
        for (InlineItemInfo item : allItems) {
            if (item.objectKind == InlineItemInfo.InlineObjectKind.INLINE_TEXT_FRAME) { hasInlineFrame = true; break; }
        }
        f.hasInlineFrame = hasInlineFrame;
        int inlineCount = 0;
        for (InlineItemInfo item : allItems) {
            if (item.itemType == InlineItemInfo.ItemType.INLINE_OBJECT) inlineCount++;
        }
        f.inlineObjectCount = inlineCount;
        f.blockType = block.blockType.name();

        // G. spatial 은 두 번째 패스에서 채움
        return f;
    }

    // ─── 공간 근접도 ───────────────────────────────────────

    private static StructuralFeatures.SpatialProximityFeatures computeSpatialProximity(
            StructuralFeatures features, List<BlockInfo> allBlocks, String selfNodeId) {
        StructuralFeatures.SpatialProximityFeatures out = new StructuralFeatures.SpatialProximityFeatures();
        String selfId = selfNodeId.startsWith("sn-") ? selfNodeId.substring(3) : selfNodeId;

        List<BlockInfo> candidates = new ArrayList<>();
        for (BlockInfo b : allBlocks) if (!b.id.equals(selfId)) candidates.add(b);

        // AABB 겹침
        List<BlockInfo> overlapping = new ArrayList<>();
        for (BlockInfo c : candidates) {
            if (aabbOverlap(features, c)) overlapping.add(c);
        }

        // 시각적 포함
        String containerId = null;
        double containmentRatio = 0;
        double selfArea = features.width * features.height;
        if (selfArea > 0) {
            for (BlockInfo c : overlapping) {
                double overlapArea = computeOverlapArea(features, c);
                double ratio = overlapArea / selfArea;
                if (ratio >= 0.8 && c.width * c.height > selfArea && ratio > containmentRatio) {
                    containerId = c.id;
                    containmentRatio = ratio;
                }
            }
        }

        // 최근접 CONTENT 노드
        String nearestId = null;
        double minDist = Double.POSITIVE_INFINITY;
        for (BlockInfo c : candidates) {
            if (c.isBackgroundOnly) continue;
            double dist = edgeToEdgeDistance(features, c);
            if (dist < minDist) {
                minDist = dist;
                nearestId = c.id;
            }
        }

        out.nearestContentNodeId = nearestId;
        out.nearestContentDistance = Double.isInfinite(minDist) ? -1 : minDist;
        List<String> ovIds = new ArrayList<>();
        for (BlockInfo n : overlapping) ovIds.add(n.id);
        out.overlappingNodeIds = ovIds;
        out.isVisuallyContainedBy = containerId;
        out.visualContainmentRatio = containmentRatio;
        return out;
    }

    // ─── 기하 ─────────────────────────────────────────────

    private static boolean aabbOverlap(StructuralFeatures a, BlockInfo b) {
        return a.x < b.x + b.width
                && a.x + a.width > b.x
                && a.y < b.y + b.height
                && a.y + a.height > b.y;
    }

    private static double computeOverlapArea(StructuralFeatures a, BlockInfo b) {
        double overlapX = Math.max(0, Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x));
        double overlapY = Math.max(0, Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y));
        return overlapX * overlapY;
    }

    private static double edgeToEdgeDistance(StructuralFeatures a, BlockInfo b) {
        double dx = Math.max(0, Math.max(a.x, b.x) - Math.min(a.x + a.width, b.x + b.width));
        double dy = Math.max(0, Math.max(a.y, b.y) - Math.min(a.y + a.height, b.y + b.height));
        return Math.sqrt(dx * dx + dy * dy);
    }

    // ─── 영역 ─────────────────────────────────────────────

    private static SemanticTypes.RegionTag computeRegion(BlockInfo block, PageInfo page) {
        double contentHeight = page.height - page.marginTop - page.marginBottom;
        double relY = (block.y - page.marginTop) / (contentHeight == 0 ? 1 : contentHeight);
        double relWidth = block.width / (page.width == 0 ? 1 : page.width);

        if (relWidth > 0.85) return SemanticTypes.RegionTag.FULL_WIDTH;
        if (relY < 0.15) return SemanticTypes.RegionTag.TOP;
        if (relY > 0.85) return SemanticTypes.RegionTag.BOTTOM;

        if (page.columnCount >= 2) {
            double contentWidth = page.width - page.marginLeft - page.marginRight;
            double midX = page.marginLeft + contentWidth / 2;
            double blockCenterX = block.x + block.width / 2;
            if (blockCenterX < midX - contentWidth * 0.1) return SemanticTypes.RegionTag.LEFT;
            if (blockCenterX > midX + contentWidth * 0.1) return SemanticTypes.RegionTag.RIGHT;
        }
        return SemanticTypes.RegionTag.MIDDLE;
    }

    private static int computeColumnIndex(BlockInfo block, PageInfo page) {
        if (page.columnCount <= 1) return 0;
        double contentWidth = page.width - page.marginLeft - page.marginRight;
        double colWidth = (contentWidth - (page.columnCount - 1) * page.columnGutter) / page.columnCount;
        if (colWidth <= 0) return 0;
        double relX = block.x - page.marginLeft;
        int idx = (int) Math.floor(relX / (colWidth + page.columnGutter));
        if (idx < 0) idx = 0;
        if (idx > page.columnCount - 1) idx = page.columnCount - 1;
        return idx;
    }

    // ─── 텍스트 분석 ───────────────────────────────────────

    private static String getFirstLineText(List<ParagraphInfo> paragraphs) {
        if (paragraphs.isEmpty()) return "";
        List<InlineItemInfo> items = paragraphs.get(0).items;
        StringBuilder sb = new StringBuilder();
        for (InlineItemInfo item : items) {
            if (item.itemType == InlineItemInfo.ItemType.TEXT_RUN && item.text != null) {
                int newlineIdx = item.text.indexOf('\n');
                if (newlineIdx >= 0) {
                    sb.append(item.text, 0, newlineIdx);
                    break;
                }
                sb.append(item.text);
            }
            if (item.itemType == InlineItemInfo.ItemType.BREAK) break;
        }
        String s = sb.toString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** TS {@code NUMBER_PATTERNS} 와 1:1. */
    private static final NumberPattern[] NUMBER_PATTERNS = new NumberPattern[]{
            new NumberPattern("^\\d+\\.\\s", "arabic_dot"),
            new NumberPattern("^\\d+\\s", "arabic_bare"),
            new NumberPattern("^[①②③④⑤⑥⑦⑧⑨⑩]", "circled"),
            new NumberPattern("^\\([가나다라마바사아자차카타파하]\\)", "parenthesized_korean"),
            new NumberPattern("^\\(\\d+\\)", "parenthesized_arabic"),
            // ECMAScript /i 플래그 — Java Pattern.CASE_INSENSITIVE
            new NumberPattern("^[ⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹ][\\.\\)]", "roman", Pattern.CASE_INSENSITIVE),
            new NumberPattern("^[가나다라마바사]\\.\\s", "korean_dot"),
            new NumberPattern("^[㉠㉡㉢㉣㉤㉥㉦㉧㉨㉩]", "circled_korean"),
    };

    private static NumberPrefix detectNumberPrefix(String text) {
        // TS의 trimStart() 와 동일: 앞쪽 공백 제거
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        String trimmed = text.substring(i);
        for (NumberPattern p : NUMBER_PATTERNS) {
            if (p.regex.matcher(trimmed).find()) {
                return new NumberPrefix(true, p.name);
            }
        }
        return new NumberPrefix(false, null);
    }

    // ─── 유틸 ─────────────────────────────────────────────

    private static SemanticTypes.NodeType mapNodeType(BlockInfo.BlockType blockType) {
        switch (blockType) {
            case TEXT_FRAME: return SemanticTypes.NodeType.FRAME;
            case TABLE: return SemanticTypes.NodeType.TABLE;
            case FIGURE: return SemanticTypes.NodeType.FIGURE;
            default: return SemanticTypes.NodeType.FRAME;
        }
    }

    /** 최빈값 — 같은 빈도면 먼저 등장한 값. */
    private static <T> T mode(List<T> arr) {
        if (arr.isEmpty()) return null;
        Map<T, Integer> counts = new HashMap<>();
        int maxCount = 0;
        T maxVal = arr.get(0);
        for (T v : arr) {
            int c = counts.getOrDefault(v, 0) + 1;
            counts.put(v, c);
            if (c > maxCount) {
                maxCount = c;
                maxVal = v;
            }
        }
        return maxVal;
    }

    /** mode<Integer> 별도 (autoboxing 차이 회피). */
    private static Integer modeInt(List<Integer> arr) {
        if (arr.isEmpty()) return null;
        return mode(arr);
    }

    private static class NumberPrefix {
        final boolean hasPrefix;
        final String pattern;
        NumberPrefix(boolean hasPrefix, String pattern) {
            this.hasPrefix = hasPrefix;
            this.pattern = pattern;
        }
    }

    private static class NumberPattern {
        final Pattern regex;
        final String name;
        NumberPattern(String pat, String name) {
            this.regex = Pattern.compile(pat);
            this.name = name;
        }
        NumberPattern(String pat, String name, int flags) {
            this.regex = Pattern.compile(pat, flags);
            this.name = name;
        }
    }
}
