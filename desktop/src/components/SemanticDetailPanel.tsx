import { useSemanticStore } from "../stores/useSemanticStore";

export function SemanticDetailPanel() {
  const getSelectedNode = useSemanticStore((s) => s.getSelectedNode);
  const getNodeRelations = useSemanticStore((s) => s.getNodeRelations);
  const setLabel = useSemanticStore((s) => s.setLabel);
  const removeLabel = useSemanticStore((s) => s.removeLabel);
  const labels = useSemanticStore((s) => s.labels);
  const nodes = useSemanticStore((s) => s.nodes);
  const selectNode = useSemanticStore((s) => s.selectNode);

  const node = getSelectedNode();
  if (!node) {
    return (
      <div className="h-full flex items-center justify-center text-gray-400 text-sm">
        노드를 선택하세요
      </div>
    );
  }

  const relations = getNodeRelations(node.id);
  const f = node.features;

  return (
    <div className="h-full overflow-y-auto text-xs">
      {/* 레이블 변경 */}
      <div className="p-3 border-b">
        <div className="flex items-center gap-2 mb-2">
          <span className="font-medium text-sm">{node.label}</span>
          {node.manualOverride && (
            <span className="text-orange-400 text-xs">수동</span>
          )}
          {node.confidence > 0 && (
            <span className="text-gray-400">
              ({Math.round(node.confidence * 100)}%)
            </span>
          )}
        </div>

        <div className="flex flex-wrap gap-1">
          {labels.map((l) => (
            <button
              key={l.id}
              onClick={() => setLabel(node.id, l.id)}
              className={`px-2 py-0.5 rounded text-white ${
                node.label === l.id ? "ring-2 ring-offset-1 ring-gray-400" : ""
              }`}
              style={{ backgroundColor: l.color }}
              title={l.description}
            >
              {l.name}
            </button>
          ))}
          {node.label !== "UNKNOWN" && (
            <button
              onClick={() => removeLabel(node.id)}
              className="px-2 py-0.5 rounded border text-gray-500 hover:bg-gray-100"
            >
              초기화
            </button>
          )}
        </div>

        {node.appliedRule && (
          <p className="mt-1 text-gray-400">규칙: {node.appliedRule}</p>
        )}
      </div>

      {/* 텍스트 미리보기 */}
      <Section title="텍스트">
        <p className="text-gray-600 whitespace-pre-wrap max-h-32 overflow-y-auto">
          {f.textContent || "(텍스트 없음)"}
        </p>
        <Row label="길이" value={`${f.textLength}자`} />
        <Row label="문단 수" value={f.paragraphCount} />
        <Row label="첫 줄" value={f.firstLineText} />
      </Section>

      {/* 위치 & 레이아웃 */}
      <Section title="위치">
        <Row label="페이지" value={f.pageNumber} />
        <Row label="좌표" value={`(${f.x}, ${f.y})`} />
        <Row label="크기" value={`${f.width} × ${f.height}`} />
        <Row label="영역" value={f.regionTag} />
        <Row label="Z-순서" value={f.zOrder} />
        <Row label="단" value={f.columnIndex} />
      </Section>

      {/* 스토리 */}
      {f.storyId && (
        <Section title="스토리">
          <Row label="ID" value={f.storyId} />
          <Row label="프레임 수" value={f.storyFrameCount} />
          <Row label="인덱스" value={f.frameIndexInStory} />
          <Row label="시작" value={f.isStoryStart ? "예" : "아니오"} />
          <Row label="끝" value={f.isStoryEnd ? "예" : "아니오"} />
        </Section>
      )}

      {/* 스타일 */}
      <Section title="스타일">
        <Row label="폰트" value={f.dominantFontFamily} />
        <Row label="크기" value={f.dominantFontSize} />
        <Row label="최대 크기" value={f.maxFontSize} />
        <Row label="Bold" value={f.hasBoldText ? "예" : "아니오"} />
        <Row label="정렬" value={f.dominantAlignment} />
        <Row label="문단 스타일" value={f.dominantParagraphStyle} />
        {f.hasNumberPrefix && (
          <Row label="번호 패턴" value={f.numberPrefixPattern} />
        )}
      </Section>

      {/* 프레임 속성 */}
      <Section title="프레임">
        <Row label="타입" value={f.blockType} />
        <Row label="배경" value={f.hasFill ? f.fillColor ?? "있음" : "없음"} />
        <Row label="테두리" value={f.hasStroke ? "있음" : "없음"} />
        <Row label="배경전용" value={f.isBackgroundOnly ? "예" : "아니오"} />
        <Row label="단 수" value={f.columnCount} />
      </Section>

      {/* 콘텐츠 */}
      <Section title="콘텐츠">
        <Row label="수식" value={f.hasEquation ? "있음" : "없음"} />
        <Row label="이미지" value={f.hasImage ? "있음" : "없음"} />
        <Row label="인라인" value={f.inlineObjectCount} />
      </Section>

      {/* 공간 근접도 */}
      <Section title="공간 관계">
        {f.spatial.isVisuallyContainedBy && (
          <Row label="포함됨" value={f.spatial.isVisuallyContainedBy} />
        )}
        {f.spatial.nearestContentNodeId && (
          <Row label="최근접" value={f.spatial.nearestContentNodeId} />
        )}
        {f.spatial.overlappingNodeIds.length > 0 && (
          <Row
            label="겹침"
            value={`${f.spatial.overlappingNodeIds.length}개`}
          />
        )}
      </Section>

      {/* 관계 */}
      {relations.length > 0 && (
        <Section title="관계">
          {relations.map((r, i) => {
            const isSource = r.sourceId === node.id;
            const otherId = isSource ? r.targetId : r.sourceId;
            const otherNode = nodes.find((n) => n.id === otherId);
            return (
              <div
                key={i}
                className="flex items-center gap-1 py-0.5 cursor-pointer hover:bg-gray-50"
                onClick={() => selectNode(otherId)}
              >
                <span className="text-gray-400">
                  {isSource ? "→" : "←"}
                </span>
                <span className="font-medium">{r.type}</span>
                <span className="text-gray-500 truncate">
                  {otherNode?.label ?? otherId}
                </span>
              </div>
            );
          })}
        </Section>
      )}

      {/* ID */}
      <Section title="메타">
        <Row label="ID" value={node.id} />
        <Row label="AST 경로" value={node.astPath} />
        <Row label="노드 타입" value={node.nodeType} />
      </Section>
    </div>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <div className="p-3 border-b">
      <h3 className="font-medium text-gray-700 mb-1">{title}</h3>
      {children}
    </div>
  );
}

function Row({
  label,
  value,
}: {
  label: string;
  value: string | number | null | undefined;
}) {
  if (value == null || value === "") return null;
  return (
    <div className="flex justify-between py-0.5">
      <span className="text-gray-400">{label}</span>
      <span className="text-gray-700 text-right max-w-[180px] truncate">
        {String(value)}
      </span>
    </div>
  );
}
