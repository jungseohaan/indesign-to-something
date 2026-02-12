import { useEffect } from "react";
import { useExtractStore } from "../stores/useExtractStore";
import type { ExtractedItem, PageInfo, SpreadInfo } from "../types";

export function ExtractPage() {
  const {
    idmlPath,
    idmlStructure,
    isAnalyzing,
    selectedSpreads,
    selectedPage,
    pagePreview,
    isGeneratingPreview,
    extractionResult,
    isExtracting,
    selectedItemIndex,
    error,
    initJarPath,
    openFile,
    toggleSpread,
    selectAllSpreads,
    deselectAllSpreads,
    selectPage,
    extract,
    exportJson,
    selectItem,
    clearError,
  } = useExtractStore();

  useEffect(() => {
    initJarPath();
  }, [initJarPath]);

  const filename = idmlPath
    ? idmlPath.substring(idmlPath.lastIndexOf("/") + 1)
    : null;

  const spreads = idmlStructure?.spreads ?? [];
  const masterSpreads = idmlStructure?.master_spreads ?? [];
  const items = extractionResult?.items ?? [];
  const selectedItem =
    selectedItemIndex !== null ? items[selectedItemIndex] : null;

  const canExtract =
    selectedSpreads.length > 0 && !isExtracting && !isAnalyzing;

  // Find master spread info for selected page
  const selectedMasterInfo = selectedPage?.page.master_spread
    ? masterSpreads.find((m) => m.id === selectedPage.page.master_spread)
    : null;

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
          disabled={isAnalyzing}
          className="px-4 py-1.5 bg-blue-500 text-white text-sm rounded hover:bg-blue-600 disabled:opacity-50"
        >
          {isAnalyzing ? "분석 중..." : "IDML 열기"}
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

      {/* Main Content - 3 Panels */}
      <div className="flex-1 flex min-h-0">
        {/* ── Left Panel: Spread & Page Selection ── */}
        <div className="w-64 border-r overflow-y-auto flex-shrink-0">
          <div className="p-3">
            <div className="flex items-center justify-between mb-2">
              <h2 className="text-sm font-semibold text-gray-600">
                Pages
                {spreads.length > 0 && (
                  <span className="ml-1 font-normal text-gray-400">
                    ({selectedSpreads.length}/{spreads.length})
                  </span>
                )}
              </h2>
              {spreads.length > 0 && (
                <div className="flex gap-2">
                  <button
                    onClick={selectAllSpreads}
                    className="text-[10px] text-blue-500 hover:text-blue-700"
                  >
                    전체
                  </button>
                  <button
                    onClick={deselectAllSpreads}
                    className="text-[10px] text-gray-500 hover:text-gray-700"
                  >
                    해제
                  </button>
                </div>
              )}
            </div>

            {!idmlPath && (
              <p className="text-sm text-gray-400 mt-4">
                IDML 파일을 열어주세요.
              </p>
            )}

            {isAnalyzing && (
              <p className="text-sm text-gray-400 mt-4">분석 중...</p>
            )}

            <div className="space-y-0.5">
              {spreads.map((spread) => {
                const spreadFile = `Spread_${spread.id}.xml`;
                const isSpreadSelected = selectedSpreads.includes(spreadFile);

                return (
                  <div key={spread.id}>
                    {/* Spread header with checkbox */}
                    <label
                      className={`flex items-center gap-2 px-2 py-1.5 rounded cursor-pointer transition-colors ${
                        isSpreadSelected
                          ? "bg-blue-50"
                          : "hover:bg-gray-50"
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={isSpreadSelected}
                        onChange={() => toggleSpread(spreadFile)}
                        className="rounded border-gray-300 text-blue-500"
                      />
                      <span className="text-xs font-medium text-gray-500">
                        Spread
                        <span className="ml-1 text-gray-400">
                          {spread.page_count}p
                        </span>
                      </span>
                    </label>

                    {/* Pages within spread */}
                    <div className="ml-5 space-y-0.5">
                      {spread.pages.map((page) => {
                        const isPageSelected =
                          selectedPage?.page.id === page.id;
                        const masterName = page.master_spread
                          ? masterSpreads.find(
                              (m) => m.id === page.master_spread
                            )?.name
                          : null;

                        return (
                          <button
                            key={page.id}
                            onClick={() => selectPage(spread.id, page)}
                            className={`w-full text-left px-2 py-1.5 rounded transition-colors ${
                              isPageSelected
                                ? "bg-purple-100 text-purple-900 ring-1 ring-purple-300"
                                : "hover:bg-gray-100 text-gray-700"
                            }`}
                          >
                            <div className="text-sm font-medium">
                              {page.name}
                            </div>
                            <div className="text-[10px] text-gray-400 mt-0.5">
                              {Math.round(page.width)} x{" "}
                              {Math.round(page.height)} pt
                              {masterName && (
                                <span className="ml-1 text-purple-400">
                                  [{masterName}]
                                </span>
                              )}
                            </div>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        {/* ── Center Panel: Page Preview & Master Info ── */}
        <div className="w-80 border-r overflow-auto flex-shrink-0 bg-gray-50">
          {selectedPage ? (
            <div className="p-4">
              {/* Page Info */}
              <div className="mb-4">
                <h3 className="text-sm font-semibold text-gray-600 mb-2">
                  {selectedPage.page.name}
                </h3>
                <div className="space-y-1 text-xs text-gray-500">
                  <div>
                    크기: {Math.round(selectedPage.page.width)} x{" "}
                    {Math.round(selectedPage.page.height)} pt
                  </div>
                  <div>
                    여백: T:{Math.round(selectedPage.page.margin_top)}{" "}
                    B:{Math.round(selectedPage.page.margin_bottom)}{" "}
                    L:{Math.round(selectedPage.page.margin_left)}{" "}
                    R:{Math.round(selectedPage.page.margin_right)} pt
                  </div>
                  {selectedPage.page.column_count > 0 && (
                    <div>단: {selectedPage.page.column_count}단</div>
                  )}
                </div>
              </div>

              {/* Master Spread Info */}
              {selectedMasterInfo && (
                <div className="mb-4 p-3 bg-white rounded-lg border border-purple-200">
                  <div className="text-xs font-semibold text-purple-600 mb-1.5">
                    마스터: {selectedMasterInfo.name}
                  </div>
                  <div className="space-y-0.5 text-[11px] text-gray-500">
                    <div>
                      {selectedMasterInfo.page_count}p
                      <span className="mx-1">·</span>
                      {selectedMasterInfo.text_frame_count +
                        selectedMasterInfo.image_frame_count +
                        selectedMasterInfo.vector_count +
                        selectedMasterInfo.group_count}{" "}
                      obj
                    </div>
                    <div className="flex gap-2">
                      {selectedMasterInfo.text_frame_count > 0 && (
                        <span className="text-blue-500">
                          T:{selectedMasterInfo.text_frame_count}
                        </span>
                      )}
                      {selectedMasterInfo.image_frame_count > 0 && (
                        <span className="text-green-500">
                          I:{selectedMasterInfo.image_frame_count}
                        </span>
                      )}
                      {selectedMasterInfo.vector_count > 0 && (
                        <span className="text-orange-500">
                          V:{selectedMasterInfo.vector_count}
                        </span>
                      )}
                      {selectedMasterInfo.group_count > 0 && (
                        <span className="text-gray-500">
                          G:{selectedMasterInfo.group_count}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
              )}

              {!selectedPage.page.master_spread && (
                <div className="mb-4 p-3 bg-gray-100 rounded-lg text-xs text-gray-400">
                  마스터 페이지 없음
                </div>
              )}

              {/* Preview Image */}
              {isGeneratingPreview && (
                <p className="text-sm text-gray-400 text-center mt-4">
                  프리뷰 생성 중...
                </p>
              )}

              {!isGeneratingPreview && pagePreview && (
                <div>
                  <div className="text-xs text-gray-400 mb-1">
                    마스터 프리뷰
                  </div>
                  <img
                    src={pagePreview.data_url}
                    alt={pagePreview.filename}
                    className="w-full object-contain shadow-lg border rounded"
                  />
                  <div className="text-center mt-1 text-[10px] text-gray-400">
                    {pagePreview.width} x {pagePreview.height}px
                  </div>
                </div>
              )}

              {!isGeneratingPreview &&
                !pagePreview &&
                selectedPage.page.master_spread && (
                  <p className="text-sm text-gray-400 text-center mt-4">
                    프리뷰를 불러올 수 없습니다.
                  </p>
                )}
            </div>
          ) : (
            <div className="flex items-center justify-center h-full">
              <p className="text-sm text-gray-400 text-center px-4">
                {idmlPath && !isAnalyzing && spreads.length > 0
                  ? "왼쪽에서 페이지를 클릭하면\n프리뷰와 마스터 정보가\n표시됩니다."
                  : idmlPath
                    ? ""
                    : "IDML 파일을 열어\n페이지를 탐색하세요."}
              </p>
            </div>
          )}
        </div>

        {/* ── Right Panel: Extraction Results ── */}
        <div className="flex-1 flex flex-col min-h-0">
          {/* Item List */}
          <div
            className={`${selectedItem ? "h-1/2" : "flex-1"} overflow-y-auto border-b`}
          >
            <div className="p-4">
              {items.length === 0 && !isExtracting && (
                <p className="text-sm text-gray-400 text-center mt-8">
                  {idmlPath
                    ? "스프레드를 선택하고 추출 버튼을 클릭하세요."
                    : "IDML 파일을 열어주세요."}
                </p>
              )}

              {isExtracting && (
                <p className="text-sm text-gray-400 text-center mt-8">
                  추출 중...
                </p>
              )}

              {items.length > 0 && (
                <div className="space-y-1">
                  <div className="text-xs text-gray-400 mb-2">
                    {items.length}개 문항
                  </div>
                  {items.map((item, index) => (
                    <button
                      key={index}
                      onClick={() => selectItem(index)}
                      className={`w-full text-left px-3 py-2 rounded-lg transition-colors ${
                        selectedItemIndex === index
                          ? "bg-blue-50 ring-1 ring-blue-300"
                          : "hover:bg-gray-50"
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <span className="text-lg font-bold text-gray-300 w-8 text-center flex-shrink-0">
                          {item.number}
                        </span>
                        <div className="flex-1 min-w-0">
                          <div className="text-sm text-gray-700 truncate">
                            {item.발문1?.substring(0, 80)}
                          </div>
                          <div className="text-xs text-gray-400 mt-0.5">
                            {item.년도출처}
                            {item.정답 !== null && (
                              <span className="ml-2 text-blue-500">
                                정답: {item.정답}
                              </span>
                            )}
                            {item.보기 && (
                              <span className="ml-2 text-orange-400">
                                보기
                              </span>
                            )}
                            {item.이미지.length > 0 && (
                              <span className="ml-2 text-green-400">
                                이미지:{item.이미지.length}
                              </span>
                            )}
                          </div>
                        </div>
                      </div>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Item Detail */}
          {selectedItem && (
            <div className="flex-1 overflow-y-auto p-4 bg-gray-50">
              <ItemDetail item={selectedItem} />
            </div>
          )}
        </div>
      </div>

      {/* Footer */}
      {idmlPath && !isAnalyzing && (
        <div className="flex items-center justify-between px-4 py-3 border-t bg-gray-50 shrink-0">
          <span className="text-sm text-gray-500">
            {items.length > 0
              ? `${items.length}개 문항 추출됨`
              : `${selectedSpreads.length}개 스프레드 선택됨`}
          </span>
          <div className="flex items-center gap-3">
            {items.length > 0 && (
              <button
                onClick={exportJson}
                className="px-4 py-2 bg-green-500 text-white text-sm rounded hover:bg-green-600"
              >
                JSON 내보내기
              </button>
            )}
            <button
              onClick={extract}
              disabled={!canExtract}
              className="px-6 py-2 bg-blue-600 text-white text-sm font-medium rounded hover:bg-blue-700 disabled:opacity-50"
            >
              {isExtracting ? "추출 중..." : "추출하기"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function ItemDetail({ item }: { item: ExtractedItem }) {
  return (
    <div className="space-y-3 text-sm">
      <div className="flex items-center gap-2 mb-2">
        <span className="text-xl font-bold text-gray-300">{item.number}</span>
        {item.정답 !== null && (
          <span className="px-2 py-0.5 bg-blue-100 text-blue-700 text-xs rounded-full font-medium">
            정답: {item.정답}
          </span>
        )}
        {item.그림 && (
          <span className="px-2 py-0.5 bg-orange-100 text-orange-600 text-xs rounded-full">
            그림 포함
          </span>
        )}
      </div>

      <Field label="년도출처" value={item.년도출처} />
      <Field label="발문1" value={item.발문1} pre />

      {item.발문2 && <Field label="발문2" value={item.발문2} pre />}

      {item.보기 && item.보기.length > 0 && (
        <div>
          <div className="font-semibold text-gray-500 text-xs mb-1">보기</div>
          <div className="text-gray-700 pl-2 space-y-0.5">
            {item.보기.map((b, i) => (
              <div key={i}>{b}</div>
            ))}
          </div>
        </div>
      )}

      {item.선지 && item.선지.length > 0 && (
        <div>
          <div className="font-semibold text-gray-500 text-xs mb-1">선지</div>
          <div className="text-gray-700 pl-2 space-y-0.5">
            {item.선지.map((s, i) => (
              <div key={i}>{s}</div>
            ))}
          </div>
        </div>
      )}

      {item.교사용풀이 && (
        <Field label="교사용풀이" value={item.교사용풀이} pre small />
      )}

      {item.표내용 && <Field label="표내용" value={item.표내용} pre small />}

      {item.실험 && <Field label="실험" value={item.실험} pre small />}

      {item.이미지 && item.이미지.length > 0 && (
        <div>
          <div className="font-semibold text-gray-500 text-xs mb-1">
            이미지 ({item.이미지.length})
          </div>
          <div className="text-xs space-y-0.5">
            {item.이미지.map((img, i) => (
              <div
                key={i}
                className={img.exists ? "text-green-600" : "text-red-500"}
              >
                {img.filename}
                {!img.exists && " (missing)"}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function Field({
  label,
  value,
  pre,
  small,
}: {
  label: string;
  value: string | null;
  pre?: boolean;
  small?: boolean;
}) {
  if (!value) return null;
  return (
    <div>
      <div className="font-semibold text-gray-500 text-xs mb-1">{label}</div>
      <div
        className={`text-gray-700 ${pre ? "whitespace-pre-wrap" : ""} ${small ? "text-xs text-gray-600" : ""}`}
      >
        {value}
      </div>
    </div>
  );
}
