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
 * IDML 폰트명을 HWPX 디폴트 폰트명으로 매핑한다.
 */
export function mapToHwpxFont(idmlFontFamily: string): string {
  if (!idmlFontFamily) return DEFAULT_HWPX_FONT;

  // 1. 한글 폰트 부분 매치
  for (const [key, value] of KOREAN_FONT_MAP) {
    if (idmlFontFamily.includes(key)) return value;
  }

  // 2. 서양 폰트 정확 매치
  const western = WESTERN_FONT_MAP.get(idmlFontFamily);
  if (western) return western;

  // 3. 한국어 키워드 폴백
  if (idmlFontFamily.includes("명조") || idmlFontFamily.includes("부리")) return "함초롬바탕";
  if (idmlFontFamily.includes("고딕") || idmlFontFamily.includes("돋움")) return "함초롬돋움";

  // 4. 영문 키워드 폴백
  const lower = idmlFontFamily.toLowerCase();
  if (SERIF_KEYWORDS.some((k) => lower.includes(k))) return "함초롬바탕";
  if (SANS_KEYWORDS.some((k) => lower.includes(k))) return "함초롬돋움";

  return DEFAULT_HWPX_FONT;
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
