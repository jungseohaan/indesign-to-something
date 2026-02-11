import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { open, save } from "@tauri-apps/plugin-dialog";
import type {
  IDMLStructure,
  MasterSpreadInfo,
  ImagePreview,
  CreateIdmlResult,
} from "../types";

interface PlaygroundState {
  // File
  idmlPath: string | null;
  jarPath: string | null;
  masterSpreads: MasterSpreadInfo[];
  isLoading: boolean;

  // Selection & Preview
  selectedMaster: MasterSpreadInfo | null;
  masterPreview: ImagePreview | null;
  isGeneratingPreview: boolean;

  // Creation
  isCreating: boolean;
  createResult: CreateIdmlResult | null;

  // Status
  error: string | null;

  // Actions
  initJarPath: () => Promise<void>;
  openFile: () => Promise<void>;
  selectMaster: (master: MasterSpreadInfo) => Promise<void>;
  createIdml: () => Promise<void>;
  clearError: () => void;
  reset: () => void;
}

export const usePlaygroundStore = create<PlaygroundState>((set, get) => ({
  idmlPath: null,
  jarPath: null,
  masterSpreads: [],
  isLoading: false,
  selectedMaster: null,
  masterPreview: null,
  isGeneratingPreview: false,
  isCreating: false,
  createResult: null,
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
        masterSpreads: [],
        selectedMaster: null,
        masterPreview: null,
        createResult: null,
        error: null,
        isLoading: true,
      });

      const { jarPath } = get();
      if (!jarPath) {
        set({ error: "JAR 파일을 찾을 수 없습니다.", isLoading: false });
        return;
      }

      const structure = await invoke<IDMLStructure>("analyze_idml", {
        path: idmlPath,
        jarPath,
      });

      set({
        masterSpreads: structure.master_spreads || [],
        isLoading: false,
      });
    } catch (e) {
      set({ error: `분석 실패: ${e}`, isLoading: false });
    }
  },

  selectMaster: async (master: MasterSpreadInfo) => {
    set({
      selectedMaster: master,
      masterPreview: null,
      isGeneratingPreview: true,
    });

    const { idmlPath, jarPath } = get();
    if (!idmlPath || !jarPath) {
      set({ isGeneratingPreview: false });
      return;
    }

    try {
      const preview = await invoke<ImagePreview>("generate_master_preview", {
        idmlPath,
        masterId: master.id,
        jarPath,
      });
      set({ masterPreview: preview, isGeneratingPreview: false });
    } catch (e) {
      console.error("마스터 프리뷰 생성 실패:", e);
      set({ isGeneratingPreview: false });
    }
  },

  createIdml: async () => {
    const { idmlPath, jarPath } = get();
    if (!idmlPath || !jarPath) return;

    // 출력 파일명 생성
    const idmlFilename = idmlPath
      .substring(idmlPath.lastIndexOf("/") + 1)
      .replace(".idml", "");
    const idmlDir = idmlPath.substring(0, idmlPath.lastIndexOf("/"));
    const defaultOutputName = `${idmlFilename}-template.idml`;

    const outputPath = await save({
      filters: [{ name: "IDML Files", extensions: ["idml"] }],
      defaultPath: `${idmlDir}/${defaultOutputName}`,
    });

    if (!outputPath) return;

    set({ isCreating: true, createResult: null, error: null });

    try {
      const result = await invoke<CreateIdmlResult>("create_idml_from_masters", {
        sourcePath: idmlPath,
        outputPath,
        masterIds: null,
        validate: true,
        jarPath,
      });

      set({ createResult: result, isCreating: false });
    } catch (e) {
      set({ error: `IDML 생성 실패: ${e}`, isCreating: false });
    }
  },

  clearError: () => set({ error: null }),

  reset: () =>
    set({
      idmlPath: null,
      masterSpreads: [],
      selectedMaster: null,
      masterPreview: null,
      createResult: null,
      error: null,
      isLoading: false,
      isGeneratingPreview: false,
      isCreating: false,
    }),
}));
