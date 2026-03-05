import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { FileSelector } from "./components/FileSelector";
import { ASTTreePanel } from "./components/ASTTreePanel";
import { ASTDetailPanel } from "./components/ASTDetailPanel";
import { PdfPreviewPanel } from "./components/PdfPreviewPanel";
import { ConversionPanel } from "./components/ConversionPanel";
import { FontMappingModal } from "./components/FontMappingModal";
import { InddBatchModal } from "./components/InddBatchModal";
import { PlaygroundPage } from "./components/PlaygroundPage";
import { ExtractPage } from "./components/ExtractPage";
import { useAppStore } from "./stores/useAppStore";

type Tab = "playground" | "extract" | "converter";
type RightPanel = "ast" | "pdf";

function App() {
  const initJarPath = useAppStore((state) => state.initJarPath);
  const selectInddFile = useAppStore((state) => state.selectInddFile);
  const selectInddFolder = useAppStore((state) => state.selectInddFolder);
  const selectHwpxFile = useAppStore((state) => state.selectHwpxFile);
  const previewPdfPath = useAppStore((state) => state.previewPdfPath);
  const [showAbout, setShowAbout] = useState(false);
  const [currentTab, setCurrentTab] = useState<Tab>("converter");
  const [rightPanel, setRightPanel] = useState<RightPanel>("ast");

  useEffect(() => {
    initJarPath();

    const unlistenOpenIndd = listen("menu-open-indd", () => {
      setCurrentTab("converter");
      selectInddFile();
    });

    const unlistenOpenInddFolder = listen("menu-open-indd-folder", () => {
      setCurrentTab("converter");
      selectInddFolder();
    });

    const unlistenOpenHwpx = listen("menu-open-hwpx", () => {
      setCurrentTab("converter");
      selectHwpxFile();
    });

    const unlistenAbout = listen("menu-about", () => {
      setShowAbout(true);
    });

    const unlistenPlayground = listen("menu-playground", () => {
      setCurrentTab("playground");
    });

    const unlistenExtract = listen("menu-extract", () => {
      setCurrentTab("extract");
    });

    return () => {
      unlistenOpenIndd.then((f) => f());
      unlistenOpenInddFolder.then((f) => f());
      unlistenOpenHwpx.then((f) => f());
      unlistenAbout.then((f) => f());
      unlistenPlayground.then((f) => f());
      unlistenExtract.then((f) => f());
    };
  }, [initJarPath, selectInddFile, selectInddFolder, selectHwpxFile]);

  const tabs: { key: Tab; label: string }[] = [
    { key: "playground", label: "Playground - 자동조판기" },
    { key: "extract", label: "문제 추출하기" },
    { key: "converter", label: "HWPX 내보내기" },
  ];

  return (
    <div className="h-screen flex flex-col bg-white">
      {/* Tab Bar */}
      <div className="flex items-center border-b bg-gray-50 px-4 shrink-0">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setCurrentTab(tab.key)}
            className={`px-5 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              currentTab === tab.key
                ? "border-blue-500 text-blue-600"
                : "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab Content */}
      {currentTab === "playground" ? (
        <PlaygroundPage />
      ) : currentTab === "extract" ? (
        <ExtractPage />
      ) : (
        <div className="flex-1 flex flex-col min-h-0">
          <FileSelector />

          <div className="flex-1 flex min-h-0">
            <div className="w-1/2 border-r overflow-hidden">
              <ASTTreePanel />
            </div>
            <div className="w-1/2 overflow-hidden flex flex-col">
              {previewPdfPath && (
                <div className="flex border-b bg-gray-50 shrink-0">
                  <button
                    onClick={() => setRightPanel("ast")}
                    className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                      rightPanel === "ast"
                        ? "text-blue-600 border-b-2 border-blue-500"
                        : "text-gray-500 hover:text-gray-700"
                    }`}
                  >
                    AST 상세
                  </button>
                  <button
                    onClick={() => setRightPanel("pdf")}
                    className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                      rightPanel === "pdf"
                        ? "text-blue-600 border-b-2 border-blue-500"
                        : "text-gray-500 hover:text-gray-700"
                    }`}
                  >
                    원본 레이아웃
                  </button>
                </div>
              )}
              <div className="flex-1 min-h-0">
                {rightPanel === "pdf" && previewPdfPath ? (
                  <PdfPreviewPanel />
                ) : (
                  <ASTDetailPanel />
                )}
              </div>
            </div>
          </div>

          <ConversionPanel />
        </div>
      )}

      {/* Font Mapping Modal */}
      <FontMappingModal />

      {/* Batch Processing Modal */}
      <InddBatchModal />

      {/* About Dialog */}
      {showAbout && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl p-6 max-w-md">
            <h2 className="text-xl font-bold mb-4">IDML to HWPX Converter</h2>
            <p className="text-gray-600 mb-2">Version 0.1.0</p>
            <p className="text-gray-500 text-sm mb-4">
              Adobe InDesign IDML 파일을 한글 HWPX 파일로 변환합니다.
            </p>
            <div className="text-right">
              <button
                onClick={() => setShowAbout(false)}
                className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
              >
                닫기
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
