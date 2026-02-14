import { useMemo } from "react";
import { useAstStore } from "../stores/useAstStore";

// ─── Resolve path to node ───────────────────────────────────────────

function resolveNode(doc: any, path: string): any {
  if (!doc || !path) return null;
  const parts = path.replace(/^root\.?/, "").split(".");
  let current = doc;
  for (const part of parts) {
    if (!part || !current) break;
    const match = part.match(/^(\w+)\[(\d+)\]$/);
    if (match) {
      current = current[match[1]]?.[parseInt(match[2])];
    } else {
      current = current[part];
    }
  }
  return current;
}

// ─── Value Renderer ─────────────────────────────────────────────────

function renderValue(value: any): React.ReactNode {
  if (value === null || value === undefined) {
    return <span className="text-gray-300">null</span>;
  }
  if (typeof value === "boolean") {
    return (
      <span className={value ? "text-green-600" : "text-red-400"}>
        {String(value)}
      </span>
    );
  }
  if (typeof value === "number") {
    return <span className="text-blue-600">{value}</span>;
  }
  if (typeof value === "string") {
    return <span className="text-amber-700">"{value}"</span>;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-gray-300">[]</span>;
    return <span className="text-gray-500">[{value.length} items]</span>;
  }
  if (typeof value === "object") {
    const keys = Object.keys(value);
    return (
      <span className="text-gray-500">{`{${keys.slice(0, 3).join(", ")}${keys.length > 3 ? "…" : ""}}`}</span>
    );
  }
  return String(value);
}

// ─── Detail Content ─────────────────────────────────────────────────

function DetailContent({ node }: { node: any }) {
  if (!node) {
    return (
      <div className="p-4 text-gray-400 text-sm">
        트리에서 노드를 선택하면 속성이 표시됩니다.
      </div>
    );
  }

  return (
    <div className="p-2 text-xs overflow-auto h-full">
      <table className="w-full">
        <tbody>
          {Object.entries(node).map(([key, value]) => {
            if (
              key === "paragraphs" ||
              key === "items" ||
              key === "blocks" ||
              key === "rows" ||
              key === "cells" ||
              key === "sections"
            )
              return null;
            return (
              <tr key={key} className="border-b border-gray-100">
                <td className="py-1 pr-2 font-medium text-gray-600 whitespace-nowrap align-top">
                  {key}
                </td>
                <td className="py-1 text-gray-800 break-all">
                  {renderValue(value)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// ─── Main Panel ─────────────────────────────────────────────────────

export function ASTDetailPanel() {
  const { astDoc, selectedPath } = useAstStore();

  const selectedNode = useMemo(
    () => resolveNode(astDoc, selectedPath || ""),
    [astDoc, selectedPath]
  );

  return (
    <div className="h-full flex flex-col min-h-0">
      <div className="px-2 py-1 border-b bg-gray-50 text-[10px] text-gray-500 font-medium shrink-0">
        {selectedPath || "No selection"}
      </div>
      <div className="flex-1 overflow-auto min-h-0">
        <DetailContent node={selectedNode} />
      </div>
    </div>
  );
}
