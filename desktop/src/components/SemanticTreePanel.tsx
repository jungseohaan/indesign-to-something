import { useSemanticStore } from "../stores/useSemanticStore";

const LABEL_COLORS: Record<string, string> = {
  UNKNOWN: "#9E9E9E",
  BACKGROUND: "#E0E0E0",
  PAGE_HEADER: "#9E9E9E",
  PAGE_FOOTER: "#9E9E9E",
  SECTION_TITLE: "#1565C0",
  BODY_TEXT: "#333333",
  FIGURE: "#43A047",
  CAPTION: "#66BB6A",
  TABLE: "#FF8F00",
  DECORATION: "#BDBDBD",
  CHAPTER_TITLE: "#0D47A1",
  SUBSECTION_TITLE: "#1976D2",
  CONCEPT_BOX: "#4FC3F7",
  FORMULA: "#7E57C2",
  EXAMPLE: "#26A69A",
  PROBLEM: "#FF6B6B",
  SUB_PROBLEM: "#FF8A80",
  CHOICES: "#FFAB91",
  SOLUTION: "#81C784",
  ANSWER: "#A5D6A7",
  TIP_BOX: "#FFF176",
  SIDEBAR: "#BCAAA4",
};

function getLabelColor(label: string): string {
  return LABEL_COLORS[label] ?? "#9E9E9E";
}

export function SemanticTreePanel() {
  const selectedNodeId = useSemanticStore((s) => s.selectedNodeId);
  const selectNode = useSemanticStore((s) => s.selectNode);
  const hoverNode = useSemanticStore((s) => s.hoverNode);
  const searchQuery = useSemanticStore((s) => s.searchQuery);
  const setSearchQuery = useSemanticStore((s) => s.setSearchQuery);
  const filterLabel = useSemanticStore((s) => s.filterLabel);
  const setFilterLabel = useSemanticStore((s) => s.setFilterLabel);
  const filterPage = useSemanticStore((s) => s.filterPage);
  const setFilterPage = useSemanticStore((s) => s.setFilterPage);
  const showUnknownOnly = useSemanticStore((s) => s.showUnknownOnly);
  const setShowUnknownOnly = useSemanticStore((s) => s.setShowUnknownOnly);
  const labels = useSemanticStore((s) => s.labels);
  const getFilteredNodes = useSemanticStore((s) => s.getFilteredNodes);
  const getPageNumbers = useSemanticStore((s) => s.getPageNumbers);
  const generateSuggestions = useSemanticStore((s) => s.generateSuggestions);
  const nodes = useSemanticStore((s) => s.nodes);

  const filteredNodes = getFilteredNodes();
  const pageNumbers = getPageNumbers();
  const manualCount = nodes.filter((n) => n.manualOverride).length;

  // 레이블별 통계
  const labelCounts = new Map<string, number>();
  for (const n of nodes) {
    labelCounts.set(n.label, (labelCounts.get(n.label) ?? 0) + 1);
  }

  return (
    <>
      {/* 검색 & 필터 */}
      <div className="p-2 border-b space-y-1.5 shrink-0">
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="노드 검색 (ID, 레이블, 텍스트)"
          className="w-full px-2 py-1 text-xs border rounded"
        />
        <div className="flex gap-1.5 items-center">
          <select
            value={filterPage ?? ""}
            onChange={(e) =>
              setFilterPage(e.target.value ? Number(e.target.value) : null)
            }
            className="flex-1 px-1.5 py-1 text-xs border rounded"
          >
            <option value="">전체 페이지</option>
            {pageNumbers.map((p) => (
              <option key={p} value={p}>
                {p}쪽
              </option>
            ))}
          </select>
          <select
            value={filterLabel ?? ""}
            onChange={(e) => setFilterLabel(e.target.value || null)}
            className="flex-1 px-1.5 py-1 text-xs border rounded"
          >
            <option value="">전체 레이블</option>
            {[...labelCounts.entries()]
              .sort((a, b) => b[1] - a[1])
              .map(([label, count]) => (
                <option key={label} value={label}>
                  {label} ({count})
                </option>
              ))}
          </select>
        </div>
        <div className="flex items-center justify-between">
          <label className="flex items-center gap-1 text-xs text-gray-500">
            <input
              type="checkbox"
              checked={showUnknownOnly}
              onChange={(e) => setShowUnknownOnly(e.target.checked)}
              className="w-3 h-3"
            />
            미분류만
          </label>
          {manualCount >= 2 && (
            <button
              onClick={generateSuggestions}
              className="text-xs text-blue-500 hover:text-blue-700"
            >
              규칙 제안 ({manualCount}개 수동)
            </button>
          )}
        </div>
      </div>

      {/* 노드 목록 */}
      <div className="flex-1 overflow-y-auto">
        {filteredNodes.map((node) => (
          <div
            key={node.id}
            onClick={() => selectNode(node.id)}
            onMouseEnter={() => hoverNode(node.id)}
            onMouseLeave={() => hoverNode(null)}
            className={`flex items-center gap-2 px-3 py-1.5 cursor-pointer border-b border-gray-100 text-xs ${
              selectedNodeId === node.id
                ? "bg-blue-50 border-l-2 border-l-blue-500"
                : "hover:bg-gray-50"
            }`}
          >
            {/* 레이블 색상 */}
            <span
              className="w-2.5 h-2.5 rounded-full shrink-0"
              style={{ backgroundColor: getLabelColor(node.label) }}
            />

            {/* 레이블 */}
            <span
              className="font-medium shrink-0 min-w-[70px]"
              style={{ color: getLabelColor(node.label) }}
            >
              {node.label}
              {node.manualOverride && (
                <span className="ml-0.5 text-orange-400" title="수동 레이블">
                  ✎
                </span>
              )}
            </span>

            {/* 텍스트 미리보기 */}
            <span className="text-gray-500 truncate">
              {node.features.firstLineText || node.id}
            </span>

            {/* 페이지 */}
            <span className="text-gray-300 shrink-0 ml-auto">
              p.{node.features.pageNumber}
            </span>
          </div>
        ))}

        {filteredNodes.length === 0 && (
          <div className="p-4 text-center text-xs text-gray-400">
            {searchQuery || filterLabel || filterPage
              ? "필터 결과 없음"
              : "노드 없음"}
          </div>
        )}
      </div>

      {/* 하단 통계 */}
      <div className="px-3 py-1.5 border-t bg-gray-50 text-xs text-gray-400 shrink-0">
        {filteredNodes.length}/{nodes.length}개 표시
      </div>
    </>
  );
}
