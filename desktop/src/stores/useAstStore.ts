import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";

interface AstStore {
  astDoc: any | null;
  isLoading: boolean;
  error: string | null;
  selectedPath: string | null;
  expandedPaths: Set<string>;
  currentSectionIndex: number;

  loadAST: (idmlPath: string, jarPath: string) => Promise<void>;
  selectPath: (path: string | null) => void;
  toggleExpand: (path: string) => void;
  setSection: (index: number) => void;
  reset: () => void;
}

export const useAstStore = create<AstStore>((set, get) => ({
  astDoc: null,
  isLoading: false,
  error: null,
  selectedPath: null,
  expandedPaths: new Set<string>(),
  currentSectionIndex: 0,

  loadAST: async (idmlPath: string, jarPath: string) => {
    set({ isLoading: true, error: null });
    try {
      const result = await invoke<any>("export_ast", {
        idmlPath,
        jarPath,
      });
      set({
        astDoc: result,
        isLoading: false,
        selectedPath: null,
        expandedPaths: new Set(["sections"]),
        currentSectionIndex: 0,
      });
    } catch (e: any) {
      set({ isLoading: false, error: String(e) });
    }
  },

  selectPath: (path) => set({ selectedPath: path }),

  toggleExpand: (path) => {
    const expanded = new Set(get().expandedPaths);
    if (expanded.has(path)) {
      expanded.delete(path);
    } else {
      expanded.add(path);
    }
    set({ expandedPaths: expanded });
  },

  setSection: (index) => set({ currentSectionIndex: index }),

  reset: () =>
    set({
      astDoc: null,
      error: null,
      selectedPath: null,
      expandedPaths: new Set(),
      currentSectionIndex: 0,
    }),
}));
