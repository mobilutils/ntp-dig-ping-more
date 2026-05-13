#!/usr/bin/env bash
# =============================================================================
# docs-converter.sh — Convert docs/ → GitHub Wiki pages + attachments
#
# Usage:
#    ./scripts/docs-converter.sh [source_dir] [output_dir]
#
#   source_dir    Path to the docs/ directory (default: ./docs)
#   output_dir    Where to write converted wiki files (default: ./wiki-output)
#
# Workflow:
#    1. Reads all .md files in source_dir/** (excluding index.md → homepage)
#    2. Parses YAML frontmatter (title, slug, tags)
#    3. Strips frontmatter from content
#    4. Copies images/docs/images/ → output/images/ as wiki attachments
#    5. Outputs clean markdown to output/pages/<slug>.wiki
#
# Wiki repo layout (what gets pushed):
#    pages/
#      getting-started/install.wiki
#      user-guide/bulk-actions.wiki
#    images/
#      ntp-screenshot.png
# =============================================================================
set -euo pipefail

# ── Defaults ─────────────────────────────────────────────────────────────────
SOURCE_DIR="${1:-docs}"
OUTPUT_DIR="${2:-wiki-output}"

if [[ ! -d "$SOURCE_DIR" ]]; then
  echo "ERROR: Source directory '$SOURCE_DIR' not found." >&2
  exit 1
fi

# Clean & recreate output dirs
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/images"

echo "=== docs-converter ==="
echo "  Source : $SOURCE_DIR"
echo "  Output : $OUTPUT_DIR"
echo ""

PAGE_COUNT=0
IMG_COUNT=0

# ── Image dimension helpers ──────────────────────────────────────────────────
get_image_dimensions() {
  local img_path="$1"

  # Try ImageMagick identify (common on GitHub Actions runners)
  if command -v identify &> /dev/null; then
    local dims
    dims=$(identify -format "%wx%h" "$img_path" 2>/dev/null | head -1)
    if [[ -n "$dims" ]]; then
      echo "$dims"
      return 0
    fi
  fi

  # Try python3 PIL (also common on GitHub Actions runners)
  if command -v python3 &> /dev/null; then
    local dims
    dims=$(python3 -c "
from PIL import Image
img = Image.open('$img_path')
print(f'{img.width}x{img.height}')
" 2>/dev/null || true)
    if [[ -n "$dims" ]]; then
      echo "$dims"
      return 0
    fi
  fi

  # Default fallback (no size control)
  echo "0x0"
}

# ── Collect images first (so paths are known) ────────────────────────────────
declare -A IMG_PATHS     # key: relative path from docs/images/ → value in output
declare -A IMG_DIMS      # key: relative path → "widthxheight"

if [[ -d "$SOURCE_DIR/images" ]]; then
  while IFS= read -r -d '' img; do
    rel="${img#$SOURCE_DIR/images/}"
    cp -- "$img" "$OUTPUT_DIR/images/$rel"
    IMG_PATHS["$rel"]=1

    # Get dimensions for size control
    dims=$(get_image_dimensions "$img")
    IMG_DIMS["$rel"]="$dims"

    IMG_COUNT=$((IMG_COUNT + 1))
  done < <(find "$SOURCE_DIR/images" -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.gif' -o -iname '*.svg' -o -iname '*.webp' \) -print0)
fi

echo "  Images copied : $IMG_COUNT"

# ── Process each .md file ───────────────────────────────────────────────────
while IFS= read -r -d '' mdfile; do
  # Skip index.md → it becomes the homepage (index.wiki)
  basename_md=$(basename "$mdfile")
  if [[ "$basename_md" == "index.md" ]]; then
    continue
  fi

  content=""
  title=""
  slug=""

  # ── Parse frontmatter ────────────────────────────────────────────────────
  in_frontmatter=false
  frontmatter_done=false

  while IFS= read -r line; do
    if [[ "$line" == '---' ]] && [[ "$frontmatter_done" == false ]]; then
      if [[ "$in_frontmatter" == false ]]; then
        in_frontmatter=true
        continue
      else
        frontmatter_done=true
        continue
      fi
    fi

    if [[ "$in_frontmatter" == true ]]; then
      # Extract title
      if [[ "$line" =~ ^title:\ *(.*) ]]; then
        title="${BASH_REMATCH[1]}"
        # Strip surrounding quotes
        title="${title#\"}"
        title="${title%\"}"
        title="${title#\'}"
        title="${title%\'}"
      fi
      # Extract slug
      if [[ "$line" =~ ^slug:\ *(.*) ]]; then
        slug="${BASH_REMATCH[1]}"
        slug="${slug#\"}"
        slug="${slug%\"}"
        slug="${slug#\'}"
        slug="${slug%\'}"
      fi
    else
      content+="$line"$'\n'
    fi
  done < "$mdfile"

  # ── Defaults ─────────────────────────────────────────────────────────────
  if [[ -z "$title" ]]; then
    # Derive from filename: "bulk-actions.md" → "Bulk Actions"
    title=$(basename "$mdfile" .md | tr '-' ' ' | sed 's/\b\(.\)/\u\1/g')
  fi

  if [[ -z "$slug" ]]; then
    # Default: relative path from source dir, no extension
    slug="${mdfile#$SOURCE_DIR/}"
    slug="${slug%.md}"
  fi

  # ── Replace image paths with wiki attachment paths ───────────────────────
  # Markdown images: ![alt](path) → Wiki uses relative path from pages/ to images/
  if [[ -d "$SOURCE_DIR/images" ]]; then
    for rel in "${!IMG_PATHS[@]}"; do
      # Replace both relative and absolute references to images
      content=$(echo "$content" | sed -E "s!\](\([^)]*)images/$rel!\](../images/$rel!g")
      
      # If dimensions found, wrap with <img> tag for size control
      dims="${IMG_DIMS[$rel]:-0x0}"
      width="${dims%x*}"
      height="${dims#*x}"
      
      if [[ "$width" != "0" && "$height" != "0" ]]; then
        # Calculate scaled width (max 600px, maintain aspect ratio)
        max_width=600
        if [[ "$width" -gt "$max_width" ]]; then
          scale=$((max_width * 100 / width))
          new_width=$max_width
          new_height=$((height * scale / 100))
          # Replace markdown image with <img> tag for size control
          content=$(echo "$content" | sed -E "s!\[([^]]*)\]\(\.\./images/$rel\)!<img src=\"../images/$rel\" alt=\"\1\" width=\"$new_width\" height=\"$new_height\">!g")
        fi
      fi
    done
  fi

  # ── Write wiki page ──────────────────────────────────────────────────────
  outpath="$OUTPUT_DIR/pages/${slug}.wiki"
  mkdir -p "$(dirname "$outpath")"

  {
    echo "# $title"
    echo ""
    echo "$content"
  } > "$outpath"

  PAGE_COUNT=$((PAGE_COUNT + 1))
done < <(find "$SOURCE_DIR" -maxdepth 3 -type f -iname '*.md' ! -name 'index.md' -print0)

# ── Homepage (index.wiki) ───────────────────────────────────────────────────
if [[ -f "$SOURCE_DIR/index.md" ]]; then
  # Parse frontmatter for index too, or use title from content
  homepage_content=""
  in_frontmatter=false
  frontmatter_done=false

  while IFS= read -r line; do
    if [[ "$line" == '---' ]] && [[ "$frontmatter_done" == false ]]; then
      if [[ "$in_frontmatter" == false ]]; then
        in_frontmatter=true
        continue
      else
        frontmatter_done=true
        continue
      fi
    fi
    if [[ "$in_frontmatter" == true ]]; then
      continue
    fi
    homepage_content+="$line"$'\n'
  done < "$SOURCE_DIR/index.md"

  mkdir -p "$OUTPUT_DIR/pages"
  echo "$homepage_content" > "$OUTPUT_DIR/pages/index.wiki"
  PAGE_COUNT=$((PAGE_COUNT + 1))
fi

echo "  Pages created : $PAGE_COUNT"
echo ""
echo "=== Done ==="
echo "Wiki files are in: $OUTPUT_DIR/"
echo "  pages/     → markdown wiki pages (.wiki extension)"
echo "  images/    → attachment images for inline use"
