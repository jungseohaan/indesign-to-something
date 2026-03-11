/**
 * ASTJsonAdapter — AST JSON 구조를 아는 유일한 파일.
 *
 * Java ASTSerializer.toJson() 출력을 ASTAdapter 인터페이스로 변환.
 * AST 필드명/구조가 변경되면 이 파일만 수정한다.
 */

import type { ASTAdapter } from './ast-adapter.js'
import type {
  PageInfo,
  BlockInfo,
  BlockType,
  ParagraphInfo,
  InlineItemInfo,
  InlineItemType,
  InlineObjectKind,
  StoryInfo,
  StyleInfo,
  ImageInfo,
  TableInfo,
  TableRowInfo,
  TableCellInfo,
  FontInfo,
  ColorMap,
} from './types.js'

/* eslint-disable @typescript-eslint/no-explicit-any */

export class ASTJsonAdapter implements ASTAdapter {
  private readonly json: any
  private readonly blockIndex: Map<string, { section: any; block: any; pageNumber: number }>
  private readonly storyIndex: Map<string, any>
  private readonly styleIndex: Map<string, StyleInfo>
  private _hash: string | null = null

  constructor(astJson: string | object) {
    this.json = typeof astJson === 'string' ? JSON.parse(astJson) : astJson
    this.blockIndex = new Map()
    this.storyIndex = new Map()
    this.styleIndex = new Map()
    this.buildIndices()
  }

  // ─── 인덱스 구축 ────────────────────────────────────────

  private buildIndices(): void {
    // 블록 인덱스
    for (const section of this.json.sections ?? []) {
      const pageNumber = section.pageNumber ?? 0
      for (const block of section.blocks ?? []) {
        const id = block.sourceId
        if (id) {
          this.blockIndex.set(id, { section, block, pageNumber })
        }
      }
    }

    // 스토리 인덱스
    for (const story of this.json.stories ?? []) {
      if (story.storyId) {
        this.storyIndex.set(story.storyId, story)
      }
    }

    // 스타일 인덱스
    for (const style of this.json.paragraphStyles ?? []) {
      this.styleIndex.set(style.styleId, this.mapStyle(style, 'paragraph'))
    }
    for (const style of this.json.characterStyles ?? []) {
      this.styleIndex.set(style.styleId, this.mapStyle(style, 'character'))
    }
  }

  // ─── 문서 구조 ──────────────────────────────────────────

  getPages(): PageInfo[] {
    return (this.json.sections ?? []).map((s: any) => {
      const layout = s.layout ?? {}
      return {
        pageNumber: s.pageNumber ?? 0,
        width: layout.pageWidth ?? 0,
        height: layout.pageHeight ?? 0,
        marginTop: layout.marginTop ?? 0,
        marginBottom: layout.marginBottom ?? 0,
        marginLeft: layout.marginLeft ?? 0,
        marginRight: layout.marginRight ?? 0,
        columnCount: layout.columnCount ?? 1,
        columnGutter: layout.columnGutter ?? 0,
      } satisfies PageInfo
    })
  }

  getBlocks(pageNumber: number): BlockInfo[] {
    const section = (this.json.sections ?? []).find(
      (s: any) => s.pageNumber === pageNumber
    )
    if (!section) return []

    return (section.blocks ?? []).map((b: any) =>
      this.mapBlock(b, pageNumber)
    )
  }

  getParagraphs(blockId: string): ParagraphInfo[] {
    const entry = this.blockIndex.get(blockId)
    if (!entry) return []

    const block = entry.block
    const paragraphs: any[] = block.paragraphs ?? []
    return paragraphs.map((p: any, i: number) => this.mapParagraph(p, i))
  }

  // ─── 스토리 ─────────────────────────────────────────────

  getStories(): StoryInfo[] {
    return (this.json.stories ?? []).map((s: any) => this.mapStory(s))
  }

  getStory(storyId: string): StoryInfo | null {
    const raw = this.storyIndex.get(storyId)
    return raw ? this.mapStory(raw) : null
  }

  getFrameIdsForStory(storyId: string): string[] {
    const raw = this.storyIndex.get(storyId)
    return raw?.linkedFrameIds ?? []
  }

  // ─── 스타일 ─────────────────────────────────────────────

  getParagraphStyles(): StyleInfo[] {
    return (this.json.paragraphStyles ?? []).map((s: any) =>
      this.mapStyle(s, 'paragraph')
    )
  }

  getCharacterStyles(): StyleInfo[] {
    return (this.json.characterStyles ?? []).map((s: any) =>
      this.mapStyle(s, 'character')
    )
  }

  getStyleByRef(ref: string): StyleInfo | null {
    return this.styleIndex.get(ref) ?? null
  }

  // ─── 미디어 ─────────────────────────────────────────────

  getImageInfo(blockId: string): ImageInfo | null {
    const entry = this.blockIndex.get(blockId)
    if (!entry) return null
    const b = entry.block

    if (b.blockType === 'FIGURE' && (b.kind === 'IMAGE' || b.imageFormat)) {
      return {
        format: b.imageFormat ?? null,
        imagePath: b.imagePath ?? null,
        bundlePath: b.bundlePath ?? null,
        pixelWidth: b.pixelWidth ?? 0,
        pixelHeight: b.pixelHeight ?? 0,
        hasImageData: b.hasImageData ?? false,
      }
    }
    return null
  }

  getTableInfo(blockId: string): TableInfo | null {
    const entry = this.blockIndex.get(blockId)
    if (!entry) return null
    const b = entry.block

    if (b.blockType !== 'TABLE') return null

    return {
      id: b.sourceId ?? blockId,
      rowCount: b.rowCount ?? 0,
      colCount: b.colCount ?? 0,
      columnWidths: b.columnWidths ?? [],
      borderColor: b.borderColor ?? null,
      borderWidth: b.borderWidth ?? 0,
      rows: (b.rows ?? []).map((r: any) => this.mapTableRow(r)),
    }
  }

  // ─── 폰트 ──────────────────────────────────────────────

  getFonts(): FontInfo[] {
    return (this.json.fonts ?? []).map((f: any) => ({
      fontId: f.fontId ?? '',
      fontFamily: f.fontFamily ?? null,
      fontType: f.fontType ?? null,
    }))
  }

  // ─── 색상 ──────────────────────────────────────────────

  getColors(): ColorMap {
    return this.json.colors ?? {}
  }

  getColorHex(colorRef: string): string | null {
    const colors = this.json.colors ?? {}
    return colors[colorRef] ?? null
  }

  // ─── 메타 ──────────────────────────────────────────────

  getSourceFile(): string | null {
    return this.json.sourceFile ?? null
  }

  getDocumentHash(): string {
    if (!this._hash) {
      this._hash = simpleHash(JSON.stringify(this.json))
    }
    return this._hash
  }

  // ─── 매핑 헬퍼 ─────────────────────────────────────────

  private mapBlock(b: any, pageNumber: number): BlockInfo {
    const blockType = this.mapBlockType(b.blockType)
    const isTextFrame = blockType === 'TEXT_FRAME'

    return {
      id: b.sourceId ?? '',
      blockType,
      pageNumber,
      x: b.x ?? 0,
      y: b.y ?? 0,
      width: b.width ?? 0,
      height: b.height ?? 0,
      zOrder: b.zOrder ?? 0,
      rotation: b.rotationAngle ?? 0,
      storyId: isTextFrame ? (b.storyId ?? null) : null,
      columnCount: isTextFrame ? (b.columnCount ?? 1) : 0,
      fillColor: b.fillColor ?? null,
      strokeColor: b.strokeColor ?? null,
      hasFill: b.fillColor != null,
      hasStroke: b.strokeColor != null,
      isBackgroundOnly: this.detectBackgroundOnly(b),
      verticalJustification: isTextFrame ? (b.verticalJustification ?? null) : null,
    }
  }

  private mapBlockType(raw: string | undefined): BlockType {
    switch (raw) {
      case 'TEXT_FRAME_BLOCK': return 'TEXT_FRAME'
      case 'TABLE': return 'TABLE'
      case 'FIGURE': return 'FIGURE'
      default: return 'TEXT_FRAME'
    }
  }

  private detectBackgroundOnly(b: any): boolean {
    // 텍스트가 없고 배경색만 있는 프레임
    if (b.blockType !== 'TEXT_FRAME_BLOCK') return false
    const paras: any[] = b.paragraphs ?? []
    if (paras.length === 0 && b.fillColor) return true
    // 단일 빈 문단 + 배경색
    if (paras.length === 1 && b.fillColor) {
      const items: any[] = paras[0].items ?? []
      if (items.length === 0) return true
      if (items.length === 1 && items[0].itemType === 'TEXT_RUN') {
        const text = items[0].text ?? ''
        if (text.trim() === '') return true
      }
    }
    return false
  }

  private mapParagraph(p: any, index: number): ParagraphInfo {
    return {
      index,
      alignment: p.alignment ?? null,
      paragraphStyleRef: p.paragraphStyleRef ?? null,
      firstLineIndent: p.firstLineIndent ?? null,
      spaceBefore: p.spaceBefore ?? null,
      spaceAfter: p.spaceAfter ?? null,
      items: (p.items ?? []).map((item: any) => this.mapInlineItem(item)),
    }
  }

  private mapInlineItem(item: any): InlineItemInfo {
    const itemType = (item.itemType ?? 'TEXT_RUN') as InlineItemType
    const result: InlineItemInfo = { itemType }

    switch (itemType) {
      case 'TEXT_RUN':
        result.text = item.text ?? undefined
        result.fontFamily = item.fontFamily ?? undefined
        result.fontStyle = item.fontStyle ?? undefined
        result.fontSize = item.fontSizeHwpunits ?? undefined
        result.textColor = item.textColor ?? undefined
        result.bold = item.fontStyle?.includes('Bold') ? true : undefined
        result.underline = item.underline ?? undefined
        result.strikeThrough = item.strikeThrough ?? undefined
        result.subscript = item.subscript ?? undefined
        result.superscript = item.superscript ?? undefined
        result.characterStyleRef = item.characterStyleRef ?? undefined
        break

      case 'INLINE_OBJECT':
        result.objectKind = (item.kind ?? undefined) as InlineObjectKind | undefined
        result.objectSourceId = item.sourceId ?? undefined
        result.objectWidth = item.width ?? undefined
        result.objectHeight = item.height ?? undefined
        break

      case 'EQUATION':
        result.equationScript = item.hwpScript ?? undefined
        result.equationSourceType = item.sourceType ?? undefined
        result.equationColor = item.textColor ?? undefined
        break

      case 'BREAK':
        result.breakType = item.breakType ?? undefined
        break
    }

    return result
  }

  private mapStory(s: any): StoryInfo {
    return {
      storyId: s.storyId ?? '',
      orientation: s.orientation ?? null,
      linkedFrameIds: s.linkedFrameIds ?? [],
      pages: s.pages ?? [],
      paragraphCount: s.paragraphCount ?? 0,
      tableCount: s.tableCount ?? 0,
    }
  }

  private mapStyle(s: any, type: 'paragraph' | 'character'): StyleInfo {
    return {
      styleId: s.styleId ?? '',
      styleName: s.styleName ?? null,
      type,
      basedOnStyleRef: s.basedOnStyleRef ?? null,
      fontFamily: s.fontFamily ?? undefined,
      fontStyle: s.fontStyle ?? undefined,
      fontSize: s.fontSizeHwpunits ?? undefined,
      bold: s.bold ?? (s.fontStyle?.includes('Bold') ? true : undefined),
      italic: s.italic ?? (s.fontStyle?.includes('Italic') ? true : undefined),
      alignment: s.alignment ?? undefined,
      textColor: s.textColor ?? undefined,
    }
  }

  private mapTableRow(r: any): TableRowInfo {
    return {
      rowIndex: r.rowIndex ?? 0,
      rowHeight: r.rowHeight ?? 0,
      cells: (r.cells ?? []).map((c: any) => this.mapTableCell(c)),
    }
  }

  private mapTableCell(c: any): TableCellInfo {
    return {
      rowIndex: c.rowIndex ?? 0,
      columnIndex: c.columnIndex ?? 0,
      rowSpan: c.rowSpan ?? 1,
      columnSpan: c.columnSpan ?? 1,
      fillColor: c.fillColor ?? null,
      paragraphs: (c.paragraphs ?? []).map((p: any, i: number) =>
        this.mapParagraph(p, i)
      ),
    }
  }
}

// ─── 유틸 ──────────────────────────────────────────────

/** 간단한 문자열 해시 (djb2) */
function simpleHash(str: string): string {
  let hash = 5381
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) + hash + str.charCodeAt(i)) >>> 0
  }
  return hash.toString(16)
}
