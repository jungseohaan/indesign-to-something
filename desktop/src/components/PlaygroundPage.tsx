import { useEffect } from "react";
import { usePlaygroundStore } from "../stores/usePlaygroundStore";

interface PlaygroundPageProps {
  onBack: () => void;
}

export function PlaygroundPage({ onBack }: PlaygroundPageProps) {
  const {
    idmlPath,
    masterSpreads,
    isLoading,
    selectedMaster,
    masterPreview,
    isGeneratingPreview,
    isCreating,
    createResult,
    error,
    initJarPath,
    openFile,
    selectMaster,
    createIdml,
    clearError,
  } = usePlaygroundStore();

  useEffect(() => {
    initJarPath();
  }, [initJarPath]);

  const filename = idmlPath
    ? idmlPath.substring(idmlPath.lastIndexOf("/") + 1)
    : null;

  return (
    <div className="h-screen flex flex-col bg-white">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b bg-gray-50">
        <div className="flex items-center gap-3">
          <button
            onClick={onBack}
            className="text-sm text-gray-600 hover:text-gray-900 flex items-center gap-1"
          >
            <span>&larr;</span> 돌아가기
          </button>
          <h1 className="text-lg font-semibold text-gray-800">Playground</h1>
        </div>
        <div className="flex items-center gap-3">
          {filename && (
            <span className="text-sm text-gray-500 truncate max-w-[300px]">
              {filename}
            </span>
          )}
          <button
            onClick={openFile}
            disabled={isLoading}
            className="px-4 py-1.5 bg-blue-500 text-white text-sm rounded hover:bg-blue-600 disabled:opacity-50"
          >
            {isLoading ? "분석 중..." : "IDML 열기"}
          </button>
        </div>
      </div>

      {/* Error Banner */}
      {error && (
        <div className="px-4 py-2 bg-red-50 border-b border-red-200 flex items-center justify-between">
          <span className="text-sm text-red-700">{error}</span>
          <button
            onClick={clearError}
            className="text-sm text-red-500 hover:text-red-700"
          >
            닫기
          </button>
        </div>
      )}

      {/* Main Content */}
      <div className="flex-1 flex min-h-0">
        {/* Left Panel - Master Spread List */}
        <div className="w-1/3 border-r overflow-y-auto">
          <div className="p-3">
            <h2 className="text-sm font-semibold text-gray-600 mb-2">
              Master Spreads
              {masterSpreads.length > 0 && (
                <span className="ml-1 text-gray-400">
                  ({masterSpreads.length})
                </span>
              )}
            </h2>

            {!idmlPath && (
              <p className="text-sm text-gray-400 mt-4">
                IDML 파일을 열어주세요.
              </p>
            )}

            {isLoading && (
              <p className="text-sm text-gray-400 mt-4">분석 중...</p>
            )}

            {masterSpreads.length === 0 && idmlPath && !isLoading && (
              <p className="text-sm text-gray-400 mt-4">
                마스터 스프레드가 없습니다.
              </p>
            )}

            <div className="space-y-1">
              {masterSpreads.map((master) => (
                <button
                  key={master.id}
                  onClick={() => selectMaster(master)}
                  className={`w-full text-left px-3 py-2 rounded text-sm transition-colors ${
                    selectedMaster?.id === master.id
                      ? "bg-blue-100 text-blue-800 border border-blue-300"
                      : "hover:bg-gray-100 text-gray-700 border border-transparent"
                  }`}
                >
                  <div className="font-medium">{master.name}</div>
                  <div className="text-xs text-gray-500 mt-0.5">
                    {master.page_count}p
                    {master.text_frame_count > 0 &&
                      ` / TF:${master.text_frame_count}`}
                    {master.image_frame_count > 0 &&
                      ` / Img:${master.image_frame_count}`}
                    {master.vector_count > 0 &&
                      ` / Vec:${master.vector_count}`}
                    {master.applied_pages.length > 0 && (
                      <span className="ml-1 text-gray-400">
                        (적용: {master.applied_pages.length}p)
                      </span>
                    )}
                  </div>
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* Right Panel - Master Preview */}
        <div className="flex-1 overflow-auto flex items-center justify-center bg-gray-50">
          {isGeneratingPreview && (
            <p className="text-sm text-gray-400">프리뷰 생성 중...</p>
          )}

          {!isGeneratingPreview && masterPreview && (
            <div className="p-4">
              <img
                src={masterPreview.data_url}
                alt={masterPreview.filename}
                className="max-w-full max-h-full object-contain shadow-lg border"
                style={{ maxHeight: "calc(100vh - 200px)" }}
              />
              <div className="text-center mt-2 text-xs text-gray-500">
                {masterPreview.width} x {masterPreview.height}px
              </div>
            </div>
          )}

          {!isGeneratingPreview && !masterPreview && selectedMaster && (
            <p className="text-sm text-gray-400">프리뷰를 불러올 수 없습니다.</p>
          )}

          {!selectedMaster && idmlPath && !isLoading && masterSpreads.length > 0 && (
            <p className="text-sm text-gray-400">
              마스터 스프레드를 선택하면 프리뷰가 표시됩니다.
            </p>
          )}

          {!idmlPath && (
            <p className="text-sm text-gray-400">
              IDML 파일을 열어 마스터 페이지를 탐색하세요.
            </p>
          )}
        </div>
      </div>

      {/* Footer */}
      <div className="px-4 py-3 border-t bg-gray-50">
        <div className="flex items-center justify-between">
          <div className="text-sm">
            {createResult && (
              <span
                className={
                  createResult.validation?.valid
                    ? "text-green-600"
                    : "text-orange-600"
                }
              >
                {createResult.success
                  ? `생성 완료: 마스터 ${createResult.master_count}개, 페이지 크기 ${Math.round(createResult.page_size.width)}x${Math.round(createResult.page_size.height)}pt`
                  : "생성 실패"}
                {createResult.validation &&
                  (createResult.validation.valid
                    ? " - 검증 통과"
                    : ` - 검증 오류: ${createResult.validation.errors.join(", ")}`)}
              </span>
            )}
          </div>
          <button
            onClick={createIdml}
            disabled={!idmlPath || isCreating || masterSpreads.length === 0}
            className="px-4 py-2 bg-green-600 text-white text-sm rounded hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isCreating ? "생성 중..." : "IDML 파일 만들기"}
          </button>
        </div>
      </div>
    </div>
  );
}
