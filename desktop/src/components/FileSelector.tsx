import { useAppStore } from "../stores/useAppStore";

export function FileSelector() {
  const { idmlPath, isAnalyzing, structure, selectFile } = useAppStore();

  const filename = idmlPath
    ? idmlPath.substring(idmlPath.lastIndexOf("/") + 1)
    : null;

  const totalPages = structure
    ? structure.spreads.reduce((sum, s) => sum + s.pages.length, 0)
    : 0;

  return (
    <div className="border-b">
      <div className="flex items-center justify-between px-4 py-2.5">
        <div className="flex items-center gap-3">
          {filename && (
            <span className="text-sm text-gray-500 truncate max-w-[400px]">
              {filename}
            </span>
          )}
        </div>
        <button
          onClick={selectFile}
          disabled={isAnalyzing}
          className="px-4 py-1.5 bg-blue-500 text-white text-sm rounded hover:bg-blue-600 disabled:opacity-50"
        >
          {isAnalyzing ? "분석 중..." : "IDML 열기"}
        </button>
      </div>

      {structure && (
        <div className="flex items-center gap-5 px-4 py-2 bg-gray-50 border-t text-xs text-gray-600">
          <span className="flex items-center gap-1">
            <span className="text-gray-400">📄</span>
            <span className="font-semibold text-gray-800">{totalPages}</span> 페이지
          </span>
          <span className="text-gray-300">|</span>
          <span className="flex items-center gap-1">
            <span className="text-gray-400 font-bold">T</span>
            <span className="font-semibold text-gray-800">{structure.total_text_frames}</span> 텍스트프레임
          </span>
          <span className="text-gray-300">|</span>
          <span className="flex items-center gap-1">
            <span className="text-gray-400">🖼</span>
            <span className="font-semibold text-gray-800">{structure.total_image_frames}</span> 이미지
          </span>
          <span className="text-gray-300">|</span>
          <span className="flex items-center gap-1">
            <span className="text-gray-400">◇</span>
            <span className="font-semibold text-gray-800">{structure.total_vector_shapes}</span> 벡터
          </span>
          <span className="text-gray-300">|</span>
          <span className="flex items-center gap-1">
            <span className="text-gray-400">📋</span>
            <span className="font-semibold text-gray-800">{structure.total_tables}</span> 테이블
          </span>
        </div>
      )}
    </div>
  );
}
