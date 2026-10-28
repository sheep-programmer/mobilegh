#!/usr/bin/env python3
"""
把 Kotlin 源码里的中文文案换成 tr("…") / trf("…{0}…", 参数) 调用，并导出词典键。

用法：
  python3 tools/i18n_extract.py --apply   # 改写源码，并把键写到 --keys 指定的文件
  python3 tools/i18n_extract.py --check   # 只检查：列出源码中还没有包装、也没有译文的中文文案

只处理 app/src/main/java 下的 .kt 文件。注释、不含汉字的字符串保持不动。
需要人工复核的位置（const val、注解参数、字符串解析类调用）会被跳过并打印出来。
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java"
CJK = re.compile(r"[\u4e00-\u9fff]")

# 这些方法的参数是用来解析/匹配文本的，不能翻译，跳过并报告
PARSE_CALL = re.compile(
    r"(?:Regex|toRegex|\.split|\.replace|\.replaceFirst|\.removePrefix|\.removeSuffix|\.startsWith|\.endsWith|"
    r"\.contains|\.substringAfter|\.substringBefore|\.substringAfterLast|\.substringBefore|\.indexOf|\.lastIndexOf|\.trim)\s*\(\s*$"
)
CONST_VAL = re.compile(r"const\s+val\s+(\w+)(\s*:\s*String)?\s*=\s*$")
ALREADY_WRAPPED = re.compile(r"\btr[fc]?\(\s*$")
ANNOTATION = re.compile(r"@\w+(?:\.\w+)*\s*\(\s*(?:\w+\s*=\s*)?$")


def unescape(src: str, raw: bool) -> str:
    if raw:
        return src
    out, i = [], 0
    while i < len(src):
        c = src[i]
        if c == "\\" and i + 1 < len(src):
            n = src[i + 1]
            simple = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", '"': '"', "'": "'", "\\": "\\", "$": "$"}
            if n in simple:
                out.append(simple[n]); i += 2; continue
            if n == "u" and i + 5 < len(src) + 0 and re.fullmatch(r"[0-9a-fA-F]{4}", src[i + 2:i + 6] or ""):
                out.append(chr(int(src[i + 2:i + 6], 16))); i += 6; continue
        out.append(c); i += 1
    return "".join(out)


class Transformer:
    def __init__(self, path: Path):
        self.path = path
        self.keys: list[str] = []
        self.skipped: list[str] = []
        self.used_tr = False
        self.used_trf = False

    # ---- 扫描一个字符串字面量，返回 (parts, end)：parts 为 [("lit", 源码文本) | ("expr", 表达式源码) | ("id", 标识符)]
    def parse_string(self, s: str, i: int):
        raw = s.startswith('"""', i)
        i += 3 if raw else 1
        parts, buf = [], []

        def flush():
            if buf:
                parts.append(("lit", "".join(buf))); buf.clear()

        while i < len(s):
            c = s[i]
            if raw:
                if s.startswith('"""', i):
                    # 原始字符串末尾允许多于三个引号，多出来的属于内容
                    j = i
                    while j < len(s) and s[j] == '"': j += 1
                    extra = j - i - 3
                    buf.extend('"' * extra)
                    flush()
                    return parts, i + 3 + extra, raw
            else:
                if c == '"':
                    flush()
                    return parts, i + 1, raw
                if c == "\\" and i + 1 < len(s):
                    buf.append(s[i:i + 2]); i += 2; continue
            if c == "$" and i + 1 < len(s):
                n = s[i + 1]
                if n == "{":
                    flush()
                    j = self.skip_braces(s, i + 1)
                    parts.append(("expr", s[i + 2:j - 1]))
                    i = j
                    continue
                m = re.match(r"[A-Za-z_][A-Za-z0-9_]*", s[i + 1:])
                if m:
                    flush()
                    parts.append(("id", m.group(0)))
                    i += 1 + len(m.group(0))
                    continue
            buf.append(c); i += 1
        raise ValueError("unterminated string in " + str(self.path))

    def skip_braces(self, s: str, i: int) -> int:
        """s[i] == '{'，返回匹配的 '}' 之后的位置（跳过其中的字符串、字符与注释）"""
        depth, i = 0, i
        while i < len(s):
            c = s[i]
            if c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0: return i + 1
            elif c == '"':
                _, i, _ = self.parse_string(s, i); continue
            elif c == "'":
                i = self.skip_char(s, i); continue
            elif s.startswith("//", i):
                i = s.find("\n", i); i = len(s) if i < 0 else i; continue
            elif s.startswith("/*", i):
                i = s.find("*/", i + 2) + 2; continue
            i += 1
        raise ValueError("unbalanced braces in " + str(self.path))

    def skip_char(self, s: str, i: int) -> int:
        j = i + 1
        if j < len(s) and s[j] == "\\": j += 2
        else: j += 1
        while j < len(s) and s[j] != "'": j += 1
        return j + 1

    # ---- 主转换：处理一段代码，返回新代码
    def code(self, s: str) -> str:
        out, i = [], 0
        while i < len(s):
            c = s[i]
            if s.startswith("//", i):
                j = s.find("\n", i); j = len(s) if j < 0 else j
                out.append(s[i:j]); i = j; continue
            if s.startswith("/*", i):
                depth, j = 1, i + 2
                while j < len(s) and depth:
                    if s.startswith("/*", j): depth += 1; j += 2
                    elif s.startswith("*/", j): depth -= 1; j += 2
                    else: j += 1
                out.append(s[i:j]); i = j; continue
            if c == "'":
                j = self.skip_char(s, i); out.append(s[i:j]); i = j; continue
            if c == '"':
                parts, end, raw = self.parse_string(s, i)
                original = s[i:end]
                head = "".join(out)
                out.append(self.literal(parts, raw, original, head))
                i = end; continue
            out.append(c); i += 1
        return "".join(out)

    def literal(self, parts, raw: bool, original: str, head: str) -> str:
        has_cjk = any(k == "lit" and CJK.search(v) for k, v in parts)
        quote = '"""' if raw else '"'
        if has_cjk and not raw and ALREADY_WRAPPED.search(head[-12:]) and not any(k in ("expr", "id") for k, _ in parts):
            # 已经包着 tr(…) / trf(…) / trc(…)：只登记键，不再包第二层
            self.keys.append(unescape(parts[0][1] if parts else "", raw))
            return original

        def rebuild(transform_exprs: bool) -> str:
            segs = []
            for k, v in parts:
                if k == "lit": segs.append(v)
                elif k == "id": segs.append("$" + v)
                else: segs.append("${" + (self.code(v) if transform_exprs else v) + "}")
            return quote + "".join(segs) + quote

        if not has_cjk:
            return rebuild(True) if any(k == "expr" for k, _ in parts) else original

        # 复核：三引号原始字符串（CSS、JS、GraphQL 一类的代码）、注解参数、解析类调用，保持原样
        tail = head[-80:]
        if raw or ANNOTATION.search(tail) or PARSE_CALL.search(tail):
            self.skipped.append(f"{self.path.relative_to(ROOT)}: {original[:60]}")
            return rebuild(True) if any(k == "expr" for k, _ in parts) else original

        has_expr = any(k in ("expr", "id") for k, _ in parts)
        if not has_expr:
            text = parts[0][1] if parts else ""
            self.keys.append(unescape(text, raw))
            self.used_tr = True
            call = f"tr({quote}{text}{quote})"
        else:
            tmpl, args, n = [], [], 0
            for k, v in parts:
                if k == "lit": tmpl.append(v)
                else:
                    tmpl.append("{%d}" % n); n += 1
                    args.append(self.code(v) if k == "expr" else v)
            text = "".join(tmpl)
            self.keys.append(unescape(text, raw))
            self.used_trf = True
            call = f"trf({quote}{text}{quote}, {', '.join(args)})"

        m = CONST_VAL.search(head)
        if m:
            # const val X = "…"  →  val X: String get() = tr("…")（调用方无法再当编译期常量用，编译会提示）
            start = len(head) - len(m.group(0))
            self.const_rewrite = (start, m.group(1))
        return call


def process(path: Path):
    src = path.read_text(encoding="utf-8")
    t = Transformer(path)
    t.const_rewrite = None
    # const val 需要整句改写，所以先在源码层面处理
    def const_sub(m):
        name, lit = m.group(1), m.group(2)
        if not CJK.search(lit): return m.group(0)
        return f'val {name}: String get() = {lit}'
    src2 = re.sub(r'const\s+val\s+(\w+)(?:\s*:\s*String)?\s*=\s*("(?:[^"\\\n]|\\.)*")', const_sub, src)
    new = t.code(src2)
    imports = []
    if t.used_tr and "import com.mobilegh.i18n.tr\n" not in new: imports.append("import com.mobilegh.i18n.tr")
    if t.used_trf and "import com.mobilegh.i18n.trf\n" not in new: imports.append("import com.mobilegh.i18n.trf")
    if imports:
        m = list(re.finditer(r"^import .*$", new, re.M))
        if m: pos = m[-1].end()
        else:
            pk = re.search(r"^package .*$", new, re.M)
            pos = pk.end() if pk else 0
            imports.insert(0, "")
        new = new[:pos] + "\n" + "\n".join(imports) + new[pos:]
    return new, t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--keys", default="/tmp/i18n_keys.txt")
    ap.add_argument("--dict", default=str(ROOT / "app/src/main/assets/i18n/en.tsv"))
    a = ap.parse_args()
    all_keys, skipped, changed = [], [], 0
    for path in sorted(SRC.rglob("*.kt")):
        if "/i18n/" in str(path): continue
        text = path.read_text(encoding="utf-8")
        if not CJK.search(text): continue
        new, t = process(path)
        all_keys += t.keys
        skipped += t.skipped
        if new != text:
            changed += 1
            if a.apply: path.write_text(new, encoding="utf-8")
    seen, uniq = set(), []
    for k in all_keys:
        if k not in seen: seen.add(k); uniq.append(k)
    if a.apply:
        Path(a.keys).write_text("\n".join(k.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t") for k in uniq) + "\n", encoding="utf-8")
    print(f"files changed: {changed}, keys: {len(all_keys)}, unique: {len(uniq)}, skipped: {len(skipped)}")
    for s in skipped: print("SKIP", s)
    if a.check:
        have = set()
        for line in Path(a.dict).read_text(encoding="utf-8").split("\n"):
            if not line or line.startswith("##") or "\t" not in line: continue
            k = line.split("\t", 1)[0]
            have.add(k.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\"))
        missing = [k for k in uniq if k not in have]
        for k in missing: print("MISSING", k.replace("\n", "\\n"))
        print(f"missing translations: {len(missing)}")
        sys.exit(1 if missing else 0)


if __name__ == "__main__":
    main()
