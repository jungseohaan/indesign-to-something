import { useState } from "react";
import { useSemanticStore } from "../stores/useSemanticStore";

export function SchemaEditorModal() {
  const setShowSchemaEditor = useSemanticStore((s) => s.setShowSchemaEditor);
  const schemaLoader = useSemanticStore((s) => s.schemaLoader);
  const activeSchemaId = useSemanticStore((s) => s.activeSchemaId);
  const loadSchema = useSemanticStore((s) => s.loadSchema);
  const reclassify = useSemanticStore((s) => s.reclassify);
  const setActiveSchema = useSemanticStore((s) => s.setActiveSchema);

  const schemaIds = schemaLoader.listIds();
  const [selectedId, setSelectedId] = useState(activeSchemaId ?? schemaIds[0] ?? "");
  const [jsonText, setJsonText] = useState(() => {
    if (!selectedId) return "";
    const schema = schemaLoader.get(selectedId);
    return schema ? JSON.stringify(schema, null, 2) : "";
  });
  const [error, setError] = useState<string | null>(null);

  const handleSelectSchema = (id: string) => {
    setSelectedId(id);
    const schema = schemaLoader.get(id);
    setJsonText(schema ? JSON.stringify(schema, null, 2) : "");
    setError(null);
  };

  const handleSave = () => {
    try {
      const parsed = JSON.parse(jsonText);
      loadSchema(parsed);
      setActiveSchema(parsed.schemaId);
      setError(null);
    } catch (e) {
      setError(String(e));
    }
  };

  const handleApply = () => {
    handleSave();
    reclassify();
    setShowSchemaEditor(false);
  };

  const handleImportFile = () => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = ".json";
    input.onchange = (e) => {
      const file = (e.target as HTMLInputElement).files?.[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        const text = reader.result as string;
        setJsonText(text);
        try {
          const parsed = JSON.parse(text);
          loadSchema(parsed);
          setSelectedId(parsed.schemaId);
          setError(null);
        } catch (err) {
          setError(String(err));
        }
      };
      reader.readAsText(file);
    };
    input.click();
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-[700px] max-h-[80vh] flex flex-col">
        <div className="flex items-center justify-between px-5 py-3 border-b">
          <h2 className="text-base font-bold">스키마 편집</h2>
          <button
            onClick={() => setShowSchemaEditor(false)}
            className="text-gray-400 hover:text-gray-600 text-lg"
          >
            ×
          </button>
        </div>

        <div className="flex-1 flex min-h-0">
          {/* 스키마 목록 */}
          <div className="w-40 border-r overflow-y-auto p-2">
            {schemaIds.map((id) => (
              <button
                key={id}
                onClick={() => handleSelectSchema(id)}
                className={`w-full text-left px-2 py-1.5 text-xs rounded ${
                  selectedId === id
                    ? "bg-blue-50 text-blue-700 font-medium"
                    : "text-gray-600 hover:bg-gray-50"
                }`}
              >
                {schemaLoader.get(id)?.schemaName ?? id}
              </button>
            ))}
            <button
              onClick={handleImportFile}
              className="w-full text-left px-2 py-1.5 text-xs text-blue-500 hover:text-blue-700 mt-2"
            >
              + JSON 불러오기
            </button>
          </div>

          {/* JSON 편집기 */}
          <div className="flex-1 flex flex-col min-h-0 p-3">
            <textarea
              value={jsonText}
              onChange={(e) => {
                setJsonText(e.target.value);
                setError(null);
              }}
              className="flex-1 w-full font-mono text-xs border rounded p-2 resize-none"
              spellCheck={false}
            />
            {error && (
              <p className="mt-1 text-xs text-red-500">{error}</p>
            )}
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t">
          <button
            onClick={() => setShowSchemaEditor(false)}
            className="px-3 py-1.5 text-sm text-gray-600 border rounded hover:bg-gray-50"
          >
            취소
          </button>
          <button
            onClick={handleSave}
            className="px-3 py-1.5 text-sm text-blue-600 border border-blue-300 rounded hover:bg-blue-50"
          >
            저장
          </button>
          <button
            onClick={handleApply}
            className="px-3 py-1.5 text-sm bg-blue-500 text-white rounded hover:bg-blue-600"
          >
            적용 & 재분류
          </button>
        </div>
      </div>
    </div>
  );
}
