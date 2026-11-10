"""Numbering, helper-text and secret-marking rules that keep a merged component in its package's style."""
import common


def _ui(f):
    return f.get("ui") or {}


def _sections(fields):
    groups = {}
    for f in fields:
        groups.setdefault(_ui(f).get("section"), []).append(_ui(f).get("order"))
    return groups


def _numeric(order):
    return isinstance(order, (int, float)) and not isinstance(order, bool)


def detect_order_style(fields):
    """First style that fits: within-section, running, section-index. None for no fields, else mixed. A field
    without a numeric `ui.order` (it is optional) means the component is not consistently numbered: mixed."""
    if not fields:
        return None
    if any(not _numeric(_ui(f).get("order")) for f in fields):
        return "mixed"
    groups = _sections(fields)
    if all(o[0] == 0 and all(a <= b for a, b in zip(o, o[1:])) for o in groups.values()):
        return "within-section"
    orders = [_ui(f).get("order") for f in fields]
    if all(a < b for a, b in zip(orders, orders[1:])):
        return "running"
    # equal inside a section, strictly increasing between sections (sections contiguous in file order)
    seq = []
    for f in fields:
        sec = _ui(f).get("section")
        if not seq or seq[-1][0] != sec:
            seq.append((sec, _ui(f).get("order")))
    if (len({s for s, _ in seq}) == len(seq)
            and all(len(set(o)) == 1 for o in groups.values())
            and all(a[1] < b[1] for a, b in zip(seq, seq[1:]))):
        return "section-index"
    return "mixed"


def stock_helper(ftype, label, section):
    return common.helper_text(ftype, label, section)


def _stock_for(field):
    u = _ui(field)
    return stock_helper(field.get("type"), u.get("label"), u.get("section"))


def is_stock_helper(field):
    return _ui(field).get("helperText") == _stock_for(field)


def detect_helper_style(fields):
    if fields and all((_ui(f).get("helperText") or "").startswith(_stock_for(f)) for f in fields):
        return "stock"
    return "sparse"


def new_helper(ftype, label, section, comment, helper_style, secret_value=None):
    if secret_value is not None and comment and str(secret_value) in comment:
        comment = None
    if helper_style == "stock":
        return common.helper_text(ftype, label, section, comment)
    c = (comment or "").strip()
    if len(c.split()) < 4:
        return None
    c = c[0].upper() + c[1:]
    return c if c.endswith((".", "!", "?")) else c + "."


def order_for_new(fields, section, order_style):
    orders = [_ui(f).get("order") for f in fields if _numeric(_ui(f).get("order"))]
    in_section = [_ui(f).get("order") for f in fields
                  if _ui(f).get("section") == section and _numeric(_ui(f).get("order"))]
    if order_style == "running":
        return max(orders) + 1 if orders else 0
    if order_style == "section-index":
        if in_section:
            return in_section[-1]
        return max(orders) + 1 if orders else 0
    return max(in_section) + 1 if in_section else 0


def restyle(component):
    counts = {"order_changed": 0, "stock_removed": 0, "custom_kept": 0}
    next_order = {}
    for f in component.get("fields", []):
        ui = f.setdefault("ui", {})
        n = next_order.get(ui.get("section"), 0)
        next_order[ui.get("section")] = n + 1
        if ui.get("order") != n:
            ui["order"] = n
            counts["order_changed"] += 1
        if "helperText" in ui:
            if is_stock_helper(f):
                del ui["helperText"]
                counts["stock_removed"] += 1
            else:
                counts["custom_kept"] += 1
    return counts


def mark_secrets(component):
    marked = []
    for f in component.get("fields", []):
        if f.get("type") == "secret" and not f.get("secret"):
            f["secret"] = True
            marked.append(f.get("key"))
    return marked
