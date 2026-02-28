import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { open, save } from "@tauri-apps/plugin-dialog";
import type {
  IDMLStructure,
  SpreadInfo,
  PageInfo,
  FrameInfo,
  ImagePreview,
  ConvertResult,
  ProgressEvent,
  LogEvent,
  TextFrameDetail,
  MasterSpreadInfo,
  InddExtractResult,
  InddExtractionProgress,
} from "../types";
import { useAstStore } from "./useAstStore";

interface AppState {
  // JAR
  jarPath: string | null;

  // File
  idmlPath: string | null;
  isAnalyzing: boolean;
  structure: IDMLStructure | null;

  // INDD Extraction
  sourceType: "idml" | "indd" | null;
  inddPath: string | null;
  resolvedJsonPath: string | null;
  isExtracting: boolean;
  extractionPhase: string | null;

  // Selection
  selectedSpread: SpreadInfo | null;
  selectedPage: PageInfo | null;
  selectedImage: FrameInfo | null;
  selectedTextFrame: FrameInfo | null;
  selectedMaster: MasterSpreadInfo | null;

  // Preview
  previewImages: ImagePreview[];
  isGeneratingPreview: boolean;
  textFrameDetail: TextFrameDetail | null;
  isLoadingTextDetail: boolean;
  masterPreview: ImagePreview | null;
  isGeneratingMasterPreview: boolean;

  // Conversion
  isConverting: boolean;
  progress: ProgressEvent | null;
  result: ConvertResult | null;
  error: string | null;
  conversionLogs: LogEvent[];
  spreadBased: boolean;
  vectorDpi: 96 | 150;
  layoutMode: "preserve" | "editable";

  // Actions
  initJarPath: () => Promise<void>;
  selectFile: () => Promise<void>;
  selectInddFile: () => Promise<void>;
  selectHwpxFile: () => Promise<void>;
  selectSpread: (spread: SpreadInfo) => void;
  selectPage: (page: PageInfo) => void;
  selectFrame: (frame: FrameInfo) => void;
  selectMaster: (master: MasterSpreadInfo) => void;
  clearSelection: () => void;
  startConversion: () => Promise<void>;
  setSpreadBased: (v: boolean) => void;
  setVectorDpi: (v: 96 | 150) => void;
  setLayoutMode: (v: "preserve" | "editable") => void;
  clearError: () => void;
}

export const useAppStore = create<AppState>((set, get) => ({
  jarPath: null,
  idmlPath: null,
  isAnalyzing: false,
  structure: null,
  sourceType: null,
  inddPath: null,
  resolvedJsonPath: null,
  isExtracting: false,
  extractionPhase: null,
  selectedSpread: null,
  selectedPage: null,
  selectedImage: null,
  selectedTextFrame: null,
  selectedMaster: null,
  previewImages: [],
  isGeneratingPreview: false,
  textFrameDetail: null,
  isLoadingTextDetail: false,
  masterPreview: null,
  isGeneratingMasterPreview: false,
  isConverting: false,
  progress: null,
  result: null,
  error: null,
  conversionLogs: [],
  spreadBased: false,
  vectorDpi: 150,
  layoutMode: "preserve",

  initJarPath: async () => {
    try {
      const jarPath = await invoke<string>("get_jar_path");
      set({ jarPath });
    } catch (e) {
      console.error("Failed to get JAR path:", e);
    }
  },

  selectFile: async () => {
    const path = await open({
      filters: [{ name: "IDML", extensions: ["idml"] }],
    });
    if (!path) return;
    set({
      idmlPath: path,
      sourceType: "idml",
      inddPath: null,
      resolvedJsonPath: null,
      isExtracting: false,
      extractionPhase: null,
      isAnalyzing: true,
      structure: null,
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
      previewImages: [],
      textFrameDetail: null,
      masterPreview: null,
      result: null,
      error: null,
    });

    try {
      const structure = await invoke<IDMLStructure>("analyze_idml", {
        path,
        jarPath: get().jarPath,
      });
      set({ structure, isAnalyzing: false });

      // AST 자동 로드
      const jarPath = get().jarPath;
      if (jarPath) {
        useAstStore.getState().loadAST(path, jarPath);
      }
      // IDML 직접 열기: resolved 사이드카 없음
      useAstStore.getState().clearResolved();
    } catch (e: any) {
      set({ isAnalyzing: false, error: String(e) });
    }
  },

  selectInddFile: async () => {
    const path = await open({
      filters: [{ name: "InDesign", extensions: ["indd"] }],
    });
    if (!path) return;

    set({
      inddPath: path,
      sourceType: "indd",
      idmlPath: null,
      resolvedJsonPath: null,
      isExtracting: true,
      extractionPhase: "launching",
      isAnalyzing: false,
      structure: null,
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
      previewImages: [],
      textFrameDetail: null,
      masterPreview: null,
      result: null,
      error: null,
    });

    // 추출 진행률 이벤트 리스너
    const unlisten = await listen<InddExtractionProgress>(
      "indd-extraction-progress",
      (event) => {
        set({ extractionPhase: event.payload.phase });
      }
    );

    try {
      // Step 1: InDesign으로 IDML 추출
      const result = await invoke<InddExtractResult>("extract_indd", {
        inddPath: path,
        jarPath: get().jarPath,
      });

      // Step 2: 추출된 IDML로 기존 분석 파이프라인 실행
      set({
        idmlPath: result.idml_path,
        resolvedJsonPath: result.resolved_json_path ?? null,
        isExtracting: false,
        isAnalyzing: true,
      });

      const structure = await invoke<IDMLStructure>("analyze_idml", {
        path: result.idml_path,
        jarPath: get().jarPath,
      });
      set({ structure, isAnalyzing: false });

      // Step 3: AST 자동 로드
      const jarPath = get().jarPath;
      if (jarPath) {
        useAstStore.getState().loadAST(result.idml_path, jarPath);
      }

      // Step 4: resolved.json 로드 (있으면 — AST 사이드카로 활용)
      if (result.resolved_json_path) {
        useAstStore.getState().loadResolved(result.resolved_json_path);
      }
    } catch (e: any) {
      set({
        isExtracting: false,
        isAnalyzing: false,
        error: String(e),
      });
    } finally {
      unlisten();
    }
  },

  selectHwpxFile: async () => {
    const file = await open({
      filters: [{ name: "HWPX", extensions: ["hwpx"] }],
    });
    if (!file) return;
    // HWPX file handling placeholder
  },

  selectSpread: (spread) => {
    set({
      selectedSpread: spread,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
    });
  },

  selectPage: (page) => {
    set({
      selectedPage: page,
      selectedSpread: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
    });
  },

  selectFrame: (frame) => {
    if (frame.type === "text") {
      set({
        selectedTextFrame: frame,
        selectedImage: null,
        selectedSpread: null,
        selectedPage: null,
        selectedMaster: null,
        isLoadingTextDetail: true,
        textFrameDetail: null,
      });
      // Load text frame detail
      invoke<TextFrameDetail>("get_text_frame_detail", {
        idmlPath: get().idmlPath,
        frameId: frame.id,
        jarPath: get().jarPath,
      })
        .then((detail) => set({ textFrameDetail: detail, isLoadingTextDetail: false }))
        .catch(() => set({ isLoadingTextDetail: false }));
    } else if (frame.type === "image" || frame.type === "vector") {
      set({
        selectedImage: frame,
        selectedTextFrame: null,
        selectedSpread: null,
        selectedPage: null,
        selectedMaster: null,
        isGeneratingPreview: true,
      });
      const cmd =
        frame.type === "image"
          ? "generate_image_preview"
          : "generate_vector_preview";
      invoke<ImagePreview>(cmd, {
        idmlPath: get().idmlPath,
        frameId: frame.id,
        jarPath: get().jarPath,
      })
        .then((preview) => {
          preview.original_path = `${frame.type}:${frame.id}`;
          set((s) => ({
            previewImages: [...s.previewImages, preview],
            isGeneratingPreview: false,
          }));
        })
        .catch(() => set({ isGeneratingPreview: false }));
    } else if (frame.type === "group") {
      // Group 선택: selectedImage에 group frame 저장 (preview panel에서 처리)
      set({
        selectedImage: frame,
        selectedTextFrame: null,
        selectedSpread: null,
        selectedPage: null,
        selectedMaster: null,
        isGeneratingPreview: false,
      });
    }
  },

  selectMaster: (master) => {
    set({
      selectedMaster: master,
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      isGeneratingMasterPreview: true,
      masterPreview: null,
    });
    invoke<ImagePreview>("generate_master_preview", {
      idmlPath: get().idmlPath,
      masterId: master.id,
      jarPath: get().jarPath,
    })
      .then((preview) =>
        set({ masterPreview: preview, isGeneratingMasterPreview: false })
      )
      .catch(() => set({ isGeneratingMasterPreview: false }));
  },

  clearSelection: () => {
    set({
      selectedSpread: null,
      selectedPage: null,
      selectedImage: null,
      selectedTextFrame: null,
      selectedMaster: null,
      textFrameDetail: null,
      masterPreview: null,
    });
  },

  startConversion: async () => {
    const { idmlPath, jarPath, spreadBased, vectorDpi, layoutMode, inddPath, resolvedJsonPath } = get();
    if (!idmlPath) return;

    const outputPath = await save({
      filters: [{ name: "HWPX", extensions: ["hwpx"] }],
    });
    if (!outputPath) return;

    set({ isConverting: true, progress: null, result: null, error: null, conversionLogs: [] });

    const unlisten = await listen<ProgressEvent>(
      "conversion-progress",
      (event) => {
        set({ progress: event.payload });
      }
    );

    const unlistenLog = await listen<LogEvent>(
      "conversion-log",
      (event) => {
        set((state) => ({ conversionLogs: [...state.conversionLogs, event.payload] }));
      }
    );

    // INDD 원본 경로가 있으면 해당 디렉토리의 Links/ 폴더를 links_directory로 지정
    let linksDir: string | null = null;
    if (inddPath) {
      const inddDir = inddPath.replace(/[/\\][^/\\]+$/, "");
      linksDir = inddDir + "/Links";
    }

    try {
      const result = await invoke<ConvertResult>("convert_idml", {
        inputPath: idmlPath,
        outputPath,
        options: {
          spread_based: spreadBased,
          vector_dpi: vectorDpi,
          include_images: true,
          links_directory: linksDir,
          resolved_json_path: resolvedJsonPath,
          start_page: null,
          end_page: null,
          layout_mode: layoutMode,
        },
        jarPath,
      });
      set({ result, isConverting: false });
    } catch (e: any) {
      set({ error: String(e), isConverting: false });
    } finally {
      unlisten();
      unlistenLog();
    }
  },

  setSpreadBased: (v) => set({ spreadBased: v }),
  setVectorDpi: (v) => set({ vectorDpi: v }),
  setLayoutMode: (v) => set({ layoutMode: v }),
  clearError: () => set({ error: null }),
}));
