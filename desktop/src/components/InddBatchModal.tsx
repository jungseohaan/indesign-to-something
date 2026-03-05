import { useState } from "react";
import { useAppStore } from "../stores/useAppStore";

export function InddBatchModal() {
  const {
    showBatchModal,
    batchScanResult,
    batchResults,
    isBatchProcessing,
    batchCurrentIndex,
    closeBatchModal,
    startBatch,
    cancelBatch,
  } = useAppStore();

  // 선택된 파일 경로 (처리 시작 전)
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [initialized, setInitialized] = useState(false);

  if (!showBatchModal) return null;

  // 모달 열릴 때 전체 선택으로 초기화
  if (batchScanResult && !initialized && !isBatchProcessing) {
    const allPaths = new Set<string>();
    for (const p of batchScanResult.direct_files) {
      allPaths.add(p);
    }
    for (const sf of batchScanResult.subfolder_files) {
      for (const f of sf.indd_files) {
        allPaths.add(f.path);
      }
    }
    if (allPaths.size > 0 && selected.size === 0) {
      setSelected(allPaths);
      setInitialized(true);
    }
  }

  // 처리 중 또는 처리 완료: 진행률/결과 UI (선택 UI보다 먼저 체크)
  if (isBatchProcessing || batchResults.length > 0) {
    const total = batchResults.length;
    const doneCount = batchResults.filter((r) => r.status === "done").length;
    const errorCount = batchResults.filter((r) => r.status === "error").length;
    const completedCount = doneCount + errorCount;
    const progressPct = total > 0 ? Math.round((completedCount / total) * 100) : 0;
    const allDone = !isBatchProcessing;

    return (
      <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
        <div className="bg-white rounded-lg shadow-xl w-[540px] max-h-[80vh] flex flex-col">
          {/* Header */}
          <div className="px-5 pt-5 pb-3">
            <h2 className="text-base font-bold text-gray-800">
              INDD 일괄 변환{" "}
              {isBatchProcessing && batchCurrentIndex >= 0 && (
                <span className="text-gray-400 font-normal">
                  ({batchCurrentIndex + 1}/{total})
                </span>
              )}
              {allDone && (
                <span className="text-green-600 font-normal ml-2">완료</span>
              )}
            </h2>
            {allDone && (
              <p className="text-xs text-gray-500 mt-1">
                성공 {doneCount}개{errorCount > 0 && `, 실패 ${errorCount}개`}
              </p>
            )}
          </div>

          {/* File Progress List */}
          <div className="px-5 py-2 max-h-[400px] overflow-y-auto border-y">
            {batchResults.map((r, i) => (
              <div
                key={r.path}
                className={`flex items-center gap-2 py-1.5 px-2 rounded text-sm ${
                  i === batchCurrentIndex && isBatchProcessing ? "bg-blue-50" : ""
                }`}
              >
                <StatusIcon status={r.status} />
                <span className="flex-1 truncate text-gray-700" title={r.path}>
                  {r.filename}
                </span>
                <span className="text-xs text-gray-400 shrink-0">
                  {r.status === "extracting" && "추출 중..."}
                  {r.status === "converting" && "변환 중..."}
                  {r.status === "done" && "완료"}
                  {r.status === "error" && (
                    <span className="text-red-500" title={r.error}>실패</span>
                  )}
                </span>
              </div>
            ))}
          </div>

          {/* Progress Bar */}
          <div className="px-5 pt-3 pb-1">
            <div className="w-full bg-gray-200 rounded-full h-2">
              <div
                className={`h-2 rounded-full transition-all duration-300 ${
                  allDone && errorCount === 0
                    ? "bg-green-500"
                    : allDone && errorCount > 0
                      ? "bg-yellow-500"
                      : "bg-blue-500"
                }`}
                style={{ width: `${progressPct}%` }}
              />
            </div>
            <p className="text-xs text-gray-400 text-right mt-1">{progressPct}%</p>
          </div>

          {/* Actions */}
          <div className="px-5 pb-4 flex justify-end">
            {isBatchProcessing ? (
              <button
                onClick={cancelBatch}
                className="px-4 py-2 text-sm text-red-500 hover:text-red-700 hover:bg-red-50 rounded transition-colors"
              >
                중단
              </button>
            ) : (
              <button
                onClick={() => { setInitialized(false); closeBatchModal(); }}
                className="px-5 py-2 bg-blue-600 text-white text-sm rounded hover:bg-blue-700 transition-colors"
              >
                닫기
              </button>
            )}
          </div>
        </div>
      </div>
    );
  }

  // 파일 선택 UI (아직 처리 시작 전)
  if (batchScanResult) {
    const allPaths: string[] = [...batchScanResult.direct_files];
    for (const sf of batchScanResult.subfolder_files) {
      for (const f of sf.indd_files) {
        allPaths.push(f.path);
      }
    }

    const hasDirectFiles = batchScanResult.direct_files.length > 0;
    const folderCount = batchScanResult.subfolder_files.length + (hasDirectFiles ? 1 : 0);

    const toggleFile = (path: string) => {
      setSelected((prev) => {
        const next = new Set(prev);
        if (next.has(path)) next.delete(path);
        else next.add(path);
        return next;
      });
    };

    const toggleAll = () => {
      if (selected.size === allPaths.length) {
        setSelected(new Set());
      } else {
        setSelected(new Set(allPaths));
      }
    };

    const handleStart = () => {
      const paths = allPaths.filter((p) => selected.has(p));
      if (paths.length > 0) {
        setInitialized(false);
        startBatch(paths);
      }
    };

    return (
      <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
        <div className="bg-white rounded-lg shadow-xl w-[540px] max-h-[80vh] flex flex-col">
          {/* Header */}
          <div className="px-5 pt-5 pb-3">
            <h2 className="text-base font-bold text-gray-800">INDD 일괄 변환</h2>
            <p className="text-xs text-gray-500 mt-1">
              {folderCount > 1
                ? `${folderCount}개 폴더에서 ${allPaths.length}개 InDesign 문서를 찾았습니다.`
                : `${allPaths.length}개 InDesign 문서를 찾았습니다.`}
            </p>
          </div>

          {/* File List */}
          <div className="px-5 py-2 max-h-[400px] overflow-y-auto border-y">
            {/* 직접 하위 파일 */}
            {hasDirectFiles && (
              <div className="mb-2">
                {batchScanResult.direct_files.map((p) => {
                  const filename = p.replace(/^.*[/\\]/, "");
                  return (
                    <label
                      key={p}
                      className="flex items-center gap-2 py-1 hover:bg-gray-50 rounded cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={selected.has(p)}
                        onChange={() => toggleFile(p)}
                        className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                      />
                      <span className="text-sm text-gray-700 truncate" title={filename}>
                        {filename}
                      </span>
                    </label>
                  );
                })}
              </div>
            )}
            {/* 서브폴더 파일 */}
            {batchScanResult.subfolder_files.map((sf) => (
              <div key={sf.folder_path} className="mb-2">
                <div className="text-xs font-medium text-gray-500 mb-1 truncate" title={sf.folder_path}>
                  {sf.folder_name}
                </div>
                {sf.indd_files.map((f) => (
                  <label
                    key={f.path}
                    className="flex items-center gap-2 pl-3 py-1 hover:bg-gray-50 rounded cursor-pointer"
                  >
                    <input
                      type="checkbox"
                      checked={selected.has(f.path)}
                      onChange={() => toggleFile(f.path)}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                    <span className="text-sm text-gray-700 truncate" title={f.filename}>
                      {f.filename}
                    </span>
                  </label>
                ))}
              </div>
            ))}
          </div>

          {/* Actions */}
          <div className="px-5 py-4 flex items-center justify-between">
            <button
              onClick={toggleAll}
              className="px-3 py-1.5 text-xs text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded transition-colors"
            >
              {selected.size === allPaths.length ? "선택 해제" : "전체 선택"}
            </button>
            <div className="flex items-center gap-2">
              <button
                onClick={() => { setInitialized(false); closeBatchModal(); }}
                className="px-4 py-2 text-sm text-gray-500 hover:text-gray-700 rounded transition-colors"
              >
                취소
              </button>
              <button
                onClick={handleStart}
                disabled={selected.size === 0}
                className="px-5 py-2 bg-blue-600 text-white text-sm rounded hover:bg-blue-700 disabled:opacity-50 transition-colors"
              >
                변환 시작 ({selected.size}개)
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return null;
}

function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case "done":
      return <span className="text-green-500 text-sm">&#10003;</span>;
    case "error":
      return <span className="text-red-500 text-sm">&#10007;</span>;
    case "extracting":
    case "converting":
      return <span className="text-blue-500 text-sm animate-spin inline-block">&#9696;</span>;
    default:
      return <span className="text-gray-300 text-sm">&#9679;</span>;
  }
}
