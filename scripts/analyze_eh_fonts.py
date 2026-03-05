#!/usr/bin/env python3
"""
Analyze EH font TTF files to understand their cmap (character map) tables.
EH fonts are custom math fonts used in Korean math textbooks.
"""

import os
import sys
from fontTools.ttLib import TTFont
from collections import defaultdict

FONT_DIR = '/Users/zerone_rent/Documents/jobs/(교)중3수학/(교)중3수학(098~135)3단원/Document fonts/'

# Characters of special interest
SPECIAL_CHARS = {
    0x00DB: 'U+00DB (Û) Latin Capital U with Circumflex',
    0x00B2: 'U+00B2 (²) Superscript Two',
    0x00B3: 'U+00B3 (³) Superscript Three',
    0x00B9: 'U+00B9 (¹) Superscript One',
    0x0078: 'U+0078 (x)',
    0x0079: 'U+0079 (y)',
    0x0061: 'U+0061 (a)',
    0x0062: 'U+0062 (b)',
    0x0063: 'U+0063 (c)',
    0x003D: 'U+003D (=)',
    0x002B: 'U+002B (+)',
    0x002D: 'U+002D (-)',
    0x0030: 'U+0030 (0)',
    0x0031: 'U+0031 (1)',
    0x0032: 'U+0032 (2)',
    0x0033: 'U+0033 (3)',
    0x0034: 'U+0034 (4)',
    0x0035: 'U+0035 (5)',
    0x0036: 'U+0036 (6)',
    0x0037: 'U+0037 (7)',
    0x0038: 'U+0038 (8)',
    0x0039: 'U+0039 (9)',
    0x00D7: 'U+00D7 (x) Multiplication Sign',
    0x00F7: 'U+00F7 (÷) Division Sign',
    0x2260: 'U+2260 (≠) Not Equal',
    0x2264: 'U+2264 (≤) Less Than or Equal',
    0x2265: 'U+2265 (≥) Greater Than or Equal',
    0x221A: 'U+221A (√) Square Root',
    0x03B1: 'U+03B1 (α) Alpha',
    0x03B2: 'U+03B2 (β) Beta',
    0x03B3: 'U+03B3 (γ) Gamma',
    0x03C0: 'U+03C0 (π) Pi',
    0x2070: 'U+2070 (⁰) Superscript Zero',
    0x00B0: 'U+00B0 (°) Degree Sign',
    0x2032: 'U+2032 (′) Prime',
    0x2033: 'U+2033 (″) Double Prime',
    0x00AB: 'U+00AB («) Left Guillemet',
    0x00BB: 'U+00BB (») Right Guillemet',
}

# Extended range: check all characters from U+0020 to U+00FF (Basic Latin + Latin-1 Supplement)
# plus common math symbols


def get_char_display(codepoint):
    """Get a displayable representation of a character."""
    try:
        ch = chr(codepoint)
        if codepoint < 0x20:
            return f'<control-{codepoint:04X}>'
        return ch
    except:
        return f'<{codepoint:04X}>'


def analyze_font(font_path, font_name, verbose=False):
    """Analyze a single font file's cmap table."""
    print(f"\n{'='*80}")
    print(f"FONT: {font_name}")
    print(f"PATH: {font_path}")
    print(f"{'='*80}")

    try:
        font = TTFont(font_path)
    except Exception as e:
        print(f"  ERROR: Could not open font: {e}")
        return None

    # Get font metadata
    if 'name' in font:
        name_table = font['name']
        for record in name_table.names:
            if record.nameID in (1, 2, 4, 6):  # Family, Subfamily, Full Name, PostScript Name
                try:
                    name_str = record.toUnicode()
                    labels = {1: 'Family', 2: 'Subfamily', 4: 'Full Name', 6: 'PostScript'}
                    print(f"  {labels.get(record.nameID, str(record.nameID))}: {name_str}")
                except:
                    pass

    # Get cmap table
    cmap_table = font.getBestCmap()
    if not cmap_table:
        print("  ERROR: No cmap table found!")
        font.close()
        return None

    print(f"\n  Total glyphs mapped: {len(cmap_table)}")

    # Organize by Unicode ranges
    ranges = {
        'ASCII Control (0x00-0x1F)': [],
        'ASCII Printable (0x20-0x7E)': [],
        'Latin-1 Supplement (0x80-0xFF)': [],
        'Latin Extended-A (0x100-0x17F)': [],
        'Latin Extended-B (0x180-0x24F)': [],
        'Greek (0x370-0x3FF)': [],
        'General Punctuation (0x2000-0x206F)': [],
        'Superscripts/Subscripts (0x2070-0x209F)': [],
        'Math Operators (0x2200-0x22FF)': [],
        'Misc Math (0x2A00-0x2AFF)': [],
        'CJK (0x3000+)': [],
        'Private Use Area (0xE000-0xF8FF)': [],
        'Other': [],
    }

    for codepoint, glyph_name in sorted(cmap_table.items()):
        if codepoint <= 0x1F:
            ranges['ASCII Control (0x00-0x1F)'].append((codepoint, glyph_name))
        elif codepoint <= 0x7E:
            ranges['ASCII Printable (0x20-0x7E)'].append((codepoint, glyph_name))
        elif codepoint <= 0xFF:
            ranges['Latin-1 Supplement (0x80-0xFF)'].append((codepoint, glyph_name))
        elif codepoint <= 0x17F:
            ranges['Latin Extended-A (0x100-0x17F)'].append((codepoint, glyph_name))
        elif codepoint <= 0x24F:
            ranges['Latin Extended-B (0x180-0x24F)'].append((codepoint, glyph_name))
        elif 0x370 <= codepoint <= 0x3FF:
            ranges['Greek (0x370-0x3FF)'].append((codepoint, glyph_name))
        elif 0x2000 <= codepoint <= 0x206F:
            ranges['General Punctuation (0x2000-0x206F)'].append((codepoint, glyph_name))
        elif 0x2070 <= codepoint <= 0x209F:
            ranges['Superscripts/Subscripts (0x2070-0x209F)'].append((codepoint, glyph_name))
        elif 0x2200 <= codepoint <= 0x22FF:
            ranges['Math Operators (0x2200-0x22FF)'].append((codepoint, glyph_name))
        elif 0x2A00 <= codepoint <= 0x2AFF:
            ranges['Misc Math (0x2A00-0x2AFF)'].append((codepoint, glyph_name))
        elif codepoint >= 0x3000 and codepoint < 0xE000:
            ranges['CJK (0x3000+)'].append((codepoint, glyph_name))
        elif 0xE000 <= codepoint <= 0xF8FF:
            ranges['Private Use Area (0xE000-0xF8FF)'].append((codepoint, glyph_name))
        else:
            ranges['Other'].append((codepoint, glyph_name))

    # Print each range
    for range_name, chars in ranges.items():
        if chars:
            print(f"\n  --- {range_name} ({len(chars)} chars) ---")
            for cp, gn in chars:
                display = get_char_display(cp)
                marker = ' <<<' if cp in SPECIAL_CHARS else ''
                print(f"    U+{cp:04X} '{display}' -> glyph: {gn}{marker}")

    # Special character check
    print(f"\n  --- SPECIAL CHARACTER CHECK ---")
    for cp, desc in sorted(SPECIAL_CHARS.items()):
        if cp in cmap_table:
            gn = cmap_table[cp]
            print(f"    FOUND: {desc} -> glyph: {gn}")
        else:
            print(f"    MISSING: {desc}")

    # Check for potential remapping: look for glyphs whose names suggest
    # they should be at different codepoints
    print(f"\n  --- GLYPH NAME ANALYSIS (potential remapping) ---")
    suspicious = []
    for cp, gn in sorted(cmap_table.items()):
        gn_lower = gn.lower()
        # Check if glyph name suggests a different character
        if 'super' in gn_lower or 'sub' in gn_lower:
            suspicious.append((cp, gn, 'super/subscript glyph name'))
        elif gn_lower.startswith('uni') and len(gn_lower) >= 7:
            # glyph named uniXXXX but mapped to different codepoint
            try:
                implied_cp = int(gn_lower[3:7], 16)
                if implied_cp != cp:
                    suspicious.append((cp, gn, f'named as U+{implied_cp:04X} but mapped to U+{cp:04X}'))
            except ValueError:
                pass

    if suspicious:
        for cp, gn, reason in suspicious:
            display = get_char_display(cp)
            print(f"    U+{cp:04X} '{display}' -> glyph: {gn}  ({reason})")
    else:
        print(f"    No obvious remapping detected from glyph names")

    # Check 'post' table for glyph names
    if 'post' in font:
        post = font['post']
        glyph_order = font.getGlyphOrder()
        print(f"\n  --- ALL GLYPH NAMES (from glyph order, total: {len(glyph_order)}) ---")
        for i, name in enumerate(glyph_order):
            # Try to find which codepoint maps to this glyph
            mapped_cps = [cp for cp, gn in cmap_table.items() if gn == name]
            if mapped_cps:
                cp_str = ', '.join(f'U+{cp:04X}' for cp in mapped_cps)
                print(f"    [{i:3d}] {name} <- {cp_str}")
            else:
                print(f"    [{i:3d}] {name} (unmapped)")

    font.close()
    return cmap_table


def main():
    # Get all EH font files
    eh_fonts = []
    for f in sorted(os.listdir(FONT_DIR)):
        if f.startswith('EH') and f.endswith('.ttf'):
            eh_fonts.append(f)

    print(f"Found {len(eh_fonts)} EH font files")
    print(f"Font directory: {FONT_DIR}")

    # Priority fonts first
    priority = ['EH수식-Plain.ttf', 'EH상부자-Plain.ttf', 'EH상부자-Bold.ttf',
                'EH상부자-Italic.ttf', 'EH상부자-BoldItalic.ttf']

    # Analyze priority fonts first
    analyzed = set()
    all_cmaps = {}

    # If command line arg specifies a single font, only analyze that
    if len(sys.argv) > 1:
        target = sys.argv[1]
        matching = [f for f in eh_fonts if target.lower() in f.lower()]
        if matching:
            eh_fonts = matching
            priority = []
        else:
            print(f"No font matching '{target}' found")
            return

    for font_name in priority:
        if font_name in eh_fonts:
            font_path = os.path.join(FONT_DIR, font_name)
            cmap = analyze_font(font_path, font_name)
            if cmap:
                all_cmaps[font_name] = cmap
            analyzed.add(font_name)

    # Then remaining fonts
    for font_name in eh_fonts:
        if font_name not in analyzed:
            font_path = os.path.join(FONT_DIR, font_name)
            cmap = analyze_font(font_path, font_name)
            if cmap:
                all_cmaps[font_name] = cmap

    # Cross-font comparison: check how the same codepoint maps differently
    print(f"\n{'='*80}")
    print("CROSS-FONT COMPARISON: Same codepoint, different glyph names")
    print(f"{'='*80}")

    # Collect all codepoints
    all_cps = set()
    for cmap in all_cmaps.values():
        all_cps.update(cmap.keys())

    for cp in sorted(all_cps):
        mappings = {}
        for fname, cmap in all_cmaps.items():
            if cp in cmap:
                gn = cmap[cp]
                if gn not in mappings:
                    mappings[gn] = []
                mappings[gn].append(fname)

        if len(mappings) > 1:
            display = get_char_display(cp)
            print(f"\n  U+{cp:04X} '{display}' has DIFFERENT glyph names across fonts:")
            for gn, fonts in mappings.items():
                print(f"    glyph '{gn}': {', '.join(fonts)}")

    print(f"\n{'='*80}")
    print("ANALYSIS COMPLETE")
    print(f"{'='*80}")


if __name__ == '__main__':
    main()
