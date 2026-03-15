package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private final Map<String, RenderedGroup> renderedGraphicFrameMap = new HashMap<>();  // DOM id → rendered complex graphic
    private Set<String> badgeGroupShapeIdmlIds;  // 배지 그룹 소속 도형 IDML hex ID ("u1735")
    private Map<String, RenderedGroup> badgeChildTextFrameMap;  // 배지 자식 TextFrame DOM id → 배지 그룹 RenderedGroup
    private final List<FontMetricEntry> fontMetrics = new ArrayList<>();  // InDesign 폰트 메트릭
    private final Map<String, FontMetricEntry> fontMetricMap = new HashMap<>();  // family → metric

    public void addStory(ResolvedStory story) {
        storyMap.put(story.id(), story);
    }

    public ResolvedStory getStory(String storyId) {
        return storyMap.get(storyId);
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

    // --- RenderedGraphicFrame (복합 장식 그래픽) ---

    public void addRenderedGraphicFrame(RenderedGroup frame) {
        renderedGraphicFrameMap.put(String.valueOf(frame.id()), frame);
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
        // textFrames: bounds, spacing
        for (ResolvedTextFrame tf : textFrames) {
            scaleDoubleArray(tf.geometricBounds(), s);
            scaleDoubleArray(tf.insetSpacing(), s);
            tf.columnGutter(tf.columnGutter() * s);
            scaleDoubleArray(tf.paragraphYOffsets(), s);
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

    // --- 통계 ---

    public int storyCount() { return storyMap.size(); }
    public int colorCount() { return colorHexMap.size(); }
    public int textFrameCount() { return textFrames.size(); }
    public int tableCount() { return tableMap.size(); }
    public int pageItemCount() { return pageItems.size(); }
    public int pageCount() { return pages.size(); }
}
