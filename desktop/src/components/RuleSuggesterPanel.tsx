import { useSemanticStore } from "../stores/useSemanticStore";

export function RuleSuggesterPanel() {
  const suggestedRules = useSemanticStore((s) => s.suggestedRules);
  const setShowRuleSuggester = useSemanticStore((s) => s.setShowRuleSuggester);
  const validationResult = useSemanticStore((s) => s.validationResult);
  const runValidation = useSemanticStore((s) => s.runValidation);

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-[600px] max-h-[70vh] flex flex-col">
        <div className="flex items-center justify-between px-5 py-3 border-b">
          <h2 className="text-base font-bold">규칙 제안</h2>
          <button
            onClick={() => setShowRuleSuggester(false)}
            className="text-gray-400 hover:text-gray-600 text-lg"
          >
            ×
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 text-xs space-y-3">
          {suggestedRules.length === 0 ? (
            <p className="text-gray-400 text-center py-8">
              수동 레이블이 2개 이상 필요합니다.
            </p>
          ) : (
            suggestedRules.map((rule, i) => (
              <div key={i} className="border rounded p-3">
                <div className="flex items-center justify-between mb-2">
                  <span className="font-medium text-sm">{rule.label}</span>
                  <span className="text-gray-400">
                    커버리지 {Math.round(rule.coverage * 100)}% ·{" "}
                    {rule.matchCount}개 매칭
                  </span>
                </div>

                <div className="space-y-1">
                  {rule.conditions.map((c, j) => (
                    <div key={j} className="flex gap-2 text-gray-600">
                      <code className="font-mono text-blue-600">
                        {c.field}
                      </code>
                      <span className="text-gray-400">{c.operator}</span>
                      <code className="font-mono">{JSON.stringify(c.value)}</code>
                    </div>
                  ))}
                </div>

                <div className="mt-2 flex gap-2">
                  <button
                    onClick={() => {
                      // 규칙 JSON 클립보드 복사
                      const json = JSON.stringify(rule, null, 2);
                      navigator.clipboard.writeText(json);
                    }}
                    className="px-2 py-0.5 text-xs border rounded text-gray-500 hover:bg-gray-50"
                  >
                    복사
                  </button>
                </div>
              </div>
            ))
          )}

          {/* 검증 결과 */}
          {validationResult && (
            <div className="border-t pt-3 mt-3">
              <h3 className="font-medium mb-2">검증 결과</h3>
              <div className="flex gap-4 mb-2">
                <span>정확도: {Math.round(validationResult.accuracy * 100)}%</span>
                <span>분류: {validationResult.classifiedCount}/{validationResult.totalCount}</span>
              </div>
              <table className="w-full text-left">
                <thead>
                  <tr className="text-gray-400">
                    <th className="py-0.5">레이블</th>
                    <th>P</th>
                    <th>R</th>
                    <th>F1</th>
                    <th>수</th>
                  </tr>
                </thead>
                <tbody>
                  {validationResult.labelMetrics.map((m) => (
                    <tr key={m.label}>
                      <td className="py-0.5 font-medium">{m.label}</td>
                      <td>{Math.round(m.precision * 100)}%</td>
                      <td>{Math.round(m.recall * 100)}%</td>
                      <td>{Math.round(m.f1 * 100)}%</td>
                      <td className="text-gray-400">{m.support}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t">
          <button
            onClick={runValidation}
            className="px-3 py-1.5 text-sm text-blue-600 border border-blue-300 rounded hover:bg-blue-50"
          >
            규칙 검증
          </button>
          <button
            onClick={() => setShowRuleSuggester(false)}
            className="px-3 py-1.5 text-sm bg-blue-500 text-white rounded hover:bg-blue-600"
          >
            닫기
          </button>
        </div>
      </div>
    </div>
  );
}
