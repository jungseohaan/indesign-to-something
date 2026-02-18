import { useState } from "react";
import { useAppStore } from "../stores/useAppStore";

export function ConversionPanel() {
  const {
    idmlPath,
    isConverting,
    progress,
    result,
    error,
    spreadBased,
    vectorDpi,
    layoutMode,
    startConversion,
    setSpreadBased,
    setVectorDpi,
    setLayoutMode,
    clearError,
  } = useAppStore();

  const [showWarnings, setShowWarnings] = useState(false);

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
              <span className="text-gray-400">📄</span>{" "}
              <span className="font-semibold text-gray-800">{result.pages_converted}</span> 페이지
            </span>
            <span className="text-gray-300">|</span>
            <span>
              <span className="text-gray-400 font-bold">T</span>{" "}
              <span className="font-semibold text-gray-800">{result.frames_converted}</span> 텍스트프레임
            </span>
            <span className="text-gray-300">|</span>
            <span>
              <span className="text-gray-400">🖼</span>{" "}
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
                  <span className="text-gray-400">📐</span>{" "}
                  <span className="font-semibold text-gray-800">{result.equations_converted}</span> 수식
                </span>
              </>
            )}
            {result.styles_converted > 0 && (
              <>
                <span className="text-gray-300">|</span>
                <span>
                  <span className="text-gray-400">🎨</span>{" "}
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
        </div>

        {/* Progress / Convert Button */}
        <div className="flex items-center gap-3">
          {isConverting && progress && (
            <span className="text-sm text-blue-600">
              {progress.message} ({progress.current}/{progress.total})
            </span>
          )}
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
