import { useState } from "react";
import { useAppStore } from "../stores/useAppStore";
import type { SpreadInfo, PageInfo, FrameInfo, MasterSpreadInfo } from "../types";

function FrameIcon({ type }: { type: string }) {
  const iconMap: Record<string, string> = {
    text: "📝",
    image: "🖼️",
    vector: "📐",
    table: "📊",
  };
  return <span className="mr-1">{iconMap[type] || "📄"}</span>;
}

function FrameItem({
  frame,
  isSelected,
  onSelect,
  selectedFrameId,
  onSelectFrame,
  depth = 0,
}: {
  frame: FrameInfo;
  isSelected: boolean;
  onSelect: () => void;
  selectedFrameId?: string | null;
  onSelectFrame?: (frame: FrameInfo) => void;
  depth?: number;
}) {
  const [isExpanded, setIsExpanded] = useState(false);
  // text, image, vector 모두 선택 가능
  const isClickable = frame.type === "text" || frame.type === "image" || frame.type === "vector";
  const hasChildren = frame.children && frame.children.length > 0;
  const paddingLeft = 12 + depth * 4; // pl-12 = 3rem, 각 depth마다 1rem 추가

  return (
    <div>
      <div
        className={`py-1 text-sm flex items-center ${
          isSelected
            ? "bg-blue-100 text-blue-800"
            : "text-gray-600 hover:bg-gray-100"
        } ${isClickable ? "cursor-pointer" : ""}`}
        style={{ paddingLeft: `${paddingLeft * 4}px` }}
        onClick={isClickable ? onSelect : undefined}
      >
        {hasChildren && (
          <span
            className="mr-1 text-xs text-gray-400 hover:text-gray-600"
            onClick={(e) => {
              e.stopPropagation();
              setIsExpanded(!isExpanded);
            }}
          >
            {isExpanded ? "▼" : "▶"}
          </span>
        )}
        <FrameIcon type={frame.type} />
        <span className={isSelected ? "text-blue-600" : "text-gray-500"}>
          [{frame.type}]
        </span>{" "}
        {frame.label || frame.id}
        {hasChildren && (
          <span className="ml-1 text-xs text-orange-500">
            ({frame.children!.length} inline)
          </span>
        )}
      </div>
      {isExpanded && hasChildren && (
        <div>
          {frame.children!.map((child) => (
            <FrameItem
              key={child.id}
              frame={child}
              isSelected={child.id === (selectedFrameId ?? null)}
              onSelect={() => onSelectFrame?.(child)}
              selectedFrameId={selectedFrameId}
              onSelectFrame={onSelectFrame}
              depth={depth + 1}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function PageItem({
  page,
  isExpanded,
  isSelected,
  onToggle,
  onSelectPage,
  selectedFrameId,
  onSelectFrame,
}: {
  page: PageInfo;
  isExpanded: boolean;
  isSelected: boolean;
  onToggle: () => void;
  onSelectPage: () => void;
  selectedFrameId: string | null;
  onSelectFrame: (frame: FrameInfo) => void;
}) {
  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onSelectPage();
  };

  const handleToggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    onToggle();
  };

  return (
    <div>
      <div
        className={`pl-8 py-1 flex items-center cursor-pointer ${
          isSelected ? "bg-blue-100" : "hover:bg-gray-100"
        }`}
        onClick={handleClick}
      >
        <span
          className="mr-2 text-gray-400 hover:text-gray-600"
          onClick={handleToggle}
        >
          {isExpanded ? "▼" : "▶"}
        </span>
        <span className={isSelected ? "text-blue-700" : ""}>
          📄 {page.name || page.id}
        </span>
        <span className="ml-2 text-xs text-gray-400">
          ({page.frames.length} frames)
        </span>
      </div>
      {isExpanded && (
        <div>
          {page.frames.map((frame) => (
            <FrameItem
              key={frame.id}
              frame={frame}
              isSelected={frame.id === selectedFrameId}
              onSelect={() => onSelectFrame(frame)}
              selectedFrameId={selectedFrameId}
              onSelectFrame={onSelectFrame}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function SpreadItem({
  spread,
  index,
  isSelected,
  selectedPageId,
  selectedFrameId,
  onSelectSpread,
  onSelectPage,
  onSelectFrame,
}: {
  spread: SpreadInfo;
  index: number;
  isSelected: boolean;
  selectedPageId: string | null;
  selectedFrameId: string | null;
  onSelectSpread: () => void;
  onSelectPage: (page: PageInfo) => void;
  onSelectFrame: (frame: FrameInfo) => void;
}) {
  const [isExpanded, setIsExpanded] = useState(true);
  const [expandedPages, setExpandedPages] = useState<Set<string>>(new Set());

  const togglePage = (pageId: string) => {
    setExpandedPages((prev) => {
      const next = new Set(prev);
      if (next.has(pageId)) {
        next.delete(pageId);
      } else {
        next.add(pageId);
      }
      return next;
    });
  };

  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onSelectSpread();
  };

  const handleToggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsExpanded(!isExpanded);
  };

  return (
    <div className="border-b last:border-b-0">
      <div
        className={`py-2 px-2 flex items-center cursor-pointer font-medium ${
          isSelected ? "bg-blue-100" : "hover:bg-gray-50"
        }`}
        onClick={handleClick}
      >
        <span
          className="mr-2 text-gray-400 hover:text-gray-600"
          onClick={handleToggle}
        >
          {isExpanded ? "▼" : "▶"}
        </span>
        <span className={isSelected ? "text-blue-700" : ""}>
          📁 Spread {index + 1}
        </span>
        {spread.master_spread_name && (
          <span className="ml-2 text-xs text-purple-600 bg-purple-50 px-1.5 py-0.5 rounded">
            {spread.master_spread_name}
          </span>
        )}
        <span className="ml-2 text-xs text-gray-500">
          ({spread.page_count} pages, {spread.text_frame_count} text,{" "}
          {spread.image_frame_count} images, {spread.vector_count} vectors)
        </span>
      </div>
      {isExpanded && (
        <div className="pb-2">
          {spread.pages.map((page) => (
            <PageItem
              key={page.id}
              page={page}
              isExpanded={expandedPages.has(page.id)}
              isSelected={page.id === selectedPageId}
              onToggle={() => togglePage(page.id)}
              onSelectPage={() => onSelectPage(page)}
              selectedFrameId={selectedFrameId}
              onSelectFrame={onSelectFrame}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function MasterSpreadItem({
  master,
  isSelected,
  onSelect,
}: {
  master: MasterSpreadInfo;
  isSelected: boolean;
  onSelect: () => void;
}) {
  const [isExpanded, setIsExpanded] = useState(false);

  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    onSelect();
  };

  const handleToggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsExpanded(!isExpanded);
  };

  const totalElements = master.text_frame_count + master.image_frame_count +
    master.vector_count + master.group_count;

  return (
    <div className="border-b last:border-b-0">
      <div
        className={`py-2 px-2 flex items-center cursor-pointer font-medium ${
          isSelected ? "bg-purple-100" : "hover:bg-purple-50"
        }`}
        onClick={handleClick}
      >
        <span
          className="mr-2 text-gray-400 hover:text-gray-600"
          onClick={handleToggle}
        >
          {isExpanded ? "▼" : "▶"}
        </span>
        <span className={isSelected ? "text-purple-800" : "text-purple-700"}>
          {master.name}
        </span>
        <span className="ml-2 text-xs text-gray-500">
          ({master.page_count} pages, {totalElements} elements)
        </span>
      </div>
      {isExpanded && (
        <div className="pb-2 pl-8 text-sm text-gray-600">
          <div className="py-0.5">
            텍스트: {master.text_frame_count}개
            {master.image_frame_count > 0 && <> · 이미지: {master.image_frame_count}개</>}
            {master.vector_count > 0 && <> · 벡터: {master.vector_count}개</>}
            {master.group_count > 0 && <> · 그룹: {master.group_count}개</>}
          </div>
          {master.applied_pages.length > 0 && (
            <div className="py-0.5 text-xs text-gray-400">
              적용 페이지: {master.applied_pages.join(", ")}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export function InventoryView() {
  const {
    idmlStructure,
    isAnalyzing,
    selectedImage,
    selectedTextFrame,
    selectedSpread,
    selectedPage,
    selectedMaster,
    selectFrame,
    selectSpread,
    selectPage,
    selectMaster,
  } = useAppStore();

  // 현재 선택된 프레임 ID
  const selectedFrameId = selectedTextFrame?.id || selectedImage?.id || null;
  const selectedSpreadId = selectedSpread?.id || null;
  const selectedPageId = selectedPage?.id || null;
  const selectedMasterId = selectedMaster?.id || null;

  if (isAnalyzing) {
    return (
      <div className="flex items-center justify-center h-full text-gray-500">
        분석 중...
      </div>
    );
  }

  if (!idmlStructure) {
    return (
      <div className="flex items-center justify-center h-full text-gray-400">
        IDML 파일을 선택하세요
      </div>
    );
  }

  const hasMasterSpreads = idmlStructure.master_spreads && idmlStructure.master_spreads.length > 0;

  return (
    <div className="h-full overflow-auto">
      <div className="p-2 bg-gray-100 border-b font-medium text-sm">
        인벤토리 뷰
        <span className="ml-2 text-gray-500 font-normal">
          총 {idmlStructure.total_text_frames} 텍스트,{" "}
          {idmlStructure.total_image_frames} 이미지,{" "}
          {idmlStructure.total_vector_shapes} 벡터,{" "}
          {idmlStructure.total_tables} 테이블
        </span>
      </div>

      {/* 마스터 스프레드 섹션 */}
      {hasMasterSpreads && (
        <>
          <div className="px-2 py-1.5 bg-purple-50 border-b text-xs font-medium text-purple-700 uppercase tracking-wide">
            Master Spreads ({idmlStructure.master_spreads.length})
          </div>
          <div>
            {idmlStructure.master_spreads.map((master) => (
              <MasterSpreadItem
                key={master.id}
                master={master}
                isSelected={master.id === selectedMasterId}
                onSelect={() => selectMaster(master)}
              />
            ))}
          </div>
        </>
      )}

      {/* 스프레드 섹션 */}
      <div className="px-2 py-1.5 bg-gray-50 border-b border-t text-xs font-medium text-gray-600 uppercase tracking-wide">
        Spreads ({idmlStructure.spreads.length})
      </div>
      <div>
        {idmlStructure.spreads.map((spread, index) => (
          <SpreadItem
            key={spread.id}
            spread={spread}
            index={index}
            isSelected={spread.id === selectedSpreadId}
            selectedPageId={selectedPageId}
            selectedFrameId={selectedFrameId}
            onSelectSpread={() => selectSpread(spread)}
            onSelectPage={selectPage}
            onSelectFrame={selectFrame}
          />
        ))}
      </div>
    </div>
  );
}
