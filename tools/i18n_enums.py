#!/usr/bin/env python3
"""一次性修正：枚举常量构造参数里的 tr("…") 在类加载时就求值，语言切换后不会刷新。
把这类枚举的 String 参数改成保存中文原文的私有字段，再用 getter 在每次读取时翻译。"""
import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "app/src/main/java"


def matching(s, i, open_c, close_c):
    depth = 0
    while i < len(s):
        if s[i] == '"':
            i += 1
            while s[i] != '"':
                i += 2 if s[i] == "\\" else 1
        elif s[i] == open_c: depth += 1
        elif s[i] == close_c:
            depth -= 1
            if depth == 0: return i
        i += 1
    raise ValueError


def fix(path: Path) -> bool:
    s = path.read_text(encoding="utf-8")
    changed = False
    pos = 0
    while True:
        m = re.search(r"enum class (\w+)\(", s[pos:])
        if not m: break
        start = pos + m.start()
        p_open = pos + m.end() - 1
        p_close = matching(s, p_open, "(", ")")
        brace = s.index("{", p_close)
        body_end = matching(s, brace, "{", "}")
        body = s[brace + 1:body_end]
        # 常量区：到第一个深度 0 的分号，没有就到末尾
        depth, semi = 0, None
        i = 0
        while i < len(body):
            c = body[i]
            if c == '"':
                i += 1
                while body[i] != '"': i += 2 if body[i] == "\\" else 1
            elif c in "({": depth += 1
            elif c in ")}": depth -= 1
            elif c == ";" and depth == 0:
                semi = i; break
            i += 1
        entries = body[:semi] if semi is not None else body
        if not re.search(r'\btr\("', entries):
            pos = body_end
            continue
        params = s[p_open + 1:p_close]
        names = re.findall(r"val (\w+): String(?=\s*(?:,|$|=))", params.replace("\n", " "))
        new_params = params
        for n in names:
            new_params = re.sub(rf"\bval {n}: String", f"private val {n}Zh: String", new_params, count=1)
        new_entries = re.sub(r'\btr\(("(?:[^"\\\n]|\\.)*")\)', r"\1", entries)
        getters = "".join(f"\n    val {n}: String get() = tr({n}Zh)" for n in names)
        if semi is not None:
            new_body = new_entries + ";" + getters + body[semi + 1:]
        else:
            stripped = new_entries.rstrip()
            trailing = new_entries[len(stripped):]
            if stripped.endswith(","): stripped = stripped[:-1]
            new_body = stripped + ";" + getters + ("\n" if "\n" in trailing else " ") 
        s = s[:p_open + 1] + new_params + s[p_close:brace + 1] + new_body + s[body_end:]
        changed = True
        pos = p_open + len(new_params) + 1  # 继续往后找
    if changed: path.write_text(s, encoding="utf-8")
    return changed


for f in sorted(SRC.rglob("*.kt")):
    if fix(f): print("fixed", f.relative_to(SRC.parent.parent.parent))
