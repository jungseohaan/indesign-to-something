import { useEffect, useState } from "react";
import { useSemanticStore } from "../stores/useSemanticStore";
import { useAstStore } from "../stores/useAstStore";
import { useAppStore } from "../stores/useAppStore";
import { SemanticTreePanel } from "./SemanticTreePanel";
import { SemanticPreviewPanel } from "./SemanticPreviewPanel";
import { SemanticTextPreview } from "./SemanticTextPreview";
import { SemanticDetailPanel } from "./SemanticDetailPanel";
import { SchemaEditorModal } from "./SchemaEditorModal";
import { RuleSuggesterPanel } from "./RuleSuggesterPanel";
import { PPTExportModal } from "./PPTExportModal";

export function SemanticPage() {
  const astDoc = useAstStore((s) => s.astDoc);
  const extractForSemantic = useAppStore((s) => s.extractForSemantic);
  const isExtractingForSemantic = useAppStore((s) => s.isExtractingForSemantic);
  const extractSemanticError = useAppStore((s) => s.extractSemanticError);
  const nodes = useSemanticStore((s) => s.nodes);
  const activeSchema = useSemanticStore((s) => s.activeSchema);
  const activeSchemaId = useSemanticStore((s) => s.activeSchemaId);
  const loadFromAst = useSemanticStore((s) => s.loadFromAst);
  const generateSchemaFromAst = useSemanticStore((s) => s.generateSchemaFromAst);
  const reclassify = useSemanticStore((s) => s.reclassify);
  const loadSchema = useSemanticStore((s) => s.loadSchema);
  const setShowSchemaEditor = useSemanticStore((s) => s.setShowSchemaEditor);
  const showSchemaEditor = useSemanticStore((s) => s.showSchemaEditor);
  const showRuleSuggester = useSemanticStore((s) => s.showRuleSuggester);
  const isProcessing = useSemanticStore((s) => s.isProcessing);
  const error = useSemanticStore((s) => s.error);
  const saveLayerToFile = useSemanticStore((s) => s.saveLayerToFile);
  const loadLayerFromFile = useSemanticStore((s) => s.loadLayerFromFile);
  const schemaLoader = useSemanticStore((s) => s.schemaLoader);

  // 내장 스키마 로드
  useEffect(() => {
    try {
      // Vite JSON import를 통해 스키마 로드
      import("../../../packages/semantic-layer/schemas/common.schema.json").then(
        (m) => loadSchema(m.default ?? m),
        () => {} // 없으면 무시
      );
      import("../../../packages/semantic-layer/schemas/math-reference.schema.json").then(
        (m) => loadSchema(m.default ?? m),
        () => {} // 없으면 무시
      );
    } catch { /* 무시 */ }
  }, [loadSchema]);

  const handleExtract = () => {
    if (!astDoc) return;
    loadFromAst(astDoc);
  };

  const handleGenerateSchema = () => {
    if (!astDoc) return;
    generateSchemaFromAst(astDoc);
  };

  const [showPptExport, setShowPptExport] = useState(false);
  const [previewMode, setPreviewMode] = useState<"visual" | "text">("visual");
  const schemaIds = schemaLoader.listIds();

  return (
    <div className="flex-1 flex flex-col min-h-0">
      {/* 상단 툴바 */}
      <div className="flex items-center gap-3 border-b bg-gray-50 px-4 py-2 shrink-0">
        <button
          onClick={handleExtract}
          disabled={!astDoc || isProcessing}
          className="px-3 py-1.5 bg-blue-500 text-white text-sm rounded hover:bg-blue-600 disabled:opacity-50"
        >
          {nodes.length > 0 ? "재추출" : "시멘틱 추출"}
        </button>

        {nodes.length > 0 && (
          <button
            onClick={reclassify}
            disabled={!activeSchema}
            className="px-3 py-1.5 bg-green-500 text-white text-sm rounded hover:bg-green-600 disabled:opacity-50"
          >
            재분류
          </button>
        )}

        <div className="flex items-center gap-1.5">
          <label className="text-xs text-gray-500">스키마:</label>
          <select
            value={activeSchemaId ?? ""}
            onChange={(e) => {
              const id = e.target.value;
              if (id) {
                useSemanticStore.getState().setActiveSchema(id);
              }
            }}
            className="px-2 py-1 text-xs border rounded"
          >
            <option value="">선택 없음</option>
            {schemaIds.map((id) => (
              <option key={id} value={id}>
                {schemaLoader.get(id)?.schemaName ?? id}
              </option>
            ))}
          </select>
          <button
            onClick={() => setShowSchemaEditor(true)}
            className="px-2 py-1 text-xs text-gray-500 hover:text-gray-700 border rounded"
          >
            편집
          </button>
          <button
            onClick={handleGenerateSchema}
            disabled={!astDoc || isProcessing}
            className="px-2 py-1 text-xs text-purple-600 hover:text-purple-800 border border-purple-300 rounded hover:bg-purple-50 disabled:opacity-50"
          >
            AST에서 생성
          </button>
        </div>

        <div className="flex-1" />

        {nodes.length > 0 && (
          <>
            <span className="text-xs text-gray-400">
              {nodes.length}개 노드 ·{" "}
              {nodes.filter((n) => n.label !== "UNKNOWN").length}개 분류됨 ·{" "}
              {nodes.filter((n) => n.manualOverride).length}개 수동
            </span>
            <button
              onClick={loadLayerFromFile}
              className="px-3 py-1.5 text-sm text-gray-600 border rounded hover:bg-gray-100"
            >
              JSON 불러오기
            </button>
            <button
              onClick={saveLayerToFile}
              className="px-3 py-1.5 text-sm text-gray-600 border rounded hover:bg-gray-100"
            >
              JSON 내보내기
            </button>
            <button
              onClick={() => setShowPptExport(true)}
              className="px-3 py-1.5 text-sm bg-orange-500 text-white rounded hover:bg-orange-600"
            >
              PPT 내보내기
            </button>
          </>
        )}
      </div>

      {error && (
        <div className="px-4 py-2 bg-red-50 border-b border-red-200 text-red-700 text-sm">
          {error}
        </div>
      )}

      {/* 메인 콘텐츠 */}
      {nodes.length === 0 ? (
        <div className="flex-1 flex items-center justify-center text-gray-400">
          <div className="text-center">
            <p className="text-lg mb-2">시멘틱 레이어</p>
            {astDoc ? (
              <p className="text-sm">
                "시멘틱 추출" 버튼을 눌러 AST에서 시멘틱 노드를 추출하세요.
              </p>
            ) : (
              <>
                <p className="text-sm mb-3">
                  {isExtractingForSemantic
                    ? "InDesign에서 추출 중입니다..."
                    : "먼저 InDesign 파일을 열어 AST를 로드하세요."}
                </p>
                {extractSemanticError && (
                  <p className="text-sm text-red-500 mb-2">{extractSemanticError}</p>
                )}
                <button
                  onClick={extractForSemantic}
                  disabled={isExtractingForSemantic}
                  className="px-4 py-2 bg-blue-500 text-white text-sm rounded hover:bg-blue-600 disabled:opacity-50"
                >
                  {isExtractingForSemantic ? "추출 중..." : "INDD 파일 열기"}
                </button>
              </>
            )}
          </div>
        </div>
      ) : (
        <div className="flex-1 flex min-h-0">
          {/* 왼쪽: 노드 트리 */}
          <div className="w-[320px] border-r overflow-hidden flex flex-col">
            <SemanticTreePanel />
          </div>

          {/* 중앙: 프리뷰 */}
          <div className="flex-1 overflow-hidden flex flex-col">
            <div className="flex border-b bg-gray-50 shrink-0">
              <button
                onClick={() => setPreviewMode("visual")}
                className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                  previewMode === "visual"
                    ? "text-blue-600 border-b-2 border-blue-500"
                    : "text-gray-500 hover:text-gray-700"
                }`}
              >
                시각 프리뷰
              </button>
              <button
                onClick={() => setPreviewMode("text")}
                className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                  previewMode === "text"
                    ? "text-blue-600 border-b-2 border-blue-500"
                    : "text-gray-500 hover:text-gray-700"
                }`}
              >
                텍스트 프리뷰
              </button>
            </div>
            <div className="flex-1 min-h-0">
              {previewMode === "text" ? (
                <SemanticTextPreview />
              ) : (
                <SemanticPreviewPanel />
              )}
            </div>
          </div>

          {/* 오른쪽: 상세 */}
          <div className="w-[340px] border-l overflow-hidden flex flex-col">
            <SemanticDetailPanel />
          </div>
        </div>
      )}

      {/* 모달 */}
      {showSchemaEditor && <SchemaEditorModal />}
      {showRuleSuggester && <RuleSuggesterPanel />}
      {showPptExport && <PPTExportModal onClose={() => setShowPptExport(false)} />}
    </div>
  );
}
