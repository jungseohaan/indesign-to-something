import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useAppStore } from "../stores/useAppStore";
import { useAstStore } from "../stores/useAstStore";
import type { ImagePreview, SemanticBlock } from "../types";

export function PdfPreviewPanel() {
  const previewPdfPath = useAppStore((s) => s.previewPdfPath);
  const perfMode = useAppStore((s) => s.perfMode);
  const jarPath = useAppStore((s) => s.jarPath);
  const astDoc = useAstStore((s) => s.astDoc);
  const semanticBlocksDoc = useAstStore((s) => s.semanticBlocksDoc);
  const selectedSemanticBlockId = useAstStore((s) => s.selectedSemanticBlockId);
  const selectedBlock = semanticBlocksDoc?.blocks.find((block) => block.id === selectedSemanticBlockId) ?? null;
  const pageRef = resolvePreviewPage(astDoc, selectedBlock?.page_start ?? 1);
  const pageSize = getPageSize(pageRef.section);
  const highlights = getHighlightBoxes(selectedBlock, pageSize, pageRef.sourcePageNumber);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [preview, setPreview] = useState<ImagePreview | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!previewPdfPath || !jarPath) {
      setPreview(null);
      setError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    invoke<ImagePreview>("generate_pdf_page_preview", {
      pdfPath: previewPdfPath,
      pageNumber: pageRef.pdfPageNumber,
      jarPath,
    })
      .then((result) => {
        if (!cancelled) {
          setPreview(result);
          setLoading(false);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setPreview(null);
          setError(String(e));
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [jarPath, pageRef.pdfPageNumber, previewPdfPath]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !preview) return;

    const image = new Image();
    image.onload = () => {
      canvas.width = image.naturalWidth;
      canvas.height = image.naturalHeight;
      const context = canvas.getContext("2d");
      if (!context) return;
      context.clearRect(0, 0, canvas.width, canvas.height);
      context.drawImage(image, 0, 0);
    };
    image.src = preview.data_url;
  }, [preview]);

  if (!previewPdfPath || !jarPath) {
    const message =
      perfMode === "fast"
        ? "빠름 모드에서도 PDF 프리뷰를 생성합니다. 현재 파일은 아직 다시 추출되지 않았거나 예전 캐시에 PDF가 없습니다."
        : "INDD 파일을 열거나 다시 추출하면 원본 레이아웃 미리보기가 표시됩니다.";

    return (
      <div className="flex h-full items-center justify-center px-8 text-center text-sm text-gray-400">
        {message}
      </div>
    );
  }

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center px-8 text-center text-sm text-gray-400">
        PDF 프리뷰 렌더링 중...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-full items-center justify-center px-8 text-center text-sm text-red-500">
        PDF 프리뷰 생성 실패: {error}
      </div>
    );
  }

  return (
    <div className="h-full overflow-auto bg-gray-100 p-4">
      <div className="mx-auto w-fit">
        <div className="mb-2 text-center text-[11px] text-gray-500">
          PDF page {pageRef.pdfPageNumber}
          {pageRef.sourcePageNumber !== pageRef.pdfPageNumber ? ` · source p.${pageRef.sourcePageNumber}` : ""}
          {selectedBlock ? ` · ${selectedBlock.display_name || selectedBlock.id}` : ""}
        </div>
        <div className="relative shadow-sm">
          <canvas
            ref={canvasRef}
            className="block h-auto max-w-full bg-white"
            title={preview?.filename || "PDF Preview"}
          />
          {highlights.map((highlight) => (
            <div
              key={highlight.id}
              className="pointer-events-none absolute border-2"
              title={`${highlight.role}: ${highlight.id}`}
              style={{
                left: `${highlight.left}%`,
                top: `${highlight.top}%`,
                width: `${highlight.width}%`,
                height: `${highlight.height}%`,
                borderColor: highlight.color,
                backgroundColor: highlight.fill,
              }}
            />
          ))}
        </div>
      </div>
    </div>
  );
}

function resolvePreviewPage(
  astDoc: any,
  sourcePageNumber: number
): { sourcePageNumber: number; pdfPageNumber: number; section: any | null } {
  const sections = Array.isArray(astDoc?.sections) ? astDoc.sections : [];
  const source = Math.max(1, Math.floor(sourcePageNumber || 1));

  const matchedIndex = sections.findIndex((section: any, index: number) => {
    const candidates = [
      section?.pageNumber,
      section?.page_number,
      section?.pageNo,
      section?.page_no,
      section?.number,
      section?.name,
    ];
    return candidates.some((value) => {
      if (typeof value === "number") return value === source;
      if (typeof value === "string") return parseInt(value, 10) === source;
      return false;
    }) || index + 1 === source;
  });

  if (matchedIndex >= 0) {
    return {
      sourcePageNumber: source,
      pdfPageNumber: matchedIndex + 1,
      section: sections[matchedIndex] ?? null,
    };
  }

  const fallbackIndex = sections.length > 0
    ? Math.max(0, Math.min(source - 1, sections.length - 1))
    : 0;
  return {
    sourcePageNumber: source,
    pdfPageNumber: fallbackIndex + 1,
    section: sections[fallbackIndex] ?? null,
  };
}

function getPageSize(section: any): { width: number; height: number } | null {
  const layout = section?.layout ?? section?.pageLayout ?? section;
  const width =
    numeric(layout?.pageWidth) ??
    numeric(layout?.page_width) ??
    numeric(section?.pageWidth) ??
    numeric(section?.page_width);
  const height =
    numeric(layout?.pageHeight) ??
    numeric(layout?.page_height) ??
    numeric(section?.pageHeight) ??
    numeric(section?.page_height);
  if (!width || !height || width <= 0 || height <= 0) return null;
  return { width, height };
}

function getHighlightBoxes(
  block: SemanticBlock | null,
  pageSize: { width: number; height: number } | null,
  sourcePageNumber: number
): Array<{
  id: string;
  role: string;
  left: number;
  top: number;
  width: number;
  height: number;
  color: string;
  fill: string;
}> {
  if (!block || !pageSize) return [];
  const memberBoxes = block.member_boxes ?? [];
  const pageBoxes = memberBoxes.filter((box) => {
    return box?.bbox && (box.page === sourcePageNumber || box.page === block.page_start);
  });

  if (pageBoxes.length > 0) {
    return pageBoxes
      .map((box) => toHighlightBox(box.id, box.role, box.bbox, pageSize))
      .filter((box): box is NonNullable<typeof box> => box !== null);
  }

  const fallback = toHighlightBox(block.id, "block", block.bbox, pageSize);
  return fallback ? [fallback] : [];
}

function toHighlightBox(
  id: string,
  role: string,
  bbox: number[] | null | undefined,
  pageSize: { width: number; height: number }
) {
  if (!bbox || bbox.length < 4) return null;
  const left = clampPercent((bbox[0] / pageSize.width) * 100);
  const top = clampPercent((bbox[1] / pageSize.height) * 100);
  const right = clampPercent((bbox[2] / pageSize.width) * 100);
  const bottom = clampPercent((bbox[3] / pageSize.height) * 100);
  const palette = rolePalette(role);
  return {
    id,
    role,
    left,
    top,
    width: Math.max(0.5, right - left),
    height: Math.max(0.5, bottom - top),
    ...palette,
  };
}

function rolePalette(role: string): { color: string; fill: string } {
  switch (role) {
    case "text_frame":
      return { color: "#f59e0b", fill: "rgba(245, 158, 11, 0.18)" };
    case "table":
      return { color: "#8b5cf6", fill: "rgba(139, 92, 246, 0.16)" };
    case "visual":
      return { color: "#06b6d4", fill: "rgba(6, 182, 212, 0.14)" };
    case "decoration":
      return { color: "#22c55e", fill: "rgba(34, 197, 94, 0.12)" };
    case "inline_object":
      return { color: "#f97316", fill: "rgba(249, 115, 22, 0.16)" };
    default:
      return { color: "#f59e0b", fill: "rgba(245, 158, 11, 0.16)" };
  }
}

function numeric(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function clampPercent(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, value));
}
