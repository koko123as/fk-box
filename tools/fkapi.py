#!/usr/bin/env python3
"""Generate the Kotlin facade of fk-core's Go API (docs/kernel-isolation.md section 8).

fk-core registers the functions and objects the app calls in core/fk/api.go
(`r.Func("name", Function)`, `r.Object("Type", (*Type)(nil))`, see core/rpc).
This script reads those registrations and the Go signatures and structs in
core/fk/*.go and writes FkLib.kt: one Kotlin function per registered name with
the gomobile-style types the app used with libbox before (int32 -> Int,
int64 and int -> Long, []byte -> ByteArray, struct -> a data class whose
properties are named the way Kotlin named gomobile's getters), and one class
per registered object type whose methods call "Type.Method" on its handle.

    python3 tools/fkapi.py gen     write FkLib.kt
    python3 tools/fkapi.py check   fail when FkLib.kt is stale
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
GO_DIR = ROOT / "core" / "fk"
API = GO_DIR / "api.go"
KT_OUT = ROOT / "clients/android/app/src/main/java/io/nekohasekai/sfa/fkcore/FkLib.kt"
PACKAGE = "io.nekohasekai.sfa.fkcore"

PRIMITIVES = {
    "string": ("String", 'FkCodec.string({v})', '""'),
    "bool": ("Boolean", 'FkCodec.bool({v})', "false"),
    "int32": ("Int", 'FkCodec.int({v})', "0"),
    "int64": ("Long", 'FkCodec.long({v})', "0L"),
    "int": ("Long", 'FkCodec.long({v})', "0L"),
    "float64": ("Double", 'FkCodec.double({v})', "0.0"),
    "[]byte": ("ByteArray", 'FkCodec.bytes({v})', "ByteArray(0)"),
}


def go_sources():
    return {path: path.read_text() for path in sorted(GO_DIR.glob("*.go")) if not path.name.endswith("_test.go")}


def split_params(text):
    """'a, b string, c int32' -> [('a','string'),('b','string'),('c','int32')]"""
    text = text.strip()
    if not text:
        return []
    parts = [p.strip() for p in text.split(",")]
    params = []
    pending = []
    for part in parts:
        fields = part.split(None, 1)
        if len(fields) == 1:
            pending.append(fields[0])
            continue
        name, typ = fields
        for p in pending:
            params.append((p, typ.strip()))
        pending = []
        params.append((name, typ.strip()))
    if pending:  # unnamed parameters: types only
        params = [(f"p{i}", t) for i, t in enumerate(pending)]
    return params


def split_results(text):
    text = text.strip()
    if not text:
        return []
    if text.startswith("("):
        text = text[1:-1]
    return [t.strip() for t in text.split(",") if t.strip()]


FUNC_RE = re.compile(r"^func (?:\((\w+) (\*?\w+)\) )?(\w+)\(([^)]*)\)\s*([^{]*)\{", re.M)


def parse_funcs(sources):
    funcs, methods = {}, {}
    for text in sources.values():
        for receiver_name, receiver, name, params, results in FUNC_RE.findall(text):
            entry = (split_params(params), split_results(results))
            if receiver:
                methods.setdefault(receiver.lstrip("*"), {})[name] = entry
            else:
                funcs[name] = entry
    return funcs, methods


STRUCT_RE = re.compile(r"^type (\w+) struct \{\n(.*?)^\}", re.M | re.S)


def parse_structs(sources):
    structs = {}
    for text in sources.values():
        for name, body in STRUCT_RE.findall(text):
            fields = []
            for line in body.splitlines():
                line = line.split("//", 1)[0].strip()
                m = re.match(r"^([A-Z]\w*(?:\s*,\s*[A-Z]\w*)*)\s+(\S+)(?:\s+`([^`]*)`)?$", line)
                if not m:
                    continue
                names, typ, tag = m.group(1), m.group(2), m.group(3) or ""
                json_name = None
                tm = re.search(r'json:"([^"]*)"', tag)
                if tm:
                    json_name = tm.group(1).split(",")[0]
                    if json_name == "-":
                        continue
                for field in [n.strip() for n in names.split(",")]:
                    fields.append((field, typ, json_name or field))
            structs[name] = fields
    return structs


def kotlin_property(name):
    """gomobile getter getXxx -> the Kotlin synthetic property name."""
    if len(name) > 1 and name[1].isupper():
        i = 0
        while i < len(name) and name[i].isupper():
            i += 1
        if i == len(name):
            return name.lower()
        return name[: i - 1].lower() + name[i - 1:]
    return name[0].lower() + name[1:]


def kotlin_ident(name):
    return f"`{name}`" if name in {"object", "fun", "val", "var", "in", "is", "as", "when", "class", "interface", "package", "typealias", "type"} else name


class Generator:
    def __init__(self):
        sources = go_sources()
        self.funcs, self.methods = parse_funcs(sources)
        self.structs = parse_structs(sources)
        api = API.read_text()
        self.registered = re.findall(r'r\.Func\("(\w+)",\s*(\w+)\)', api)
        self.objects = dict((go, name) for name, go in re.findall(r'r\.Object\("(\w+)",\s*\(\*(\w+)\)\(nil\)\)', api))
        self.data_classes = {}
        self.errors = []

    def kt_type(self, go):
        if go in PRIMITIVES:
            return PRIMITIVES[go][0]
        base = go.lstrip("*")
        if base in self.objects:
            return self.objects[base]
        if base in self.structs:
            self.data_classes[base] = True
            return base
        self.errors.append(f"unsupported Go type {go}")
        return "Any?"

    def decoder(self, go, value):
        if go in PRIMITIVES:
            return PRIMITIVES[go][1].format(v=value)
        base = go.lstrip("*")
        if base in self.objects:
            return f"{self.objects[base]}(FkCodec.handle({value}) ?: throw FkCoreException(\"fk-core returned no {base}\"))"
        if base in self.structs:
            self.data_classes[base] = True
            return f"{base}.decode(FkCodec.obj({value}) ?: org.json.JSONObject())"
        return value

    def function(self, kt_name, params, results, call_name, handle=None):
        args = ", ".join(f"{kotlin_ident(n)}: {self.kt_type(t)}" for n, t in params)
        call_args = ", ".join(([handle] if handle else []) + [kotlin_ident(n) for n, _ in params])
        call = f'FkCoreClient.call("{call_name}"' + (f", {call_args}" if call_args else "") + ")"
        values = [r for r in results if r != "error"]
        if not values:
            return f"    fun {kt_name}({args}) {{\n        {call}\n    }}\n"
        go = values[0]
        return f"    fun {kt_name}({args}): {self.kt_type(go)} = {self.decoder(go, f'CallResult({call})')}\n"

    def data_class(self, name):
        fields = self.structs[name]
        props = []
        decodes = []
        for field, typ, key in fields:
            kt = self.kt_type(typ)
            props.append(f"    val {kotlin_ident(kotlin_property(field))}: {kt},")
            accessor = 'o.opt("' + key + '")'
            decodes.append("            " + self.decoder(typ, accessor) + ",")
        body = "\n".join(props) if props else ""
        return (f"/** Go struct {name} (core/fk). */\n"
                f"data class {name}(\n{body}\n) {{\n"
                f"    companion object {{\n"
                f"        fun decode(o: org.json.JSONObject): {name} = {name}(\n"
                + "\n".join(decodes) + "\n        )\n    }\n}\n")

    def render(self):
        out = [f"// Code generated by tools/fkapi.py from core/fk/api.go. DO NOT EDIT.\n",
               f"package {PACKAGE}\n\n",
               "import io.nekohasekai.sfa.bridge.FkCoreClient\nimport io.nekohasekai.sfa.bridge.FkCoreException\n\n",
               "/** fk-core's Go API (core/fk/api.go), called over FkCoreClient. Errors throw FkCoreException. */\n",
               "object FkLib {\n"]
        for name, go in self.registered:
            if go not in self.funcs:
                self.errors.append(f"{name}: no Go function {go} in core/fk")
                continue
            params, results = self.funcs[go]
            out.append(self.function(name, params, results, name))
        out.append("}\n\n")
        for go, kt in sorted(self.objects.items(), key=lambda item: item[1]):
            out.append(f"/** Go object *{go} (core/fk), held by fk-core under a handle; [close] releases it. */\n")
            out.append(f"class {kt} internal constructor(private val handle: Long) : AutoCloseable {{\n")
            for method, (params, results) in sorted(self.methods.get(go, {}).items()):
                if not method[0].isupper() or method == "Close":
                    continue
                out.append(self.function(method[0].lower() + method[1:], params, results, f"{kt}.{method}", handle="handle"))
            out.append('    override fun close() {\n        runCatching { FkCoreClient.call("\\$release", handle) }\n    }\n}\n\n')
        done = set()
        while True:
            pending = [n for n in sorted(self.data_classes) if n not in done]
            if not pending:
                break
            for name in pending:
                done.add(name)
                out.append(self.data_class(name) + "\n")
        return "".join(out).rstrip() + "\n"


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"
    generator = Generator()
    text = generator.render()
    if generator.errors:
        for error in sorted(set(generator.errors)):
            print(f"::error::{error}")
        sys.exit(1)
    if mode == "gen":
        KT_OUT.parent.mkdir(parents=True, exist_ok=True)
        KT_OUT.write_text(text)
        print(f"wrote {KT_OUT.relative_to(ROOT)}: {len(generator.registered)} functions, {len(generator.objects)} objects")
    elif mode == "check":
        if not KT_OUT.exists() or KT_OUT.read_text() != text:
            sys.exit(f"{KT_OUT.relative_to(ROOT)} is stale: run python3 tools/fkapi.py gen")
        # fk-core owns these now: the app must not reach the in-process copy.
        names = [name for name, _ in generator.registered]
        pattern = re.compile(r"\bLibbox(?:\.|::)(" + "|".join(map(re.escape, names)) + r")\b")
        stale = []
        for path in (ROOT / "clients/android/app/src").rglob("*.kt"):
            for number, line in enumerate(path.read_text().splitlines(), 1):
                if pattern.search(line) and not line.lstrip().startswith(("/", "*")):
                    stale.append(f"{path.relative_to(ROOT)}:{number}: {line.strip()}")
        if stale:
            print("\n".join(f"::error::calls fk-core's API through libbox: {s}" for s in stale))
            sys.exit(1)
        print(f"FkLib.kt up to date: {len(generator.registered)} functions, {len(generator.objects)} objects")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
