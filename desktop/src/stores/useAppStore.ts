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
  TextFrameDetail,
  MasterSpreadInfo,
} from "../types";
import { useAstStore } from "./useAstStore";

interface AppState {
  // JAR
  jarPath: string | null;

  // File
  idmlPath: string | null;
  isAnalyzing: boolean;
  structure: IDMLStructure | null;

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
  spreadBased: boolean;
  vectorDpi: 96 | 150;

  // Actions
  initJarPath: () => Promise<void>;
  selectFile: () => Promise<void>;
  selectHwpxFile: () => Promise<void>;
  selectSpread: (spread: SpreadInfo) => void;
  selectPage: (page: PageInfo) => void;
  selectFrame: (frame: FrameInfo) => void;
  selectMaster: (master: MasterSpreadInfo) => void;
  clearSelection: () => void;
  startConversion: () => Promise<void>;
  setSpreadBased: (v: boolean) => void;
  setVectorDpi: (v: 96 | 150) => void;
  clearError: () => void;
}

export const useAppStore = create<AppState>((set, get) => ({
  jarPath: null,
  idmlPath: null,
  isAnalyzing: false,
  structure: null,
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
  spreadBased: false,
  vectorDpi: 150,

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
    } catch (e: any) {
      set({ isAnalyzing: false, error: String(e) });
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
    const { idmlPath, jarPath, spreadBased, vectorDpi } = get();
    if (!idmlPath) return;

    const outputPath = await save({
      filters: [{ name: "HWPX", extensions: ["hwpx"] }],
    });
    if (!outputPath) return;

    set({ isConverting: true, progress: null, result: null, error: null });

    const unlisten = await listen<ProgressEvent>(
      "conversion-progress",
      (event) => {
        set({ progress: event.payload });
      }
    );

    try {
      const result = await invoke<ConvertResult>("convert_idml", {
        inputPath: idmlPath,
        outputPath,
        options: {
          spread_based: spreadBased,
          vector_dpi: vectorDpi,
          include_images: true,
          links_directory: null,
          start_page: null,
          end_page: null,
        },
        jarPath,
      });
      set({ result, isConverting: false });
    } catch (e: any) {
      set({ error: String(e), isConverting: false });
    } finally {
      unlisten();
    }
  },

  setSpreadBased: (v) => set({ spreadBased: v }),
  setVectorDpi: (v) => set({ vectorDpi: v }),
  clearError: () => set({ error: null }),
}));
