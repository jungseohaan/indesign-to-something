import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { open, save } from "@tauri-apps/plugin-dialog";
import type {
  IDMLStructure,
  MasterSpreadInfo,
  ImagePreview,
  ExtractionResult,
  ExtractedItem,
  PageInfo,
} from "../types";

interface SelectedPage {
  spreadId: string;
  page: PageInfo;
}

interface ExtractState {
  // File
  idmlPath: string | null;
  jarPath: string | null;

  // Analysis
  idmlStructure: IDMLStructure | null;
  isAnalyzing: boolean;

  // Spread Selection
  selectedSpreads: string[]; // spread filenames e.g. "Spread_u243bf.xml"

  // Page Preview
  selectedPage: SelectedPage | null;
  pagePreview: ImagePreview | null;
  isGeneratingPreview: boolean;

  // Extraction
  extractionResult: ExtractionResult | null;
  isExtracting: boolean;

  // UI
  selectedItemIndex: number | null;
  error: string | null;

  // Actions
  initJarPath: () => Promise<void>;
  openFile: () => Promise<void>;
  toggleSpread: (spreadFile: string) => void;
  selectAllSpreads: () => void;
  deselectAllSpreads: () => void;
  selectPage: (spreadId: string, page: PageInfo) => Promise<void>;
  extract: () => Promise<void>;
  exportJson: () => Promise<void>;
  selectItem: (index: number | null) => void;
  clearError: () => void;
}

export const useExtractStore = create<ExtractState>((set, get) => ({
  idmlPath: null,
  jarPath: null,
  idmlStructure: null,
  isAnalyzing: false,
  selectedSpreads: [],
  selectedPage: null,
  pagePreview: null,
  isGeneratingPreview: false,
  extractionResult: null,
  isExtracting: false,
  selectedItemIndex: null,
  error: null,

  initJarPath: async () => {
    try {
      const path = await invoke<string>("get_jar_path");
      set({ jarPath: path });
    } catch (e) {
      set({ error: `JAR 파일을 찾을 수 없습니다: ${e}` });
    }
  },

  openFile: async () => {
    try {
      const selected = await open({
        multiple: false,
        filters: [{ name: "IDML Files", extensions: ["idml"] }],
      });
      if (!selected) return;

      const idmlPath = selected as string;
      set({
        idmlPath,
        idmlStructure: null,
        selectedSpreads: [],
        selectedPage: null,
        pagePreview: null,
        extractionResult: null,
        selectedItemIndex: null,
        error: null,
        isAnalyzing: true,
      });

      const { jarPath } = get();
      if (!jarPath) {
        set({ error: "JAR 파일을 찾을 수 없습니다.", isAnalyzing: false });
        return;
      }

      const structure = await invoke<IDMLStructure>("analyze_idml", {
        path: idmlPath,
        jarPath,
      });

      set({
        idmlStructure: structure,
        isAnalyzing: false,
      });
    } catch (e) {
      set({ error: `분석 실패: ${e}`, isAnalyzing: false });
    }
  },

  toggleSpread: (spreadFile: string) => {
    const { selectedSpreads } = get();
    if (selectedSpreads.includes(spreadFile)) {
      set({ selectedSpreads: selectedSpreads.filter((s) => s !== spreadFile) });
    } else {
      set({ selectedSpreads: [...selectedSpreads, spreadFile] });
    }
  },

  selectAllSpreads: () => {
    const { idmlStructure } = get();
    if (!idmlStructure) return;
    const allSpreadFiles = idmlStructure.spreads.map(
      (s) => `Spread_${s.id}.xml`
    );
    set({ selectedSpreads: allSpreadFiles });
  },

  deselectAllSpreads: () => {
    set({ selectedSpreads: [] });
  },

  selectPage: async (spreadId: string, page: PageInfo) => {
    set({
      selectedPage: { spreadId, page },
      pagePreview: null,
      isGeneratingPreview: true,
    });

    const { idmlPath, jarPath, idmlStructure } = get();
    if (!idmlPath || !jarPath) {
      set({ isGeneratingPreview: false });
      return;
    }

    // Find the master spread ID for this page
    const masterId = page.master_spread;
    if (!masterId) {
      set({ isGeneratingPreview: false });
      return;
    }

    try {
      const preview = await invoke<ImagePreview>("generate_master_preview", {
        idmlPath,
        masterId,
        jarPath,
      });
      set({ pagePreview: preview, isGeneratingPreview: false });
    } catch (e) {
      console.error("마스터 프리뷰 생성 실패:", e);
      set({ isGeneratingPreview: false });
    }
  },

  extract: async () => {
    const { idmlPath, selectedSpreads } = get();
    if (!idmlPath || selectedSpreads.length === 0) return;

    set({
      isExtracting: true,
      extractionResult: null,
      error: null,
      selectedItemIndex: null,
    });

    try {
      const result = await invoke<ExtractionResult>("extract_questions", {
        idmlPath,
        spreads: selectedSpreads,
      });
      set({ extractionResult: result });
    } catch (e) {
      set({ error: `추출 실패: ${e}` });
    } finally {
      set({ isExtracting: false });
    }
  },

  exportJson: async () => {
    const { extractionResult, idmlPath } = get();
    if (!extractionResult) return;

    const idmlFilename = idmlPath
      ? idmlPath
          .substring(idmlPath.lastIndexOf("/") + 1)
          .replace(".idml", "")
      : "extraction";
    const idmlDir = idmlPath
      ? idmlPath.substring(0, idmlPath.lastIndexOf("/"))
      : undefined;

    const outputPath = await save({
      filters: [{ name: "JSON Files", extensions: ["json"] }],
      defaultPath: idmlDir
        ? `${idmlDir}/${idmlFilename}_items.json`
        : undefined,
    });

    if (!outputPath) return;

    try {
      const jsonStr = JSON.stringify(extractionResult, null, 2);
      await invoke("write_text_file", {
        path: outputPath,
        content: jsonStr,
      });
    } catch (e) {
      set({ error: `JSON 저장 실패: ${e}` });
    }
  },

  selectItem: (index: number | null) => {
    set({ selectedItemIndex: index });
  },

  clearError: () => set({ error: null }),
}));
