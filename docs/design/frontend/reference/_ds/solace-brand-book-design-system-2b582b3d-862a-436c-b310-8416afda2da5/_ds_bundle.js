/* @ds-bundle: {"format":3,"namespace":"SolaceDesignSystem_2b582b","components":[{"name":"Alert","sourcePath":"components/core/Alert.jsx"},{"name":"Avatar","sourcePath":"components/core/Avatar.jsx"},{"name":"Badge","sourcePath":"components/core/Badge.jsx"},{"name":"Button","sourcePath":"components/core/Button.jsx"},{"name":"Card","sourcePath":"components/core/Card.jsx"},{"name":"CodeBlock","sourcePath":"components/core/CodeBlock.jsx"},{"name":"Eyebrow","sourcePath":"components/core/Eyebrow.jsx"},{"name":"IconButton","sourcePath":"components/core/IconButton.jsx"},{"name":"Logo","sourcePath":"components/core/Logo.jsx"},{"name":"LogoMark","sourcePath":"components/core/Logo.jsx"},{"name":"Stat","sourcePath":"components/core/Stat.jsx"},{"name":"Tabs","sourcePath":"components/core/Tabs.jsx"},{"name":"Tag","sourcePath":"components/core/Tag.jsx"},{"name":"Checkbox","sourcePath":"components/forms/Checkbox.jsx"},{"name":"Input","sourcePath":"components/forms/Input.jsx"},{"name":"Select","sourcePath":"components/forms/Select.jsx"},{"name":"Switch","sourcePath":"components/forms/Switch.jsx"},{"name":"Textarea","sourcePath":"components/forms/Textarea.jsx"}],"sourceHashes":{"components/core/Alert.jsx":"b9fa68034f2a","components/core/Avatar.jsx":"a186af17c31c","components/core/Badge.jsx":"36a56f91c3e3","components/core/Button.jsx":"c9e9bf6c54e6","components/core/Card.jsx":"d61bc8710a41","components/core/CodeBlock.jsx":"1c32e302710a","components/core/Eyebrow.jsx":"da4ed6b3ca4d","components/core/IconButton.jsx":"68d1dcad85a9","components/core/Logo.jsx":"b1acd2e409a5","components/core/Stat.jsx":"30a9b1904b57","components/core/Tabs.jsx":"30790d4f7ae3","components/core/Tag.jsx":"8a628283b5d6","components/forms/Checkbox.jsx":"9621cd1a5032","components/forms/Input.jsx":"73c319105a55","components/forms/Select.jsx":"5cfb30a92fdc","components/forms/Switch.jsx":"8c3afbd23123","components/forms/Textarea.jsx":"2c8ea35213a6","ui_kits/platform/app.jsx":"d8635a4caa63","ui_kits/platform/components.jsx":"2bb9865472d1","ui_kits/website/app.jsx":"748733c94233","ui_kits/website/components.jsx":"656bcb4d86f5"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.SolaceDesignSystem_2b582b = window.SolaceDesignSystem_2b582b || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// components/core/Alert.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Alert — inline message banner. Tones use palette; left accent + icon slot. */
function Alert({
  children,
  title,
  tone = "info",
  icon,
  onClose,
  style,
  ...rest
}) {
  const tones = {
    info: {
      bd: "var(--green-deep)",
      bg: "color-mix(in srgb, var(--green-deep) 8%, white)",
      fg: "var(--green-deep)"
    },
    success: {
      bd: "var(--green-classic)",
      bg: "color-mix(in srgb, var(--green-classic) 12%, white)",
      fg: "var(--green-deep)"
    },
    warning: {
      bd: "var(--orange)",
      bg: "color-mix(in srgb, var(--orange) 12%, white)",
      fg: "#9A5B00"
    },
    danger: {
      bd: "var(--status-danger)",
      bg: "color-mix(in srgb, var(--status-danger) 10%, white)",
      fg: "#B42A2E"
    }
  };
  const t = tones[tone] || tones.info;
  return /*#__PURE__*/React.createElement("div", _extends({
    role: "status",
    style: {
      display: "flex",
      gap: "12px",
      alignItems: "flex-start",
      padding: "14px 16px",
      borderRadius: "var(--radius-md)",
      background: t.bg,
      borderLeft: `3px solid ${t.bd}`,
      fontFamily: "var(--font-sans)",
      color: "var(--text-body)",
      ...style
    }
  }, rest), icon && /*#__PURE__*/React.createElement("span", {
    style: {
      color: t.fg,
      display: "inline-flex",
      flexShrink: 0,
      marginTop: "1px"
    }
  }, icon), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      fontSize: "14px",
      lineHeight: 1.5
    }
  }, title && /*#__PURE__*/React.createElement("div", {
    style: {
      fontWeight: 600,
      color: "var(--text-strong)",
      marginBottom: children ? "2px" : 0
    }
  }, title), children), onClose && /*#__PURE__*/React.createElement("button", {
    onClick: onClose,
    "aria-label": "Dismiss",
    style: {
      border: "none",
      background: "transparent",
      cursor: "pointer",
      color: "var(--text-muted)",
      fontSize: "16px",
      lineHeight: 1,
      padding: 0
    }
  }, "\xD7"));
}
Object.assign(__ds_scope, { Alert });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Alert.jsx", error: String((e && e.message) || e) }); }

// components/core/Avatar.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Avatar — circular, initials fallback, optional image + status dot. */
function Avatar({
  src,
  name = "",
  size = 40,
  status,
  style,
  ...rest
}) {
  const initials = name.split(" ").map(p => p[0]).filter(Boolean).slice(0, 2).join("").toUpperCase();
  return /*#__PURE__*/React.createElement("span", _extends({
    style: {
      position: "relative",
      display: "inline-flex",
      width: size,
      height: size,
      ...style
    }
  }, rest), /*#__PURE__*/React.createElement("span", {
    style: {
      width: size,
      height: size,
      borderRadius: "var(--radius-circle)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      overflow: "hidden",
      background: "var(--blue-deep)",
      color: "white",
      fontFamily: "var(--font-sans)",
      fontWeight: 600,
      fontSize: size * 0.4
    }
  }, src ? /*#__PURE__*/React.createElement("img", {
    src: src,
    alt: name,
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover",
      filter: "grayscale(1) contrast(1.05)"
    }
  }) : initials), status && /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      right: 0,
      bottom: 0,
      width: size * 0.28,
      height: size * 0.28,
      borderRadius: "50%",
      border: "2px solid var(--surface-card)",
      background: status === "online" ? "var(--green-classic)" : status === "busy" ? "var(--orange)" : "var(--gray-40)"
    }
  }));
}
Object.assign(__ds_scope, { Avatar });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Avatar.jsx", error: String((e && e.message) || e) }); }

// components/core/Badge.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Badge — small status/metadata pill. Tones map to the palette; copy never uses secondary color, so text stays readable. */
function Badge({
  children,
  tone = "neutral",
  solid = false,
  style,
  ...rest
}) {
  const tones = {
    neutral: {
      bg: "var(--surface-sunk)",
      fg: "var(--text-body)",
      bd: "var(--border-subtle)"
    },
    green: {
      bg: "color-mix(in srgb, var(--green-classic) 16%, white)",
      fg: "var(--green-deep)",
      bd: "color-mix(in srgb, var(--green-classic) 32%, transparent)"
    },
    blue: {
      bg: "color-mix(in srgb, var(--blue-deep) 10%, white)",
      fg: "var(--blue-deep)",
      bd: "color-mix(in srgb, var(--blue-deep) 22%, transparent)"
    },
    warning: {
      bg: "color-mix(in srgb, var(--orange) 18%, white)",
      fg: "#9A5B00",
      bd: "color-mix(in srgb, var(--orange) 40%, transparent)"
    },
    danger: {
      bg: "color-mix(in srgb, var(--status-danger) 14%, white)",
      fg: "#B42A2E",
      bd: "color-mix(in srgb, var(--status-danger) 30%, transparent)"
    }
  };
  const t = tones[tone] || tones.neutral;
  const solidStyle = {
    green: {
      bg: "var(--green-classic)",
      fg: "var(--blue-deep)"
    },
    blue: {
      bg: "var(--blue-deep)",
      fg: "white"
    },
    warning: {
      bg: "var(--orange)",
      fg: "var(--blue-deep)"
    },
    danger: {
      bg: "var(--status-danger)",
      fg: "white"
    },
    neutral: {
      bg: "var(--gray-80)",
      fg: "white"
    }
  }[tone] || {};
  return /*#__PURE__*/React.createElement("span", _extends({
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: "6px",
      height: "22px",
      padding: "0 10px",
      fontFamily: "var(--font-sans)",
      fontSize: "12px",
      fontWeight: 600,
      lineHeight: 1,
      borderRadius: "var(--radius-pill)",
      background: solid ? solidStyle.bg : t.bg,
      color: solid ? solidStyle.fg : t.fg,
      border: solid ? "1px solid transparent" : `1px solid ${t.bd}`,
      whiteSpace: "nowrap",
      ...style
    }
  }, rest), children);
}
Object.assign(__ds_scope, { Badge });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Badge.jsx", error: String((e && e.message) || e) }); }

// components/core/Button.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Solace Button — primary action element.
 * Set in Figtree (UI font), pill radius, calm hover/press states.
 */
function Button({
  children,
  variant = "primary",
  size = "md",
  iconLeft,
  iconRight,
  fullWidth = false,
  disabled = false,
  type = "button",
  onClick,
  style,
  ...rest
}) {
  const sizes = {
    sm: {
      padding: "8px 16px",
      fontSize: "14px",
      gap: "6px",
      height: "36px"
    },
    md: {
      padding: "11px 22px",
      fontSize: "15px",
      gap: "8px",
      height: "44px"
    },
    lg: {
      padding: "15px 30px",
      fontSize: "17px",
      gap: "10px",
      height: "54px"
    }
  };
  const s = sizes[size] || sizes.md;
  const variants = {
    primary: {
      background: "var(--accent)",
      color: "var(--on-accent)",
      border: "1.5px solid var(--accent)"
    },
    secondary: {
      background: "transparent",
      color: "var(--text-strong)",
      border: "1.5px solid var(--border-strong)"
    },
    ghost: {
      background: "transparent",
      color: "var(--text-strong)",
      border: "1.5px solid transparent"
    },
    inverse: {
      background: "var(--surface-card)",
      color: "var(--text-strong)",
      border: "1.5px solid var(--surface-card)"
    }
  };
  const v = variants[variant] || variants.primary;
  const base = {
    display: fullWidth ? "flex" : "inline-flex",
    width: fullWidth ? "100%" : "auto",
    alignItems: "center",
    justifyContent: "center",
    gap: s.gap,
    height: s.height,
    padding: s.padding,
    fontSize: s.fontSize,
    fontFamily: "var(--font-sans)",
    fontWeight: 600,
    lineHeight: 1,
    letterSpacing: "0",
    borderRadius: "var(--radius-pill)",
    cursor: disabled ? "not-allowed" : "pointer",
    opacity: disabled ? 0.45 : 1,
    transition: "background var(--dur-base) var(--ease-standard), border-color var(--dur-base) var(--ease-standard), transform var(--dur-fast) var(--ease-standard), box-shadow var(--dur-base) var(--ease-standard)",
    whiteSpace: "nowrap",
    ...v,
    ...style
  };
  const onEnter = e => {
    if (disabled) return;
    if (variant === "primary") e.currentTarget.style.background = "var(--accent-hover)";
    if (variant === "primary") e.currentTarget.style.borderColor = "var(--accent-hover)";
    if (variant === "secondary") e.currentTarget.style.borderColor = "var(--accent)";
    if (variant === "ghost") e.currentTarget.style.background = "var(--surface-sunk)";
  };
  const onLeave = e => {
    if (disabled) return;
    e.currentTarget.style.background = v.background;
    e.currentTarget.style.borderColor = v.border.split(" ").slice(2).join(" ");
    e.currentTarget.style.transform = "none";
  };
  const onDown = e => {
    if (!disabled) e.currentTarget.style.transform = "scale(0.98)";
  };
  const onUp = e => {
    if (!disabled) e.currentTarget.style.transform = "none";
  };
  const onFocus = e => {
    e.currentTarget.style.boxShadow = "var(--ring)";
  };
  const onBlur = e => {
    e.currentTarget.style.boxShadow = "none";
  };
  return /*#__PURE__*/React.createElement("button", _extends({
    type: type,
    disabled: disabled,
    onClick: onClick,
    style: base,
    onMouseEnter: onEnter,
    onMouseLeave: onLeave,
    onMouseDown: onDown,
    onMouseUp: onUp,
    onFocus: onFocus,
    onBlur: onBlur
  }, rest), iconLeft, children, iconRight);
}
Object.assign(__ds_scope, { Button });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Button.jsx", error: String((e && e.message) || e) }); }

// components/core/Card.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Card — white surface, hairline border, soft deep-blue-tinted shadow,
 * medium radius. The core container across Solace surfaces.
 */
function Card({
  children,
  interactive = false,
  padding = "var(--space-6)",
  style,
  ...rest
}) {
  const [hover, setHover] = React.useState(false);
  return /*#__PURE__*/React.createElement("div", _extends({
    onMouseEnter: () => interactive && setHover(true),
    onMouseLeave: () => interactive && setHover(false),
    style: {
      background: "var(--surface-card)",
      border: "1px solid var(--border-subtle)",
      borderRadius: "var(--radius-lg)",
      boxShadow: hover ? "var(--shadow-lg)" : "var(--shadow-sm)",
      padding,
      transition: "box-shadow var(--dur-base) var(--ease-standard), transform var(--dur-base) var(--ease-standard)",
      transform: hover ? "translateY(-2px)" : "none",
      cursor: interactive ? "pointer" : "default",
      color: "var(--text-body)",
      ...style
    }
  }, rest), children);
}
Object.assign(__ds_scope, { Card });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Card.jsx", error: String((e && e.message) || e) }); }

// components/core/CodeBlock.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * CodeBlock — Space Mono, always dark per brand rule
 * ("developer tools and code should always be displayed in dark mode").
 */
function CodeBlock({
  children,
  lang = "bash",
  filename,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({
    "data-mode": "dark",
    style: {
      background: "var(--blue-dark)",
      borderRadius: "var(--radius-md)",
      border: "1px solid rgba(255,255,255,0.1)",
      overflow: "hidden",
      fontFamily: "var(--font-mono)",
      ...style
    }
  }, rest), (filename || lang) && /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between",
      padding: "8px 14px",
      borderBottom: "1px solid rgba(255,255,255,0.08)"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: "12px",
      color: "#7E94A3"
    }
  }, filename || ""), /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: "11px",
      letterSpacing: "0.12em",
      textTransform: "uppercase",
      color: "var(--green-bright)"
    }
  }, lang)), /*#__PURE__*/React.createElement("pre", {
    style: {
      margin: 0,
      padding: "14px 16px",
      overflowX: "auto",
      fontSize: "13px",
      lineHeight: 1.6,
      color: "#D7E2EA"
    }
  }, /*#__PURE__*/React.createElement("code", null, children)));
}
Object.assign(__ds_scope, { CodeBlock });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/CodeBlock.jsx", error: String((e && e.message) || e) }); }

// components/core/Eyebrow.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Eyebrow / kicker — Space Mono, ALL CAPS, tracked. Sits above headlines.
 * (e.g. "BLOG POST", "PROFESSIONAL SERVICES PACKS")
 */
function Eyebrow({
  children,
  color = "var(--green-deep)",
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("span", _extends({
    style: {
      fontFamily: "var(--font-mono)",
      fontSize: "var(--fs-eyebrow)",
      fontWeight: 700,
      textTransform: "uppercase",
      letterSpacing: "var(--ls-eyebrow)",
      color,
      display: "inline-block",
      ...style
    }
  }, rest), children);
}
Object.assign(__ds_scope, { Eyebrow });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Eyebrow.jsx", error: String((e && e.message) || e) }); }

// components/core/IconButton.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** IconButton — square, icon-only action. Pass a Lucide (or other) icon node as children. */
function IconButton({
  children,
  variant = "ghost",
  size = 40,
  label,
  disabled = false,
  style,
  ...rest
}) {
  const [hover, setHover] = React.useState(false);
  const variants = {
    ghost: {
      background: hover ? "var(--surface-sunk)" : "transparent",
      color: "var(--text-strong)",
      border: "1px solid transparent"
    },
    outline: {
      background: hover ? "var(--surface-sunk)" : "transparent",
      color: "var(--text-strong)",
      border: "1px solid var(--border-strong)"
    },
    solid: {
      background: hover ? "var(--accent-hover)" : "var(--accent)",
      color: "var(--on-accent)",
      border: "1px solid transparent"
    }
  };
  return /*#__PURE__*/React.createElement("button", _extends({
    "aria-label": label,
    disabled: disabled,
    onMouseEnter: () => setHover(true),
    onMouseLeave: () => setHover(false),
    style: {
      width: size,
      height: size,
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      borderRadius: "var(--radius-md)",
      cursor: disabled ? "not-allowed" : "pointer",
      opacity: disabled ? 0.45 : 1,
      transition: "var(--transition-base)",
      ...variants[variant],
      ...style
    }
  }, rest), children);
}
Object.assign(__ds_scope, { IconButton });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/IconButton.jsx", error: String((e && e.message) || e) }); }

// components/core/Logo.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/**
 * Solace wordmark — "solace." set in the display serif with the
 * signature green period. NOTE: the real logotype uses custom ITC
 * Souvenir glyphs; this is a faithful CSS recreation (see readme Caveats).
 */
function Logo({
  size = 28,
  color,
  dotColor = "var(--green-classic)",
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("span", _extends({
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: size,
      lineHeight: 1,
      letterSpacing: "-0.02em",
      color: color || "var(--text-strong)",
      display: "inline-flex",
      alignItems: "baseline",
      userSelect: "none",
      ...style
    }
  }, rest), "solace", /*#__PURE__*/React.createElement("span", {
    style: {
      color: dotColor
    }
  }, "."));
}

/** The circular "S" app/fav icon. */
function LogoMark({
  size = 40,
  bg = "var(--green-classic)",
  fg = "var(--blue-deep)",
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("span", _extends({
    style: {
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      width: size,
      height: size,
      borderRadius: "var(--radius-circle)",
      background: bg,
      color: fg,
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: size * 0.56,
      lineHeight: 1,
      ...style
    }
  }, rest), "s", /*#__PURE__*/React.createElement("span", {
    style: {
      marginLeft: size * 0.01
    }
  }, "."));
}
Object.assign(__ds_scope, { Logo, LogoMark });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Logo.jsx", error: String((e && e.message) || e) }); }

// components/core/Stat.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Stat — big number + label, used in metrics strips ("118 billion messages a day"). Display serif numeral. */
function Stat({
  value,
  label,
  sub,
  align = "left",
  accent = "var(--green-deep)",
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("div", _extends({
    style: {
      textAlign: align,
      ...style
    }
  }, rest), /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: "var(--fs-display-2)",
      lineHeight: 1,
      letterSpacing: "-0.02em",
      color: accent
    }
  }, value), /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: "var(--font-sans)",
      fontWeight: 600,
      fontSize: "15px",
      color: "var(--text-strong)",
      marginTop: "10px"
    }
  }, label), sub && /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: "var(--font-sans)",
      fontSize: "13px",
      color: "var(--text-muted)",
      marginTop: "4px"
    }
  }, sub));
}
Object.assign(__ds_scope, { Stat });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Stat.jsx", error: String((e && e.message) || e) }); }

// components/core/Tabs.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Tabs — underline style. Controlled or uncontrolled. */
function Tabs({
  tabs = [],
  value,
  defaultValue,
  onChange,
  style,
  ...rest
}) {
  const [internal, setInternal] = React.useState(defaultValue ?? (tabs[0] && tabs[0].id));
  const active = value !== undefined ? value : internal;
  const select = id => {
    setInternal(id);
    onChange && onChange(id);
  };
  return /*#__PURE__*/React.createElement("div", _extends({
    role: "tablist",
    style: {
      display: "flex",
      gap: "4px",
      borderBottom: "1px solid var(--border-subtle)",
      ...style
    }
  }, rest), tabs.map(t => {
    const on = t.id === active;
    return /*#__PURE__*/React.createElement("button", {
      key: t.id,
      role: "tab",
      "aria-selected": on,
      onClick: () => select(t.id),
      style: {
        position: "relative",
        border: "none",
        background: "transparent",
        cursor: "pointer",
        padding: "12px 16px",
        fontFamily: "var(--font-sans)",
        fontSize: "15px",
        fontWeight: on ? 600 : 500,
        color: on ? "var(--text-strong)" : "var(--text-muted)",
        transition: "color var(--dur-base) var(--ease-standard)"
      }
    }, t.label, /*#__PURE__*/React.createElement("span", {
      style: {
        position: "absolute",
        left: 0,
        right: 0,
        bottom: "-1px",
        height: "2.5px",
        borderRadius: "2px",
        background: on ? "var(--accent)" : "transparent",
        transition: "background var(--dur-base) var(--ease-standard)"
      }
    }));
  }));
}
Object.assign(__ds_scope, { Tabs });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Tabs.jsx", error: String((e && e.message) || e) }); }

// components/core/Tag.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Tag — removable/filter chip. Slightly larger than Badge; supports a remove affordance and selected state. */
function Tag({
  children,
  selected = false,
  onRemove,
  style,
  ...rest
}) {
  return /*#__PURE__*/React.createElement("span", _extends({
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: "8px",
      height: "30px",
      padding: onRemove ? "0 8px 0 12px" : "0 14px",
      fontFamily: "var(--font-sans)",
      fontSize: "13px",
      fontWeight: 500,
      borderRadius: "var(--radius-pill)",
      background: selected ? "color-mix(in srgb, var(--green-classic) 16%, white)" : "var(--surface-sunk)",
      color: selected ? "var(--green-deep)" : "var(--text-body)",
      border: `1px solid ${selected ? "color-mix(in srgb, var(--green-classic) 38%, transparent)" : "var(--border-subtle)"}`,
      cursor: "default",
      ...style
    }
  }, rest), children, onRemove && /*#__PURE__*/React.createElement("button", {
    onClick: onRemove,
    "aria-label": "Remove",
    style: {
      border: "none",
      background: "transparent",
      cursor: "pointer",
      display: "inline-flex",
      padding: 0,
      color: "inherit",
      opacity: 0.7,
      fontSize: "15px",
      lineHeight: 1
    }
  }, "\xD7"));
}
Object.assign(__ds_scope, { Tag });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/core/Tag.jsx", error: String((e && e.message) || e) }); }

// components/forms/Checkbox.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Checkbox — custom-styled, green when checked. Controlled or uncontrolled. */
function Checkbox({
  label,
  checked,
  defaultChecked,
  onChange,
  disabled = false,
  id,
  style,
  ...rest
}) {
  const [internal, setInternal] = React.useState(!!defaultChecked);
  const on = checked !== undefined ? checked : internal;
  const inputId = id || React.useId();
  const toggle = e => {
    setInternal(e.target.checked);
    onChange && onChange(e);
  };
  return /*#__PURE__*/React.createElement("label", {
    htmlFor: inputId,
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: "10px",
      fontFamily: "var(--font-sans)",
      fontSize: "15px",
      color: "var(--text-body)",
      cursor: disabled ? "not-allowed" : "pointer",
      opacity: disabled ? 0.5 : 1,
      ...style
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: "20px",
      height: "20px",
      borderRadius: "var(--radius-xs)",
      flexShrink: 0,
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      background: on ? "var(--accent)" : "var(--surface-card)",
      border: `1.5px solid ${on ? "var(--accent)" : "var(--border-strong)"}`,
      transition: "var(--transition-base)"
    }
  }, on && /*#__PURE__*/React.createElement("svg", {
    width: "12",
    height: "12",
    viewBox: "0 0 12 12",
    fill: "none"
  }, /*#__PURE__*/React.createElement("path", {
    d: "M2.5 6.2L4.8 8.5L9.5 3.5",
    stroke: "var(--on-accent)",
    strokeWidth: "2",
    strokeLinecap: "round",
    strokeLinejoin: "round"
  }))), /*#__PURE__*/React.createElement("input", _extends({
    id: inputId,
    type: "checkbox",
    checked: on,
    disabled: disabled,
    onChange: toggle,
    style: {
      position: "absolute",
      opacity: 0,
      width: 0,
      height: 0
    }
  }, rest)), label);
}
Object.assign(__ds_scope, { Checkbox });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Checkbox.jsx", error: String((e && e.message) || e) }); }

// components/forms/Input.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Input — text field with optional label, hint, error, and icon. */
function Input({
  label,
  hint,
  error,
  iconLeft,
  id,
  style,
  wrapStyle,
  ...rest
}) {
  const [focus, setFocus] = React.useState(false);
  const inputId = id || React.useId();
  const borderColor = error ? "var(--status-danger)" : focus ? "var(--accent)" : "var(--border-strong)";
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      flexDirection: "column",
      gap: "6px",
      fontFamily: "var(--font-sans)",
      ...wrapStyle
    }
  }, label && /*#__PURE__*/React.createElement("label", {
    htmlFor: inputId,
    style: {
      fontSize: "14px",
      fontWeight: 600,
      color: "var(--text-strong)"
    }
  }, label), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      display: "flex",
      alignItems: "center"
    }
  }, iconLeft && /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      left: "12px",
      display: "inline-flex",
      color: "var(--text-muted)",
      pointerEvents: "none"
    }
  }, iconLeft), /*#__PURE__*/React.createElement("input", _extends({
    id: inputId,
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false),
    style: {
      width: "100%",
      height: "44px",
      boxSizing: "border-box",
      padding: iconLeft ? "0 14px 0 38px" : "0 14px",
      fontFamily: "var(--font-sans)",
      fontSize: "15px",
      color: "var(--text-body)",
      background: "var(--surface-card)",
      border: `1.5px solid ${borderColor}`,
      borderRadius: "var(--radius-md)",
      outline: "none",
      boxShadow: focus ? "var(--ring)" : "none",
      transition: "border-color var(--dur-base) var(--ease-standard), box-shadow var(--dur-base) var(--ease-standard)",
      ...style
    }
  }, rest))), (hint || error) && /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: "12.5px",
      color: error ? "var(--status-danger)" : "var(--text-muted)"
    }
  }, error || hint));
}
Object.assign(__ds_scope, { Input });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Input.jsx", error: String((e && e.message) || e) }); }

// components/forms/Select.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Select — native dropdown styled to match Input. */
function Select({
  label,
  hint,
  error,
  options = [],
  id,
  style,
  wrapStyle,
  children,
  ...rest
}) {
  const [focus, setFocus] = React.useState(false);
  const inputId = id || React.useId();
  const borderColor = error ? "var(--status-danger)" : focus ? "var(--accent)" : "var(--border-strong)";
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      flexDirection: "column",
      gap: "6px",
      fontFamily: "var(--font-sans)",
      ...wrapStyle
    }
  }, label && /*#__PURE__*/React.createElement("label", {
    htmlFor: inputId,
    style: {
      fontSize: "14px",
      fontWeight: 600,
      color: "var(--text-strong)"
    }
  }, label), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      display: "flex",
      alignItems: "center"
    }
  }, /*#__PURE__*/React.createElement("select", _extends({
    id: inputId,
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false),
    style: {
      width: "100%",
      height: "44px",
      boxSizing: "border-box",
      padding: "0 38px 0 14px",
      appearance: "none",
      WebkitAppearance: "none",
      fontFamily: "var(--font-sans)",
      fontSize: "15px",
      color: "var(--text-body)",
      background: "var(--surface-card)",
      border: `1.5px solid ${borderColor}`,
      borderRadius: "var(--radius-md)",
      outline: "none",
      cursor: "pointer",
      boxShadow: focus ? "var(--ring)" : "none",
      transition: "border-color var(--dur-base) var(--ease-standard), box-shadow var(--dur-base) var(--ease-standard)",
      ...style
    }
  }, rest), children || options.map(o => {
    const val = typeof o === "string" ? o : o.value;
    const lbl = typeof o === "string" ? o : o.label;
    return /*#__PURE__*/React.createElement("option", {
      key: val,
      value: val
    }, lbl);
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      right: "14px",
      pointerEvents: "none",
      color: "var(--text-muted)",
      fontSize: "11px"
    }
  }, "\u25BE")), (hint || error) && /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: "12.5px",
      color: error ? "var(--status-danger)" : "var(--text-muted)"
    }
  }, error || hint));
}
Object.assign(__ds_scope, { Select });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Select.jsx", error: String((e && e.message) || e) }); }

// components/forms/Switch.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Switch — toggle. Green track when on; calm slide. */
function Switch({
  label,
  checked,
  defaultChecked,
  onChange,
  disabled = false,
  id,
  style,
  ...rest
}) {
  const [internal, setInternal] = React.useState(!!defaultChecked);
  const on = checked !== undefined ? checked : internal;
  const inputId = id || React.useId();
  const toggle = e => {
    setInternal(e.target.checked);
    onChange && onChange(e);
  };
  return /*#__PURE__*/React.createElement("label", {
    htmlFor: inputId,
    style: {
      display: "inline-flex",
      alignItems: "center",
      gap: "10px",
      fontFamily: "var(--font-sans)",
      fontSize: "15px",
      color: "var(--text-body)",
      cursor: disabled ? "not-allowed" : "pointer",
      opacity: disabled ? 0.5 : 1,
      ...style
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: "42px",
      height: "24px",
      borderRadius: "var(--radius-pill)",
      flexShrink: 0,
      position: "relative",
      background: on ? "var(--accent)" : "var(--gray-14)",
      transition: "background var(--dur-base) var(--ease-standard)"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      top: "2px",
      left: on ? "20px" : "2px",
      width: "20px",
      height: "20px",
      borderRadius: "50%",
      background: "white",
      boxShadow: "var(--shadow-sm)",
      transition: "left var(--dur-base) var(--ease-out)"
    }
  })), /*#__PURE__*/React.createElement("input", _extends({
    id: inputId,
    type: "checkbox",
    checked: on,
    disabled: disabled,
    onChange: toggle,
    style: {
      position: "absolute",
      opacity: 0,
      width: 0,
      height: 0
    }
  }, rest)), label);
}
Object.assign(__ds_scope, { Switch });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Switch.jsx", error: String((e && e.message) || e) }); }

// components/forms/Textarea.jsx
try { (() => {
function _extends() { return _extends = Object.assign ? Object.assign.bind() : function (n) { for (var e = 1; e < arguments.length; e++) { var t = arguments[e]; for (var r in t) ({}).hasOwnProperty.call(t, r) && (n[r] = t[r]); } return n; }, _extends.apply(null, arguments); }
/** Textarea — multiline field matching Input styling. */
function Textarea({
  label,
  hint,
  error,
  id,
  rows = 4,
  style,
  wrapStyle,
  ...rest
}) {
  const [focus, setFocus] = React.useState(false);
  const inputId = id || React.useId();
  const borderColor = error ? "var(--status-danger)" : focus ? "var(--accent)" : "var(--border-strong)";
  return /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      flexDirection: "column",
      gap: "6px",
      fontFamily: "var(--font-sans)",
      ...wrapStyle
    }
  }, label && /*#__PURE__*/React.createElement("label", {
    htmlFor: inputId,
    style: {
      fontSize: "14px",
      fontWeight: 600,
      color: "var(--text-strong)"
    }
  }, label), /*#__PURE__*/React.createElement("textarea", _extends({
    id: inputId,
    rows: rows,
    onFocus: () => setFocus(true),
    onBlur: () => setFocus(false),
    style: {
      width: "100%",
      boxSizing: "border-box",
      padding: "12px 14px",
      resize: "vertical",
      fontFamily: "var(--font-sans)",
      fontSize: "15px",
      lineHeight: 1.5,
      color: "var(--text-body)",
      background: "var(--surface-card)",
      border: `1.5px solid ${borderColor}`,
      borderRadius: "var(--radius-md)",
      outline: "none",
      boxShadow: focus ? "var(--ring)" : "none",
      transition: "border-color var(--dur-base) var(--ease-standard), box-shadow var(--dur-base) var(--ease-standard)",
      ...style
    }
  }, rest)), (hint || error) && /*#__PURE__*/React.createElement("span", {
    style: {
      fontSize: "12.5px",
      color: error ? "var(--status-danger)" : "var(--text-muted)"
    }
  }, error || hint));
}
Object.assign(__ds_scope, { Textarea });
})(); } catch (e) { __ds_ns.__errors.push({ path: "components/forms/Textarea.jsx", error: String((e && e.message) || e) }); }

// ui_kits/platform/app.jsx
try { (() => {
/* Solace Platform console — assembly (dark mode) */
function PlatformApp() {
  const [active, setActive] = React.useState("mesh");
  const titles = {
    mesh: "Event Mesh",
    brokers: "Brokers",
    portal: "Event Portal",
    queues: "Queues",
    monitoring: "Monitoring",
    access: "Access & Roles"
  };
  return /*#__PURE__*/React.createElement("div", {
    "data-mode": "dark",
    style: {
      display: "flex",
      height: "100vh",
      fontFamily: "var(--font-sans)",
      background: "var(--surface-page)",
      color: "var(--text-body)",
      overflow: "hidden"
    }
  }, /*#__PURE__*/React.createElement(Sidebar, {
    active: active,
    onSelect: setActive
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      display: "flex",
      flexDirection: "column",
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement(Topbar, {
    title: titles[active]
  }), active === "mesh" ? /*#__PURE__*/React.createElement(MeshView, null) : /*#__PURE__*/React.createElement(Placeholder, {
    title: titles[active]
  })));
}
ReactDOM.createRoot(document.getElementById("root")).render(/*#__PURE__*/React.createElement(PlatformApp, null));
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/platform/app.jsx", error: String((e && e.message) || e) }); }

// ui_kits/platform/components.jsx
try { (() => {
/* Solace Platform console UI Kit — shared components (dark mode dev tool). */
const PDS = window.SolaceDesignSystem_2b582b;
const {
  Button,
  Badge,
  Tag,
  Input,
  Tabs,
  Stat
} = PDS;
function PIcon({
  name,
  size = 18,
  color = "currentColor",
  strokeWidth = 2,
  style
}) {
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (window.lucide && ref.current) {
      ref.current.innerHTML = "";
      const el = document.createElement("i");
      el.setAttribute("data-lucide", name);
      ref.current.appendChild(el);
      window.lucide.createIcons({
        attrs: {
          width: size,
          height: size,
          stroke: color,
          "stroke-width": strokeWidth
        },
        nameAttr: "data-lucide"
      });
    }
  });
  return /*#__PURE__*/React.createElement("span", {
    ref: ref,
    style: {
      display: "inline-flex",
      lineHeight: 0,
      ...style
    }
  });
}

/* ---------- Sidebar ---------- */
function Sidebar({
  active,
  onSelect
}) {
  const nav = [{
    id: "mesh",
    label: "Event Mesh",
    icon: "share-2"
  }, {
    id: "brokers",
    label: "Brokers",
    icon: "server"
  }, {
    id: "portal",
    label: "Event Portal",
    icon: "git-branch"
  }, {
    id: "queues",
    label: "Queues",
    icon: "layers"
  }, {
    id: "monitoring",
    label: "Monitoring",
    icon: "activity"
  }, {
    id: "access",
    label: "Access & Roles",
    icon: "shield"
  }];
  return /*#__PURE__*/React.createElement("aside", {
    style: {
      width: "248px",
      flexShrink: 0,
      background: "var(--surface-sunk)",
      borderRight: "1px solid var(--border-subtle)",
      display: "flex",
      flexDirection: "column",
      padding: "20px 14px"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: "10px",
      padding: "4px 10px 22px"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 30,
      height: 30,
      borderRadius: "50%",
      background: "var(--green-classic)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: 17,
      color: "var(--blue-deep)"
    }
  }, "s."), /*#__PURE__*/React.createElement("span", {
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: 19,
      color: "#fff"
    }
  }, "Platform")), /*#__PURE__*/React.createElement("nav", {
    style: {
      display: "flex",
      flexDirection: "column",
      gap: "2px"
    }
  }, nav.map(n => {
    const on = n.id === active;
    return /*#__PURE__*/React.createElement("button", {
      key: n.id,
      onClick: () => onSelect(n.id),
      style: {
        display: "flex",
        alignItems: "center",
        gap: "11px",
        padding: "10px 12px",
        border: "none",
        borderRadius: "var(--radius-sm)",
        cursor: "pointer",
        textAlign: "left",
        background: on ? "color-mix(in srgb, var(--green-classic) 16%, transparent)" : "transparent",
        color: on ? "var(--green-bright)" : "var(--text-body)",
        fontFamily: "var(--font-sans)",
        fontSize: 14.5,
        fontWeight: on ? 600 : 500
      }
    }, /*#__PURE__*/React.createElement(PIcon, {
      name: n.icon,
      size: 18,
      color: on ? "var(--green-bright)" : "var(--text-muted)"
    }), n.label);
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: "auto",
      padding: "12px",
      borderRadius: "var(--radius-md)",
      background: "var(--surface-raised)",
      border: "1px solid var(--border-subtle)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12.5,
      color: "var(--text-muted)",
      marginBottom: 8
    }
  }, "Production cluster"), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 8,
      fontSize: 13.5,
      color: "var(--text-strong)",
      fontWeight: 600
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 8,
      height: 8,
      borderRadius: 4,
      background: "var(--green-classic)"
    }
  }), " Healthy \xB7 6/6 nodes")));
}

/* ---------- Top bar ---------- */
function Topbar({
  title
}) {
  return /*#__PURE__*/React.createElement("header", {
    style: {
      height: "64px",
      flexShrink: 0,
      borderBottom: "1px solid var(--border-subtle)",
      display: "flex",
      alignItems: "center",
      gap: "16px",
      padding: "0 24px",
      background: "var(--surface-page)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: "8px",
      fontFamily: "var(--font-mono)",
      fontSize: 12,
      letterSpacing: "0.06em",
      textTransform: "uppercase",
      color: "var(--text-muted)"
    }
  }, /*#__PURE__*/React.createElement("span", null, "us-east-1"), /*#__PURE__*/React.createElement(PIcon, {
    name: "chevron-right",
    size: 13,
    color: "var(--text-muted)"
  }), /*#__PURE__*/React.createElement("span", {
    style: {
      color: "var(--text-strong)"
    }
  }, title)), /*#__PURE__*/React.createElement("div", {
    style: {
      marginLeft: "auto",
      display: "flex",
      alignItems: "center",
      gap: "14px"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      display: "flex",
      alignItems: "center"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      position: "absolute",
      left: 12,
      display: "inline-flex"
    }
  }, /*#__PURE__*/React.createElement(PIcon, {
    name: "search",
    size: 16,
    color: "var(--text-muted)"
  })), /*#__PURE__*/React.createElement("input", {
    placeholder: "Search topics, queues\u2026",
    style: {
      height: 38,
      width: 240,
      padding: "0 14px 0 36px",
      borderRadius: "var(--radius-pill)",
      border: "1.5px solid var(--border-subtle)",
      background: "var(--surface-sunk)",
      color: "var(--text-body)",
      fontFamily: "var(--font-sans)",
      fontSize: 14,
      outline: "none"
    }
  })), /*#__PURE__*/React.createElement("button", {
    style: {
      width: 38,
      height: 38,
      borderRadius: "50%",
      border: "1px solid var(--border-subtle)",
      background: "var(--surface-sunk)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      cursor: "pointer"
    }
  }, /*#__PURE__*/React.createElement(PIcon, {
    name: "bell",
    size: 17,
    color: "var(--text-body)"
  })), /*#__PURE__*/React.createElement("span", {
    style: {
      width: 38,
      height: 38,
      borderRadius: "50%",
      background: "var(--blue-deep)",
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center",
      color: "#fff",
      fontWeight: 600,
      fontSize: 14,
      fontFamily: "var(--font-sans)"
    }
  }, "DK")));
}

/* ---------- Mesh node card ---------- */
function MeshNode({
  name,
  region,
  msgRate,
  status,
  x,
  y,
  accent
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      left: x,
      top: y,
      width: 184,
      padding: "14px 16px",
      borderRadius: "var(--radius-lg)",
      background: "var(--surface-card)",
      border: `1.5px solid ${status === "active" ? "var(--green-classic)" : "var(--border-strong)"}`,
      boxShadow: "var(--shadow-md)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 8
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      width: 30,
      height: 30,
      borderRadius: "var(--radius-sm)",
      background: accent,
      display: "inline-flex",
      alignItems: "center",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement(PIcon, {
    name: "server",
    size: 16,
    color: "var(--blue-deep)"
  })), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 14,
      fontWeight: 700,
      color: "var(--text-strong)"
    }
  }, name), /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: "var(--font-mono)",
      fontSize: 11,
      color: "var(--text-muted)"
    }
  }, region))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 12,
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between"
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      fontFamily: "var(--font-mono)",
      fontSize: 12,
      color: "var(--green-bright)"
    }
  }, msgRate), /*#__PURE__*/React.createElement(Badge, {
    tone: "green",
    solid: status === "active"
  }, status === "active" ? "Active" : "Standby")));
}

/* ---------- Mesh canvas ---------- */
function MeshView() {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "24px",
      flex: 1,
      overflow: "auto"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      justifyContent: "space-between",
      marginBottom: 20
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("h1", {
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: 30,
      color: "var(--text-strong)",
      margin: 0
    }
  }, "Event Mesh"), /*#__PURE__*/React.createElement("p", {
    style: {
      fontSize: 14.5,
      color: "var(--text-muted)",
      margin: "6px 0 0"
    }
  }, "6 brokers across 3 regions \xB7 streaming 1.3M msg/s")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 10
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    size: "sm",
    iconLeft: /*#__PURE__*/React.createElement(PIcon, {
      name: "download",
      size: 15,
      color: "var(--text-strong)"
    })
  }, "Export"), /*#__PURE__*/React.createElement(Button, {
    variant: "primary",
    size: "sm",
    iconLeft: /*#__PURE__*/React.createElement(PIcon, {
      name: "plus",
      size: 16,
      color: "var(--on-accent)"
    })
  }, "Add broker"))), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "grid",
      gridTemplateColumns: "repeat(4,1fr)",
      gap: 16,
      marginBottom: 24
    }
  }, [["Throughput", "1.31M", "msg/s"], ["Connections", "48,210", "clients"], ["Latency", "0.8ms", "p99"], ["Spool", "12%", "used"]].map(([l, v, s]) => /*#__PURE__*/React.createElement("div", {
    key: l,
    style: {
      padding: "16px 18px",
      borderRadius: "var(--radius-md)",
      background: "var(--surface-card)",
      border: "1px solid var(--border-subtle)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12.5,
      color: "var(--text-muted)"
    }
  }, l), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "baseline",
      gap: 6,
      marginTop: 6
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: 26,
      color: "var(--text-strong)"
    }
  }, v), /*#__PURE__*/React.createElement("span", {
    style: {
      fontFamily: "var(--font-mono)",
      fontSize: 11,
      color: "var(--text-muted)"
    }
  }, s))))), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      height: 320,
      borderRadius: "var(--radius-lg)",
      background: "var(--surface-sunk)",
      border: "1px solid var(--border-subtle)",
      overflow: "hidden"
    }
  }, /*#__PURE__*/React.createElement("svg", {
    style: {
      position: "absolute",
      inset: 0,
      width: "100%",
      height: "100%"
    }
  }, /*#__PURE__*/React.createElement("line", {
    x1: "150",
    y1: "90",
    x2: "430",
    y2: "90",
    stroke: "var(--green-classic)",
    strokeWidth: "1.5",
    strokeDasharray: "5 5",
    opacity: "0.6"
  }), /*#__PURE__*/React.createElement("line", {
    x1: "150",
    y1: "90",
    x2: "290",
    y2: "230",
    stroke: "var(--green-classic)",
    strokeWidth: "1.5",
    strokeDasharray: "5 5",
    opacity: "0.6"
  }), /*#__PURE__*/React.createElement("line", {
    x1: "430",
    y1: "90",
    x2: "290",
    y2: "230",
    stroke: "var(--green-classic)",
    strokeWidth: "1.5",
    strokeDasharray: "5 5",
    opacity: "0.6"
  }), /*#__PURE__*/React.createElement("line", {
    x1: "430",
    y1: "90",
    x2: "610",
    y2: "230",
    stroke: "var(--border-strong)",
    strokeWidth: "1.5",
    strokeDasharray: "5 5",
    opacity: "0.5"
  })), /*#__PURE__*/React.createElement(MeshNode, {
    name: "broker-ny-1",
    region: "us-east-1",
    msgRate: "612K/s",
    status: "active",
    x: 40,
    y: 48,
    accent: "var(--green-bright)"
  }), /*#__PURE__*/React.createElement(MeshNode, {
    name: "broker-eu-1",
    region: "eu-west-1",
    msgRate: "488K/s",
    status: "active",
    x: 400,
    y: 48,
    accent: "var(--blue-sky)"
  }), /*#__PURE__*/React.createElement(MeshNode, {
    name: "broker-ap-1",
    region: "ap-south-1",
    msgRate: "210K/s",
    status: "active",
    x: 200,
    y: 196,
    accent: "var(--green-spring)"
  }), /*#__PURE__*/React.createElement(MeshNode, {
    name: "broker-ap-2",
    region: "ap-south-1",
    msgRate: "0/s",
    status: "standby",
    x: 520,
    y: 196,
    accent: "var(--gray-14)"
  })));
}

/* ---------- Generic placeholder for other routes ---------- */
function Placeholder({
  title
}) {
  return /*#__PURE__*/React.createElement("div", {
    style: {
      flex: 1,
      display: "flex",
      flexDirection: "column",
      alignItems: "center",
      justifyContent: "center",
      color: "var(--text-muted)",
      gap: 12
    }
  }, /*#__PURE__*/React.createElement(PIcon, {
    name: "construction",
    size: 36,
    color: "var(--text-muted)"
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: "var(--font-display)",
      fontSize: 22,
      color: "var(--text-strong)"
    }
  }, title), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 14
    }
  }, "This surface is part of the kit's navigation demo."));
}
Object.assign(window, {
  PIcon,
  Sidebar,
  Topbar,
  MeshView,
  MeshNode,
  Placeholder
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/platform/components.jsx", error: String((e && e.message) || e) }); }

// ui_kits/website/app.jsx
try { (() => {
/* Solace marketing homepage — assembly */
function WebsiteApp() {
  const [route, setRoute] = React.useState("home");
  return /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: "var(--font-sans)",
      color: "var(--text-body)",
      background: "#fff",
      minHeight: "100vh"
    }
  }, /*#__PURE__*/React.createElement(SiteNav, {
    onNav: setRoute
  }), /*#__PURE__*/React.createElement(Hero, {
    onNav: setRoute
  }), /*#__PURE__*/React.createElement(StatStrip, null), /*#__PURE__*/React.createElement(ResourceGrid, null), /*#__PURE__*/React.createElement(CtaBand, null), /*#__PURE__*/React.createElement(SiteFooter, null));
}
ReactDOM.createRoot(document.getElementById("root")).render(/*#__PURE__*/React.createElement(WebsiteApp, null));
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/website/app.jsx", error: String((e && e.message) || e) }); }

// ui_kits/website/components.jsx
try { (() => {
/* Solace Website UI Kit — shared components.
   Loaded as a Babel script; exports to window for app.jsx.
   Uses DS primitives from window.SolaceDesignSystem_2b582b and Lucide icons. */
const DS = window.SolaceDesignSystem_2b582b;
const {
  Button,
  Eyebrow,
  Card,
  Badge,
  Tag,
  Avatar,
  Stat,
  Input
} = DS;

/* Lucide icon helper — re-runs createIcons after each render */
function Icon({
  name,
  size = 20,
  color = "currentColor",
  strokeWidth = 2,
  style
}) {
  const ref = React.useRef(null);
  React.useEffect(() => {
    if (window.lucide && ref.current) {
      ref.current.innerHTML = "";
      const el = document.createElement("i");
      el.setAttribute("data-lucide", name);
      ref.current.appendChild(el);
      window.lucide.createIcons({
        icons: window.lucide.icons,
        attrs: {
          width: size,
          height: size,
          stroke: color,
          "stroke-width": strokeWidth
        },
        nameAttr: "data-lucide"
      });
    }
  });
  return /*#__PURE__*/React.createElement("span", {
    ref: ref,
    style: {
      display: "inline-flex",
      lineHeight: 0,
      ...style
    }
  });
}

/* ---------- Top navigation ---------- */
function SiteNav({
  onNav
}) {
  const links = ["Platform", "Solutions", "Developers", "Resources", "Company"];
  return /*#__PURE__*/React.createElement("header", {
    style: {
      position: "sticky",
      top: 0,
      zIndex: 20,
      background: "rgba(255,255,255,0.86)",
      backdropFilter: "var(--blur-md)",
      borderBottom: "1px solid var(--border-subtle)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: "var(--container-xl)",
      margin: "0 auto",
      height: "72px",
      padding: "0 32px",
      display: "flex",
      alignItems: "center",
      gap: "32px"
    }
  }, /*#__PURE__*/React.createElement("span", {
    onClick: () => onNav && onNav("home"),
    style: {
      cursor: "pointer",
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: "26px",
      letterSpacing: "-0.02em",
      color: "var(--blue-deep)"
    }
  }, "solace", /*#__PURE__*/React.createElement("span", {
    style: {
      color: "var(--green-classic)"
    }
  }, ".")), /*#__PURE__*/React.createElement("nav", {
    style: {
      display: "flex",
      gap: "4px",
      marginLeft: "8px"
    }
  }, links.map(l => /*#__PURE__*/React.createElement("a", {
    key: l,
    style: {
      display: "flex",
      alignItems: "center",
      gap: "4px",
      padding: "8px 14px",
      fontSize: "15px",
      fontWeight: 500,
      color: "var(--text-body)",
      textDecoration: "none",
      cursor: "pointer",
      borderRadius: "var(--radius-sm)"
    },
    onMouseEnter: e => e.currentTarget.style.background = "var(--surface-sunk)",
    onMouseLeave: e => e.currentTarget.style.background = "transparent"
  }, l, " ", (l === "Platform" || l === "Resources") && /*#__PURE__*/React.createElement(Icon, {
    name: "chevron-down",
    size: 15,
    color: "var(--text-muted)"
  })))), /*#__PURE__*/React.createElement("div", {
    style: {
      marginLeft: "auto",
      display: "flex",
      alignItems: "center",
      gap: "12px"
    }
  }, /*#__PURE__*/React.createElement("a", {
    style: {
      fontSize: "15px",
      fontWeight: 600,
      color: "var(--text-strong)",
      cursor: "pointer",
      display: "flex",
      alignItems: "center",
      gap: "6px"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "search",
    size: 17,
    color: "var(--text-muted)"
  }), " "), /*#__PURE__*/React.createElement(Button, {
    variant: "ghost",
    size: "sm",
    onClick: () => onNav && onNav("login")
  }, "Log in"), /*#__PURE__*/React.createElement(Button, {
    variant: "primary",
    size: "sm",
    iconRight: /*#__PURE__*/React.createElement(Icon, {
      name: "arrow-right",
      size: 16,
      color: "var(--on-accent)"
    })
  }, "Start free"))));
}

/* ---------- Decorative brand shapes ---------- */
function BrandShapes() {
  return /*#__PURE__*/React.createElement("div", {
    "aria-hidden": true,
    style: {
      position: "absolute",
      inset: 0,
      overflow: "hidden",
      pointerEvents: "none"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      right: "-60px",
      top: "40px",
      width: "360px",
      height: "360px",
      background: "var(--grad-lime)",
      opacity: 0.5,
      borderRadius: "50%",
      filter: "blur(2px)"
    }
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "absolute",
      right: "220px",
      top: "150px",
      width: "200px",
      height: "200px",
      border: "1.5px solid var(--blue-deep)",
      opacity: 0.18,
      borderRadius: "50%"
    }
  }));
}

/* ---------- Hero ---------- */
function Hero({
  onNav
}) {
  return /*#__PURE__*/React.createElement("section", {
    style: {
      position: "relative",
      background: "var(--surface-page)",
      overflow: "hidden"
    }
  }, /*#__PURE__*/React.createElement(BrandShapes, null), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      maxWidth: "var(--container-xl)",
      margin: "0 auto",
      padding: "96px 32px 80px",
      display: "grid",
      gridTemplateColumns: "1.05fr 0.95fr",
      gap: "48px",
      alignItems: "center"
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(Eyebrow, null, "Event-Driven Integration & Streaming"), /*#__PURE__*/React.createElement("h1", {
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 300,
      fontSize: "64px",
      lineHeight: 1.03,
      letterSpacing: "-0.015em",
      color: "var(--text-strong)",
      margin: "18px 0 0"
    }
  }, "The digital nervous system for the real-time, agentic world"), /*#__PURE__*/React.createElement("p", {
    style: {
      fontSize: "19px",
      lineHeight: 1.55,
      color: "var(--text-body)",
      margin: "22px 0 0",
      maxWidth: "520px"
    }
  }, "Connect every application, data source, system, and intelligent agent \u2014 securely, seamlessly, and at scale. Become real-time and agent-ready."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: "14px",
      marginTop: "32px"
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "primary",
    size: "lg",
    iconRight: /*#__PURE__*/React.createElement(Icon, {
      name: "arrow-right",
      size: 18,
      color: "var(--on-accent)"
    })
  }, "Start free"), /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    size: "lg",
    iconLeft: /*#__PURE__*/React.createElement(Icon, {
      name: "play",
      size: 16,
      color: "var(--text-strong)"
    })
  }, "Watch the webinar")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: "10px",
      marginTop: "28px",
      flexWrap: "wrap"
    }
  }, /*#__PURE__*/React.createElement(Tag, null, "PubSub+ Event Broker"), /*#__PURE__*/React.createElement(Tag, null, "Event Mesh"), /*#__PURE__*/React.createElement(Tag, null, "Event Portal"))), /*#__PURE__*/React.createElement("div", {
    style: {
      position: "relative",
      display: "flex",
      justifyContent: "center"
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: "../../assets/illustrations/migration.png",
    alt: "Connect every system",
    style: {
      width: "100%",
      maxWidth: "540px",
      height: "auto",
      filter: "drop-shadow(0 18px 40px rgba(9,59,95,0.14))"
    }
  }))));
}

/* ---------- Stats strip ---------- */
function StatStrip() {
  return /*#__PURE__*/React.createElement("section", {
    style: {
      background: "var(--gray-12)",
      borderTop: "1px solid var(--border-subtle)",
      borderBottom: "1px solid var(--border-subtle)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: "var(--container-lg)",
      margin: "0 auto",
      padding: "48px 32px",
      display: "grid",
      gridTemplateColumns: "repeat(4,1fr)",
      gap: "24px"
    }
  }, /*#__PURE__*/React.createElement(Stat, {
    value: "118B",
    label: "Messages per day",
    sub: "RBC Capital Markets"
  }), /*#__PURE__*/React.createElement(Stat, {
    value: "1.3M",
    label: "Messages per second"
  }), /*#__PURE__*/React.createElement(Stat, {
    value: "24/7",
    label: "Zero data loss"
  }), /*#__PURE__*/React.createElement(Stat, {
    value: "500+",
    label: "Enterprises connected"
  })));
}

/* ---------- Resource / blog grid ---------- */
function ResourceGrid() {
  const items = [{
    eyebrow: "Blog Post",
    img: "../../assets/illustrations/usability.png",
    title: "Micro-Integrations: The Lightweight Way to Liberate your Data",
    meta: "8 min read"
  }, {
    eyebrow: "Webinar",
    img: "../../assets/illustrations/scalability-light.png",
    title: "From Data Mess to Data Mesh: Building Data Products on Solace",
    meta: "On demand"
  }, {
    eyebrow: "Case Study",
    img: "../../assets/illustrations/reliability.png",
    title: "Enabling Modern Digital Retail Banking with Event-Driven Architecture",
    meta: "RBC · Capital Markets"
  }];
  return /*#__PURE__*/React.createElement("section", {
    style: {
      maxWidth: "var(--container-xl)",
      margin: "0 auto",
      padding: "88px 32px"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "flex-end",
      justifyContent: "space-between",
      marginBottom: "36px"
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement(Eyebrow, null, "Resources"), /*#__PURE__*/React.createElement("h2", {
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 300,
      fontSize: "44px",
      letterSpacing: "-0.01em",
      color: "var(--text-strong)",
      margin: "12px 0 0"
    }
  }, "From the Solace community")), /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    iconRight: /*#__PURE__*/React.createElement(Icon, {
      name: "arrow-right",
      size: 16,
      color: "var(--text-strong)"
    })
  }, "Browse all")), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "grid",
      gridTemplateColumns: "repeat(3,1fr)",
      gap: "24px"
    }
  }, items.map((it, i) => /*#__PURE__*/React.createElement(Card, {
    key: i,
    interactive: true,
    padding: "0",
    style: {
      overflow: "hidden",
      display: "flex",
      flexDirection: "column"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      height: "168px",
      background: "var(--gray-12)",
      position: "relative",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      overflow: "hidden"
    }
  }, /*#__PURE__*/React.createElement("img", {
    src: it.img,
    alt: "",
    style: {
      width: "100%",
      height: "100%",
      objectFit: "cover"
    }
  })), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: "20px 22px 24px",
      display: "flex",
      flexDirection: "column",
      flex: 1
    }
  }, /*#__PURE__*/React.createElement(Eyebrow, null, it.eyebrow), /*#__PURE__*/React.createElement("h3", {
    style: {
      fontFamily: "var(--font-sans)",
      fontWeight: 700,
      fontSize: "19px",
      lineHeight: 1.28,
      color: "var(--text-strong)",
      margin: "10px 0 0"
    }
  }, it.title), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: "auto",
      paddingTop: "18px",
      display: "flex",
      alignItems: "center",
      gap: "8px",
      fontSize: "13px",
      color: "var(--text-muted)"
    }
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "clock",
    size: 14,
    color: "var(--text-muted)"
  }), " ", it.meta))))));
}

/* ---------- CTA band (bright mode) ---------- */
function CtaBand() {
  return /*#__PURE__*/React.createElement("section", {
    "data-mode": "bright",
    style: {
      background: "var(--surface-page)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: "var(--container-lg)",
      margin: "0 auto",
      padding: "80px 32px",
      textAlign: "center"
    }
  }, /*#__PURE__*/React.createElement("h2", {
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 300,
      fontSize: "48px",
      letterSpacing: "-0.01em",
      color: "var(--text-strong)",
      margin: 0
    }
  }, "Ready to become real-time?"), /*#__PURE__*/React.createElement("p", {
    style: {
      fontSize: "18px",
      color: "var(--text-body)",
      margin: "16px auto 0",
      maxWidth: "560px"
    }
  }, "Spin up an event broker in minutes. No credit card required."), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: "14px",
      justifyContent: "center",
      marginTop: "32px"
    }
  }, /*#__PURE__*/React.createElement(Button, {
    variant: "primary",
    size: "lg"
  }, "Start free"), /*#__PURE__*/React.createElement(Button, {
    variant: "secondary",
    size: "lg"
  }, "Talk to an architect"))));
}

/* ---------- Footer ---------- */
function SiteFooter() {
  const cols = {
    Platform: ["Event Broker", "Event Mesh", "Event Portal", "Pricing"],
    Developers: ["Docs", "Tutorials", "API reference", "Community"],
    Company: ["About", "Customers", "Careers", "Contact"]
  };
  return /*#__PURE__*/React.createElement("footer", {
    "data-mode": "dark",
    style: {
      background: "var(--surface-page)",
      color: "var(--text-body)"
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      maxWidth: "var(--container-xl)",
      margin: "0 auto",
      padding: "64px 32px 40px",
      display: "grid",
      gridTemplateColumns: "1.4fr repeat(3,1fr)",
      gap: "32px"
    }
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("span", {
    style: {
      fontFamily: "var(--font-display)",
      fontWeight: 600,
      fontSize: 26,
      color: "#fff"
    }
  }, "solace", /*#__PURE__*/React.createElement("span", {
    style: {
      color: "var(--green-classic)"
    }
  }, ".")), /*#__PURE__*/React.createElement("p", {
    style: {
      fontSize: 14,
      lineHeight: 1.6,
      color: "var(--text-muted)",
      marginTop: 14,
      maxWidth: 280
    }
  }, "The digital nervous system for the real-time, event-driven, agentic world.")), Object.entries(cols).map(([h, links]) => /*#__PURE__*/React.createElement("div", {
    key: h
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontFamily: "var(--font-mono)",
      fontSize: 11,
      letterSpacing: "0.14em",
      textTransform: "uppercase",
      color: "var(--green-bright)",
      marginBottom: 14
    }
  }, h), links.map(l => /*#__PURE__*/React.createElement("a", {
    key: l,
    style: {
      display: "block",
      fontSize: 14,
      color: "var(--text-body)",
      textDecoration: "none",
      padding: "5px 0",
      cursor: "pointer"
    }
  }, l))))), /*#__PURE__*/React.createElement("div", {
    style: {
      borderTop: "1px solid rgba(255,255,255,0.12)",
      padding: "20px 32px",
      maxWidth: "var(--container-xl)",
      margin: "0 auto",
      display: "flex",
      justifyContent: "space-between",
      fontSize: 13,
      color: "var(--text-muted)"
    }
  }, /*#__PURE__*/React.createElement("span", null, "\xA9 2025 Solace, LLC."), /*#__PURE__*/React.createElement("span", {
    style: {
      display: "flex",
      gap: "18px"
    }
  }, /*#__PURE__*/React.createElement("a", {
    style: {
      color: "inherit",
      textDecoration: "none",
      cursor: "pointer"
    }
  }, "Privacy"), /*#__PURE__*/React.createElement("a", {
    style: {
      color: "inherit",
      textDecoration: "none",
      cursor: "pointer"
    }
  }, "Terms"))));
}
Object.assign(window, {
  Icon,
  SiteNav,
  Hero,
  StatStrip,
  ResourceGrid,
  CtaBand,
  SiteFooter
});
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/website/components.jsx", error: String((e && e.message) || e) }); }

__ds_ns.Alert = __ds_scope.Alert;

__ds_ns.Avatar = __ds_scope.Avatar;

__ds_ns.Badge = __ds_scope.Badge;

__ds_ns.Button = __ds_scope.Button;

__ds_ns.Card = __ds_scope.Card;

__ds_ns.CodeBlock = __ds_scope.CodeBlock;

__ds_ns.Eyebrow = __ds_scope.Eyebrow;

__ds_ns.IconButton = __ds_scope.IconButton;

__ds_ns.Logo = __ds_scope.Logo;

__ds_ns.LogoMark = __ds_scope.LogoMark;

__ds_ns.Stat = __ds_scope.Stat;

__ds_ns.Tabs = __ds_scope.Tabs;

__ds_ns.Tag = __ds_scope.Tag;

__ds_ns.Checkbox = __ds_scope.Checkbox;

__ds_ns.Input = __ds_scope.Input;

__ds_ns.Select = __ds_scope.Select;

__ds_ns.Switch = __ds_scope.Switch;

__ds_ns.Textarea = __ds_scope.Textarea;

})();
