package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFontDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTPageBackground;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;

import java.util.*;

/**
 * Flat 문서의 쿼리 인터페이스.
 * 모든 컨버터의 유일한 데이터 접근 경로.
 * 내부적으로 인덱스를 구축하여 O(1) 조회를 제공한다.
 */
public class FlatDocumentGateway {

    private final FlatDocument doc;

    // 인덱스
    private final Map<String, FlatPage> pageById;
    private final Map<Integer, FlatPage> pageByNumber;
    private final Map<String, FlatLayoutNode> nodeById;
    private final Map<String, FlatComponent> componentById;

    // 파생 인덱스 (lazy 없이 생성자에서 즉시 구축)
    private final Map<String, List<FlatLayoutNode>> nodesByPage;         // pageId → ABSOLUTE nodes (z-order)
    private final Map<String, List<FlatLayoutNode>> allNodesByPage;      // pageId → all nodes
    private final Map<String, List<FlatLayoutNode>> overlayByParent;     // parentNodeId → overlay children
    private final Map<String, List<FlatLayoutNode>> nodesByStory;        // storyId → linked chain
    private final Map<String, List<FlatComponent>> componentsByNode;     // nodeId → components

    public FlatDocumentGateway(FlatDocument doc) {
        this.doc = doc;

        // Layer 0 index
        this.pageById = new LinkedHashMap<String, FlatPage>();
        this.pageByNumber = new LinkedHashMap<Integer, FlatPage>();
        for (FlatPage page : doc.pages()) {
            pageById.put(page.pageId(), page);
            pageByNumber.put(page.pageNumber(), page);
        }

        // Layer 1 index
        this.nodeById = new LinkedHashMap<String, FlatLayoutNode>();
        this.nodesByPage = new LinkedHashMap<String, List<FlatLayoutNode>>();
        this.allNodesByPage = new LinkedHashMap<String, List<FlatLayoutNode>>();
        this.overlayByParent = new LinkedHashMap<String, List<FlatLayoutNode>>();
        this.nodesByStory = new LinkedHashMap<String, List<FlatLayoutNode>>();

        for (FlatLayoutNode node : doc.layoutNodes()) {
            nodeById.put(node.nodeId(), node);

            // Page-level grouping
            String pid = node.pageId();
            if (pid != null) {
                getOrCreateList(allNodesByPage, pid).add(node);
                if (node.positioning() == FlatLayoutNode.PositioningMode.ABSOLUTE) {
                    getOrCreateList(nodesByPage, pid).add(node);
                }
            }

            // Overlay parent grouping
            if (node.positioning() == FlatLayoutNode.PositioningMode.OVERLAY
                    && node.overlayParentId() != null) {
                getOrCreateList(overlayByParent, node.overlayParentId()).add(node);
            }

            // Story grouping (for linked text frames)
            if (node.storyId() != null
                    && node.nodeType() == FlatLayoutNode.NodeType.TEXT_FRAME
                    && node.positioning() == FlatLayoutNode.PositioningMode.ABSOLUTE) {
                getOrCreateList(nodesByStory, node.storyId()).add(node);
            }
        }

        // Sort floating nodes by z-order
        for (List<FlatLayoutNode> nodes : nodesByPage.values()) {
            Collections.sort(nodes, new Comparator<FlatLayoutNode>() {
                @Override
                public int compare(FlatLayoutNode a, FlatLayoutNode b) {
                    return Integer.compare(a.zOrder(), b.zOrder());
                }
            });
        }

        // Layer 2 index
        this.componentById = new LinkedHashMap<String, FlatComponent>();
        this.componentsByNode = new LinkedHashMap<String, List<FlatComponent>>();
        for (FlatComponent comp : doc.components()) {
            componentById.put(comp.componentId(), comp);
            if (comp.parentNodeId() != null) {
                getOrCreateList(componentsByNode, comp.parentNodeId()).add(comp);
            }
        }
    }

    // =========================================================================
    // Layer 0: Page queries
    // =========================================================================

    /** 모든 페이지 (순서대로). */
    public List<FlatPage> pages() {
        return Collections.unmodifiableList(doc.pages());
    }

    /** 페이지 번호로 조회. */
    public FlatPage pageByNumber(int pageNumber) {
        return pageByNumber.get(pageNumber);
    }

    /** 페이지 ID로 조회. */
    public FlatPage page(String pageId) {
        return pageById.get(pageId);
    }

    /** 총 페이지 수. */
    public int pageCount() {
        return doc.pages().size();
    }

    // =========================================================================
    // Layer 1: Layout node queries
    // =========================================================================

    /** 페이지의 모든 레이아웃 노드 (읽기 순서). */
    public List<FlatLayoutNode> allNodesOnPage(String pageId) {
        List<FlatLayoutNode> list = allNodesByPage.get(pageId);
        return list != null ? Collections.unmodifiableList(list) : Collections.<FlatLayoutNode>emptyList();
    }

    /** 페이지의 ABSOLUTE 노드 (z-order 정렬). */
    public List<FlatLayoutNode> floatingNodesOnPage(String pageId) {
        List<FlatLayoutNode> list = nodesByPage.get(pageId);
        return list != null ? Collections.unmodifiableList(list) : Collections.<FlatLayoutNode>emptyList();
    }

    /** 노드 ID로 조회. */
    public FlatLayoutNode layoutNode(String nodeId) {
        return nodeById.get(nodeId);
    }

    /** 페이지의 BACKGROUND 레이어 노드 (z-order 정렬). */
    public List<FlatLayoutNode> backgroundNodes(String pageId) {
        return nodesForLayer(pageId, FlatLayoutNode.SemanticLayer.BACKGROUND);
    }

    /** 페이지의 CONTENT 레이어 노드 (z-order 정렬). */
    public List<FlatLayoutNode> contentNodes(String pageId) {
        return nodesForLayer(pageId, FlatLayoutNode.SemanticLayer.CONTENT);
    }

    /** 페이지의 FOREGROUND 레이어 노드 (z-order 정렬). */
    public List<FlatLayoutNode> foregroundNodes(String pageId) {
        return nodesForLayer(pageId, FlatLayoutNode.SemanticLayer.FOREGROUND);
    }

    private List<FlatLayoutNode> nodesForLayer(String pageId, FlatLayoutNode.SemanticLayer layer) {
        FlatPage page = pageById.get(pageId);
        if (page == null) return Collections.emptyList();

        List<String> ids;
        switch (layer) {
            case BACKGROUND: ids = page.backgroundNodeIds(); break;
            case CONTENT:    ids = page.contentNodeIds(); break;
            case FOREGROUND: ids = page.foregroundNodeIds(); break;
            default: return Collections.emptyList();
        }
        List<FlatLayoutNode> result = new ArrayList<FlatLayoutNode>(ids.size());
        for (String id : ids) {
            FlatLayoutNode node = nodeById.get(id);
            if (node != null) result.add(node);
        }
        return Collections.unmodifiableList(result);
    }

    /** 오버레이 자식 노드 조회. */
    public List<FlatLayoutNode> overlayChildren(String parentNodeId) {
        List<FlatLayoutNode> list = overlayByParent.get(parentNodeId);
        return list != null ? Collections.unmodifiableList(list) : Collections.<FlatLayoutNode>emptyList();
    }

    /** 같은 storyId를 공유하는 연결 프레임 체인. */
    public List<FlatLayoutNode> linkedFrameChain(String storyId) {
        List<FlatLayoutNode> list = nodesByStory.get(storyId);
        return list != null ? Collections.unmodifiableList(list) : Collections.<FlatLayoutNode>emptyList();
    }

    /**
     * 연결 프레임이 있는 storyId 목록.
     * 2개 이상의 프레임이 공유하는 storyId만 반환.
     */
    public Set<String> linkedStoryIds() {
        Set<String> result = new LinkedHashSet<String>();
        for (Map.Entry<String, List<FlatLayoutNode>> entry : nodesByStory.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    /**
     * 배경 전용 블록인지 판별.
     * fillColor가 있으면서 실질 텍스트가 없는 TEXT_FRAME.
     */
    public boolean isBackgroundOnly(FlatLayoutNode node) {
        if (node.nodeType() != FlatLayoutNode.NodeType.TEXT_FRAME) return false;
        if (node.fillColor() == null || node.fillColor().isEmpty()) return false;

        List<FlatComponent> comps = components(node.nodeId());
        for (FlatComponent comp : comps) {
            for (FlatInlineItem item : comp.items()) {
                if (item.itemType() == FlatInlineItem.ItemType.TEXT_RUN) {
                    String text = item.text();
                    if (text != null && !text.trim().isEmpty()) return false;
                } else if (item.itemType() == FlatInlineItem.ItemType.LAYOUT_REF) {
                    return false;
                } else if (item.itemType() == FlatInlineItem.ItemType.EQUATION) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * TEXT_FRAME 노드를 텍스트 프레임과 기타 블록으로 분류.
     * ASTToHwpxConverter.convertSection()과 동일한 패턴.
     */
    public void classifyTextFramesOnPage(String pageId,
                                          List<FlatLayoutNode> textFrames,
                                          List<FlatLayoutNode> otherBlocks) {
        for (FlatLayoutNode node : floatingNodesOnPage(pageId)) {
            if (node.nodeType() == FlatLayoutNode.NodeType.TEXT_FRAME) {
                textFrames.add(node);
            } else {
                otherBlocks.add(node);
            }
        }
    }

    // =========================================================================
    // Layer 2: Component queries
    // =========================================================================

    /** 레이아웃 노드의 컴포넌트 목록 (순서 유지). */
    public List<FlatComponent> components(String nodeId) {
        List<FlatComponent> list = componentsByNode.get(nodeId);
        return list != null ? Collections.unmodifiableList(list) : Collections.<FlatComponent>emptyList();
    }

    /**
     * 레이아웃 노드의 componentIds 순서에 따른 컴포넌트 목록.
     * componentsByNode과 달리 노드의 componentIds 순서를 엄격히 보장.
     */
    public List<FlatComponent> orderedComponents(FlatLayoutNode node) {
        List<FlatComponent> result = new ArrayList<FlatComponent>();
        for (String cid : node.componentIds()) {
            FlatComponent comp = componentById.get(cid);
            if (comp != null) {
                result.add(comp);
            }
        }
        return result;
    }

    /** 테이블 셀의 컴포넌트 목록. */
    public List<FlatComponent> cellComponents(FlatTableCell cell) {
        List<FlatComponent> result = new ArrayList<FlatComponent>();
        for (String cid : cell.componentIds()) {
            FlatComponent comp = componentById.get(cid);
            if (comp != null) {
                result.add(comp);
            }
        }
        return result;
    }

    /** 컴포넌트 ID로 조회. */
    public FlatComponent component(String componentId) {
        return componentById.get(componentId);
    }

    // =========================================================================
    // Inline reference resolution
    // =========================================================================

    /** LAYOUT_REF 인라인 항목을 레이아웃 노드로 해석. */
    public FlatLayoutNode resolveLayoutRef(FlatInlineItem item) {
        if (item == null || item.itemType() != FlatInlineItem.ItemType.LAYOUT_REF) {
            return null;
        }
        return nodeById.get(item.layoutNodeId());
    }

    // =========================================================================
    // Metadata queries
    // =========================================================================

    public List<ASTFontDef> fonts() {
        return doc.fonts();
    }

    public List<ASTStyleDef> paragraphStyles() {
        return doc.paragraphStyles();
    }

    public List<ASTStyleDef> characterStyles() {
        return doc.characterStyles();
    }

    public Map<String, String> colors() {
        return doc.colors();
    }

    public List<ASTStory> stories() {
        return doc.stories();
    }

    public List<ASTPageBackground> backgrounds() {
        return doc.backgrounds();
    }

    public String sourceFile() {
        return doc.sourceFile();
    }

    public String sourceFormat() {
        return doc.sourceFormat();
    }

    // =========================================================================
    // Statistics / diagnostic
    // =========================================================================

    /** 전체 레이아웃 노드 수. */
    public int totalNodeCount() {
        return doc.layoutNodes().size();
    }

    /** 전체 컴포넌트 수. */
    public int totalComponentCount() {
        return doc.components().size();
    }

    /** LAYOUT_REF 무결성 검증 — 모든 참조가 유효한 nodeId를 가리키는지 확인. */
    public List<String> validateLayoutRefs() {
        List<String> errors = new ArrayList<String>();
        for (FlatComponent comp : doc.components()) {
            for (FlatInlineItem item : comp.items()) {
                if (item.itemType() == FlatInlineItem.ItemType.LAYOUT_REF) {
                    if (item.layoutNodeId() == null || !nodeById.containsKey(item.layoutNodeId())) {
                        errors.add("Component " + comp.componentId()
                                + " has dangling LAYOUT_REF: " + item.layoutNodeId());
                    }
                }
            }
        }
        return errors;
    }

    /** 모든 componentIds 참조가 유효한지 검증. */
    public List<String> validateComponentRefs() {
        List<String> errors = new ArrayList<String>();
        for (FlatLayoutNode node : doc.layoutNodes()) {
            for (String cid : node.componentIds()) {
                if (!componentById.containsKey(cid)) {
                    errors.add("Node " + node.nodeId() + " has dangling component ref: " + cid);
                }
            }
            if (node.tableRows() != null) {
                for (FlatTableRow row : node.tableRows()) {
                    for (FlatTableCell cell : row.cells()) {
                        for (String cid : cell.componentIds()) {
                            if (!componentById.containsKey(cid)) {
                                errors.add("Cell [" + cell.rowIndex() + "," + cell.columnIndex()
                                        + "] in node " + node.nodeId()
                                        + " has dangling component ref: " + cid);
                            }
                        }
                    }
                }
            }
        }
        return errors;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static <K, V> List<V> getOrCreateList(Map<K, List<V>> map, K key) {
        List<V> list = map.get(key);
        if (list == null) {
            list = new ArrayList<V>();
            map.put(key, list);
        }
        return list;
    }
}
