package com.solace.sm.cp.catalog.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cross-referencing gates the schema cannot express — 4, 5, 5b, 9, 10, 12, 13, 14, 15, 16 — ported from
 * {@code validate-package.py:semantic_gates} with the SAME ORDER of checks, because the Python prints only
 * {@code errs[0]} under {@code --expect-fail} and a reordering would change which gate a package is refused as.
 * Gate 2 (provenance) and gate 3 (overlap) are {@link SignatureGate} and {@link OverlapGate}.
 *
 * <p>Runs only on a package that passed gate 1 (the caller guarantees it, as the Python's {@code validate}
 * does): it assumes the schema's shapes. Messages name components, field keys and version ids — never a
 * {@code default}, a predicate value or an option ({@code I6}).
 */
public final class SemanticGates {

    private SemanticGates() {
    }

    public static List<GateFailure> check(JsonNode pkg) {
        List<GateFailure> errs = new ArrayList<>();
        List<JsonNode> packageVersions = list(pkg.get("packageVersion"));

        for (JsonNode comp : list(pkg.get("components"))) {
            String ct = comp.get("type").asText();
            List<JsonNode> fields = list(comp.get("fields"));
            Map<String, JsonNode> byKey = new LinkedHashMap<>();
            for (JsonNode f : fields) {
                byKey.put(f.get("key").asText(), f);
            }
            List<JsonNode> componentVersions = effectiveVersions(comp.get("version"), packageVersions);

            // gate 14 - every declaration version must be selectable; a field cannot outlive its component.
            for (JsonNode version : list(comp.get("version"))) {
                if (!Py.in(version, packageVersions)) {
                    errs.add(g("14", ct + ": component version " + Py.version(version) + " is not declared by packageVersion"));
                }
            }
            for (JsonNode field : fields) {
                String key = field.get("key").asText();
                for (JsonNode version : list(field.get("version"))) {
                    if (!Py.in(version, packageVersions)) {
                        errs.add(g("14", ct + "/" + key + ": field version " + Py.version(version) + " is not declared by packageVersion"));
                    } else if (!Py.in(version, componentVersions)) {
                        errs.add(g("14", ct + "/" + key + ": field version " + Py.version(version) + " is outside its component versions"));
                    }
                }
            }

            // gate 9 - uniqueness within the component
            checkUnique(errs, ct, "field", fields, "key");
            checkUnique(errs, ct, "metric", list(comp.get("metrics")), "name");
            checkUnique(errs, ct, "action", list(comp.get("actions")), "key");

            for (JsonNode f : fields) {
                checkPredicate(errs, ct, byKey, componentVersions, f.get("key").asText(), f.get("when"),
                        effectiveVersions(f.get("version"), componentVersions));
            }
            for (JsonNode a : list(comp.get("actions"))) {
                String owner = a.get("key").asText();
                checkPredicate(errs, ct, byKey, componentVersions, owner, a.get("when"), componentVersions);
                for (JsonNode inp : list(a.get("inputs"))) {
                    JsonNode target = byKey.get(inp.asText());
                    if (target == null) {
                        errs.add(g("4", ct + "/" + owner + ": action input " + quote(inp.asText()) + " is not a field"));
                        continue;
                    }
                    List<JsonNode> inputVersions = effectiveVersions(target.get("version"), componentVersions);
                    List<JsonNode> missing = componentVersions.stream().filter(v -> !Py.in(v, inputVersions)).toList();
                    if (!missing.isEmpty()) {
                        errs.add(g("14", ct + "/" + owner + ": action input " + quote(inp.asText())
                                + " is unavailable in versions " + Py.versions(missing)));
                    }
                }
            }
            for (JsonNode m : list(comp.get("metrics"))) {
                checkPredicate(errs, ct, byKey, componentVersions, m.get("name").asText(), m.get("when"), componentVersions);
            }

            // gate 12 - a discriminator that any predicate keys on must be reachable: it declares a default, or
            // the operator can set it. Otherwise every dependent field is hidden on a fresh draft.
            Set<String> keyed = new HashSet<>();
            for (List<JsonNode> group : List.of(fields, list(comp.get("actions")), list(comp.get("metrics")))) {
                for (JsonNode x : group) {
                    JsonNode when = x.get("when");
                    if (Py.truthy(when)) {
                        when.fieldNames().forEachRemaining(keyed::add);
                    }
                }
            }
            for (JsonNode f : fields) {
                String key = f.get("key").asText();
                if (!keyed.contains(key)) {
                    continue;
                }
                boolean hasDefault = f.has("default") && !f.get("default").isNull();
                JsonNode ui = f.get("ui");
                boolean settable = !(Py.truthy(ui) && Py.truthy(ui.get("readOnly")));
                if (!hasDefault && !settable) {
                    errs.add(g("12", ct + ": " + quote(key) + " is keyed on by a predicate but has no default and is "
                            + "readOnly, so every dependent field is hidden forever"));
                }
            }

            // gate 13 - secrecy is declared one way
            for (JsonNode f : fields) {
                JsonNode secret = f.get("secret");
                if ("secret".equals(Py.text(f.get("type"))) && secret != null && secret.isBoolean() && !secret.booleanValue()) {
                    errs.add(g("13", ct + ": " + quote(f.get("key").asText()) + " declares type 'secret' with secret:false "
                            + "- one guarantee, two contradictory spellings"));
                }
            }

            // gate 10 - a yaml subtree must not collide with a declared key
            for (JsonNode f : fields) {
                if ("yaml".equals(Py.text(f.get("type")))) {
                    String own = f.get("key").asText();
                    String sub = f.has("yamlSubtree") ? f.get("yamlSubtree").asText() : "";
                    for (String k : byKey.keySet()) {
                        if (!k.equals(own) && (k.equals(sub) || k.startsWith(sub + "."))) {
                            errs.add(g("10", ct + ": yaml field " + quote(own) + " subtree " + quote(sub)
                                    + " collides with field " + quote(k)));
                        }
                    }
                }
            }

            // gate 16 - a solace_event metric's instanceKey names a field of THIS component that is not a secret (plan
            // 011 P11-5.1; I6): its configured value is matched against what a broker event carries, and a secret is
            // never read for that. A key naming no field could never match, so it is refused here. Names the component,
            // the metric and the field key - never a value.
            for (JsonNode m : list(comp.get("metrics"))) {
                JsonNode source = m.get("source");
                if (source == null || !"solace_event".equals(Py.text(source.get("kind")))) {
                    continue;
                }
                JsonNode instanceKey = source.get("instanceKey");
                String ref = instanceKey == null ? null : Py.text(instanceKey.get("configField"));
                JsonNode target = ref == null ? null : byKey.get(ref);
                String metric = m.get("name").asText();
                if (target == null) {
                    errs.add(g("16", ct + "/" + metric + ": instanceKey.configField " + quote(String.valueOf(ref))
                            + " is not a field of this component"));
                } else if ("secret".equals(Py.text(target.get("type")))
                        || (target.get("secret") != null && target.get("secret").isBoolean()
                        && target.get("secret").booleanValue())) {
                    errs.add(g("16", ct + "/" + metric + ": instanceKey.configField " + quote(ref)
                            + " is a secret field, which is never read for attribution"));
                }
            }
        }

        // gate 15 - a metric name is unique across ALL of a package's components (plan 011 M-Q5: the key a metric is
        // stored under has no component in it). Gate 9 covers a repeat WITHIN one component, so only a name an
        // EARLIER component declared is reported here. Names the component and the metric, never a value.
        Map<String, Integer> declaredByIndex = new LinkedHashMap<>();
        Map<String, String> declaredByType = new LinkedHashMap<>();
        List<JsonNode> components = list(pkg.get("components"));
        for (int idx = 0; idx < components.size(); idx++) {
            JsonNode comp = components.get(idx);
            String ct = comp.get("type").asText();
            Set<String> own = new LinkedHashSet<>();
            for (JsonNode m : list(comp.get("metrics"))) {
                String name = m.get("name").asText();
                Integer first = declaredByIndex.get(name);
                if (first != null && first != idx && !own.contains(name)) {
                    errs.add(g("15", ct + ": metric " + quote(name) + " is already declared by component "
                            + quote(declaredByType.get(name))));
                }
                own.add(name);
            }
            for (String name : own) {
                declaredByIndex.putIfAbsent(name, idx);
                declaredByType.putIfAbsent(name, ct);
            }
        }
        return errs;
    }

    private static void checkUnique(List<GateFailure> errs, String ct, String label, List<JsonNode> items, String keyName) {
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            String k = item.get(keyName).asText();
            if (!seen.add(k)) {
                errs.add(g("9", ct + ": duplicate " + label + " key " + quote(k)));
            }
        }
    }

    /** {@code check_pred}: gates 4, 14 (availability), 5b and 5 for one owner's {@code when}. */
    private static void checkPredicate(List<GateFailure> errs, String ct, Map<String, JsonNode> byKey,
            List<JsonNode> componentVersions, String owner, JsonNode when, List<JsonNode> ownerVersions) {
        if (!Py.truthy(when)) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = when.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String k = entry.getKey();
            JsonNode v = entry.getValue();

            // gate 4 - the key names a field in the same component
            JsonNode tgt = byKey.get(k);
            if (tgt == null) {
                errs.add(g("4", ct + "/" + owner + ": when references unknown field " + quote(k)));
                continue;
            }
            List<JsonNode> targetVersions = effectiveVersions(tgt.get("version"), componentVersions);
            List<JsonNode> missing = ownerVersions.stream().filter(x -> !Py.in(x, targetVersions)).toList();
            if (!missing.isEmpty()) {
                errs.add(g("14", ct + "/" + owner + ": when field " + quote(k) + " is unavailable in versions " + Py.versions(missing)));
            }
            // gate 5b - the value must be a member / type-compatible
            if (tgt.has("options")) {
                if (!Py.in(v, tgt.get("options"))) {
                    errs.add(g("5b", ct + "/" + owner + ": when " + k + " value is not in its options"));
                }
            } else if (tgt.has("optionsSource")) {
                // not statically checkable; noted in design section 6.4
            } else if ("bool".equals(Py.text(tgt.get("type"))) && !v.isBoolean()) {
                errs.add(g("5b", ct + "/" + owner + ": when " + k + " value is not a bool"));
            } else if ("int".equals(Py.text(tgt.get("type"))) && !Py.isInt(v)) {
                errs.add(g("5b", ct + "/" + owner + ": when " + k + " value is not an int"));
            }
            // gate 5 - reachability: this predicate must ENTAIL the target's own predicate
            JsonNode targetWhen = tgt.get("when");
            if (Py.truthy(targetWhen)) {
                Iterator<Map.Entry<String, JsonNode>> ancestors = targetWhen.fields();
                while (ancestors.hasNext()) {
                    Map.Entry<String, JsonNode> a = ancestors.next();
                    JsonNode mine = when.get(a.getKey());
                    if (mine == null) {
                        errs.add(g("5", ct + "/" + owner + ": when omits ancestor " + quote(a.getKey()) + " required by " + quote(k)));
                    } else if (!Py.eq(mine, a.getValue())) {
                        errs.add(g("5", ct + "/" + owner + ": unreachable - " + a.getKey() + " contradicts "
                                + quote(k) + "'s own predicate on it"));
                    }
                }
            }
        }
    }

    /** {@code effective_versions}: no declaration inherits; a declaration is narrowed to what the parent has. */
    private static List<JsonNode> effectiveVersions(JsonNode declared, List<JsonNode> inherited) {
        if (declared == null || declared.isNull()) {
            return inherited;
        }
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode v : declared) {
            if (Py.in(v, inherited)) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    private static GateFailure g(String gate, String message) {
        return new GateFailure(gate, message);
    }

    private static String quote(String s) {
        return "'" + s + "'";
    }
}
