package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final Map<String, RenderedGroup> renderedTextFrameMap = new HashMap<>();  // DOM id → rendered TextFrame
    private final Map<String, RenderedGroup> renderedPdfFrameMap = new HashMap<>();  // DOM id → rendered PDF frame
    private final Map<String, RenderedGroup> renderedGraphicFrameMap = new LinkedHashMap<>();  // DOM id → rendered complex graphic (순서 보존)
    private final Map<String, RenderedGroup> renderedImageFrameMap = new HashMap<>();  // DOM id → rendered image frame (PSD, AI 등)
    private final Map<String, RenderedGroup> childImageToGroupMap = new HashMap<>();  // 자식 이미지 DOM id → 부모 그룹 RenderedGroup
    private final Set<Integer> processedImageGroupIds = new HashSet<>();  // 이미 Figure로 변환된 그룹 렌더 ID
    private Set<String> badgeGroupShapeIdmlIds;  // 배지 그룹 소속 도형 IDML hex ID ("u1735")
    private Map<String, RenderedGroup> badgeChildTextFrameMap;  // 배지 자식 TextFrame DOM id → 배지 그룹 RenderedGroup
    private final List<FontMetricEntry> fontMetrics = new ArrayList<>();  // InDesign 폰트 메트릭
    private double scaleFactor = 2.8346;  // resolved 좌표 → pt 변환 스케일
    private final Map<String, FontMetricEntry> fontMetricMap = new HashMap<>();  // family → metric
    private final Set<String> consumedRenderedGraphicIds = new HashSet<>();  // 인라인 처리로 소비된 deco DOM id
    private final List<RenderedGroup> renderedFloatingItems = new ArrayList<>();  // 통합 플로팅 그래픽
    private final Map<String, RenderedGroup> renderedFloatingItemMap = new LinkedHashMap<>();
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

    // SPEC-025: 배지 자식 TextFrame 중 "단순 scribble" 타입 — 텍스트가 그룹 영역을 거의 채우므로
    // PNG 를 흰 배경 박스로 대체 가능. 일러스트 배지(예: 선인장 + 작은 라벨)는 여기 포함되지 않음.
    private final Set<String> simpleBadgeChildIds = new HashSet<>();
    public void markSimpleBadgeChild(String domId) { if (domId != null) simpleBadgeChildIds.add(domId); }
    public boolean isSimpleBadgeChild(String domId) { return domId != null && simpleBadgeChildIds.contains(domId); }

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
        // 기본 색상 폴백 (resolved.json colors 배열에 누락된 경우)
        if ("Paper".equals(colorName)) return "#FFFFFF";
        if ("Black".equals(colorName)) return "#000000";
        if ("White".equals(colorName)) return "#FFFFFF";
        return null;
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
        String key = String.valueOf(item.id());
        RenderedGroup existing = renderedFloatingItemMap.get(key);
        if (existing != null && existing.childIds() != null && item.childIds() == null) {
            item.childIds(existing.childIds());
        }
        renderedFloatingItemMap.put(key, item);
        renderedFloatingItems.add(item);
    }

    public List<RenderedGroup> allRenderedFloatingItems() { return renderedFloatingItems; }

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

    // --- RenderedTextFrame ---

    public void addRenderedTextFrame(RenderedGroup frame) {
        renderedTextFrameMap.put(String.valueOf(frame.id()), frame);
    }

    /**
     * IDML hex ID ("u1735") → DOM decimal ID ("5941") 변환 후 렌더링된 텍스트 프레임 조회.
     */
    public RenderedGroup getRenderedTextFrameByIdmlId(String idmlId) {
        if (idmlId == null || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return null;
        try {
            String decimalId = String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
            return renderedTextFrameMap.get(decimalId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** DOM decimal ID로 렌더링된 텍스트 프레임 조회 */
    public RenderedGroup getRenderedTextFrameByDomId(String domId) {
        return renderedTextFrameMap.get(domId);
    }

    public int renderedTextFrameCount() { return renderedTextFrameMap.size(); }

    public java.util.Collection<RenderedGroup> allRenderedTextFrames() {
        return renderedTextFrameMap.values();
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

    /**
     * DOM decimal ID가 렌더 텍스트 프레임 또는 렌더 PDF 프레임에도 등록되어 있는지 확인한다.
     * 이미 다른 경로로 처리된 프레임을 중복 주입하지 않기 위함.
     */
    public boolean isRenderedByOtherChannel(int domId) {
        String key = String.valueOf(domId);
        return renderedTextFrameMap.containsKey(key) || renderedPdfFrameMap.containsKey(key);
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
        // renderedTextFrames: bounds
        for (RenderedGroup rt : renderedTextFrameMap.values()) {
            scaleDoubleArray(rt.bounds(), s);
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

    // --- 배지 그룹 인덱스 ---

    /**
     * 배지 그룹 인덱스를 빌드한다.
     * renderedTextFrames에서 type="badge_group"인 항목의 childIds를 수집하여
     * IDML hex ID 형식으로 변환, 배지 소속 도형 조회에 사용한다.
     */
    public void buildBadgeGroupIndex() {
        badgeGroupShapeIdmlIds = new HashSet<>();
        badgeChildTextFrameMap = new HashMap<>();
        int badgeCount = 0;
        for (RenderedGroup rg : renderedTextFrameMap.values()) {
            if (rg.isBadgeGroup() && rg.childIds() != null) {
                badgeCount++;
                for (int childDomId : rg.childIds()) {
                    // DOM decimal → IDML hex ("u" + hex)
                    String idmlId = "u" + Integer.toHexString(childDomId);
                    badgeGroupShapeIdmlIds.add(idmlId);
                }
                // 배지 자식 TextFrame → 배지 그룹 역방향 매핑
                if (rg.childTextFrameIds() != null) {
                    for (int tfDomId : rg.childTextFrameIds()) {
                        badgeChildTextFrameMap.put(String.valueOf(tfDomId), rg);
                    }
                }
                // 배지 그룹 자체 ID도 매핑 (인라인 Group이 wrapper로 변환될 때 sourceId=Group ID)
                badgeChildTextFrameMap.put(String.valueOf(rg.id()), rg);
            }
        }
        if (badgeCount > 0) {
            System.out.println("[ResolvedData] 배지 그룹 " + badgeCount + "개, "
                    + "자식 도형 " + badgeGroupShapeIdmlIds.size() + "개 인덱싱");
        }
    }

    /**
     * 배지 자식 TextFrame의 IDML hex ID로 배지 그룹 RenderedGroup을 조회한다.
     * 인라인 배지 텍스트 프레임을 렌더 이미지로 교체할 때 사용.
     */
    public RenderedGroup getBadgeGroupByChildTextFrameIdmlId(String idmlId) {
        if (badgeChildTextFrameMap == null || idmlId == null
                || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return null;
        try {
            String decimalId = String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
            return badgeChildTextFrameMap.get(decimalId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 도형이 배지 그룹에 소속되었는지 조회한다.
     * @param idmlId IDML hex ID (예: "u1735")
     */
    public boolean isShapeInBadgeGroup(String idmlId) {
        if (badgeGroupShapeIdmlIds == null || idmlId == null) return false;
        return badgeGroupShapeIdmlIds.contains(idmlId);
    }

    // --- ExtendScript 렌더 통합 조건 ---

    private Set<String> renderedExtIdmlIds;  // ExtendScript가 렌더한 모든 객체의 IDML hex ID

    /**
     * ExtendScript가 렌더한 모든 객체의 IDML hex ID 집합을 빌드한다.
     * 4가지 렌더 유형 + 자식 ID를 모두 수집:
     * 1. renderedImageFrame: 그룹 ID + childImageIds
     * 2. renderedGraphicFrame: 도형/그룹 ID
     * 3. renderedTextFrame: 프레임 ID + badge childIds + badge childTextFrameIds
     * 4. renderedPdfFrame: 도형 ID
     *
     * buildBadgeGroupIndex() 이후에 호출해야 한다.
     */
    public void buildRenderedIdSet() {
        renderedExtIdmlIds = new HashSet<>();

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

        // 3. renderedTextFrame: 프레임 자체 + badge childIds + badge childTextFrameIds
        for (RenderedGroup rg : renderedTextFrameMap.values()) {
            if (rg.file() == null) continue;  // 렌더 실패한 항목은 건너뜀
            renderedExtIdmlIds.add("u" + Integer.toHexString(rg.id()));
            if (rg.childIds() != null) {
                for (int childId : rg.childIds()) {
                    renderedExtIdmlIds.add("u" + Integer.toHexString(childId));
                }
            }
            if (rg.childTextFrameIds() != null) {
                for (int childId : rg.childTextFrameIds()) {
                    renderedExtIdmlIds.add("u" + Integer.toHexString(childId));
                }
            }
        }

        // 4. renderedFloatingItems: 통합 플로팅 그래픽
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
