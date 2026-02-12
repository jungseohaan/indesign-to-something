import { useAppStore } from "../stores/useAppStore";

export function FileSelector() {
  const { idmlPath, isAnalyzing, selectFile, selectHwpxFile } = useAppStore();

  const filename = idmlPath
    ? idmlPath.substring(idmlPath.lastIndexOf("/") + 1)
    : null;

  return (
    <div className="flex items-center justify-between px-4 py-2.5 border-b">
      <div className="flex items-center gap-3">
        {filename && (
          <span className="text-sm text-gray-500 truncate max-w-[400px]">
            {filename}
          </span>
        )}
      </div>
      <div className="flex items-center gap-2">
        <button
          onClick={selectFile}
          disabled={isAnalyzing}
          className="px-4 py-1.5 bg-blue-500 text-white text-sm rounded hover:bg-blue-600 disabled:opacity-50"
        >
          {isAnalyzing ? "분석 중..." : "IDML 열기"}
        </button>
        <button
          onClick={selectHwpxFile}
          disabled={isAnalyzing}
          className="px-4 py-1.5 bg-green-500 text-white text-sm rounded hover:bg-green-600 disabled:opacity-50"
        >
          HWPX 열기
        </button>
      </div>
    </div>
  );
}
