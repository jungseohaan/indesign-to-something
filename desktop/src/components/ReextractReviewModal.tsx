import { useSemanticStore } from "../stores/useSemanticStore";

export function ReextractReviewModal() {
  const layer = useSemanticStore((s) => s.layer);
  const setShowReextractReview = useSemanticStore(
    (s) => s.setShowReextractReview
  );

  const lastMerge = layer?.mergeHistory[layer.mergeHistory.length - 1];
  if (!lastMerge) {
    return null;
  }

  const stats = lastMerge.stats;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-[420px]">
        <div className="flex items-center justify-between px-5 py-3 border-b">
          <h2 className="text-base font-bold">재추출 결과</h2>
          <button
            onClick={() => setShowReextractReview(false)}
            className="text-gray-400 hover:text-gray-600 text-lg"
          >
            ×
          </button>
        </div>

        <div className="p-5 space-y-3 text-sm">
          <div className="grid grid-cols-2 gap-2">
            <StatCard label="매칭됨" value={stats.matched} color="text-blue-600" />
            <StatCard label="수동 보존" value={stats.manualPreserved} color="text-orange-500" />
            <StatCard label="재분류" value={stats.reclassified} color="text-green-600" />
            <StatCard label="추가됨" value={stats.added} color="text-emerald-600" />
            <StatCard label="삭제됨" value={stats.deleted} color="text-red-500" />
            <StatCard label="유사도 매칭" value={stats.symmetryMatched} color="text-purple-600" />
          </div>

          {layer?.deletedNodes && layer.deletedNodes.length > 0 && (
            <div className="border-t pt-3">
              <h3 className="text-xs font-medium text-gray-500 mb-1">
                삭제된 노드 ({layer.deletedNodes.length})
              </h3>
              <div className="max-h-32 overflow-y-auto space-y-1">
                {layer.deletedNodes.slice(-10).map((d) => (
                  <div key={d.id} className="flex items-center gap-2 text-xs">
                    <span className="text-gray-400">{d.id}</span>
                    <span className="font-medium">{d.label}</span>
                    {d.manualOverride && (
                      <span className="text-orange-400">수동</span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          <p className="text-xs text-gray-400">
            {lastMerge.timestamp}
          </p>
        </div>

        <div className="flex justify-end px-5 py-3 border-t">
          <button
            onClick={() => setShowReextractReview(false)}
            className="px-4 py-1.5 text-sm bg-blue-500 text-white rounded hover:bg-blue-600"
          >
            확인
          </button>
        </div>
      </div>
    </div>
  );
}

function StatCard({
  label,
  value,
  color,
}: {
  label: string;
  value: number;
  color: string;
}) {
  return (
    <div className="bg-gray-50 rounded p-2.5">
      <p className="text-xs text-gray-400">{label}</p>
      <p className={`text-lg font-bold ${color}`}>{value}</p>
    </div>
  );
}
