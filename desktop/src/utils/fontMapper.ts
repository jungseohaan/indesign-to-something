/**
 * FontMapper.java의 TypeScript 포팅.
 * IDML 폰트명 → HWPX 폰트명 디폴트 매핑.
 */

/** 한글 폰트 매핑: 부분 매치 (순서 중요 — 구체적 → 일반적) */
const KOREAN_FONT_MAP: [string, string][] = [
  // 윤디자인
  ["윤명조", "함초롬바탕"],
  ["윤고딕", "함초롬돋움"],
  // Sandoll
  ["Sandoll 명조", "함초롬바탕"],
  ["Sandoll 고딕", "함초롬돋움"],
  ["Sandoll 안단테", "함초롬바탕"],
  ["Sandoll 제비", "함초롬돋움"],
  ["Sandoll 고고", "함초롬돋움"],
  // Rix
  ["Rix정고딕", "함초롬돋움"],
  ["Rix착한아이", "함초롬돋움"],
  ["Rix개봉박두", "함초롬돋움"],
  ["Rix", "함초롬돋움"],
  // 210 시리즈
  ["210 나무굴림", "함초롬돋움"],
  ["210 나무젓가락", "함초롬돋움"],
  ["210 네모진", "함초롬돋움"],
  ["210 데이라잇", "함초롬돋움"],
  ["210 딱지치기", "함초롬돋움"],
  ["210 밤의해변", "함초롬바탕"],
  ["210 비밀정원", "함초롬바탕"],
  ["210 자연공원", "함초롬돋움"],
  ["210 한반도", "함초롬돋움"],
  ["210 가장자리", "함초롬돋움"],
  ["210 공중전화", "함초롬돋움"],
  ["210 꽃길", "함초롬바탕"],
  ["210 늘솔길", "함초롬바탕"],
  ["210 라임", "함초롬돋움"],
  ["210 리얼러브", "함초롬바탕"],
  ["210 생활반장", "함초롬돋움"],
  ["210 잎새바람", "함초롬바탕"],
  ["210", "함초롬돋움"],
  // HU 시리즈
  ["HU가는펜글씨", "함초롬바탕"],
  ["HU금요일오후", "함초롬바탕"],
  ["HU너무자몽다", "함초롬돋움"],
  ["HU달달한코코아", "함초롬바탕"],
  ["HU바야흐로꽃", "함초롬바탕"],
  ["HU", "함초롬바탕"],
  // DX
  ["DX바른필기", "함초롬바탕"],
  ["DX새명조", "함초롬바탕"],
  ["DX", "함초롬바탕"],
  // THE
  ["THE삐끗삐끗", "함초롬돋움"],
  ["THE수수깡", "함초롬돋움"],
  ["THE", "함초롬돋움"],
  // 기타 한글
  ["둘기마요_고딕", "HG꼬딕체"],
  ["둘기마요", "HG꼬딕체"],
  ["마루 부리", "함초롬바탕"],
  ["양진체", "HG꼬딕체"],
  ["나눔스퀘어", "함초롬돋움"],
  ["땅스부대찌개", "함초롬돋움"],
  ["ONE 모바일POP", "함초롬돋움"],
  ["TT더좋은날에", "함초롬바탕"],
  ["ViMaru", "함초롬바탕"],
  // Adobe / Noto
  ["Adobe 명조", "함초롬바탕"],
  ["Adobe 고딕", "함초롬돋움"],
  ["Noto Sans", "함초롬돋움"],
  ["Noto Serif", "함초롬바탕"],
  ["본명조", "함초롬바탕"],
  ["본고딕", "함초롬돋움"],
  // 나눔 시리즈
  ["나눔명조", "함초롬바탕"],
  ["나눔고딕", "함초롬돋움"],
  ["나눔바른", "함초롬돋움"],
  ["나눔손글씨", "함초롬바탕"],
  ["나눔", "함초롬돋움"],
  // 기본 시스템 폰트
  ["맑은 고딕", "함초롬돋움"],
  ["바탕", "함초롬바탕"],
  ["돋움", "함초롬돋움"],
  ["굴림", "함초롬돋움"],
  ["궁서", "함초롬바탕"],
  ["신명조", "함초롬바탕"],
];

/** 서양 폰트 매핑: 정확 매치 */
const WESTERN_FONT_MAP = new Map<string, string>([
  // Serif
  ["Minion Pro", "함초롬바탕"],
  ["Times New Roman", "함초롬바탕"],
  ["Georgia", "함초롬바탕"],
  ["Palatino", "함초롬바탕"],
  ["Cambria", "함초롬바탕"],
  ["Book Antiqua", "함초롬바탕"],
  // Sans-serif
  ["Myriad Pro", "함초롬돋움"],
  ["Arial", "함초롬돋움"],
  ["Arial Rounded MT Bold", "함초롬돋움"],
  ["Helvetica", "함초롬돋움"],
  ["Calibri", "함초롬돋움"],
  ["Verdana", "함초롬돋움"],
  ["Tahoma", "함초롬돋움"],
  ["Segoe UI", "함초롬돋움"],
  ["Roboto", "함초롬돋움"],
  ["DIN", "함초롬돋움"],
]);

const DEFAULT_HWPX_FONT = "함초롬바탕";

const SERIF_KEYWORDS = ["serif", "roman", "garamond", "minion", "times", "palatino"];
const SANS_KEYWORDS = ["sans", "gothic", "grotesque", "arial", "helvetica", "myriad", "rounded"];

/**
 * SPEC-014: 폰트 매핑 결과의 신뢰도 등급.
 * - exact: 폰트명 정확 매치 (서양 폰트 테이블)
 * - partial: 부분 문자열 매치 (한글 폰트 테이블)
 * - keyword: "명조"/"고딕" 등 키워드 매치
 * - fallback: 매치 실패 → 기본값 (수동 검토 권장)
 */
export type MatchConfidence = "exact" | "partial" | "keyword" | "fallback";

export interface FontMatch {
  font: string;
  confidence: MatchConfidence;
  reason: string;
}

const CONFIDENCE_RANK: Record<MatchConfidence, number> = {
  exact: 0,
  partial: 1,
  keyword: 2,
  fallback: 3,
};

/**
 * 가장 신뢰도 높은 매치 한 건을 반환한다.
 * 신뢰도와 사유를 함께 노출해 사용자가 검토할 수 있게 한다.
 */
export function getFontMatch(idmlFontFamily: string): FontMatch {
  if (!idmlFontFamily) {
    return { font: DEFAULT_HWPX_FONT, confidence: "fallback", reason: "빈 폰트명" };
  }

  // 1. 한글 폰트 부분 매치
  for (const [key, value] of KOREAN_FONT_MAP) {
    if (idmlFontFamily.includes(key)) {
      return { font: value, confidence: "partial", reason: `한글 부분 매치: "${key}"` };
    }
  }

  // 2. 서양 폰트 정확 매치
  const western = WESTERN_FONT_MAP.get(idmlFontFamily);
  if (western) {
    return { font: western, confidence: "exact", reason: "서양 폰트 정확 매치" };
  }

  // 3. 한국어 키워드 폴백
  if (idmlFontFamily.includes("명조") || idmlFontFamily.includes("부리")) {
    return { font: "함초롬바탕", confidence: "keyword", reason: "키워드: 명조/부리" };
  }
  if (idmlFontFamily.includes("고딕") || idmlFontFamily.includes("돋움")) {
    return { font: "함초롬돋움", confidence: "keyword", reason: "키워드: 고딕/돋움" };
  }

  // 4. 영문 키워드 폴백
  const lower = idmlFontFamily.toLowerCase();
  for (const k of SERIF_KEYWORDS) {
    if (lower.includes(k)) {
      return { font: "함초롬바탕", confidence: "keyword", reason: `영문 키워드: ${k}` };
    }
  }
  for (const k of SANS_KEYWORDS) {
    if (lower.includes(k)) {
      return { font: "함초롬돋움", confidence: "keyword", reason: `영문 키워드: ${k}` };
    }
  }

  return {
    font: DEFAULT_HWPX_FONT,
    confidence: "fallback",
    reason: "매치 실패 — 수동 검토 권장",
  };
}

/**
 * IDML 폰트명을 HWPX 디폴트 폰트명으로 매핑한다 (호환용).
 */
export function mapToHwpxFont(idmlFontFamily: string): string {
  return getFontMatch(idmlFontFamily).font;
}

/**
 * 후보 매칭. 최상위 매치 + 대안(서로 다른 family, 최대 limit개).
 * 현재는 가벼운 휴리스틱: best match + 한글/영문 family 양쪽을 추가 후보로 노출.
 */
export function getFontCandidates(
  idmlFontFamily: string,
  limit: number = 3
): FontMatch[] {
  const best = getFontMatch(idmlFontFamily);
  const candidates: FontMatch[] = [best];

  // best와 다른 family 추가
  const ALTERNATES: FontMatch[] = [
    { font: "함초롬돋움", confidence: "fallback", reason: "대안: 돋움 계열" },
    { font: "함초롬바탕", confidence: "fallback", reason: "대안: 바탕 계열" },
    { font: "한컴돋움", confidence: "fallback", reason: "대안: 한컴돋움" },
    { font: "한컴바탕", confidence: "fallback", reason: "대안: 한컴바탕" },
  ];
  for (const alt of ALTERNATES) {
    if (candidates.length >= limit) break;
    if (!candidates.some((c) => c.font === alt.font)) candidates.push(alt);
  }
  return candidates;
}

/** 신뢰도 정렬 비교자: fallback이 가장 위로. */
export function compareConfidence(a: MatchConfidence, b: MatchConfidence): number {
  return CONFIDENCE_RANK[b] - CONFIDENCE_RANK[a];
}

/** HWPX 콤보박스에 표시할 한컴오피스 기본 제공 폰트 목록 */
export const COMMON_HWPX_FONTS = [
  // 함초롬 (기본 글꼴)
  "함초롬바탕",
  "함초롬돋움",
  // 한컴 시리즈
  "한컴바탕",
  "한컴돋움",
  // HG 시리즈
  "HG꼬딕체",
  // HY 시리즈 (TTF 내장)
  "HY견명조",
  "HY견고딕",
  "HY궁서",
  "HY그래픽",
  "HY강B",
  "HY강M",
  "HY나무B",
  "HY나무L",
  "HY나무M",
  "HY동녘B",
  "HY동녘M",
  "HY바다L",
  "HY바다M",
  "HY백송B",
  "HY산B",
  "HY수평선B",
  "HY수평선M",
  "HY센스L",
  "HY엽서M",
  "HY울릉도B",
  "HY울릉도M",
  "HY크리스탈M",
  "HY태백B",
  "HY헤드라인M",
  "HY목판L",
  "한양해서",
  // HFT 전용 (한/글에서만)
  "HY둥근고딕",
  "신명조",
  "견명조",
  "중고딕",
  "견고딕",
  // Windows 시스템 폰트
  "맑은 고딕",
  "바탕",
  "돋움",
  "굴림",
  "궁서",
  // 나눔 시리즈 (시스템에 설치된 경우)
  "나눔고딕",
  "나눔명조",
  "나눔바른고딕",
];
