import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useAppStore } from "../stores/useAppStore";

export function PdfPreviewPanel() {
  const previewPdfPath = useAppStore((s) => s.previewPdfPath);
  const [pdfDataUrl, setPdfDataUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!previewPdfPath) {
      setPdfDataUrl(null);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    invoke<string>("read_file_base64", { path: previewPdfPath })
      .then((base64) => {
        if (!cancelled) {
          setPdfDataUrl(`data:application/pdf;base64,${base64}`);
          setLoading(false);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(String(e));
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [previewPdfPath]);

  if (!previewPdfPath) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-gray-400">
        INDD 파일을 열면 원본 레이아웃 미리보기가 표시됩니다.
      </div>
    );
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-gray-400">
        PDF 로딩 중...
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-red-400">
        PDF 로딩 실패: {error}
      </div>
    );
  }

  return (
    <div className="h-full">
      {pdfDataUrl && (
        <iframe
          src={pdfDataUrl}
          className="w-full h-full border-0"
          title="PDF Preview"
        />
      )}
    </div>
  );
}
