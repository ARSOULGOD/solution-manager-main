#!/usr/bin/env python3
"""File Mesh source adapter (SM-D20): FM_CONFIGURATION_TEMPLATE -> a Solution Package.

This is the executable test of UI-D22/UI-D24: can the contract express File Mesh's real
surface with no SM code per solution? It emits a package AND a coverage report naming
every row it could not express, so the answer is measured rather than asserted.

    python3 filemesh-adapter.py > ../fixtures/filemesh-ftp-solace.package.json
    python3 filemesh-adapter.py --report
"""
import collections, importlib.util, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
EV = os.path.join(HERE, "..", "..", "..", "docs", "design", "dynamic-ui", "evidence",
                  "extract-fm-vocabulary.py")
spec = importlib.util.spec_from_file_location("fm", EV)
fm = importlib.util.module_from_spec(spec); spec.loader.exec_module(fm)

TARGETS = ("COMP_SOURCE_FTP", "COMP_SINK_SOLACE")

# UI_TYPE / meta.type -> the contract's closed `type` vocabulary (UI-D14)
def contract_type(ui_type, m):
    mt = m.get("type")
    if mt == "yaml":     return "yaml"
    if mt == "password": return "string"          # secrecy is a flag, not a type
    if mt == "number":   return "int"
    if mt == "checkbox": return "bool"
    return {"checkbox": "bool", "flag": "bool", "table": "table", "int": "int",
            "autocomplete": "enum", "radio": "enum",
            "input": "string", "text": "string", "textarea": "string"}.get(ui_type, "string")

# FM's api endpoints -> SM-owned catalog entities (UI-D22: the package names an entity, never a URL)
ENTITY = {"connectors/schema/all": "broker",
          "connectors/schema-list/env/all": "environment",
          "connectors/connection-object/insert-update": "connection"}

# 16 distinct buttons across all 1,989 rows, classified
ACTION_MAP = {"TEST_CONNECTION":    ("test-connection", None),
              "ADD_OR_EDIT_BROKER": ("manage-catalog-entry", "broker"),
              "ADD_BROKER":         ("manage-catalog-entry", "broker"),
              "ADD_SERVER":         ("manage-catalog-entry", "connection"),
              "ADD_OR_EDIT_SERVER": ("manage-catalog-entry", "connection"),
              "ADD_PATH":           ("manage-catalog-entry", "connection")}
CHROME = {"BACK", "REFRESH", "SAVE", "SAVE_DATA_PLANE", "SAVE_MANAGEMENT_PLANE",
          "SAVE_PROJECT_CONFIG", "SAVE_RESOURCE_CONFIG", "SAVE_SOURCE_FILE_CONFIGURATION"}
OUT_OF_SCOPE = {"DEPLOY_CONNECTOR", "DOWNLOAD_BINARY"}   # I1 - SM does not deploy


def jload(s):
    if not s or s == "NULL":
        return {}
    try:
        o = json.loads(s)
        return o if isinstance(o, dict) else {}
    except Exception:
        return {}


def norm_default(v, ctype):
    """FM's DEFAULT_VALUE / meta.defaultValue is untyped: 0, "10", "0"-for-boolean."""
    if v in (None, "NULL", ""):
        return None
    if ctype == "bool":
        return str(v).lower() in ("1", "true", "yes")
    if ctype == "int":
        try:    return int(str(v))
        except Exception: return None
    return str(v)


def build():
    txt = open(fm.DEFAULT_SQL, encoding="utf-8", errors="replace").read()
    rows = [r for r in fm.rows_for(txt, "FM_CONFIGURATION_TEMPLATE") if "_BAD" not in r]
    rows = [r for r in rows if r["COMPONENT_TYPE"] in TARGETS]

    groups = collections.defaultdict(list)
    for r in rows:
        groups[(r["COMPONENT_TYPE"], r["FILE_PROCESS_CONFIGURATION"])].append(r)

    components, dropped, notes = [], [], []

    for (ctype_name, variant), grp in sorted(groups.items()):
        fields, actions, label_to_key = [], [], {}
        # a field name is only a LABEL in FM; 57 collide. The real key is CONFIGURATION_PATH.
        seen_labels = collections.Counter(r["CONFIGURATION_FIELD"] for r in grp)

        for r in grp:
            m = jload(r["UI_TYPE_META"])
            ds = jload(r["UI_DATA_SOURCE"])
            name, path, ui = r["CONFIGURATION_FIELD"], r["CONFIGURATION_PATH"], r["UI_TYPE"]

            if ui == "button":
                if name in CHROME:
                    dropped.append((ctype_name, variant, name, "renderer chrome - not a declaration"))
                    continue
                if name in OUT_OF_SCOPE:
                    dropped.append((ctype_name, variant, name, "out of SM scope per I1 - SM does not deploy"))
                    continue
                kind, entity = ACTION_MAP.get(name, (None, None))
                if not kind:
                    dropped.append((ctype_name, variant, name, "unclassified button"))
                    continue
                a = {"key": name, "kind": kind}
                if entity:
                    a["entity"] = entity
                if kind == "test-connection":
                    a["inputs"] = []          # filled below, once keys are known
                if m.get("showWhen"):
                    a["_rawWhen"] = m["showWhen"]
                a["ui"] = {"label": m.get("label", name.replace("_", " ").title()),
                           "section": r["SECTION"] if r["SECTION"] != "NULL" else None}
                a["ui"] = {k: v for k, v in a["ui"].items() if v is not None}
                actions.append(a)
                continue

            is_virtual = not path or path == "NULL"
            key = (path.replace("/", ".") if not is_virtual
                   else (f"{r['SECTION']}.{name}" if seen_labels[name] > 1 else name))
            ct = contract_type(ui, m)

            f = {"key": key, "type": ct}
            if is_virtual:
                f["virtual"] = True

            if ct == "enum":
                if "options" in ds and isinstance(ds["options"], list):
                    f["options"] = ds["options"]
                elif "api" in ds:
                    ent = next((e for k, e in ENTITY.items() if k in ds["api"]), None)
                    if ent:
                        f["optionsSource"] = {"kind": "sm-catalog", "entity": ent}
                    else:
                        f["type"] = ct = "string"
                        notes.append((ctype_name, name, f"api endpoint not mapped to an entity: {ds['api']}"))
                elif m.get("freeText"):
                    f["type"] = ct = "string"          # combobox with suggestions == free text
                else:
                    f["type"] = ct = "string"
                    notes.append((ctype_name, name, "autocomplete with no declarable option source -> string"))

            d = norm_default(m.get("defaultValue", r["DEFAULT_VALUE"]), ct)
            if d is not None:
                f["default"] = d
            if m.get("required"):
                f["required"] = True
            if m.get("password") or m.get("type") == "password":
                f["secret"] = True
            if m.get("showWhen"):
                f["_rawWhen"] = m["showWhen"]
            if ct == "yaml":
                f["yamlSubtree"] = key

            uid = {"label": name,
                   "section": r["SECTION"] if r["SECTION"] not in (None, "NULL") else None,
                   "subSection": r["SUB_SECTION"] if r["SUB_SECTION"] not in (None, "NULL") else None,
                   "advanced": bool(m.get("advanced")),
                   "helperText": m.get("helperText"),
                   "readOnly": True if m.get("disabled") else None}
            try:
                uid["order"] = int(float(r["SECTION_FIELD_ORDER"]))
            except Exception:
                pass
            f["ui"] = {k: v for k, v in uid.items() if v is not None}
            f["provenance"] = {"key": "derived", "type": "derived",
                               "helperText": "derived" if m.get("helperText") else "authored"}
            fields.append(f)
            label_to_key.setdefault(name, key)

        # resolve predicates: FM's showWhen keys are LABELS; the contract's are keys
        def resolve(raw):
            out = {}
            for k, v in raw.items():
                if k == "label":
                    continue                      # FM conflates presentation into `dependency`
                out[label_to_key.get(k, k)] = v
            return out

        for holder in (fields, actions):
            for x in holder:
                if "_rawWhen" in x:
                    x["when"] = resolve(x.pop("_rawWhen"))

        # a discriminator is any field another field's predicate keys on
        keyed = {k for x in fields + actions for k in (x.get("when") or {})}
        for f in fields:
            if f["key"] in keyed:
                f["discriminator"] = True
                if f["type"] != "enum":
                    f["type"] = "enum"
                    if "options" not in f and "optionsSource" not in f:
                        vals = sorted({v for x in fields + actions
                                       for k, v in (x.get("when") or {}).items() if k == f["key"]})
                        f["options"] = vals
                        notes.append((ctype_name, f["key"],
                                      "discriminator options inferred from observed predicate values"))
                # Gate 12: a keyed-on discriminator with no default and no way for the operator
                # to set it leaves every dependent field hidden forever. A read-only field with
                # exactly one option IS that value, so emit it as the default rather than
                # shipping an unreachable branch.
                if "default" not in f and (f.get("ui") or {}).get("readOnly"):
                    opts = f.get("options") or []
                    if len(opts) == 1:
                        f["default"] = opts[0]
                        notes.append((ctype_name, f["key"],
                                      f"readOnly single-option discriminator: default set to {opts[0]!r} (gate 12)"))
                    else:
                        f["ui"].pop("readOnly", None)
                        notes.append((ctype_name, f["key"],
                                      "keyed-on discriminator was readOnly with no default; readOnly dropped so the operator can set it (gate 12)"))

        # test-connection inputs: the credential-ish fields in the same section
        for a in actions:
            if a["kind"] == "test-connection":
                sec = a.get("ui", {}).get("section")
                a["inputs"] = [f["key"] for f in fields
                               if f.get("ui", {}).get("section") == sec
                               and (f.get("secret") or re.search(r"host|url|user|vpn|port",
                                                                 f["key"], re.I))][:8]
                if not a["inputs"]:
                    a["inputs"] = [f["key"] for f in fields[:4]]

        comp = {"type": f"{ctype_name}@{variant}",
                "cardinality": "one-of-many",
                "role": "source" if "SOURCE" in ctype_name else "sink",
                "configPath": "",
                "configFile": "/projConfig/application.yml",
                "fields": fields}
        if actions:
            comp["actions"] = actions
        components.append(comp)

    pkg = {"solution": "file-mesh",
           "packageVersion": [1],
           "appliesTo": ">=1.0 <2.0",
           "provenance": {"author": "dev-mode"},
           "authoring": {"reviewedRows": 0,
                         "totalRows": len(rows),
                         "source": "FM_CONFIGURATION_TEMPLATE via contracts/adapters/filemesh-adapter.py"},
           "components": components}
    return pkg, rows, dropped, notes


if __name__ == "__main__":
    pkg, rows, dropped, notes = build()
    if "--report" in sys.argv:
        nf = sum(len(c["fields"]) for c in pkg["components"])
        nv = sum(1 for c in pkg["components"] for f in c["fields"] if f.get("virtual"))
        na = sum(len(c.get("actions", [])) for c in pkg["components"])
        print(f"source rows in scope        : {len(rows)}")
        print(f"emitted fields              : {nf}  (of which virtual: {nv})")
        print(f"emitted actions             : {na}")
        print(f"dropped rows                : {len(dropped)}")
        for reason, n in collections.Counter(d[3] for d in dropped).most_common():
            print(f"    {n:>3}  {reason}")
        print(f"coverage                    : {(nf + na)}/{len(rows)} = {100*(nf+na)/len(rows):.1f}%")
        if notes:
            print(f"\nadapter notes ({len(notes)}):")
            for c, n, msg in sorted(set(notes))[:20]:
                print(f"    {c:<20} {n:<28} {msg}")
    else:
        print(json.dumps(pkg, indent=2))
