import { useState, useEffect, useRef } from "react";
import { useAppStore } from "../stores/useAppStore";

function useElapsedTick(intervalMs = 500) {
  const [, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick((t) => t + 1), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
}

function formatElapsed(startMs: number, endMs?: number): string {
  const ms = (endMs ?? Date.now()) - startMs;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}초`;
  return `${Math.floor(s / 60)}분 ${s % 60}초`;
}

export function InddBatchModal() {
  const {
    showBatchModal,
    batchScanResult,
    selectedFolderPaths,
    batchResults,
    isBatchProcessing,
    batchCurrentIndex,
    batchCurrentPhaseMessage,
    batchError,
    noPreview,
    setNoPreview,
    perfMode,
    setPerfMode,
    extractChunkSize,
    setExtractChunkSize,
    outputFormat,
    setOutputFormat,
    closeBatchModal,
    startBatch,
    cancelBatch,
    addFolders,
    removeFolderFromBatch,
  } = useAppStore();

  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [initialized, setInitialized] = useState(false);
  useElapsedTick(500);

  if (!showBatchModal) return null;

  // 모달 열릴 때 전체 선택으로 초기화
  if (batchScanResult && !initialized && !isBatchProcessing) {
    const allPaths = new Set<string>();
    for (const p of batchScanResult.direct_files) allPaths.add(p);
    for (const sf of batchScanResult.subfolder_files) {
      for (const f of sf.indd_files) allPaths.add(f.path);
    }
    if (allPaths.size > 0 && selected.size === 0) {
      setSelected(allPaths);
      setInitialized(true);
    }
  }

  // 처리 중 또는 처리 완료 UI
  if (isBatchProcessing || batchResults.length > 0) {
    const total = batchResults.length;
    const doneCount = batchResults.filter((r) => r.status === "done").length;
    const errorCount = batchResults.filter((r) => r.status === "error").length;
    const completedCount = doneCount + errorCount;
    const progressPct = total > 0 ? Math.round((completedCount / total) * 100) : 0;
    const allDone = !isBatchProcessing;

    return (
      <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
        <div className="bg-white rounded-lg shadow-xl w-[580px] max-h-[80vh] flex flex-col">
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
            {allDone ? (
              <p className="text-xs text-gray-500 mt-1">
                성공 {doneCount}개{errorCount > 0 && `, 실패 ${errorCount}개`}
              </p>
            ) : batchCurrentPhaseMessage ? (
              <p className="text-xs text-blue-500 mt-1 truncate" title={batchCurrentPhaseMessage}>
                {batchCurrentPhaseMessage}
              </p>
            ) : null}
          </div>

          {/* File Progress List */}
          <div className="px-5 py-2 max-h-[400px] overflow-y-auto border-y">
            {batchResults.map((r, i) => {
              const isActive = i === batchCurrentIndex && isBatchProcessing;
              const elapsed = r.startedAt
                ? formatElapsed(r.startedAt, r.completedAt)
                : null;
              return (
                <div
                  key={r.path}
                  className={`py-1.5 px-2 rounded text-sm ${isActive ? "bg-blue-50" : ""}`}
                >
                  <div className="flex items-center gap-2">
                    <StatusIcon status={r.status} />
                    <span className="flex-1 truncate text-gray-700" title={r.path}>
                      {r.filename}
                    </span>
                    <span className="text-xs text-gray-400 shrink-0 flex items-center gap-1.5">
                      {r.cached && (
                        <span
                          className="px-1.5 py-0.5 rounded bg-green-50 text-green-600 text-[10px] font-medium"
                          title="추출 캐시 사용 (ExtendScript 건너뜀)"
                        >
                          캐시
                        </span>
                      )}
                      {elapsed && (
                        <span className="text-gray-400 text-[11px]">{elapsed}</span>
                      )}
                      {r.status === "extracting" && "추출 중..."}
                      {r.status === "converting" && "변환 중..."}
                      {r.status === "done" && "완료"}
                      {r.status === "error" && (
                        <span className="text-red-500">실패</span>
                      )}
                    </span>
                  </div>
                  {r.status === "error" && r.error && (
                    <div className="ml-6 mt-1 text-xs text-red-400 break-words whitespace-pre-wrap">
                      {r.error}
                    </div>
                  )}
                </div>
              );
            })}
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

  // 파일 선택 UI (처리 시작 전)
  if (batchScanResult) {
    const allPaths: string[] = [...batchScanResult.direct_files];
    for (const sf of batchScanResult.subfolder_files) {
      for (const f of sf.indd_files) allPaths.push(f.path);
    }

    const hasDirectFiles = batchScanResult.direct_files.length > 0;

    const toggleFile = (path: string) => {
      setSelected((prev) => {
        const next = new Set(prev);
        if (next.has(path)) next.delete(path);
        else next.add(path);
        return next;
      });
    };

    const toggleAll = () => {
      if (selected.size === allPaths.length) setSelected(new Set());
      else setSelected(new Set(allPaths));
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
        <div className="bg-white rounded-lg shadow-xl w-[580px] max-h-[85vh] flex flex-col">
          {/* Header */}
          <div className="px-5 pt-5 pb-3">
            <h2 className="text-base font-bold text-gray-800">INDD 일괄 변환</h2>
            <p className="text-xs text-gray-500 mt-1">
              {allPaths.length}개 InDesign 문서
            </p>
          </div>

          {/* Folder List */}
          <div className="px-5 pb-2 flex flex-wrap gap-1.5">
            {selectedFolderPaths.map((fp) => {
              const folderName = fp.replace(/^.*[/\\]/, "") || fp;
              return (
                <span
                  key={fp}
                  className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-50 border border-blue-200 text-blue-700 text-xs rounded-full"
                  title={fp}
                >
                  {folderName}
                  <button
                    onClick={() => {
                      removeFolderFromBatch(fp);
                      setSelected((prev) => {
                        const next = new Set(prev);
                        for (const sf of batchScanResult.subfolder_files) {
                          if (sf.folder_path === fp) {
                            for (const f of sf.indd_files) next.delete(f.path);
                          }
                        }
                        return next;
                      });
                    }}
                    className="text-blue-400 hover:text-blue-700 leading-none ml-0.5"
                    title="폴더 제거"
                  >
                    ×
                  </button>
                </span>
              );
            })}
            <button
              onClick={addFolders}
              className="inline-flex items-center gap-1 px-2 py-0.5 border border-dashed border-gray-300 text-gray-500 text-xs rounded-full hover:bg-gray-50 hover:border-gray-400 transition-colors"
            >
              + 폴더 추가
            </button>
          </div>

          {/* Error Banner */}
          {batchError && (
            <div className="mx-5 mb-2 px-3 py-2 bg-red-50 border border-red-200 rounded text-xs text-red-700">
              {batchError}
            </div>
          )}

          {/* File List */}
          <div className="px-5 py-2 flex-1 min-h-0 max-h-[360px] overflow-y-auto border-y">
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
          <div className="px-5 py-4 flex items-center justify-between gap-4">
            <div className="flex flex-wrap items-center gap-3">
              <button
                onClick={toggleAll}
                className="px-3 py-1.5 text-xs text-gray-600 hover:text-gray-800 hover:bg-gray-100 rounded transition-colors"
              >
                {selected.size === allPaths.length ? "선택 해제" : "전체 선택"}
              </button>
              <label className="flex items-center gap-1.5 text-xs text-gray-600 cursor-pointer">
                <input
                  type="checkbox"
                  checked={noPreview}
                  onChange={(e) => setNoPreview(e.target.checked)}
                  className="rounded border-gray-300"
                />
                변환 후 열지 않음
              </label>
              <label
                className="flex items-center gap-1.5 text-xs text-gray-600"
                title="INDD 추출 렌더링 모드. 빠름은 150dpi, 표준은 220dpi, 고품질은 300dpi로 PNG를 렌더링합니다. PDF preview는 생성 후 캐시됩니다."
              >
                추출모드:
                <select
                  value={perfMode}
                  onChange={(e) => setPerfMode(e.target.value as "fast" | "standard" | "high")}
                  className="border border-gray-300 rounded px-1 py-0.5 text-xs"
                >
                  <option value="fast">빠름</option>
                  <option value="standard">표준</option>
                  <option value="high">고품질</option>
                </select>
              </label>
              <label
                className="flex items-center gap-1.5 text-xs text-gray-600"
                title="긴 문서에서 InDesign 연결이 끊기는 경우 N페이지 단위로 분할 추출합니다."
              >
                분할추출:
                <select
                  value={extractChunkSize}
                  onChange={(e) => setExtractChunkSize(Number(e.target.value))}
                  className="border border-gray-300 rounded px-1 py-0.5 text-xs"
                >
                  <option value={0}>끄기</option>
                  <option value={5}>5p</option>
                  <option value={10}>10p</option>
                  <option value={20}>20p</option>
                  <option value={30}>30p</option>
                  <option value={50}>50p</option>
                  <option value={100}>100p</option>
                </select>
              </label>
              <div className="flex items-center gap-1 text-xs text-gray-600">
                <label className="flex items-center gap-0.5 cursor-pointer">
                  <input type="radio" name="batchOutputFormat" value="hwpx"
                    checked={outputFormat === "hwpx"}
                    onChange={() => setOutputFormat("hwpx")}
                    className="w-3 h-3"
                  />
                  HWPX
                </label>
              </div>
            </div>
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
