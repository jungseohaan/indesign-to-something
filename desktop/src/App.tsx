import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { FileSelector } from "./components/FileSelector";
import { InventoryView } from "./components/InventoryView";
import { ImagePreviewPanel } from "./components/ImagePreviewPanel";
import { LayoutDetailPanel } from "./components/LayoutDetailPanel";
import { ConversionPanel } from "./components/ConversionPanel";
import { PlaygroundPage } from "./components/PlaygroundPage";
import { useAppStore } from "./stores/useAppStore";

function App() {
  const initJarPath = useAppStore((state) => state.initJarPath);
  const selectFile = useAppStore((state) => state.selectFile);
  const selectHwpxFile = useAppStore((state) => state.selectHwpxFile);
  const selectedSpread = useAppStore((state) => state.selectedSpread);
  const selectedPage = useAppStore((state) => state.selectedPage);
  const [showAbout, setShowAbout] = useState(false);
  const [currentView, setCurrentView] = useState<"converter" | "playground">("converter");

  useEffect(() => {
    initJarPath();

    // 메뉴 이벤트 리스너
    const unlistenOpenIdml = listen("menu-open-idml", () => {
      setCurrentView("converter");
      selectFile();
    });

    const unlistenOpenHwpx = listen("menu-open-hwpx", () => {
      setCurrentView("converter");
      selectHwpxFile();
    });

    const unlistenAbout = listen("menu-about", () => {
      setShowAbout(true);
    });

    const unlistenPlayground = listen("menu-playground", () => {
      setCurrentView("playground");
    });

    return () => {
      unlistenOpenIdml.then((f) => f());
      unlistenOpenHwpx.then((f) => f());
      unlistenAbout.then((f) => f());
      unlistenPlayground.then((f) => f());
    };
  }, [initJarPath, selectFile, selectHwpxFile]);

  // 스프레드나 페이지가 선택되면 레이아웃 상세 정보를, 아니면 이미지 미리보기를 표시
  const showLayoutDetail = selectedSpread || selectedPage;

  return (
    <>
      {currentView === "converter" ? (
        <div className="h-screen flex flex-col bg-white">
          {/* Header - File Selection */}
          <FileSelector />

          {/* Main Content */}
          <div className="flex-1 flex min-h-0">
            {/* Left Panel - Inventory View */}
            <div className="w-1/2 border-r overflow-hidden">
              <InventoryView />
            </div>

            {/* Right Panel - Layout Detail or Image Preview */}
            <div className="w-1/2 overflow-hidden">
              {showLayoutDetail ? <LayoutDetailPanel /> : <ImagePreviewPanel />}
            </div>
          </div>

          {/* Footer - Conversion Panel */}
          <ConversionPanel />
        </div>
      ) : (
        <PlaygroundPage onBack={() => setCurrentView("converter")} />
      )}

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
    </>
  );
}

export default App;
