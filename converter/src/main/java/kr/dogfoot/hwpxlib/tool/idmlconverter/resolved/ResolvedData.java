package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

/**
 * resolved.json 최상위 컨테이너.
 * InDesign ExtendScript에서 수집한 모든 resolved 데이터.
 */
public class ResolvedData {
    private final Map<String, ResolvedStory> storyMap = new HashMap<>();
    private final Map<String, String> colorHexMap = new HashMap<>();  // colorName → "#RRGGBB"
    private final List<ResolvedTextFrame> textFrames = new ArrayList<>();
    private final Map<String, ResolvedTable> tableMap = new HashMap<>();
    private final List<ResolvedPageItem> pageItems = new ArrayList<>();
    private final Map<String, ResolvedPageItem> pageItemMap = new HashMap<>();  // DOM id → pageItem
    private final List<ResolvedPage> pages = new ArrayList<>();
    private final Map<String, ResolvedPage> pageByName = new HashMap<>();  // page name ("240") → page
    private final Map<String, RenderedGroup> renderedPdfFrameMap = new HashMap<>();  // DOM id → rendered PDF frame
    private final Map<String, RenderedGroup> renderedGraphicFrameMap = new LinkedHashMap<>();  // DOM id → rendered complex graphic (순서 보존)
    private final Map<String, RenderedGroup> renderedImageFrameMap = new HashMap<>();  // DOM id → rendered image frame (PSD, AI 등)
    private final Map<String, RenderedGroup> childImageToGroupMap = new HashMap<>();  // 자식 이미지 DOM id → 부모 그룹 RenderedGroup
    private final Set<Integer> processedImageGroupIds = new HashSet<>();  // 이미 Figure로 변환된 그룹 렌더 ID
    private Set<Integer> inlineObjectDomIds;                     // inline_object로 등록된 DOM id 집합 (Stage 3 조상 검사용)
    private final List<FontMetricEntry> fontMetrics = new ArrayList<>();  // InDesign 폰트 메트릭
    private double scaleFactor = 2.8346;  // resolved 좌표 → pt 변환 스케일
    private final Map<String, FontMetricEntry> fontMetricMap = new HashMap<>();  // family → metric
    private final Set<String> consumedRenderedGraphicIds = new HashSet<>();  // 인라인 처리로 소비된 deco DOM id
    private final List<RenderedGroup> renderedFloatingItems = new ArrayList<>();  // 통합 플로팅 그래픽
    private final Map<String, RenderedGroup> renderedFloatingItemMap = new LinkedHashMap<>();
    private final Set<String> indesignPngTextOwnerFrameIds = new HashSet<>();
    private final Set<String> atomicIndesignPngTextOwnerFrameIds = new HashSet<>();
    private Set<String> editableTextFrameIds;  // 배경에서 숨겨진 TextFrame DOM ID
    private String basePath;  // resolved.json 부모 디렉토리 경로
    private final Map<String, String> paragraphStyleJustMap = new HashMap<>();  // styleName → justification (top-level paragraphStyles)

    public String basePath() { return basePath; }
    public void basePath(String path) { this.basePath = path; }

    /** top-level paragraphStyles의 name → justification 매핑 추가 */
    public void addParagraphStyleJustification(String name, String justification) {
        if (name != null && justification != null) {
            paragraphStyleJustMap.put(name, justification);
        }
    }

    /** top-level paragraphStyles에서 스타일 이름으로 justification 조회 */
    public String getParagraphStyleJustification(String styleName) {
        return paragraphStyleJustMap.get(styleName);
    }

    /** top-level paragraphStyles justification 맵 전체 반환 */
    public Map<String, String> paragraphStyleJustMap() { return paragraphStyleJustMap; }

    public Set<String> editableTextFrameIds() { return editableTextFrameIds; }
    public void editableTextFrameIds(Set<String> v) { this.editableTextFrameIds = v; }
    public boolean isEditableTextFrame(String domId) {
        return editableTextFrameIds != null && editableTextFrameIds.contains(domId);
    }

    /**
     * PNG export에서 텍스트를 숨기고 HWPX TF가 텍스트를 소유하도록 선언된 프레임.
     *
     * 일부 inline child TF는 resolved.json 최상위 editableTextFrameIds에는 빠지지만,
     * renderedFloatingItems[].editableTextFrameIds + textOwner=hwpx_tf에는 정확히 기록된다.
     * 이 경우 최상위 editable 목록보다 textOwner 계약을 우선해야 PNG/TF 양쪽에서 모두
     * 텍스트가 사라지는 일을 막을 수 있다.
     */
    public boolean isHwpxOwnedTextFrame(String domId) {
        return hasHwpxTextOwnerRenderForFrame(domId);
    }

    public void addStory(ResolvedStory story) {
        storyMap.put(story.id(), story);
    }

    public ResolvedStory getStory(String storyId) {
        return storyMap.get(storyId);
    }

    public java.util.Set<String> allStoryIds() {
        return storyMap.keySet();
    }

    /**
     * 색상 이름 → hex 매핑 추가.
     */
    public void addColor(String name, String hex) {
        if (name != null && hex != null) {
            putColorAlias(name, hex);
            putColorAlias("Color/" + name, hex);
            putColorAlias("Swatch/" + name, hex);
            if (name.startsWith("#") && name.length() > 1) {
                putColorAlias(name.substring(1), hex);
            } else {
                putColorAlias("#" + name, hex);
            }
        }
    }

    private void putColorAlias(String name, String hex) {
        if (name != null && !name.isEmpty()) {
            colorHexMap.put(name, hex);
        }
    }

    /**
     * 색상 이름으로 hex 조회.
     * resolved.json의 colors[] 배열에서 빌드한 매핑.
     */
    public String resolveColorHex(String colorName) {
        if (colorName == null) return null;
        String hex = colorHexMap.get(colorName);
        if (hex != null) return hex;
        String name = normalizeColorName(colorName);
        if (name != null && !name.equals(colorName)) {
            hex = colorHexMap.get(name);
            if (hex != null) return hex;
        }
        hex = resolveTintedMappedColor(name);
        if (hex != null) return hex;
        // 기본 색상 폴백 (resolved.json colors 배열에 누락된 경우)
        if ("Paper".equals(name)) return "#FFFFFF";
        if ("Black".equals(name)) return "#000000";
        if ("검정".equals(name)) return "#000000";
        if ("Text_Color".equals(name) || "Text Color".equals(name)
                || "$ID/Text_Color".equals(name) || "$ID/Text Color".equals(name)) return "#000000";
        if ("White".equals(name)) return "#FFFFFF";
        if ("흰색".equals(name)) return "#FFFFFF";
        hex = resolveTintedNamedColor(name);
        if (hex != null) return hex;
        hex = resolveCmykColorHex(name);
        if (hex != null) return hex;
        return null;
    }

    private static String normalizeColorName(String colorName) {
        String name = colorName;
        if (name.startsWith("Color/")) name = name.substring("Color/".length());
        if (name.startsWith("Swatch/")) name = name.substring("Swatch/".length());
        if (name.startsWith("Tint/")) name = name.substring("Tint/".length());
        if (name.contains("%25")) name = name.replace("%25", "%");
        if (name.startsWith("#") && name.length() > 1) name = name.substring(1);
        return name;
    }

    private String resolveTintedMappedColor(String name) {
        if (name == null || name.indexOf('%') < 0) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\[?([^\\]]+)\\]?\\s+(\\d+\\.?\\d*)%$")
                .matcher(name.trim());
        if (!m.find()) return null;
        String base = m.group(1).trim();
        double tint;
        try {
            tint = Double.parseDouble(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        String baseHex = colorHexMap.get(base);
        if (baseHex == null && base.startsWith("#") && base.length() > 1) {
            baseHex = colorHexMap.get(base.substring(1));
        }
        if (baseHex == null && !base.startsWith("#")) {
            baseHex = colorHexMap.get("#" + base);
        }
        if (baseHex == null) return null;
        return ColorResolver.applyTintToHex(baseHex, tint);
    }

    private static String resolveTintedNamedColor(String name) {
        if (name == null || name.indexOf('%') < 0) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\[?([^\\]]+)\\]?\\s+(\\d+\\.?\\d*)%$")
                .matcher(name.trim());
        if (!m.find()) return null;
        String base = m.group(1).trim();
        double tint;
        try {
            tint = Double.parseDouble(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        String baseHex = null;
        if ("Black".equals(base) || "검정".equals(base)) baseHex = "#000000";
        else if ("Paper".equals(base) || "White".equals(base) || "흰색".equals(base)) baseHex = "#FFFFFF";
        if (baseHex == null) return null;
        return ColorResolver.applyTintToHex(baseHex, Math.max(0.0, Math.min(100.0, tint)));
    }

    private static String resolveCmykColorHex(String name) {
        if (name == null || !name.contains("C=") || !name.contains("M=")) return null;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("C=(\\d+\\.?\\d*)\\s+M=(\\d+\\.?\\d*)\\s+Y=(\\d+\\.?\\d*)\\s+K=(\\d+\\.?\\d*)")
                    .matcher(name);
            if (!m.find()) return null;
            double c = Double.parseDouble(m.group(1)) / 100.0;
            double mm = Double.parseDouble(m.group(2)) / 100.0;
            double y = Double.parseDouble(m.group(3)) / 100.0;
            double k = Double.parseDouble(m.group(4)) / 100.0;
            int r = (int) (255 * (1 - c) * (1 - k));
            int g = (int) (255 * (1 - mm) * (1 - k));
            int b = (int) (255 * (1 - y) * (1 - k));
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return null;
        }
    }

    public String resolveTintedColorHex(String colorName, double tint) {
        return ColorResolver.applyTintToHex(resolveColorHex(colorName), tint);
    }

    public void addTextFrame(ResolvedTextFrame frame) {
        textFrames.add(frame);
    }

    /**
     * 특정 storyId(10진수)에 속하는 textFrame 목록 조회.
     */
    public List<ResolvedTextFrame> getTextFramesForStory(String storyId) {
        List<ResolvedTextFrame> result = new ArrayList<>();
        for (ResolvedTextFrame tf : textFrames) {
            if (storyId.equals(tf.storyId())) {
                result.add(tf);
            }
        }
        return result;
    }

    public void addTable(ResolvedTable table) {
        tableMap.put(table.id(), table);
    }

    public ResolvedTable getTable(String tableId) {
        return tableMap.get(tableId);
    }

    /**
     * IDML 테이블 ID로 resolved 테이블의 bounds를 조회한다.
     * @param idmlTableId IDML Table Self ID (예: "u9cbi8146")
     * @return [top, left, bottom, right] page-relative (mm 단위), 없으면 null
     */
    public double[] getTableBounds(String idmlTableId) {
        if (idmlTableId == null) return null;
        // IDML table ID → resolved table ID (DOM decimal)
        // IDML: "u9cbi8146" → "i" 뒤의 hex 부분이 DOM ID
        int iIdx = idmlTableId.indexOf('i');
        if (iIdx < 0) return null;
        String hexPart = idmlTableId.substring(iIdx + 1);
        try {
            String decimalId = String.valueOf(Integer.parseInt(hexPart, 16));
            ResolvedTable rt = tableMap.get(decimalId);
            if (rt != null && rt.bounds() != null) {
                return rt.bounds();
            }
        } catch (NumberFormatException e) {}
        return null;
    }

    /**
     * IDML Table의 실제 page placement bounds를 조회한다.
     *
     * 우선순위:
     * 1. table-only owner TextFrame bounds가 있으면 그것을 사용한다.
     * 2. 없으면 resolved table bounds를 사용한다.
     *
     * IDML Table은 Story XML 안에만 있고 page 좌표를 직접 갖지 않는 경우가 많다.
     * 이때 table content의 좌표 소유자는 Table 자체가 아니라 그것을 담는 TextFrame이다.
     * resolved table bounds는 paragraph/story 기반으로 추정된 보조값일 수 있으므로,
     * marker-only/table-only TextFrame이 명확하면 그 frame이 placement owner다.
     *
     * @return [top, left, bottom, right] page-relative, resolved 좌표 단위(mm), 없으면 null
     */
    public double[] getTablePlacementBounds(String idmlTableId) {
        ResolvedTextFrame owner = getTableOwnerTextFrame(idmlTableId);
        if (owner != null && isMarkerOnlyTextFrame(owner)) {
            double[] ownerBounds = tableOwnerPlacementBounds(owner);
            if (validBounds(ownerBounds)) return ownerBounds;
        }

        double[] tableBounds = getTableBounds(idmlTableId);
        if (validBounds(tableBounds)) return tableBounds;
        return null;
    }

    /**
     * IDML Table의 page placement owner를 조회한다.
     *
     * Spread를 가로지르는 table-only frame은 InDesign export상 오른쪽 페이지
     * 객체로 귀속될 수 있지만, frame geometry는 spread 시작 페이지 기준으로
     * 배치된다. 이 경우 Table content의 owner page도 frame이 시작되는 페이지다.
     */
    public Integer getTablePlacementPageIndex(String idmlTableId) {
        ResolvedTextFrame owner = getTableOwnerTextFrame(idmlTableId);
        if (owner == null || !isMarkerOnlyTextFrame(owner)) return null;
        if (startsOnPreviousSpreadPage(owner)) {
            return Math.max(0, owner.pageIndex() - 1);
        }
        return owner.pageIndex();
    }

    private double[] tableOwnerPlacementBounds(ResolvedTextFrame owner) {
        if (owner == null) return null;
        double[] pageRelative = owner.pageRelativeBounds();
        if (validBounds(pageRelative) && !isImplausiblyOffPageTableBounds(owner, pageRelative)) {
            return pageRelative;
        }
        double[] geometric = owner.geometricBounds();
        if (validBounds(geometric) && looksLikePageLocalTableGeometry(owner, geometric)) {
            return pointBoundsToResolvedUnits(geometric);
        }
        double[] converted = textFrameGeometricBoundsToPageRelativeResolvedUnits(owner);
        if (validBounds(converted)) return converted;
        return validBounds(pageRelative) ? pageRelative : null;
    }

    private boolean isImplausiblyOffPageTableBounds(ResolvedTextFrame owner, double[] bounds) {
        ResolvedPage page = owner != null ? getPage(owner.pageIndex()) : null;
        if (page == null || page.width() <= 0 || bounds == null || bounds.length < 4) return false;
        double pageWidth = page.width();
        double left = bounds[1];
        double right = bounds[3];
        return left < -pageWidth * 0.25 && right > 0 && right <= pageWidth * 1.25;
    }

    private boolean startsOnPreviousSpreadPage(ResolvedTextFrame owner) {
        ResolvedPage page = owner != null ? getPage(owner.pageIndex()) : null;
        if (page == null || page.width() <= 0 || page.bounds() == null || page.bounds().length < 4) {
            return false;
        }
        if (owner.pageIndex() <= 0 || page.bounds()[1] <= 1.0) return false;
        double[] rel = owner.pageRelativeBounds();
        double[] geometric = owner.geometricBounds();
        if (!validBounds(rel) || !validBounds(geometric)) return false;

        double pageWidth = page.width();
        double relLeft = rel[1];
        double relRight = rel[3];
        double geoLeft = geometric[1];
        double geoRight = geometric[3];

        return relLeft < -pageWidth * 0.25
                && relRight > 0
                && geoLeft >= 0
                && geoLeft < pageWidth
                && geoRight > page.bounds()[1];
    }

    private boolean looksLikePageLocalTableGeometry(ResolvedTextFrame owner, double[] bounds) {
        ResolvedPage page = owner != null ? getPage(owner.pageIndex()) : null;
        if (page == null || page.width() <= 0 || bounds == null || bounds.length < 4) return false;
        double pageWidth = page.width();
        double left = bounds[1];
        double width = bounds[3] - bounds[1];
        return left >= 0 && left < pageWidth && width > pageWidth * 0.75;
    }

    private double[] pointBoundsToResolvedUnits(double[] bounds) {
        if (!validBounds(bounds)) return null;
        double scale = scaleFactor > 0 ? scaleFactor : 1.0;
        return new double[] {
                bounds[0] / scale,
                bounds[1] / scale,
                bounds[2] / scale,
                bounds[3] / scale
        };
    }

    /**
     * IDML Table이 속한 Story를 배치하는 TextFrame을 찾는다.
     *
     * Table id는 보통 "u{storyHex}i{tableHex}" 형태다. Story id를 통해
     * TextFrame 후보를 찾고, object marker만 가진 table-only frame을 우선한다.
     */
    public ResolvedTextFrame getTableOwnerTextFrame(String idmlTableId) {
        String storyId = idmlTableOwnerStoryDomId(idmlTableId);
        if (storyId == null) return null;
        List<ResolvedTextFrame> candidates = getTextFramesForStory(storyId);
        if (candidates.isEmpty()) return null;

        ResolvedTextFrame best = null;
        for (ResolvedTextFrame tf : candidates) {
            if (tf == null || tf.onHiddenLayer() || tf.nonprinting()) continue;
            if (best == null) {
                best = tf;
                continue;
            }
            boolean tfMarker = isMarkerOnlyTextFrame(tf);
            boolean bestMarker = isMarkerOnlyTextFrame(best);
            if (tfMarker && !bestMarker) {
                best = tf;
                continue;
            }
            if (tfMarker == bestMarker && textFrameComesBefore(tf, best)) {
                best = tf;
            }
        }
        return best;
    }

    private String idmlTableOwnerStoryDomId(String idmlTableId) {
        if (idmlTableId == null || idmlTableId.length() < 2 || idmlTableId.charAt(0) != 'u') return null;
        int iIdx = idmlTableId.indexOf('i');
        if (iIdx <= 1) return null;
        String storyHex = idmlTableId.substring(1, iIdx);
        try {
            return String.valueOf(Integer.parseInt(storyHex, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isMarkerOnlyTextFrame(ResolvedTextFrame tf) {
        if (tf == null) return false;
        String text = tf.frameVisibleText();
        if (text == null || text.isEmpty()) return true;
        String normalized = text
                .replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .replace("\u0003", "")
                .replace("\u0007", "")
                .replace("\u0008", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        return normalized.isEmpty();
    }

    private boolean textFrameComesBefore(ResolvedTextFrame a, ResolvedTextFrame b) {
        if (a == null) return false;
        if (b == null) return true;
        if (a.pageIndex() != b.pageIndex()) return a.pageIndex() < b.pageIndex();
        double[] ab = bestTextFrameBounds(a);
        double[] bb = bestTextFrameBounds(b);
        if (ab == null || bb == null) return false;
        if (Math.abs(ab[0] - bb[0]) > 0.01) return ab[0] < bb[0];
        return ab[1] < bb[1];
    }

    private double[] bestTextFrameBounds(ResolvedTextFrame tf) {
        if (tf == null) return null;
        if (validBounds(tf.pageRelativeBounds())) return tf.pageRelativeBounds();
        return tf.geometricBounds();
    }

    private double[] textFrameGeometricBoundsToPageRelativeResolvedUnits(ResolvedTextFrame tf) {
        if (tf == null || !validBounds(tf.geometricBounds())) return null;
        ResolvedPage page = getPage(tf.pageIndex());
        if (page == null || page.bounds() == null || page.bounds().length < 4) return null;
        double[] rel = page.toPageRelative(tf.geometricBounds());
        if (rel == null || rel.length < 2) return null;
        double scale = scaleFactor > 0 ? scaleFactor : 1.0;
        double top = rel[1] / scale;
        double left = rel[0] / scale;
        double height = (tf.geometricBounds()[2] - tf.geometricBounds()[0]) / scale;
        double width = (tf.geometricBounds()[3] - tf.geometricBounds()[1]) / scale;
        if (width <= 0 || height <= 0) return null;
        return new double[] { top, left, top + height, left + width };
    }

    private static boolean validBounds(double[] bounds) {
        return bounds != null && bounds.length >= 4
                && Double.isFinite(bounds[0])
                && Double.isFinite(bounds[1])
                && Double.isFinite(bounds[2])
                && Double.isFinite(bounds[3])
                && bounds[2] > bounds[0]
                && bounds[3] > bounds[1];
    }

    // --- PageItem ---

    public void addPageItem(ResolvedPageItem item) {
        pageItems.add(item);
        if (item.id() != null) {
            pageItemMap.put(item.id(), item);
        }
    }

    public List<ResolvedPageItem> pageItems() { return pageItems; }

    /** DOM decimal ID로 조회 */
    public ResolvedPageItem getPageItem(String domId) {
        return pageItemMap.get(domId);
    }

    /**
     * IDML hex ID ("u1735") → DOM decimal ID ("5941") 변환 후 조회.
     */
    public ResolvedPageItem getPageItemByIdmlId(String idmlId) {
        if (idmlId == null || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return null;
        try {
            String decimalId = String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
            return pageItemMap.get(decimalId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * ancestorId를 루트로 최대 maxDepth 깊이까지 자손 ID 집합을 구축한다.
     * tryInlineGroupAsBoxList 등에서 O(P×depth) 반복 순회 대신 O(1) 조회를 가능하게 한다.
     */
    public java.util.Set<String> buildDescendantSet(String ancestorId, int maxDepth) {
        java.util.Set<String> result = new java.util.HashSet<>();
        if (ancestorId == null || maxDepth <= 0) return result;
        // 자식 목록 인덱스: parentId → children (pageItems 한 번 순회)
        java.util.Map<String, java.util.List<String>> childMap = new java.util.HashMap<>();
        for (ResolvedPageItem pi : pageItems) {
            if (pi == null || pi.parentId() == null) continue;
            childMap.computeIfAbsent(pi.parentId(), k -> new java.util.ArrayList<>()).add(pi.id());
        }
        // BFS
        java.util.Queue<String> queue = new java.util.ArrayDeque<>();
        queue.add(ancestorId);
        java.util.Queue<String> nextLevel = new java.util.ArrayDeque<>();
        for (int d = 0; d < maxDepth && !queue.isEmpty(); d++) {
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                java.util.List<String> children = childMap.get(cur);
                if (children != null) {
                    for (String child : children) {
                        if (result.add(child)) nextLevel.add(child);
                    }
                }
            }
            queue.addAll(nextLevel);
            nextLevel.clear();
        }
        return result;
    }

    /**
     * itemId의 조상 체인을 최대 maxHops 단계까지 순회하여 ancestorId가 있으면 true.
     * FramePlacer / InlineFrameHandler 전역에서 반복되는 "5 hop 부모 체인 순회" 패턴의 공통 구현.
     */
    public boolean isDescendantOf(String itemId, String ancestorId, int maxHops) {
        if (itemId == null || ancestorId == null) return false;
        ResolvedPageItem pi = pageItemMap.get(itemId);
        if (pi == null) return false;
        String cur = pi.parentId();
        for (int h = 0; h < maxHops && cur != null; h++) {
            if (ancestorId.equals(cur)) return true;
            ResolvedPageItem next = pageItemMap.get(cur);
            if (next == null) break;
            cur = next.parentId();
        }
        return false;
    }

    // --- Page ---

    public void addPage(ResolvedPage page) {
        pages.add(page);
        if (page.name() != null) {
            pageByName.put(page.name(), page);
        }
    }

    public List<ResolvedPage> pages() { return pages; }

    public ResolvedPage getPage(int index) {
        if (index < 0 || index >= pages.size()) return null;
        return pages.get(index);
    }

    /** 페이지 이름(실제 페이지 번호 문자열, "240")으로 조회 */
    public ResolvedPage getPageByName(String name) {
        return pageByName.get(name);
    }

    // --- FontMetrics ---

    public void addFontMetric(FontMetricEntry entry) {
        fontMetrics.add(entry);
        if (entry.family() != null) {
            fontMetricMap.put(entry.family(), entry);
        }
    }

    public List<FontMetricEntry> fontMetrics() { return fontMetrics; }
    public double scaleFactor() { return scaleFactor; }

    // --- RenderedFloatingItem (통합 플로팅 그래픽) ---

    public void addRenderedFloatingItem(RenderedGroup item) {
        if (isExactRenderedItemDuplicate(item)) {
            return;
        }
        if (isMasterInlineTitleFallbackDuplicate(item)) {
            return;
        }
        if (isLiveTitleDecorationDuplicate(item)) {
            return;
        }
        if (isDuplicateVisualPageObject(item)) {
            return;
        }
        String key = String.valueOf(item.id());
        RenderedGroup existing = renderedFloatingItemMap.get(key);
        if (existing != null && existing.childIds() != null && item.childIds() == null) {
            item.childIds(existing.childIds());
        }
        renderedFloatingItemMap.put(key, item);
        renderedFloatingItems.add(item);
        registerIndesignPngTextOwner(item);
        unregisterEditableLabelTextOwnersCoveredByShell(item);
    }

    public List<RenderedGroup> allRenderedFloatingItems() { return renderedFloatingItems; }

    private boolean isExactRenderedItemDuplicate(RenderedGroup item) {
        if (item == null) return true;
        for (RenderedGroup existing : renderedFloatingItems) {
            if (existing == null) continue;
            if (existing.id() != item.id()) continue;
            if (existing.pageIndex() != item.pageIndex()) continue;
            if (!safeEquals(existing.type(), item.type())) continue;
            if (!safeEquals(existing.itemType(), item.itemType())) continue;
            if (!safeEquals(existing.file(), item.file())) continue;
            if (!safeEquals(existing.reason(), item.reason())) continue;
            if (!sameBounds(existing.bounds(), item.bounds(), 0.05)) continue;
            if (!sameSourceObjectIds(existing.sourceObjectIds(), item.sourceObjectIds())) continue;
            return true;
        }
        return false;
    }

    private boolean isMasterInlineTitleFallbackDuplicate(RenderedGroup item) {
        if (!isMasterFallbackGraphic(item)) return false;
        if (!isSmallGraphic(item.bounds(), 24.0, 18.0)) return false;
        if (!overlapsMasterTitleInlineAnchor(item)) return false;
        for (RenderedGroup existing : renderedFloatingItems) {
            if (!isNonFallbackMasterGraphic(existing)) continue;
            if (existing.pageIndex() != item.pageIndex()) continue;
            if (!containsBounds(existing.bounds(), item.bounds(), 0.75)) continue;
            if (!containsAllSourceObjectIds(existing.sourceObjectIds(), item.sourceObjectIds())) continue;
            return true;
        }
        return false;
    }

    private static boolean isMasterFallbackGraphic(RenderedGroup item) {
        if (item == null || !"master_graphic".equals(item.reason())) return false;
        String file = item.file();
        return file != null && file.contains("_fallback_");
    }

    private static boolean isNonFallbackMasterGraphic(RenderedGroup item) {
        if (item == null || !"master_graphic".equals(item.reason())) return false;
        String file = item.file();
        return file == null || !file.contains("_fallback_");
    }

    private boolean overlapsMasterTitleInlineAnchor(RenderedGroup item) {
        double[] ib = item != null ? item.bounds() : null;
        if (ib == null || ib.length < 4) return false;
        for (ResolvedTextFrame tf : textFrames) {
            if (tf == null || tf.pageIndex() != item.pageIndex()) continue;
            if (!tf.isMasterInstance()) continue;
            String text = tf.frameVisibleText();
            if (text == null || text.indexOf('\uFFFC') < 0) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (!overlaps(ib, tb, 0.5)) continue;
            if (!looksLikeMasterInlineTitleMarker(ib, tb)) continue;
            ResolvedStory story = tf.storyId() != null ? storyMap.get(tf.storyId()) : null;
            if (isMasterTitleStory(story)) return true;
        }
        return false;
    }

    private static boolean isMasterTitleStory(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) {
            return false;
        }
        ResolvedParagraph first = story.paragraphs().get(0);
        String style = first != null ? first.styleName() : null;
        if (style == null) return false;
        return style.contains("단원명")
                || style.contains("단원제목")
                || style.contains("소단원");
    }

    private static boolean looksLikeMasterInlineTitleMarker(double[] itemBounds, double[] titleBounds) {
        if (itemBounds == null || titleBounds == null
                || itemBounds.length < 4 || titleBounds.length < 4) {
            return false;
        }
        double itemW = itemBounds[3] - itemBounds[1];
        double itemH = itemBounds[2] - itemBounds[0];
        double titleW = titleBounds[3] - titleBounds[1];
        double titleH = titleBounds[2] - titleBounds[0];
        if (itemW <= 0 || itemH <= 0 || titleW <= 0 || titleH <= 0) return false;
        return itemW <= Math.max(16.0, titleW * 0.30)
                && itemH <= Math.max(12.0, titleH * 1.60)
                && itemBounds[0] >= titleBounds[0] - titleH * 0.40
                && itemBounds[2] <= titleBounds[2] + titleH * 0.40;
    }

    private static boolean isSmallGraphic(double[] bounds, double maxWidth, double maxHeight) {
        if (bounds == null || bounds.length < 4) return false;
        double w = bounds[3] - bounds[1];
        double h = bounds[2] - bounds[0];
        return w > 0 && h > 0 && w <= maxWidth && h <= maxHeight;
    }

    private static boolean containsBounds(double[] outer, double[] inner, double tolerancePt) {
        if (outer == null || inner == null || outer.length < 4 || inner.length < 4) return false;
        return outer[0] <= inner[0] + tolerancePt
                && outer[1] <= inner[1] + tolerancePt
                && outer[2] >= inner[2] - tolerancePt
                && outer[3] >= inner[3] - tolerancePt;
    }

    private static boolean containsAllSourceObjectIds(int[] superset, int[] subset) {
        if (superset == null || subset == null || superset.length == 0 || subset.length == 0) return false;
        Set<Integer> values = new HashSet<>();
        for (int id : superset) values.add(id);
        for (int id : subset) {
            if (!values.contains(id)) return false;
        }
        return true;
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private boolean isDuplicateVisualPageObject(RenderedGroup item) {
        if (!isVisualOnlyPageObject(item)) return false;
        for (RenderedGroup existing : renderedFloatingItems) {
            if (!isVisualOnlyPageObject(existing)) continue;
            if (existing.pageIndex() != item.pageIndex()) continue;
            if (!sameBounds(existing.bounds(), item.bounds(), 0.05)) continue;
            if (!sameSourceObjectIds(existing.sourceObjectIds(), item.sourceObjectIds())) continue;
            return true;
        }
        return false;
    }

    private boolean isLiveTitleDecorationDuplicate(RenderedGroup item) {
        if (!isVisualOnlyPageObject(item)) return false;
        String reason = item.reason();
        if (reason == null || !(reason.contains("decoration_group")
                || reason.contains("complex_graphic"))) {
            return false;
        }
        double[] ib = item.bounds();
        if (ib == null || ib.length < 4) return false;
        for (ResolvedTextFrame tf : textFrames) {
            if (tf == null || tf.pageIndex() != item.pageIndex()) continue;
            double[] tb = tf.geometricBounds();
            if (!overlaps(ib, tb, 0.5)) continue;
            if (!looksLikeInlineTitleDecoration(ib, tb)) continue;
            ResolvedStory story = tf.storyId() != null ? storyMap.get(tf.storyId()) : null;
            if (!isUnitTitleStory(story)) continue;
            return true;
        }
        return false;
    }

    private static boolean isUnitTitleStory(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) {
            return false;
        }
        ResolvedParagraph first = story.paragraphs().get(0);
        String style = first != null ? first.styleName() : null;
        return "02 단원제목".equals(style);
    }

    private static boolean looksLikeInlineTitleDecoration(double[] itemBounds, double[] titleBounds) {
        if (itemBounds == null || titleBounds == null
                || itemBounds.length < 4 || titleBounds.length < 4) {
            return false;
        }
        double itemW = itemBounds[3] - itemBounds[1];
        double itemH = itemBounds[2] - itemBounds[0];
        double titleW = titleBounds[3] - titleBounds[1];
        double titleH = titleBounds[2] - titleBounds[0];
        if (itemW <= 0 || itemH <= 0 || titleW <= 0 || titleH <= 0) return false;

        // 제목 중복 제거는 TF 내부에 붙은 작은 번호/장식 마커만 대상으로 한다.
        // 페이지 좌상단의 큰 단원 아이콘처럼 제목 TF와 겹치지만 독립 시각 요소인
        // 그래픽은 보존해야 한다.
        return itemW <= titleW * 0.75
                && itemH <= titleH * 1.25
                && itemBounds[1] >= titleBounds[1] - titleW * 0.20
                && itemBounds[0] >= titleBounds[0] - titleH * 0.35
                && itemBounds[2] <= titleBounds[2] + titleH * 0.35;
    }

    private static boolean isVisualOnlyPageObject(RenderedGroup item) {
        if (item == null) return false;
        if (!"page_object".equals(item.type())) return false;
        if (Boolean.TRUE.equals(item.containsEditableText())) return false;
        if ("indesign_png".equals(item.textOwner())) return false;
        return item.sourceObjectIds() != null && item.sourceObjectIds().length > 0;
    }

    private static boolean sameSourceObjectIds(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int[] aa = Arrays.copyOf(a, a.length);
        int[] bb = Arrays.copyOf(b, b.length);
        Arrays.sort(aa);
        Arrays.sort(bb);
        return Arrays.equals(aa, bb);
    }

    private static boolean sameBounds(double[] a, double[] b, double tolerancePt) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(a[i] - b[i]) > tolerancePt) return false;
        }
        return true;
    }

    private static boolean overlaps(double[] a, double[] b, double tolerancePt) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        return a[0] < b[2] + tolerancePt
                && a[2] > b[0] - tolerancePt
                && a[1] < b[3] + tolerancePt
                && a[3] > b[1] - tolerancePt;
    }

    private void registerIndesignPngTextOwner(RenderedGroup item) {
        if (shouldUseCompletePngForSimpleButtonLabel(item)) {
            String[] ids = simpleButtonLabelTextFrameIds(item);
            if (ids == null) return;
            for (String id : ids) {
                if (id != null && !id.isEmpty()) {
                    atomicIndesignPngTextOwnerFrameIds.add(id);
                    indesignPngTextOwnerFrameIds.add(id);
                }
            }
            return;
        }
        if (item == null || !"indesign_png".equals(item.textOwner())) return;
        String[] ids = item.editableTextFrameIds();
        if (ids == null) return;
        if ("visual_marker_label_indesign_png".equals(item.reason())) return;
        if (shouldKeepVisualLabelTextEditable(item)) return;
        if (!allEditableTextFramesAreAtomicMarkers(ids)) return;
        for (String id : ids) {
            if (id != null && !id.isEmpty()) {
                indesignPngTextOwnerFrameIds.add(id);
            }
        }
    }

    /**
     * 해당 TextFrame 텍스트가 InDesign PNG 내부에 이미 포함되어 있는지 확인한다.
     * 이런 TF는 HWPX 글상자로 다시 배치하면 같은 라벨 텍스트가 중복된다.
     */
    public boolean isTextOwnedByIndesignPng(String domId) {
        if (domId != null && atomicIndesignPngTextOwnerFrameIds.contains(domId)) {
            return true;
        }
        if (hasCompletePngForSimpleButtonLabelTextFrame(domId)) {
            return true;
        }
        return domId != null
                && indesignPngTextOwnerFrameIds.contains(domId)
                && !hasHwpxTextOwnerRenderForFrame(domId);
    }

    public boolean hasCompletePngForSimpleButtonLabelTextFrame(String textFrameId) {
        if (textFrameId == null || !isSimpleButtonLabelTextFrame(textFrameId)) return false;
        for (RenderedGroup item : renderedFloatingItems) {
            if (!shouldUseCompletePngForSimpleButtonLabel(item)) continue;
            String[] ids = simpleButtonLabelTextFrameIds(item);
            if (containsString(ids, textFrameId)) return true;
        }
        return false;
    }

    /**
     * 기본 원칙은 editable TF 유지다. 다만 가/나/다/라, ㄱ/ㄴ/ㄷ, 1/2/3, 01/02처럼
     * 내용 의미보다 위치 식별자 성격이 강한 짧은 버튼 라벨은 InDesign에서
     * 완성형 PNG로 추출해도 편집 손실보다 중복/정렬 리스크가 작다.
     * 이 기준은 플로팅/인라인에 동일하게 적용한다.
     */
    public boolean shouldUseCompletePngForSimpleButtonLabel(RenderedGroup item) {
        if (item == null) return false;
        if (!"indesign_png".equals(item.visualOwner())) return false;
        if ("visual_marker_label_indesign_png".equals(item.reason())) {
            if (!"indesign_png".equals(item.textOwner())) return false;
            if (!Boolean.TRUE.equals(item.containsEditableText())) return false;
        } else if ("visual_label_text_hidden_shell".equals(item.reason())) {
            if (!"hwpx_tf".equals(item.textOwner())) return false;
        } else if (!isInlineCompleteSimpleButtonLabelCandidate(item)) {
            return false;
        }
        String[] ids = simpleButtonLabelTextFrameIds(item);
        if (ids == null || ids.length == 0 || ids.length > 2) return false;
        for (String id : ids) {
            ResolvedTextFrame tf = getTextFrame(id);
            if (!isSimpleButtonLabelText(tf)) return false;
        }
        return true;
    }

    private static boolean isInlineCompleteSimpleButtonLabelCandidate(RenderedGroup item) {
        if (item == null) return false;
        if (!"inline_object".equals(item.type()) && !"inline_object".equals(item.itemType())) return false;
        if (!"inline_graphic_only".equals(item.reason())) return false;
        String file = item.file();
        return file != null && file.contains("inline_");
    }

    private String[] simpleButtonLabelTextFrameIds(RenderedGroup item) {
        if (item == null) return null;
        String[] ids = item.editableTextFrameIds();
        if (ids != null && ids.length > 0) return ids;
        int[] sourceIds = item.sourceObjectIds();
        if (sourceIds == null || sourceIds.length == 0) return null;
        List<String> found = new ArrayList<>();
        for (int sourceId : sourceIds) {
            String id = String.valueOf(sourceId);
            ResolvedTextFrame tf = getTextFrame(id);
            if (tf == null || !isSimpleButtonLabelText(tf)) continue;
            if (!found.contains(id)) found.add(id);
            if (found.size() > 2) break;
        }
        return found.toArray(new String[0]);
    }

    public boolean isSimpleButtonLabelTextFrame(String textFrameId) {
        return isSimpleButtonLabelText(getTextFrame(textFrameId));
    }

    public String simpleButtonLabelText(String textFrameId) {
        return simpleButtonLabelText(getTextFrame(textFrameId));
    }

    private static boolean isSimpleButtonLabelText(ResolvedTextFrame tf) {
        return simpleButtonLabelText(tf) != null;
    }

    private static String simpleButtonLabelText(ResolvedTextFrame tf) {
        if (tf == null) return null;
        String text = tf.frameVisibleText();
        if (text == null) return null;
        text = text.replace("\uFFFC", "")
                .replace("\r", "")
                .replace("\n", "")
                .replaceAll("\\s+", "")
                .trim();
        if (text.isEmpty()) return null;
        if (text.matches("\\d{1,2}")) return text;
        if (text.codePointCount(0, text.length()) != 1) return null;
        int cp = text.codePointAt(0);
        if ((cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0x3131 && cp <= 0x318E)) {
            return text;
        }
        return null;
    }

    /**
     * Short visual labels are usually atomic PNGs, but semantic title labels
     * should keep their text editable when the graphic shell can be placed
     * separately. The extractor now avoids marking these as indesign-owned; this
     * guard also repairs older cached resolved.json files.
     */
    public boolean shouldKeepVisualLabelTextEditable(RenderedGroup item) {
        if (item == null) return false;
        if (!"visual_label_indesign_png".equals(item.reason())) return false;
        if (!"indesign_png".equals(item.textOwner())) return false;
        if (!Boolean.TRUE.equals(item.containsEditableText())) return false;
        String[] ids = item.editableTextFrameIds();
        if (ids == null || ids.length == 0) return false;
        for (String id : ids) {
            ResolvedTextFrame tf = getTextFrame(id);
            if (tf == null) continue;
            ResolvedStory story = tf.storyId() != null ? storyMap.get(tf.storyId()) : null;
            String style = firstParagraphStyleName(story);
            if (isSemanticTextFrame(tf, style)) {
                return true;
            }
            if (isShortSemanticLabel(tf) && hasHwpxTextOwnerRenderForFrame(id)) {
                return true;
            }
            if (isShortSemanticLabel(tf) && hasSeparateVisualShellForLabel(item, tf)) {
                return true;
            }
        }
        return false;
    }

    private boolean allEditableTextFramesAreAtomicMarkers(String[] ids) {
        if (ids == null || ids.length == 0) return false;
        for (String id : ids) {
            if (id == null || id.isEmpty()) return false;
            if (!isSimpleButtonLabelText(getTextFrame(id))) return false;
        }
        return true;
    }

    private static String firstParagraphStyleName(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) {
            return null;
        }
        ResolvedParagraph first = story.paragraphs().get(0);
        return first != null ? first.styleName() : null;
    }

    private static boolean isSemanticTextFrame(ResolvedTextFrame tf, String styleName) {
        String text = normalizedText(tf != null ? tf.frameVisibleText() : null);
        if (text.isEmpty()) return false;
        if (simpleButtonLabelText(tf) != null) return false;
        if (hasSemanticStyleName(styleName)) return true;
        if (text.length() > 1) return true;
        return false;
    }

    private static boolean hasSemanticStyleName(String styleName) {
        if (styleName == null || styleName.isBlank()) return false;
        String s = styleName.toLowerCase();
        return s.contains("제목")
                || s.contains("title")
                || s.contains("heading")
                || s.contains("단원")
                || s.contains("소단원")
                || s.contains("표제")
                || s.contains("본문")
                || s.contains("문항")
                || s.contains("출처")
                || s.contains("내용");
    }

    private void unregisterEditableLabelTextOwnersCoveredByShell(RenderedGroup shell) {
        if (!isEditableLabelShellCandidate(shell)) return;
        for (RenderedGroup item : renderedFloatingItems) {
            if (item == null || item == shell) continue;
            if (!"visual_label_indesign_png".equals(item.reason())) continue;
            if (!containsId(item.childIds(), shell.id())) continue;
            if (!shouldKeepVisualLabelTextEditable(item)) continue;
            String[] ids = item.editableTextFrameIds();
            if (ids == null) continue;
            for (String id : ids) {
                if (id != null) indesignPngTextOwnerFrameIds.remove(id);
            }
        }
    }

    private boolean hasSeparateVisualShellForLabel(RenderedGroup label, ResolvedTextFrame tf) {
        if (label == null || tf == null || label.childIds() == null) return false;
        for (RenderedGroup candidate : renderedFloatingItems) {
            if (!isEditableLabelShellCandidate(candidate)) continue;
            if (candidate.pageIndex() != label.pageIndex()) continue;
            if (!containsId(label.childIds(), candidate.id())) continue;
            if (overlapsTextFrame(candidate.bounds(), tf, 0.55)) return true;
        }
        return false;
    }

    private boolean hasHwpxTextOwnerRenderForFrame(String textFrameId) {
        if (textFrameId == null) return false;
        ResolvedTextFrame tf = getTextFrame(textFrameId);
        if (tf == null) return false;
        for (RenderedGroup item : renderedFloatingItems) {
            if (item == null) continue;
            if (item.pageIndex() != tf.pageIndex()) continue;
            if (!"page_object".equals(item.type())) continue;
            if (!"indesign_png".equals(item.visualOwner())) continue;
            if (!"hwpx_tf".equals(item.textOwner())) continue;
            if (!containsString(item.editableTextFrameIds(), textFrameId)) continue;
            String reason = item.reason();
            if (reason == null || !reason.contains("text_hidden")) continue;
            if (!overlapsTextFrame(item.bounds(), tf, 0.75)) continue;
            return true;
        }
        return false;
    }

    private static boolean isEditableLabelShellCandidate(RenderedGroup item) {
        if (!isVisualOnlyPageObject(item)) return false;
        String reason = item.reason();
        if (reason == null || !(reason.contains("decoration")
                || reason.contains("visual_shell")
                || reason.contains("complex_graphic"))) {
            return false;
        }
        double[] b = item.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w >= 8.0 && w <= 90.0
                && h >= 2.5 && h <= 14.0
                && w / h >= 2.0;
    }

    private static boolean isShortSemanticLabel(ResolvedTextFrame tf) {
        String text = normalizedText(tf != null ? tf.frameVisibleText() : null);
        return !text.isEmpty()
                && text.length() <= 60
                && text.indexOf('\n') < 0
                && text.indexOf('\r') < 0;
    }

    private static String normalizedText(String text) {
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .replace("\u0007", "")
                .trim();
    }

    private static boolean overlapsTextFrame(double[] shellBounds, ResolvedTextFrame tf, double minTfCoverage) {
        if (shellBounds == null || tf == null) return false;
        double[] tfBounds = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
        if (tfBounds == null || tfBounds.length < 4) return false;
        double tfArea = area(tfBounds);
        if (tfArea <= 0) return false;
        return overlapArea(shellBounds, tfBounds) / tfArea >= minTfCoverage;
    }

    private static double area(double[] b) {
        if (b == null || b.length < 4) return 0.0;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w > 0 && h > 0 ? w * h : 0.0;
    }

    private static double overlapArea(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double left = Math.max(a[1], b[1]);
        double top = Math.max(a[0], b[0]);
        double right = Math.min(a[3], b[3]);
        double bottom = Math.min(a[2], b[2]);
        double w = right - left;
        double h = bottom - top;
        return w > 0 && h > 0 ? w * h : 0.0;
    }

    private static boolean containsId(int[] ids, int target) {
        if (ids == null) return false;
        for (int id : ids) {
            if (id == target) return true;
        }
        return false;
    }

    private static boolean containsString(String[] ids, String target) {
        if (ids == null || target == null) return false;
        for (String id : ids) {
            if (target.equals(id)) return true;
        }
        return false;
    }

    public boolean hasRenderedFloatingItem(String idmlHexId) {
        if (idmlHexId == null || idmlHexId.length() < 2 || idmlHexId.charAt(0) != 'u') return false;
        try {
            String decimalId = String.valueOf(Integer.parseInt(idmlHexId.substring(1), 16));
            return renderedFloatingItemMap.containsKey(decimalId);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public List<RenderedGroup> getRenderedFloatingItemsByPage(int pageIndex) {
        List<RenderedGroup> result = new ArrayList<>();
        for (RenderedGroup rg : renderedFloatingItems) {
            if (rg.pageIndex() == pageIndex) result.add(rg);
        }
        return result;
    }

    public FontMetricEntry getFontMetric(String family) {
        return fontMetricMap.get(family);
    }

    // --- RenderedPdfFrame ---

    public void addRenderedPdfFrame(RenderedGroup frame) {
        renderedPdfFrameMap.put(String.valueOf(frame.id()), frame);
    }

    /**
     * IDML hex ID ("u1735") → DOM decimal ID ("5941") 변환 후 렌더링된 PDF 프레임 조회.
     */
    public RenderedGroup getRenderedPdfFrameByIdmlId(String idmlId) {
        if (idmlId == null || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return null;
        try {
            String decimalId = String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
            return renderedPdfFrameMap.get(decimalId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public java.util.Collection<RenderedGroup> allRenderedPdfFrames() {
        return renderedPdfFrameMap.values();
    }

    // --- RenderedGraphicFrame (복합 장식 그래픽) ---

    public void addRenderedGraphicFrame(RenderedGroup frame) {
        String key = String.valueOf(frame.id());
        RenderedGroup existing = renderedGraphicFrameMap.get(key);
        if (existing != null && existing.childIds() != null && frame.childIds() == null) {
            // 기존 엔트리에 childIds가 있고 새 엔트리에 없으면 childIds 보존
            frame.childIds(existing.childIds());
        }
        renderedGraphicFrameMap.put(key, frame);
    }

    /**
     * 모든 렌더링된 복합 그래픽 프레임을 반환한다.
     */
    public java.util.Collection<RenderedGroup> allRenderedGraphicFrames() {
        return renderedGraphicFrameMap.values();
    }

    /** 렌더 PDF 프레임에 등록된 DOM ID인지 확인한다 (중복 주입 방지용). */
    public boolean isRenderedByOtherChannel(int domId) {
        return renderedPdfFrameMap.containsKey(String.valueOf(domId));
    }

    /**
     * IDML hex ID ("u1735") → DOM decimal ID ("5941") 변환 후 렌더링된 복합 그래픽 프레임 조회.
     */
    public RenderedGroup getRenderedGraphicFrameByIdmlId(String idmlId) {
        if (idmlId == null || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return null;
        try {
            String decimalId = String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
            return renderedGraphicFrameMap.get(decimalId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 인라인 그래픽 처리 시 자손 deco를 소비 마킹 (orphan 주입에서 제외).
     */
    public void markConsumedRenderedGraphic(String domId) {
        consumedRenderedGraphicIds.add(domId);
    }

    public boolean isConsumedRenderedGraphic(String domId) {
        return consumedRenderedGraphicIds.contains(domId);
    }

    // --- RenderedImageFrame (이미지 배치 프레임) ---

    public void addRenderedImageFrame(RenderedGroup frame) {
        renderedImageFrameMap.put(String.valueOf(frame.id()), frame);
        // 그룹 렌더링의 자식 이미지 ID → 부모 그룹 역방향 매핑
        if (frame.childImageIds() != null) {
            for (int childDomId : frame.childImageIds()) {
                childImageToGroupMap.put(String.valueOf(childDomId), frame);
            }
        }
    }

    /**
     * IDML hex ID ("u1735") → DOM decimal ID ("5941") 변환 후 렌더링된 이미지 프레임 조회.
     * 직접 매핑이 없으면 자식 이미지 프레임 → 부모 그룹 역방향 매핑도 조회한다.
     */
    public RenderedGroup getRenderedImageFrameByIdmlId(String idmlId) {
        if (idmlId == null || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return null;
        try {
            String decimalId = String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
            RenderedGroup direct = renderedImageFrameMap.get(decimalId);
            if (direct != null) {
                return direct;
            }
            // 자식 이미지 프레임 → 부모 그룹 렌더링 폴백
            return childImageToGroupMap.get(decimalId);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    /**
     * IDML hex ID로 renderedImageFrame 등록 여부 확인 (suppress 무시).
     * 이미지 프레임 dedup용: 그룹이 렌더링되었으면 개별 이미지 불필요.
     */
    public boolean hasRenderedImageFrameByIdmlId(String idmlId) {
        if (idmlId == null || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return false;
        try {
            String decimalId = String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
            return renderedImageFrameMap.containsKey(decimalId);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** DOM(decimal) id가 renderedImageFrame(실제 래스터 이미지 프레임)으로 등록됐는지. suppress 무시 판단용. */
    public boolean isRenderedImageFrameDomId(int domId) {
        return renderedImageFrameMap.containsKey(String.valueOf(domId));
    }

    // --- 좌표 단위 정규화 ---

    /**
     * resolved 좌표 단위를 points로 변환한다.
     * IDML 페이지 폭(항상 points)과 resolved 페이지 폭을 비교하여
     * 스케일 팩터를 자동 계산하고 모든 geometry 필드에 적용한다.
     *
     * InDesign DOM은 문서의 측정 단위(mm, in, pt 등)로 좌표를 반환하므로,
     * IDML(항상 points)과 비교하여 변환 비율을 결정한다.
     *
     * @param idmlPageWidthPts IDML 첫 페이지의 폭 (points)
     */
    public void normalizeToPoints(double idmlPageWidthPts) {
        if (pages.isEmpty() || idmlPageWidthPts <= 0) return;
        double resolvedPageWidth = pages.get(0).width();
        if (resolvedPageWidth <= 0) return;

        double scale = idmlPageWidthPts / resolvedPageWidth;
        this.scaleFactor = scale;
        if (Math.abs(scale - 1.0) < 0.01) return;  // 이미 points

        System.out.println("[ResolvedData] 좌표 단위 정규화: scale=" + String.format("%.4f", scale)
                + " (resolved " + String.format("%.1f", resolvedPageWidth)
                + " → " + String.format("%.1f", idmlPageWidthPts) + " pt)");
        applyScale(scale);
    }

    private void applyScale(double s) {
        // pages: bounds, margins
        for (ResolvedPage p : pages) {
            scaleDoubleArray(p.bounds(), s);
            p.marginTop(p.marginTop() * s);
            p.marginBottom(p.marginBottom() * s);
            p.marginLeft(p.marginLeft() * s);
            p.marginRight(p.marginRight() * s);
        }
        // pageItems: bounds, spatial properties
        for (ResolvedPageItem pi : pageItems) {
            scaleDoubleArray(pi.geometricBounds(), s);
            scaleDoubleArray(pi.visibleBounds(), s);
            pi.strokeWeight(pi.strokeWeight() * s);
            pi.cornerRadius(pi.cornerRadius() * s);
            pi.dropShadowDistance(pi.dropShadowDistance() * s);
            pi.dropShadowSize(pi.dropShadowSize() * s);
            pi.gradientFeatherLength(pi.gradientFeatherLength() * s);
        }
        // textFrames: bounds, spacing, composedLines
        for (ResolvedTextFrame tf : textFrames) {
            scaleDoubleArray(tf.geometricBounds(), s);
            scaleDoubleArray(tf.insetSpacing(), s);
            tf.columnGutter(tf.columnGutter() * s);
            scaleDoubleArray(tf.paragraphYOffsets(), s);
            // composedLines bounds + wrapIndent도 스케일
            if (tf.composedLines() != null) {
                for (ResolvedTextFrame.ComposedLine cl : tf.composedLines()) {
                    scaleDoubleArray(cl.bounds(), s);
                    cl.wrapIndentLeft(cl.wrapIndentLeft() * s);
                    cl.wrapIndentRight(cl.wrapIndentRight() * s);
                }
            }
        }
        // renderedPdfFrames: bounds
        for (RenderedGroup rt : renderedPdfFrameMap.values()) {
            scaleDoubleArray(rt.bounds(), s);
        }
        // renderedGraphicFrames: bounds
        for (RenderedGroup rt : renderedGraphicFrameMap.values()) {
            scaleDoubleArray(rt.bounds(), s);
        }
        // renderedImageFrames: bounds
        for (RenderedGroup rt : renderedImageFrameMap.values()) {
            scaleDoubleArray(rt.bounds(), s);
        }
        // stories: paragraph indent + tabStops
        for (ResolvedStory story : storyMap.values()) {
            for (ResolvedParagraph para : story.paragraphs()) {
                if (para.leftIndent() != null) para.leftIndent(para.leftIndent() * s);
                if (para.firstLineIndent() != null) para.firstLineIndent(para.firstLineIndent() * s);
                if (para.rightIndent() != null) para.rightIndent(para.rightIndent() * s);
                if (para.spaceBefore() != null) para.spaceBefore(para.spaceBefore() * s);
                if (para.spaceAfter() != null) para.spaceAfter(para.spaceAfter() * s);
                // tabStops position도 스케일
                if (para.hasTabStops()) {
                    for (ResolvedTabStop rts : para.tabStops()) {
                        if (rts.position() != null) {
                            rts.position(rts.position() * s);
                        }
                    }
                }
            }
        }
    }

    private static void scaleDoubleArray(double[] arr, double s) {
        if (arr == null) return;
        for (int i = 0; i < arr.length; i++) {
            arr[i] *= s;
        }
    }

    /** domId가 inline_object로 등록된 항목인지 O(1) 조회. buildRenderedIdSet() 이후 유효. */
    public boolean isInlineObjectId(int domId) {
        return inlineObjectDomIds != null && inlineObjectDomIds.contains(domId);
    }

    // --- ExtendScript 렌더 통합 조건 ---

    private Set<String> renderedExtIdmlIds;  // ExtendScript가 렌더한 모든 객체의 IDML hex ID

    /**
     * ExtendScript가 렌더한 모든 객체의 IDML hex ID 집합과 inline_object DOM id 집합을 빌드한다.
     */
    public void buildRenderedIdSet() {
        renderedExtIdmlIds = new HashSet<>();

        // inline_object로 등록된 DOM id 집합 (FramePlacer 조상 검사 + Stage 3 skip용)
        inlineObjectDomIds = new HashSet<>();
        for (RenderedGroup flt : renderedFloatingItems) {
            if ("inline_object".equals(flt.itemType()) || "inline_object".equals(flt.type())) {
                inlineObjectDomIds.add(flt.id());
            }
        }

        // 1. renderedImageFrame: 그룹 자체 + 자식 이미지
        for (RenderedGroup rg : renderedImageFrameMap.values()) {
            if (rg.file() == null) continue;  // 렌더 실패한 항목은 건너뜀
            renderedExtIdmlIds.add("u" + Integer.toHexString(rg.id()));
            if (rg.childImageIds() != null) {
                for (int childId : rg.childImageIds()) {
                    renderedExtIdmlIds.add("u" + Integer.toHexString(childId));
                }
            }
        }

        // 2. renderedGraphicFrame: 도형/그룹 자체 + childIds (벡터 그룹)
        for (RenderedGroup rg : renderedGraphicFrameMap.values()) {
            if (rg.file() == null) continue;  // 렌더 실패한 항목은 건너뜀
            renderedExtIdmlIds.add("u" + Integer.toHexString(rg.id()));
            if (rg.childIds() != null) {
                for (int childId : rg.childIds()) {
                    renderedExtIdmlIds.add("u" + Integer.toHexString(childId));
                }
            }
        }

        // 3. renderedFloatingItems: 통합 플로팅 그래픽
        for (RenderedGroup rg : renderedFloatingItems) {
            renderedExtIdmlIds.add("u" + Integer.toHexString(rg.id()));
            if (rg.childIds() != null) {
                for (int childId : rg.childIds()) {
                    renderedExtIdmlIds.add("u" + Integer.toHexString(childId));
                }
            }
        }

        // 5. renderedPdfFrame: orphan 주입 대상이므로 여기서 등록하지 않음
        // IDML 이미지 프레임 파이프라인이 Links 폴더의 .ai/.pdf 파일을 처리할 수 있으므로
        // isRenderedByExtendScript에서 제외하여 정상 파이프라인을 우선 적용.
        // IDML이 처리하지 못한 경우에만 orphan으로 폴백된다.

        System.err.println("[ResolvedData] ExtendScript 렌더 ID " + renderedExtIdmlIds.size() + "개 인덱싱");
    }

    /**
     * 주어진 IDML hex ID가 ExtendScript에 의해 렌더된 객체인지 확인한다.
     * @param idmlId IDML hex ID (예: "u1735")
     */
    public boolean isRenderedByExtendScript(String idmlId) {
        if (renderedExtIdmlIds == null || idmlId == null) return false;
        return renderedExtIdmlIds.contains(idmlId);
    }

    // --- 통계 ---

    /** 모든 렌더링된 이미지 프레임을 반환한다. */
    public java.util.Collection<RenderedGroup> allRenderedImageFrames() {
        return renderedImageFrameMap.values();
    }

    /** 그룹 렌더 ID가 이미 처리되었는지 확인하고 마킹한다. 첫 호출만 false 반환. */
    public boolean markImageGroupProcessed(int groupId) {
        return !processedImageGroupIds.add(groupId);
    }

    public int storyCount() { return storyMap.size(); }
    public int colorCount() { return colorHexMap.size(); }
    public List<ResolvedTextFrame> textFrames() { return textFrames; }

    /** DOM decimal ID로 textFrame 조회 */
    public ResolvedTextFrame getTextFrame(String domId) {
        for (ResolvedTextFrame tf : textFrames) {
            if (domId != null && domId.equals(tf.id())) return tf;
        }
        return null;
    }

    /** 모든 Story를 컬렉션으로 반환 */
    public java.util.Collection<ResolvedStory> stories() { return storyMap.values(); }

    public int textFrameCount() { return textFrames.size(); }
    public int tableCount() { return tableMap.size(); }
    public int pageItemCount() { return pageItems.size(); }
    public int pageCount() { return pages.size(); }
}
