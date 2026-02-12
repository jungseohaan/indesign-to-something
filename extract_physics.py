#!/usr/bin/env python3
"""
Extract 32 question items from physics-1.idml (pages 162-173).

IDML structure:
- Spreads contain Group objects, each Group = one question item
- Each question Group has exactly 2 TextFrames:
  - Content frame (w~245.2pt): main question content
  - Badge frame (w~24.4pt): question number (01-32)
- TextFrames reference Stories via ParentStory attribute
- Stories contain ParagraphStyleRange elements with text Content

Paragraph styles mapping:
- $ID/[No paragraph style] -> SKIP (concatenated duplicate)
- 01_년도별 출제 경향 -> 년도출처
- 01_발문, 01_발문(1행) -> 발문1 or 발문2 (split by spacing between them)
- 표빈공간 -> empty spacing
- 표빈공간(중앙) -> figure/image placeholder
- 01_선지(5개), 01_선지, 01_선지(중앙) -> 선지
- 01_교사용풀이 -> 교사용풀이
- 01_표 중앙, 01_표 좌우(-1pt) -> 표내용


보기 extraction:
- 보기 content (ㄱ., ㄴ., ㄷ. items) lives in separate Story XML files
- These stories are referenced by inline TextFrame elements embedded within
  the main content story's XML (anchored inside table cells)
- Paragraph styles for 보기 content: 01_보기(ㄱ.), 01_보기, 01_보기(ㄱ.) 분수, etc.
- We scan the content story XML for TextFrame elements, read their ParentStory,
  and extract text from stories that contain 보기-style paragraphs starting with ㄱ./ㄴ./ㄷ.
"""

import xml.etree.ElementTree as ET
import os
import json
import re
import sys
import argparse
import unicodedata
from urllib.parse import unquote

# Default paths (overridden by CLI arguments)
IDML_DIR = '/tmp/physics-idml'
SPREAD_DIR = os.path.join(IDML_DIR, 'Spreads')
STORY_DIR = os.path.join(IDML_DIR, 'Stories')

# Global table registry — tables are extracted separately and referenced by ID
_tables = []
_table_counter = 0


def register_table(html):
    """Register a table HTML and return its reference ID (e.g., 'T01')."""
    global _table_counter
    _table_counter += 1
    table_id = f'T{_table_counter:02d}'
    _tables.append({'id': table_id, 'html': html})
    return table_id


def reset_table_registry():
    """Reset the global table registry (for fresh runs)."""
    global _tables, _table_counter
    _tables = []
    _table_counter = 0


# ── InDesign Unicode 매핑 테이블 ──
# IDML Content 요소에 포함되는 특수 유니코드 문자를 가시적 표현으로 변환
IDML_CHAR_MAP = {
    # 특수 공백 → 가시적 플레이스홀더
    '\u2003': '[특수공백:전각]',       # EM SPACE
    '\u2002': '[특수공백:반각]',       # EN SPACE
    '\u2004': '[특수공백:3분각]',      # THREE-PER-EM SPACE
    '\u2005': '[특수공백:4분각]',      # FOUR-PER-EM SPACE
    '\u2006': '[특수공백:6분각]',      # SIX-PER-EM SPACE
    '\u2007': '[특수공백:숫자폭]',     # FIGURE SPACE
    '\u2008': '[특수공백:구두점폭]',    # PUNCTUATION SPACE
    '\u2009': '[특수공백:가는]',       # THIN SPACE
    '\u200A': '[특수공백:극세]',       # HAIR SPACE
    '\u00A0': '[특수공백:비분리]',     # NO-BREAK SPACE
    '\u202F': '[특수공백:좁은비분리]',  # NARROW NO-BREAK SPACE
    # 제로폭 문자 → 제거
    '\u200B': '',                      # ZERO WIDTH SPACE
    '\u200C': '',                      # ZERO WIDTH NON-JOINER
    '\u200D': '',                      # ZERO WIDTH JOINER
    '\u2060': '',                      # WORD JOINER
    '\uFEFF': '',                      # BOM / ZERO WIDTH NO-BREAK SPACE
    # 줄바꿈 / 구분자
    '\u2028': '\n',                    # LINE SEPARATOR (강제 줄바꿈)
    '\u2029': '\n',                    # PARAGRAPH SEPARATOR
    # 하이픈
    '\u2011': '-',                     # NON-BREAKING HYPHEN → 일반 하이픈
    '\u00AD': '',                      # SOFT HYPHEN → 제거
    # InDesign 제어 문자
    '\u0003': '',                      # END OF TEXT (InDesign 스토리 끝)
    '\u0018': '',                      # CANCEL (InDesign 특수문자 자리)
    '\uFFFC': '[인라인객체]',           # OBJECT REPLACEMENT CHARACTER
}

# 매핑 테이블 기반 변환 정규식 (한번만 컴파일)
_IDML_CHAR_RE = re.compile(
    '(' + '|'.join(re.escape(k) for k in IDML_CHAR_MAP.keys()) + ')'
)


def apply_idml_char_map(text):
    """IDML Content 텍스트에서 특수 유니코드를 매핑 테이블에 따라 변환."""
    if not text:
        return text
    return _IDML_CHAR_RE.sub(lambda m: IDML_CHAR_MAP[m.group()], text)


# Target spreads for pages 162-173 (in page order)
TARGET_SPREADS = [
    'Spread_u243bf.xml',   # pages 162-163
    'Spread_u38551.xml',   # pages 164-165
    'Spread_u300ff.xml',   # pages 166-167
    'Spread_u2455a.xml',   # pages 168-169
    'Spread_u30651.xml',   # pages 170-171
    'Spread_u246e0.xml',   # pages 172-173
]


def get_textframe_width(tf_elem):
    """Extract width from a TextFrame's PathPointArray geometry."""
    for elem in tf_elem.iter():
        tag = elem.tag.split('}')[-1] if '}' in elem.tag else elem.tag
        if tag == 'PathPointArray':
            points = []
            for pp in elem:
                pptag = pp.tag.split('}')[-1] if '}' in pp.tag else pp.tag
                if pptag == 'PathPointType':
                    anchor = pp.attrib.get('Anchor', '')
                    points.append(anchor)
            if points:
                pts = [p.split(' ') for p in points]
                xs = [float(p[0]) for p in pts]
                return max(xs) - min(xs)
    return 0


def _get_tag(elem):
    """Get the local tag name, stripping any namespace prefix."""
    tag = elem.tag
    return tag.split('}')[-1] if '}' in tag else tag


def _extract_cell_text(cell_elem):
    """
    Extract text content from a Cell element.
    Handles multiple ParagraphStyleRange/CharacterStyleRange/Content elements,
    and Br elements (paragraph breaks within a cell) joined by newlines.
    """
    text_parts = []
    for elem in cell_elem.iter():
        ctag = _get_tag(elem)
        if ctag == 'Content':
            text_parts.append(apply_idml_char_map(elem.text or ''))
        elif ctag == 'Br':
            text_parts.append('\n')
    return ''.join(text_parts).strip()


def _extract_table_html(table_elem):
    """
    Extract an IDML Table element into an HTML table string.

    IDML table structure:
    - Table has BodyRowCount and ColumnCount attributes
    - Row and Column children define the grid
    - Cell children have Name="col:row", RowSpan, ColumnSpan attributes
    - Cell contains ParagraphStyleRange > CharacterStyleRange > Content

    Returns an HTML string like:
    <table><tr><td>...</td><td>...</td></tr>...</table>
    """
    num_rows = int(table_elem.attrib.get('BodyRowCount', '0'))
    num_cols = int(table_elem.attrib.get('ColumnCount', '0'))

    if num_rows == 0 or num_cols == 0:
        return ''

    # Parse cells into a dict keyed by (col, row)
    cells = {}
    for child in table_elem:
        if _get_tag(child) != 'Cell':
            continue
        name = child.attrib.get('Name', '')
        if ':' not in name:
            continue
        col_str, row_str = name.split(':', 1)
        try:
            col, row = int(col_str), int(row_str)
        except ValueError:
            continue
        row_span = int(child.attrib.get('RowSpan', '1'))
        col_span = int(child.attrib.get('ColumnSpan', '1'))
        text = _extract_cell_text(child)
        cells[(col, row)] = {
            'text': text,
            'rowspan': row_span,
            'colspan': col_span,
        }

    # Track which grid positions are covered by spanning cells
    covered = set()
    for (col, row), info in cells.items():
        for dr in range(info['rowspan']):
            for dc in range(info['colspan']):
                if dr == 0 and dc == 0:
                    continue
                covered.add((col + dc, row + dr))

    # Build HTML rows
    html_parts = ['<table>']
    for r in range(num_rows):
        html_parts.append('<tr>')
        for c in range(num_cols):
            if (c, r) in covered:
                continue  # This position is covered by a spanning cell
            if (c, r) in cells:
                info = cells[(c, r)]
                attrs = ''
                if info['rowspan'] > 1:
                    attrs += f' rowspan="{info["rowspan"]}"'
                if info['colspan'] > 1:
                    attrs += f' colspan="{info["colspan"]}"'
                html_parts.append(f'<td{attrs}>{info["text"]}</td>')
            else:
                html_parts.append('<td></td>')
        html_parts.append('</tr>')
    html_parts.append('</table>')
    return ''.join(html_parts)


def _is_data_table(table_elem):
    """Check if a Table element is a data table (not a layout table).
    Layout tables use 'TableStyle/문제(표기본)', data tables use other styles."""
    style = table_elem.attrib.get('AppliedTableStyle', '')
    return '문제(표기본)' not in style


def _psr_contains_data_table(psr_elem):
    """Check if a ParagraphStyleRange contains a data Table (not layout table)."""
    for child in psr_elem:
        ctag = _get_tag(child)
        if ctag == 'Table' and _is_data_table(child):
            return True
        if ctag == 'CharacterStyleRange':
            for grandchild in child:
                if _get_tag(grandchild) == 'Table' and _is_data_table(grandchild):
                    return True
    return False


def _find_data_tables_in_psr(psr_elem):
    """Find all data Table elements (not layout tables) in a ParagraphStyleRange."""
    tables = []
    for child in psr_elem:
        ctag = _get_tag(child)
        if ctag == 'Table' and _is_data_table(child):
            tables.append(child)
        elif ctag == 'CharacterStyleRange':
            for grandchild in child:
                if _get_tag(grandchild) == 'Table' and _is_data_table(grandchild):
                    tables.append(grandchild)
    return tables


def _extract_text_from_psr(psr_elem, skip_tables=False):
    """
    Extract text from a ParagraphStyleRange, splitting on Br elements.
    If skip_tables is True, do not descend into Table elements.
    Returns list of text strings (one per Br-separated paragraph).
    """
    text_parts = []
    results = []

    def _walk(elem):
        ctag = _get_tag(elem)
        if skip_tables and ctag == 'Table':
            return
        if ctag == 'Content':
            text_parts.append(apply_idml_char_map(elem.text or ''))
        elif ctag == 'Br':
            results.append(''.join(text_parts).strip())
            text_parts.clear()
        # Recurse into children
        for child in elem:
            _walk(child)

    # Walk direct children (not the PSR element itself to avoid re-processing)
    for child in psr_elem:
        _walk(child)

    # Flush remaining
    results.append(''.join(text_parts).strip())
    return results


def read_story_paragraphs(story_id):
    """
    Read a Story XML file and return list of (style_name, text) tuples.
    Handles Br (line break) elements that create paragraph splits within
    a single ParagraphStyleRange.

    When a Table element is encountered inside a ParagraphStyleRange,
    it is extracted as an HTML table string and inserted as a paragraph
    with the style name '__table__'. The non-table text from the same
    ParagraphStyleRange is also extracted normally (without duplicating
    the table cell content).
    """
    story_file = os.path.join(STORY_DIR, f'Story_{story_id}.xml')
    if not os.path.exists(story_file):
        return []

    tree = ET.parse(story_file)
    root = tree.getroot()

    paragraphs = []

    # Collect ParagraphStyleRange elements that are inside data table Cells
    # (not layout table cells). These will be skipped in the top-level
    # iteration because their content is captured by table HTML extraction.
    data_table_cell_psrs = set()
    for table_elem in root.iter():
        if _get_tag(table_elem) != 'Table':
            continue
        if not _is_data_table(table_elem):
            continue  # Layout table cells should still be processed normally
        for cell_elem in table_elem:
            if _get_tag(cell_elem) != 'Cell':
                continue
            for psr in cell_elem:
                if _get_tag(psr) == 'ParagraphStyleRange':
                    data_table_cell_psrs.add(id(psr))

    for elem in root.iter():
        tag = _get_tag(elem)
        if tag != 'ParagraphStyleRange':
            continue

        # Skip ParagraphStyleRange elements inside data table Cells
        # (their content is captured by _extract_table_html)
        if id(elem) in data_table_cell_psrs:
            continue

        style = elem.attrib.get('AppliedParagraphStyle', '')
        style_name = style.replace('ParagraphStyle/', '')

        # Check if this PSR contains inline data Table elements
        if _psr_contains_data_table(elem):
            # Extract non-table text (before/after the table)
            text_segments = _extract_text_from_psr(elem, skip_tables=True)
            for text in text_segments:
                if text:
                    paragraphs.append((style_name, text))

            # Extract each inline data table as HTML
            tables = _find_data_tables_in_psr(elem)
            for tbl in tables:
                html = _extract_table_html(tbl)
                if html:
                    paragraphs.append(('__table__', html))
        else:
            # Normal paragraph extraction (no tables, or only layout tables)
            text_parts = []
            for child in elem.iter():
                ctag = _get_tag(child)
                if ctag == 'Content':
                    text_parts.append(apply_idml_char_map(child.text or ''))
                elif ctag == 'Br':
                    text = ''.join(text_parts).strip()
                    paragraphs.append((style_name, text))
                    text_parts = []

            # Flush remaining
            text = ''.join(text_parts).strip()
            paragraphs.append((style_name, text))

    return paragraphs


# Paragraph styles that indicate 보기 content (ㄱ./ㄴ./ㄷ. items)
BOGI_STYLES = {
    '01_보기(ㄱ.)',
    '01_보기(ㄱ.) 분수',
    '01_보기',
    '01_보기(고딕8pt)',
    '01_보기(윤명조130)',
    '01_보기(중앙)',
}

# Korean consonant prefixes used in 보기 items
BOGI_PREFIXES = ('ㄱ.', 'ㄴ.', 'ㄷ.', 'ㄹ.', 'ㅁ.', 'ㅂ.')


def extract_bogi_from_content_story(content_story_id):
    """
    Extract 보기 content from inline TextFrames embedded in the content story.

    The content story XML may contain anchored/inline TextFrame elements that
    reference separate Story files. We scan for these TextFrames, read their
    associated stories, and check if they contain 보기-style paragraphs with
    ㄱ./ㄴ./ㄷ. prefixed text.

    Returns a list of strings like ["ㄱ. ...", "ㄴ. ...", "ㄷ. ..."], or None
    if no 보기 content is found.
    """
    story_file = os.path.join(STORY_DIR, f'Story_{content_story_id}.xml')
    if not os.path.exists(story_file):
        return None

    tree = ET.parse(story_file)
    root = tree.getroot()

    # Collect all inline TextFrame ParentStory references from the content story XML
    inline_story_ids = []
    for elem in root.iter():
        tag = elem.tag.split('}')[-1] if '}' in elem.tag else elem.tag
        if tag == 'TextFrame':
            ps = elem.attrib.get('ParentStory', '')
            if ps:
                inline_story_ids.append(ps)

    if not inline_story_ids:
        return None

    # Check each inline story for 보기 content
    for inline_sid in inline_story_ids:
        inline_file = os.path.join(STORY_DIR, f'Story_{inline_sid}.xml')
        if not os.path.exists(inline_file):
            continue

        inline_paras = read_story_paragraphs(inline_sid)
        if not inline_paras:
            continue

        # Check if this story has 보기-style paragraphs with ㄱ./ㄴ./ㄷ. content
        has_bogi_style = any(s in BOGI_STYLES for s, _ in inline_paras)
        has_bogi_prefix = any(
            t.lstrip().startswith(BOGI_PREFIXES) for _, t in inline_paras if t.strip()
        )

        if not (has_bogi_style and has_bogi_prefix):
            continue

        # Extract 보기 items: each non-empty paragraph that starts with a Korean consonant prefix
        bogi_items = []
        for style, text in inline_paras:
            text = text.strip()
            if not text:
                continue
            if text.startswith(BOGI_PREFIXES):
                # Normalize: replace tab after "ㄱ." with space
                # Input formats: "ㄱ.\ttext" or "ㄱ. text" or "ㄱ.text"
                normalized = re.sub(r'^(ㄱ\.|ㄴ\.|ㄷ\.|ㄹ\.|ㅁ\.|ㅂ\.)\s*', lambda m: m.group(1) + ' ', text)
                bogi_items.append(normalized)

        if bogi_items:
            return bogi_items

    return None


def extract_inline_tables_from_content_story(content_story_id):
    """
    Extract inline table HTML from inline TextFrame stories embedded in
    the content story. Only considers stories that contain data tables
    (i.e., __table__ entries) and are NOT purely 보기 content.

    Scans the content story XML for anchored TextFrame elements, reads
    their associated stories, and returns any __table__ entries found
    along with the surrounding experiment procedure text.

    Returns a list of dicts, each containing:
    - 'story_id': the inline story ID
    - 'paragraphs': list of (style, text) tuples from the inline story
    - 'tables': list of HTML table strings found in the inline story

    Returns empty list if no inline stories contain tables.
    """
    story_file = os.path.join(STORY_DIR, f'Story_{content_story_id}.xml')
    if not os.path.exists(story_file):
        return []

    tree = ET.parse(story_file)
    root = tree.getroot()

    # Collect all inline TextFrame ParentStory references
    inline_story_ids = []
    for elem in root.iter():
        tag = _get_tag(elem)
        if tag == 'TextFrame':
            ps = elem.attrib.get('ParentStory', '')
            if ps:
                inline_story_ids.append(ps)

    if not inline_story_ids:
        return []

    results = []
    for inline_sid in inline_story_ids:
        inline_file = os.path.join(STORY_DIR, f'Story_{inline_sid}.xml')
        if not os.path.exists(inline_file):
            continue

        inline_paras = read_story_paragraphs(inline_sid)
        if not inline_paras:
            continue

        # Skip stories that are purely 보기 content (ㄱ./ㄴ./ㄷ. items)
        has_bogi_style = any(s in BOGI_STYLES for s, _ in inline_paras)
        has_bogi_prefix = any(
            t.lstrip().startswith(BOGI_PREFIXES) for _, t in inline_paras if t.strip()
        )
        if has_bogi_style and has_bogi_prefix:
            # Check if this is a mixed story (has both 보기 and tables)
            has_table = any(s == '__table__' for s, _ in inline_paras)
            if not has_table:
                continue  # Pure 보기 story, skip

        # Check if this inline story has any __table__ entries
        tables = [text for style, text in inline_paras if style == '__table__']
        if tables:
            results.append({
                'story_id': inline_sid,
                'paragraphs': inline_paras,
                'tables': tables,
            })

    return results


def extract_question_groups_from_spread(spread_file):
    """
    Extract all question Groups from a spread XML file.
    Returns list of dicts with badge_story, content_story, group position.
    """
    tree = ET.parse(os.path.join(SPREAD_DIR, spread_file))
    root = tree.getroot()

    groups = []

    for elem in root.iter():
        tag = elem.tag.split('}')[-1] if '}' in elem.tag else elem.tag
        if tag != 'Group':
            continue

        # Find TextFrame direct children only
        textframes = []
        for child in elem:
            ctag = child.tag.split('}')[-1] if '}' in child.tag else child.tag
            if ctag == 'TextFrame':
                parent_story = child.attrib.get('ParentStory', '')
                width = get_textframe_width(child)
                textframes.append((parent_story, width))

        if len(textframes) != 2:
            continue

        has_badge = any(23 < w < 26 for _, w in textframes)
        has_content = any(244 < w < 247 for _, w in textframes)

        if not (has_badge and has_content):
            continue

        content_story = next(s for s, w in textframes if w > 200)
        badge_story = next(s for s, w in textframes if w < 30)

        groups.append({
            'content_story': content_story,
            'badge_story': badge_story,
        })

    return groups


def parse_item_from_paragraphs(paragraphs, badge_number):
    """
    Parse paragraphs into a structured question item.

    Strategy:
    1. Skip $ID/[No paragraph style] (full-text duplicate)
    2. Collect 년도출처 from non-empty 01_년도별 출제 경향
    3. Classify remaining paragraphs into zones using style names
    4. 발문 paragraphs separated by any 표빈공간 -> 발문1 (first block), 발문2 (second block)
    5. 표빈공간(중앙) indicates figure placeholder -> 그림=true
    6. 선지 styles -> parsed choices
    7. 교사용풀이 -> teacher's solution
    8. 표 styles -> table content
    """
    item = {
        'number': badge_number,
        '년도출처': '',
        '발문1': '',
        '그림': False,
        '발문2': '',
        '보기': None,
        '선지': [],
        '교사용풀이': '',
        '표내용': None,
    }

    # Filter out skippable styles
    filtered = [(s, t) for s, t in paragraphs
                if s not in ('$ID/[No paragraph style]', '$ID/NormalParagraphStyle')]

    # === 1. 년도출처 ===
    nyundo_parts = [t.strip() for s, t in filtered
                    if s == '01_년도별 출제 경향' and t.strip()]
    item['년도출처'] = '\n'.join(nyundo_parts)

    # === 2. Check for figure (표빈공간(중앙)) ===
    item['그림'] = any(s == '표빈공간(중앙)' for s, t in filtered)

    # === 3. Walk through non-년도출처 paragraphs to classify zones ===
    # Sequence: [년도출처] -> [spacing] -> 발문1 -> [spacing] -> [표] -> 발문2 -> [spacing] -> 선지 -> 교사용풀이

    balmun_groups = []  # List of lists - each list is a contiguous block of 발문 paragraphs
    current_balmun = []
    pyo_parts = []
    senji_parts = []   # List of (style, text) for 선지
    gyosa_parts = []

    phase = 'before_senji'  # Simplified: we just need to collect things in order

    for style, text in filtered:
        if style == '01_년도별 출제 경향':
            continue  # Already handled

        # Handle data tables found in the content story —
        # register the table and insert a reference placeholder
        if style == '__table__':
            table_id = register_table(text)
            ref = f'{{{{{table_id}}}}}'
            if phase == 'before_senji':
                if current_balmun:
                    balmun_groups.append(current_balmun)
                    current_balmun = []
                pyo_parts.append(ref)
            elif phase == 'senji':
                pyo_parts.append(ref)
            elif phase == 'gyosa':
                gyosa_parts.append(ref)
            continue

        if phase == 'before_senji':
            if style in ('01_발문', '01_발문(1행)'):
                current_balmun.append(text)
            elif style in ('표빈공간', '표빈공간(중앙)'):
                # If we have accumulated 발문, this spacing ends a 발문 block
                if current_balmun:
                    balmun_groups.append(current_balmun)
                    current_balmun = []
            elif style in ('01_표 중앙', '01_표 좌우(-1pt)'):
                # Table content - flush any pending 발문
                if current_balmun:
                    balmun_groups.append(current_balmun)
                    current_balmun = []
                if text.strip():
                    pyo_parts.append(text.strip())
            elif style in ('01_선지(5개)', '01_선지', '01_선지(중앙)'):
                # Start of choices - flush pending 발문
                if current_balmun:
                    balmun_groups.append(current_balmun)
                    current_balmun = []
                phase = 'senji'
                senji_parts.append((style, text))
            elif style == '01_교사용풀이':
                if current_balmun:
                    balmun_groups.append(current_balmun)
                    current_balmun = []
                phase = 'gyosa'
                if text.strip():
                    gyosa_parts.append(text.strip())

        elif phase == 'senji':
            if style in ('01_선지(5개)', '01_선지', '01_선지(중앙)'):
                senji_parts.append((style, text))
            elif style == '01_교사용풀이':
                phase = 'gyosa'
                if text.strip():
                    gyosa_parts.append(text.strip())
            elif style in ('표빈공간', '표빈공간(중앙)'):
                pass  # Space within 선지 section (e.g., table choices)
            elif style in ('01_표 중앙', '01_표 좌우(-1pt)'):
                if text.strip():
                    pyo_parts.append(text.strip())

        elif phase == 'gyosa':
            if style == '01_교사용풀이':
                if text.strip():
                    gyosa_parts.append(text.strip())

    # Flush any remaining 발문
    if current_balmun:
        balmun_groups.append(current_balmun)

    # === 4. Assign 발문 groups to 발문1 and 발문2 ===
    # Typically: balmun_groups[0] = 발문1, balmun_groups[1] = 발문2
    # Some items have only one 발문 group (no split)

    if len(balmun_groups) >= 1:
        # 발문1 = first group, join with newline
        item['발문1'] = '\n'.join(t for t in balmun_groups[0] if t.strip())

    if len(balmun_groups) >= 2:
        # 발문2 = second group onward
        all_balmun2 = []
        for grp in balmun_groups[1:]:
            all_balmun2.extend(t for t in grp if t.strip())
        item['발문2'] = '\n'.join(all_balmun2)

    # If there's only one group and it contains the question prompt pattern,
    # check if it should be split (e.g., item 11 with no 발문2)

    # === 5. Parse 선지 ===
    item['선지'] = parse_choices(senji_parts)

    # === 6. 교사용풀이 ===
    item['교사용풀이'] = '\n'.join(gyosa_parts)

    # === 7. 표내용 ===
    item['표내용'] = '\n'.join(pyo_parts) if pyo_parts else None

    return item


def parse_choices(senji_parts):
    """
    Parse choice/answer options from (style, text) pairs.

    Three formats:
    1. 선지(5개): "① X\t② Y\t③ Z\t④ W\t⑤ V" (tab-separated on one line)
    2. 선지 (vertical): Each entry is "①\ttext"
    3. 선지 + 선지(중앙): Table grid with headers and data
    """
    if not senji_parts:
        return []

    # Determine which styles are present
    styles = set(s for s, t in senji_parts)

    # Case 1: 선지(5개) - may be single-line or Br-split into multiple entries
    if '01_선지(5개)' in styles:
        # Combine ALL 선지(5개) entries (Br splits create multiple entries)
        parts = [t.strip() for s, t in senji_parts if s == '01_선지(5개)' and t.strip()]
        combined = '\n'.join(parts)
        if combined:
            return split_by_circle_numbers(combined)

    # Case 2: 선지 only (vertical list)
    if '01_선지' in styles and '01_선지(중앙)' not in styles:
        combined = '\n'.join(t for s, t in senji_parts if t.strip())
        if combined:
            return split_by_circle_numbers(combined)

    # Case 3: 선지 + 선지(중앙) - table format
    if '01_선지(중앙)' in styles:
        return parse_table_choices(senji_parts)

    # Fallback
    return [t for s, t in senji_parts if t.strip()]


def split_by_circle_numbers(text):
    """Split text by circled number markers."""
    circle_nums = ['①', '②', '③', '④', '⑤']

    positions = []
    for cn in circle_nums:
        pos = text.find(cn)
        if pos >= 0:
            positions.append((pos, cn))

    if not positions:
        return [text.strip()] if text.strip() else []

    positions.sort()

    choices = []
    for idx, (pos, cn) in enumerate(positions):
        start = pos + len(cn)
        end = positions[idx + 1][0] if idx + 1 < len(positions) else len(text)
        choice_text = text[start:end].strip().strip('\t').strip()
        choices.append(f'{cn} {choice_text}')

    return choices


def parse_table_choices(senji_parts):
    """
    Parse table-format choices (선지 + 선지(중앙)).

    Structure:
    - Headers: 선지(중앙) entries before first ①-⑤ marker
    - Rows: 선지 entry with ①-⑤ marker, followed by 선지(중앙) column values
    - Two table blocks (left: ①③⑤, right: ②④) separated by 표빈공간
    """
    circle_nums = {'①', '②', '③', '④', '⑤'}

    # Split into blocks (header + data rows)
    # Walk through entries
    headers = []
    rows = {}
    current_num = None
    current_vals = []
    header_done = False

    for style, text in senji_parts:
        text = text.strip()

        if style == '01_선지':
            if text in circle_nums:
                # New choice row
                if current_num and current_vals:
                    non_empty = [v for v in current_vals if v]
                    if non_empty:
                        rows[current_num] = non_empty
                current_num = text
                current_vals = []
                header_done = True
            elif not text:
                # Empty separator between rows
                if current_num and current_vals:
                    non_empty = [v for v in current_vals if v]
                    if non_empty:
                        rows[current_num] = non_empty
                current_num = None
                current_vals = []

        elif style == '01_선지(중앙)':
            if not header_done:
                if text:
                    headers.append(text)
            elif current_num:
                if text:
                    # Auto-flush row when we've collected enough columns
                    if headers and len(current_vals) >= len(headers):
                        non_empty = [v for v in current_vals if v]
                        if non_empty:
                            rows[current_num] = non_empty
                        current_num = None
                        current_vals = []
                    else:
                        current_vals.append(text)
            elif not current_num and text:
                # New header block (for second column of table)
                # Skip duplicate headers from right-side table block
                pass

    # Flush last row
    if current_num and current_vals:
        non_empty = [v for v in current_vals if v]
        if non_empty:
            rows[current_num] = non_empty

    # Build choice strings
    choices = []
    for cn in ['①', '②', '③', '④', '⑤']:
        if cn in rows:
            vals = rows[cn]
            if headers and len(vals) == len(headers):
                parts = [f'{h}:{v}' for h, v in zip(headers, vals)]
                choices.append(f'{cn} {", ".join(parts)}')
            else:
                choices.append(f'{cn} {" ".join(vals)}')

    return choices


# ── 테이블 형식 선지 후처리 ──

_CIRCLE_NUMS = {'①', '②', '③', '④', '⑤'}
_TD_RE = re.compile(r'<td[^>]*>(.*?)</td>', re.DOTALL)
_TR_RE = re.compile(r'<tr[^>]*>(.*?)</tr>', re.DOTALL)


def _parse_html_table_rows(html):
    """HTML 테이블을 [[cell_text, ...], ...] 형태로 파싱."""
    rows = []
    for tr_match in _TR_RE.finditer(html):
        cells = [td.strip() for td in _TD_RE.findall(tr_match.group(1))]
        rows.append(cells)
    return rows


def _is_senji_table(html):
    """테이블 HTML에 ①-⑤ 마커가 포함되어 있는지 확인."""
    return any(cn in html for cn in _CIRCLE_NUMS)


def try_extract_table_senji(item):
    """
    선지가 비어있고 표내용에 테이블 참조가 있을 때,
    테이블이 선지 형식(①-⑤ 포함)이면 선지로 변환.
    표내용에서 해당 테이블 참조는 제거.
    """
    if item.get('선지'):  # 이미 선지가 있으면 스킵
        return
    pyo = item.get('표내용')
    if not pyo:
        return

    # 표내용에서 {{Txx}} 참조 찾기
    ref_pattern = re.compile(r'\{\{(T\d+)\}\}')
    refs = ref_pattern.findall(pyo)
    if not refs:
        return

    # 선지 테이블 찾기
    senji_table_ids = []
    for table_id in refs:
        tbl = next((t for t in _tables if t['id'] == table_id), None)
        if tbl and _is_senji_table(tbl['html']):
            senji_table_ids.append(table_id)

    if not senji_table_ids:
        return

    # 모든 선지 테이블에서 헤더와 행 수집
    headers = []
    choice_rows = {}  # cn -> [val, val, ...]

    for table_id in senji_table_ids:
        tbl = next(t for t in _tables if t['id'] == table_id)
        rows = _parse_html_table_rows(tbl['html'])
        if not rows:
            continue

        # 첫 번째 행에서 헤더 추출 (비어있지 않은 셀만)
        if not headers:
            headers = [c for c in rows[0] if c.strip()]

        # 나머지 행에서 선지 추출
        for row in rows[1:]:
            if not row:
                continue
            first_cell = row[0].strip()
            if first_cell in _CIRCLE_NUMS:
                # 비어있지 않은 데이터 셀만 수집 (첫 번째 셀 제외)
                vals = [c for c in row[1:] if c.strip()]
                choice_rows[first_cell] = vals

    if not choice_rows:
        return

    # 선지 문자열 빌드
    choices = []
    for cn in ['①', '②', '③', '④', '⑤']:
        if cn in choice_rows:
            vals = choice_rows[cn]
            if headers and len(vals) == len(headers):
                parts = [f'{h}:{v}' for h, v in zip(headers, vals)]
                choices.append(f'{cn} {", ".join(parts)}')
            else:
                choices.append(f'{cn} {" ".join(vals)}')

    if choices:
        item['선지'] = choices
        # 표내용에서 선지 테이블 참조 제거
        remaining_parts = []
        for line in pyo.split('\n'):
            line_refs = ref_pattern.findall(line)
            # 선지 테이블 참조만 있는 줄은 제거
            non_senji_refs = [r for r in line_refs if r not in senji_table_ids]
            if not line_refs or non_senji_refs:
                # 선지 테이블 참조만 제거하고 나머지 유지
                cleaned = line
                for sid in senji_table_ids:
                    cleaned = cleaned.replace(f'{{{{{sid}}}}}', '').strip()
                if cleaned:
                    remaining_parts.append(cleaned)
        item['표내용'] = '\n'.join(remaining_parts) if remaining_parts else None


# ── 정답 추출 ──

# InDesign 정답 마커: \ue34c 뒤에 ①~⑤가 오는 패턴
_ANSWER_MARKER = '\ue34c'
_ANSWER_RE = re.compile(r'\ue34c\s*([①②③④⑤])')
_ANSWER_MAP = {'①': 1, '②': 2, '③': 3, '④': 4, '⑤': 5}


def extract_answer_from_story(story_id):
    """
    Extract the correct answer number from a content story and its inline sub-stories.
    The answer is marked by \ue34c followed by a circled number ①~⑤.

    Returns the answer as an integer (1-5), or None if not found.
    """
    visited = set()

    def _scan_story(sid):
        if sid in visited:
            return None
        visited.add(sid)

        story_file = os.path.join(STORY_DIR, f'Story_{sid}.xml')
        if not os.path.exists(story_file):
            return None

        tree = ET.parse(story_file)
        root = tree.getroot()

        # Collect all text from this story
        text_parts = []
        inline_stories = []
        for elem in root.iter():
            tag = _get_tag(elem)
            if tag == 'Content':
                text_parts.append(elem.text or '')
            elif tag == 'TextFrame':
                ps = elem.attrib.get('ParentStory', '')
                if ps:
                    inline_stories.append(ps)

        text = ''.join(text_parts)
        m = _ANSWER_RE.search(text)
        if m:
            return _ANSWER_MAP[m.group(1)]

        # Check inline stories
        for inline_sid in inline_stories:
            result = _scan_story(inline_sid)
            if result is not None:
                return result

        return None

    return _scan_story(story_id)


# ── 이미지 링크 추출 ──

# IDML 파일과 같은 폴더의 Links/ 디렉토리
LINKS_DIR = os.path.expanduser('~/Downloads/Links')


def extract_images_from_story(story_id):
    """
    Extract image filenames from a story and its inline TextFrame sub-stories.
    Images are found as Link elements within Rectangle[ContentType=GraphicType]
    elements inside story XML files.

    Returns a list of unique image filenames (deduplicated, order preserved).
    """
    visited = set()
    images = []  # preserve order, deduplicate

    def _scan_story(sid):
        if sid in visited:
            return
        visited.add(sid)

        story_file = os.path.join(STORY_DIR, f'Story_{sid}.xml')
        if not os.path.exists(story_file):
            return

        tree = ET.parse(story_file)
        root = tree.getroot()

        for elem in root.iter():
            tag = _get_tag(elem)
            if tag == 'Link':
                uri = elem.attrib.get('LinkResourceURI', '')
                if uri:
                    decoded = unquote(uri)
                    filename = decoded.rsplit('/', 1)[-1]
                    if filename not in images:
                        images.append(filename)
            elif tag == 'TextFrame':
                ps = elem.attrib.get('ParentStory', '')
                if ps:
                    _scan_story(ps)

    _scan_story(story_id)
    return images


def resolve_image_paths(filenames):
    """
    Resolve image filenames to actual file paths in LINKS_DIR.
    Uses NFC normalization to match macOS NFD filenames with IDML NFC names.
    Returns list of dicts: {'filename': str, 'path': str or None, 'exists': bool}
    """
    if not os.path.isdir(LINKS_DIR):
        return [{'filename': fn, 'path': None, 'exists': False} for fn in filenames]

    # Build NFC-normalized lookup: NFC(filename) -> actual_filename
    nfc_lookup = {}
    for f in os.listdir(LINKS_DIR):
        nfc_lookup[unicodedata.normalize('NFC', f)] = f

    results = []
    for fn in filenames:
        nfc_fn = unicodedata.normalize('NFC', fn)
        actual = nfc_lookup.get(nfc_fn)
        if actual:
            results.append({
                'filename': fn,
                'path': os.path.join(LINKS_DIR, actual),
                'exists': True,
            })
        else:
            results.append({
                'filename': fn,
                'path': None,
                'exists': False,
            })
    return results


def main():
    global IDML_DIR, SPREAD_DIR, STORY_DIR, LINKS_DIR, TARGET_SPREADS

    parser = argparse.ArgumentParser(description='Extract question items from IDML')
    parser.add_argument('--idml-dir', help='Path to extracted IDML directory')
    parser.add_argument('--links-dir', help='Path to Links directory for image resolution')
    parser.add_argument('--spreads', help='Comma-separated spread filenames (e.g. Spread_u243bf.xml,Spread_u38551.xml)')
    args = parser.parse_args()

    if args.idml_dir:
        IDML_DIR = args.idml_dir
        SPREAD_DIR = os.path.join(IDML_DIR, 'Spreads')
        STORY_DIR = os.path.join(IDML_DIR, 'Stories')
    if args.links_dir:
        LINKS_DIR = args.links_dir
    if args.spreads:
        TARGET_SPREADS = [s.strip() for s in args.spreads.split(',')]

    reset_table_registry()
    all_items = []

    for spread_file in TARGET_SPREADS:
        groups = extract_question_groups_from_spread(spread_file)

        for g in groups:
            # Read badge number
            badge_paras = read_story_paragraphs(g['badge_story'])
            badge_number = ''
            for style, text in badge_paras:
                if text.strip():
                    badge_number = text.strip()
                    break

            # Read content paragraphs
            content_paras = read_story_paragraphs(g['content_story'])

            # Parse into structured item
            item = parse_item_from_paragraphs(content_paras, badge_number)

            # Extract 보기 from inline TextFrames in the content story
            bogi = extract_bogi_from_content_story(g['content_story'])
            if bogi:
                item['보기'] = bogi

            # Extract inline tables from inline TextFrame stories
            inline_table_results = extract_inline_tables_from_content_story(g['content_story'])
            if inline_table_results:
                all_tables = []
                for result in inline_table_results:
                    # Collect non-empty, non-table paragraphs as context text
                    context_parts = []
                    for style, text in result['paragraphs']:
                        if style == '__table__':
                            # Register the table and insert a reference
                            table_id = register_table(text)
                            context_parts.append(f'{{{{{table_id}}}}}')
                        elif text.strip():
                            context_parts.append(text.strip())
                    all_tables.append('\n'.join(context_parts))
                item['실험'] = '\n'.join(all_tables) if all_tables else None
            else:
                item['실험'] = None

            # 후처리: 테이블 형식 선지 감지 및 변환
            try_extract_table_senji(item)

            # 정답 추출: content story + inline stories에서 \ue34c + ①~⑤ 패턴
            answer = extract_answer_from_story(g['content_story'])
            item['정답'] = answer

            # 이미지 추출: content story + inline stories에서 이미지 링크 수집
            image_filenames = extract_images_from_story(g['content_story'])
            if image_filenames:
                image_info = resolve_image_paths(image_filenames)
                item['이미지'] = image_info
            else:
                item['이미지'] = []

            all_items.append(item)

    # Sort by badge number (01-32)
    all_items.sort(key=lambda x: int(x['number']) if x['number'].isdigit() else 999)

    # Verify we got 32 items
    numbers = [it['number'] for it in all_items]
    print(f"Total items extracted: {len(all_items)}", file=sys.stderr)
    print(f"Numbers: {numbers}", file=sys.stderr)

    # 후처리: 교사용풀이에서 정답 마커(\ue34c ①~⑤) 제거
    for it in all_items:
        gyosa = it.get('교사용풀이', '')
        if gyosa:
            it['교사용풀이'] = _ANSWER_RE.sub('', gyosa).rstrip()

    # Check for any issues
    bogi_count = sum(1 for it in all_items if it.get('보기'))
    answer_count = sum(1 for it in all_items if it.get('정답') is not None)
    img_total = sum(len(it.get('이미지', [])) for it in all_items)
    img_found = sum(1 for it in all_items for img in it.get('이미지', []) if img['exists'])
    img_missing = img_total - img_found
    print(f"Items with 보기: {bogi_count}", file=sys.stderr)
    print(f"Items with 정답: {answer_count}/{len(all_items)}", file=sys.stderr)
    print(f"Images: {img_total} total, {img_found} found in Links/, {img_missing} missing", file=sys.stderr)

    for it in all_items:
        issues = []
        if not it['년도출처']:
            issues.append('missing 년도출처')
        if not it['발문1']:
            issues.append('missing 발문1')
        if not it['선지']:
            issues.append('missing 선지')
        if it.get('정답') is None:
            issues.append('missing 정답')
        # Check if item references 보기 in text but has no 보기 content
        all_text = it.get('발문1', '') + it.get('발문2', '')
        if '보기' in all_text and not it.get('보기'):
            issues.append('references 보기 but 보기 is null')
        # 이미지 누락 경고
        missing_imgs = [img['filename'] for img in it.get('이미지', []) if not img['exists']]
        if missing_imgs:
            issues.append(f'missing images: {", ".join(missing_imgs)}')
        if issues:
            print(f"  Item {it['number']}: {', '.join(issues)}", file=sys.stderr)

    # Output JSON to stdout — tables separated as top-level items
    output = {
        'tables': _tables,
        'items': all_items,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
