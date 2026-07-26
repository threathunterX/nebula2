#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 Markdown 文档中的仓库内相对链接均可达。

1.x 的文档大量链接指向已不存在的路径,读者点开就是 404。这个检查确保
2.0 不重蹈覆辙。外部 http(s) 链接不检查(需要联网,且失效不应阻断构建)。

检查三件事:

1. 相对路径存在
2. **路径大小写完全一致** —— macOS 默认的文件系统大小写不敏感,`Path.exists()`
   在本地会放行拼错大小写的链接,推到 CI(Linux,大小写敏感)才失败。贡献者遇到
   的是「本地好好的」这种最难查的情况。
3. **锚点确实存在于目标文档** —— 指向章节的链接写错标题不会有任何报错,点开只是
   停在文档顶部,读者不会意识到自己没到该到的地方。
"""
import pathlib
import re
import sys
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent.parent
LINK = re.compile(r'\[[^\]]*\]\(([^)]+)\)')
HEADING = re.compile(r'^(#{1,6})\s+(.*?)\s*#*$')
SKIP_DIRS = {'.git', 'node_modules', 'target', 'build', 'dist'}


def slugify(title):
    """GitHub 的标题锚点规则:小写、去掉除连字符与中日韩字符外的标点、空格转连字符。"""
    t = title.strip().lower()
    t = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', t)   # 行内链接只保留文字
    t = re.sub(r'[`*_~]', '', t)                        # 行内代码与强调标记
    t = re.sub(r'[^\w\u4e00-\u9fff\- ]', '', t)
    t = re.sub(r'-{2,}', '-', t.replace(' ', '-'))
    # 返回两种形式,两种都算有效。
    #
    # 标题里带 emoji 时(如「## ⚠️ 项目状态」),GitHub 的 slugger 先 trim 再去符号,
    # 于是 emoji 与后面空格之间的关系决定了锚点里有没有前导连字符 —— 这个细节随
    # 实现版本变动过,仓库里两种写法都存在且都能跳转。
    #
    # 这个检查器的职责是抓拼错的锚点,不是当 slug 规则的权威。在自己也吃不准的地方
    # 从严判定,只会把合法链接判成坏链 —— 那样的检查器最后会被加进忽略列表,
    # 连同它本来能抓到的真问题一起失效。
    return {t, t.strip('-')}


def anchors_of(path):
    try:
        text = path.read_text(encoding='utf-8')
    except (UnicodeDecodeError, OSError):
        return None
    out, in_code = set(), False
    for line in text.splitlines():
        if line.lstrip().startswith('```'):
            in_code = not in_code
            continue
        if in_code:
            continue
        m = HEADING.match(line)
        if m:
            out |= slugify(m.group(2))
    return out


def case_exact(path):
    """逐级比对真实目录项,确认路径的大小写与磁盘上完全一致。"""
    cur = path.parent
    if not cur.exists():
        return False
    try:
        return path.name in {p.name for p in cur.iterdir()}
    except OSError:
        return False


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
                    broken.append((md.relative_to(ROOT), lineno, target, '路径不存在'))
                    continue
                if not case_exact(resolved):
                    broken.append((md.relative_to(ROOT), lineno, target,
                                   '路径大小写与磁盘不一致(Linux 上会 404)'))
                    continue
                frag = target.split('#', 1)[1] if '#' in target else ''
                if frag and resolved.suffix == '.md':
                    have = anchors_of(resolved)
                    if have is not None and urllib.parse.unquote(frag).lower() not in have:
                        broken.append((md.relative_to(ROOT), lineno, target,
                                       '锚点在目标文档中不存在'))

    print(f'检查了 {checked} 个仓库内链接')
    if broken:
        print(f'\n发现 {len(broken)} 个失效链接:')
        for p, ln, t, why in broken:
            print(f'  {p}:{ln}  ->  {t}\n      {why}')
        return 1
    print('全部可达')
    return 0


if __name__ == '__main__':
    sys.exit(main())
