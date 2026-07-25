#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 Markdown 文档中的仓库内相对链接均可达。

1.x 的文档大量链接指向已不存在的路径,读者点开就是 404。这个检查确保
2.0 不重蹈覆辙。外部 http(s) 链接不检查(需要联网,且失效不应阻断构建)。
"""
import pathlib
import re
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent.parent
LINK = re.compile(r'\[[^\]]*\]\(([^)]+)\)')
SKIP_DIRS = {'.git', 'node_modules', 'target', 'build', 'dist'}


def main():
    broken = []
    checked = 0

    for md in ROOT.rglob('*.md'):
        if any(part in SKIP_DIRS for part in md.parts):
            continue
        try:
            text = md.read_text(encoding='utf-8')
        except (UnicodeDecodeError, OSError):
            continue

        for lineno, line in enumerate(text.splitlines(), 1):
            for m in LINK.finditer(line):
                target = m.group(1).strip()
                if target.startswith(('http://', 'https://', 'mailto:', '#')):
                    continue
                # 去掉锚点与 URL 编码
                path_part = urllib.parse.unquote(target.split('#', 1)[0])
                if not path_part:
                    continue
                checked += 1
                resolved = (md.parent / path_part).resolve()
                if not resolved.exists():
                    broken.append((md.relative_to(ROOT), lineno, target))

    print(f'检查了 {checked} 个仓库内链接')
    if broken:
        print(f'\n发现 {len(broken)} 个失效链接:')
        for p, ln, t in broken:
            print(f'  {p}:{ln}  ->  {t}')
        return 1
    print('全部可达')
    return 0


if __name__ == '__main__':
    sys.exit(main())
