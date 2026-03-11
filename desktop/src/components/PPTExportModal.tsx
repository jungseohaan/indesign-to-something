import { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { save } from "@tauri-apps/plugin-dialog";
import { useSemanticStore } from "../stores/useSemanticStore";
import {
  breakIntoSlides,
  clusterStyles,
  layoutSlides,
  renderPptx,
} from "@its/semantic-layer";

interface Props {
  onClose: () => void;
}

export function PPTExportModal({ onClose }: Props) {
  const nodes = useSemanticStore((s) => s.nodes);
  const relations = useSemanticStore((s) => s.relations);
  const [isExporting, setIsExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [slideCount, setSlideCount] = useState<number | null>(null);
  const [author, setAuthor] = useState("");
  const [title, setTitle] = useState("시멘틱 레이어 내보내기");

  const labeledCount = nodes.filter((n) => n.label !== "UNKNOWN").length;

  const handlePreview = () => {
    const slides = breakIntoSlides(nodes, relations);
    setSlideCount(slides.length);
  };

  const handleExport = async () => {
    try {
      setIsExporting(true);
      setError(null);

      const slides = breakIntoSlides(nodes, relations);
      const clusters = clusterStyles(nodes);
      const layouted = layoutSlides(slides);

      const path = await save({
        filters: [{ name: "PowerPoint", extensions: ["pptx"] }],
        defaultPath: `${title || "export"}.pptx`,
      });
      if (!path) return;

      const buffer = await renderPptx(layouted, clusters, {
        author: author || undefined,
        title: title || undefined,
      });

      // base64 인코딩 후 Tauri 커맨드로 저장
      const bytes = new Uint8Array(buffer);
      let binary = "";
      for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      const base64Data = btoa(binary);
      await invoke("save_pptx", { path, base64Data });

      setSlideCount(layouted.length);
    } catch (e) {
      setError(String(e));
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-[480px]">
        <div className="flex items-center justify-between px-5 py-3 border-b">
          <h2 className="text-base font-bold">PPT 내보내기</h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-lg"
          >
            ×
          </button>
        </div>

        <div className="p-5 space-y-4">
          {/* 상태 */}
          <div className="bg-gray-50 rounded p-3 text-sm">
            <div className="flex justify-between">
              <span className="text-gray-500">전체 노드</span>
              <span className="font-medium">{nodes.length}개</span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-500">분류됨</span>
              <span className="font-medium">{labeledCount}개</span>
            </div>
            {slideCount != null && (
              <div className="flex justify-between">
                <span className="text-gray-500">슬라이드</span>
                <span className="font-medium text-blue-600">
                  {slideCount}장
                </span>
              </div>
            )}
          </div>

          {/* 옵션 */}
          <div className="space-y-2">
            <div>
              <label className="text-xs text-gray-500 block mb-1">제목</label>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className="w-full px-3 py-1.5 text-sm border rounded"
              />
            </div>
            <div>
              <label className="text-xs text-gray-500 block mb-1">
                작성자
              </label>
              <input
                type="text"
                value={author}
                onChange={(e) => setAuthor(e.target.value)}
                placeholder="선택사항"
                className="w-full px-3 py-1.5 text-sm border rounded"
              />
            </div>
          </div>

          {labeledCount === 0 && (
            <p className="text-sm text-orange-500">
              분류된 노드가 없습니다. 먼저 시멘틱 추출을 실행하세요.
            </p>
          )}

          {error && (
            <p className="text-sm text-red-500">{error}</p>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3 border-t">
          <button
            onClick={handlePreview}
            disabled={labeledCount === 0}
            className="px-3 py-1.5 text-sm text-blue-600 border border-blue-300 rounded hover:bg-blue-50 disabled:opacity-50"
          >
            미리보기
          </button>
          <button
            onClick={onClose}
            className="px-3 py-1.5 text-sm text-gray-600 border rounded hover:bg-gray-50"
          >
            취소
          </button>
          <button
            onClick={handleExport}
            disabled={labeledCount === 0 || isExporting}
            className="px-3 py-1.5 text-sm bg-blue-500 text-white rounded hover:bg-blue-600 disabled:opacity-50"
          >
            {isExporting ? "내보내는 중..." : ".pptx 내보내기"}
          </button>
        </div>
      </div>
    </div>
  );
}
