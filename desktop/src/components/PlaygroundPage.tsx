import { useEffect } from "react";
import { usePlaygroundStore } from "../stores/usePlaygroundStore";
import type { GroupChild } from "../types";

interface PlaygroundPageProps {
  onBack: () => void;
}

const FIELD_LABELS: Record<string, string> = {
  year: "년도",
  number: "번호",
  question: "문제",
  choices: "문항",
};

function fieldLabel(name: string): string {
  return FIELD_LABELS[name] || name;
}

function childIcon(child: GroupChild): string {
  switch (child.type) {
    case "rectangle":
      return "R";
    case "textFrame":
      return "T";
    case "graphicLine":
      return "L";
    default:
      return "?";
  }
}

function childColor(child: GroupChild): string {
  switch (child.type) {
    case "rectangle":
      return "bg-gray-200 text-gray-600";
    case "textFrame":
      return "bg-blue-100 text-blue-700";
    case "graphicLine":
      return "bg-orange-100 text-orange-600";
    default:
      return "bg-gray-100 text-gray-500";
  }
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
    pages,
    schema,
    items,
    initJarPath,
    openFile,
    selectMaster,
    mergeIdml,
    clearError,
    addPage,
    removePage,
    setPageMaster,
    movePageUp,
    movePageDown,
    toggleTextFrame,
    tfMode,
    setTfMode,
    setItemField,
    addItem,
    removeItem,
    loadDataFile,
  } = usePlaygroundStore();

  useEffect(() => {
    initJarPath();
  }, [initJarPath]);

  const filename = idmlPath
    ? idmlPath.substring(idmlPath.lastIndexOf("/") + 1)
    : null;

  const tfPageCount = pages.filter(
    (p) => p.textFrame && p.masterSpreadId
  ).length;

  const nonEmptyItemCount = items.filter((item) =>
    Object.values(item).some((v) => v.trim() !== "")
  ).length;

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

      {/* Main Content - 3 Columns */}
      <div className="flex-1 flex min-h-0">
        {/* Left Panel - Template Schema */}
        <div className="w-56 border-r overflow-y-auto flex-shrink-0">
          <div className="p-3">
            <h2 className="text-sm font-semibold text-gray-600 mb-2">
              Template
            </h2>

            {!idmlPath && (
              <p className="text-sm text-gray-400 mt-4">
                IDML 파일을 열어주세요.
              </p>
            )}

            {isLoading && (
              <p className="text-sm text-gray-400 mt-4">분석 중...</p>
            )}

            {/* Master Spreads */}
            {masterSpreads.length > 0 && (
              <div className="mb-3">
                <h3 className="text-xs font-medium text-gray-500 mb-1">
                  Masters ({masterSpreads.length})
                </h3>
                <div className="space-y-0.5">
                  {masterSpreads.map((master) => (
                    <button
                      key={master.id}
                      onClick={() => selectMaster(master)}
                      className={`w-full text-left px-2 py-1 rounded text-xs transition-colors ${
                        selectedMaster?.id === master.id
                          ? "bg-blue-100 text-blue-800"
                          : "hover:bg-gray-100 text-gray-700"
                      }`}
                    >
                      <span className="font-medium">{master.name}</span>
                      <span className="text-gray-400 ml-1">
                        {Math.round(master.page_width)}x
                        {Math.round(master.page_height)}
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            )}

            {/* Group Template Schema */}
            {schema?.groupTemplate && (
              <div className="mb-3">
                <h3 className="text-xs font-medium text-gray-500 mb-1">
                  Group ({Math.round(schema.groupTemplate.width)}pt)
                </h3>
                <div className="space-y-0.5">
                  {schema.groupTemplate.children.map((child, i) => (
                    <div
                      key={i}
                      className="flex items-start gap-1.5 px-1 py-0.5"
                    >
                      <span
                        className={`inline-flex items-center justify-center w-4 h-4 text-[10px] font-bold rounded flex-shrink-0 mt-0.5 ${childColor(child)}`}
                      >
                        {childIcon(child)}
                      </span>
                      <div className="min-w-0">
                        <div className="text-xs text-gray-700">{child.role}</div>
                        {child.fields && child.fields.length > 0 && (
                          <div className="text-[10px] text-gray-400">
                            {child.fields.map((f) => f.name).join(", ")}
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Item Fields */}
            {schema && schema.itemFields.length > 0 && (
              <div>
                <h3 className="text-xs font-medium text-gray-500 mb-1">
                  Fields ({schema.itemFields.length})
                </h3>
                <div className="flex flex-wrap gap-1">
                  {schema.itemFields.map((field) => (
                    <span
                      key={field}
                      className="text-[10px] px-1.5 py-0.5 bg-blue-50 text-blue-600 rounded"
                    >
                      {fieldLabel(field)}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {/* Pages */}
            {idmlPath && !isLoading && (
              <div className="mt-3 pt-3 border-t">
                <div className="flex items-center justify-between mb-1">
                  <h3 className="text-xs font-medium text-gray-500">
                    Pages ({pages.length})
                  </h3>
                  <button
                    onClick={addPage}
                    className="text-[10px] px-1.5 py-0.5 bg-blue-50 text-blue-600 rounded hover:bg-blue-100"
                  >
                    +
                  </button>
                </div>

                {/* TF Mode */}
                {tfPageCount > 0 && (
                  <div className="flex items-center gap-1 mb-1.5">
                    <span className="text-[10px] text-gray-400">TF</span>
                    <select
                      value={tfMode}
                      onChange={(e) =>
                        setTfMode(e.target.value as "master" | "custom")
                      }
                      className="flex-1 text-[10px] border border-gray-200 rounded px-1 py-0.5 bg-white focus:outline-none focus:border-blue-400"
                    >
                      <option value="master">마스터 상속</option>
                      <option value="custom">2단 (5mm)</option>
                    </select>
                  </div>
                )}

                <div className="space-y-0.5">
                  {pages.map((page, index) => (
                    <div
                      key={page.id}
                      className="flex items-center gap-0.5 px-1 py-0.5 rounded border border-gray-100 text-[10px]"
                    >
                      <span className="text-gray-400 w-3 text-right flex-shrink-0">
                        {index + 1}
                      </span>
                      <select
                        value={page.masterSpreadId || "none"}
                        onChange={(e) =>
                          setPageMaster(
                            page.id,
                            e.target.value === "none" ? null : e.target.value
                          )
                        }
                        className="flex-1 min-w-0 text-[10px] border-none bg-transparent focus:outline-none"
                      >
                        <option value="none">-</option>
                        {masterSpreads.map((m) => (
                          <option key={m.id} value={m.id}>
                            {m.name}
                          </option>
                        ))}
                      </select>
                      {page.masterSpreadId && (
                        <label className="flex items-center cursor-pointer">
                          <input
                            type="checkbox"
                            checked={page.textFrame}
                            onChange={() => toggleTextFrame(page.id)}
                            className="w-2.5 h-2.5 rounded border-gray-300 text-blue-500"
                          />
                        </label>
                      )}
                      <button
                        onClick={() => movePageUp(page.id)}
                        disabled={index === 0}
                        className="text-gray-300 hover:text-gray-500 disabled:opacity-30"
                      >
                        <svg className="w-2.5 h-2.5" viewBox="0 0 16 16" fill="currentColor">
                          <path d="M8 4l4 4H4z" />
                        </svg>
                      </button>
                      <button
                        onClick={() => movePageDown(page.id)}
                        disabled={index === pages.length - 1}
                        className="text-gray-300 hover:text-gray-500 disabled:opacity-30"
                      >
                        <svg className="w-2.5 h-2.5" viewBox="0 0 16 16" fill="currentColor">
                          <path d="M8 12l4-4H4z" />
                        </svg>
                      </button>
                      <button
                        onClick={() => removePage(page.id)}
                        disabled={pages.length <= 1}
                        className="text-gray-300 hover:text-red-400 disabled:opacity-30"
                      >
                        <svg className="w-2.5 h-2.5" viewBox="0 0 16 16" fill="currentColor">
                          <path d="M4.646 4.646a.5.5 0 0 1 .708 0L8 7.293l2.646-2.647a.5.5 0 0 1 .708.708L8.707 8l2.647 2.646a.5.5 0 0 1-.708.708L8 8.707l-2.646 2.647a.5.5 0 0 1-.708-.708L7.293 8 4.646 5.354a.5.5 0 0 1 0-.708z" />
                        </svg>
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Center Panel - Data Items */}
        <div className="flex-1 overflow-y-auto border-r">
          <div className="p-3">
            <div className="flex items-center justify-between mb-2">
              <h2 className="text-sm font-semibold text-gray-600">
                Data Items
                {items.length > 0 && (
                  <span className="ml-1 text-gray-400">({items.length})</span>
                )}
              </h2>
              {schema && (
                <div className="flex items-center gap-1">
                  <button
                    onClick={loadDataFile}
                    className="text-xs px-2 py-1 bg-gray-100 text-gray-600 rounded hover:bg-gray-200"
                  >
                    JSON
                  </button>
                  <button
                    onClick={addItem}
                    className="text-xs px-2 py-1 bg-blue-50 text-blue-600 rounded hover:bg-blue-100"
                  >
                    + 추가
                  </button>
                </div>
              )}
            </div>

            {!schema && idmlPath && !isLoading && (
              <p className="text-sm text-gray-400 mt-4">
                스키마를 추출할 수 없습니다.
              </p>
            )}

            {!idmlPath && (
              <p className="text-sm text-gray-400 mt-4">
                IDML을 열면 문항 데이터를 입력할 수 있습니다.
              </p>
            )}

            {schema && items.length > 0 && (
              <div className="space-y-2">
                {items.map((item, itemIndex) => (
                  <div
                    key={itemIndex}
                    className="border border-gray-200 rounded-lg p-2.5 bg-white"
                  >
                    <div className="flex items-center justify-between mb-1.5">
                      <span className="text-xs font-medium text-gray-500">
                        #{itemIndex + 1}
                      </span>
                      <button
                        onClick={() => removeItem(itemIndex)}
                        disabled={items.length <= 1}
                        className="text-gray-300 hover:text-red-400 disabled:opacity-30"
                      >
                        <svg
                          className="w-3.5 h-3.5"
                          viewBox="0 0 16 16"
                          fill="currentColor"
                        >
                          <path d="M4.646 4.646a.5.5 0 0 1 .708 0L8 7.293l2.646-2.647a.5.5 0 0 1 .708.708L8.707 8l2.647 2.646a.5.5 0 0 1-.708.708L8 8.707l-2.646 2.647a.5.5 0 0 1-.708-.708L7.293 8 4.646 5.354a.5.5 0 0 1 0-.708z" />
                        </svg>
                      </button>
                    </div>
                    <div className="space-y-1">
                      {schema.itemFields.map((field) => (
                        <div key={field} className="flex items-start gap-2">
                          <label className="text-[11px] text-gray-500 w-10 pt-1 text-right flex-shrink-0">
                            {fieldLabel(field)}
                          </label>
                          {field === "question" ? (
                            <textarea
                              value={item[field] || ""}
                              onChange={(e) =>
                                setItemField(itemIndex, field, e.target.value)
                              }
                              rows={2}
                              className="flex-1 text-xs border border-gray-200 rounded px-2 py-1 focus:outline-none focus:border-blue-400 resize-none"
                              placeholder={`${fieldLabel(field)} 입력...`}
                            />
                          ) : (
                            <input
                              type="text"
                              value={item[field] || ""}
                              onChange={(e) =>
                                setItemField(itemIndex, field, e.target.value)
                              }
                              className="flex-1 text-xs border border-gray-200 rounded px-2 py-1 focus:outline-none focus:border-blue-400"
                              placeholder={`${fieldLabel(field)} 입력...`}
                            />
                          )}
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Right Panel - Master Preview */}
        <div className="w-80 overflow-auto flex items-center justify-center bg-gray-50 flex-shrink-0">
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
                왼쪽 패널에서 마스터를 클릭하면 프리뷰가 표시됩니다.
              </p>
            )}

          {!idmlPath && (
            <p className="text-sm text-gray-400 text-center px-4">
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
                  ? `생성 완료: ${createResult.page_count}페이지, 마스터 ${createResult.master_count}개`
                  : "생성 실패"}
                {createResult.validation &&
                  (createResult.validation.valid
                    ? " - 검증 통과"
                    : ` - 검증 오류: ${createResult.validation.errors.join(", ")}`)}
              </span>
            )}
          </div>
          <button
            onClick={mergeIdml}
            disabled={
              !idmlPath ||
              isCreating ||
              !schema ||
              nonEmptyItemCount === 0
            }
            className="px-4 py-2 bg-green-600 text-white text-sm rounded hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {isCreating
              ? "생성 중..."
              : `IDML 생성 (${nonEmptyItemCount}문항, ${pages.length}p)`}
          </button>
        </div>
      </div>
    </div>
  );
}
