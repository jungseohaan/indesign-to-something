import { useState, useRef, useEffect } from "react";
import { invoke } from "@tauri-apps/api/core";
import { save } from "@tauri-apps/plugin-dialog";
import { useAppStore } from "../stores/useAppStore";

export function ConversionPanel() {
  const {
    idmlPath,
    jarPath,
    isConverting,
    progress,
    result,
    error,
    spreadBased,
    vectorDpi,
    layoutMode,
    perfMode,
    conversionLogs,
    fontMappings,
    debugStartPage,
    debugEndPage,
    setDebugPageRange,
    startConversion,
    setSpreadBased,
    setVectorDpi,
    setLayoutMode,
    setPerfMode,
    clearError,
    openFontMappingModal,
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
            <input
              type="checkbox"
              checked={spreadBased}
              onChange={(e) => setSpreadBased(e.target.checked)}
              className="rounded border-gray-300"
            />
            스프레드 모드
          </label>
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
            레이아웃:
            <select
              value={layoutMode}
              onChange={(e) => setLayoutMode(e.target.value as "preserve" | "editable")}
              className="border border-gray-300 rounded px-2 py-0.5 text-sm"
            >
              <option value="preserve">레이아웃 유지</option>
              <option value="editable">편집 우선 (1단)</option>
            </select>
          </label>
          <label
            className="flex items-center gap-1.5 text-sm"
            title="추출 속도: fast=PNG 150dpi+PDF 스킵, standard=220dpi+PDF, high=300dpi+PDF"
          >
            추출:
            <select
              value={perfMode}
              onChange={(e) => setPerfMode(e.target.value as "fast" | "standard" | "high")}
              className="border border-gray-300 rounded px-2 py-0.5 text-sm"
            >
              <option value="fast">빠름</option>
              <option value="standard">표준</option>
              <option value="high">고품질</option>
            </select>
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
