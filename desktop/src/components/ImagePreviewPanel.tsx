import { useAppStore } from "../stores/useAppStore";

export function ImagePreviewPanel() {
  const {
    selectedImage,
    selectedTextFrame,
    previewImages,
    isGeneratingPreview,
    textFrameDetail,
    isLoadingTextDetail,
    masterPreview,
    isGeneratingMasterPreview,
    selectedMaster,
  } = useAppStore();

  // Master spread preview
  if (selectedMaster) {
    if (isGeneratingMasterPreview) {
      return (
        <div className="flex items-center justify-center h-full text-gray-500">
          마스터 프리뷰 생성 중...
        </div>
      );
    }
    if (masterPreview) {
      return (
        <div className="h-full flex flex-col items-center justify-center p-4">
          <img
            src={masterPreview.data_url}
            alt={masterPreview.filename}
            className="max-w-full max-h-full object-contain shadow-lg border"
            style={{ maxHeight: "calc(100vh - 200px)" }}
          />
          <div className="mt-2 text-xs text-gray-500">
            {masterPreview.width} x {masterPreview.height}px
          </div>
        </div>
      );
    }
  }

  // Text frame detail
  if (selectedTextFrame) {
    if (isLoadingTextDetail) {
      return (
        <div className="flex items-center justify-center h-full text-gray-500">
          텍스트 프레임 로딩 중...
        </div>
      );
    }

    // textFrameDetail(API)이 있으면 상세 뷰, 없으면 story_content 폴백
    const sc = selectedTextFrame.story_content;

    if (textFrameDetail) {
      return (
        <div className="h-full overflow-auto p-4">
          <h3 className="font-medium text-sm mb-2">
            Text Frame: {textFrameDetail.frame_id}
          </h3>

          {/* Frame Properties */}
          {textFrameDetail.frame_properties && (
            <div className="mb-3 p-2 bg-gray-50 rounded text-xs space-y-1">
              <div className="font-medium text-gray-600">Frame Properties</div>
              <div className="flex gap-4 text-gray-500">
                <span>{textFrameDetail.frame_properties.width.toFixed(1)} x {textFrameDetail.frame_properties.height.toFixed(1)}</span>
                {textFrameDetail.frame_properties.fill_color && (
                  <span>Fill: {textFrameDetail.frame_properties.fill_color}</span>
                )}
                {textFrameDetail.frame_properties.stroke_color && (
                  <span>Stroke: {textFrameDetail.frame_properties.stroke_color} ({textFrameDetail.frame_properties.stroke_weight}pt)</span>
                )}
              </div>
            </div>
          )}

          {/* Paragraphs */}
          <div className="space-y-2 mb-4">
            {textFrameDetail.paragraphs.map((para, i) => (
              <div key={i} className="border rounded p-2 text-sm">
                <div className="text-xs text-gray-500 mb-1 flex items-center gap-2">
                  <span className="font-medium">{para.style_name}</span>
                  {para.style && para.style.font_family && (
                    <span className="text-gray-400">{para.style.font_family} {para.style.font_size}pt</span>
                  )}
                </div>
                <div className="mb-1">{para.text}</div>
                {/* Character Runs */}
                {para.runs && para.runs.length > 0 && (
                  <div className="mt-1 border-t pt-1 space-y-0.5">
                    {para.runs.map((run, ri) => (
                      <div key={ri} className="text-[11px] flex items-center gap-1">
                        {run.anchors && run.anchors.length > 0 ? (
                          <span className="text-orange-500 font-mono">
                            [anchor: {run.anchors.join(", ")}]
                          </span>
                        ) : (
                          <span className="text-gray-600 truncate">{run.text}</span>
                        )}
                        {run.font_style && (
                          <span className="text-gray-400 text-[10px]">{run.font_style}</span>
                        )}
                        {run.fill_color && (
                          <span className="text-gray-400 text-[10px]">[{run.fill_color}]</span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>

          {/* Raw JSON */}
          <div className="border-t pt-3">
            <h4 className="font-medium text-xs text-gray-500 mb-1">Raw JSON</h4>
            <pre className="text-[10px] bg-gray-50 border rounded p-2 overflow-auto max-h-[400px] whitespace-pre-wrap text-gray-600">
              {JSON.stringify(textFrameDetail, null, 2)}
            </pre>
          </div>
        </div>
      );
    }

    // Fallback: story_content from analyze data
    if (sc) {
      return (
        <div className="h-full overflow-auto p-4">
          <h3 className="font-medium text-sm mb-2">
            Text Frame: {selectedTextFrame.id}
          </h3>
          <div className="mb-2 text-xs text-gray-400">
            Story: {sc.story_id} &middot; {sc.paragraph_count} paragraphs
          </div>

          <div className="space-y-2 mb-4">
            {sc.paragraphs.map((p, i) => (
              <div key={i} className="border rounded p-2 text-sm">
                <div className="text-xs text-gray-500 mb-1">
                  {p.style_name || "(no style)"}
                </div>
                {p.text && <div className="mb-1 whitespace-pre-wrap">{p.text}</div>}
                {p.runs && p.runs.length > 0 && (
                  <div className="mt-1 border-t pt-1 space-y-0.5">
                    {p.runs.map((r, ri) => (
                      <div key={ri} className="text-[11px] flex items-center gap-1">
                        {r.type === "text" ? (
                          <>
                            <span className="text-gray-600 truncate">{r.text}</span>
                            {r.font_style && (
                              <span className="text-gray-400 text-[10px]">{r.font_style}</span>
                            )}
                            {r.font_size && (
                              <span className="text-gray-400 text-[10px]">{r.font_size}pt</span>
                            )}
                          </>
                        ) : (
                          <span className="text-orange-500 font-mono text-[10px]">
                            [{r.type}] {r.frame_id || r.graphic_type || ""}{" "}
                            {r.width && r.height ? `${Math.round(r.width)}x${Math.round(r.height)}` : ""}
                          </span>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>

          {/* Raw JSON */}
          <div className="border-t pt-3">
            <h4 className="font-medium text-xs text-gray-500 mb-1">Raw JSON (story_content)</h4>
            <pre className="text-[10px] bg-gray-50 border rounded p-2 overflow-auto max-h-[400px] whitespace-pre-wrap text-gray-600">
              {JSON.stringify(sc, null, 2)}
            </pre>
          </div>
        </div>
      );
    }
  }

  // Group detail
  if (selectedImage && selectedImage.type === "group") {
    const childCount = selectedImage.children?.length ?? 0;
    const childTypes = (selectedImage.children ?? []).reduce<Record<string, number>>((acc, c) => {
      acc[c.type] = (acc[c.type] || 0) + 1;
      return acc;
    }, {});

    return (
      <div className="h-full overflow-auto p-4">
        <h3 className="font-medium text-sm mb-2">
          Group: {selectedImage.label || selectedImage.id}
        </h3>
        <div className="mb-3 p-2 bg-amber-50 rounded text-xs space-y-1">
          <div className="flex gap-4 text-gray-600">
            <span>Size: {Math.round(selectedImage.width)} x {Math.round(selectedImage.height)}</span>
            <span>Position: ({Math.round(selectedImage.x)}, {Math.round(selectedImage.y)})</span>
          </div>
          <div className="text-gray-500">
            Children: {childCount} ({Object.entries(childTypes).map(([t, n]) => `${n} ${t}`).join(", ")})
          </div>
        </div>

        {/* Child frames list */}
        {selectedImage.children && selectedImage.children.length > 0 && (
          <div className="space-y-1 mb-4">
            <h4 className="font-medium text-xs text-gray-600">Child Frames</h4>
            {selectedImage.children.map((child, i) => (
              <div key={i} className="border rounded p-2 text-xs">
                <div className="flex items-center gap-2">
                  <span className={`inline-flex items-center justify-center w-4 h-4 rounded text-[10px] font-bold ${
                    child.type === "text" ? "text-blue-600 bg-blue-50" :
                    child.type === "image" ? "text-green-600 bg-green-50" :
                    child.type === "vector" ? "text-purple-600 bg-purple-50" :
                    "text-gray-600 bg-gray-50"
                  }`}>
                    {child.type === "text" ? "T" : child.type === "image" ? "I" : child.type === "vector" ? "V" : "?"}
                  </span>
                  <span className="font-medium text-gray-700">{child.label || child.id}</span>
                  <span className="text-gray-400 ml-auto">{Math.round(child.width)}x{Math.round(child.height)}</span>
                </div>
                {child.story_content && (
                  <div className="mt-1 text-gray-500 ml-6">
                    {child.story_content.paragraph_count} paragraphs
                    {child.story_content.paragraphs.some(p => p.runs.some(r => r.type !== "text")) && (
                      <span className="text-orange-500 ml-1">(has inline objects)</span>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}

        {/* Raw JSON */}
        <div className="border-t pt-3">
          <h4 className="font-medium text-xs text-gray-500 mb-1">Raw JSON</h4>
          <pre className="text-[10px] bg-gray-50 border rounded p-2 overflow-auto max-h-[400px] whitespace-pre-wrap text-gray-600">
            {JSON.stringify(selectedImage, null, 2)}
          </pre>
        </div>
      </div>
    );
  }

  // Image/vector preview
  if (selectedImage) {
    const previewKey =
      selectedImage.type === "image"
        ? `image:${selectedImage.id}`
        : `vector:${selectedImage.id}`;
    const preview = previewImages.find((p) => p.original_path === previewKey);

    if (isGeneratingPreview) {
      return (
        <div className="flex items-center justify-center h-full text-gray-500">
          미리보기 생성 중...
        </div>
      );
    }

    if (preview) {
      return (
        <div className="h-full flex flex-col items-center justify-center p-4">
          <img
            src={preview.data_url}
            alt={preview.filename}
            className="max-w-full max-h-full object-contain shadow-lg border"
            style={{ maxHeight: "calc(100vh - 200px)" }}
          />
          <div className="mt-2 text-xs text-gray-500">
            {preview.width} x {preview.height}px
          </div>
        </div>
      );
    }
  }

  return (
    <div className="flex items-center justify-center h-full text-gray-400">
      프레임을 선택하면 미리보기가 표시됩니다
    </div>
  );
}
