import { useMemo } from "react";
import { useSemanticStore } from "../stores/useSemanticStore";

const LABEL_DOT: Record<string, string> = {
  SECTION_TITLE: "#1565C0",
  CHAPTER_TITLE: "#0D47A1",
  BODY_TEXT: "#333333",
  PROBLEM: "#FF6B6B",
  SUB_PROBLEM: "#FF8A80",
  CHOICES: "#FFAB91",
  SOLUTION: "#81C784",
  ANSWER: "#A5D6A7",
  EXAMPLE: "#26A69A",
  CONCEPT_BOX: "#4FC3F7",
  FORMULA: "#7E57C2",
  FIGURE: "#43A047",
  CAPTION: "#66BB6A",
  TABLE: "#FF8F00",
  TIP_BOX: "#FFF176",
  SIDEBAR: "#BCAAA4",
  PAGE_HEADER: "#9E9E9E",
  PAGE_FOOTER: "#9E9E9E",
  BACKGROUND: "#E0E0E0",
  DECORATION: "#BDBDBD",
  UNKNOWN: "#9E9E9E",
};

function dotColor(label: string): string {
  return LABEL_DOT[label] ?? "#9E9E9E";
}

function truncate(text: string, max: number): string {
  if (!text) return "";
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > max ? clean.slice(0, max) + "…" : clean;
}

/** 관계 타입 → 화살표 기호 */
function relSymbol(type: string): string {
  switch (type) {
    case "CONTINUES_FROM": return "→";
    case "PARENT_OF": return "├─";
    case "CAPTION_FOR": return "📎";
    default: return "─";
  }
}

export function SemanticTextPreview() {
  const nodes = useSemanticStore((s) => s.nodes);
  const relations = useSemanticStore((s) => s.relations);
  const selectedNodeId = useSemanticStore((s) => s.selectedNodeId);
  const selectNode = useSemanticStore((s) => s.selectNode);
  const getPageNumbers = useSemanticStore((s) => s.getPageNumbers);

  const pageNumbers = getPageNumbers();

  // 관계 인덱스: parentId → childIds
  const childMap = useMemo(() => {
    const m = new Map<string, string[]>();
    for (const r of relations) {
      if (r.type === "PARENT_OF") {
        const kids = m.get(r.sourceId) ?? [];
        kids.push(r.targetId);
        m.set(r.sourceId, kids);
      }
    }
    return m;
  }, [relations]);

  // 자식으로 참조된 노드 (트리에서 2차 렌더링 방지)
  const childIds = useMemo(() => {
    const set = new Set<string>();
    for (const kids of childMap.values()) {
      for (const k of kids) set.add(k);
    }
    return set;
  }, [childMap]);

  // 페이지별 그룹
  const pageGroups = useMemo(() => {
    return pageNumbers.map((page) => {
      const pageNodes = nodes
        .filter((n) => n.features.pageNumber === page)
        .sort((a, b) => a.features.y - b.features.y);
      return { page, nodes: pageNodes };
    });
  }, [nodes, pageNumbers]);

  const nodeById = useMemo(() => {
    const m = new Map<string, (typeof nodes)[0]>();
    for (const n of nodes) m.set(n.id, n);
    return m;
  }, [nodes]);

  return (
    <div className="h-full flex flex-col">
      <div className="flex-1 overflow-auto font-mono text-xs leading-5 p-3 bg-white">
        {pageGroups.map(({ page, nodes: pageNodes }) => (
          <div key={page} className="mb-4">
            <div className="text-gray-400 font-bold mb-1 border-b border-gray-200 pb-0.5">
              ── Page {page} ({pageNodes.length})
            </div>
            {pageNodes.map((node) => {
              // 자식 노드는 부모 아래에서 렌더링
              if (childIds.has(node.id)) return null;
              const children = childMap.get(node.id) ?? [];
              return (
                <NodeLine
                  key={node.id}
                  node={node}
                  depth={0}
                  isSelected={node.id === selectedNodeId}
                  onClick={() => selectNode(node.id)}
                  children={children}
                  childMap={childMap}
                  nodeById={nodeById}
                  selectedNodeId={selectedNodeId}
                  selectNode={selectNode}
                />
              );
            })}
          </div>
        ))}

        {/* 관계 요약 */}
        {relations.length > 0 && (
          <div className="mt-4 pt-3 border-t border-gray-200">
            <div className="text-gray-400 font-bold mb-1">
              ── Relations ({relations.length})
            </div>
            {relations.slice(0, 50).map((r, i) => {
              const src = nodeById.get(r.sourceId);
              const tgt = nodeById.get(r.targetId);
              return (
                <div key={i} className="text-gray-500 pl-2">
                  {relSymbol(r.type)}{" "}
                  <span className="text-gray-600">{r.type}</span>{" "}
                  <span className="text-blue-500">
                    {src?.label ?? r.sourceId}
                  </span>
                  {" → "}
                  <span className="text-blue-500">
                    {tgt?.label ?? r.targetId}
                  </span>
                </div>
              );
            })}
            {relations.length > 50 && (
              <div className="text-gray-400 pl-2">
                … +{relations.length - 50}개 더
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function NodeLine({
  node,
  depth,
  isSelected,
  onClick,
  children,
  childMap,
  nodeById,
  selectedNodeId,
  selectNode,
}: {
  node: { id: string; label: string; manualOverride: boolean; features: { textContent: string; dominantFontSize: number; y: number } };
  depth: number;
  isSelected: boolean;
  onClick: () => void;
  children: string[];
  childMap: Map<string, string[]>;
  nodeById: Map<string, any>;
  selectedNodeId: string | null;
  selectNode: (id: string) => void;
}) {
  const indent = "  ".repeat(depth);
  const prefix = depth === 0 ? "▸ " : "├ ";
  const text = truncate(node.features.textContent, 60);
  const fontSize = node.features.dominantFontSize
    ? `${(node.features.dominantFontSize / 100).toFixed(0)}pt`
    : "";

  return (
    <>
      <div
        onClick={onClick}
        className={`cursor-pointer hover:bg-blue-50 px-1 rounded ${
          isSelected ? "bg-blue-100" : ""
        }`}
      >
        <span className="text-gray-300">{indent}{prefix}</span>
        <span
          className="inline-block w-2 h-2 rounded-full mr-1"
          style={{ backgroundColor: dotColor(node.label), verticalAlign: "middle" }}
        />
        <span className="font-bold" style={{ color: dotColor(node.label) }}>
          {node.label}
        </span>
        {node.manualOverride && (
          <span className="text-orange-400 ml-0.5" title="수동 분류">✎</span>
        )}
        {fontSize && (
          <span className="text-gray-400 ml-1.5">[{fontSize}]</span>
        )}
        {text && (
          <span className="text-gray-500 ml-1.5">{text}</span>
        )}
      </div>
      {children.map((childId) => {
        const child = nodeById.get(childId);
        if (!child) return null;
        const grandChildren = childMap.get(childId) ?? [];
        return (
          <NodeLine
            key={childId}
            node={child}
            depth={depth + 1}
            isSelected={childId === selectedNodeId}
            onClick={() => selectNode(childId)}
            children={grandChildren}
            childMap={childMap}
            nodeById={nodeById}
            selectedNodeId={selectedNodeId}
            selectNode={selectNode}
          />
        );
      })}
    </>
  );
}
