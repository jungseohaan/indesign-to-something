// src/adapter/ast-json-adapter.ts
var ASTJsonAdapter = class {
  constructor(astJson) {
    this._hash = null;
    this.json = typeof astJson === "string" ? JSON.parse(astJson) : astJson;
    this.blockIndex = /* @__PURE__ */ new Map();
    this.storyIndex = /* @__PURE__ */ new Map();
    this.styleIndex = /* @__PURE__ */ new Map();
    this.buildIndices();
  }
  // ─── 인덱스 구축 ────────────────────────────────────────
  buildIndices() {
    for (const section of this.json.sections ?? []) {
      const pageNumber = section.pageNumber ?? 0;
      for (const block of section.blocks ?? []) {
        const id = block.sourceId;
        if (id) {
          this.blockIndex.set(id, { section, block, pageNumber });
        }
      }
    }
    for (const story of this.json.stories ?? []) {
      if (story.storyId) {
        this.storyIndex.set(story.storyId, story);
      }
    }
    for (const style of this.json.paragraphStyles ?? []) {
      this.styleIndex.set(style.styleId, this.mapStyle(style, "paragraph"));
    }
    for (const style of this.json.characterStyles ?? []) {
      this.styleIndex.set(style.styleId, this.mapStyle(style, "character"));
    }
  }
  // ─── 문서 구조 ──────────────────────────────────────────
  getPages() {
    return (this.json.sections ?? []).map((s) => {
      const layout = s.layout ?? {};
      return {
        pageNumber: s.pageNumber ?? 0,
        width: layout.pageWidth ?? 0,
        height: layout.pageHeight ?? 0,
        marginTop: layout.marginTop ?? 0,
        marginBottom: layout.marginBottom ?? 0,
        marginLeft: layout.marginLeft ?? 0,
        marginRight: layout.marginRight ?? 0,
        columnCount: layout.columnCount ?? 1,
        columnGutter: layout.columnGutter ?? 0
      };
    });
  }
  getBlocks(pageNumber) {
    const section = (this.json.sections ?? []).find(
      (s) => s.pageNumber === pageNumber
    );
    if (!section) return [];
    return (section.blocks ?? []).map(
      (b) => this.mapBlock(b, pageNumber)
    );
  }
  getParagraphs(blockId) {
    const entry = this.blockIndex.get(blockId);
    if (!entry) return [];
    const block = entry.block;
    const paragraphs = block.paragraphs ?? [];
    return paragraphs.map((p, i) => this.mapParagraph(p, i));
  }
  // ─── 스토리 ─────────────────────────────────────────────
  getStories() {
    return (this.json.stories ?? []).map((s) => this.mapStory(s));
  }
  getStory(storyId) {
    const raw = this.storyIndex.get(storyId);
    return raw ? this.mapStory(raw) : null;
  }
  getFrameIdsForStory(storyId) {
    const raw = this.storyIndex.get(storyId);
    return raw?.linkedFrameIds ?? [];
  }
  // ─── 스타일 ─────────────────────────────────────────────
  getParagraphStyles() {
    return (this.json.paragraphStyles ?? []).map(
      (s) => this.mapStyle(s, "paragraph")
    );
  }
  getCharacterStyles() {
    return (this.json.characterStyles ?? []).map(
      (s) => this.mapStyle(s, "character")
    );
  }
  getStyleByRef(ref) {
    return this.styleIndex.get(ref) ?? null;
  }
  // ─── 미디어 ─────────────────────────────────────────────
  getImageInfo(blockId) {
    const entry = this.blockIndex.get(blockId);
    if (!entry) return null;
    const b = entry.block;
    if (b.blockType === "FIGURE" && (b.kind === "IMAGE" || b.imageFormat)) {
      return {
        format: b.imageFormat ?? null,
        imagePath: b.imagePath ?? null,
        bundlePath: b.bundlePath ?? null,
        pixelWidth: b.pixelWidth ?? 0,
        pixelHeight: b.pixelHeight ?? 0,
        hasImageData: b.hasImageData ?? false
      };
    }
    return null;
  }
  getTableInfo(blockId) {
    const entry = this.blockIndex.get(blockId);
    if (!entry) return null;
    const b = entry.block;
    if (b.blockType !== "TABLE") return null;
    return {
      id: b.sourceId ?? blockId,
      rowCount: b.rowCount ?? 0,
      colCount: b.colCount ?? 0,
      columnWidths: b.columnWidths ?? [],
      borderColor: b.borderColor ?? null,
      borderWidth: b.borderWidth ?? 0,
      rows: (b.rows ?? []).map((r) => this.mapTableRow(r))
    };
  }
  // ─── 폰트 ──────────────────────────────────────────────
  getFonts() {
    return (this.json.fonts ?? []).map((f) => ({
      fontId: f.fontId ?? "",
      fontFamily: f.fontFamily ?? null,
      fontType: f.fontType ?? null
    }));
  }
  // ─── 색상 ──────────────────────────────────────────────
  getColors() {
    return this.json.colors ?? {};
  }
  getColorHex(colorRef) {
    const colors = this.json.colors ?? {};
    return colors[colorRef] ?? null;
  }
  // ─── 메타 ──────────────────────────────────────────────
  getSourceFile() {
    return this.json.sourceFile ?? null;
  }
  getDocumentHash() {
    if (!this._hash) {
      this._hash = simpleHash(JSON.stringify(this.json));
    }
    return this._hash;
  }
  // ─── 매핑 헬퍼 ─────────────────────────────────────────
  mapBlock(b, pageNumber) {
    const blockType = this.mapBlockType(b.blockType);
    const isTextFrame = blockType === "TEXT_FRAME";
    return {
      id: b.sourceId ?? "",
      blockType,
      pageNumber,
      x: b.x ?? 0,
      y: b.y ?? 0,
      width: b.width ?? 0,
      height: b.height ?? 0,
      zOrder: b.zOrder ?? 0,
      rotation: b.rotationAngle ?? 0,
      storyId: isTextFrame ? b.storyId ?? null : null,
      columnCount: isTextFrame ? b.columnCount ?? 1 : 0,
      fillColor: b.fillColor ?? null,
      strokeColor: b.strokeColor ?? null,
      hasFill: b.fillColor != null,
      hasStroke: b.strokeColor != null,
      isBackgroundOnly: this.detectBackgroundOnly(b),
      verticalJustification: isTextFrame ? b.verticalJustification ?? null : null
    };
  }
  mapBlockType(raw) {
    switch (raw) {
      case "TEXT_FRAME_BLOCK":
        return "TEXT_FRAME";
      case "TABLE":
        return "TABLE";
      case "FIGURE":
        return "FIGURE";
      default:
        return "TEXT_FRAME";
    }
  }
  detectBackgroundOnly(b) {
    if (b.blockType !== "TEXT_FRAME_BLOCK") return false;
    const paras = b.paragraphs ?? [];
    if (paras.length === 0 && b.fillColor) return true;
    if (paras.length === 1 && b.fillColor) {
      const items = paras[0].items ?? [];
      if (items.length === 0) return true;
      if (items.length === 1 && items[0].itemType === "TEXT_RUN") {
        const text = items[0].text ?? "";
        if (text.trim() === "") return true;
      }
    }
    return false;
  }
  mapParagraph(p, index) {
    return {
      index,
      alignment: p.alignment ?? null,
      paragraphStyleRef: p.paragraphStyleRef ?? null,
      firstLineIndent: p.firstLineIndent ?? null,
      spaceBefore: p.spaceBefore ?? null,
      spaceAfter: p.spaceAfter ?? null,
      items: (p.items ?? []).map((item) => this.mapInlineItem(item))
    };
  }
  mapInlineItem(item) {
    const itemType = item.itemType ?? "TEXT_RUN";
    const result = { itemType };
    switch (itemType) {
      case "TEXT_RUN":
        result.text = item.text ?? void 0;
        result.fontFamily = item.fontFamily ?? void 0;
        result.fontStyle = item.fontStyle ?? void 0;
        result.fontSize = item.fontSizeHwpunits ?? void 0;
        result.textColor = item.textColor ?? void 0;
        result.bold = item.fontStyle?.includes("Bold") ? true : void 0;
        result.underline = item.underline ?? void 0;
        result.strikeThrough = item.strikeThrough ?? void 0;
        result.subscript = item.subscript ?? void 0;
        result.superscript = item.superscript ?? void 0;
        result.characterStyleRef = item.characterStyleRef ?? void 0;
        break;
      case "INLINE_OBJECT":
        result.objectKind = item.kind ?? void 0;
        result.objectSourceId = item.sourceId ?? void 0;
        result.objectWidth = item.width ?? void 0;
        result.objectHeight = item.height ?? void 0;
        break;
      case "EQUATION":
        result.equationScript = item.hwpScript ?? void 0;
        result.equationSourceType = item.sourceType ?? void 0;
        result.equationColor = item.textColor ?? void 0;
        break;
      case "BREAK":
        result.breakType = item.breakType ?? void 0;
        break;
    }
    return result;
  }
  mapStory(s) {
    return {
      storyId: s.storyId ?? "",
      orientation: s.orientation ?? null,
      linkedFrameIds: s.linkedFrameIds ?? [],
      pages: s.pages ?? [],
      paragraphCount: s.paragraphCount ?? 0,
      tableCount: s.tableCount ?? 0
    };
  }
  mapStyle(s, type) {
    return {
      styleId: s.styleId ?? "",
      styleName: s.styleName ?? null,
      type,
      basedOnStyleRef: s.basedOnStyleRef ?? null,
      fontFamily: s.fontFamily ?? void 0,
      fontStyle: s.fontStyle ?? void 0,
      fontSize: s.fontSizeHwpunits ?? void 0,
      bold: s.bold ?? (s.fontStyle?.includes("Bold") ? true : void 0),
      italic: s.italic ?? (s.fontStyle?.includes("Italic") ? true : void 0),
      alignment: s.alignment ?? void 0,
      textColor: s.textColor ?? void 0
    };
  }
  mapTableRow(r) {
    return {
      rowIndex: r.rowIndex ?? 0,
      rowHeight: r.rowHeight ?? 0,
      cells: (r.cells ?? []).map((c) => this.mapTableCell(c))
    };
  }
  mapTableCell(c) {
    return {
      rowIndex: c.rowIndex ?? 0,
      columnIndex: c.columnIndex ?? 0,
      rowSpan: c.rowSpan ?? 1,
      columnSpan: c.columnSpan ?? 1,
      fillColor: c.fillColor ?? null,
      paragraphs: (c.paragraphs ?? []).map(
        (p, i) => this.mapParagraph(p, i)
      )
    };
  }
};
function simpleHash(str) {
  let hash = 5381;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) + hash + str.charCodeAt(i) >>> 0;
  }
  return hash.toString(16);
}

// src/core/feature-extractor.ts
function extractFeatures(adapter) {
  const pages = adapter.getPages();
  const stories = adapter.getStories();
  const storyMap = new Map(stories.map((s) => [s.storyId, s]));
  const nodes = [];
  const allBlocksByPage = /* @__PURE__ */ new Map();
  for (const page of pages) {
    const blocks = adapter.getBlocks(page.pageNumber);
    allBlocksByPage.set(page.pageNumber, blocks);
    for (let i = 0; i < blocks.length; i++) {
      const block = blocks[i];
      const paragraphs = adapter.getParagraphs(block.id);
      const story = block.storyId ? storyMap.get(block.storyId) ?? null : null;
      let frameIndex = 0;
      if (story) {
        const idx = story.linkedFrameIds.indexOf(block.id);
        if (idx >= 0) frameIndex = idx;
      }
      const features = buildFeatures(
        block,
        page,
        paragraphs,
        story,
        frameIndex,
        adapter
      );
      const nodeType = mapNodeType(block.blockType);
      const node = {
        id: `sn-${block.id}`,
        astPath: `sections[${pages.indexOf(page)}].blocks[${i}]`,
        nodeType,
        features,
        label: "UNKNOWN",
        confidence: 0,
        appliedRule: null,
        manualOverride: false,
        children: [],
        storyId: block.storyId,
        metadata: {}
      };
      nodes.push(node);
    }
  }
  for (const node of nodes) {
    const pageBlocks = allBlocksByPage.get(node.features.pageNumber) ?? [];
    const page = pages.find((p) => p.pageNumber === node.features.pageNumber);
    if (page) {
      node.features.spatial = computeSpatialProximity(node.features, pageBlocks, node.id);
    }
  }
  return nodes;
}
function buildFeatures(block, page, paragraphs, story, frameIndex, adapter) {
  const allItems = paragraphs.flatMap((p) => p.items);
  const textRuns = allItems.filter((i) => i.itemType === "TEXT_RUN");
  const fullText = textRuns.map((r) => r.text ?? "").join("");
  const firstLineText = getFirstLineText(paragraphs);
  const fontSizes = textRuns.filter((r) => r.fontSize != null).map((r) => r.fontSize);
  const fontFamilies = textRuns.filter((r) => r.fontFamily != null).map((r) => r.fontFamily);
  const paraStyleRefs = paragraphs.map((p) => p.paragraphStyleRef).filter((r) => r != null);
  const paraStyleNames = paraStyleRefs.map((ref) => {
    const style = adapter.getStyleByRef(ref);
    return style?.styleName ?? ref;
  });
  const charStyleRefs = textRuns.map((r) => r.characterStyleRef).filter((r) => r != null);
  const charStyleNames = [...new Set(charStyleRefs.map((ref) => {
    const style = adapter.getStyleByRef(ref);
    return style?.styleName ?? ref;
  }))];
  const { hasPrefix, pattern } = detectNumberPrefix(firstLineText);
  const alignments = paragraphs.map((p) => p.alignment).filter((a) => a != null);
  return {
    // A. 위치 & 레이아웃
    pageNumber: page.pageNumber,
    x: block.x,
    y: block.y,
    width: block.width,
    height: block.height,
    zOrder: block.zOrder,
    regionTag: computeRegion(block, page),
    columnIndex: computeColumnIndex(block, page),
    relativeYInPage: page.height > 0 ? block.y / page.height : 0,
    // B. 스토리
    storyId: block.storyId,
    storyFrameCount: story?.linkedFrameIds.length ?? 0,
    storyPageSpan: story?.pages.length ?? 0,
    frameIndexInStory: frameIndex,
    isStoryStart: frameIndex === 0,
    isStoryEnd: story ? frameIndex === story.linkedFrameIds.length - 1 : true,
    // C. 텍스트 속성
    textContent: fullText,
    textLength: fullText.length,
    paragraphCount: paragraphs.length,
    dominantFontSize: mode(fontSizes) ?? 0,
    maxFontSize: fontSizes.length > 0 ? Math.max(...fontSizes) : 0,
    dominantFontFamily: mode(fontFamilies) ?? "",
    hasBoldText: textRuns.some((r) => r.bold === true),
    dominantAlignment: mode(alignments) ?? null,
    hasNumberPrefix: hasPrefix,
    numberPrefixPattern: pattern,
    firstLineText,
    // D. 스타일
    paragraphStyleNames: [...new Set(paraStyleNames)],
    characterStyleNames: charStyleNames,
    dominantParagraphStyle: mode(paraStyleNames) ?? null,
    // E. 프레임 속성
    hasFill: block.hasFill,
    fillColor: block.fillColor,
    hasStroke: block.hasStroke,
    isBackgroundOnly: block.isBackgroundOnly,
    columnCount: block.columnCount,
    rotationAngle: block.rotation,
    // F. 콘텐츠 구성
    hasTable: block.blockType === "TABLE",
    hasImage: block.blockType === "FIGURE" || allItems.some((i) => i.objectKind === "IMAGE"),
    hasEquation: allItems.some((i) => i.itemType === "EQUATION"),
    hasInlineFrame: allItems.some((i) => i.objectKind === "INLINE_TEXT_FRAME"),
    inlineObjectCount: allItems.filter((i) => i.itemType === "INLINE_OBJECT").length,
    blockType: block.blockType,
    // G. 공간 근접도 (나중에 채움)
    spatial: {
      nearestContentNodeId: null,
      nearestContentDistance: Infinity,
      overlappingNodeIds: [],
      isVisuallyContainedBy: null,
      visualContainmentRatio: 0
    }
  };
}
function computeSpatialProximity(features, allBlocks, selfNodeId) {
  const selfId = selfNodeId.replace("sn-", "");
  const candidates = allBlocks.filter((b) => b.id !== selfId);
  const overlapping = candidates.filter((c) => aabbOverlap(features, c));
  let containerId = null;
  let containmentRatio = 0;
  const selfArea = features.width * features.height;
  if (selfArea > 0) {
    for (const c of overlapping) {
      const overlapArea = computeOverlapArea(features, c);
      const ratio = overlapArea / selfArea;
      if (ratio >= 0.8 && c.width * c.height > selfArea && ratio > containmentRatio) {
        containerId = c.id;
        containmentRatio = ratio;
      }
    }
  }
  const contentNodes = candidates.filter((c) => !c.isBackgroundOnly);
  let nearestId = null;
  let minDist = Infinity;
  for (const c of contentNodes) {
    const dist = edgeToEdgeDistance(features, c);
    if (dist < minDist) {
      minDist = dist;
      nearestId = c.id;
    }
  }
  return {
    nearestContentNodeId: nearestId,
    nearestContentDistance: minDist === Infinity ? -1 : minDist,
    overlappingNodeIds: overlapping.map((n) => n.id),
    isVisuallyContainedBy: containerId,
    visualContainmentRatio: containmentRatio
  };
}
function aabbOverlap(a, b) {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;
}
function computeOverlapArea(a, b) {
  const overlapX = Math.max(0, Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x));
  const overlapY = Math.max(0, Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y));
  return overlapX * overlapY;
}
function edgeToEdgeDistance(a, b) {
  const dx = Math.max(0, Math.max(a.x, b.x) - Math.min(a.x + a.width, b.x + b.width));
  const dy = Math.max(0, Math.max(a.y, b.y) - Math.min(a.y + a.height, b.y + b.height));
  return Math.sqrt(dx * dx + dy * dy);
}
function computeRegion(block, page) {
  const contentHeight = page.height - page.marginTop - page.marginBottom;
  const relY = (block.y - page.marginTop) / (contentHeight || 1);
  const relWidth = block.width / (page.width || 1);
  if (relWidth > 0.85) return "FULL_WIDTH";
  if (relY < 0.15) return "TOP";
  if (relY > 0.85) return "BOTTOM";
  if (page.columnCount >= 2) {
    const contentWidth = page.width - page.marginLeft - page.marginRight;
    const midX = page.marginLeft + contentWidth / 2;
    const blockCenterX = block.x + block.width / 2;
    if (blockCenterX < midX - contentWidth * 0.1) return "LEFT";
    if (blockCenterX > midX + contentWidth * 0.1) return "RIGHT";
  }
  return "MIDDLE";
}
function computeColumnIndex(block, page) {
  if (page.columnCount <= 1) return 0;
  const contentWidth = page.width - page.marginLeft - page.marginRight;
  const colWidth = (contentWidth - (page.columnCount - 1) * page.columnGutter) / page.columnCount;
  if (colWidth <= 0) return 0;
  const relX = block.x - page.marginLeft;
  return Math.min(Math.floor(relX / (colWidth + page.columnGutter)), page.columnCount - 1);
}
function getFirstLineText(paragraphs) {
  if (paragraphs.length === 0) return "";
  const items = paragraphs[0].items;
  const texts = [];
  for (const item of items) {
    if (item.itemType === "TEXT_RUN" && item.text) {
      const newlineIdx = item.text.indexOf("\n");
      if (newlineIdx >= 0) {
        texts.push(item.text.slice(0, newlineIdx));
        break;
      }
      texts.push(item.text);
    }
    if (item.itemType === "BREAK") break;
  }
  return texts.join("").slice(0, 200);
}
var NUMBER_PATTERNS = [
  { pattern: /^\d+\.\s/, name: "arabic_dot" },
  { pattern: /^\d+\s/, name: "arabic_bare" },
  { pattern: /^[①②③④⑤⑥⑦⑧⑨⑩]/, name: "circled" },
  { pattern: /^\([가나다라마바사아자차카타파하]\)/, name: "parenthesized_korean" },
  { pattern: /^\(\d+\)/, name: "parenthesized_arabic" },
  { pattern: /^[ⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹ][\.\)]/i, name: "roman" },
  { pattern: /^[가나다라마바사]\.\s/, name: "korean_dot" },
  { pattern: /^[㉠㉡㉢㉣㉤㉥㉦㉧㉨㉩]/, name: "circled_korean" }
];
function detectNumberPrefix(text) {
  const trimmed = text.trimStart();
  for (const { pattern, name } of NUMBER_PATTERNS) {
    if (pattern.test(trimmed)) {
      return { hasPrefix: true, pattern: name };
    }
  }
  return { hasPrefix: false, pattern: null };
}
function mapNodeType(blockType) {
  switch (blockType) {
    case "TEXT_FRAME":
      return "FRAME";
    case "TABLE":
      return "TABLE";
    case "FIGURE":
      return "FIGURE";
    default:
      return "FRAME";
  }
}
function mode(arr) {
  if (arr.length === 0) return void 0;
  const counts = /* @__PURE__ */ new Map();
  let maxCount = 0;
  let maxVal = arr[0];
  for (const v of arr) {
    const c = (counts.get(v) ?? 0) + 1;
    counts.set(v, c);
    if (c > maxCount) {
      maxCount = c;
      maxVal = v;
    }
  }
  return maxVal;
}

// src/core/schema-loader.ts
var SchemaLoader = class {
  constructor() {
    this.schemas = /* @__PURE__ */ new Map();
  }
  /** 스키마 등록 */
  register(schema) {
    this.schemas.set(schema.schemaId, schema);
  }
  /** JSON에서 스키마 로드 & 등록 */
  loadFromJson(json) {
    const raw = typeof json === "string" ? JSON.parse(json) : json;
    const schema = validateSchema(raw);
    this.register(schema);
    return schema;
  }
  /** 스키마 가져오기 (상속 해석 포함) */
  get(schemaId) {
    const schema = this.schemas.get(schemaId);
    if (!schema) return null;
    return this.resolve(schema);
  }
  /** 등록된 모든 스키마 ID */
  listIds() {
    return [...this.schemas.keys()];
  }
  /** 상속 해석: extends된 부모 스키마와 합치기 */
  resolve(schema) {
    if (!schema.extends) return schema;
    const parent = this.schemas.get(schema.extends);
    if (!parent) return schema;
    const resolvedParent = this.resolve(parent);
    return mergeSchemas(resolvedParent, schema);
  }
};
function mergeSchemas(parent, child) {
  const labelMap = /* @__PURE__ */ new Map();
  for (const l of parent.labels) labelMap.set(l.id, l);
  for (const l of child.labels) labelMap.set(l.id, l);
  const allRules = [...parent.rules, ...child.rules];
  allRules.sort((a, b) => a.priority - b.priority);
  const relationMap = /* @__PURE__ */ new Map();
  for (const r of parent.relationRules) relationMap.set(r.id, r);
  for (const r of child.relationRules) relationMap.set(r.id, r);
  const hintMap = /* @__PURE__ */ new Map();
  for (const h of parent.layoutHints) hintMap.set(h.label, h);
  for (const h of child.layoutHints) hintMap.set(h.label, h);
  return {
    schemaId: child.schemaId,
    schemaName: child.schemaName,
    version: child.version,
    subject: child.subject,
    documentType: child.documentType,
    // extends는 제거 (이미 해석됨)
    labels: [...labelMap.values()],
    rules: allRules,
    relationRules: [...relationMap.values()],
    layoutHints: [...hintMap.values()]
  };
}
function validateSchema(raw) {
  return {
    schemaId: raw.schemaId ?? "",
    schemaName: raw.schemaName ?? "",
    version: raw.version ?? "1.0.0",
    subject: raw.subject ?? "",
    documentType: raw.documentType ?? "",
    extends: raw.extends ?? void 0,
    labels: (raw.labels ?? []).map(validateLabel),
    rules: (raw.rules ?? []).map(validateRule),
    relationRules: raw.relationRules ?? [],
    layoutHints: raw.layoutHints ?? []
  };
}
function validateLabel(raw) {
  return {
    id: raw.id ?? "",
    name: raw.name ?? "",
    description: raw.description ?? "",
    color: raw.color ?? "#888888",
    icon: raw.icon ?? "",
    category: raw.category ?? "content",
    allowedChildren: raw.allowedChildren ?? []
  };
}
function validateRule(raw) {
  return {
    id: raw.id ?? "",
    label: raw.label ?? "",
    priority: raw.priority ?? 999,
    conditions: raw.conditions ?? [],
    confidence: raw.confidence ?? 0.5
  };
}

// src/core/rule-classifier.ts
function classifyNodes(nodes, rules) {
  return nodes.map((node) => {
    if (node.manualOverride) return node;
    const result = classifyNode(node, rules);
    if (!result) return node;
    return {
      ...node,
      label: result.label,
      confidence: result.confidence,
      appliedRule: result.ruleId
    };
  });
}
function classifyNode(node, rules) {
  let best = null;
  for (const rule of rules) {
    if (!evaluateConditions(rule.conditions, node.features)) continue;
    if (!best || rule.confidence > best.confidence) {
      best = {
        label: rule.label,
        confidence: rule.confidence,
        ruleId: rule.id
      };
    }
  }
  return best;
}
function evaluateConditions(conditions, features) {
  return conditions.every((c) => evaluateCondition(c, features));
}
function evaluateCondition(condition, features) {
  const value = getFieldValue(condition.field, features);
  const expected = condition.value;
  switch (condition.operator) {
    case "eq":
      return value === expected;
    case "ne":
      return value !== expected;
    case "gt":
      return typeof value === "number" && typeof expected === "number" && value > expected;
    case "lt":
      return typeof value === "number" && typeof expected === "number" && value < expected;
    case "gte":
      return typeof value === "number" && typeof expected === "number" && value >= expected;
    case "lte":
      return typeof value === "number" && typeof expected === "number" && value <= expected;
    case "contains":
      if (typeof value === "string" && typeof expected === "string") {
        return value.includes(expected);
      }
      if (Array.isArray(value)) {
        return value.includes(expected);
      }
      return false;
    case "startsWith":
      return typeof value === "string" && typeof expected === "string" && value.startsWith(expected);
    case "matches":
      if (typeof value === "string" && typeof expected === "string") {
        try {
          return new RegExp(expected).test(value);
        } catch {
          return false;
        }
      }
      return false;
    case "in":
      return Array.isArray(expected) && expected.includes(value);
    case "notIn":
      return Array.isArray(expected) && !expected.includes(value);
    default:
      return false;
  }
}
function getFieldValue(field, features) {
  const parts = field.split(".");
  let current = features;
  for (const part of parts) {
    if (current == null || typeof current !== "object") return void 0;
    current = current[part];
  }
  return current;
}

// src/core/relation-builder.ts
function buildRelations(nodes, relationRules) {
  const relations = [];
  relations.push(...buildStoryRelations(nodes));
  relations.push(...buildRuleBasedRelations(nodes, relationRules));
  return deduplicateRelations(relations);
}
function buildStoryRelations(nodes) {
  const relations = [];
  const storyGroups = /* @__PURE__ */ new Map();
  for (const node of nodes) {
    if (!node.storyId) continue;
    const group = storyGroups.get(node.storyId);
    if (group) {
      group.push(node);
    } else {
      storyGroups.set(node.storyId, [node]);
    }
  }
  for (const [, group] of storyGroups) {
    if (group.length < 2) continue;
    group.sort((a, b) => a.features.frameIndexInStory - b.features.frameIndexInStory);
    for (let i = 1; i < group.length; i++) {
      relations.push({
        type: "CONTINUES_FROM",
        sourceId: group[i].id,
        targetId: group[i - 1].id,
        confidence: 1
      });
    }
  }
  return relations;
}
function buildRuleBasedRelations(nodes, rules) {
  const relations = [];
  const nodesByLabel = /* @__PURE__ */ new Map();
  for (const node of nodes) {
    if (node.label === "UNKNOWN") continue;
    const list = nodesByLabel.get(node.label);
    if (list) {
      list.push(node);
    } else {
      nodesByLabel.set(node.label, [node]);
    }
  }
  for (const rule of rules) {
    const sources = nodesByLabel.get(rule.sourceLabel) ?? [];
    const targets = nodesByLabel.get(rule.targetLabel) ?? [];
    for (const source of sources) {
      for (const target of targets) {
        if (source.id === target.id) continue;
        if (rule.conditions && rule.conditions.length > 0) {
          if (!evaluateConditions(rule.conditions, source.features)) continue;
        }
        if (!isRelatable(source, target, rule.type)) continue;
        relations.push({
          type: rule.type,
          sourceId: source.id,
          targetId: target.id,
          confidence: 0.8
        });
      }
    }
  }
  return relations;
}
function isRelatable(source, target, type) {
  if (type === "CONTINUES_FROM") return false;
  if (source.features.pageNumber === target.features.pageNumber) return true;
  if (Math.abs(source.features.pageNumber - target.features.pageNumber) <= 1) return true;
  return false;
}
function deduplicateRelations(relations) {
  const seen = /* @__PURE__ */ new Set();
  return relations.filter((r) => {
    const key = `${r.type}:${r.sourceId}:${r.targetId}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

// src/core/rule-suggester.ts
var ANALYZABLE_FIELDS = [
  { field: "regionTag", type: "string" },
  { field: "blockType", type: "string" },
  { field: "hasBoldText", type: "boolean" },
  { field: "hasFill", type: "boolean" },
  { field: "hasStroke", type: "boolean" },
  { field: "isBackgroundOnly", type: "boolean" },
  { field: "hasEquation", type: "boolean" },
  { field: "hasImage", type: "boolean" },
  { field: "hasTable", type: "boolean" },
  { field: "hasNumberPrefix", type: "boolean" },
  { field: "numberPrefixPattern", type: "string" },
  { field: "dominantParagraphStyle", type: "string" },
  { field: "dominantAlignment", type: "string" },
  { field: "isStoryStart", type: "boolean" },
  { field: "isStoryEnd", type: "boolean" },
  { field: "dominantFontFamily", type: "string" },
  { field: "firstLineText", type: "string_contains" }
];
function suggestRules(labeledNodes, minCoverage = 0.6) {
  const groups = /* @__PURE__ */ new Map();
  for (const node of labeledNodes) {
    if (node.label === "UNKNOWN") continue;
    const group = groups.get(node.label);
    if (group) {
      group.push(node);
    } else {
      groups.set(node.label, [node]);
    }
  }
  const suggestions = [];
  let ruleIdx = 0;
  for (const [label, nodes] of groups) {
    if (nodes.length < 2) continue;
    const commonConditions = findCommonConditions(nodes);
    if (commonConditions.length === 0) continue;
    const combos = generateConditionCombos(commonConditions, 3);
    for (const conditions of combos) {
      const matchCount = nodes.filter(
        (n) => conditions.every((c) => matchesCondition(c, n))
      ).length;
      const coverage = matchCount / nodes.length;
      if (coverage >= minCoverage && matchCount >= 2) {
        suggestions.push({
          id: `suggested-${label.toLowerCase()}-${ruleIdx++}`,
          label,
          priority: 100,
          conditions,
          confidence: Math.round(coverage * 100) / 100,
          matchCount,
          coverage
        });
      }
    }
  }
  suggestions.sort((a, b) => {
    if (b.coverage !== a.coverage) return b.coverage - a.coverage;
    return a.conditions.length - b.conditions.length;
  });
  return deduplicateSuggestions(suggestions);
}
function findCommonConditions(nodes) {
  const conditions = [];
  for (const { field, type } of ANALYZABLE_FIELDS) {
    const values = nodes.map((n) => getField(n, field));
    if (type === "boolean") {
      const trueCount = values.filter((v) => v === true).length;
      const falseCount = values.filter((v) => v === false).length;
      if (trueCount === nodes.length) {
        conditions.push({ field, operator: "eq", value: true });
      } else if (falseCount === nodes.length) {
        conditions.push({ field, operator: "eq", value: false });
      }
    } else if (type === "string") {
      const nonNull = values.filter((v) => typeof v === "string" && v !== "");
      if (nonNull.length < nodes.length * 0.8) continue;
      const freq = mode2(nonNull);
      if (freq && nonNull.filter((v) => v === freq).length >= nodes.length * 0.8) {
        conditions.push({ field, operator: "eq", value: freq });
      }
    } else if (type === "string_contains") {
      const nonNull = values.filter((v) => typeof v === "string" && v.length > 0);
      if (nonNull.length < nodes.length * 0.8) continue;
      const common = findCommonSubstring(nonNull);
      if (common && common.length >= 2) {
        conditions.push({ field, operator: "contains", value: common });
      }
    }
  }
  const fontSizes = nodes.map((n) => n.features.dominantFontSize).filter((v) => v > 0);
  if (fontSizes.length >= nodes.length * 0.8) {
    const min = Math.min(...fontSizes);
    const max = Math.max(...fontSizes);
    if (min === max) {
      conditions.push({ field: "dominantFontSize", operator: "eq", value: min });
    } else if (max - min <= min * 0.2) {
      conditions.push({ field: "dominantFontSize", operator: "gte", value: min });
      conditions.push({ field: "dominantFontSize", operator: "lte", value: max });
    }
  }
  return conditions;
}
function generateConditionCombos(conditions, maxSize) {
  const result = [];
  for (const c of conditions) {
    result.push([c]);
  }
  if (maxSize >= 2 && conditions.length >= 2) {
    for (let i = 0; i < conditions.length; i++) {
      for (let j = i + 1; j < conditions.length; j++) {
        if (conditions[i].field === conditions[j].field) {
          result.push([conditions[i], conditions[j]]);
        } else {
          result.push([conditions[i], conditions[j]]);
        }
      }
    }
  }
  if (maxSize >= 3 && conditions.length >= 3) {
    for (let i = 0; i < conditions.length; i++) {
      for (let j = i + 1; j < conditions.length; j++) {
        for (let k = j + 1; k < conditions.length; k++) {
          result.push([conditions[i], conditions[j], conditions[k]]);
        }
      }
    }
  }
  return result;
}
function getField(node, field) {
  const parts = field.split(".");
  let current = node.features;
  for (const part of parts) {
    if (current == null || typeof current !== "object") return void 0;
    current = current[part];
  }
  return current;
}
function matchesCondition(condition, node) {
  const value = getField(node, condition.field);
  switch (condition.operator) {
    case "eq":
      return value === condition.value;
    case "ne":
      return value !== condition.value;
    case "gt":
      return typeof value === "number" && typeof condition.value === "number" && value > condition.value;
    case "lt":
      return typeof value === "number" && typeof condition.value === "number" && value < condition.value;
    case "gte":
      return typeof value === "number" && typeof condition.value === "number" && value >= condition.value;
    case "lte":
      return typeof value === "number" && typeof condition.value === "number" && value <= condition.value;
    case "contains":
      if (typeof value === "string" && typeof condition.value === "string") return value.includes(condition.value);
      if (Array.isArray(value)) return value.includes(condition.value);
      return false;
    default:
      return false;
  }
}
function findCommonSubstring(strings) {
  if (strings.length === 0) return null;
  const first = strings[0];
  let prefix = "";
  for (let i = 0; i < Math.min(first.length, 20); i++) {
    const char = first[i];
    if (strings.every((s) => s[i] === char)) {
      prefix += char;
    } else {
      break;
    }
  }
  return prefix.trim() || null;
}
function mode2(arr) {
  if (arr.length === 0) return void 0;
  const counts = /* @__PURE__ */ new Map();
  let maxCount = 0;
  let maxVal = arr[0];
  for (const v of arr) {
    const c = (counts.get(v) ?? 0) + 1;
    counts.set(v, c);
    if (c > maxCount) {
      maxCount = c;
      maxVal = v;
    }
  }
  return maxVal;
}
function deduplicateSuggestions(suggestions) {
  const seen = /* @__PURE__ */ new Map();
  return suggestions.filter((s) => {
    const key = `${s.label}:${s.conditions.map((c) => `${c.field}${c.operator}${c.value}`).sort().join(",")}`;
    if (seen.has(key)) return false;
    seen.set(key, s);
    return true;
  });
}

// src/core/schema-generator.ts
var COLORS = [
  "#1565C0",
  "#0D47A1",
  "#1976D2",
  "#2196F3",
  // 파란 계열 (구조)
  "#333333",
  "#616161",
  // 회색 계열 (본문)
  "#FF6B6B",
  "#FF8A80",
  "#FFAB91",
  // 빨강 계열 (문제)
  "#81C784",
  "#A5D6A7",
  "#66BB6A",
  "#43A047",
  // 초록 계열 (풀이/그림)
  "#4FC3F7",
  "#26A69A",
  // 청록 계열 (개념)
  "#7E57C2",
  "#AB47BC",
  // 보라 계열 (수식)
  "#FF8F00",
  "#FFB300",
  // 주황 계열 (표)
  "#FFF176",
  "#BCAAA4",
  // 노랑/갈색 (참고)
  "#9E9E9E",
  "#E0E0E0",
  "#BDBDBD"
  // 회색 계열 (장식)
];
function generateSchema(nodes, options = {}) {
  const {
    schemaId = "auto-generated",
    schemaName = "\uC790\uB3D9 \uC0DD\uC131 \uC2A4\uD0A4\uB9C8",
    subject = "",
    documentType = "",
    minGroupSize = 2
  } = options;
  const groups = groupNodes(nodes, minGroupSize);
  const labelGroups = assignLabels(groups);
  const labels = labelGroups.map((g, i) => ({
    id: g.label,
    name: g.labelName,
    description: g.description,
    color: COLORS[i % COLORS.length],
    icon: "",
    category: g.category,
    allowedChildren: []
  }));
  labels.push({
    id: "UNKNOWN",
    name: "\uBBF8\uBD84\uB958",
    description: "\uC790\uB3D9 \uBD84\uB958\uB418\uC9C0 \uC54A\uC740 \uB178\uB4DC",
    color: "#9E9E9E",
    icon: "",
    category: "decoration",
    allowedChildren: []
  });
  const rules = labelGroups.map((g) => ({
    id: `rule-${g.label.toLowerCase()}`,
    label: g.label,
    priority: g.priority,
    conditions: g.conditions,
    confidence: g.confidence
  }));
  const relationRules = inferRelationRules(labelGroups, nodes);
  const layoutHints = labelGroups.filter((g) => g.regionTags.length > 0).map((g) => ({
    label: g.label,
    expectedRegions: g.regionTags
  }));
  for (const rel of relationRules) {
    if (rel.type === "PARENT_OF") {
      const parentLabel = labels.find((l) => l.id === rel.sourceLabel);
      if (parentLabel && !parentLabel.allowedChildren.includes(rel.targetLabel)) {
        parentLabel.allowedChildren.push(rel.targetLabel);
      }
    }
  }
  return {
    schemaId,
    schemaName,
    version: "1.0.0",
    subject,
    documentType,
    labels,
    rules,
    relationRules,
    layoutHints
  };
}
function groupNodes(nodes, minGroupSize) {
  const byBlockType = /* @__PURE__ */ new Map();
  for (const n of nodes) {
    const bt = n.features.blockType;
    const list = byBlockType.get(bt) ?? [];
    list.push(n);
    byBlockType.set(bt, list);
  }
  const groups = [];
  const figures = byBlockType.get("FIGURE") ?? [];
  if (figures.length >= minGroupSize) {
    groups.push(makeBlockTypeGroup("FIGURE", figures));
  }
  const tables = byBlockType.get("TABLE") ?? [];
  if (tables.length >= minGroupSize) {
    groups.push(makeBlockTypeGroup("TABLE", tables));
  }
  const textFrames = byBlockType.get("TEXT_FRAME") ?? [];
  const bgNodes = textFrames.filter((n) => n.features.isBackgroundOnly);
  const contentNodes = textFrames.filter((n) => !n.features.isBackgroundOnly);
  if (bgNodes.length >= minGroupSize) {
    groups.push({
      key: "background",
      nodes: bgNodes,
      label: "BACKGROUND",
      labelName: "\uBC30\uACBD",
      description: "\uBC30\uACBD \uC804\uC6A9 \uD504\uB808\uC784 (\uC7A5\uC2DD, \uC0C9\uC0C1 \uBE14\uB85D)",
      category: "decoration",
      color: "#E0E0E0",
      conditions: [{ field: "isBackgroundOnly", operator: "eq", value: true }],
      priority: 5,
      confidence: 0.95,
      regionTags: []
    });
  }
  const decoNodes = contentNodes.filter(
    (n) => n.features.textLength === 0 && n.features.width * n.features.height < 5e3 * 5e3
  );
  const textContentNodes = contentNodes.filter(
    (n) => n.features.textLength > 0 || n.features.width * n.features.height >= 5e3 * 5e3
  );
  if (decoNodes.length >= minGroupSize) {
    groups.push({
      key: "decoration",
      nodes: decoNodes,
      label: "DECORATION",
      labelName: "\uC7A5\uC2DD",
      description: "\uAD6C\uBD84\uC120, \uC544\uC774\uCF58 \uB4F1 \uC7A5\uC2DD \uC694\uC18C",
      category: "decoration",
      color: "#BDBDBD",
      conditions: [
        { field: "blockType", operator: "eq", value: "TEXT_FRAME" },
        { field: "textLength", operator: "eq", value: 0 }
      ],
      priority: 3,
      confidence: 0.9,
      regionTags: []
    });
  }
  const { headers, footers, rest: afterHF } = detectHeaderFooter(textContentNodes);
  if (headers.length >= minGroupSize) {
    groups.push({
      key: "page-header",
      nodes: headers,
      label: "PAGE_HEADER",
      labelName: "\uCABD \uBA38\uB9AC",
      description: "\uD398\uC774\uC9C0 \uC0C1\uB2E8 \uBC18\uBCF5 \uD14D\uC2A4\uD2B8",
      category: "structure",
      color: "#9E9E9E",
      conditions: buildConditionsFromNodes(headers, ["regionTag", "textLength_lt"]),
      priority: 10,
      confidence: 0.85,
      regionTags: ["TOP", "FULL_WIDTH"]
    });
  }
  if (footers.length >= minGroupSize) {
    groups.push({
      key: "page-footer",
      nodes: footers,
      label: "PAGE_FOOTER",
      labelName: "\uCABD \uAF2C\uB9AC",
      description: "\uD398\uC774\uC9C0 \uD558\uB2E8 \uBC18\uBCF5 \uD14D\uC2A4\uD2B8 (\uD398\uC774\uC9C0 \uBC88\uD638 \uB4F1)",
      category: "structure",
      color: "#9E9E9E",
      conditions: buildConditionsFromNodes(footers, ["regionTag", "textLength_lt"]),
      priority: 10,
      confidence: 0.85,
      regionTags: ["BOTTOM", "FULL_WIDTH"]
    });
  }
  const styleGroups = clusterByStyle(afterHF, minGroupSize);
  groups.push(...styleGroups);
  return groups;
}
function assignLabels(groups) {
  let priorityCounter = 15;
  for (const g of groups) {
    if (g.label) continue;
    g.priority = priorityCounter;
    priorityCounter += 5;
  }
  return groups;
}
function inferRelationRules(groups, nodes) {
  const rules = [];
  const labelSet = new Set(groups.map((g) => g.label));
  if (labelSet.has("CAPTION") && labelSet.has("FIGURE")) {
    rules.push({ id: "rel-caption-figure", type: "CAPTION_FOR", sourceLabel: "CAPTION", targetLabel: "FIGURE" });
  }
  if (labelSet.has("CAPTION") && labelSet.has("TABLE")) {
    rules.push({ id: "rel-caption-table", type: "CAPTION_FOR", sourceLabel: "CAPTION", targetLabel: "TABLE" });
  }
  if (labelSet.has("PROBLEM") && labelSet.has("SUB_PROBLEM")) {
    rules.push({ id: "rel-sub-problem", type: "PARENT_OF", sourceLabel: "PROBLEM", targetLabel: "SUB_PROBLEM" });
  }
  if (labelSet.has("PROBLEM") && labelSet.has("CHOICES")) {
    rules.push({ id: "rel-choices-problem", type: "PARENT_OF", sourceLabel: "PROBLEM", targetLabel: "CHOICES" });
  }
  if (labelSet.has("SOLUTION") && labelSet.has("EXAMPLE")) {
    rules.push({ id: "rel-solution-example", type: "SOLUTION_FOR", sourceLabel: "SOLUTION", targetLabel: "EXAMPLE" });
  }
  if (labelSet.has("ANSWER") && labelSet.has("PROBLEM")) {
    rules.push({ id: "rel-answer-problem", type: "ANSWER_FOR", sourceLabel: "ANSWER", targetLabel: "PROBLEM" });
  }
  return rules;
}
function clusterByStyle(nodes, minGroupSize) {
  const clusters = /* @__PURE__ */ new Map();
  for (const n of nodes) {
    const k = {
      dominantParagraphStyle: n.features.dominantParagraphStyle,
      fontSizeBucket: Math.round(n.features.dominantFontSize / 100) * 100,
      // 1pt 단위 버킷
      hasFill: n.features.hasFill,
      hasStroke: n.features.hasStroke,
      hasNumberPrefix: n.features.hasNumberPrefix,
      numberPrefixPattern: n.features.numberPrefixPattern
    };
    const keyStr = JSON.stringify(k);
    const existing = clusters.get(keyStr);
    if (existing) {
      existing.nodes.push(n);
    } else {
      clusters.set(keyStr, { key: k, nodes: [n] });
    }
  }
  const groups = [];
  let colorIdx = 0;
  let priorityCounter = 20;
  const sorted = [...clusters.values()].sort((a, b) => b.nodes.length - a.nodes.length);
  for (const cluster of sorted) {
    if (cluster.nodes.length < minGroupSize) continue;
    const { label, labelName, description, category } = inferLabelFromCluster(cluster.key, cluster.nodes);
    const conditions = buildClusterConditions(cluster.key, cluster.nodes);
    groups.push({
      key: `style-${JSON.stringify(cluster.key)}`,
      nodes: cluster.nodes,
      label,
      labelName,
      description,
      category,
      color: COLORS[colorIdx++ % COLORS.length],
      conditions,
      priority: priorityCounter,
      confidence: computeClusterConfidence(cluster.nodes),
      regionTags: inferRegionTags(cluster.nodes)
    });
    priorityCounter += 5;
  }
  const labelCounts = /* @__PURE__ */ new Map();
  for (const g of groups) {
    const count = (labelCounts.get(g.label) ?? 0) + 1;
    labelCounts.set(g.label, count);
    if (count > 1) {
      g.label = `${g.label}_${count}`;
      g.labelName = `${g.labelName} ${count}`;
    }
  }
  return groups;
}
function inferLabelFromCluster(key, nodes) {
  const avgFontSize = avg(nodes.map((n) => n.features.dominantFontSize));
  const avgTextLen = avg(nodes.map((n) => n.features.textLength));
  const avgParaCount = avg(nodes.map((n) => n.features.paragraphCount));
  const styleName = key.dominantParagraphStyle ?? "";
  if (avgFontSize >= 1400 && avgParaCount <= 3 && avgTextLen < 200) {
    if (avgFontSize >= 1800) {
      return { label: "CHAPTER_TITLE", labelName: "\uB300\uB2E8\uC6D0 \uC81C\uBAA9", description: `\uC2A4\uD0C0\uC77C: ${styleName}, \uD3F0\uD2B8 ${(avgFontSize / 100).toFixed(0)}pt`, category: "structure" };
    }
    return { label: "SECTION_TITLE", labelName: "\uB2E8\uC6D0 \uC81C\uBAA9", description: `\uC2A4\uD0C0\uC77C: ${styleName}, \uD3F0\uD2B8 ${(avgFontSize / 100).toFixed(0)}pt`, category: "structure" };
  }
  if (avgFontSize >= 1100 && avgFontSize < 1400 && avgParaCount <= 3 && avgTextLen < 150) {
    return { label: "SUBSECTION_TITLE", labelName: "\uC18C\uC81C\uBAA9", description: `\uC2A4\uD0C0\uC77C: ${styleName}, \uD3F0\uD2B8 ${(avgFontSize / 100).toFixed(0)}pt`, category: "structure" };
  }
  if (key.hasNumberPrefix) {
    switch (key.numberPrefixPattern) {
      case "circled":
      case "circled_korean":
        return { label: "SUB_PROBLEM", labelName: "\uC18C\uBB38\uD56D", description: `${key.numberPrefixPattern} \uBC88\uD638 \uC811\uB450\uC0AC`, category: "content" };
      case "parenthesized_arabic":
      case "parenthesized_korean":
        return { label: "CHOICES", labelName: "\uC120\uD0DD\uC9C0", description: `${key.numberPrefixPattern} \uBC88\uD638 \uC811\uB450\uC0AC`, category: "content" };
      case "arabic_dot":
        if (avgFontSize >= 1200) {
          return { label: "SECTION_TITLE", labelName: "\uB2E8\uC6D0 \uC81C\uBAA9", description: `\uC22B\uC790+\uC810 \uC811\uB450, \uD3F0\uD2B8 ${(avgFontSize / 100).toFixed(0)}pt`, category: "structure" };
        }
        return { label: "PROBLEM", labelName: "\uBB38\uC81C", description: `\uC22B\uC790+\uC810 \uC811\uB450, \uD3F0\uD2B8 ${(avgFontSize / 100).toFixed(0)}pt`, category: "content" };
      case "roman":
        return { label: "CHAPTER_TITLE", labelName: "\uB300\uB2E8\uC6D0 \uC81C\uBAA9", description: "\uB85C\uB9C8 \uC22B\uC790 \uC811\uB450", category: "structure" };
    }
  }
  if (key.hasFill && key.hasStroke) {
    if (avgTextLen > 200) {
      return { label: "CONCEPT_BOX", labelName: "\uAC1C\uB150 \uC0C1\uC790", description: "\uBC30\uACBD+\uD14C\uB450\uB9AC, \uAE34 \uD14D\uC2A4\uD2B8", category: "content" };
    }
    return { label: "INFO_BOX", labelName: "\uC815\uBCF4 \uC0C1\uC790", description: "\uBC30\uACBD+\uD14C\uB450\uB9AC \uBC15\uC2A4", category: "content" };
  }
  if (key.hasFill && !key.hasStroke) {
    if (avgTextLen < 100) {
      return { label: "TIP_BOX", labelName: "\uCC38\uACE0 \uC0C1\uC790", description: "\uBC30\uACBD\uC0C9 \uBC15\uC2A4, \uC9E7\uC740 \uD14D\uC2A4\uD2B8", category: "content" };
    }
    return { label: "HIGHLIGHT_BOX", labelName: "\uAC15\uC870 \uC0C1\uC790", description: "\uBC30\uACBD\uC0C9 \uBC15\uC2A4", category: "content" };
  }
  const equationRatio = nodes.filter((n) => n.features.hasEquation).length / nodes.length;
  if (equationRatio > 0.5) {
    return { label: "FORMULA", labelName: "\uACF5\uC2DD", description: "\uC218\uC2DD \uD3EC\uD568 \uBE14\uB85D", category: "content" };
  }
  if (avgTextLen < 80 && avgParaCount <= 2) {
    const nearFigureOrTable = nodes.filter((n) => {
      const nearby = n.features.spatial.nearestContentNodeId;
      return nearby != null;
    }).length;
    if (nearFigureOrTable > nodes.length * 0.5) {
      return { label: "CAPTION", labelName: "\uCEA1\uC158", description: "\uADF8\uB9BC/\uD45C \uC124\uBA85 \uD14D\uC2A4\uD2B8", category: "content" };
    }
  }
  if (avgTextLen > 100 && avgParaCount >= 2) {
    return { label: "BODY_TEXT", labelName: "\uBCF8\uBB38", description: `\uC2A4\uD0C0\uC77C: ${styleName}, \uD3F0\uD2B8 ${(avgFontSize / 100).toFixed(0)}pt`, category: "content" };
  }
  const firstLines = nodes.map((n) => n.features.firstLineText.trim()).filter((t) => t.length > 0);
  const commonKeyword = findCommonKeyword(firstLines);
  if (commonKeyword) {
    const safeId = commonKeyword.replace(/[^a-zA-Z가-힣0-9]/g, "_").toUpperCase();
    return { label: `LABELED_${safeId}`, labelName: commonKeyword, description: `\uACF5\uD1B5 \uD0A4\uC6CC\uB4DC: "${commonKeyword}"`, category: "content" };
  }
  if (styleName && styleName !== "[Basic Paragraph]") {
    const safeId = styleName.replace(/[^a-zA-Z0-9가-힣]/g, "_").toUpperCase();
    return { label: `STYLE_${safeId}`, labelName: styleName, description: `\uBB38\uB2E8 \uC2A4\uD0C0\uC77C: ${styleName}`, category: "content" };
  }
  return { label: "BODY_TEXT", labelName: "\uBCF8\uBB38", description: "\uC77C\uBC18 \uD14D\uC2A4\uD2B8", category: "content" };
}
function buildClusterConditions(key, nodes) {
  const conditions = [];
  if (key.dominantParagraphStyle) {
    conditions.push({ field: "dominantParagraphStyle", operator: "eq", value: key.dominantParagraphStyle });
  }
  if (key.fontSizeBucket > 0) {
    const sizes = nodes.map((n) => n.features.dominantFontSize).filter((s) => s > 0);
    if (sizes.length > 0) {
      const minSize = Math.min(...sizes);
      const maxSize = Math.max(...sizes);
      if (minSize === maxSize) {
        conditions.push({ field: "dominantFontSize", operator: "eq", value: minSize });
      } else {
        conditions.push({ field: "dominantFontSize", operator: "gte", value: minSize });
        if (maxSize - minSize > 100) {
          conditions.push({ field: "dominantFontSize", operator: "lte", value: maxSize });
        }
      }
    }
  }
  if (key.hasFill) {
    conditions.push({ field: "hasFill", operator: "eq", value: true });
  }
  if (key.hasStroke) {
    conditions.push({ field: "hasStroke", operator: "eq", value: true });
  }
  if (key.hasNumberPrefix) {
    conditions.push({ field: "hasNumberPrefix", operator: "eq", value: true });
    if (key.numberPrefixPattern) {
      conditions.push({ field: "numberPrefixPattern", operator: "eq", value: key.numberPrefixPattern });
    }
  }
  return conditions;
}
function detectHeaderFooter(nodes) {
  const pageCount = new Set(nodes.map((n) => n.features.pageNumber)).size;
  if (pageCount < 2) {
    return { headers: [], footers: [], rest: nodes };
  }
  const headers = [];
  const footers = [];
  const rest = [];
  for (const n of nodes) {
    if (n.features.textLength < 50 && n.features.regionTag === "TOP") {
      headers.push(n);
    } else if (n.features.textLength < 30 && n.features.regionTag === "BOTTOM") {
      footers.push(n);
    } else {
      rest.push(n);
    }
  }
  const headerPages = new Set(headers.map((n) => n.features.pageNumber)).size;
  const footerPages = new Set(footers.map((n) => n.features.pageNumber)).size;
  if (headerPages < pageCount * 0.4) {
    rest.push(...headers);
    headers.length = 0;
  }
  if (footerPages < pageCount * 0.4) {
    rest.push(...footers);
    footers.length = 0;
  }
  return { headers, footers, rest };
}
function makeBlockTypeGroup(blockType, nodes) {
  const isFigure = blockType === "FIGURE";
  return {
    key: blockType.toLowerCase(),
    nodes,
    label: blockType,
    labelName: isFigure ? "\uADF8\uB9BC" : "\uD45C",
    description: isFigure ? "\uC774\uBBF8\uC9C0, \uADF8\uB798\uD504 \uB4F1 \uC2DC\uAC01 \uC790\uB8CC" : "\uD45C \uBE14\uB85D",
    category: isFigure ? "media" : "content",
    color: isFigure ? "#43A047" : "#FF8F00",
    conditions: [{ field: "blockType", operator: "eq", value: blockType }],
    priority: 15,
    confidence: 0.9,
    regionTags: []
  };
}
function buildConditionsFromNodes(nodes, fields) {
  const conditions = [];
  for (const field of fields) {
    if (field === "regionTag") {
      const tags = nodes.map((n) => n.features.regionTag);
      const dominant = mode3(tags);
      if (dominant) {
        conditions.push({ field: "regionTag", operator: "eq", value: dominant });
      }
    } else if (field === "textLength_lt") {
      const maxLen = Math.max(...nodes.map((n) => n.features.textLength));
      conditions.push({ field: "textLength", operator: "lt", value: Math.ceil(maxLen * 1.2) });
    }
  }
  return conditions;
}
function computeClusterConfidence(nodes) {
  const size = nodes.length;
  if (size >= 10) return 0.85;
  if (size >= 5) return 0.8;
  if (size >= 3) return 0.75;
  return 0.7;
}
function inferRegionTags(nodes) {
  const tagCounts = /* @__PURE__ */ new Map();
  for (const n of nodes) {
    const tag = n.features.regionTag;
    tagCounts.set(tag, (tagCounts.get(tag) ?? 0) + 1);
  }
  const threshold = nodes.length * 0.7;
  const result = [];
  for (const [tag, count] of tagCounts) {
    if (count >= threshold) result.push(tag);
  }
  return result;
}
function findCommonKeyword(texts) {
  if (texts.length < 2) return null;
  const first = texts[0];
  for (let len = Math.min(first.length, 10); len >= 2; len--) {
    const prefix = first.slice(0, len).trim();
    if (prefix.length < 2) continue;
    if (texts.every((t) => t.startsWith(prefix))) {
      return prefix;
    }
  }
  return null;
}
function avg(nums) {
  if (nums.length === 0) return 0;
  return nums.reduce((a, b) => a + b, 0) / nums.length;
}
function mode3(arr) {
  if (arr.length === 0) return void 0;
  const counts = /* @__PURE__ */ new Map();
  let maxCount = 0;
  let maxVal = arr[0];
  for (const v of arr) {
    const c = (counts.get(v) ?? 0) + 1;
    counts.set(v, c);
    if (c > maxCount) {
      maxCount = c;
      maxVal = v;
    }
  }
  return maxVal;
}

// src/core/rule-validator.ts
function validateRules(groundTruth, rules) {
  const predictions = /* @__PURE__ */ new Map();
  const actuals = /* @__PURE__ */ new Map();
  for (const node of groundTruth) {
    actuals.set(node.id, node.label);
    const testNode = { ...node, manualOverride: false };
    const result = classifyNode(testNode, rules);
    predictions.set(node.id, result?.label ?? "UNKNOWN");
  }
  const allLabels = /* @__PURE__ */ new Set();
  for (const l of actuals.values()) allLabels.add(l);
  for (const l of predictions.values()) allLabels.add(l);
  allLabels.delete("UNKNOWN");
  const labelMetrics = [];
  let totalCorrect = 0;
  for (const label of allLabels) {
    let tp = 0, fp = 0, fn = 0;
    for (const nodeId of actuals.keys()) {
      const actual = actuals.get(nodeId);
      const predicted = predictions.get(nodeId);
      if (actual === label && predicted === label) tp++;
      else if (actual !== label && predicted === label) fp++;
      else if (actual === label && predicted !== label) fn++;
    }
    const precision = tp + fp > 0 ? tp / (tp + fp) : 0;
    const recall = tp + fn > 0 ? tp / (tp + fn) : 0;
    const f1 = precision + recall > 0 ? 2 * precision * recall / (precision + recall) : 0;
    labelMetrics.push({
      label,
      tp,
      fp,
      fn,
      precision: round(precision),
      recall: round(recall),
      f1: round(f1),
      support: tp + fn
    });
    totalCorrect += tp;
  }
  const ruleMetrics = [];
  for (const rule of rules) {
    const lm = labelMetrics.find((m) => m.label === rule.label);
    ruleMetrics.push({
      ruleId: rule.id,
      label: rule.label,
      tp: lm?.tp ?? 0,
      fp: lm?.fp ?? 0,
      fn: lm?.fn ?? 0,
      precision: lm?.precision ?? 0,
      recall: lm?.recall ?? 0,
      f1: lm?.f1 ?? 0
    });
  }
  const totalCount = groundTruth.length;
  const classifiedCount = [...predictions.values()].filter((l) => l !== "UNKNOWN").length;
  return {
    ruleMetrics,
    labelMetrics,
    accuracy: totalCount > 0 ? round(totalCorrect / totalCount) : 0,
    classifiedCount,
    totalCount
  };
}
function round(n) {
  return Math.round(n * 1e4) / 1e4;
}

// src/merge/node-matcher.ts
function matchNodes(oldNodes, newNodes, symmetryThreshold = 0.8) {
  const matched = [];
  const symmetryMatched = [];
  const matchedOldIds = /* @__PURE__ */ new Set();
  const matchedNewIds = /* @__PURE__ */ new Set();
  const oldFingerprints = /* @__PURE__ */ new Map();
  for (const n of oldNodes) {
    oldFingerprints.set(n.id, n);
  }
  const oldBySourceId = indexBy(oldNodes, (n) => extractSourceId(n.id));
  for (const newNode of newNodes) {
    const sourceId = extractSourceId(newNode.id);
    const oldNode = oldBySourceId.get(sourceId);
    if (oldNode && !matchedOldIds.has(oldNode.id)) {
      matched.push([oldNode.id, newNode.id]);
      matchedOldIds.add(oldNode.id);
      matchedNewIds.add(newNode.id);
    }
  }
  const remainingOld = oldNodes.filter((n) => !matchedOldIds.has(n.id));
  const remainingNew = newNodes.filter((n) => !matchedNewIds.has(n.id));
  const oldByStoryFrame = indexBy(
    remainingOld,
    (n) => n.storyId ? `${n.storyId}:${n.features.frameIndexInStory}` : ""
  );
  for (const newNode of remainingNew) {
    if (!newNode.storyId) continue;
    const key = `${newNode.storyId}:${newNode.features.frameIndexInStory}`;
    const oldNode = oldByStoryFrame.get(key);
    if (oldNode && !matchedOldIds.has(oldNode.id)) {
      matched.push([oldNode.id, newNode.id]);
      matchedOldIds.add(oldNode.id);
      matchedNewIds.add(newNode.id);
    }
  }
  const remainingOld2 = oldNodes.filter((n) => !matchedOldIds.has(n.id));
  const remainingNew2 = newNodes.filter((n) => !matchedNewIds.has(n.id));
  const oldByTextFp = indexBy(remainingOld2, (n) => computeTextFingerprint(n));
  for (const newNode of remainingNew2) {
    const fp = computeTextFingerprint(newNode);
    if (!fp) continue;
    const oldNode = oldByTextFp.get(fp);
    if (oldNode && !matchedOldIds.has(oldNode.id)) {
      matched.push([oldNode.id, newNode.id]);
      matchedOldIds.add(oldNode.id);
      matchedNewIds.add(newNode.id);
    }
  }
  const remainingOld3 = oldNodes.filter((n) => !matchedOldIds.has(n.id));
  const remainingNew3 = newNodes.filter((n) => !matchedNewIds.has(n.id));
  for (const oldNode of remainingOld3) {
    let bestNewNode = null;
    let bestScore = 0;
    for (const newNode of remainingNew3) {
      if (matchedNewIds.has(newNode.id)) continue;
      const score = computeSymmetryScore(oldNode, newNode);
      if (score > bestScore) {
        bestScore = score;
        bestNewNode = newNode;
      }
    }
    if (bestNewNode && bestScore >= symmetryThreshold) {
      matched.push([oldNode.id, bestNewNode.id]);
      symmetryMatched.push([oldNode.id, bestNewNode.id, bestScore]);
      matchedOldIds.add(oldNode.id);
      matchedNewIds.add(bestNewNode.id);
    }
  }
  return {
    matched,
    unmatchedOld: oldNodes.filter((n) => !matchedOldIds.has(n.id)).map((n) => n.id),
    unmatchedNew: newNodes.filter((n) => !matchedNewIds.has(n.id)).map((n) => n.id),
    symmetryMatched
  };
}
function createFingerprint(node) {
  return {
    sourceId: extractSourceId(node.id),
    storyId: node.storyId,
    frameIndexInStory: node.features.frameIndexInStory,
    textFingerprint: computeTextFingerprint(node),
    pageNumber: node.features.pageNumber
  };
}
function computeSymmetryScore(a, b) {
  const textSim = lcsTextSimilarity(a.features.textContent, b.features.textContent);
  const styleSim = computeStyleMatch(a, b);
  const posSim = computePositionProximity(a, b);
  return textSim * 0.6 + styleSim * 0.2 + posSim * 0.2;
}
function lcsTextSimilarity(a, b) {
  if (!a && !b) return 1;
  if (!a || !b) return 0;
  const maxLen = 200;
  const sa = a.length > maxLen * 2 ? a.slice(0, maxLen) + a.slice(-maxLen) : a;
  const sb = b.length > maxLen * 2 ? b.slice(0, maxLen) + b.slice(-maxLen) : b;
  const lcsLen = lcsLength(sa, sb);
  const maxPossible = Math.max(sa.length, sb.length);
  return maxPossible > 0 ? lcsLen / maxPossible : 1;
}
function lcsLength(a, b) {
  if (a.length > b.length) return lcsLength(b, a);
  const m = a.length;
  const n = b.length;
  const prev = new Uint16Array(m + 1);
  const curr = new Uint16Array(m + 1);
  for (let j = 1; j <= n; j++) {
    for (let i = 1; i <= m; i++) {
      if (a[i - 1] === b[j - 1]) {
        curr[i] = prev[i - 1] + 1;
      } else {
        curr[i] = Math.max(prev[i], curr[i - 1]);
      }
    }
    prev.set(curr);
    curr.fill(0);
  }
  return prev[m];
}
function computeStyleMatch(a, b) {
  let score = 0;
  let count = 0;
  if (a.features.dominantParagraphStyle || b.features.dominantParagraphStyle) {
    score += a.features.dominantParagraphStyle === b.features.dominantParagraphStyle ? 1 : 0;
    count++;
  }
  if (a.features.dominantFontSize > 0 || b.features.dominantFontSize > 0) {
    score += a.features.dominantFontSize === b.features.dominantFontSize ? 1 : 0;
    count++;
  }
  if (a.features.dominantFontFamily || b.features.dominantFontFamily) {
    score += a.features.dominantFontFamily === b.features.dominantFontFamily ? 1 : 0;
    count++;
  }
  score += a.features.hasBoldText === b.features.hasBoldText ? 1 : 0;
  count++;
  return count > 0 ? score / count : 0;
}
function computePositionProximity(a, b) {
  const samePage = a.features.pageNumber === b.features.pageNumber ? 1 : 0;
  const sameRegion = a.features.regionTag === b.features.regionTag ? 1 : 0;
  const yDiff = Math.abs(a.features.relativeYInPage - b.features.relativeYInPage);
  const ySim = Math.max(0, 1 - yDiff * 2);
  return samePage * 0.4 + sameRegion * 0.3 + ySim * 0.3;
}
function extractSourceId(nodeId) {
  return nodeId.startsWith("sn-") ? nodeId.slice(3) : nodeId;
}
function computeTextFingerprint(node) {
  const text = node.features.textContent;
  if (!text || text.length === 0) return "";
  const normalized = text.replace(/\s+/g, " ").trim();
  const key = normalized.length > 200 ? normalized.slice(0, 100) + "|" + normalized.slice(-100) : normalized;
  return simpleHash2(key);
}
function simpleHash2(str) {
  let hash = 5381;
  for (let i = 0; i < str.length; i++) {
    hash = (hash << 5) + hash + str.charCodeAt(i) & 2147483647;
  }
  return hash.toString(36);
}
function indexBy(items, keyFn) {
  const map = /* @__PURE__ */ new Map();
  for (const item of items) {
    const key = keyFn(item);
    if (key && !map.has(key)) {
      map.set(key, item);
    }
  }
  return map;
}

// src/merge/merger.ts
function mergeLayer(previous, adapter, options) {
  const newNodes = extractFeatures(adapter);
  const astHash = adapter.getDocumentHash();
  const now = (/* @__PURE__ */ new Date()).toISOString();
  if (!previous) {
    const classified2 = classifyNodes(newNodes, options.rules);
    const relations2 = buildRelations(classified2, options.relationRules);
    return {
      version: "1.0.0",
      schemaId: options.schemaId,
      sourceAstHash: astHash,
      createdAt: now,
      modifiedAt: now,
      mergeHistory: [],
      nodes: classified2,
      relations: relations2,
      deletedNodes: []
    };
  }
  const threshold = options.symmetryThreshold ?? 0.8;
  const matchResult = matchNodes(previous.nodes, newNodes, threshold);
  const mergedNodes = [];
  for (const [oldId, newId] of matchResult.matched) {
    const oldNode = previous.nodes.find((n) => n.id === oldId);
    const newNode = newNodes.find((n) => n.id === newId);
    if (oldNode.manualOverride) {
      mergedNodes.push({
        ...newNode,
        label: oldNode.label,
        confidence: oldNode.confidence,
        appliedRule: oldNode.appliedRule,
        manualOverride: true,
        children: oldNode.children,
        metadata: { ...oldNode.metadata, ...newNode.metadata }
      });
    } else {
      const isSymmetry = matchResult.symmetryMatched.some(
        ([oId, nId]) => oId === oldId && nId === newId
      );
      if (isSymmetry && oldNode.label !== "UNKNOWN") {
        mergedNodes.push({
          ...newNode,
          label: oldNode.label,
          confidence: oldNode.confidence * 0.9,
          // 약간 감소
          appliedRule: oldNode.appliedRule,
          manualOverride: false,
          metadata: { ...newNode.metadata, symmetryInherited: true }
        });
      } else {
        mergedNodes.push(newNode);
      }
    }
  }
  for (const newId of matchResult.unmatchedNew) {
    const newNode = newNodes.find((n) => n.id === newId);
    mergedNodes.push(newNode);
  }
  const newDeletedNodes = [...previous.deletedNodes];
  for (const oldId of matchResult.unmatchedOld) {
    const oldNode = previous.nodes.find((n) => n.id === oldId);
    newDeletedNodes.push({
      id: oldNode.id,
      label: oldNode.label,
      manualOverride: oldNode.manualOverride,
      deletedAt: now,
      fingerprint: createFingerprint(oldNode)
    });
  }
  const classified = classifyNodes(mergedNodes, options.rules);
  const relations = buildRelations(classified, options.relationRules);
  const historyEntry = {
    timestamp: now,
    previousHash: previous.sourceAstHash,
    stats: {
      matched: matchResult.matched.length,
      manualPreserved: matchResult.matched.filter(
        ([oldId]) => previous.nodes.find((n) => n.id === oldId)?.manualOverride
      ).length,
      reclassified: classified.filter(
        (n) => !n.manualOverride && n.label !== "UNKNOWN" && !n.metadata?.symmetryInherited
      ).length,
      added: matchResult.unmatchedNew.length,
      deleted: matchResult.unmatchedOld.length,
      symmetryMatched: matchResult.symmetryMatched.length
    }
  };
  return {
    version: "1.0.0",
    schemaId: options.schemaId,
    sourceAstHash: astHash,
    previousAstHash: previous.sourceAstHash,
    createdAt: previous.createdAt,
    modifiedAt: now,
    mergeHistory: [...previous.mergeHistory, historyEntry],
    nodes: classified,
    relations,
    deletedNodes: newDeletedNodes
  };
}

// src/ppt/ppt-style-mapper.ts
var LABEL_TO_SLOT = {
  CHAPTER_TITLE: "TITLE",
  SECTION_TITLE: "TITLE",
  SUBSECTION_TITLE: "HEADING1",
  HEADING: "HEADING2",
  BODY_TEXT: "BODY",
  PROBLEM: "BODY",
  SUB_PROBLEM: "BODY",
  EXAMPLE: "BODY",
  SOLUTION: "BODY",
  ANSWER: "BODY",
  CHOICES: "LIST_ITEM",
  CAPTION: "CAPTION",
  FORMULA: "CODE",
  PAGE_HEADER: "BODY_SMALL",
  PAGE_FOOTER: "BODY_SMALL",
  SIDEBAR: "BODY_SMALL",
  TIP_BOX: "EMPHASIS",
  CONCEPT_BOX: "EMPHASIS"
};
function hwpunitToPt(hwpunit) {
  return Math.round(hwpunit / 100);
}
function clusterStyles(nodes) {
  const entries = [];
  for (const node of nodes) {
    if (node.label === "BACKGROUND" || node.label === "DECORATION") continue;
    if (node.label === "UNKNOWN" && node.features.textLength === 0) continue;
    const slot = resolveSlot(node);
    const fontSizePt = hwpunitToPt(node.features.dominantFontSize);
    const styleName = node.features.dominantParagraphStyle ?? node.label;
    entries.push({
      slot,
      fontSizePt: fontSizePt > 0 ? fontSizePt : 12,
      fontFamily: node.features.dominantFontFamily || "\uB9D1\uC740 \uACE0\uB515",
      bold: node.features.hasBoldText,
      alignment: node.features.dominantAlignment ?? "LeftAlign",
      styleName,
      count: 1
    });
  }
  const slotGroups = /* @__PURE__ */ new Map();
  for (const entry of entries) {
    const group = slotGroups.get(entry.slot);
    if (group) group.push(entry);
    else slotGroups.set(entry.slot, [entry]);
  }
  const clusters = [];
  for (const [slot, group] of slotGroups) {
    const fontSizes = group.map((e) => e.fontSizePt);
    const repFontSize = mode4(fontSizes) ?? 12;
    const families = group.map((e) => e.fontFamily);
    const repFamily = mode4(families) ?? "\uB9D1\uC740 \uACE0\uB515";
    const boldCount = group.filter((e) => e.bold).length;
    const repBold = boldCount > group.length / 2;
    const aligns = group.map((e) => e.alignment);
    const repAlign = mode4(aligns) ?? "LeftAlign";
    const sourceStyles = [...new Set(group.map((e) => e.styleName))];
    clusters.push({
      slot,
      pptFontSize: clampFontSize(slot, repFontSize),
      pptFontFamily: mapToPptFont(repFamily),
      pptBold: repBold || isTitleSlot(slot),
      pptItalic: slot === "CAPTION",
      pptColor: getSlotColor(slot),
      pptAlignment: mapAlignment(repAlign),
      sourceStyles
    });
  }
  ensureDefaults(clusters);
  return clusters;
}
function resolveSlot(node) {
  const labelSlot = LABEL_TO_SLOT[node.label];
  if (labelSlot) return labelSlot;
  const fontPt = hwpunitToPt(node.features.dominantFontSize);
  if (fontPt >= 20) return "HEADING1";
  if (fontPt >= 14) return "BODY";
  if (fontPt < 10 && fontPt > 0) return "BODY_SMALL";
  if (node.features.hasEquation) return "CODE";
  if (node.features.hasBoldText && fontPt >= 14) return "EMPHASIS";
  return "BODY";
}
function clampFontSize(slot, pt) {
  const ranges = {
    TITLE: [24, 44],
    SUBTITLE: [18, 24],
    HEADING1: [20, 28],
    HEADING2: [16, 22],
    BODY: [10, 14],
    BODY_SMALL: [8, 10],
    LIST_ITEM: [10, 14],
    CAPTION: [8, 10],
    CODE: [10, 12],
    EMPHASIS: [10, 14]
  };
  const [min, max] = ranges[slot];
  return Math.max(min, Math.min(max, pt));
}
function isTitleSlot(slot) {
  return slot === "TITLE" || slot === "HEADING1" || slot === "HEADING2";
}
function mapToPptFont(family) {
  if (!family) return "\uB9D1\uC740 \uACE0\uB515";
  const lower = family.toLowerCase();
  if (lower.includes("\uACE0\uB515") || lower.includes("gothic")) return "\uB9D1\uC740 \uACE0\uB515";
  if (lower.includes("\uBC14\uD0D5") || lower.includes("batang")) return "\uBC14\uD0D5";
  if (lower.includes("\uB3CB\uC6C0") || lower.includes("dotum")) return "\uB3CB\uC6C0";
  if (lower.includes("\uAD74\uB9BC") || lower.includes("gulim")) return "\uAD74\uB9BC";
  if (lower.includes("arial")) return "Arial";
  if (lower.includes("times")) return "Times New Roman";
  return "\uB9D1\uC740 \uACE0\uB515";
}
function mapAlignment(align) {
  switch (align) {
    case "CenterAlign":
    case "CenterJustify":
      return "center";
    case "RightAlign":
    case "RightJustify":
      return "right";
    default:
      return "left";
  }
}
function getSlotColor(slot) {
  switch (slot) {
    case "TITLE":
      return "#333333";
    case "SUBTITLE":
      return "#555555";
    case "HEADING1":
      return "#333333";
    case "HEADING2":
      return "#444444";
    case "CAPTION":
      return "#666666";
    case "BODY_SMALL":
      return "#666666";
    case "EMPHASIS":
      return "#1565C0";
    default:
      return "#000000";
  }
}
function ensureDefaults(clusters) {
  const existing = new Set(clusters.map((c) => c.slot));
  const defaults = [
    { slot: "TITLE", pptFontSize: 36, pptFontFamily: "\uB9D1\uC740 \uACE0\uB515", pptBold: true, pptItalic: false, pptColor: "#333333", pptAlignment: "center", sourceStyles: [] },
    { slot: "BODY", pptFontSize: 12, pptFontFamily: "\uB9D1\uC740 \uACE0\uB515", pptBold: false, pptItalic: false, pptColor: "#000000", pptAlignment: "left", sourceStyles: [] }
  ];
  for (const d of defaults) {
    if (!existing.has(d.slot)) {
      clusters.push(d);
    }
  }
}
function mode4(arr) {
  if (arr.length === 0) return void 0;
  const counts = /* @__PURE__ */ new Map();
  let maxCount = 0;
  let maxVal = arr[0];
  for (const v of arr) {
    const c = (counts.get(v) ?? 0) + 1;
    counts.set(v, c);
    if (c > maxCount) {
      maxCount = c;
      maxVal = v;
    }
  }
  return maxVal;
}

// src/ppt/slide-breaker.ts
var SLIDE_BREAK_LABELS = /* @__PURE__ */ new Set([
  "CHAPTER_TITLE",
  "SECTION_TITLE",
  "SUBSECTION_TITLE"
]);
var STANDALONE_LABELS = /* @__PURE__ */ new Set([
  "CONCEPT_BOX",
  "TABLE"
]);
var PROBLEM_GROUP_LABELS = /* @__PURE__ */ new Set([
  "PROBLEM",
  "SUB_PROBLEM",
  "CHOICES",
  "SOLUTION",
  "ANSWER"
]);
var MAX_SLIDE_TEXT_LENGTH = 800;
function breakIntoSlides(nodes, relations) {
  const slides = [];
  const contentNodes = nodes.filter(
    (n) => !["BACKGROUND", "DECORATION", "PAGE_HEADER", "PAGE_FOOTER", "UNKNOWN"].includes(n.label) && n.features.textLength > 0
  );
  contentNodes.sort((a, b) => {
    if (a.features.pageNumber !== b.features.pageNumber) {
      return a.features.pageNumber - b.features.pageNumber;
    }
    return a.features.y - b.features.y;
  });
  const childToParent = /* @__PURE__ */ new Map();
  for (const r of relations) {
    if (r.type === "PARENT_OF") {
      childToParent.set(r.targetId, r.sourceId);
    }
  }
  let currentSlide = [];
  let currentType = "CONTENT_SLIDE";
  let currentTextLen = 0;
  const processedIds = /* @__PURE__ */ new Set();
  function flushSlide() {
    if (currentSlide.length === 0) return;
    slides.push({
      index: slides.length,
      type: currentType,
      nodes: [...currentSlide],
      title: extractTitle(currentSlide)
    });
    currentSlide = [];
    currentType = "CONTENT_SLIDE";
    currentTextLen = 0;
  }
  for (const node of contentNodes) {
    if (processedIds.has(node.id)) continue;
    if (childToParent.has(node.id)) continue;
    if (SLIDE_BREAK_LABELS.has(node.label)) {
      flushSlide();
      currentType = "TITLE_SLIDE";
      currentSlide.push(node);
      processedIds.add(node.id);
      flushSlide();
      continue;
    }
    if (STANDALONE_LABELS.has(node.label)) {
      flushSlide();
      currentType = node.label === "TABLE" ? "TABLE_SLIDE" : "CONCEPT_SLIDE";
      currentSlide.push(node);
      processedIds.add(node.id);
      addRelatedCaptions(node, contentNodes, relations, currentSlide, processedIds);
      flushSlide();
      continue;
    }
    if (node.label === "FIGURE" || node.nodeType === "FIGURE") {
      flushSlide();
      currentType = "FIGURE_SLIDE";
      currentSlide.push(node);
      processedIds.add(node.id);
      addRelatedCaptions(node, contentNodes, relations, currentSlide, processedIds);
      flushSlide();
      continue;
    }
    if (node.label === "PROBLEM") {
      flushSlide();
      currentType = "PROBLEM_SLIDE";
      currentSlide.push(node);
      processedIds.add(node.id);
      collectProblemGroup(node, contentNodes, relations, currentSlide, processedIds);
      flushSlide();
      continue;
    }
    if (currentTextLen + node.features.textLength > MAX_SLIDE_TEXT_LENGTH && currentSlide.length > 0) {
      flushSlide();
    }
    currentSlide.push(node);
    processedIds.add(node.id);
    currentTextLen += node.features.textLength;
  }
  flushSlide();
  return slides;
}
function collectProblemGroup(problem, allNodes, relations, slide, processed) {
  const childIds = /* @__PURE__ */ new Set();
  for (const r of relations) {
    if (r.type === "PARENT_OF" && r.sourceId === problem.id) {
      childIds.add(r.targetId);
    }
  }
  for (const node of allNodes) {
    if (processed.has(node.id)) continue;
    if (!PROBLEM_GROUP_LABELS.has(node.label)) continue;
    if (childIds.has(node.id) || node.features.pageNumber === problem.features.pageNumber && node.features.y > problem.features.y) {
      slide.push(node);
      processed.add(node.id);
    }
  }
}
function addRelatedCaptions(target, allNodes, relations, slide, processed) {
  for (const r of relations) {
    if (r.type === "CAPTION_FOR" && r.targetId === target.id) {
      const caption = allNodes.find((n) => n.id === r.sourceId);
      if (caption && !processed.has(caption.id)) {
        slide.push(caption);
        processed.add(caption.id);
      }
    }
  }
}
function extractTitle(nodes) {
  const titleNode = nodes.find(
    (n) => ["CHAPTER_TITLE", "SECTION_TITLE", "SUBSECTION_TITLE"].includes(n.label)
  );
  if (titleNode) return titleNode.features.firstLineText || titleNode.features.textContent.slice(0, 50);
  const first = nodes[0];
  if (!first) return "";
  return first.features.firstLineText || first.label;
}

// src/ppt/slide-layout.ts
var LABEL_TO_STYLE = {
  CHAPTER_TITLE: "TITLE",
  SECTION_TITLE: "TITLE",
  SUBSECTION_TITLE: "HEADING1",
  BODY_TEXT: "BODY",
  PROBLEM: "BODY",
  SUB_PROBLEM: "BODY",
  EXAMPLE: "BODY",
  SOLUTION: "BODY",
  ANSWER: "BODY",
  CHOICES: "LIST_ITEM",
  CAPTION: "CAPTION",
  FORMULA: "CODE",
  CONCEPT_BOX: "EMPHASIS",
  TIP_BOX: "EMPHASIS",
  SIDEBAR: "BODY_SMALL",
  TABLE: "BODY",
  FIGURE: "BODY"
};
var MARGIN = { top: 0.8, bottom: 0.5, left: 0.7, right: 0.7 };
function layoutSlides(slides, slideSize = { width: 10, height: 7.5 }) {
  return slides.map((slide) => ({
    index: slide.index,
    type: slide.type,
    title: slide.title,
    elements: layoutSlide(slide, slideSize)
  }));
}
function layoutSlide(slide, size) {
  switch (slide.type) {
    case "TITLE_SLIDE":
      return layoutTitleSlide(slide, size);
    case "PROBLEM_SLIDE":
      return layoutProblemSlide(slide, size);
    case "FIGURE_SLIDE":
      return layoutFigureSlide(slide, size);
    default:
      return layoutContentSlide(slide, size);
  }
}
function layoutTitleSlide(slide, size) {
  const titleNode = slide.nodes[0];
  if (!titleNode) return [];
  return [{
    nodeId: titleNode.id,
    label: titleNode.label,
    x: size.width * 0.1,
    y: size.height * 0.3,
    w: size.width * 0.8,
    h: size.height * 0.4,
    text: titleNode.features.textContent,
    styleSlot: "TITLE",
    elementType: "text"
  }];
}
function layoutProblemSlide(slide, size) {
  const elements = [];
  const contentW = size.width - MARGIN.left - MARGIN.right;
  let y = MARGIN.top;
  const problem = slide.nodes.find((n) => n.label === "PROBLEM");
  const others = slide.nodes.filter((n) => n.label !== "PROBLEM");
  if (problem) {
    const h = estimateHeight(problem, contentW, size.height);
    elements.push({
      nodeId: problem.id,
      label: problem.label,
      x: MARGIN.left,
      y,
      w: contentW,
      h,
      text: problem.features.textContent,
      styleSlot: "BODY",
      elementType: "text"
    });
    y += h + 0.2;
  }
  for (const node of others) {
    const h = estimateHeight(node, contentW, size.height);
    if (y + h > size.height - MARGIN.bottom) break;
    elements.push({
      nodeId: node.id,
      label: node.label,
      x: MARGIN.left + (node.label === "SUB_PROBLEM" ? 0.3 : 0),
      y,
      w: contentW - (node.label === "SUB_PROBLEM" ? 0.3 : 0),
      h,
      text: node.features.textContent,
      styleSlot: LABEL_TO_STYLE[node.label] ?? "BODY",
      elementType: "text"
    });
    y += h + 0.15;
  }
  return elements;
}
function layoutFigureSlide(slide, size) {
  const elements = [];
  const contentW = size.width - MARGIN.left - MARGIN.right;
  const figure = slide.nodes.find((n) => n.label === "FIGURE" || n.nodeType === "FIGURE");
  const caption = slide.nodes.find((n) => n.label === "CAPTION");
  if (figure) {
    const figH = caption ? size.height * 0.6 : size.height * 0.7;
    elements.push({
      nodeId: figure.id,
      label: figure.label,
      x: size.width * 0.15,
      y: MARGIN.top,
      w: size.width * 0.7,
      h: figH,
      text: "",
      styleSlot: "BODY",
      elementType: "image"
    });
    if (caption) {
      elements.push({
        nodeId: caption.id,
        label: caption.label,
        x: MARGIN.left,
        y: MARGIN.top + figH + 0.2,
        w: contentW,
        h: 0.5,
        text: caption.features.textContent,
        styleSlot: "CAPTION",
        elementType: "text"
      });
    }
  }
  return elements;
}
function layoutContentSlide(slide, size) {
  const elements = [];
  const contentW = size.width - MARGIN.left - MARGIN.right;
  let y = MARGIN.top;
  for (const node of slide.nodes) {
    const h = estimateHeight(node, contentW, size.height);
    if (y + h > size.height - MARGIN.bottom && elements.length > 0) break;
    elements.push({
      nodeId: node.id,
      label: node.label,
      x: MARGIN.left,
      y,
      w: contentW,
      h,
      text: node.features.textContent,
      styleSlot: LABEL_TO_STYLE[node.label] ?? "BODY",
      elementType: node.nodeType === "TABLE" ? "table" : "text"
    });
    y += h + 0.15;
  }
  return elements;
}
function estimateHeight(node, widthInch, maxHeight) {
  const charsPerLine = Math.floor(widthInch * 7);
  const lines = Math.max(1, Math.ceil(node.features.textLength / charsPerLine));
  const lineHeight = 0.25;
  return Math.min(lines * lineHeight, maxHeight * 0.4);
}

// src/ppt/ppt-template.ts
var BUILT_IN_TEMPLATES = [
  {
    templateId: "title-only",
    name: "\uC81C\uBAA9 \uC2AC\uB77C\uC774\uB4DC",
    matchType: "TITLE_SLIDE",
    layout: {
      TITLE: { x: "10%", y: "30%", w: "80%", h: "40%" }
    },
    slideSize: { width: 10, height: 7.5 }
  },
  {
    templateId: "problem-with-choices",
    name: "\uBB38\uC81C + \uBCF4\uAE30",
    matchType: "PROBLEM_SLIDE",
    matchLabels: ["PROBLEM", "CHOICES"],
    layout: {
      PROBLEM: { x: "5%", y: "10%", w: "90%", h: "40%" },
      CHOICES: { x: "5%", y: "55%", w: "90%", h: "40%" }
    },
    slideSize: { width: 10, height: 7.5 }
  },
  {
    templateId: "figure-with-caption",
    name: "\uADF8\uB9BC + \uCEA1\uC158",
    matchType: "FIGURE_SLIDE",
    matchLabels: ["FIGURE", "CAPTION"],
    layout: {
      FIGURE: { x: "10%", y: "8%", w: "80%", h: "65%" },
      CAPTION: { x: "10%", y: "78%", w: "80%", h: "10%" }
    },
    slideSize: { width: 10, height: 7.5 }
  },
  {
    templateId: "content-basic",
    name: "\uAE30\uBCF8 \uCF58\uD150\uCE20",
    matchType: "CONTENT_SLIDE",
    layout: {
      DEFAULT: { x: "5%", y: "10%", w: "90%", h: "85%" }
    },
    slideSize: { width: 10, height: 7.5 }
  },
  {
    templateId: "concept-box",
    name: "\uAC1C\uB150 \uBC15\uC2A4",
    matchType: "CONCEPT_SLIDE",
    layout: {
      CONCEPT_BOX: { x: "8%", y: "10%", w: "84%", h: "80%" }
    },
    slideSize: { width: 10, height: 7.5 }
  },
  {
    templateId: "table-slide",
    name: "\uD45C",
    matchType: "TABLE_SLIDE",
    layout: {
      TABLE: { x: "5%", y: "10%", w: "90%", h: "80%" },
      CAPTION: { x: "5%", y: "92%", w: "90%", h: "6%" }
    },
    slideSize: { width: 10, height: 7.5 }
  }
];
function matchTemplate(slideType, labels, customTemplates = []) {
  const allTemplates = [...customTemplates, ...BUILT_IN_TEMPLATES];
  for (const t of allTemplates) {
    if (t.matchType !== slideType) continue;
    if (t.matchLabels) {
      const labelSet = new Set(labels);
      if (t.matchLabels.every((l) => labelSet.has(l))) {
        return t;
      }
    }
  }
  for (const t of allTemplates) {
    if (t.matchType === slideType && !t.matchLabels) {
      return t;
    }
  }
  return BUILT_IN_TEMPLATES.find((t) => t.templateId === "content-basic");
}

// src/ppt/ppt-renderer.ts
import PptxGenJS from "pptxgenjs";
async function renderPptx(slides, styleClusters, options = {}) {
  const pptx = new PptxGenJS();
  pptx.author = options.author ?? "Semantic Layer";
  pptx.title = options.title ?? "\uC2DC\uBA58\uD2F1 \uB808\uC774\uC5B4 \uB0B4\uBCF4\uB0B4\uAE30";
  pptx.subject = options.subject ?? "";
  const size = options.slideSize ?? { width: 10, height: 7.5 };
  pptx.defineLayout({ name: "CUSTOM", width: size.width, height: size.height });
  pptx.layout = "CUSTOM";
  const styleMap = /* @__PURE__ */ new Map();
  for (const c of styleClusters) {
    styleMap.set(c.slot, c);
  }
  for (const layoutedSlide of slides) {
    const slide = pptx.addSlide();
    if (layoutedSlide.type === "TITLE_SLIDE") {
      slide.background = { color: "F5F5F5" };
    }
    for (const element of layoutedSlide.elements) {
      renderElement(slide, element, styleMap);
    }
  }
  const output = await pptx.write({ outputType: "arraybuffer" });
  return output;
}
function renderElement(slide, element, styleMap) {
  const style = styleMap.get(element.styleSlot) ?? getDefaultStyle(element.styleSlot);
  switch (element.elementType) {
    case "text":
      renderTextElement(slide, element, style);
      break;
    case "image":
      renderImagePlaceholder(slide, element);
      break;
    case "table":
      renderTablePlaceholder(slide, element, style);
      break;
    case "shape":
      renderShape(slide, element, style);
      break;
  }
}
function renderTextElement(slide, element, style) {
  const text = element.text || element.label;
  slide.addText(text, {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fontSize: style.pptFontSize,
    fontFace: style.pptFontFamily,
    bold: style.pptBold,
    italic: style.pptItalic,
    color: style.pptColor.replace("#", ""),
    align: style.pptAlignment,
    valign: element.styleSlot === "TITLE" ? "middle" : "top",
    wrap: true,
    shrinkText: true
  });
}
function renderImagePlaceholder(slide, element) {
  slide.addShape("rect", {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fill: { color: "F0F0F0" },
    line: { color: "CCCCCC", width: 1 }
  });
  slide.addText("[Image]", {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fontSize: 10,
    color: "999999",
    align: "center",
    valign: "middle"
  });
}
function renderTablePlaceholder(slide, element, style) {
  slide.addText(element.text || "[Table]", {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fontSize: style.pptFontSize,
    fontFace: style.pptFontFamily,
    color: style.pptColor.replace("#", ""),
    fill: { color: "FAFAFA" },
    line: { color: "DDDDDD", width: 0.5 },
    wrap: true
  });
}
function renderShape(slide, element, style) {
  slide.addShape("rect", {
    x: element.x,
    y: element.y,
    w: element.w,
    h: element.h,
    fill: { color: "E8EAF6" },
    line: { color: "7986CB", width: 1 }
  });
  if (element.text) {
    slide.addText(element.text, {
      x: element.x,
      y: element.y,
      w: element.w,
      h: element.h,
      fontSize: style.pptFontSize,
      fontFace: style.pptFontFamily,
      wrap: true
    });
  }
}
function getDefaultStyle(slot) {
  return {
    slot,
    pptFontSize: 12,
    pptFontFamily: "\uB9D1\uC740 \uACE0\uB515",
    pptBold: false,
    pptItalic: false,
    pptColor: "#000000",
    pptAlignment: "left",
    sourceStyles: []
  };
}
export {
  ASTJsonAdapter,
  BUILT_IN_TEMPLATES,
  SchemaLoader,
  breakIntoSlides,
  buildRelations,
  classifyNode,
  classifyNodes,
  clusterStyles,
  computeSymmetryScore,
  createFingerprint,
  evaluateConditions,
  extractFeatures,
  generateSchema,
  layoutSlides,
  matchNodes,
  matchTemplate,
  mergeLayer,
  renderPptx,
  suggestRules,
  validateRules
};
