#!/usr/bin/env python3
"""
docs-converter.py — Convert docs/ → GitHub Wiki pages + attachments

Usage:
    python3 scripts/docs-converter.py [source_dir] [output_dir]

  source_dir    Path to the docs/ directory (default: ./docs)
  output_dir    Where to write converted wiki files (default: ./wiki-output)

Workflow:
    1. Reads all .md files in source_dir/** (excluding index.md → homepage)
    2. Parses YAML frontmatter (title, slug, tags)
    3. Strips frontmatter from content
    4. Copies images/docs/images/ → output/images/ as wiki attachments
    5. Outputs clean markdown to output/pages/<slug>.wiki

Wiki repo layout (what gets pushed):
   pages/
     getting-started/install.wiki
     user-guide/bulk-actions.wiki
   images/
     ntp-screenshot.png
"""

import os
import sys
import re
import shutil
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    Image = None


def parse_frontmatter(text):
    """Parse YAML frontmatter and return (metadata_dict, content_without_frontmatter)."""
    metadata = {}
    
    # Match frontmatter block between --- delimiters
    match = re.match(r'^---\s*\n(.*?)\n---\s*\n(.*)', text, re.DOTALL)
    if not match:
        return metadata, text
    
    fm_block = match.group(1)
    content = match.group(2)
    
    # Parse key: value pairs
    for line in fm_block.split('\n'):
        line = line.strip()
        if ':' not in line:
            continue
        
        key, _, value = line.partition(':')
        key = key.strip().lower()
        value = value.strip()
        
        # Strip surrounding quotes
        if (value.startswith('"') and value.endswith('"')) or \
           (value.startswith("'") and value.endswith("'")):
            value = value[1:-1]
        
        metadata[key] = value
    
    return metadata, content


def get_image_dimensions(img_path):
    """Get image width x height in pixels."""
    if Image is None:
        return 0, 0
    
    try:
        with Image.open(img_path) as img:
            return img.size  # (width, height)
    except Exception:
        return 0, 0


def replace_image_paths(content, image_files):
    """Replace markdown image paths with wiki attachment paths and apply size control."""
    
    # Pattern to match markdown images: ![alt](path) or ![alt]( relative/path)
    img_pattern = re.compile(r'!\[([^\]]*)\]\(([^)]+)\)')
    
    def replace_img(match):
        alt = match.group(1)
        path = match.group(2).strip()
        
        # Check if this image is in our images directory
        for img_rel in image_files:
            # Match various path forms: images/foo.png, ../images/foo.png, ./images/foo.png
            patterns_to_try = [
                f'images/{img_rel}',
                f'../images/{img_rel}',
                f'./images/{img_rel}',
                f'../../images/{img_rel}',
            ]
            
            if any(path.endswith(p) for p in patterns_to_try):
                # This is a wiki image — replace with <img> tag
                img_path = os.path.join('docs', 'images', img_rel)
                width, height = get_image_dimensions(img_path)
                
                if width > 0 and height > 0:
                    # Apply size control (max 600px width)
                    max_width = 600
                    if width > max_width:
                        scale = max_width * 100 / width
                        new_width = max_width
                        new_height = int(height * scale / 100)
                        return f'<img src="../images/{img_rel}" alt="{alt}" width="{new_width}" height="{new_height}">'
                    else:
                        # Use original dimensions
                        return f'<img src="../images/{img_rel}" alt="{alt}" width="{width}" height="{height}">'
                else:
                    # No dimensions available
                    return f'<img src="../images/{img_rel}" alt="{alt}">'
        
        # Not a wiki image, leave as-is
        return match.group(0)
    
    return img_pattern.sub(replace_img, content)


def main():
    source_dir = sys.argv[1] if len(sys.argv) > 1 else 'docs'
    output_dir = sys.argv[2] if len(sys.argv) > 2 else 'wiki-output'
    
    source_path = Path(source_dir)
    output_path = Path(output_dir)
    
    if not source_path.is_dir():
        print(f"ERROR: Source directory '{source_dir}' not found.")
        sys.exit(1)
    
    # Clean & recreate output dirs
    if output_path.exists():
        shutil.rmtree(output_path)
    (output_path / 'images').mkdir(parents=True, exist_ok=True)
    
    print("=== docs-converter ===")
    print(f"  Source : {source_dir}")
    print(f"  Output : {output_dir}")
    print()
    
    page_count = 0
    img_count = 0
    
    # ── Collect images first ───────────────────────────────────────────────
    image_files = []  # relative paths from docs/images/
    
    images_dir = source_path / 'images'
    if images_dir.is_dir():
        for ext in ('*.png', '*.jpg', '*.jpeg', '*.gif', '*.svg', '*.webp'):
            for img_file in images_dir.rglob(ext):
                rel = str(img_file.relative_to(images_dir))
                # Copy to output
                dest = output_path / 'images' / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(img_file, dest)
                image_files.append(rel)
                img_count += 1
    
    print(f"  Images copied : {img_count}")
    
    # ── Process each .md file ──────────────────────────────────────────────
    md_files = sorted(source_path.rglob('*.md'))
    
    for md_file in md_files:
        # Skip index.md → homepage
        if md_file.name == 'index.md':
            continue
        
        text = md_file.read_text(encoding='utf-8')
        
        # Parse frontmatter
        metadata, content = parse_frontmatter(text)
        
        # Defaults
        title = metadata.get('title', '')
        slug = metadata.get('slug', '')
        
        if not title:
            # Derive from filename: "bulk-actions.md" → "Bulk Actions"
            title = md_file.stem.replace('-', ' ').title()
        
        if not slug:
            # Default: relative path from source dir, no extension
            slug = str(md_file.relative_to(source_path)).replace('.md', '')
        
        # Replace image paths with wiki attachment paths
        content = replace_image_paths(content, image_files)
        
        # Write wiki page
        outpath = output_path / 'pages' / f"{slug}.wiki"
        outpath.parent.mkdir(parents=True, exist_ok=True)
        outpath.write_text(f"# {title}\n\n{content}", encoding='utf-8')
        
        page_count += 1
    
    # ── Homepage (index.wiki) ──────────────────────────────────────────────
    index_file = source_path / 'index.md'
    if index_file.is_file():
        text = index_file.read_text(encoding='utf-8')
        metadata, content = parse_frontmatter(text)
        
        outpath = output_path / 'pages' / 'index.wiki'
        outpath.write_text(content, encoding='utf-8')
        page_count += 1
    
    print(f"  Pages created : {page_count}")
    print()
    print("=== Done ===")
    print(f"Wiki files are in: {output_dir}/")
    print("  pages/      → markdown wiki pages (.wiki extension)")
    print("  images/     → attachment images for inline use")


if __name__ == '__main__':
    main()
