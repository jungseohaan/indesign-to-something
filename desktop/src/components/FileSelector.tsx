import { useAppStore } from "../stores/useAppStore";

export function FileSelector() {
  const {
    idmlPath,
    inddPath,
    sourceType,
    isExtracting,
    extractionPhase,
    extractionMessage,
    structure,
    indesignPath,
    selectInddFile,
  } = useAppStore();

  const displayPath = sourceType === "indd" ? inddPath : idmlPath;
  const filename = displayPath
    ? displayPath.substring(displayPath.lastIndexOf("/") + 1)
    : null;

  const totalPages = structure
    ? structure.spreads.reduce((sum, s) => sum + s.pages.length, 0)
    : 0;

  // InDesign 앱 이름 추출 (경로에서)
  const indesignName = indesignPath
    ? indesignPath.split("/").pop()?.replace(".app", "") ?? "InDesign"
    : null;

  return (
    <div className="border-b">
      <div className="flex items-center justify-between px-4 py-2.5">
        <div className="flex items-center gap-3">
          {filename && (
            <span className="text-sm text-gray-500 truncate max-w-[400px]">
              {filename}
              {sourceType === "indd" && (
                <span className="ml-1.5 px-1.5 py-0.5 text-[10px] font-medium bg-purple-100 text-purple-600 rounded">
                  InDesign
                </span>
              )}
            </span>
          )}
          {isExtracting && (
            <span className="text-xs text-purple-500 animate-pulse">
              {extractionMessage || "준비 중..."}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          {indesignName ? (
            <span className="text-xs text-gray-400">{indesignName}</span>
          ) : (
            <span className="text-xs text-red-400">InDesign 미설치</span>
          )}
          <button
            onClick={selectInddFile}
            disabled={isExtracting || !indesignPath}
            className="px-4 py-1.5 bg-purple-500 text-white text-sm rounded hover:bg-purple-600 disabled:opacity-50"
          >
            {isExtracting ? "추출 중..." : "INDD 열기"}
          </button>
        </div>
      </div>

      {structure && (
        <div className="flex items-center gap-5 px-4 py-2 bg-gray-50 border-t text-xs text-gray-600">
          <span className="flex items-center gap-1">
            <span className="text-gray-400">p.</span>
            <span className="font-semibold text-gray-800">{totalPages}</span> 페이지
          </span>
          <span className="text-gray-300">|</span>
          <span className="flex items-center gap-1">
            <span className="text-gray-400 font-bold">T</span>
            <span className="font-semibold text-gray-800">{structure.total_text_frames}</span> 텍스트프레임
          </span>
          <span className="text-gray-300">|</span>
          <span className="flex items-center gap-1">
            <span className="text-gray-400">img</span>
            <span className="font-semibold text-gray-800">{structure.total_image_frames}</span> 이미지
          </span>
          <span className="text-gray-300">|</span>
          <span className="flex items-center gap-1">
            <span className="text-gray-400">vec</span>
            <span className="font-semibold text-gray-800">{structure.total_vector_shapes}</span> 벡터
          </span>
          <span className="text-gray-300">|</span>
          <span className="flex items-center gap-1">
            <span className="text-gray-400">tbl</span>
            <span className="font-semibold text-gray-800">{structure.total_tables}</span> 테이블
          </span>
        </div>
      )}
    </div>
  );
}
