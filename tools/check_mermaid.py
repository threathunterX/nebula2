#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验文档中的 Mermaid 图能否被解析。

GitHub 原生渲染 Mermaid,但**语法错误不会以任何方式告诉作者** —— 页面上只是显示一块
红色的错误区,而写文档的人通常不会再回去看渲染结果。这个检查在 CI 上把图真正编译
一遍。

需要 mermaid-cli(`npx @mermaid-js/mermaid-cli`)。未安装时跳过并明确说明,
不静默通过。
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", "node_modules", "target", "build", "dist", ".venv", "venv"}
BLOCK = re.compile(r"```mermaid\n(.*?)```", re.S)


def collect():
    out = []
    for md in sorted(ROOT.rglob("*.md")):
        if any(p in SKIP_DIRS for p in md.parts):
            continue
        try:
            text = md.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for i, m in enumerate(BLOCK.finditer(text), 1):
            line = text[: m.start()].count("\n") + 1
            out.append((md.relative_to(ROOT), line, i, m.group(1)))
    return out


def main():
    blocks = collect()
    print(f"发现 {len(blocks)} 张 Mermaid 图")
    if not blocks:
        return 0

    mmdc = shutil.which("mmdc")
    if not mmdc:
        print("未找到 mmdc,跳过渲染校验。")
        print("  本地安装:npm i -g @mermaid-js/mermaid-cli")
        print("  (CI 上不会跳过 —— 那里始终安装)")
        return 0

    failed = []
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        # puppeteer 在 CI 容器里需要 --no-sandbox
        cfg = tmp / "puppeteer.json"
        cfg.write_text('{"args":["--no-sandbox","--disable-setuid-sandbox"]}')
        for path, line, idx, src in blocks:
            f = tmp / f"d{idx}_{abs(hash(str(path)))}.mmd"
            f.write_text(src, encoding="utf-8")
            r = subprocess.run(
                [mmdc, "-i", str(f), "-o", str(f.with_suffix(".svg")),
                 "-p", str(cfg), "-q"],
                capture_output=True, text=True)
            if r.returncode != 0:
                err = (r.stderr or r.stdout).strip().splitlines()
                failed.append((path, line, err[:3]))

    if failed:
        print(f"\n{len(failed)} 张图无法渲染:")
        for path, line, err in failed:
            print(f"  {path}:{line}")
            for e in err:
                print(f"      {e}")
        return 1
    print("全部可渲染")
    return 0


if __name__ == "__main__":
    sys.exit(main())
