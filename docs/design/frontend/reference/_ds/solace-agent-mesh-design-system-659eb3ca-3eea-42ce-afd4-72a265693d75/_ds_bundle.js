/* @ds-bundle: {"format":3,"namespace":"SolaceAgentMeshDesignSystem_659eb3","components":[],"sourceHashes":{"ui_kits/webui/AgentList.jsx":"21e7e9a1ecb1","ui_kits/webui/ChatPanel.jsx":"c8fc7a9857f6","ui_kits/webui/Header.jsx":"f0507a6be41a","ui_kits/webui/Icon.jsx":"998299f4601e","ui_kits/webui/SideSheet.jsx":"1da35a7ea2f3","ui_kits/webui/Sidebar.jsx":"245214a7c1f1"},"inlinedExternals":[],"unexposedExports":[]} */

(() => {

const __ds_ns = (window.SolaceAgentMeshDesignSystem_659eb3 = window.SolaceAgentMeshDesignSystem_659eb3 || {});

const __ds_scope = {};

(__ds_ns.__errors = __ds_ns.__errors || []);

// ui_kits/webui/AgentList.jsx
try { (() => {
const AGENTS = [{
  id: "oa",
  initials: "OA",
  name: "order-analyst",
  status: "ok",
  statusLabel: "Healthy",
  version: "v1.4.2",
  runtime: "python@3.11",
  desc: "Analyzes order failure patterns and surfaces root causes across payment, inventory and fraud channels.",
  invocations: "1,284",
  success: "98.2%",
  p50: "410ms",
  colors: ["#00C895", "#E6FAF4"]
}, {
  id: "rt",
  initials: "RT",
  name: "report-tailor",
  status: "warn",
  statusLabel: "Degraded",
  version: "v0.9.1",
  runtime: "python@3.11",
  desc: "Generates customer-facing weekly summaries. Escalates anomalies to a human reviewer.",
  invocations: "312",
  success: "87.1%",
  p50: "2.1s",
  colors: ["#FF8E2B", "#FFF4EA"]
}, {
  id: "fs",
  initials: "FS",
  name: "fraud-screen",
  status: "ok",
  statusLabel: "Healthy",
  version: "v2.0.0",
  runtime: "node@20",
  desc: "Real-time scoring of transactions, delegates borderline cases to reviewer-agent.",
  invocations: "9,022",
  success: "99.7%",
  p50: "88ms",
  colors: ["#00C895", "#E6FAF4"]
}, {
  id: "sn",
  initials: "SN",
  name: "sms-notifier",
  status: "err",
  statusLabel: "Failed",
  version: "v1.0.0",
  runtime: "python@3.11",
  desc: "Dispatches order-status SMS via Twilio. Currently unable to reach broker.",
  invocations: "0",
  success: "—",
  p50: "—",
  colors: ["#E94C4E", "#FDEDED"]
}, {
  id: "rv",
  initials: "RV",
  name: "reviewer-agent",
  status: "ok",
  statusLabel: "Healthy",
  version: "v1.2.0",
  runtime: "python@3.11",
  desc: "Human-in-the-loop gateway. Queues decisions in a Slack channel.",
  invocations: "44",
  success: "100%",
  p50: "—",
  colors: ["#015B82", "#E6EFF2"]
}, {
  id: "mc",
  initials: "MC",
  name: "mcp-connector",
  status: "info",
  statusLabel: "Idle",
  version: "v0.3.0",
  runtime: "node@20",
  desc: "Bridges MCP-compatible tool servers. Exposes filesystem + web.fetch tools.",
  invocations: "—",
  success: "—",
  p50: "—",
  colors: ["#015B82", "#E6EFF2"]
}];
const statusClass = {
  ok: "badge-ok",
  warn: "badge-warn",
  err: "badge-err",
  info: "badge-info"
};
const AgentCard = ({
  agent,
  onOpen
}) => /*#__PURE__*/React.createElement("div", {
  className: "card agent-card",
  onClick: () => onOpen(agent)
}, /*#__PURE__*/React.createElement("div", {
  className: "ac-head"
}, /*#__PURE__*/React.createElement("div", {
  className: "ac-icon",
  style: {
    background: agent.colors[1],
    color: agent.colors[0]
  }
}, agent.initials), /*#__PURE__*/React.createElement("div", {
  style: {
    minWidth: 0,
    flex: 1
  }
}, /*#__PURE__*/React.createElement("div", {
  className: "ac-name"
}, agent.name), /*#__PURE__*/React.createElement("div", {
  className: "ac-sub"
}, /*#__PURE__*/React.createElement("span", {
  className: `badge ${statusClass[agent.status]}`
}, /*#__PURE__*/React.createElement("span", {
  className: "d"
}), agent.statusLabel), /*#__PURE__*/React.createElement("span", {
  style: {
    color: "var(--secondary-text-wMain)",
    fontFamily: "var(--font-mono)",
    fontSize: 11
  }
}, agent.version))), /*#__PURE__*/React.createElement("button", {
  className: "icon-btn",
  style: {
    color: "var(--secondary-text-wMain)"
  }
}, /*#__PURE__*/React.createElement(Icon, {
  name: "more",
  size: 16
}))), /*#__PURE__*/React.createElement("p", {
  className: "ac-desc"
}, agent.desc), /*#__PURE__*/React.createElement("div", {
  className: "ac-stats"
}, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("span", {
  className: "ac-stats-val"
}, agent.invocations), /*#__PURE__*/React.createElement("span", {
  className: "ac-stats-lbl"
}, "invocations")), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("span", {
  className: "ac-stats-val"
}, agent.success), /*#__PURE__*/React.createElement("span", {
  className: "ac-stats-lbl"
}, "success")), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("span", {
  className: "ac-stats-val"
}, agent.p50), /*#__PURE__*/React.createElement("span", {
  className: "ac-stats-lbl"
}, "p50 latency"))));
const AgentMeshView = ({
  onOpen
}) => {
  const [q, setQ] = React.useState("");
  const filtered = AGENTS.filter(a => a.name.includes(q));
  return /*#__PURE__*/React.createElement("div", {
    className: "view"
  }, /*#__PURE__*/React.createElement("div", {
    className: "view-head"
  }, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("h1", {
    className: "view-title"
  }, "Agent mesh"), /*#__PURE__*/React.createElement("div", {
    className: "view-sub"
  }, "12 agents \xB7 connected to ", /*#__PURE__*/React.createElement("code", null, "solace-prod.us-east"), " \xB7 last heartbeat 3s ago")), /*#__PURE__*/React.createElement("div", {
    className: "view-actions"
  }, /*#__PURE__*/React.createElement("div", {
    className: "search-field"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "search",
    size: 14,
    style: {
      color: "var(--secondary-text-wMain)"
    }
  }), /*#__PURE__*/React.createElement("input", {
    placeholder: "Filter agents\u2026",
    value: q,
    onChange: e => setQ(e.target.value)
  }), /*#__PURE__*/React.createElement("kbd", null, "\u2318K")), /*#__PURE__*/React.createElement("button", {
    className: "btn btn-secondary"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "refresh",
    size: 14
  }), "Sync"), /*#__PURE__*/React.createElement("button", {
    className: "btn btn-primary"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "plus",
    size: 14
  }), "Deploy agent"))), /*#__PURE__*/React.createElement("div", {
    className: "filters-row"
  }, /*#__PURE__*/React.createElement("div", {
    className: "chip active"
  }, "All ", /*#__PURE__*/React.createElement("span", null, "12")), /*#__PURE__*/React.createElement("div", {
    className: "chip"
  }, "Healthy ", /*#__PURE__*/React.createElement("span", null, "9")), /*#__PURE__*/React.createElement("div", {
    className: "chip"
  }, "Degraded ", /*#__PURE__*/React.createElement("span", null, "2")), /*#__PURE__*/React.createElement("div", {
    className: "chip"
  }, "Failed ", /*#__PURE__*/React.createElement("span", null, "1")), /*#__PURE__*/React.createElement("div", {
    className: "chip-divider"
  }), /*#__PURE__*/React.createElement("div", {
    className: "chip ghost"
  }, "python ", /*#__PURE__*/React.createElement(Icon, {
    name: "close",
    size: 11
  })), /*#__PURE__*/React.createElement("div", {
    className: "chip ghost"
  }, "+ add filter")), /*#__PURE__*/React.createElement("div", {
    className: "agent-grid"
  }, filtered.map(a => /*#__PURE__*/React.createElement(AgentCard, {
    key: a.id,
    agent: a,
    onOpen: onOpen
  }))));
};
window.AgentMeshView = AgentMeshView;
window.AGENTS = AGENTS;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/webui/AgentList.jsx", error: String((e && e.message) || e) }); }

// ui_kits/webui/ChatPanel.jsx
try { (() => {
const SAMPLE_CONV = [{
  role: "user",
  text: "Summarize yesterday's order failures and tell me what to watch today."
}, {
  role: "tool",
  text: "order-analyst",
  detail: "invoked · reading broker topic solace/events/orders/failed"
}, {
  role: "assistant",
  text: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("p", {
    style: {
      margin: "0 0 8px"
    }
  }, /*#__PURE__*/React.createElement("b", null, "14 failures"), " across 3 channels yesterday:"), /*#__PURE__*/React.createElement("ul", {
    style: {
      margin: "0 0 8px 16px",
      padding: 0
    }
  }, /*#__PURE__*/React.createElement("li", null, "11 payment declines \u2014 all ", /*#__PURE__*/React.createElement("span", {
    className: "text-code"
  }, "stripe_4002"), " (card authentication)"), /*#__PURE__*/React.createElement("li", null, "2 inventory mismatches on SKU ", /*#__PURE__*/React.createElement("span", {
    className: "text-code"
  }, "AL-2201")), /*#__PURE__*/React.createElement("li", null, "1 fraud flag routed to reviewer-agent")), /*#__PURE__*/React.createElement("p", {
    style: {
      margin: 0
    }
  }, "Today the fraud rule was re-tuned \u2014 watch for false positives after 10 AM UTC when traffic picks up."))
}, {
  role: "user",
  text: "Open the flow for that fraud rule."
}];
const Composer = () => {
  const [val, setVal] = React.useState("");
  return /*#__PURE__*/React.createElement("div", {
    className: "composer"
  }, /*#__PURE__*/React.createElement("div", {
    className: "composer-attach-row"
  }, /*#__PURE__*/React.createElement("button", {
    className: "attach-btn"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "attach",
    size: 14
  }), "Attach"), /*#__PURE__*/React.createElement("button", {
    className: "attach-btn"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "bolt",
    size: 14
  }), "Tools ", /*#__PURE__*/React.createElement("kbd", null, "\u2318/")), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1
    }
  }), /*#__PURE__*/React.createElement("span", {
    className: "route-pill"
  }, /*#__PURE__*/React.createElement("span", {
    className: "d"
  }), " routing \u2192 ", /*#__PURE__*/React.createElement("b", null, "order-analyst"))), /*#__PURE__*/React.createElement("textarea", {
    value: val,
    onChange: e => setVal(e.target.value),
    placeholder: "Ask the mesh something, or /invoke an agent directly\u2026",
    rows: 2
  }), /*#__PURE__*/React.createElement("div", {
    className: "composer-foot"
  }, /*#__PURE__*/React.createElement("div", {
    className: "composer-hints"
  }, /*#__PURE__*/React.createElement("kbd", null, "\u21B5"), " to send \xA0\xB7\xA0 ", /*#__PURE__*/React.createElement("kbd", null, "\u21E7\u21B5"), " newline \xA0\xB7\xA0 ", /*#__PURE__*/React.createElement("kbd", null, "/"), " invoke"), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      gap: 6
    }
  }, /*#__PURE__*/React.createElement("button", {
    className: "icon-btn"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "mic",
    size: 15
  })), /*#__PURE__*/React.createElement("button", {
    className: "btn btn-primary btn-sm",
    disabled: !val
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "send",
    size: 13
  }), "Send"))));
};
const ChatView = () => /*#__PURE__*/React.createElement("div", {
  className: "chat-view"
}, /*#__PURE__*/React.createElement("div", {
  className: "chat-stream"
}, /*#__PURE__*/React.createElement("div", {
  className: "chat-day"
}, "Today, 3:14 PM"), SAMPLE_CONV.map((m, i) => {
  if (m.role === "tool") return /*#__PURE__*/React.createElement("div", {
    key: i,
    className: "msg-tool"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "bolt",
    size: 13
  }), /*#__PURE__*/React.createElement("span", null, "\u2192 invoking ", /*#__PURE__*/React.createElement("b", null, m.text)), /*#__PURE__*/React.createElement("span", {
    className: "muted"
  }, m.detail));
  return /*#__PURE__*/React.createElement("div", {
    key: i,
    className: `msg msg-${m.role}`
  }, m.role === "assistant" && /*#__PURE__*/React.createElement("div", {
    className: "msg-avatar"
  }, /*#__PURE__*/React.createElement("span", null, "S")), /*#__PURE__*/React.createElement("div", {
    className: "msg-bubble"
  }, m.text), m.role === "assistant" && /*#__PURE__*/React.createElement("div", {
    className: "msg-actions"
  }, /*#__PURE__*/React.createElement("button", {
    className: "icon-btn icon-btn-xs"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "copy",
    size: 13
  })), /*#__PURE__*/React.createElement("button", {
    className: "icon-btn icon-btn-xs"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "refresh",
    size: 13
  }))));
}), /*#__PURE__*/React.createElement("div", {
  className: "msg msg-assistant",
  style: {
    opacity: 0.75
  }
}, /*#__PURE__*/React.createElement("div", {
  className: "msg-avatar"
}, /*#__PURE__*/React.createElement("span", null, "S")), /*#__PURE__*/React.createElement("div", {
  className: "msg-bubble typing"
}, /*#__PURE__*/React.createElement("span", {
  className: "dot"
}), /*#__PURE__*/React.createElement("span", {
  className: "dot"
}), /*#__PURE__*/React.createElement("span", {
  className: "dot"
})))), /*#__PURE__*/React.createElement(Composer, null));
window.ChatView = ChatView;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/webui/ChatPanel.jsx", error: String((e && e.message) || e) }); }

// ui_kits/webui/Header.jsx
try { (() => {
const Header = ({
  title,
  subtitle,
  actions
}) => /*#__PURE__*/React.createElement("header", {
  className: "header"
}, /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("span", {
  className: "header-title"
}, title), subtitle && /*#__PURE__*/React.createElement("span", {
  className: "header-sub"
}, " \xB7 ", subtitle)), /*#__PURE__*/React.createElement("div", {
  className: "header-spacer"
}), /*#__PURE__*/React.createElement("div", {
  style: {
    display: "flex",
    alignItems: "center",
    gap: 6
  }
}, /*#__PURE__*/React.createElement("button", {
  className: "icon-btn",
  title: "Search"
}, /*#__PURE__*/React.createElement(Icon, {
  name: "search",
  size: 17
})), /*#__PURE__*/React.createElement("button", {
  className: "icon-btn",
  title: "Notifications",
  style: {
    position: "relative"
  }
}, /*#__PURE__*/React.createElement(Icon, {
  name: "bell",
  size: 17
}), /*#__PURE__*/React.createElement("span", {
  style: {
    position: "absolute",
    top: 6,
    right: 6,
    width: 7,
    height: 7,
    borderRadius: "50%",
    background: "var(--error-wMain)",
    border: "2px solid #fff"
  }
})), /*#__PURE__*/React.createElement("button", {
  className: "icon-btn",
  title: "Help"
}, /*#__PURE__*/React.createElement(Icon, {
  name: "help",
  size: 17
})), actions));
window.Header = Header;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/webui/Header.jsx", error: String((e && e.message) || e) }); }

// ui_kits/webui/Icon.jsx
try { (() => {
// Small inline SVG icon system for the UI kit. Lucide-flavored, stroke 1.75.
const Icon = ({
  name,
  size = 16,
  className = "",
  style = {}
}) => {
  const common = {
    width: size,
    height: size,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 1.75,
    strokeLinecap: "round",
    strokeLinejoin: "round",
    className,
    style
  };
  const paths = {
    chat: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "M21 12a8 8 0 0 1-11.6 7.1L4 21l1.9-5.4A8 8 0 1 1 21 12Z"
    })),
    mesh: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("circle", {
      cx: "5",
      cy: "5",
      r: "2"
    }), /*#__PURE__*/React.createElement("circle", {
      cx: "19",
      cy: "5",
      r: "2"
    }), /*#__PURE__*/React.createElement("circle", {
      cx: "12",
      cy: "12",
      r: "2"
    }), /*#__PURE__*/React.createElement("circle", {
      cx: "5",
      cy: "19",
      r: "2"
    }), /*#__PURE__*/React.createElement("circle", {
      cx: "19",
      cy: "19",
      r: "2"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M7 6 10.5 10.5M17 6 13.5 10.5M7 18l3.5-3.5M17 18l-3.5-3.5"
    })),
    flow: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("rect", {
      x: "3",
      y: "3",
      width: "7",
      height: "7",
      rx: "1.5"
    }), /*#__PURE__*/React.createElement("rect", {
      x: "14",
      y: "3",
      width: "7",
      height: "7",
      rx: "1.5"
    }), /*#__PURE__*/React.createElement("rect", {
      x: "3",
      y: "14",
      width: "7",
      height: "7",
      rx: "1.5"
    }), /*#__PURE__*/React.createElement("rect", {
      x: "14",
      y: "14",
      width: "7",
      height: "7",
      rx: "1.5"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M10 6.5h4M10 17.5h4M6.5 10v4M17.5 10v4"
    })),
    logs: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M14 3v5h5M9 13h6M9 17h6M9 9h2"
    })),
    settings: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("circle", {
      cx: "12",
      cy: "12",
      r: "3"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3h.1a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9v.1a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"
    })),
    plus: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "M12 5v14M5 12h14"
    })),
    search: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("circle", {
      cx: "11",
      cy: "11",
      r: "7"
    }), /*#__PURE__*/React.createElement("path", {
      d: "m21 21-4.3-4.3"
    })),
    bell: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0"
    })),
    user: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("circle", {
      cx: "12",
      cy: "8",
      r: "4"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M4 21a8 8 0 0 1 16 0"
    })),
    send: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "m22 2-7 20-4-9-9-4Z"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M22 2 11 13"
    })),
    attach: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "m21.4 11.1-9.2 9.2a5 5 0 0 1-7.1-7.1L14.3 4A3.3 3.3 0 1 1 19 8.7l-9.2 9.2a1.7 1.7 0 1 1-2.4-2.3L16 7"
    })),
    mic: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("rect", {
      x: "9",
      y: "2",
      width: "6",
      height: "12",
      rx: "3"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M19 10a7 7 0 0 1-14 0M12 19v3"
    })),
    close: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "M18 6 6 18M6 6l12 12"
    })),
    chevron: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "m9 6 6 6-6 6"
    })),
    more: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("circle", {
      cx: "12",
      cy: "5",
      r: "1"
    }), /*#__PURE__*/React.createElement("circle", {
      cx: "12",
      cy: "12",
      r: "1"
    }), /*#__PURE__*/React.createElement("circle", {
      cx: "12",
      cy: "19",
      r: "1"
    })),
    play: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "m6 3 14 9-14 9Z"
    })),
    pause: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("rect", {
      x: "6",
      y: "4",
      width: "4",
      height: "16",
      rx: "1"
    }), /*#__PURE__*/React.createElement("rect", {
      x: "14",
      y: "4",
      width: "4",
      height: "16",
      rx: "1"
    })),
    check: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "m5 12 5 5 10-11"
    })),
    copy: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("rect", {
      x: "9",
      y: "9",
      width: "12",
      height: "12",
      rx: "2"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"
    })),
    refresh: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "M3 12a9 9 0 0 1 15-6.7L21 8"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M21 3v5h-5M21 12a9 9 0 0 1-15 6.7L3 16"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M3 21v-5h5"
    })),
    bolt: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "M13 2 4 14h7l-1 8 9-12h-7Z"
    })),
    doc: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M14 3v5h5"
    })),
    help: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("circle", {
      cx: "12",
      cy: "12",
      r: "9"
    }), /*#__PURE__*/React.createElement("path", {
      d: "M9.1 9a3 3 0 1 1 4.9 2.3c-.9.7-1.5 1.3-1.5 2.2M12 17h.01"
    })),
    sparkle: /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("path", {
      d: "m12 3 2.2 5.8L20 11l-5.8 2.2L12 19l-2.2-5.8L4 11l5.8-2.2Z"
    }))
  };
  return /*#__PURE__*/React.createElement("svg", common, paths[name] || paths.help);
};
window.Icon = Icon;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/webui/Icon.jsx", error: String((e && e.message) || e) }); }

// ui_kits/webui/SideSheet.jsx
try { (() => {
const SideSheet = ({
  agent,
  onClose
}) => {
  if (!agent) return null;
  return /*#__PURE__*/React.createElement(React.Fragment, null, /*#__PURE__*/React.createElement("div", {
    className: "sheet-scrim",
    onClick: onClose
  }), /*#__PURE__*/React.createElement("aside", {
    className: "sheet"
  }, /*#__PURE__*/React.createElement("div", {
    className: "sheet-head"
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      alignItems: "center",
      gap: 10,
      flex: 1,
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    className: "ac-icon",
    style: {
      background: agent.colors[1],
      color: agent.colors[0],
      width: 36,
      height: 36
    }
  }, agent.initials), /*#__PURE__*/React.createElement("div", {
    style: {
      minWidth: 0
    }
  }, /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 15,
      fontWeight: 600
    }
  }, agent.name), /*#__PURE__*/React.createElement("div", {
    style: {
      fontSize: 12,
      color: "var(--secondary-text-wMain)",
      fontFamily: "var(--font-mono)"
    }
  }, agent.version, " \xB7 ", agent.runtime))), /*#__PURE__*/React.createElement("button", {
    className: "icon-btn",
    onClick: onClose
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "close",
    size: 16
  }))), /*#__PURE__*/React.createElement("div", {
    className: "sheet-tabs"
  }, /*#__PURE__*/React.createElement("div", {
    className: "stab active"
  }, "Overview"), /*#__PURE__*/React.createElement("div", {
    className: "stab"
  }, "Invocations"), /*#__PURE__*/React.createElement("div", {
    className: "stab"
  }, "Config"), /*#__PURE__*/React.createElement("div", {
    className: "stab"
  }, "Logs")), /*#__PURE__*/React.createElement("div", {
    className: "sheet-body"
  }, /*#__PURE__*/React.createElement("p", {
    style: {
      margin: "0 0 18px",
      color: "var(--primary-text-wMain)",
      fontSize: 13,
      lineHeight: 1.55
    }
  }, agent.desc), /*#__PURE__*/React.createElement("div", {
    className: "kv"
  }, /*#__PURE__*/React.createElement("div", {
    className: "kv-label"
  }, "Status"), /*#__PURE__*/React.createElement("div", null, /*#__PURE__*/React.createElement("span", {
    className: `badge ${statusClass[agent.status]}`
  }, /*#__PURE__*/React.createElement("span", {
    className: "d"
  }), agent.statusLabel)), /*#__PURE__*/React.createElement("div", {
    className: "kv-label"
  }, "Broker"), /*#__PURE__*/React.createElement("div", {
    className: "kv-mono"
  }, "solace-prod.us-east"), /*#__PURE__*/React.createElement("div", {
    className: "kv-label"
  }, "Subscribes"), /*#__PURE__*/React.createElement("div", {
    className: "kv-mono"
  }, "solace/events/orders/>"), /*#__PURE__*/React.createElement("div", {
    className: "kv-label"
  }, "Publishes"), /*#__PURE__*/React.createElement("div", {
    className: "kv-mono"
  }, "solace/agent/", agent.name, "/response"), /*#__PURE__*/React.createElement("div", {
    className: "kv-label"
  }, "Owner"), /*#__PURE__*/React.createElement("div", null, "Payments squad")), /*#__PURE__*/React.createElement("h4", {
    className: "sheet-section"
  }, "Recent invocations"), /*#__PURE__*/React.createElement("div", {
    className: "inv-list"
  }, [{
    t: "3:14 PM",
    q: "Summarize yesterday's order failures…",
    d: "410ms",
    ok: true
  }, {
    t: "2:52 PM",
    q: "Why is payment success dropping?",
    d: "388ms",
    ok: true
  }, {
    t: "2:41 PM",
    q: "Run weekly report",
    d: "2.4s",
    ok: true
  }, {
    t: "1:09 PM",
    q: "Analyze SKU AL-2201 failures",
    d: "441ms",
    ok: true
  }].map((r, i) => /*#__PURE__*/React.createElement("div", {
    key: i,
    className: "inv-row"
  }, /*#__PURE__*/React.createElement("span", {
    className: "inv-t"
  }, r.t), /*#__PURE__*/React.createElement("span", {
    className: "inv-q"
  }, r.q), /*#__PURE__*/React.createElement("span", {
    className: "inv-d"
  }, r.d), /*#__PURE__*/React.createElement("span", {
    className: "badge badge-ok"
  }, /*#__PURE__*/React.createElement("span", {
    className: "d"
  }), "OK"))))), /*#__PURE__*/React.createElement("div", {
    className: "sheet-foot"
  }, /*#__PURE__*/React.createElement("button", {
    className: "btn btn-secondary btn-sm"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "logs",
    size: 13
  }), "Open logs"), /*#__PURE__*/React.createElement("span", {
    style: {
      flex: 1
    }
  }), /*#__PURE__*/React.createElement("button", {
    className: "btn btn-ghost btn-sm"
  }, "Pause"), /*#__PURE__*/React.createElement("button", {
    className: "btn btn-primary btn-sm"
  }, /*#__PURE__*/React.createElement(Icon, {
    name: "bolt",
    size: 13
  }), "Invoke"))));
};
window.SideSheet = SideSheet;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/webui/SideSheet.jsx", error: String((e && e.message) || e) }); }

// ui_kits/webui/Sidebar.jsx
try { (() => {
const Sidebar = ({
  active,
  setActive
}) => {
  const groups = [{
    label: "Workspace",
    items: [{
      id: "chat",
      icon: "chat",
      label: "Chat",
      count: "3"
    }, {
      id: "mesh",
      icon: "mesh",
      label: "Agent mesh",
      count: "12"
    }, {
      id: "flows",
      icon: "flow",
      label: "Flows",
      count: "8"
    }]
  }, {
    label: "Observe",
    items: [{
      id: "logs",
      icon: "logs",
      label: "Session logs"
    }, {
      id: "metrics",
      icon: "bolt",
      label: "Metrics"
    }]
  }, {
    label: "Configure",
    items: [{
      id: "settings",
      icon: "settings",
      label: "Settings"
    }, {
      id: "docs",
      icon: "doc",
      label: "Documentation"
    }]
  }];
  return /*#__PURE__*/React.createElement("aside", {
    className: "sidebar"
  }, /*#__PURE__*/React.createElement("div", {
    className: "sidebar-logo"
  }, /*#__PURE__*/React.createElement("svg", {
    viewBox: "0 0 32 32",
    width: "24",
    height: "24"
  }, /*#__PURE__*/React.createElement("circle", {
    cx: "16",
    cy: "16",
    r: "14",
    fill: "#00C895"
  }), /*#__PURE__*/React.createElement("path", {
    d: "M10 20c2 2 10 2 12 0M10 12c2-2 10-2 12 0",
    stroke: "#03223B",
    strokeWidth: "2",
    fill: "none",
    strokeLinecap: "round"
  })), /*#__PURE__*/React.createElement("div", {
    className: "sidebar-logo-text"
  }, "Agent Mesh ", /*#__PURE__*/React.createElement("em", null, "/ prod"))), /*#__PURE__*/React.createElement("div", {
    className: "sidebar-groups"
  }, groups.map(g => /*#__PURE__*/React.createElement("div", {
    key: g.label,
    className: "sidebar-group"
  }, /*#__PURE__*/React.createElement("div", {
    className: "sidebar-group-label"
  }, g.label), g.items.map(it => /*#__PURE__*/React.createElement("div", {
    key: it.id,
    className: `sidebar-item ${active === it.id ? "active" : ""}`,
    onClick: () => setActive(it.id)
  }, /*#__PURE__*/React.createElement(Icon, {
    name: it.icon,
    size: 16
  }), /*#__PURE__*/React.createElement("span", null, it.label), it.count && /*#__PURE__*/React.createElement("span", {
    className: "count"
  }, it.count)))))), /*#__PURE__*/React.createElement("div", {
    className: "sidebar-foot"
  }, /*#__PURE__*/React.createElement("div", {
    className: "avatar-sm"
  }, "DR"), /*#__PURE__*/React.createElement("div", {
    style: {
      display: "flex",
      flexDirection: "column",
      lineHeight: 1.25
    }
  }, /*#__PURE__*/React.createElement("span", {
    style: {
      color: "#fff"
    }
  }, "Devon Ramirez"), /*#__PURE__*/React.createElement("span", {
    style: {
      color: "var(--darkSurface-textMuted)",
      fontSize: 11
    }
  }, "Platform admin")), /*#__PURE__*/React.createElement(Icon, {
    name: "settings",
    size: 14,
    style: {
      marginLeft: "auto",
      color: "var(--darkSurface-textMuted)"
    }
  })));
};
window.Sidebar = Sidebar;
})(); } catch (e) { __ds_ns.__errors.push({ path: "ui_kits/webui/Sidebar.jsx", error: String((e && e.message) || e) }); }

})();
