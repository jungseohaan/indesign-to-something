import { useState, useRef, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { save, ask, message } from "@tauri-apps/plugin-dialog";
import { useAppStore } from "../stores/useAppStore";

export function ConversionPanel() {
  const {
    idmlPath,
    jarPath,
    isConverting,
    progress,
    result,
    error,
    vectorDpi,
    conversionLogs,
    fontMappings,
    debugStartPage,
    debugEndPage,
    setDebugPageRange,
    startConversion,
    noPreview,
    setVectorDpi,
    setNoPreview,
    clearError,
    openFontMappingModal,
    lastExtractStats,
    extractChunkSize,
    setExtractChunkSize,
  } = useAppStore();

  const debugRangeEnabled = debugStartPage > 0 || debugEndPage > 0;
  const [isExportingAst, setIsExportingAst] = useState(false);

  // SPEC-015: AST JSON 저장 — 기존 export_ast Tauri 커맨드를 호출해 사용자 지정 경로에 파일로 기록.
  async function handleExportAst() {
    if (!idmlPath || !jarPath || isExportingAst) return;
    const defaultName = idmlPath.replace(/^.*[/\\]/, "").replace(/\.[^.]+$/, ".ast.json");
    const outputPath = await save({
      defaultPath: defaultName,
      filters: [{ name: "JSON", extensions: ["json"] }],
    });
    if (!outputPath) return;
    setIsExportingAst(true);
    try {
      const ast = await invoke<unknown>("export_ast", { idmlPath, jarPath });
      await invoke("write_text_file", {
        path: outputPath,
        content: JSON.stringify(ast, null, 2),
      });
      try { await invoke("open_file", { path: outputPath }); } catch {}
    } catch (e: any) {
      alert(`AST 저장 실패: ${e}`);
    } finally {
      setIsExportingAst(false);
    }
  }

  const [showWarnings, setShowWarnings] = useState(false);
  const [showLogs, setShowLogs] = useState(false);
  const [cacheStats, setCacheStats] = useState<{ count: number; mb: number } | null>(null);
  const [isClearingCache, setIsClearingCache] = useState(false);

  useEffect(() => {
    invoke<[number, number]>("extract_cache_stats")
      .then(([count, bytes]) => setCacheStats({ count, mb: Math.round(bytes / 1024 / 1024 * 10) / 10 }))
      .catch(() => {});
  }, []);

  async function handleClearCache() {
    if (isClearingCache) return;
    const label = cacheStats
      ? `캐시 ${cacheStats.count}개 (${cacheStats.mb} MB)를 삭제합니다.`
      : "추출 캐시를 모두 삭제합니다.";
    const yes = await ask(`${label}\n삭제하면 다음 추출 시 InDesign에서 다시 추출합니다.`, {
      title: "캐시 삭제",
      kind: "warning",
    });
    if (!yes) return;
    setIsClearingCache(true);
    try {
      const [count] = await invoke<[number, number]>("clear_extract_cache");
      setCacheStats(null);
      await message(`캐시 ${count}개를 삭제했습니다.`, { title: "완료", kind: "info" });
    } catch (e: any) {
      await message(`캐시 삭제 실패: ${e}`, { title: "오류", kind: "error" });
    } finally {
      setIsClearingCache(false);
    }
  }
  const logEndRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom when new logs arrive
  useEffect(() => {
    if (showLogs && logEndRef.current) {
      logEndRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [conversionLogs, showLogs]);

  return (
    <div className="px-4 py-3 border-t bg-gray-50">
      {/* Error */}
      {error && (
        <div className="mb-2 px-3 py-2 bg-red-50 border border-red-200 rounded flex items-center justify-between">
          <span className="text-sm text-red-700">{error}</span>
          <button
            onClick={clearError}
            className="text-sm text-red-500 hover:text-red-700"
          >
            닫기
          </button>
        </div>
      )}

      {/* Extract Stats */}
      {lastExtractStats && (
        <div className="mb-2 px-3 py-2 bg-blue-50 border border-blue-200 rounded">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-blue-600 font-semibold text-sm">추출 통계</span>
            <span className="text-xs text-gray-400">
              {lastExtractStats.page_count}p · {(lastExtractStats.elapsed_ms / 1000).toFixed(1)}s
            </span>
          </div>
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-gray-600">
            <span title="배지 PNG (배지 도형+텍스트 그룹)">
              배지 <span className="font-semibold text-gray-800">{lastExtractStats.badge_count}</span>
            </span>
            <span className="text-gray-300">|</span>
            <span title="통 이미지: 도형 그룹을 하나의 PNG로 렌더">
              통 이미지 <span className="font-semibold text-blue-700">
                {lastExtractStats.deco_group_count + lastExtractStats.complex_frame_count}
              </span>
              <span className="text-gray-400 ml-1">
                (그룹 {lastExtractStats.deco_group_count} · 복합 {lastExtractStats.complex_frame_count})
              </span>
            </span>
            <span className="text-gray-300">|</span>
            <span title="개별 이미지: 도형·이미지 하나씩 PNG">
              개별 <span className="font-semibold text-orange-600">
                {lastExtractStats.shape_count + lastExtractStats.image_frame_count}
              </span>
              <span className="text-gray-400 ml-1">
                (도형 {lastExtractStats.shape_count} · 이미지 {lastExtractStats.image_frame_count})
              </span>
            </span>
            <span className="text-gray-300">|</span>
            <span title="인라인 객체 (앵커된 이미지/도형)">
              인라인 <span className="font-semibold text-gray-800">{lastExtractStats.inline_object_count}</span>
            </span>
            {lastExtractStats.master_graphic_count > 0 && (
              <>
                <span className="text-gray-300">|</span>
                <span title="마스터 페이지 그래픽">
                  마스터 <span className="font-semibold text-gray-800">{lastExtractStats.master_graphic_count}</span>
                </span>
              </>
            )}
          </div>
        </div>
      )}

      {/* Conversion Report */}
      {result && (
        <div className="mb-2 px-3 py-2 bg-green-50 border border-green-200 rounded">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-green-600 font-semibold text-sm">변환 완료</span>
          </div>
          <div className="flex flex-wrap items-center gap-4 text-xs text-gray-600">
            <span>
              <span className="text-gray-400">p.</span>{" "}
              <span className="font-semibold text-gray-800">{result.pages_converted}</span> 페이지
            </span>
            <span className="text-gray-300">|</span>
            <span>
              <span className="text-gray-400 font-bold">T</span>{" "}
              <span className="font-semibold text-gray-800">{result.frames_converted}</span> 텍스트프레임
            </span>
            <span className="text-gray-300">|</span>
            <span>
              <span className="text-gray-400">img</span>{" "}
              <span className="font-semibold text-gray-800">{result.images_converted}</span> 이미지
              {(result.images_psd > 0 || result.images_ai > 0 || result.images_tiff > 0) && (
                <span className="text-gray-500">
                  {" "}(
                  {[
                    result.images_psd > 0 && `PSD ${result.images_psd}`,
                    result.images_ai > 0 && `AI ${result.images_ai}`,
                    result.images_tiff > 0 && `TIFF ${result.images_tiff}`,
                  ].filter(Boolean).join(", ")}
                  )
                </span>
              )}
            </span>
            {result.equations_converted > 0 && (
              <>
                <span className="text-gray-300">|</span>
                <span>
                  <span className="text-gray-400">eq</span>{" "}
                  <span className="font-semibold text-gray-800">{result.equations_converted}</span> 수식
                </span>
              </>
            )}
            {result.styles_converted > 0 && (
              <>
                <span className="text-gray-300">|</span>
                <span>
                  <span className="text-gray-400">sty</span>{" "}
                  <span className="font-semibold text-gray-800">{result.styles_converted}</span> 스타일
                </span>
              </>
            )}
            {result.images_skipped > 0 && (
              <>
                <span className="text-gray-300">|</span>
                <span className="text-amber-600">
                  건너뜀: 이미지 {result.images_skipped}개
                </span>
              </>
            )}
            {result.warnings.length > 0 && (
              <>
                <span className="text-gray-300">|</span>
                <button
                  onClick={() => setShowWarnings(!showWarnings)}
                  className="text-amber-600 hover:text-amber-700 underline"
                >
                  경고 {result.warnings.length}건 {showWarnings ? "▲" : "▼"}
                </button>
              </>
            )}
          </div>
          {showWarnings && result.warnings.length > 0 && (
            <div className="mt-2 max-h-32 overflow-y-auto text-xs text-amber-700 bg-amber-50 rounded p-2 border border-amber-100">
              {result.warnings.map((w, i) => (
                <div key={i} className="py-0.5">• {w}</div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Conversion Logs */}
      {conversionLogs.length > 0 && (
        <div className="mb-2">
          <button
            onClick={() => setShowLogs(!showLogs)}
            className="text-xs text-gray-500 hover:text-gray-700"
          >
            로그 {conversionLogs.length}건 {showLogs ? "▲" : "▼"}
          </button>
          {showLogs && (
            <div className="mt-1 max-h-48 overflow-y-auto text-xs font-mono bg-gray-900 text-gray-200 rounded p-2 border border-gray-700">
              {conversionLogs.map((log, i) => (
                <div key={i} className="py-0.5 whitespace-pre-wrap">{log.message}</div>
              ))}
              <div ref={logEndRef} />
            </div>
          )}
        </div>
      )}

      <div className="flex items-center justify-between">
        {/* Options */}
        <div className="flex items-center gap-4">
          <label className="flex items-center gap-1.5 text-sm">
            DPI:
            <select
              value={vectorDpi}
              onChange={(e) => setVectorDpi(Number(e.target.value) as 96 | 150)}
              className="border border-gray-300 rounded px-2 py-0.5 text-sm"
            >
              <option value={96}>96</option>
              <option value={150}>150</option>
            </select>
          </label>
          <label className="flex items-center gap-1.5 text-sm">
            <input
              type="checkbox"
              checked={noPreview}
              onChange={(e) => setNoPreview(e.target.checked)}
              className="rounded border-gray-300"
            />
            변환 후 열지 않음
          </label>
          <button
            onClick={openFontMappingModal}
            disabled={!idmlPath}
            className="px-3 py-1 text-sm border border-gray-300 rounded hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {Object.keys(fontMappings).length > 0
              ? `폰트 (${Object.keys(fontMappings).length}개 변경)`
              : "폰트 매핑"}
          </button>
          <button
            onClick={handleClearCache}
            disabled={isClearingCache}
            className="px-3 py-1 text-sm border border-gray-300 rounded hover:bg-red-50 hover:border-red-300 hover:text-red-600 disabled:opacity-50 disabled:cursor-not-allowed"
            title="추출 캐시를 삭제하면 다음 추출 시 InDesign에서 다시 추출합니다."
          >
            {isClearingCache
              ? "삭제 중..."
              : cacheStats
              ? `캐시 삭제 (${cacheStats.count}개 · ${cacheStats.mb}MB)`
              : "캐시 삭제"}
          </button>
          {/* 분할 추출: 긴 문서를 N페이지 단위로 나눠 InDesign 세션 분할 */}
          <label
            className="flex items-center gap-1.5 text-xs text-gray-500"
            title="긴 문서에서 InDesign 연결이 끊기는 경우 N페이지 단위로 분할 추출. 0 = 단일 세션."
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
          {/* SPEC-011: 디버그 페이지 범위 추출 (다음 INDD 추출에 적용, 캐시 우회) */}
          <label className="flex items-center gap-1.5 text-xs text-gray-500" title="다음 INDD 추출 시에만 적용. 캐시를 우회한다.">
            <input
              type="checkbox"
              checked={debugRangeEnabled}
              onChange={(e) => {
                if (e.target.checked) setDebugPageRange(1, 1);
                else setDebugPageRange(0, 0);
              }}
              className="rounded border-gray-300"
            />
            디버그 추출
            {debugRangeEnabled && (
              <>
                <input
                  type="number"
                  min={1}
                  value={debugStartPage || ""}
                  onChange={(e) => setDebugPageRange(Number(e.target.value) || 0, debugEndPage)}
                  className="w-12 border border-gray-300 rounded px-1 py-0.5 text-xs"
                />
                <span>~</span>
                <input
                  type="number"
                  min={1}
                  value={debugEndPage || ""}
                  onChange={(e) => setDebugPageRange(debugStartPage, Number(e.target.value) || 0)}
                  className="w-12 border border-gray-300 rounded px-1 py-0.5 text-xs"
                />
              </>
            )}
          </label>
        </div>

        {/* Progress / Convert Button */}
        <div className="flex items-center gap-3">
          {isConverting && progress && (
            <span className="text-sm text-blue-600">
              {progress.message} ({progress.current}/{progress.total})
            </span>
          )}
          {/* SPEC-015: 디버깅용 AST JSON 저장 */}
          <button
            onClick={handleExportAst}
            disabled={!idmlPath || isConverting || isExportingAst}
            className="px-3 py-2 text-sm border border-gray-300 rounded hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
            title="AST 중간 표현을 JSON 파일로 저장 (디버깅용)"
          >
            {isExportingAst ? "AST 저장 중..." : "AST JSON 저장"}
          </button>
          <button
            onClick={startConversion}
            disabled={!idmlPath || isConverting}
            className="px-6 py-2 bg-blue-600 text-white text-sm rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isConverting ? "변환 중..." : "HWPX 변환"}
          </button>
        </div>
      </div>
    </div>
  );
}
