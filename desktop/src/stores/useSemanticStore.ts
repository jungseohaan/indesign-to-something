import { create } from "zustand";
import { invoke } from "@tauri-apps/api/core";
import { save, open } from "@tauri-apps/plugin-dialog";
import type {
  SemanticNode,
  SemanticLayer,
  SemanticSchema,
  SemanticRelation,
  ClassificationRule,
  RelationRule,
  LabelDef,
} from "@its/semantic-layer";
import {
  ASTJsonAdapter,
  extractFeatures,
  classifyNodes,
  buildRelations,
  SchemaLoader,
  mergeLayer,
  suggestRules,
  validateRules,
  generateSchema,
} from "@its/semantic-layer";
import type { SuggestedRule, ValidationResult } from "@its/semantic-layer";

interface SemanticState {
  // ─── SLA 데이터 ──────────────────────────────
  layer: SemanticLayer | null;
  nodes: SemanticNode[];
  relations: SemanticRelation[];
  selectedNodeId: string | null;
  hoveredNodeId: string | null;

  // ─── 스키마 ─────────────────────────────────
  schemaLoader: SchemaLoader;
  activeSchemaId: string | null;
  activeSchema: SemanticSchema | null;
  labels: LabelDef[];

  // ─── 필터 & 검색 ──────────────────────────────
  searchQuery: string;
  filterLabel: string | null;
  filterPage: number | null;
  showUnknownOnly: boolean;

  // ─── 규칙 제안 ─────────────────────────────
  suggestedRules: SuggestedRule[];
  validationResult: ValidationResult | null;

  // ─── UI 상태 ────────────────────────────────
  showSchemaEditor: boolean;
  showRuleSuggester: boolean;
  showReextractReview: boolean;
  showPptExport: boolean;
  isProcessing: boolean;
  error: string | null;

  // ─── 액션 ──────────────────────────────────
  loadFromAst: (astJson: unknown) => void;
  reextract: (astJson: unknown) => void;
  selectNode: (nodeId: string | null) => void;
  hoverNode: (nodeId: string | null) => void;
  setLabel: (nodeId: string, label: string) => void;
  removeLabel: (nodeId: string) => void;
  setSearchQuery: (query: string) => void;
  setFilterLabel: (label: string | null) => void;
  setFilterPage: (page: number | null) => void;
  setShowUnknownOnly: (show: boolean) => void;
  loadSchema: (schemaJson: string | object) => void;
  setActiveSchema: (schemaId: string) => void;
  reclassify: () => void;
  generateSchemaFromAst: (astJson: unknown) => void;
  generateSuggestions: () => void;
  runValidation: () => void;
  exportLayerJson: () => string;
  importLayerJson: (json: string) => void;
  saveLayerToFile: () => Promise<void>;
  loadLayerFromFile: () => Promise<void>;
  setShowSchemaEditor: (show: boolean) => void;
  setShowRuleSuggester: (show: boolean) => void;
  setShowReextractReview: (show: boolean) => void;
  setShowPptExport: (show: boolean) => void;
  reset: () => void;

  // ─── 선택된 노드 ──────────────────────────────
  getSelectedNode: () => SemanticNode | null;
  getNodeRelations: (nodeId: string) => SemanticRelation[];
  getFilteredNodes: () => SemanticNode[];
  getPageNumbers: () => number[];
}

// 내장 스키마 로드
const defaultSchemaLoader = new SchemaLoader();

export const useSemanticStore = create<SemanticState>((set, get) => ({
  layer: null,
  nodes: [],
  relations: [],
  selectedNodeId: null,
  hoveredNodeId: null,
  schemaLoader: defaultSchemaLoader,
  activeSchemaId: null,
  activeSchema: null,
  labels: [],
  searchQuery: "",
  filterLabel: null,
  filterPage: null,
  showUnknownOnly: false,
  suggestedRules: [],
  validationResult: null,
  showSchemaEditor: false,
  showRuleSuggester: false,
  showReextractReview: false,
  showPptExport: false,
  isProcessing: false,
  error: null,

  loadFromAst: (astJson: unknown) => {
    try {
      set({ isProcessing: true, error: null });
      const adapter = new ASTJsonAdapter(astJson as string | object);
      const { activeSchema, schemaLoader, activeSchemaId } = get();
      const rules = activeSchema?.rules ?? [];
      const relationRules = activeSchema?.relationRules ?? [];

      const nodes = extractFeatures(adapter);
      const classified = classifyNodes(nodes, rules);
      const relations = buildRelations(classified, relationRules);

      const now = new Date().toISOString();
      const layer: SemanticLayer = {
        version: "1.0.0",
        schemaId: activeSchemaId ?? "",
        sourceAstHash: adapter.getDocumentHash(),
        createdAt: now,
        modifiedAt: now,
        mergeHistory: [],
        nodes: classified,
        relations,
        deletedNodes: [],
      };

      set({ layer, nodes: classified, relations, isProcessing: false });
    } catch (e) {
      set({ error: String(e), isProcessing: false });
    }
  },

  reextract: (astJson: unknown) => {
    try {
      set({ isProcessing: true, error: null });
      const adapter = new ASTJsonAdapter(astJson as string | object);
      const { layer, activeSchema } = get();
      const rules = activeSchema?.rules ?? [];
      const relationRules = activeSchema?.relationRules ?? [];

      const updated = mergeLayer(layer, adapter, {
        rules,
        relationRules,
        schemaId: activeSchema?.schemaId ?? "",
      });

      set({
        layer: updated,
        nodes: updated.nodes,
        relations: updated.relations,
        isProcessing: false,
        showReextractReview: true,
      });
    } catch (e) {
      set({ error: String(e), isProcessing: false });
    }
  },

  selectNode: (nodeId) => set({ selectedNodeId: nodeId }),
  hoverNode: (nodeId) => set({ hoveredNodeId: nodeId }),

  setLabel: (nodeId, label) => {
    const { nodes, activeSchema } = get();
    const updated = nodes.map((n) =>
      n.id === nodeId
        ? { ...n, label, manualOverride: true, confidence: 1.0 }
        : n
    );
    // 관계 재빌드
    const relations = buildRelations(
      updated,
      activeSchema?.relationRules ?? []
    );
    set({ nodes: updated, relations, layer: get().layer ? { ...get().layer!, nodes: updated, relations, modifiedAt: new Date().toISOString() } : null });
  },

  removeLabel: (nodeId) => {
    const { nodes, activeSchema } = get();
    const updated = nodes.map((n) =>
      n.id === nodeId
        ? { ...n, label: "UNKNOWN", manualOverride: false, confidence: 0 }
        : n
    );
    const relations = buildRelations(
      updated,
      activeSchema?.relationRules ?? []
    );
    set({ nodes: updated, relations, layer: get().layer ? { ...get().layer!, nodes: updated, relations, modifiedAt: new Date().toISOString() } : null });
  },

  setSearchQuery: (searchQuery) => set({ searchQuery }),
  setFilterLabel: (filterLabel) => set({ filterLabel }),
  setFilterPage: (filterPage) => set({ filterPage }),
  setShowUnknownOnly: (showUnknownOnly) => set({ showUnknownOnly }),

  loadSchema: (schemaJson) => {
    const { schemaLoader } = get();
    const schema = schemaLoader.loadFromJson(schemaJson);
    set({ activeSchemaId: schema.schemaId });
    const resolved = schemaLoader.get(schema.schemaId);
    if (resolved) {
      set({ activeSchema: resolved, labels: resolved.labels });
    }
  },

  setActiveSchema: (schemaId) => {
    const { schemaLoader } = get();
    const schema = schemaLoader.get(schemaId);
    if (schema) {
      set({
        activeSchemaId: schemaId,
        activeSchema: schema,
        labels: schema.labels,
      });
    }
  },

  reclassify: () => {
    const { nodes, activeSchema } = get();
    if (!activeSchema) return;
    const classified = classifyNodes(nodes, activeSchema.rules);
    const relations = buildRelations(classified, activeSchema.relationRules);
    set({ nodes: classified, relations, layer: get().layer ? { ...get().layer!, nodes: classified, relations, modifiedAt: new Date().toISOString() } : null });
  },

  generateSchemaFromAst: (astJson: unknown) => {
    try {
      set({ isProcessing: true, error: null });
      const adapter = new ASTJsonAdapter(astJson as string | object);
      const nodes = extractFeatures(adapter);
      const schema = generateSchema(nodes, {
        schemaId: "auto-" + Date.now(),
        schemaName: "자동 생성 스키마",
      });

      // 스키마 등록 및 활성화
      const { schemaLoader } = get();
      schemaLoader.loadFromJson(schema);
      const resolved = schemaLoader.get(schema.schemaId);
      if (resolved) {
        // 생성된 스키마로 분류 실행
        const classified = classifyNodes(nodes, resolved.rules);
        const relations = buildRelations(classified, resolved.relationRules);

        const now = new Date().toISOString();
        const layer: SemanticLayer = {
          version: "1.0.0",
          schemaId: schema.schemaId,
          sourceAstHash: adapter.getDocumentHash(),
          createdAt: now,
          modifiedAt: now,
          mergeHistory: [],
          nodes: classified,
          relations,
          deletedNodes: [],
        };

        set({
          activeSchemaId: schema.schemaId,
          activeSchema: resolved,
          labels: resolved.labels,
          layer,
          nodes: classified,
          relations,
          isProcessing: false,
        });
      } else {
        set({ isProcessing: false });
      }
    } catch (e) {
      set({ error: String(e), isProcessing: false });
    }
  },

  generateSuggestions: () => {
    const { nodes } = get();
    const labeled = nodes.filter((n) => n.manualOverride);
    const suggestions = suggestRules(labeled);
    set({ suggestedRules: suggestions, showRuleSuggester: true });
  },

  runValidation: () => {
    const { nodes, activeSchema } = get();
    if (!activeSchema) return;
    const labeled = nodes.filter((n) => n.manualOverride);
    if (labeled.length === 0) return;
    const result = validateRules(labeled, activeSchema.rules);
    set({ validationResult: result });
  },

  exportLayerJson: () => {
    const { layer, nodes, relations } = get();
    if (!layer) return "{}";
    return JSON.stringify({ ...layer, nodes, relations }, null, 2);
  },

  importLayerJson: (json) => {
    try {
      const data = JSON.parse(json) as SemanticLayer;
      set({
        layer: data,
        nodes: data.nodes,
        relations: data.relations,
      });
    } catch (e) {
      set({ error: String(e) });
    }
  },

  saveLayerToFile: async () => {
    try {
      const json = get().exportLayerJson();
      if (json === "{}") return;
      const path = await save({
        filters: [{ name: "JSON", extensions: ["json"] }],
        defaultPath: "semantic-layer.json",
      });
      if (!path) return;
      await invoke("save_semantic_layer", { path, json });
    } catch (e) {
      set({ error: String(e) });
    }
  },

  loadLayerFromFile: async () => {
    try {
      const path = await open({
        filters: [{ name: "JSON", extensions: ["json"] }],
        multiple: false,
      });
      if (!path) return;
      const json = await invoke<string>("load_semantic_layer", { path });
      get().importLayerJson(json);
    } catch (e) {
      set({ error: String(e) });
    }
  },

  setShowSchemaEditor: (show) => set({ showSchemaEditor: show }),
  setShowRuleSuggester: (show) => set({ showRuleSuggester: show }),
  setShowReextractReview: (show) => set({ showReextractReview: show }),
  setShowPptExport: (show) => set({ showPptExport: show }),

  reset: () =>
    set({
      layer: null,
      nodes: [],
      relations: [],
      selectedNodeId: null,
      hoveredNodeId: null,
      suggestedRules: [],
      validationResult: null,
      error: null,
    }),

  getSelectedNode: () => {
    const { nodes, selectedNodeId } = get();
    return nodes.find((n) => n.id === selectedNodeId) ?? null;
  },

  getNodeRelations: (nodeId) => {
    const { relations } = get();
    return relations.filter(
      (r) => r.sourceId === nodeId || r.targetId === nodeId
    );
  },

  getFilteredNodes: () => {
    const { nodes, searchQuery, filterLabel, filterPage, showUnknownOnly } =
      get();
    return nodes.filter((n) => {
      if (showUnknownOnly && n.label !== "UNKNOWN") return false;
      if (filterLabel && n.label !== filterLabel) return false;
      if (filterPage != null && n.features.pageNumber !== filterPage)
        return false;
      if (searchQuery) {
        const q = searchQuery.toLowerCase();
        return (
          n.id.toLowerCase().includes(q) ||
          n.label.toLowerCase().includes(q) ||
          n.features.textContent.toLowerCase().includes(q) ||
          n.features.firstLineText.toLowerCase().includes(q)
        );
      }
      return true;
    });
  },

  getPageNumbers: () => {
    const { nodes } = get();
    const pages = new Set(nodes.map((n) => n.features.pageNumber));
    return [...pages].sort((a, b) => a - b);
  },
}));
