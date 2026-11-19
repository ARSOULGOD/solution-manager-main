import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import style  # noqa: E402

STOCK = object()


def field(ftype, label, section, helperText=None, order=None):
    ui = {"section": section, "label": label, "order": order}
    if helperText is not None:
        ui["helperText"] = helperText
    return {"key": label.lower(), "type": ftype, "ui": ui}


def fields(*rows):
    out = []
    for i, row in enumerate(rows):
        section, order = row[0], row[1]
        label = f"L{i}"
        helper = None
        if len(row) > 2:
            helper = style.stock_helper("string", label, section) if row[2] is STOCK else row[2]
        out.append(field("string", label, section, helper, order))
    return out


class StyleTests(unittest.TestCase):
    def test_detects_the_three_sample_styles(self):
        self.assertEqual(style.detect_order_style(fields(("A", 0), ("A", 0), ("B", 1), ("B", 1))), "section-index")
        self.assertEqual(style.detect_order_style(fields(("A", 5), ("A", 6), ("B", 7), ("B", 8))), "running")
        self.assertEqual(style.detect_order_style(fields(("C", 0), ("C", 1), ("C", 4), ("C", 4), ("P", 0), ("P", 1), ("M", 0))), "within-section")
        self.assertIsNone(style.detect_order_style([]))
        self.assertEqual(style.detect_order_style(fields(("A", 0), ("A", 2), ("A", 1))), "mixed")

    def test_stock_helper_detection_is_exact(self):
        stock = "Enter the numeric value for “SFTP port” in the “SFTP” section."
        self.assertTrue(style.is_stock_helper(field("int", "SFTP port", "SFTP", stock)))
        self.assertFalse(style.is_stock_helper(field("int", "SFTP port", "SFTP", stock + " Extra.")))
        self.assertFalse(style.is_stock_helper(field("int", "Port", "SFTP", stock)))

    def test_helper_style_detection(self):
        self.assertEqual(style.detect_helper_style(fields(("A", 0, STOCK), ("A", 1, STOCK))), "stock")
        self.assertEqual(style.detect_helper_style(fields(("A", 0, STOCK), ("A", 1))), "sparse")

    def test_restyle_counts_and_keeps_custom(self):
        comp = {"fields": fields(("A", 5, STOCK), ("A", 6, "Custom."), ("B", 7, STOCK))}
        self.assertEqual(style.restyle(comp), {"order_changed": 3, "stock_removed": 2, "custom_kept": 1})
        self.assertEqual([f["ui"]["order"] for f in comp["fields"]], [0, 1, 0])
        self.assertEqual(comp["fields"][1]["ui"]["helperText"], "Custom.")
        self.assertNotIn("helperText", comp["fields"][0]["ui"])

    def test_new_helper_sparse_uses_the_comment_only(self):
        self.assertEqual(style.new_helper("string", "Name", "File", "base log file name", "sparse"), "Base log file name.")
        self.assertIsNone(style.new_helper("secret", "Password", "SSL", "or your password", "sparse"))

    def test_new_helper_never_repeats_a_secret_value(self):
        c = "the keystore password is hunter2pass for local runs"
        self.assertIsNone(style.new_helper("secret", "Key store password", "SSL", c, "sparse", "hunter2pass"))
        self.assertEqual(style.new_helper("secret", "Key store password", "SSL", c, "stock", "hunter2pass"),
                         style.stock_helper("secret", "Key store password", "SSL"))

    def test_order_for_new_follows_each_style(self):
        w = fields(("A", 0), ("A", 1), ("B", 0))
        self.assertEqual(style.order_for_new(w, "A", "within-section"), 2)
        self.assertEqual(style.order_for_new(w, "Z", "within-section"), 0)
        self.assertEqual(style.order_for_new(fields(("A", 5), ("B", 6)), "A", "running"), 7)
        self.assertEqual(style.order_for_new(fields(("A", 0), ("B", 1)), "A", "section-index"), 0)
        self.assertEqual(style.order_for_new(fields(("A", 0), ("B", 1)), "Z", "section-index"), 2)

    def test_mark_secrets(self):
        comp = {"fields": [{"key": "a", "type": "secret"}, {"key": "b", "type": "secret", "secret": True}, {"key": "c", "type": "string"}]}
        self.assertEqual(style.mark_secrets(comp), ["a"])
        self.assertTrue(all(f.get("secret") for f in comp["fields"] if f["type"] == "secret"))


    def test_a_missing_order_is_mixed_and_tolerated(self):  # final review 2
        fs = fields(("A", 0), ("A", 1), ("B", 0))
        del fs[1]["ui"]["order"]
        self.assertEqual(style.detect_order_style(fs), "mixed")
        self.assertEqual(style.order_for_new(fs, "A", "mixed"), 1)
        self.assertEqual(style.order_for_new(fs, "A", "running"), 1)
        self.assertEqual(style.order_for_new(fs, "A", "section-index"), 0)
        self.assertEqual(style.restyle({"fields": fs})["order_changed"], 1)
        self.assertEqual([f["ui"]["order"] for f in fs], [0, 1, 0])

if __name__ == "__main__":
    unittest.main()
