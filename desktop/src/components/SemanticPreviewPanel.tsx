import { useMemo, useState } from "react";
import { useSemanticStore } from "../stores/useSemanticStore";
// SemanticNode type from the store's node array
interface NodeFeatures {
  x: number;
  y: number;
  width: number;
  height: number;
  pageNumber: number;
}
interface MinimalNode {
  id: string;
  label: string;
  manualOverride: boolean;
  features: NodeFeatures;
}

const LABEL_COLORS: Record<string, string> = {
  UNKNOWN: "rgba(158,158,158,0.2)",
  BACKGROUND: "rgba(224,224,224,0.15)",
  PAGE_HEADER: "rgba(158,158,158,0.3)",
  PAGE_FOOTER: "rgba(158,158,158,0.3)",
  SECTION_TITLE: "rgba(21,101,192,0.25)",
  BODY_TEXT: "rgba(51,51,51,0.15)",
  FIGURE: "rgba(67,160,71,0.25)",
  CAPTION: "rgba(102,187,106,0.2)",
  TABLE: "rgba(255,143,0,0.25)",
  DECORATION: "rgba(189,189,189,0.15)",
  CHAPTER_TITLE: "rgba(13,71,161,0.3)",
  CONCEPT_BOX: "rgba(79,195,247,0.2)",
  FORMULA: "rgba(126,87,194,0.25)",
  EXAMPLE: "rgba(38,166,154,0.2)",
  PROBLEM: "rgba(255,107,107,0.25)",
  SUB_PROBLEM: "rgba(255,138,128,0.2)",
  CHOICES: "rgba(255,171,145,0.2)",
  SOLUTION: "rgba(129,199,132,0.2)",
  ANSWER: "rgba(165,214,167,0.2)",
  TIP_BOX: "rgba(255,241,118,0.2)",
  SIDEBAR: "rgba(188,170,164,0.2)",
};

const LABEL_BORDER_COLORS: Record<string, string> = {
  UNKNOWN: "#9E9E9E",
  BACKGROUND: "#E0E0E0",
  SECTION_TITLE: "#1565C0",
  CHAPTER_TITLE: "#0D47A1",
  PROBLEM: "#FF6B6B",
  EXAMPLE: "#26A69A",
  CONCEPT_BOX: "#4FC3F7",
  FIGURE: "#43A047",
  TABLE: "#FF8F00",
};

function getOverlayColor(label: string): string {
  return LABEL_COLORS[label] ?? "rgba(158,158,158,0.2)";
}

function getBorderColor(label: string): string {
  return LABEL_BORDER_COLORS[label] ?? "#9E9E9E";
}

export function SemanticPreviewPanel() {
  const nodes = useSemanticStore((s) => s.nodes);
  const selectedNodeId = useSemanticStore((s) => s.selectedNodeId);
  const hoveredNodeId = useSemanticStore((s) => s.hoveredNodeId);
  const selectNode = useSemanticStore((s) => s.selectNode);
  const hoverNode = useSemanticStore((s) => s.hoverNode);
  const filterPage = useSemanticStore((s) => s.filterPage);
  const getPageNumbers = useSemanticStore((s) => s.getPageNumbers);

  const pageNumbers = getPageNumbers();
  const [currentPage, setCurrentPage] = useState(pageNumbers[0] ?? 1);
  const activePage = filterPage ?? currentPage;

  // 현재 페이지의 노드 + 페이지 레이아웃 계산
  const pageData = useMemo(() => {
    const pageNodes = nodes.filter(
      (n) => n.features.pageNumber === activePage
    );
    if (pageNodes.length === 0) return null;

    // 페이지 크기 추정 (FULL_WIDTH 노드나 최대 좌표에서 추정)
    let pageWidth = 59528; // 기본값 A4
    let pageHeight = 84189;
    for (const n of pageNodes) {
      const right = n.features.x + n.features.width;
      const bottom = n.features.y + n.features.height;
      if (right > pageWidth) pageWidth = right;
      if (bottom > pageHeight) pageHeight = bottom;
    }

    return { pageNodes, pageWidth, pageHeight };
  }, [nodes, activePage]);

  if (!pageData) {
    return (
      <div className="h-full flex items-center justify-center text-gray-400 text-sm">
        페이지 데이터 없음
      </div>
    );
  }

  const { pageNodes, pageWidth, pageHeight } = pageData;
  const viewBox = `0 0 ${pageWidth} ${pageHeight}`;

  return (
    <div className="h-full flex flex-col">
      {/* 페이지 네비게이션 */}
      <div className="flex items-center gap-2 px-3 py-1.5 border-b bg-gray-50 shrink-0">
        <span className="text-xs text-gray-500">페이지:</span>
        {pageNumbers.map((p) => (
          <button
            key={p}
            onClick={() => setCurrentPage(p)}
            className={`px-2 py-0.5 text-xs rounded ${
              activePage === p
                ? "bg-blue-500 text-white"
                : "bg-white border text-gray-600 hover:bg-gray-100"
            }`}
          >
            {p}
          </button>
        ))}
        <span className="flex-1" />
        <span className="text-xs text-gray-400">
          {pageNodes.length}개 노드
        </span>
      </div>

      {/* SVG 프리뷰 */}
      <div className="flex-1 overflow-auto p-4 bg-gray-200">
        <svg
          viewBox={viewBox}
          className="w-full h-full bg-white shadow-lg"
          style={{ maxWidth: 800 }}
        >
          {/* 노드 오버레이 */}
          {pageNodes.map((node) => (
            <NodeOverlay
              key={node.id}
              node={node}
              isSelected={node.id === selectedNodeId}
              isHovered={node.id === hoveredNodeId}
              onClick={() => selectNode(node.id)}
              onMouseEnter={() => hoverNode(node.id)}
              onMouseLeave={() => hoverNode(null)}
            />
          ))}
        </svg>
      </div>
    </div>
  );
}

function NodeOverlay({
  node,
  isSelected,
  isHovered,
  onClick,
  onMouseEnter,
  onMouseLeave,
}: {
  node: MinimalNode;
  isSelected: boolean;
  isHovered: boolean;
  onClick: () => void;
  onMouseEnter: () => void;
  onMouseLeave: () => void;
}) {
  const { x, y, width, height } = node.features;
  const fillColor = getOverlayColor(node.label);
  const borderColor = getBorderColor(node.label);

  const strokeWidth = isSelected ? 80 : isHovered ? 60 : 30;
  const opacity = isSelected || isHovered ? 1 : 0.7;

  return (
    <g
      onClick={onClick}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      style={{ cursor: "pointer", opacity }}
    >
      <rect
        x={x}
        y={y}
        width={width}
        height={height}
        fill={fillColor}
        stroke={borderColor}
        strokeWidth={strokeWidth}
        strokeDasharray={node.label === "UNKNOWN" ? "200 100" : undefined}
      />
      {/* 레이블 텍스트 */}
      {(isSelected || isHovered || node.label !== "UNKNOWN") && (
        <text
          x={x + 100}
          y={y + 600}
          fontSize={400}
          fill={borderColor}
          fontWeight={isSelected ? "bold" : "normal"}
        >
          {node.label}
          {node.manualOverride ? " ✎" : ""}
        </text>
      )}
    </g>
  );
}
