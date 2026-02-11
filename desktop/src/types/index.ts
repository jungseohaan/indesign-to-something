export interface IDMLStructure {
  spreads: SpreadInfo[];
  master_spreads: MasterSpreadInfo[];
  total_text_frames: number;
  total_image_frames: number;
  total_vector_shapes: number;
  total_tables: number;
}

export interface MasterSpreadInfo {
  id: string;
  name: string;
  page_count: number;
  text_frame_count: number;
  image_frame_count: number;
  vector_count: number;
  group_count: number;
  applied_pages: string[];
}

export interface SpreadInfo {
  id: string;
  page_count: number;
  pages: PageInfo[];
  text_frame_count: number;
  image_frame_count: number;
  vector_count: number;
  master_spread_name: string | null;
  // 스프레드 레이아웃 상세 정보
  bounds_top: number;
  bounds_left: number;
  bounds_bottom: number;
  bounds_right: number;
  total_width: number;
  total_height: number;
}

export interface PageInfo {
  id: string;
  name: string;
  page_number: number;
  width: number;
  height: number;
  frames: FrameInfo[];
  // 페이지 레이아웃 상세 정보
  geometric_bounds: number[] | null;  // [top, left, bottom, right]
  item_transform: number[] | null;    // 6요소 변환 행렬
  margin_top: number;
  margin_bottom: number;
  margin_left: number;
  margin_right: number;
  column_count: number;
  master_spread: string | null;
}

export interface FrameInfo {
  id: string;
  type: "text" | "image" | "vector" | "table";
  label: string;
  x: number;
  y: number;
  width: number;
  height: number;
  // 이미지 타입에만 있는 추가 필드
  link_path?: string;
  needs_preview?: boolean; // PSD, AI, EPS 여부
  // 인라인 자식 프레임 (텍스트 타입만)
  children?: FrameInfo[];
  // 스토리 내용 요약 (텍스트 타입만)
  story_content?: StoryContentInfo;
}

export interface StoryContentInfo {
  story_id: string;
  paragraph_count: number;
  truncated: boolean;
  paragraphs: ParagraphSummaryItem[];
}

export interface ParagraphSummaryItem {
  index: number;
  style_name: string | null;
  runs: RunSummaryItem[];
}

export interface RunSummaryItem {
  type: "text" | "inline_frame" | "inline_graphic";
  text?: string;
  font_style?: string;
  font_size?: number;
  frame_id?: string;
  graphic_type?: string;
  width?: number;
  height?: number;
}

export interface ImagePreview {
  original_path: string;
  data_url: string;  // base64 data URL
  filename: string;
  width: number;
  height: number;
}

export interface ConvertOptions {
  spread_based: boolean;
  vector_dpi: number;
  include_images: boolean;
  links_directory: string | null;
  start_page: number | null;
  end_page: number | null;
}

export interface ConvertResult {
  pages_converted: number;
  frames_converted: number;
  images_converted: number;
  warnings: string[];
}

export interface ProgressEvent {
  current: number;
  total: number;
  message: string;
}

export interface LogEvent {
  message: string;
  timestamp: number;
}

export type TreeNode = {
  id: string;
  name: string;
  type: "spread" | "page" | "text" | "image" | "vector" | "table";
  children?: TreeNode[];
};

// Text Frame Detail Types
export interface TextFrameDetail {
  frame_id: string;
  story_id: string;
  frame_properties: FrameProperties;
  paragraphs: ParagraphInfo[];
}

export interface FrameProperties {
  fill_color: string | null;
  stroke_color: string | null;
  stroke_weight: number;
  corner_radius: number;
  corner_radii: number[] | null;  // [topLeft, topRight, bottomLeft, bottomRight]
  fill_tint: number;   // 0~100, 100=opaque
  stroke_tint: number;
  width: number;
  height: number;
}

export interface ParagraphInfo {
  style_name: string;
  style_ref: string;
  style: ParagraphStyle;
  runs: CharacterRun[];
  text: string;
}

export interface ParagraphStyle {
  font_family: string | null;
  font_size: number | null;
  text_alignment: string | null;
  first_line_indent: number | null;
  left_indent: number | null;
  space_before: number | null;
  space_after: number | null;
  leading: number | null;
}

export interface CharacterRun {
  text: string;
  char_style: string | null;
  font_family: string | null;
  font_size: number | null;
  font_style: string | null;
  fill_color: string | null;
  anchors: string[];
}

// Playground Types
export interface CreateIdmlResult {
  success: boolean;
  master_count: number;
  page_size: { width: number; height: number };
  warnings?: string[];
  validation?: {
    valid: boolean;
    errors: string[];
    warnings: string[];
  };
}
