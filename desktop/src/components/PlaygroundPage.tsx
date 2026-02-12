import { useEffect } from "react";
import { usePlaygroundStore } from "../stores/usePlaygroundStore";

export function PlaygroundPage() {
  const {
    idmlPath,
    masterSpreads,
    isLoading,
    selectedMaster,
    masterPreview,
    isGeneratingPreview,
    isExporting,
    error,
    pages,
    initJarPath,
    openFile,
    selectMaster,
    exportIdml,
    clearError,
    addPage,
    removePage,
    setPageMaster,
    movePageUp,
    movePageDown,
  } = usePlaygroundStore();

  useEffect(() => {
    initJarPath();
  }, [initJarPath]);

  const filename = idmlPath
    ? idmlPath.substring(idmlPath.lastIndexOf("/") + 1)
    : null;

  const canExport = !!idmlPath && !isLoading && pages.length > 0 &&
    pages.some((p) => p.masterSpreadId !== null);

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-white">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b">
        <div className="flex items-center gap-3">
          {filename && (
            <span className="text-sm text-gray-500 truncate max-w-[400px]">
              {filename}
            </span>
          )}
        </div>
        <button
          onClick={openFile}
          disabled={isLoading}
          className="px-4 py-1.5 bg-blue-500 text-white text-sm rounded hover:bg-blue-600 disabled:opacity-50"
        >
          {isLoading ? "분석 중..." : "IDML 열기"}
        </button>
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

      {/* Main Content - 3 Columns */}
      <div className="flex-1 flex min-h-0">

        {/* ── Left Panel: Master Pages ── */}
        <div className="w-60 border-r overflow-y-auto flex-shrink-0">
          <div className="p-3">
            <h2 className="text-sm font-semibold text-gray-600 mb-3">
              Master Pages
              {masterSpreads.length > 0 && (
                <span className="ml-1 font-normal text-gray-400">
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

            <div className="space-y-1">
              {masterSpreads.map((master) => {
                const isSelected = selectedMaster?.id === master.id;
                const elements =
                  master.text_frame_count +
                  master.image_frame_count +
                  master.vector_count +
                  master.group_count;

                return (
                  <button
                    key={master.id}
                    onClick={() => selectMaster(master)}
                    className={`w-full text-left px-3 py-2 rounded-lg transition-colors ${
                      isSelected
                        ? "bg-purple-100 text-purple-900 ring-1 ring-purple-300"
                        : "hover:bg-gray-100 text-gray-700"
                    }`}
                  >
                    <div className="font-medium text-sm">{master.name}</div>
                    <div className="text-xs text-gray-400 mt-0.5">
                      {Math.round(master.page_width)} x{" "}
                      {Math.round(master.page_height)} pt
                      <span className="mx-1">·</span>
                      {master.page_count}p
                      <span className="mx-1">·</span>
                      {elements} obj
                    </div>
                    {(master.text_frame_count > 0 ||
                      master.image_frame_count > 0 ||
                      master.vector_count > 0) && (
                      <div className="flex gap-2 mt-1 text-[10px]">
                        {master.text_frame_count > 0 && (
                          <span className="text-blue-500">
                            T:{master.text_frame_count}
                          </span>
                        )}
                        {master.image_frame_count > 0 && (
                          <span className="text-green-500">
                            I:{master.image_frame_count}
                          </span>
                        )}
                        {master.vector_count > 0 && (
                          <span className="text-orange-500">
                            V:{master.vector_count}
                          </span>
                        )}
                        {master.group_count > 0 && (
                          <span className="text-gray-500">
                            G:{master.group_count}
                          </span>
                        )}
                      </div>
                    )}
                  </button>
                );
              })}
            </div>
          </div>
        </div>

        {/* ── Center Panel: Page Composition ── */}
        <div className="flex-1 overflow-y-auto border-r">
          <div className="p-4">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-sm font-semibold text-gray-600">
                Pages
                {pages.length > 0 && (
                  <span className="ml-1 font-normal text-gray-400">
                    ({pages.length})
                  </span>
                )}
              </h2>
              {idmlPath && !isLoading && (
                <button
                  onClick={addPage}
                  className="text-xs px-3 py-1.5 bg-blue-500 text-white rounded hover:bg-blue-600"
                >
                  + 페이지 추가
                </button>
              )}
            </div>

            {!idmlPath && (
              <p className="text-sm text-gray-400 mt-8 text-center">
                IDML을 열면 페이지를 구성할 수 있습니다.
              </p>
            )}

            {idmlPath && !isLoading && pages.length === 0 && (
              <p className="text-sm text-gray-400 mt-8 text-center">
                페이지가 없습니다. 위의 버튼으로 추가하세요.
              </p>
            )}

            {idmlPath && !isLoading && pages.length > 0 && (
              <div className="space-y-2">
                {pages.map((page, index) => {
                  const assignedMaster = masterSpreads.find(
                    (m) => m.id === page.masterSpreadId
                  );

                  return (
                    <div
                      key={page.id}
                      className="flex items-center gap-3 px-4 py-3 rounded-lg border border-gray-200 bg-white hover:border-gray-300 transition-colors"
                    >
                      {/* Page number */}
                      <span className="text-lg font-bold text-gray-300 w-8 text-center flex-shrink-0">
                        {index + 1}
                      </span>

                      {/* Master select */}
                      <div className="flex-1 min-w-0">
                        <select
                          value={page.masterSpreadId || "none"}
                          onChange={(e) =>
                            setPageMaster(
                              page.id,
                              e.target.value === "none"
                                ? null
                                : e.target.value
                            )
                          }
                          className="w-full text-sm border border-gray-200 rounded-md px-3 py-1.5 bg-white focus:outline-none focus:ring-2 focus:ring-purple-300 focus:border-purple-400"
                        >
                          <option value="none">-- 마스터 선택 --</option>
                          {masterSpreads.map((m) => (
                            <option key={m.id} value={m.id}>
                              {m.name}
                            </option>
                          ))}
                        </select>
                        {assignedMaster && (
                          <div className="text-[11px] text-gray-400 mt-1 ml-1">
                            {Math.round(assignedMaster.page_width)} x{" "}
                            {Math.round(assignedMaster.page_height)} pt
                          </div>
                        )}
                      </div>

                      {/* Actions */}
                      <div className="flex items-center gap-1 flex-shrink-0">
                        <button
                          onClick={() => movePageUp(page.id)}
                          disabled={index === 0}
                          className="p-1 text-gray-400 hover:text-gray-600 disabled:opacity-20"
                          title="위로"
                        >
                          <svg className="w-4 h-4" viewBox="0 0 16 16" fill="currentColor">
                            <path d="M8 4l4 4H4z" />
                          </svg>
                        </button>
                        <button
                          onClick={() => movePageDown(page.id)}
                          disabled={index === pages.length - 1}
                          className="p-1 text-gray-400 hover:text-gray-600 disabled:opacity-20"
                          title="아래로"
                        >
                          <svg className="w-4 h-4" viewBox="0 0 16 16" fill="currentColor">
                            <path d="M8 12l4-4H4z" />
                          </svg>
                        </button>
                        <button
                          onClick={() => removePage(page.id)}
                          className="p-1 text-gray-400 hover:text-red-500"
                          title="삭제"
                        >
                          <svg className="w-4 h-4" viewBox="0 0 16 16" fill="currentColor">
                            <path d="M4.646 4.646a.5.5 0 0 1 .708 0L8 7.293l2.646-2.647a.5.5 0 0 1 .708.708L8.707 8l2.647 2.646a.5.5 0 0 1-.708.708L8 8.707l-2.646 2.647a.5.5 0 0 1-.708-.708L7.293 8 4.646 5.354a.5.5 0 0 1 0-.708z" />
                          </svg>
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>

        {/* ── Right Panel: Master Preview ── */}
        <div className="w-96 overflow-auto flex items-center justify-center bg-gray-50 flex-shrink-0">
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
            <p className="text-sm text-gray-400">
              프리뷰를 불러올 수 없습니다.
            </p>
          )}

          {!selectedMaster &&
            idmlPath &&
            !isLoading &&
            masterSpreads.length > 0 && (
              <p className="text-sm text-gray-400 text-center px-4">
                왼쪽에서 마스터를 클릭하면
                <br />
                프리뷰가 표시됩니다.
              </p>
            )}

          {!idmlPath && (
            <p className="text-sm text-gray-400 text-center px-4">
              IDML 파일을 열어
              <br />
              마스터 페이지를 탐색하세요.
            </p>
          )}
        </div>
      </div>

      {/* Footer - Export */}
      {idmlPath && !isLoading && (
        <div className="flex items-center justify-between px-4 py-3 border-t bg-gray-50 shrink-0">
          <span className="text-sm text-gray-500">
            {pages.length}페이지
          </span>
          <button
            onClick={exportIdml}
            disabled={!canExport || isExporting}
            className="px-6 py-2 bg-blue-500 text-white text-sm font-medium rounded hover:bg-blue-600 disabled:opacity-50"
          >
            {isExporting ? "내보내는 중..." : "IDML 내보내기"}
          </button>
        </div>
      )}
    </div>
  );
}
