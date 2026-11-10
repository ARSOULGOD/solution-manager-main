"""Shared helpers for the yamlToPackage engine: a strict YAML loader, flattening, profile
matching and label/section humanising. Nothing in this module knows about any one solution;
every solution-specific decision arrives through the mapping profile."""
import fnmatch
import re

import yaml

# --------------------------------------------------------------------------------------------
# YAML loading
#
# PyYAML resolves YAML 1.1 implicit types: `no`/`yes`/`on`/`off` become booleans, `12:30` becomes
# the sexagesimal int 750, `0755` becomes octal 493 and `2026-01-01` becomes a date. Each silently
# changes a config value (`strictHostKeyChecking: no` would arrive as False). The loader below
# keeps YAML 1.2 core semantics: only true/false are booleans, only plain decimal digits are ints.
# --------------------------------------------------------------------------------------------

BOOL_TAG = "tag:yaml.org,2002:bool"
INT_TAG = "tag:yaml.org,2002:int"
FLOAT_TAG = "tag:yaml.org,2002:float"
TIMESTAMP_TAG = "tag:yaml.org,2002:timestamp"


class StrictLoader(yaml.SafeLoader):
    pass


StrictLoader.yaml_implicit_resolvers = {
    first: [(tag, rx) for tag, rx in resolvers if tag not in (BOOL_TAG, INT_TAG, FLOAT_TAG, TIMESTAMP_TAG)]
    for first, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
StrictLoader.add_implicit_resolver(BOOL_TAG, re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$"), list("tTfF"))
StrictLoader.add_implicit_resolver(INT_TAG, re.compile(r"^[-+]?[0-9]+$"), list("-+0123456789"))
StrictLoader.add_implicit_resolver(
    FLOAT_TAG, re.compile(r"^[-+]?(?:[0-9]+\.[0-9]*|\.[0-9]+)(?:[eE][-+]?[0-9]+)?$"), list("-+0123456789."))

YAML11_BOOL_WORDS = {"yes", "no", "on", "off", "y", "n"}


def _scalar(node):
    return StrictLoader("").construct_object(node, deep=True)


def _comment_above(lines, line_idx):
    """Contiguous full-line comments directly above a key line (0-based index)."""
    out = []
    i = line_idx - 1
    while i >= 0 and lines[i].strip().startswith("#"):
        out.insert(0, lines[i].strip().lstrip("#").strip())
        i -= 1
    return " ".join(c for c in out if c)


def _comment_inline(lines, mark):
    """Text after the value on the value's own line, if it is a comment."""
    if mark is None or mark.line >= len(lines):
        return ""
    rest = lines[mark.line][mark.column:]
    m = re.match(r"^\s+#\s?(.*)$", rest)
    return m.group(1).strip() if m else ""


def _looks_like_commented_config(text):
    return bool(re.match(r"^[\w.\-]+\s*:\s*\S*$", text))


def load_yaml_leaves(path, list_policy=lambda key: "flag", map_leaf=lambda key: False):
    """Flatten a YAML file to an ordered list of leaf dicts.

    list_policy(key) -> 'flag' | 'index' | 'join-comma' | 'list' decides what a sequence becomes.
    map_leaf(key) -> True keeps a mapping as one `map`-typed leaf instead of recursing.
    Path segments are kept as a tuple so a key that itself contains dots
    (`logging.level.com.solace.connector.jms`) still has the right parent.
    """
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    lines = text.splitlines()
    docs = list(yaml.compose_all(text, Loader=StrictLoader))
    leaves, duplicates = [], []

    def emit(path_t, node, kind, value, key_node, doc_idx, extra=None):
        key = ".".join(path_t)
        above = _comment_above(lines, key_node.start_mark.line) if key_node is not None else ""
        inline = _comment_inline(lines, node.end_mark if kind == "scalar" else (key_node.end_mark if key_node else None))
        comment = " ".join(c for c in (above, inline) if c and not _looks_like_commented_config(c))
        leaf = {
            "path": path_t, "key": key, "kind": kind, "value": value,
            "line": (key_node or node).start_mark.line + 1, "doc": doc_idx, "comment": comment,
            "quoted": kind == "scalar" and node.style in ("'", '"'),
            "raw": node.value if kind == "scalar" else None,
        }
        if extra:
            leaf.update(extra)
        leaves.append(leaf)

    def walk(node, path_t, key_node, doc_idx):
        key = ".".join(path_t)
        if isinstance(node, yaml.MappingNode):
            if path_t and (map_leaf(key) or not node.value):
                emit(path_t, node, "map", _scalar(node), key_node, doc_idx)
                return
            seen = {}
            for k_node, v_node in node.value:
                k = str(_scalar(k_node))
                if k in seen:
                    duplicates.append({"key": ".".join(path_t + (k,)), "line": k_node.start_mark.line + 1,
                                       "first_line": seen[k]})
                    continue
                seen[k] = k_node.start_mark.line + 1
                walk(v_node, path_t + (k,), k_node, doc_idx)
        elif isinstance(node, yaml.SequenceNode):
            policy = list_policy(key)
            if policy == "index":
                for i, item in enumerate(node.value):
                    walk(item, path_t + (str(i),), key_node, doc_idx)
                return
            value = _scalar(node)
            if policy == "join-comma" and all(not isinstance(v, (dict, list)) for v in value):
                emit(path_t, node, "scalar-joined", ",".join(str(v) for v in value), key_node, doc_idx,
                     {"joined_from": value})
            else:
                emit(path_t, node, "list", value, key_node, doc_idx, {"list_policy": policy})
        else:
            emit(path_t, node, "scalar", _scalar(node), key_node, doc_idx)

    for i, doc in enumerate(docs):
        if doc is not None:
            walk(doc, (), None, i)
    return {"leaves": leaves, "duplicates": duplicates, "documents": len(docs), "text": text}


# --------------------------------------------------------------------------------------------
# Profile matching
# --------------------------------------------------------------------------------------------

def is_glob(pattern):
    return any(ch in pattern for ch in "*?[")


def matches(pattern, key):
    """A plain pattern is a dotted prefix on a segment boundary; a glob matches the whole key."""
    if is_glob(pattern):
        return fnmatch.fnmatchcase(key, pattern)
    return key == pattern or key.startswith(pattern + ".")


def root_len(pattern, path_t):
    """How many path segments the component root covers (0 for a glob)."""
    if is_glob(pattern):
        return 0
    for i in range(1, len(path_t) + 1):
        if len(".".join(path_t[:i])) >= len(pattern):
            return i
    return len(path_t)


def best_match(patterns, key):
    """Longest matching plain prefix wins; a glob counts as length 0 but still matches."""
    best = None
    for p in patterns:
        if matches(p, key):
            score = -1 if is_glob(p) else len(p)
            if best is None or score > best[0]:
                best = (score, p)
    return best[1] if best else None


def lookup(table, key):
    """Value for `key` in a {pattern: value} table; exact key first, then longest pattern."""
    if not table:
        return None
    if key in table:
        return table[key]
    hit = best_match(list(table), key)
    return table[hit] if hit else None


# --------------------------------------------------------------------------------------------
# Humanising
# --------------------------------------------------------------------------------------------

ACRONYMS = {
    "id", "ip", "url", "uri", "ssl", "tls", "sftp", "ftp", "gcs", "http", "https", "api", "jdbc", "db",
    "eod", "lvq", "vpn", "cors", "ttl", "json", "xml", "sql", "jvm", "jms", "tcp", "udp", "dns", "aws",
    "s3", "cpu", "uuid", "smtp", "sms", "jwt", "mqtt", "amqp", "rest", "ui", "tz", "sso", "ldap", "oauth",
    "kms", "iam", "csv", "pem", "jks", "pkcs12", "mb", "gb", "kb",
}
NOISE_SUFFIXES = {"settings", "setting", "config", "configuration", "properties", "props"}
UNIT_SUFFIXES = {
    "sec": "seconds", "secs": "seconds", "seconds": "seconds", "ms": "milliseconds", "millis": "milliseconds",
    "min": "minutes", "mins": "minutes", "minutes": "minutes", "hours": "hours", "hrs": "hours", "bytes": "bytes",
}
GENERIC_LEAVES = {"enabled", "enable", "host", "port", "user", "username", "password", "ip", "url", "name",
                  "type", "id", "path", "address", "timeout", "mode", "secured"}


def split_words(segment):
    s = re.sub(r"[-_.\s]+", " ", str(segment))
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", s)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1 \2", s)
    return [w for w in s.split() if w]


def _word(w, acronyms, first):
    if w.lower() in acronyms:
        return w.upper()
    return w.capitalize() if first else w.lower()


def title_case(words, acronyms):
    return " ".join(w.upper() if w.lower() in acronyms else w.capitalize() for w in words)


def section_for(mid_segments, root_segments, fallback, acronyms):
    """Nearest meaningful parent grouping: the last <=2 segments between the component root and
    the leaf, minus noise suffixes like `_settings`; else the root's own last segment."""
    words = []
    for seg in list(mid_segments)[-2:]:
        ws = split_words(seg)
        while ws and ws[-1].lower() in NOISE_SUFFIXES and len(ws) > 1:
            ws = ws[:-1]
        words += ws
    if not words and root_segments:
        words = [w for w in split_words(root_segments[-1]) if w.lower() not in NOISE_SUFFIXES]
    return title_case(words, acronyms) if words else fallback


def label_for(leaf_segment, section, acronyms):
    ws = split_words(leaf_segment)
    unit = None
    if len(ws) > 1 and ws[-1].lower() in UNIT_SUFFIXES:
        unit = UNIT_SUFFIXES[ws[-1].lower()]
        ws = ws[:-1]
    if section and ws and all(w.lower() in GENERIC_LEAVES for w in ws) and section != "General":
        sec = [_word(w, acronyms, i == 0) for i, w in enumerate(section.split())]
        text = " ".join(sec + [_word(w, acronyms, False) for w in ws])
    else:
        text = " ".join(_word(w, acronyms, i == 0) for i, w in enumerate(ws)) or str(leaf_segment)
    return f"{text} ({unit})" if unit else text


HELPER_PATTERNS = {
    "string": "Enter the value for “{label}” in the “{section}” section.",
    "int": "Enter the numeric value for “{label}” in the “{section}” section.",
    "bool": "Turn “{label}” on or off in the “{section}” section.",
    "enum": "Select the “{label}” option in the “{section}” section.",
    "secret": "Enter “{label}” for the “{section}” section. This value is treated as sensitive.",
    "duration": "Enter the duration for “{label}” in the “{section}” section.",
    "list": "Enter the values for “{label}” in the “{section}” section.",
    "map": "Enter the entries for “{label}” in the “{section}” section.",
}


def helper_text(ftype, label, section, comment=None):
    """The reference packages' wording pattern for the type. A descriptive YAML comment (four or
    more words) is appended as a second sentence; a fragment like `# or your password` is not."""
    text = HELPER_PATTERNS.get(ftype, HELPER_PATTERNS["string"]).format(label=label, section=section)
    c = (comment or "").strip()
    if len(c.split()) >= 4:
        c = c[0].upper() + c[1:]
        text += " " + (c if c.endswith((".", "!", "?")) else c + ".")
    return text


# --------------------------------------------------------------------------------------------
# Type inference
# --------------------------------------------------------------------------------------------

SECRET_RX = re.compile(r"(password|passwd|pwd|secret|token|api[-_]?key|apikey|private[-_]?key|passphrase)$",
                       re.IGNORECASE)
DURATION_RX = re.compile(r"^(?:\d+(?:ns|us|ms|s|m|h|d)|P(?:\d+D)?(?:T(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?)$")


def is_secret_name(leaf_segment, extra_patterns=()):
    name = str(leaf_segment)
    if SECRET_RX.search(name.replace("-", "").replace("_", "")) or SECRET_RX.search(name):
        return True
    return any(re.search(p, name, re.IGNORECASE) for p in extra_patterns)


def infer_type(leaf, extra_secret_patterns=()):
    """Returns (type or None, coercion note or None, problem or None)."""
    v, last = leaf["value"], leaf["path"][-1]
    if leaf["kind"] == "list":
        return ("list", None, None) if leaf.get("list_policy") == "list" else (None, None, "YAML list")
    if leaf["kind"] == "map":
        return ("map", None, None) if v else (None, None, "empty mapping")
    if leaf["kind"] == "scalar-joined":
        return "string", f"list {leaf['joined_from']!r} joined with ',' into one string", None
    if isinstance(v, bool):
        return "bool", None, None
    if isinstance(v, int):
        return "int", None, None
    if isinstance(v, float):
        return None, None, f"floating-point value {v!r} has no field type"
    if (v is None or isinstance(v, str)) and is_secret_name(last, extra_secret_patterns):
        return "secret", None, None
    if v is None:
        return "string", "null value: type assumed string", None
    if isinstance(v, str):
        if v.lower() in ("true", "false"):
            return "bool", f"string {leaf['raw']!r} coerced to bool", None
        if DURATION_RX.match(v) and not v.isdigit():
            return "duration", None, None
        return "string", None, None
    return None, None, f"unsupported value type {type(v).__name__}"


def coerce_default(ftype, v):
    if ftype == "bool" and isinstance(v, str):
        return v.lower() == "true"
    return v


def normalise_key(key):
    return re.sub(r"[-_]", "", key).lower()
