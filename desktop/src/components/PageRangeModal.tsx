import { useState } from "react";
import { useAppStore } from "../stores/useAppStore";

export function PageRangeModal() {
  const {
    showPageRangeModal,
    inddPages,
    closePageRangeModal,
    confirmPageRangeAndExtract,
  } = useAppStore();

  const [localStart, setLocalStart] = useState<string>("");
  const [localEnd, setLocalEnd] = useState<string>("");

  if (!showPageRangeModal || inddPages.length === 0) return null;

  const firstLabel = inddPages[0].name;
  const lastLabel = inddPages[inddPages.length - 1].name;

  const resolvePageIndex = (val: string): number | null => {
    if (!val.trim()) return null;
    const idx = inddPages.findIndex((p) => p.name === val.trim());
    if (idx >= 0) return idx + 1; // 1-based
    const num = Number(val);
    return num > 0 && num <= inddPages.length ? num : null;
  };

  const handleConfirm = () => {
    confirmPageRangeAndExtract(
      resolvePageIndex(localStart),
      resolvePageIndex(localEnd),
    );
  };

  const handleAll = () => {
    confirmPageRangeAndExtract(null, null);
  };

  // 선택 범위 미리보기
  const startIdx = resolvePageIndex(localStart);
  const endIdx = resolvePageIndex(localEnd);
  const rangeStart = startIdx ?? 1;
  const rangeEnd = endIdx ?? inddPages.length;
  const pageCount = Math.max(0, rangeEnd - rangeStart + 1);

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-[420px] max-h-[80vh] flex flex-col">
        {/* Header */}
        <div className="px-5 pt-5 pb-3">
          <h2 className="text-base font-bold text-gray-800">페이지 범위 선택</h2>
          <p className="text-xs text-gray-500 mt-1">
            전체 {inddPages.length}페이지 ({firstLabel} ~ {lastLabel})
          </p>
        </div>

        {/* Page Grid */}
        <div className="px-5 py-2 max-h-[240px] overflow-y-auto border-y">
          <div className="grid grid-cols-10 gap-1">
            {inddPages.map((page, i) => {
              const seq = i + 1;
              const inRange = seq >= rangeStart && seq <= rangeEnd;
              const isEdge =
                (seq === rangeStart && localStart !== "") ||
                (seq === rangeEnd && localEnd !== "");
              return (
                <button
                  key={page.index}
                  onClick={() => {
                    if (!localStart || (localStart && localEnd)) {
                      setLocalStart(page.name);
                      setLocalEnd("");
                    } else {
                      const startSeq = resolvePageIndex(localStart) ?? 1;
                      if (seq >= startSeq) {
                        setLocalEnd(page.name);
                      } else {
                        setLocalStart(page.name);
                        setLocalEnd("");
                      }
                    }
                  }}
                  className={`
                    w-full aspect-square flex items-center justify-center text-[11px] rounded
                    transition-colors cursor-pointer
                    ${isEdge
                      ? "bg-blue-600 text-white font-bold"
                      : inRange && (localStart || localEnd)
                        ? "bg-blue-100 text-blue-700"
                        : "bg-gray-50 text-gray-600 hover:bg-gray-100"
                    }
                  `}
                  title={`${page.name}p`}
                >
                  {page.name}
                </button>
              );
            })}
          </div>
        </div>

        {/* Range Input */}
        <div className="px-5 py-3">
          <div className="flex items-center gap-2">
            <label className="text-sm text-gray-600">범위:</label>
            <input
              type="text"
              inputMode="numeric"
              placeholder={firstLabel}
              value={localStart}
              onChange={(e) => setLocalStart(e.target.value)}
              className="border border-gray-300 rounded px-2 py-1 text-sm w-20 text-center focus:ring-1 focus:ring-blue-400 focus:border-blue-400"
            />
            <span className="text-gray-400">~</span>
            <input
              type="text"
              inputMode="numeric"
              placeholder={lastLabel}
              value={localEnd}
              onChange={(e) => setLocalEnd(e.target.value)}
              className="border border-gray-300 rounded px-2 py-1 text-sm w-20 text-center focus:ring-1 focus:ring-blue-400 focus:border-blue-400"
            />
            {(localStart || localEnd) && (
              <span className="text-xs text-gray-500 ml-1">
                ({pageCount}p)
              </span>
            )}
          </div>
        </div>

        {/* Actions */}
        <div className="px-5 pb-4 flex items-center justify-between">
          <button
            onClick={handleAll}
            className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded transition-colors"
          >
            전체 페이지
          </button>
          <div className="flex items-center gap-2">
            <button
              onClick={closePageRangeModal}
              className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 rounded transition-colors"
            >
              취소
            </button>
            <button
              onClick={handleConfirm}
              className="px-5 py-2 bg-blue-600 text-white text-sm rounded hover:bg-blue-700 transition-colors"
            >
              확인
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
