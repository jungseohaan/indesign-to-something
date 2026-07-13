package kr.dogfoot.hwpxlib.tool.idmlconverter.semanticblock;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTPageLayout;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stage 1~5 implementation for Semantic Block Discovery.
 *
 * <p>v1 favors member grouping over block classification. It only reads
 * ASTDocument and does not affect HWPX generation.</p>
 */
public final class SemanticBlockDetector {
    private static final Pattern QUESTION_PREFIX = Pattern.compile(
            "^(\\d+[.)]|\\([0-9]+\\)|[가-힣][.)]|[①-⑳]|[ㄱ-ㅎ][.)])\\s*.*");
    private static final Pattern PAGE_NUMBER_TEXT = Pattern.compile("^[\\s\\-–—]*\\d{1,3}[\\s\\-–—]*$");
    private static final Pattern SOURCE_OR_CAPTION_TEXT = Pattern.compile(
            "^(출처|자료\\s*출처|※|\\*|그림\\s*\\d*|표\\s*\\d*)[\\s:：.)-]?.*");

    private SemanticBlockDetector() {}

    public static SemanticBlockDocument detect(ASTDocument doc, String documentName) {
        return detect(doc, documentName, null);
    }

    public static SemanticBlockDocument detect(ASTDocument doc, String documentName, ResolvedData resolvedData) {
        SemanticBlockDocument out = new SemanticBlockDocument();
        out.source.document_name = documentName;
        out.source.generated_at = Instant.now().toString();

        if (doc == null || doc.sections() == null) {
            return out;
        }

        int readingOrder = 1;
        int anchorCount = 0;
        int unattachedVisuals = 0;
        Map<String, ASTStyleDef> paragraphStyles = buildParagraphStyleIndex(doc);
        DocumentProfile profile = buildDocumentProfile(doc, paragraphStyles);
        SemanticBlockHints hints = SemanticBlockHints.fromResolvedData(resolvedData);
        out.source.semantic_context = hints.hasData() ? "ASTDocument+ResolvedData" : "ASTDocument";

        for (ASTSection section : doc.sections()) {
            if (section == null) continue;
            out.summary.pages++;

            List<Node> nodes = collectPageNodes(section, paragraphStyles, hints);
            Collections.sort(nodes, READING_ORDER);

            List<Candidate> candidates = new ArrayList<Candidate>();
            Candidate current = null;

            for (Node node : nodes) {
                if (node.isIgnorableBackground() || profile.isNoise(node) || hints.shouldSkip(node)) {
                    continue;
                }

                Anchor anchor = detectAnchor(node);
                if (anchor.isAnchor) {
                    current = new Candidate(node, anchor);
                    candidates.add(current);
                    anchorCount++;
                    continue;
                }

                if (node.kind == NodeKind.TEXT || node.kind == NodeKind.TABLE) {
                    if (current == null) {
                        current = new Candidate(node, Anchor.fallback(node));
                        candidates.add(current);
                    } else if (shouldStartFallbackBlock(current, node)) {
                        current = new Candidate(node, Anchor.fallback(node));
                        candidates.add(current);
                    } else {
                        current.add(node, 0.0);
                    }
                }
            }

            if (candidates.isEmpty()) {
                for (Node node : nodes) {
                    if (!node.isIgnorableBackground() && !hints.shouldSkip(node)) {
                        candidates.add(new Candidate(node, Anchor.fallback(node)));
                        break;
                    }
                }
            }

            for (Node node : nodes) {
                if (node.kind != NodeKind.FIGURE || node.isIgnorableBackground()
                        || profile.isNoise(node) || hints.shouldSkip(node)) {
                    continue;
                }
                Candidate target = findVisualTarget(node, candidates, hints);
                if (target == null) {
                    unattachedVisuals++;
                } else {
                    target.add(node, target.lastImageScore);
                }
            }

            for (Candidate candidate : candidates) {
                SemanticBlock block = candidate.toBlock(readingOrder++);
                out.blocks.add(block);
            }
        }

        out.summary.blocks = out.blocks.size();
        out.summary.anchors = anchorCount;
        out.summary.unattached_visuals = unattachedVisuals;
        int members = 0;
        for (SemanticBlock block : out.blocks) {
            members += block.member_ids.size();
        }
        out.summary.members = members;
        return out;
    }

    private static Map<String, ASTStyleDef> buildParagraphStyleIndex(ASTDocument doc) {
        Map<String, ASTStyleDef> index = new HashMap<String, ASTStyleDef>();
        if (doc == null || doc.paragraphStyles() == null) return index;
        for (ASTStyleDef style : doc.paragraphStyles()) {
            if (style == null) continue;
            putStyle(index, style.styleId(), style);
            putStyle(index, style.styleName(), style);
            putStyle(index, normalizeStyleRef(style.styleId()), style);
            putStyle(index, normalizeStyleRef(style.styleName()), style);
        }
        return index;
    }

    private static void putStyle(Map<String, ASTStyleDef> index, String key, ASTStyleDef style) {
        if (key != null && !key.isEmpty()) {
            index.put(key, style);
        }
    }

    private static List<Node> collectPageNodes(ASTSection section, Map<String, ASTStyleDef> paragraphStyles) {
        return collectPageNodes(section, paragraphStyles, SemanticBlockHints.empty());
    }

    private static List<Node> collectPageNodes(ASTSection section, Map<String, ASTStyleDef> paragraphStyles,
                                               SemanticBlockHints hints) {
        List<Node> nodes = new ArrayList<Node>();
        int index = 0;
        SectionProfile sectionProfile = SectionProfile.from(section);
        for (ASTBlock block : section.blocks()) {
            if (block == null) continue;
            Node node = Node.fromBlock(section.pageNumber(), index++, block, paragraphStyles, sectionProfile);
            hints.enrich(node);
            nodes.add(node);
        }
        return nodes;
    }

    private static DocumentProfile buildDocumentProfile(ASTDocument doc, Map<String, ASTStyleDef> paragraphStyles) {
        DocumentProfile profile = new DocumentProfile();
        if (doc == null || doc.sections() == null) return profile;

        HashMap<String, Integer> edgeTextCounts = new HashMap<String, Integer>();
        for (ASTSection section : doc.sections()) {
            if (section == null) continue;
            for (Node node : collectPageNodes(section, paragraphStyles)) {
                if (node.kind != NodeKind.TEXT) continue;
                String text = normalizeText(node.text);
                if (text.length() == 0 || text.length() > 60) continue;
                if (node.isPageEdgeText() || isPageNumberText(text)) {
                    edgeTextCounts.put(text, Integer.valueOf(edgeTextCounts.containsKey(text)
                            ? edgeTextCounts.get(text).intValue() + 1
                            : 1));
                }
            }
        }

        for (Map.Entry<String, Integer> entry : edgeTextCounts.entrySet()) {
            if (entry.getValue().intValue() >= 2) {
                profile.repeatedEdgeTexts.add(entry.getKey());
            }
        }
        return profile;
    }

    private static Anchor detectAnchor(Node node) {
        if (node.kind != NodeKind.TEXT) {
            return Anchor.none();
        }

        double score = 0.0;
        String type = "generic_start";
        String reason = "";

        String style = safe(node.paragraphStyle).toLowerCase();
        String text = normalizeText(node.text);
        String displayStyle = safe(node.representativeStyleName).toLowerCase();
        boolean shortText = text.length() > 0 && text.length() <= 45;
        boolean activitySignal = containsAny(style, "활동")
                || containsAny(displayStyle, "활동")
                || containsAny(text, "활동", "탐구");
        boolean conceptSignal = containsAny(style, "개념", "학습", "목표", "정리")
                || containsAny(displayStyle, "개념", "학습", "목표", "정리")
                || containsAny(text, "학습 목표", "개념", "정리");
        boolean exampleSignal = containsAny(style, "예시")
                || containsAny(displayStyle, "예시")
                || containsAny(text, "예시");

        if (isPageNumberText(text) || isCaptionOrSourceText(style, displayStyle, text)) {
            return Anchor.none();
        }

        if (containsAny(style, "제목", "title", "heading", "소단원", "대단원", "단원")) {
            score += 0.45;
            reason = appendReason(reason, "paragraph_style_title");
        }
        if (containsAny(style, "학습", "목표", "개념", "활동", "문제", "보기")) {
            score += 0.30;
            reason = appendReason(reason, "paragraph_style_semantic");
        }
        if (containsAny(displayStyle, "제목", "title", "heading", "소단원", "대단원", "단원",
                "학습", "목표", "개념", "활동", "문제", "보기")) {
            score += 0.25;
            reason = appendReason(reason, "representative_style_semantic");
        }
        if (containsAny(text, "학습 목표", "활동", "읽기 전", "읽기 중", "읽기 후",
                "탐구", "정리", "예시")) {
            score += 0.30;
            reason = appendReason(reason, "keyword");
        }
        if (containsAny(text, "문제", "생각", "보기")) {
            score += 0.15;
            reason = appendReason(reason, "weak_keyword");
        }
        if (shortText && (activitySignal || conceptSignal || exampleSignal)) {
            score += 0.10;
            reason = appendReason(reason, "short_semantic_heading");
        }
        if (QUESTION_PREFIX.matcher(text).matches()) {
            score += 0.35;
            type = "question_start";
            reason = appendReason(reason, "question_prefix");
        }
        if (activitySignal) {
            type = "activity_start";
        } else if (conceptSignal) {
            type = "concept_start";
        } else if (exampleSignal) {
            type = "example_start";
        }
        if (node.representativeStyleFontSize >= 1700 && text.length() > 0 && text.length() <= 60) {
            score += 0.35;
            reason = appendReason(reason, "large_heading_font");
        }
        if (shortText && node.height > 0 && node.width > 0) {
            score += 0.15;
            reason = appendReason(reason, "short_text_frame");
        }

        if (score >= 0.445) {
            Anchor anchor = new Anchor();
            anchor.isAnchor = true;
            anchor.score = clamp(score);
            anchor.type = type;
            anchor.reason = reason.length() == 0 ? "heuristic" : reason;
            return anchor;
        }
        return Anchor.none();
    }

    private static Candidate findVisualTarget(Node visual, List<Candidate> candidates, SemanticBlockHints hints) {
        Candidate best = null;
        double bestScore = 0.0;
        for (Candidate candidate : candidates) {
            double score = imageAttachmentScore(visual, candidate, hints);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null && bestScore >= 0.35) {
            best.lastImageScore = bestScore;
            return best;
        }
        return null;
    }

    private static boolean shouldStartFallbackBlock(Candidate current, Node node) {
        if (current == null || node == null || !current.isFallback()) return false;
        if (node.kind != NodeKind.TEXT && node.kind != NodeKind.TABLE) return false;

        long verticalGap = current.bbox == null ? 0 : node.y - current.bbox[3];
        if (verticalGap > 14000) {
            return true;
        }

        if (node.pageWidth > 0 && current.bbox != null) {
            long xGap = Math.abs(node.x - current.bbox[0]);
            boolean columnJump = xGap > (long) (node.pageWidth * 0.35);
            boolean similarBand = node.y < current.bbox[3] + 5000;
            if (columnJump && !current.hasGroup(node.groupId) && !similarBand) {
                return true;
            }
        }

        return false;
    }

    private static double imageAttachmentScore(Node visual, Candidate candidate, SemanticBlockHints hints) {
        double score = 0.0;
        double resolvedScore = hints.relationScore(visual, candidate.memberKeys);
        if (resolvedScore > 0.0) {
            score = Math.max(score, resolvedScore);
        }
        if (visual.groupId != null && candidate.hasGroup(visual.groupId)) {
            score = Math.max(score, 0.95);
        }
        if (overlapsOrContains(candidate.bbox, visual.bbox)) {
            score = Math.max(score, 0.70);
        }
        if (isNear(candidate.bbox, visual.bbox)) {
            score = Math.max(score, 0.55);
        }
        if (candidate.anchor != null && "question_start".equals(candidate.anchor.type)) {
            score -= 0.10;
        }
        candidate.lastResolvedRelationScore = resolvedScore;
        return clamp(score);
    }

    private static boolean overlapsOrContains(long[] a, long[] b) {
        if (a == null || b == null) return false;
        long overlapX = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        long overlapY = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        return overlapX > 0 && overlapY > 0;
    }

    private static boolean isNear(long[] a, long[] b) {
        if (a == null || b == null) return false;
        long gapX = Math.max(0, Math.max(a[0], b[0]) - Math.min(a[2], b[2]));
        long gapY = Math.max(0, Math.max(a[1], b[1]) - Math.min(a[3], b[3]));
        long maxNear = 18000; // roughly 50mm in HWPUNIT; review-oriented first pass.
        return gapX <= maxNear && gapY <= maxNear;
    }

    private static boolean isPageNumberText(String text) {
        return text != null && PAGE_NUMBER_TEXT.matcher(text).matches();
    }

    private static boolean isCaptionOrSourceText(String style, String displayStyle, String text) {
        String joinedStyle = safe(style) + " " + safe(displayStyle);
        if (containsAny(joinedStyle, "캡션", "caption", "출처", "쪽번호", "페이지번호", "머리말", "꼬리말")) {
            return true;
        }
        return text != null && SOURCE_OR_CAPTION_TEXT.matcher(text).matches()
                && !containsAny(text, "자료 살펴보기", "자료 읽기", "자료 탐구");
    }

    private static String extractText(ASTTextFrameBlock tf) {
        StringBuilder sb = new StringBuilder();
        if (tf.paragraphs() == null) return "";
        for (ASTParagraph paragraph : tf.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) sb.append(text);
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String firstParagraphStyle(ASTTextFrameBlock tf) {
        if (tf.paragraphs() == null || tf.paragraphs().isEmpty()) return null;
        ASTParagraph paragraph = tf.paragraphs().get(0);
        return paragraph != null ? paragraph.paragraphStyleRef() : null;
    }

    private static StyleCandidate largestParagraphStyle(ASTTextFrameBlock tf, Map<String, ASTStyleDef> styles) {
        StyleCandidate best = null;
        if (tf.paragraphs() == null) return null;
        for (ASTParagraph paragraph : tf.paragraphs()) {
            if (paragraph == null) continue;
            String ref = paragraph.paragraphStyleRef();
            if (ref == null || ref.isEmpty()) continue;
            ASTStyleDef style = resolveStyle(styles, ref);
            String name = style != null && style.styleName() != null && !style.styleName().isEmpty()
                    ? style.styleName()
                    : normalizeStyleRef(ref);
            int fontSize = style != null && style.fontSizeHwpunits() != null
                    ? style.fontSizeHwpunits()
                    : maxRunFontSize(paragraph);
            if (fontSize <= 0) fontSize = 0;
            if (best == null || fontSize > best.fontSizeHwpunits) {
                best = new StyleCandidate(name, fontSize);
            }
        }
        return best;
    }

    private static ASTStyleDef resolveStyle(Map<String, ASTStyleDef> styles, String ref) {
        if (styles == null || ref == null) return null;
        ASTStyleDef style = styles.get(ref);
        if (style != null) return style;
        return styles.get(normalizeStyleRef(ref));
    }

    private static String normalizeStyleRef(String ref) {
        if (ref == null) return null;
        int slash = ref.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < ref.length()) {
            return ref.substring(slash + 1);
        }
        return ref;
    }

    private static int maxRunFontSize(ASTParagraph paragraph) {
        int max = 0;
        if (paragraph.items() == null) return max;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                Integer size = ((ASTTextRun) item).fontSizeHwpunits();
                if (size != null && size > max) max = size;
            }
        }
        return max;
    }

    private static void collectInlineIds(ASTTextFrameBlock tf, Set<String> out) {
        if (tf.paragraphs() == null) return;
        for (ASTParagraph paragraph : tf.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTInlineObject) {
                    ASTInlineObject obj = (ASTInlineObject) item;
                    if (obj.sourceId() != null && !obj.sourceId().isEmpty()) {
                        out.add(obj.sourceId());
                    }
                    if (obj.overlayFrames() != null) {
                        for (ASTInlineObject overlay : obj.overlayFrames()) {
                            if (overlay != null && overlay.sourceId() != null && !overlay.sourceId().isEmpty()) {
                                out.add(overlay.sourceId());
                            }
                        }
                    }
                }
            }
        }
    }

    private static String normalizeText(String text) {
        return safe(text).replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        String normalizedValue = value.toLowerCase();
        for (String needle : needles) {
            if (needle != null && normalizedValue.contains(needle.toLowerCase())) return true;
        }
        return false;
    }

    private static String appendReason(String reason, String next) {
        return reason == null || reason.isEmpty() ? next : reason + "," + next;
    }

    private static Set<String> sourceKeyVariants(String id) {
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        if (id == null || id.isEmpty()) return keys;
        addSourceKey(keys, id);

        String base = id;
        int groupSplit = base.indexOf("_g");
        if (groupSplit > 0) {
            addSourceKey(keys, base.substring(0, groupSplit));
        }
        addPrefixedNumericKey(keys, base, "page_obj_");
        addPrefixedNumericKey(keys, base, "page_object_");
        addPrefixedNumericKey(keys, base, "page_object_ov_");
        addPrefixedNumericKey(keys, base, "page_object_lv_");
        if (base.startsWith("page_obj_") && base.endsWith("_ov")) {
            addNumericKey(keys, base.substring("page_obj_".length(), base.length() - "_ov".length()));
        }
        if (base.startsWith("page_obj_") && base.endsWith("_ov_prev")) {
            addNumericKey(keys, base.substring("page_obj_".length(), base.length() - "_ov_prev".length()));
        }
        return keys;
    }

    private static void addPrefixedNumericKey(Set<String> keys, String value, String prefix) {
        if (value != null && value.startsWith(prefix)) {
            addNumericKey(keys, value.substring(prefix.length()));
        }
    }

    private static void addNumericKey(Set<String> keys, String value) {
        if (value == null || value.isEmpty()) return;
        try {
            int n = Integer.parseInt(value);
            addSourceKey(keys, String.valueOf(n));
            addSourceKey(keys, "u" + Integer.toHexString(n));
        } catch (NumberFormatException ignored) {
            addSourceKey(keys, value);
        }
    }

    private static void addSourceKey(Set<String> keys, String value) {
        if (value == null || value.isEmpty()) return;
        keys.add(value);
        if (value.startsWith("u") && value.length() > 1) {
            String hex = value.substring(1);
            int suffix = hex.indexOf('_');
            if (suffix >= 0) hex = hex.substring(0, suffix);
            try {
                keys.add(String.valueOf(Integer.parseInt(hex, 16)));
            } catch (NumberFormatException ignored) {
                // keep original key only
            }
        } else {
            try {
                int n = Integer.parseInt(value);
                keys.add("u" + Integer.toHexString(n));
            } catch (NumberFormatException ignored) {
                // keep original key only
            }
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static double clamp(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }

    private static long[] union(long[] a, long[] b) {
        if (a == null) return b == null ? null : new long[] { b[0], b[1], b[2], b[3] };
        if (b == null) return a;
        return new long[] {
                Math.min(a[0], b[0]),
                Math.min(a[1], b[1]),
                Math.max(a[2], b[2]),
                Math.max(a[3], b[3])
        };
    }

    private static final Comparator<Node> READING_ORDER = new Comparator<Node>() {
        public int compare(Node a, Node b) {
            int y = Long.compare(a.y, b.y);
            if (Math.abs(a.y - b.y) > 1800 && y != 0) return y;
            int x = Long.compare(a.x, b.x);
            if (x != 0) return x;
            int z = Integer.compare(a.zOrder, b.zOrder);
            if (z != 0) return z;
            return Integer.compare(a.index, b.index);
        }
    };

    private enum NodeKind {
        TEXT,
        TABLE,
        FIGURE
    }

    private static class Node {
        String id;
        NodeKind kind;
        int page;
        int index;
        long x;
        long y;
        long width;
        long height;
        long[] bbox;
        int zOrder;
        long pageWidth;
        long pageHeight;
        String text;
        String paragraphStyle;
        String representativeStyleName;
        int representativeStyleFontSize;
        String visualLayer;
        String groupId;
        Set<String> sourceKeys = new LinkedHashSet<String>();
        Set<String> nestedMemberIds = new LinkedHashSet<String>();

        static Node fromBlock(int page, int index, ASTBlock block, Map<String, ASTStyleDef> paragraphStyles,
                              SectionProfile sectionProfile) {
            Node n = new Node();
            n.page = page;
            n.index = index;
            n.pageWidth = sectionProfile.pageWidth;
            n.pageHeight = sectionProfile.pageHeight;
            n.id = block.sourceId();
            if (n.id == null || n.id.isEmpty()) {
                n.id = "ast:p" + page + ":b" + index;
            }
            n.sourceKeys.addAll(sourceKeyVariants(n.id));

            if (block instanceof ASTTextFrameBlock) {
                ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
                n.kind = NodeKind.TEXT;
                n.x = tf.effectiveX();
                n.y = tf.y();
                n.width = tf.effectiveWidth();
                n.height = tf.height();
                n.zOrder = tf.zOrder();
                n.text = extractText(tf);
                n.paragraphStyle = firstParagraphStyle(tf);
                StyleCandidate style = largestParagraphStyle(tf, paragraphStyles);
                if (style != null) {
                    n.representativeStyleName = style.name;
                    n.representativeStyleFontSize = style.fontSizeHwpunits;
                }
                collectInlineIds(tf, n.nestedMemberIds);
                for (String nestedId : n.nestedMemberIds) {
                    n.sourceKeys.addAll(sourceKeyVariants(nestedId));
                }
            } else if (block instanceof ASTTable) {
                ASTTable table = (ASTTable) block;
                n.kind = NodeKind.TABLE;
                n.x = table.x();
                n.y = table.y();
                n.width = table.width();
                n.height = table.height();
                n.zOrder = table.zOrder();
            } else if (block instanceof ASTFigure) {
                ASTFigure figure = (ASTFigure) block;
                n.kind = NodeKind.FIGURE;
                n.x = figure.x();
                n.y = figure.y();
                n.width = figure.width();
                n.height = figure.height();
                n.zOrder = figure.zOrder();
                n.visualLayer = figure.visualLayer();
                n.groupId = figure.parentGroupId();
            } else {
                n.kind = NodeKind.FIGURE;
            }
            n.bbox = new long[] { n.x, n.y, n.x + Math.max(0, n.width), n.y + Math.max(0, n.height) };
            return n;
        }

        boolean isIgnorableBackground() {
            return kind == NodeKind.FIGURE
                    && ("PAGE_BACKGROUND".equals(visualLayer) || "CONTAINER_BACKDROP".equals(visualLayer));
        }

        boolean isPageEdgeText() {
            if (kind != NodeKind.TEXT || pageHeight <= 0) return false;
            return y < pageHeight * 0.12 || y + Math.max(0, height) > pageHeight * 0.88;
        }

        SemanticBlock.MemberBox toMemberBox(String overrideId, String role) {
            SemanticBlock.MemberBox box = new SemanticBlock.MemberBox();
            box.id = overrideId != null && !overrideId.isEmpty() ? overrideId : id;
            box.page = page;
            box.bbox = bbox == null ? null : new long[] { bbox[0], bbox[1], bbox[2], bbox[3] };
            box.kind = kind == null ? "unknown" : kind.name().toLowerCase();
            box.role = role;
            box.parent_id = overrideId == null || overrideId.equals(id) ? null : id;
            box.visual_layer = visualLayer;
            box.group_id = groupId;
            return box;
        }
    }

    private static class SectionProfile {
        long pageWidth;
        long pageHeight;

        static SectionProfile from(ASTSection section) {
            SectionProfile profile = new SectionProfile();
            ASTPageLayout layout = section != null ? section.layout() : null;
            if (layout != null) {
                profile.pageWidth = layout.pageWidth();
                profile.pageHeight = layout.pageHeight();
            }
            return profile;
        }
    }

    private static class DocumentProfile {
        Set<String> repeatedEdgeTexts = new HashSet<String>();

        boolean isNoise(Node node) {
            if (node == null || node.kind != NodeKind.TEXT) return false;
            String text = normalizeText(node.text);
            if (text.length() == 0) return false;
            if (isPageNumberText(text)) return true;
            return node.isPageEdgeText() && repeatedEdgeTexts.contains(text);
        }
    }

    private static class SemanticBlockHints {
        private static final SemanticBlockHints EMPTY = new SemanticBlockHints();
        private final Map<String, RenderHint> renderByKey = new HashMap<String, RenderHint>();

        static SemanticBlockHints empty() {
            return EMPTY;
        }

        static SemanticBlockHints fromResolvedData(ResolvedData data) {
            SemanticBlockHints hints = new SemanticBlockHints();
            if (data == null) return hints;
            hints.addAll(data.allRenderedFloatingItems());
            hints.addAll(data.allRenderedGraphicFrames());
            hints.addAll(data.allRenderedImageFrames());
            hints.addAll(data.allRenderedPdfFrames());
            return hints;
        }

        boolean hasData() {
            return !renderByKey.isEmpty();
        }

        void enrich(Node node) {
            RenderHint hint = find(node);
            if (hint == null) return;
            node.sourceKeys.addAll(hint.allKeys);
            if ((node.visualLayer == null || node.visualLayer.isEmpty()) && hint.visualLayer != null) {
                node.visualLayer = hint.visualLayer;
            }
            if ((node.groupId == null || node.groupId.isEmpty()) && hint.groupId != null) {
                node.groupId = hint.groupId;
            }
        }

        boolean shouldSkip(Node node) {
            RenderHint hint = find(node);
            return hint != null && hint.dropVisual && node.kind == NodeKind.FIGURE;
        }

        double relationScore(Node visual, Set<String> candidateMemberKeys) {
            if (visual == null || visual.kind != NodeKind.FIGURE || candidateMemberKeys == null
                    || candidateMemberKeys.isEmpty()) {
                return 0.0;
            }
            RenderHint hint = find(visual);
            if (hint == null) return 0.0;
            if (intersects(candidateMemberKeys, hint.editableTextKeys)) return 0.98;
            if (intersects(candidateMemberKeys, hint.sourceObjectKeys)) return 0.90;
            if (intersects(candidateMemberKeys, hint.allKeys)) return 0.85;
            return 0.0;
        }

        private void addAll(java.util.Collection<RenderedGroup> groups) {
            if (groups == null) return;
            for (RenderedGroup rg : groups) {
                add(rg);
            }
        }

        private void add(RenderedGroup rg) {
            if (rg == null) return;
            RenderHint hint = RenderHint.from(rg);
            for (String key : hint.allKeys) {
                if (!renderByKey.containsKey(key)) {
                    renderByKey.put(key, hint);
                }
            }
        }

        private RenderHint find(Node node) {
            if (node == null || node.sourceKeys == null) return null;
            for (String key : node.sourceKeys) {
                RenderHint hint = renderByKey.get(key);
                if (hint != null) return hint;
            }
            return null;
        }

        private static boolean intersects(Set<String> a, Set<String> b) {
            if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
            Set<String> smaller = a.size() <= b.size() ? a : b;
            Set<String> larger = a.size() <= b.size() ? b : a;
            for (String key : smaller) {
                if (larger.contains(key)) return true;
            }
            return false;
        }
    }

    private static class RenderHint {
        final Set<String> allKeys = new LinkedHashSet<String>();
        final Set<String> editableTextKeys = new LinkedHashSet<String>();
        final Set<String> sourceObjectKeys = new LinkedHashSet<String>();
        String visualLayer;
        String groupId;
        boolean dropVisual;

        static RenderHint from(RenderedGroup rg) {
            RenderHint hint = new RenderHint();
            addAllVariants(hint.allKeys, String.valueOf(rg.id()));
            addAllVariants(hint.allKeys, "u" + Integer.toHexString(rg.id()));
            addAllVariants(hint.allKeys, "page_obj_" + rg.id());
            addAllVariants(hint.allKeys, "page_obj_" + rg.id() + "_ov");
            addAllVariants(hint.allKeys, "page_obj_" + rg.id() + "_ov_prev");
            addAllVariants(hint.allKeys, "page_object_" + rg.id());
            addAllVariants(hint.allKeys, "page_object_ov_" + rg.id());
            addAllVariants(hint.allKeys, "page_object_lv_" + rg.id());
            hint.groupId = "rendered:" + rg.id();
            hint.visualLayer = visualLayerOf(rg);
            hint.dropVisual = Boolean.FALSE.equals(rg.placementAllowed()) || rg.shouldSkipByOwnership();

            if (rg.editableTextFrameIds() != null) {
                for (String id : rg.editableTextFrameIds()) {
                    addAllVariants(hint.editableTextKeys, id);
                }
            }
            if (rg.sourceObjectIds() != null) {
                for (int id : rg.sourceObjectIds()) {
                    addAllVariants(hint.sourceObjectKeys, String.valueOf(id));
                    addAllVariants(hint.sourceObjectKeys, "u" + Integer.toHexString(id));
                }
            }
            hint.allKeys.addAll(hint.editableTextKeys);
            hint.allKeys.addAll(hint.sourceObjectKeys);
            return hint;
        }

        private static void addAllVariants(Set<String> keys, String id) {
            keys.addAll(sourceKeyVariants(id));
        }

        private static String visualLayerOf(RenderedGroup rg) {
            if (rg == null) return null;
            if (rg.visualLayer() != null && !rg.visualLayer().isEmpty()) {
                return rg.visualLayer();
            }
            String type = safe(rg.type());
            String reason = safe(rg.reason());
            if (rg.isPageBackground() || "page_background".equals(type)) return "PAGE_BACKGROUND";
            if ("label_backdrop_group".equals(reason)) return "LABEL_BACKDROP";
            if (reason.contains("outline") || reason.contains("mask")) return "CONTAINER_OUTLINE";
            if (reason.contains("backdrop")) return "CONTAINER_BACKDROP";
            if ("hwpx_tf".equals(rg.textOwner()) && "indesign_png".equals(rg.visualOwner())) {
                return "LABEL_BACKDROP";
            }
            return null;
        }
    }

    private static class StyleCandidate {
        final String name;
        final int fontSizeHwpunits;

        StyleCandidate(String name, int fontSizeHwpunits) {
            this.name = name;
            this.fontSizeHwpunits = fontSizeHwpunits;
        }
    }

    private static class Anchor {
        boolean isAnchor;
        double score;
        String type;
        String reason;

        static Anchor none() {
            Anchor a = new Anchor();
            a.isAnchor = false;
            a.score = 0.0;
            a.type = "none";
            a.reason = "";
            return a;
        }

        static Anchor fallback(Node node) {
            Anchor a = new Anchor();
            a.isAnchor = true;
            a.score = node.kind == NodeKind.TEXT ? 0.40 : 0.20;
            a.type = "generic_start";
            a.reason = "fallback";
            return a;
        }
    }

    private static class Candidate {
        Anchor anchor;
        LinkedHashSet<String> memberIds = new LinkedHashSet<String>();
        LinkedHashSet<String> memberKeys = new LinkedHashSet<String>();
        LinkedHashMap<String, SemanticBlock.MemberBox> memberBoxes =
                new LinkedHashMap<String, SemanticBlock.MemberBox>();
        HashSet<String> groupIds = new HashSet<String>();
        long[] bbox;
        int pageStart;
        int pageEnd;
        String anchorId;
        double maxAnchorScore;
        double maxContainerScore;
        double maxImageScore;
        double maxResolvedRelationScore;
        double lastResolvedRelationScore;
        double lastImageScore;
        int textMembers;
        int visualMembers;
        int tableMembers;
        String displayName;
        int displayNameFontSize;

        Candidate(Node anchorNode, Anchor anchor) {
            this.anchor = anchor;
            this.pageStart = anchorNode.page;
            this.pageEnd = anchorNode.page;
            this.anchorId = anchorNode.id;
            this.maxAnchorScore = anchor.score;
            add(anchorNode, 0.0);
        }

        void add(Node node, double imageScore) {
            memberIds.add(node.id);
            memberKeys.addAll(node.sourceKeys);
            addMemberBox(node.toMemberBox(null, memberRole(node)));
            memberIds.addAll(node.nestedMemberIds);
            for (String nestedId : node.nestedMemberIds) {
                memberKeys.addAll(sourceKeyVariants(nestedId));
                addMemberBox(node.toMemberBox(nestedId, "inline_object"));
            }
            if (node.groupId != null && !node.groupId.isEmpty()) {
                groupIds.add(node.groupId);
                maxContainerScore = Math.max(maxContainerScore, 0.95);
            }
            bbox = union(bbox, node.bbox);
            pageStart = Math.min(pageStart, node.page);
            pageEnd = Math.max(pageEnd, node.page);
            if (node.kind == NodeKind.TEXT) textMembers++;
            if (node.kind == NodeKind.TABLE) tableMembers++;
            if (node.kind == NodeKind.FIGURE) visualMembers++;
            if (node.kind == NodeKind.TEXT
                    && node.representativeStyleName != null
                    && !node.representativeStyleName.isEmpty()
                    && (displayName == null || node.representativeStyleFontSize > displayNameFontSize)) {
                displayName = node.representativeStyleName;
                displayNameFontSize = node.representativeStyleFontSize;
            }
            maxImageScore = Math.max(maxImageScore, imageScore);
            maxResolvedRelationScore = Math.max(maxResolvedRelationScore, lastResolvedRelationScore);
            lastResolvedRelationScore = 0.0;
        }

        boolean hasGroup(String groupId) {
            return groupIds.contains(groupId);
        }

        boolean isFallback() {
            return anchor != null && "fallback".equals(anchor.reason);
        }

        SemanticBlock toBlock(int readingOrder) {
            SemanticBlock block = new SemanticBlock();
            block.id = String.format("sb_%06d", readingOrder);
            block.member_ids.addAll(memberIds);
            block.member_boxes.addAll(memberBoxes.values());
            block.anchor_id = anchorId;
            block.display_name = displayName();
            block.page_start = pageStart;
            block.page_end = pageEnd;
            block.bbox = bbox;
            block.reading_order = readingOrder;
            block.block_type = blockType(anchor);
            block.signals.anchor_score = maxAnchorScore;
            block.signals.container_score = maxContainerScore;
            block.signals.image_score = maxImageScore;
            block.signals.resolved_relation_score = maxResolvedRelationScore;
            block.confidence = confidence();
            block.debug.anchor_type = anchor.type;
            block.debug.anchor_reason = anchor.reason;
            block.debug.text_members = textMembers;
            block.debug.visual_members = visualMembers;
            block.debug.table_members = tableMembers;
            return block;
        }

        private String displayName() {
            if (displayName != null && !displayName.isEmpty()) return displayName;
            if (textMembers == 0) return "그래픽";
            return "텍스트";
        }

        private double confidence() {
            double contentScore = (textMembers + tableMembers) > 0 ? 0.20 : 0.05;
            double score = Math.max(maxAnchorScore, 0.30) + contentScore
                    + Math.min(maxContainerScore, 0.15)
                    + Math.min(maxImageScore, 0.15);
            return clamp(score);
        }

        private void addMemberBox(SemanticBlock.MemberBox box) {
            if (box == null || box.id == null || box.id.isEmpty()) return;
            if (!memberBoxes.containsKey(box.id)) {
                memberBoxes.put(box.id, box);
            }
        }

        private static String memberRole(Node node) {
            if (node == null || node.kind == null) return "unknown";
            switch (node.kind) {
                case TEXT:
                    return "text_frame";
                case TABLE:
                    return "table";
                case FIGURE:
                    if ("LABEL_BACKDROP".equals(node.visualLayer)
                            || "CONTAINER_OUTLINE".equals(node.visualLayer)
                            || "FOREGROUND_MASK".equals(node.visualLayer)) {
                        return "decoration";
                    }
                    return "visual";
                default:
                    return "unknown";
            }
        }

        private static String blockType(Anchor anchor) {
            if (anchor == null || anchor.type == null) return "unknown";
            if ("question_start".equals(anchor.type)) return "question";
            if ("activity_start".equals(anchor.type)) return "activity";
            if ("example_start".equals(anchor.type)) return "example";
            if ("concept_start".equals(anchor.type)) return "concept";
            return "unknown";
        }
    }
}
