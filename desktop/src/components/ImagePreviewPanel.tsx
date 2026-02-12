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
    if (textFrameDetail) {
      return (
        <div className="h-full overflow-auto p-4">
          <h3 className="font-medium text-sm mb-2">
            Text Frame: {textFrameDetail.frame_id}
          </h3>
          <div className="space-y-2">
            {textFrameDetail.paragraphs.map((para, i) => (
              <div key={i} className="border rounded p-2 text-sm">
                <div className="text-xs text-gray-500 mb-1">
                  {para.style_name}
                </div>
                <div>{para.text}</div>
              </div>
            ))}
          </div>
        </div>
      );
    }
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
