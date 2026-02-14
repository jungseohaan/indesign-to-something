import { useState } from "react";
import { useAppStore } from "../stores/useAppStore";
import type { SpreadInfo, PageInfo, FrameInfo, MasterSpreadInfo } from "../types";

export function InventoryView() {
  const { structure, selectedSpread, selectedPage, selectedImage, selectedTextFrame, selectedMaster } = useAppStore();
  const { selectSpread, selectPage, selectFrame, selectMaster } = useAppStore();
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  if (!structure) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400 text-sm">
        IDML 파일을 열어주세요
      </div>
    );
  }

  const toggleGroup = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const frameIcon = (type: string) => {
    switch (type) {
      case "text": return "T";
      case "image": return "I";
      case "vector": return "V";
      case "table": return "#";
      case "group": return "G";
      default: return "?";
    }
  };

  const frameColor = (type: string) => {
    switch (type) {
      case "text": return "text-blue-600 bg-blue-50";
      case "image": return "text-green-600 bg-green-50";
      case "vector": return "text-purple-600 bg-purple-50";
      case "table": return "text-orange-600 bg-orange-50";
      case "group": return "text-amber-600 bg-amber-50";
      default: return "text-gray-600 bg-gray-50";
    }
  };

  const renderFrame = (frame: FrameInfo, depth: number) => {
    const paddingLeft = 8 + depth * 16;
    const isGroup = frame.type === "group";
    const isExpanded = expandedGroups.has(frame.id);
    const hasChildren = frame.children && frame.children.length > 0;

    return (
      <div key={frame.id}>
        <div
          onClick={(e) => {
            if (isGroup && hasChildren) {
              toggleGroup(frame.id, e);
              selectFrame(frame);
            } else {
              selectFrame(frame);
            }
          }}
          style={{ paddingLeft: `${paddingLeft * 0.25}rem` }}
          className={`py-0.5 cursor-pointer hover:bg-blue-50 flex items-center gap-2 pr-3 ${
            (selectedImage?.id === frame.id || selectedTextFrame?.id === frame.id)
              ? "bg-blue-100"
              : ""
          }`}
        >
          {isGroup && hasChildren ? (
            <span className="text-[10px] text-gray-400 w-3 text-center select-none">
              {isExpanded ? "▼" : "▶"}
            </span>
          ) : (
            <span className="w-3" />
          )}
          <span
            className={`inline-flex items-center justify-center w-4 h-4 rounded text-[10px] font-bold flex-shrink-0 ${frameColor(
              frame.type
            )}`}
          >
            {frameIcon(frame.type)}
          </span>
          <span className="truncate text-xs text-gray-700">
            {frame.label || frame.id}
          </span>
          <span className="text-[10px] text-gray-400 ml-auto whitespace-nowrap">
            {Math.round(frame.width)}x{Math.round(frame.height)}
          </span>
        </div>
        {isGroup && isExpanded && hasChildren && (
          <div>
            {frame.children!.map((child) => renderFrame(child, depth + 1))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="h-full overflow-auto text-sm">
      {/* Summary */}
      <div className="px-3 py-2 bg-gray-50 border-b text-xs text-gray-500">
        {structure.spreads.length} spreads &middot;{" "}
        {structure.total_text_frames} text &middot;{" "}
        {structure.total_image_frames} image &middot;{" "}
        {structure.total_vector_shapes} vector &middot;{" "}
        {structure.total_tables} table
      </div>

      {/* Master Spreads */}
      {structure.master_spreads.length > 0 && (
        <div className="border-b">
          <div className="px-3 py-1.5 bg-gray-50 text-xs font-medium text-gray-500 uppercase tracking-wide">
            Masters
          </div>
          {structure.master_spreads.map((ms: MasterSpreadInfo) => (
            <div
              key={ms.id}
              onClick={() => selectMaster(ms)}
              className={`px-3 py-1.5 cursor-pointer border-b border-gray-100 hover:bg-blue-50 ${
                selectedMaster?.id === ms.id ? "bg-blue-100" : ""
              }`}
            >
              <span className="font-medium">{ms.name || ms.id}</span>
              <span className="text-gray-400 ml-2 text-xs">
                {ms.page_count}p
              </span>
            </div>
          ))}
        </div>
      )}

      {/* Spreads */}
      {structure.spreads.map((spread: SpreadInfo, si: number) => (
        <div key={spread.id} className="border-b">
          <div
            onClick={() => selectSpread(spread)}
            className={`px-3 py-1.5 cursor-pointer font-medium hover:bg-blue-50 flex items-center justify-between ${
              selectedSpread?.id === spread.id ? "bg-blue-100" : "bg-gray-50"
            }`}
          >
            <span>Spread {si + 1}</span>
            <span className="text-xs text-gray-400">
              {spread.page_count}p &middot; {spread.text_frame_count}T{" "}
              {spread.image_frame_count}I {spread.vector_count}V
            </span>
          </div>

          {spread.pages.map((page: PageInfo) => (
            <div key={page.id}>
              <div
                onClick={() => selectPage(page)}
                className={`px-5 py-1 cursor-pointer hover:bg-blue-50 flex items-center justify-between ${
                  selectedPage?.id === page.id ? "bg-blue-100" : ""
                }`}
              >
                <span>
                  {page.name || `Page ${page.page_number}`}
                </span>
                <span className="text-xs text-gray-400">
                  {page.frames.length} frames
                </span>
              </div>

              {page.frames.map((frame: FrameInfo) => renderFrame(frame, 0))}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
