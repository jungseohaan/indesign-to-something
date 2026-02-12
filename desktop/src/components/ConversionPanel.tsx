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
    startConversion,
    setSpreadBased,
    setVectorDpi,
    clearError,
  } = useAppStore();

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
        </div>

        {/* Result / Progress / Convert Button */}
        <div className="flex items-center gap-3">
          {result && (
            <span className="text-sm text-green-600">
              변환 완료: {result.pages_converted}페이지,{" "}
              {result.frames_converted}프레임
              {result.warnings.length > 0 &&
                ` (경고 ${result.warnings.length}건)`}
            </span>
          )}
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
