#!/usr/bin/env python3
"""Generate publish-wiki.yml with precise YAML indentation."""

SP2 = "   "
SP4 = SP2 * 2
SP6 = SP2 * 3
SP8 = SP2 * 4
SP10 = SP2 * 5
SP12 = SP2 * 6

lines = []
lines.append("name: Publish Wiki")
lines.append("")
lines.append("on:")
lines.append(f"{SP2}push:")
lines.append(f"{SP4}branches: [main]")
lines.append(f"{SP4}paths: ['docs/**']")
lines.append(f"{SP2}workflow_dispatch:")
lines.append("")
lines.append("permissions:")
lines.append(f"{SP2}contents: write")
lines.append("")
lines.append("jobs:")
lines.append(f"{SP2}publish-wiki:")
lines.append(f"{SP4}runs-on: ubuntu-latest")
lines.append(f"{SP4}steps:")

# Step 1: Checkout
lines.append(f"{SP6}- name: Checkout repo")
lines.append(f"{SP8}uses: actions/checkout@v4")
lines.append(f"{SP8}with:")
lines.append(f"{SP10}persist-credentials: true")
lines.append("")

# Step 2: Set up Python
lines.append(f"{SP6}- name: Set up Python for image dimensions")
lines.append(f"{SP8}uses: actions/setup-python@v5")
lines.append(f"{SP8}with:")
lines.append(f"{SP10}python-version: '3.11'")
lines.append("")

# Step 3: Install Pillow
lines.append(f"{SP6}- name: Install Pillow for image processing")
lines.append(f"{SP8}run: pip install Pillow")
lines.append("")

# Step 4: Convert docs
lines.append(f"{SP6}- name: Convert docs to wiki format")
lines.append(f"{SP8}run: |")
lines.append(f"{SP10}chmod +x ./scripts/docs-converter.py")
lines.append(f"{SP10}python3 ./scripts/docs-converter.py docs wiki-output")
lines.append("")

# Step 5: Push to wiki repo
lines.append(f"{SP6}- name: Push to wiki repo")
lines.append(f"{SP8}env:")
lines.append(f"{SP10}WIKI_TOKEN: ${{{{ secrets.WIKI_PAT }}}}")
lines.append(f"{SP8}run: |")
lines.append(f"{SP10}cd wiki-output")
lines.append("")
lines.append(f"{SP12}# Clone or init the wiki repo")
lines.append(f'{SP10}if git ls-remote --heads "https://x-access-token:${{WIKI_TOKEN}}@github.com/${{{{ github.repository }}}}.wiki.git" 2>/dev/null | grep -q .; then')
lines.append(f'      git clone "https://x-access-token:${{WIKI_TOKEN}}@github.com/${{{{ github.repository }}}}.wiki.git" wiki-repo')
lines.append(f"      cd wiki-repo")
lines.append(f"      rm -rf *")
lines.append(f"{SP8}    else")
lines.append(f"      mkdir wiki-repo")
lines.append(f"      cd wiki-repo")
lines.append(f"      git init")
lines.append(f'      git config user.name "github-actions[bot]"')
lines.append(f'      git config user.email "github-actions[bot]@users.noreply.github.com"')
lines.append(f"{SP8}    fi")
lines.append("")
lines.append(f"{SP12}# Copy converted pages and images")
lines.append(f"{SP10}cp -r ../pages/* . 2>/dev/null || true")
lines.append(f"{SP10}cp -r ../images/* . 2>/dev/null || true")
lines.append("")
lines.append(f"{SP12}# Stage and commit")
lines.append(f"{SP10}git add .")
lines.append(f"{SP10}if git diff --cached --quiet; then")
lines.append(f'      echo "No changes to commit."')
lines.append(f"      exit 0")
lines.append(f"{SP8}    fi")
lines.append("")
lines.append(f'    git commit -m "docs: update wiki from docs/ [ci skip]"')
lines.append(f'    git push "https://x-access-token:${{WIKI_TOKEN}}@github.com/${{{{ github.repository }}}}.wiki.git" master')

content = "\n".join(lines) + "\n"

with open('.github/workflows/publish-wiki.yml', 'w') as f:
    f.write(content)

# Verify indentation around steps:
print("=== Indentation check (lines 15-25) ===")
for i, line in enumerate(lines[14:25], 15):
    spaces = len(line) - len(line.lstrip()) if line.strip() else 0
    print(f"Line {i}: {spaces} spaces | {line[:60] if line.strip() else '(empty)'}")

print("\n=== YAML validation ===")
try:
    import yaml
    with open('.github/workflows/publish-wiki.yml') as f:
        yaml.safe_load(f)
    print("Valid YAML!")
except ImportError:
    print("PyYAML not installed, skipping validation")
except Exception as e:
    print(f"Invalid YAML: {e}")
