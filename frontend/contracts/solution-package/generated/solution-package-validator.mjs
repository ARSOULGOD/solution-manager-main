/* This file is generated. Do not edit it directly. */
/* source: contracts/solution-package.schema.json · sha256:6360547d4b4f09751502de767ac8c237dafe7120912e9115a82cfef3c6f1b383 */
"use strict";
export const validate = validate20;
export default validate20;
const schema31 = {"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://solace.com/solution-manager/solution-package.schema.json","title":"Solution Package","description":"The contract by which a solution declares its components, fields, actions, metrics, health and logs. Canonical home of every closed vocabulary (UI-D14). See docs/design/dynamic-ui/design.md.","type":"object","required":["solution","packageVersion","appliesTo","components"],"additionalProperties":false,"properties":{"solution":{"type":"string","pattern":"^[a-z][a-z0-9-]*$"},"packageVersion":{"type":"array","description":"Ordered, non-empty package schema versions served by this one document.","minItems":1,"uniqueItems":true,"items":{"$ref":"#/$defs/packageVersionId"}},"appliesTo":{"type":"string","description":"semver range of observed MI versions this package serves (UI-D11)"},"provenance":{"type":"object","additionalProperties":false,"properties":{"author":{"enum":["solace","dev-mode"]},"signature":{"type":"string"}},"required":["author"]},"authoring":{"type":"object","additionalProperties":false,"description":"UI-D17 — makes an unreviewed extraction visible in the registry","properties":{"reviewedRows":{"type":"integer","minimum":0},"totalRows":{"type":"integer","minimum":0},"source":{"type":"string"}}},"components":{"type":"array","minItems":1,"items":{"$ref":"#/$defs/component"}}},"$defs":{"packageVersionId":{"oneOf":[{"type":"integer","minimum":1},{"type":"string","minLength":1}]},"predicate":{"description":"UI-D2 — an object of field:literal, implicitly AND-ed. No OR, no comparison, no negation. Evaluated over defaults-applied values.","type":"object","minProperties":1,"additionalProperties":{"type":["string","number","boolean"]}},"component":{"type":"object","required":["type","cardinality","configPath"],"additionalProperties":false,"properties":{"type":{"type":"string"},"version":{"type":"array","description":"Package versions containing this component. Omitted means every declared package version.","minItems":1,"uniqueItems":true,"items":{"$ref":"#/$defs/packageVersionId"}},"cardinality":{"enum":["exactly-one","one-of-many","zero-or-more"]},"role":{"type":["string","null"],"minLength":1,"description":"Optional package-defined component role. The legacy value 'none' remains accepted as an explicit shared-component marker."},"configPath":{"type":"string","description":"property prefix SM writes under"},"configFile":{"type":"string"},"loggerPrefix":{"type":"string"},"fields":{"type":"array","items":{"$ref":"#/$defs/field"}},"actions":{"type":"array","items":{"$ref":"#/$defs/action"}},"metrics":{"type":"array","items":{"$ref":"#/$defs/metric"}},"restartSignal":{"$ref":"#/$defs/restartSignal"},"health":{"$ref":"#/$defs/health"},"logs":{"$ref":"#/$defs/logs"}},"if":{"required":["logs"],"properties":{"logs":{"type":"object","required":["loggers"]}}},"then":{"required":["loggerPrefix"]}},"field":{"type":"object","required":["key","type"],"additionalProperties":false,"properties":{"key":{"type":"string","description":"the config property path; NOT a display label (see the File Mesh adapter note)"},"type":{"description":"UI-D14 closed vocabulary","enum":["string","int","bool","enum","duration","list","map","table","yaml","secret"]},"role":{"type":["string","null"],"minLength":1,"description":"Optional package-defined field role. Omitted or 'none' means shared."},"version":{"type":"array","description":"Package versions containing this field. Omitted means every version containing its component.","minItems":1,"uniqueItems":true,"items":{"$ref":"#/$defs/packageVersionId"}},"virtual":{"type":"boolean","description":"UI-D24 - a declared field that drives the form and predicates but is NEVER written to the config file and is outside T-KEYS. Every File Mesh discriminator is one."},"options":{"type":"array","items":{"type":["string","number","boolean"]},"minItems":1},"optionsSource":{"$ref":"#/$defs/optionsSource"},"valueFrom":{"$ref":"#/$defs/valueFrom"},"columns":{"type":"array","items":{"$ref":"#/$defs/column"},"description":"type: table only"},"default":{"type":["string","number","boolean","null"]},"required":{"type":"boolean"},"secret":{"type":"boolean"},"discriminator":{"type":"boolean"},"when":{"$ref":"#/$defs/predicate"},"min":{"type":"number"},"max":{"type":"number"},"pattern":{"type":"string"},"yamlSubtree":{"type":"string","description":"UI-D23 — the isolated subtree a yaml field renders into"},"ui":{"$ref":"#/$defs/ui"},"provenance":{"$ref":"#/$defs/provenanceTiers"}},"allOf":[{"description":"gate 11 — options XOR optionsSource","not":{"required":["options","optionsSource"]}},{"description":"an enum must say where its options come from","if":{"properties":{"type":{"const":"enum"}},"required":["type"]},"then":{"anyOf":[{"required":["options"]},{"required":["optionsSource"]}]}},{"description":"gate 6 - a discriminator must have an ENUMERABLE domain: enum or bool. Free text cannot drive a subtree. (Corrected by the Cache fixture: solace.enabled is a boolean discriminator, as is File Mesh's CONFIG_JAVA_RUNTIME.)","if":{"properties":{"discriminator":{"const":true}},"required":["discriminator"]},"then":{"properties":{"type":{"enum":["enum","bool"]}}}},{"description":"a table declares its columns","if":{"properties":{"type":{"const":"table"}},"required":["type"]},"then":{"required":["columns"]}},{"description":"UI-D23 — a yaml field must name its isolated subtree","if":{"properties":{"type":{"const":"yaml"}},"required":["type"]},"then":{"required":["yamlSubtree"]}}]},"column":{"type":"object","required":["key","type"],"additionalProperties":false,"properties":{"key":{"type":"string"},"type":{"enum":["string","int","bool","enum","duration","secret"]},"options":{"type":"array","items":{"type":["string","number","boolean"]}},"label":{"type":"string"}}},"optionsSource":{"type":"object","required":["kind","entity"],"additionalProperties":false,"description":"UI-D22, amended by UI-D27 — a package names an SM-owned entity. It can never supply a URL.","properties":{"kind":{"enum":["sm-catalog"]},"entity":{"enum":["connection","environment","instance","metric"]},"connectionType":{"enum":["solace-broker","remote-server"]}},"allOf":[{"description":"UI-D27 — a connection selector must say which connection type it wants","if":{"properties":{"entity":{"const":"connection"}},"required":["entity"]},"then":{"required":["connectionType"]}}]},"valueFrom":{"type":"object","required":["ref","attribute"],"additionalProperties":false,"description":"UI-D28 — this field takes its value from the connection chosen in `ref`. Same component only.","properties":{"ref":{"type":"string","description":"field.key of a connection selector in the SAME component"},"attribute":{"type":"string","description":"attribute key in that selector's connectionType descriptor"}}},"action":{"type":"object","required":["key","kind"],"additionalProperties":false,"description":"UI-D22 — actions hold no value and appear in no config file.","properties":{"key":{"type":"string"},"kind":{"enum":["test-connection","manage-catalog-entry"]},"entity":{"enum":["connection","environment","instance","metric"]},"inputs":{"type":"array","items":{"type":"string"}},"when":{"$ref":"#/$defs/predicate"},"ui":{"$ref":"#/$defs/ui"}},"allOf":[{"description":"catalog management must say which entity it manages","if":{"properties":{"kind":{"const":"manage-catalog-entry"}},"required":["kind"]},"then":{"required":["entity"]}},{"description":"a connection test must name the inputs it tests","if":{"properties":{"kind":{"const":"test-connection"}},"required":["kind"]},"then":{"required":["inputs"]}}]},"ui":{"type":"object","additionalProperties":false,"properties":{"label":{"type":"string"},"section":{"type":"string"},"subSection":{"type":"string"},"order":{"type":"integer"},"advanced":{"type":"boolean"},"helperText":{"type":"string"},"readOnly":{"type":"boolean"},"listColumn":{"type":"boolean","description":"UI-D27 — show this attribute as a column in the catalog list. Descriptors only."},"promoteToDeclare":{"type":"boolean","description":"UI-D27 — the Declare Instance dialog surfaces this field early."}}},"provenanceTiers":{"type":"object","additionalProperties":{"enum":["derived","suggested","authored"]},"description":"UI-D17 — provenance is a property of the column, carried per field for audit"},"metric":{"type":"object","required":["name","metricType","unit","source"],"additionalProperties":false,"properties":{"name":{"type":"string","pattern":"^[a-z][a-z0-9_.]{0,127}(?![\\s\\S])","description":"P11-1.1 - the same grammar as sm_metrics' metric_name CHECK (plan 011 P11-2.1/P11-2.2), so a package cannot load and then fail at declare."},"metricType":{"enum":["counter","gauge","histogram"]},"unit":{"type":"string","minLength":1,"maxLength":64,"description":"P11-1.1 - 1..64 characters, the sm_metrics unit CHECK."},"layer":{"enum":["package-baseline","user-extension"]},"when":{"$ref":"#/$defs/predicate"},"source":{"$ref":"#/$defs/source"},"defaultChart":{"type":"boolean","description":"OQ-7 (b): false keeps this metric out of the derived default dashboard layer; absent means true. The metric stays queryable."}},"allOf":[{"description":"P11-5.1 (OR-106): a count of matching events is a cumulative number the Data Plane synthesises, so the metric is a counter. A gauge or a histogram of it is refused at load.","if":{"required":["source"],"properties":{"source":{"type":"object","required":["extract"],"properties":{"extract":{"const":"count"}}}}},"then":{"properties":{"metricType":{"const":"counter"}}}}]},"source":{"type":"object","required":["kind","onUnavailable"],"description":"UI-D14 — transport mechanisms only, never a solution name. Canonical list lives here. P11-1.1 (M-Q4): a per-kind union, discriminated by `kind`: http_json is collected in v1 and has its own closed shape; prometheus, exec and jdbc_query are admitted but not collected (their branch is unchanged). P11-5.1 (OR-106, OR-107) splits solace_event out as the push path: broker-pushed events, attributed by a package-declared instanceKey, extracted as a number at a pointer or as a count of matching events.","oneOf":[{"$ref":"#/$defs/sourceHttpJson"},{"$ref":"#/$defs/sourceSolaceEvent"},{"$ref":"#/$defs/sourceUncollected"}]},"sourceHttpJson":{"type":"object","additionalProperties":false,"required":["kind","path","onUnavailable"],"properties":{"kind":{"const":"http_json"},"path":{"type":"string","pattern":"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"},"port":{"type":"integer","minimum":1,"maximum":65535,"description":"The CONTAINER port; the host side comes from the declare body's metricsPorts (M-Q14). Absent: the metric is not collectable (OQ-24 (b))."},"pointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])","description":"A JSON-pointer-shaped path into the response body; no `.` or `..` segment. Absent: the whole body is the number."},"queryParams":{"type":"object","maxProperties":8,"propertyNames":{"pattern":"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"},"additionalProperties":{"type":"string","pattern":"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"},"description":"M-Q4: a closed grammar for keys AND values; no encoding, so nothing is ever interpolated into a URL unescaped."},"intervalSeconds":{"type":"integer","minimum":10,"maximum":3600,"description":"OQ-9 (a); the Data Plane default is 30."},"onUnavailable":{"enum":["degrade","error"]}}},"sourceSolaceEvent":{"type":"object","additionalProperties":false,"required":["kind","topicPattern","instanceKey","onUnavailable"],"description":"P11-5.1 (OR-106, OR-107): the push path. The Data Plane holds ONE broker connection per (organization, environment) and one subscription per distinct topicPattern, never one per instance; each event it is delivered names its instance in the payload at instanceKey.pointer, matched exactly and case-sensitively against the instance's own value of the config field instanceKey.configField (gate 16: never a secret). Nothing is substituted into a topic. extract=value reads a finite number at valuePointer; extract=count counts the events that pass eventFilter (OR-106), a counter the Data Plane synthesises and treats as an inferred reset when it restarts. I1: it reads events the MI already emits.","properties":{"kind":{"const":"solace_event"},"topicPattern":{"type":"string","pattern":"^(([A-Za-z0-9_.-]{1,64}|\\*)/){0,15}[A-Za-z0-9_.-]{1,64}(?![\\s\\S])","description":"Levels joined by /, each a literal or * (exactly one level). I6: the LAST level is a literal - no >, no *, no substitution token - so a subscription names its states and an event of another state (a start event may carry a plaintext broker password) is never delivered to the Data Plane. No #, no +, no empty level."},"eventFilter":{"type":"object","additionalProperties":false,"required":["pointer","equals"],"properties":{"pointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])"},"equals":{"type":"string","pattern":"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"}},"description":"An event counts only when the value at pointer, read as text, equals this."},"extract":{"enum":["value","count"],"description":"Absent means value. value: valuePointer is required. count (OR-106): valuePointer is optional and ignored (the Data Plane counts the events that pass eventFilter), and the metric must be a counter."},"valuePointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])","description":"A JSON-pointer-shaped path to a finite number in the event body; no . or .. segment."},"instanceKey":{"type":"object","additionalProperties":false,"required":["configField","pointer"],"properties":{"configField":{"type":"string","pattern":"^[A-Za-z0-9_.-]{1,256}(?![\\s\\S])","description":"The key of a field of THIS component whose configured value identifies the instance in its events (gate 16: it must exist and may not be a secret)."},"pointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])","description":"Where the event body carries that value (File Mesh: /header/adapterID)."}}},"onUnavailable":{"enum":["degrade","error"]}},"if":{"required":["extract"],"properties":{"extract":{"const":"count"}}},"else":{"required":["valuePointer"]}},"sourceUncollected":{"type":"object","additionalProperties":false,"required":["kind","onUnavailable"],"description":"Admitted, not collected in v1. Keeps every property `$defs/source` carried before P11-1.1, unchanged: queryParams exists only on http_json.","properties":{"kind":{"enum":["prometheus","exec","jdbc_query"]},"path":{"type":"string"},"port":{"type":"integer"},"pointer":{"type":"string"},"topicPattern":{"type":"string"},"query":{"type":"string"},"command":{"type":"string"},"onUnavailable":{"enum":["degrade","error"]}}},"restartSignal":{"type":"object","additionalProperties":false,"required":["kind","path","port","onUnavailable"],"description":"OQ-1 (a), M-Q9: how the Data Plane detects a restart of this component in direct mode (an http_json read of a process start time). The same shape as an http_json source minus intervalSeconds and queryParams, and port is REQUIRED (W2-05): a signal with no port cannot be fetched in direct mode, yet its presence makes the Data Plane stop inferring resets, so every counter of the component would get no delta. SSH mode needs nothing from the package.","properties":{"kind":{"const":"http_json"},"path":{"type":"string","pattern":"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"},"port":{"type":"integer","minimum":1,"maximum":65535},"pointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])"},"onUnavailable":{"enum":["degrade","error"]}}},"health":{"type":"object","required":["probe"],"additionalProperties":false,"properties":{"probe":{"type":"object","required":["kind","strength"],"additionalProperties":false,"properties":{"kind":{"enum":["actuator-health","http-status","broker-heartbeat"]},"path":{"type":"string"},"port":{"type":"integer"},"strength":{"description":"UI-D14 closed vocabulary. Only the package can say whether its probe's UP means the process is *serving* (readiness) or only that it *came up* (liveness) — SM may not infer it (I2) and may not ask the MI to change it (I1). Owner ruling 2026-09-29 (S0-10). Required so no package claims readiness by omission.","enum":["readiness","liveness"]}}}}},"logs":{"type":"object","additionalProperties":false,"dependentRequired":{"loggers":["baselineExposure"]},"properties":{"logfile":{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string"}}},"baselineExposure":{"type":"array","items":{"type":"string"},"description":"UI-D10 — the union base; SM cannot read the effective value"},"requiresConfig":{"type":"array","items":{"type":"string"}},"loggers":{"type":"object","additionalProperties":false,"description":"OR-103 / BE-D38 — declares that this component's MI speaks the Spring Boot Actuator loggers endpoint and the logging.level property. Nothing is inferred from loggerPrefix (a non-Boot MI can carry a prefix); a package with no block gets no live log-level read and no overlay. The empty object declares support with both defaults. Requires the component to declare loggerPrefix and this block's logs.baselineExposure: SM writes the exposure list as the union of that baseline and its own required names, so a package that declares none would have its health endpoint withdrawn.","properties":{"path":{"type":"string","pattern":"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])","default":"/actuator/loggers","description":"The loggers endpoint's path on the application port. The grammar is the probe path's (SM-D11a P): it reaches a shell command, so nothing outside it is accepted."},"levelProperty":{"type":"string","pattern":"^[a-z][a-z0-9.-]{0,127}(?![\\s\\S])","default":"logging.level","description":"The property prefix under which a per-logger level is configured; the level of logger L is <levelProperty>.<L>."}}}}}}};
const schema32 = {"oneOf":[{"type":"integer","minimum":1},{"type":"string","minLength":1}]};
const pattern4 = new RegExp("^[a-z][a-z0-9-]*$", "u");
import func1Module from "ajv/dist/runtime/ucs2length";
const func1 = typeof func1Module === 'function' ? func1Module : func1Module.default;
import func0Module from "ajv/dist/runtime/equal";
const func0 = typeof func0Module === 'function' ? func0Module : func0Module.default;
const schema33 = {"type":"object","required":["type","cardinality","configPath"],"additionalProperties":false,"properties":{"type":{"type":"string"},"version":{"type":"array","description":"Package versions containing this component. Omitted means every declared package version.","minItems":1,"uniqueItems":true,"items":{"$ref":"#/$defs/packageVersionId"}},"cardinality":{"enum":["exactly-one","one-of-many","zero-or-more"]},"role":{"type":["string","null"],"minLength":1,"description":"Optional package-defined component role. The legacy value 'none' remains accepted as an explicit shared-component marker."},"configPath":{"type":"string","description":"property prefix SM writes under"},"configFile":{"type":"string"},"loggerPrefix":{"type":"string"},"fields":{"type":"array","items":{"$ref":"#/$defs/field"}},"actions":{"type":"array","items":{"$ref":"#/$defs/action"}},"metrics":{"type":"array","items":{"$ref":"#/$defs/metric"}},"restartSignal":{"$ref":"#/$defs/restartSignal"},"health":{"$ref":"#/$defs/health"},"logs":{"$ref":"#/$defs/logs"}},"if":{"required":["logs"],"properties":{"logs":{"type":"object","required":["loggers"]}}},"then":{"required":["loggerPrefix"]}};
const schema52 = {"type":"object","additionalProperties":false,"required":["kind","path","port","onUnavailable"],"description":"OQ-1 (a), M-Q9: how the Data Plane detects a restart of this component in direct mode (an http_json read of a process start time). The same shape as an http_json source minus intervalSeconds and queryParams, and port is REQUIRED (W2-05): a signal with no port cannot be fetched in direct mode, yet its presence makes the Data Plane stop inferring resets, so every counter of the component would get no delta. SSH mode needs nothing from the package.","properties":{"kind":{"const":"http_json"},"path":{"type":"string","pattern":"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"},"port":{"type":"integer","minimum":1,"maximum":65535},"pointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])"},"onUnavailable":{"enum":["degrade","error"]}}};
const schema53 = {"type":"object","required":["probe"],"additionalProperties":false,"properties":{"probe":{"type":"object","required":["kind","strength"],"additionalProperties":false,"properties":{"kind":{"enum":["actuator-health","http-status","broker-heartbeat"]},"path":{"type":"string"},"port":{"type":"integer"},"strength":{"description":"UI-D14 closed vocabulary. Only the package can say whether its probe's UP means the process is *serving* (readiness) or only that it *came up* (liveness) — SM may not infer it (I2) and may not ask the MI to change it (I1). Owner ruling 2026-09-29 (S0-10). Required so no package claims readiness by omission.","enum":["readiness","liveness"]}}}}};
const schema54 = {"type":"object","additionalProperties":false,"dependentRequired":{"loggers":["baselineExposure"]},"properties":{"logfile":{"type":"object","additionalProperties":false,"properties":{"path":{"type":"string"}}},"baselineExposure":{"type":"array","items":{"type":"string"},"description":"UI-D10 — the union base; SM cannot read the effective value"},"requiresConfig":{"type":"array","items":{"type":"string"}},"loggers":{"type":"object","additionalProperties":false,"description":"OR-103 / BE-D38 — declares that this component's MI speaks the Spring Boot Actuator loggers endpoint and the logging.level property. Nothing is inferred from loggerPrefix (a non-Boot MI can carry a prefix); a package with no block gets no live log-level read and no overlay. The empty object declares support with both defaults. Requires the component to declare loggerPrefix and this block's logs.baselineExposure: SM writes the exposure list as the union of that baseline and its own required names, so a package that declares none would have its health endpoint withdrawn.","properties":{"path":{"type":"string","pattern":"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])","default":"/actuator/loggers","description":"The loggers endpoint's path on the application port. The grammar is the probe path's (SM-D11a P): it reaches a shell command, so nothing outside it is accepted."},"levelProperty":{"type":"string","pattern":"^[a-z][a-z0-9.-]{0,127}(?![\\s\\S])","default":"logging.level","description":"The property prefix under which a per-logger level is configured; the level of logger L is <levelProperty>.<L>."}}}}};
const func3 = Object.prototype.hasOwnProperty;
const schema35 = {"type":"object","required":["key","type"],"additionalProperties":false,"properties":{"key":{"type":"string","description":"the config property path; NOT a display label (see the File Mesh adapter note)"},"type":{"description":"UI-D14 closed vocabulary","enum":["string","int","bool","enum","duration","list","map","table","yaml","secret"]},"role":{"type":["string","null"],"minLength":1,"description":"Optional package-defined field role. Omitted or 'none' means shared."},"version":{"type":"array","description":"Package versions containing this field. Omitted means every version containing its component.","minItems":1,"uniqueItems":true,"items":{"$ref":"#/$defs/packageVersionId"}},"virtual":{"type":"boolean","description":"UI-D24 - a declared field that drives the form and predicates but is NEVER written to the config file and is outside T-KEYS. Every File Mesh discriminator is one."},"options":{"type":"array","items":{"type":["string","number","boolean"]},"minItems":1},"optionsSource":{"$ref":"#/$defs/optionsSource"},"valueFrom":{"$ref":"#/$defs/valueFrom"},"columns":{"type":"array","items":{"$ref":"#/$defs/column"},"description":"type: table only"},"default":{"type":["string","number","boolean","null"]},"required":{"type":"boolean"},"secret":{"type":"boolean"},"discriminator":{"type":"boolean"},"when":{"$ref":"#/$defs/predicate"},"min":{"type":"number"},"max":{"type":"number"},"pattern":{"type":"string"},"yamlSubtree":{"type":"string","description":"UI-D23 — the isolated subtree a yaml field renders into"},"ui":{"$ref":"#/$defs/ui"},"provenance":{"$ref":"#/$defs/provenanceTiers"}},"allOf":[{"description":"gate 11 — options XOR optionsSource","not":{"required":["options","optionsSource"]}},{"description":"an enum must say where its options come from","if":{"properties":{"type":{"const":"enum"}},"required":["type"]},"then":{"anyOf":[{"required":["options"]},{"required":["optionsSource"]}]}},{"description":"gate 6 - a discriminator must have an ENUMERABLE domain: enum or bool. Free text cannot drive a subtree. (Corrected by the Cache fixture: solace.enabled is a boolean discriminator, as is File Mesh's CONFIG_JAVA_RUNTIME.)","if":{"properties":{"discriminator":{"const":true}},"required":["discriminator"]},"then":{"properties":{"type":{"enum":["enum","bool"]}}}},{"description":"a table declares its columns","if":{"properties":{"type":{"const":"table"}},"required":["type"]},"then":{"required":["columns"]}},{"description":"UI-D23 — a yaml field must name its isolated subtree","if":{"properties":{"type":{"const":"yaml"}},"required":["type"]},"then":{"required":["yamlSubtree"]}}]};
const schema37 = {"type":"object","required":["kind","entity"],"additionalProperties":false,"description":"UI-D22, amended by UI-D27 — a package names an SM-owned entity. It can never supply a URL.","properties":{"kind":{"enum":["sm-catalog"]},"entity":{"enum":["connection","environment","instance","metric"]},"connectionType":{"enum":["solace-broker","remote-server"]}},"allOf":[{"description":"UI-D27 — a connection selector must say which connection type it wants","if":{"properties":{"entity":{"const":"connection"}},"required":["entity"]},"then":{"required":["connectionType"]}}]};
const schema38 = {"type":"object","required":["ref","attribute"],"additionalProperties":false,"description":"UI-D28 — this field takes its value from the connection chosen in `ref`. Same component only.","properties":{"ref":{"type":"string","description":"field.key of a connection selector in the SAME component"},"attribute":{"type":"string","description":"attribute key in that selector's connectionType descriptor"}}};
const schema39 = {"type":"object","required":["key","type"],"additionalProperties":false,"properties":{"key":{"type":"string"},"type":{"enum":["string","int","bool","enum","duration","secret"]},"options":{"type":"array","items":{"type":["string","number","boolean"]}},"label":{"type":"string"}}};
const schema40 = {"description":"UI-D2 — an object of field:literal, implicitly AND-ed. No OR, no comparison, no negation. Evaluated over defaults-applied values.","type":"object","minProperties":1,"additionalProperties":{"type":["string","number","boolean"]}};
const schema41 = {"type":"object","additionalProperties":false,"properties":{"label":{"type":"string"},"section":{"type":"string"},"subSection":{"type":"string"},"order":{"type":"integer"},"advanced":{"type":"boolean"},"helperText":{"type":"string"},"readOnly":{"type":"boolean"},"listColumn":{"type":"boolean","description":"UI-D27 — show this attribute as a column in the catalog list. Descriptors only."},"promoteToDeclare":{"type":"boolean","description":"UI-D27 — the Declare Instance dialog surfaces this field early."}}};
const schema42 = {"type":"object","additionalProperties":{"enum":["derived","suggested","authored"]},"description":"UI-D17 — provenance is a property of the column, carried per field for audit"};

function validate22(data, {instancePath="", parentData, parentDataProperty, rootData=data, dynamicAnchors={}}={}){
let vErrors = null;
let errors = 0;
const evaluated0 = validate22.evaluated;
if(evaluated0.dynamicProps){
evaluated0.props = undefined;
}
if(evaluated0.dynamicItems){
evaluated0.items = undefined;
}
const _errs2 = errors;
const _errs3 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing0;
if(((data.options === undefined) && (missing0 = "options")) || ((data.optionsSource === undefined) && (missing0 = "optionsSource"))){
const err0 = {};
if(vErrors === null){
vErrors = [err0];
}
else {
vErrors.push(err0);
}
errors++;
}
}
var valid1 = _errs3 === errors;
if(valid1){
const err1 = {instancePath,schemaPath:"#/allOf/0/not",keyword:"not",params:{},message:"must NOT be valid"};
if(vErrors === null){
vErrors = [err1];
}
else {
vErrors.push(err1);
}
errors++;
}
else {
errors = _errs2;
if(vErrors !== null){
if(_errs2){
vErrors.length = _errs2;
}
else {
vErrors = null;
}
}
}
const _errs5 = errors;
let valid2 = true;
const _errs6 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing1;
if((data.type === undefined) && (missing1 = "type")){
const err2 = {};
if(vErrors === null){
vErrors = [err2];
}
else {
vErrors.push(err2);
}
errors++;
}
else {
if(data.type !== undefined){
if("enum" !== data.type){
const err3 = {};
if(vErrors === null){
vErrors = [err3];
}
else {
vErrors.push(err3);
}
errors++;
}
}
}
}
var _valid0 = _errs6 === errors;
errors = _errs5;
if(vErrors !== null){
if(_errs5){
vErrors.length = _errs5;
}
else {
vErrors = null;
}
}
if(_valid0){
const _errs8 = errors;
const _errs9 = errors;
let valid4 = false;
const _errs10 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.options === undefined){
const err4 = {instancePath,schemaPath:"#/allOf/1/then/anyOf/0/required",keyword:"required",params:{missingProperty: "options"},message:"must have required property '"+"options"+"'"};
if(vErrors === null){
vErrors = [err4];
}
else {
vErrors.push(err4);
}
errors++;
}
}
var _valid1 = _errs10 === errors;
valid4 = valid4 || _valid1;
const _errs11 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.optionsSource === undefined){
const err5 = {instancePath,schemaPath:"#/allOf/1/then/anyOf/1/required",keyword:"required",params:{missingProperty: "optionsSource"},message:"must have required property '"+"optionsSource"+"'"};
if(vErrors === null){
vErrors = [err5];
}
else {
vErrors.push(err5);
}
errors++;
}
}
var _valid1 = _errs11 === errors;
valid4 = valid4 || _valid1;
if(!valid4){
const err6 = {instancePath,schemaPath:"#/allOf/1/then/anyOf",keyword:"anyOf",params:{},message:"must match a schema in anyOf"};
if(vErrors === null){
vErrors = [err6];
}
else {
vErrors.push(err6);
}
errors++;
}
else {
errors = _errs9;
if(vErrors !== null){
if(_errs9){
vErrors.length = _errs9;
}
else {
vErrors = null;
}
}
}
var _valid0 = _errs8 === errors;
valid2 = _valid0;
}
if(!valid2){
const err7 = {instancePath,schemaPath:"#/allOf/1/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err7];
}
else {
vErrors.push(err7);
}
errors++;
}
const _errs13 = errors;
let valid5 = true;
const _errs14 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing2;
if((data.discriminator === undefined) && (missing2 = "discriminator")){
const err8 = {};
if(vErrors === null){
vErrors = [err8];
}
else {
vErrors.push(err8);
}
errors++;
}
else {
if(data.discriminator !== undefined){
if(true !== data.discriminator){
const err9 = {};
if(vErrors === null){
vErrors = [err9];
}
else {
vErrors.push(err9);
}
errors++;
}
}
}
}
var _valid2 = _errs14 === errors;
errors = _errs13;
if(vErrors !== null){
if(_errs13){
vErrors.length = _errs13;
}
else {
vErrors = null;
}
}
if(_valid2){
const _errs16 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.type !== undefined){
let data2 = data.type;
if(!((data2 === "enum") || (data2 === "bool"))){
const err10 = {instancePath:instancePath+"/type",schemaPath:"#/allOf/2/then/properties/type/enum",keyword:"enum",params:{allowedValues: schema35.allOf[2].then.properties.type.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err10];
}
else {
vErrors.push(err10);
}
errors++;
}
}
}
var _valid2 = _errs16 === errors;
valid5 = _valid2;
if(valid5){
var props0 = {};
props0.type = true;
props0.discriminator = true;
}
}
if(!valid5){
const err11 = {instancePath,schemaPath:"#/allOf/2/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err11];
}
else {
vErrors.push(err11);
}
errors++;
}
if(props0 !== true){
props0 = props0 || {};
props0.type = true;
}
const _errs19 = errors;
let valid8 = true;
const _errs20 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing3;
if((data.type === undefined) && (missing3 = "type")){
const err12 = {};
if(vErrors === null){
vErrors = [err12];
}
else {
vErrors.push(err12);
}
errors++;
}
else {
if(data.type !== undefined){
if("table" !== data.type){
const err13 = {};
if(vErrors === null){
vErrors = [err13];
}
else {
vErrors.push(err13);
}
errors++;
}
}
}
}
var _valid3 = _errs20 === errors;
errors = _errs19;
if(vErrors !== null){
if(_errs19){
vErrors.length = _errs19;
}
else {
vErrors = null;
}
}
if(_valid3){
const _errs22 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.columns === undefined){
const err14 = {instancePath,schemaPath:"#/allOf/3/then/required",keyword:"required",params:{missingProperty: "columns"},message:"must have required property '"+"columns"+"'"};
if(vErrors === null){
vErrors = [err14];
}
else {
vErrors.push(err14);
}
errors++;
}
}
var _valid3 = _errs22 === errors;
valid8 = _valid3;
}
if(!valid8){
const err15 = {instancePath,schemaPath:"#/allOf/3/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err15];
}
else {
vErrors.push(err15);
}
errors++;
}
if(props0 !== true){
props0 = props0 || {};
props0.type = true;
}
const _errs24 = errors;
let valid10 = true;
const _errs25 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing4;
if((data.type === undefined) && (missing4 = "type")){
const err16 = {};
if(vErrors === null){
vErrors = [err16];
}
else {
vErrors.push(err16);
}
errors++;
}
else {
if(data.type !== undefined){
if("yaml" !== data.type){
const err17 = {};
if(vErrors === null){
vErrors = [err17];
}
else {
vErrors.push(err17);
}
errors++;
}
}
}
}
var _valid4 = _errs25 === errors;
errors = _errs24;
if(vErrors !== null){
if(_errs24){
vErrors.length = _errs24;
}
else {
vErrors = null;
}
}
if(_valid4){
const _errs27 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.yamlSubtree === undefined){
const err18 = {instancePath,schemaPath:"#/allOf/4/then/required",keyword:"required",params:{missingProperty: "yamlSubtree"},message:"must have required property '"+"yamlSubtree"+"'"};
if(vErrors === null){
vErrors = [err18];
}
else {
vErrors.push(err18);
}
errors++;
}
}
var _valid4 = _errs27 === errors;
valid10 = _valid4;
}
if(!valid10){
const err19 = {instancePath,schemaPath:"#/allOf/4/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err19];
}
else {
vErrors.push(err19);
}
errors++;
}
if(props0 !== true){
props0 = props0 || {};
props0.type = true;
}
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.key === undefined){
const err20 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "key"},message:"must have required property '"+"key"+"'"};
if(vErrors === null){
vErrors = [err20];
}
else {
vErrors.push(err20);
}
errors++;
}
if(data.type === undefined){
const err21 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "type"},message:"must have required property '"+"type"+"'"};
if(vErrors === null){
vErrors = [err21];
}
else {
vErrors.push(err21);
}
errors++;
}
for(const key0 in data){
if(!(func3.call(schema35.properties, key0))){
const err22 = {instancePath,schemaPath:"#/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key0},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err22];
}
else {
vErrors.push(err22);
}
errors++;
}
}
if(data.key !== undefined){
if(typeof data.key !== "string"){
const err23 = {instancePath:instancePath+"/key",schemaPath:"#/properties/key/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err23];
}
else {
vErrors.push(err23);
}
errors++;
}
}
if(data.type !== undefined){
let data6 = data.type;
if(!((((((((((data6 === "string") || (data6 === "int")) || (data6 === "bool")) || (data6 === "enum")) || (data6 === "duration")) || (data6 === "list")) || (data6 === "map")) || (data6 === "table")) || (data6 === "yaml")) || (data6 === "secret"))){
const err24 = {instancePath:instancePath+"/type",schemaPath:"#/properties/type/enum",keyword:"enum",params:{allowedValues: schema35.properties.type.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err24];
}
else {
vErrors.push(err24);
}
errors++;
}
}
if(data.role !== undefined){
let data7 = data.role;
if((typeof data7 !== "string") && (data7 !== null)){
const err25 = {instancePath:instancePath+"/role",schemaPath:"#/properties/role/type",keyword:"type",params:{type: schema35.properties.role.type},message:"must be string,null"};
if(vErrors === null){
vErrors = [err25];
}
else {
vErrors.push(err25);
}
errors++;
}
if(typeof data7 === "string"){
if(func1(data7) < 1){
const err26 = {instancePath:instancePath+"/role",schemaPath:"#/properties/role/minLength",keyword:"minLength",params:{limit: 1},message:"must NOT have fewer than 1 characters"};
if(vErrors === null){
vErrors = [err26];
}
else {
vErrors.push(err26);
}
errors++;
}
}
}
if(data.version !== undefined){
let data8 = data.version;
if(Array.isArray(data8)){
if(data8.length < 1){
const err27 = {instancePath:instancePath+"/version",schemaPath:"#/properties/version/minItems",keyword:"minItems",params:{limit: 1},message:"must NOT have fewer than 1 items"};
if(vErrors === null){
vErrors = [err27];
}
else {
vErrors.push(err27);
}
errors++;
}
const len0 = data8.length;
for(let i0=0; i0<len0; i0++){
let data9 = data8[i0];
const _errs38 = errors;
let valid16 = false;
let passing0 = null;
const _errs39 = errors;
if(!(((typeof data9 == "number") && (!(data9 % 1) && !isNaN(data9))) && (isFinite(data9)))){
const err28 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/0/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err28];
}
else {
vErrors.push(err28);
}
errors++;
}
if((typeof data9 == "number") && (isFinite(data9))){
if(data9 < 1 || isNaN(data9)){
const err29 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/0/minimum",keyword:"minimum",params:{comparison: ">=", limit: 1},message:"must be >= 1"};
if(vErrors === null){
vErrors = [err29];
}
else {
vErrors.push(err29);
}
errors++;
}
}
var _valid5 = _errs39 === errors;
if(_valid5){
valid16 = true;
passing0 = 0;
}
const _errs41 = errors;
if(typeof data9 === "string"){
if(func1(data9) < 1){
const err30 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/1/minLength",keyword:"minLength",params:{limit: 1},message:"must NOT have fewer than 1 characters"};
if(vErrors === null){
vErrors = [err30];
}
else {
vErrors.push(err30);
}
errors++;
}
}
else {
const err31 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/1/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err31];
}
else {
vErrors.push(err31);
}
errors++;
}
var _valid5 = _errs41 === errors;
if(_valid5 && valid16){
valid16 = false;
passing0 = [passing0, 1];
}
else {
if(_valid5){
valid16 = true;
passing0 = 1;
}
}
if(!valid16){
const err32 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf",keyword:"oneOf",params:{passingSchemas: passing0},message:"must match exactly one schema in oneOf"};
if(vErrors === null){
vErrors = [err32];
}
else {
vErrors.push(err32);
}
errors++;
}
else {
errors = _errs38;
if(vErrors !== null){
if(_errs38){
vErrors.length = _errs38;
}
else {
vErrors = null;
}
}
}
}
let i1 = data8.length;
let j0;
if(i1 > 1){
outer0:
for(;i1--;){
for(j0 = i1; j0--;){
if(func0(data8[i1], data8[j0])){
const err33 = {instancePath:instancePath+"/version",schemaPath:"#/properties/version/uniqueItems",keyword:"uniqueItems",params:{i: i1, j: j0},message:"must NOT have duplicate items (items ## "+j0+" and "+i1+" are identical)"};
if(vErrors === null){
vErrors = [err33];
}
else {
vErrors.push(err33);
}
errors++;
break outer0;
}
}
}
}
}
else {
const err34 = {instancePath:instancePath+"/version",schemaPath:"#/properties/version/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err34];
}
else {
vErrors.push(err34);
}
errors++;
}
}
if(data.virtual !== undefined){
if(typeof data.virtual !== "boolean"){
const err35 = {instancePath:instancePath+"/virtual",schemaPath:"#/properties/virtual/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err35];
}
else {
vErrors.push(err35);
}
errors++;
}
}
if(data.options !== undefined){
let data11 = data.options;
if(Array.isArray(data11)){
if(data11.length < 1){
const err36 = {instancePath:instancePath+"/options",schemaPath:"#/properties/options/minItems",keyword:"minItems",params:{limit: 1},message:"must NOT have fewer than 1 items"};
if(vErrors === null){
vErrors = [err36];
}
else {
vErrors.push(err36);
}
errors++;
}
const len1 = data11.length;
for(let i2=0; i2<len1; i2++){
let data12 = data11[i2];
if(((typeof data12 !== "string") && (!((typeof data12 == "number") && (isFinite(data12))))) && (typeof data12 !== "boolean")){
const err37 = {instancePath:instancePath+"/options/" + i2,schemaPath:"#/properties/options/items/type",keyword:"type",params:{type: schema35.properties.options.items.type},message:"must be string,number,boolean"};
if(vErrors === null){
vErrors = [err37];
}
else {
vErrors.push(err37);
}
errors++;
}
}
}
else {
const err38 = {instancePath:instancePath+"/options",schemaPath:"#/properties/options/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err38];
}
else {
vErrors.push(err38);
}
errors++;
}
}
if(data.optionsSource !== undefined){
let data13 = data.optionsSource;
const _errs53 = errors;
let valid22 = true;
const _errs54 = errors;
if(data13 && typeof data13 == "object" && !Array.isArray(data13)){
let missing5;
if((data13.entity === undefined) && (missing5 = "entity")){
const err39 = {};
if(vErrors === null){
vErrors = [err39];
}
else {
vErrors.push(err39);
}
errors++;
}
else {
if(data13.entity !== undefined){
if("connection" !== data13.entity){
const err40 = {};
if(vErrors === null){
vErrors = [err40];
}
else {
vErrors.push(err40);
}
errors++;
}
}
}
}
var _valid6 = _errs54 === errors;
errors = _errs53;
if(vErrors !== null){
if(_errs53){
vErrors.length = _errs53;
}
else {
vErrors = null;
}
}
if(_valid6){
const _errs56 = errors;
if(data13 && typeof data13 == "object" && !Array.isArray(data13)){
if(data13.connectionType === undefined){
const err41 = {instancePath:instancePath+"/optionsSource",schemaPath:"#/$defs/optionsSource/allOf/0/then/required",keyword:"required",params:{missingProperty: "connectionType"},message:"must have required property '"+"connectionType"+"'"};
if(vErrors === null){
vErrors = [err41];
}
else {
vErrors.push(err41);
}
errors++;
}
}
var _valid6 = _errs56 === errors;
valid22 = _valid6;
}
if(!valid22){
const err42 = {instancePath:instancePath+"/optionsSource",schemaPath:"#/$defs/optionsSource/allOf/0/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err42];
}
else {
vErrors.push(err42);
}
errors++;
}
if(data13 && typeof data13 == "object" && !Array.isArray(data13)){
if(data13.kind === undefined){
const err43 = {instancePath:instancePath+"/optionsSource",schemaPath:"#/$defs/optionsSource/required",keyword:"required",params:{missingProperty: "kind"},message:"must have required property '"+"kind"+"'"};
if(vErrors === null){
vErrors = [err43];
}
else {
vErrors.push(err43);
}
errors++;
}
if(data13.entity === undefined){
const err44 = {instancePath:instancePath+"/optionsSource",schemaPath:"#/$defs/optionsSource/required",keyword:"required",params:{missingProperty: "entity"},message:"must have required property '"+"entity"+"'"};
if(vErrors === null){
vErrors = [err44];
}
else {
vErrors.push(err44);
}
errors++;
}
for(const key1 in data13){
if(!(((key1 === "kind") || (key1 === "entity")) || (key1 === "connectionType"))){
const err45 = {instancePath:instancePath+"/optionsSource",schemaPath:"#/$defs/optionsSource/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key1},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err45];
}
else {
vErrors.push(err45);
}
errors++;
}
}
if(data13.kind !== undefined){
if(!(data13.kind === "sm-catalog")){
const err46 = {instancePath:instancePath+"/optionsSource/kind",schemaPath:"#/$defs/optionsSource/properties/kind/enum",keyword:"enum",params:{allowedValues: schema37.properties.kind.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err46];
}
else {
vErrors.push(err46);
}
errors++;
}
}
if(data13.entity !== undefined){
let data16 = data13.entity;
if(!((((data16 === "connection") || (data16 === "environment")) || (data16 === "instance")) || (data16 === "metric"))){
const err47 = {instancePath:instancePath+"/optionsSource/entity",schemaPath:"#/$defs/optionsSource/properties/entity/enum",keyword:"enum",params:{allowedValues: schema37.properties.entity.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err47];
}
else {
vErrors.push(err47);
}
errors++;
}
}
if(data13.connectionType !== undefined){
let data17 = data13.connectionType;
if(!((data17 === "solace-broker") || (data17 === "remote-server"))){
const err48 = {instancePath:instancePath+"/optionsSource/connectionType",schemaPath:"#/$defs/optionsSource/properties/connectionType/enum",keyword:"enum",params:{allowedValues: schema37.properties.connectionType.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err48];
}
else {
vErrors.push(err48);
}
errors++;
}
}
}
else {
const err49 = {instancePath:instancePath+"/optionsSource",schemaPath:"#/$defs/optionsSource/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err49];
}
else {
vErrors.push(err49);
}
errors++;
}
}
if(data.valueFrom !== undefined){
let data18 = data.valueFrom;
if(data18 && typeof data18 == "object" && !Array.isArray(data18)){
if(data18.ref === undefined){
const err50 = {instancePath:instancePath+"/valueFrom",schemaPath:"#/$defs/valueFrom/required",keyword:"required",params:{missingProperty: "ref"},message:"must have required property '"+"ref"+"'"};
if(vErrors === null){
vErrors = [err50];
}
else {
vErrors.push(err50);
}
errors++;
}
if(data18.attribute === undefined){
const err51 = {instancePath:instancePath+"/valueFrom",schemaPath:"#/$defs/valueFrom/required",keyword:"required",params:{missingProperty: "attribute"},message:"must have required property '"+"attribute"+"'"};
if(vErrors === null){
vErrors = [err51];
}
else {
vErrors.push(err51);
}
errors++;
}
for(const key2 in data18){
if(!((key2 === "ref") || (key2 === "attribute"))){
const err52 = {instancePath:instancePath+"/valueFrom",schemaPath:"#/$defs/valueFrom/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key2},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err52];
}
else {
vErrors.push(err52);
}
errors++;
}
}
if(data18.ref !== undefined){
if(typeof data18.ref !== "string"){
const err53 = {instancePath:instancePath+"/valueFrom/ref",schemaPath:"#/$defs/valueFrom/properties/ref/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err53];
}
else {
vErrors.push(err53);
}
errors++;
}
}
if(data18.attribute !== undefined){
if(typeof data18.attribute !== "string"){
const err54 = {instancePath:instancePath+"/valueFrom/attribute",schemaPath:"#/$defs/valueFrom/properties/attribute/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err54];
}
else {
vErrors.push(err54);
}
errors++;
}
}
}
else {
const err55 = {instancePath:instancePath+"/valueFrom",schemaPath:"#/$defs/valueFrom/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err55];
}
else {
vErrors.push(err55);
}
errors++;
}
}
if(data.columns !== undefined){
let data21 = data.columns;
if(Array.isArray(data21)){
const len2 = data21.length;
for(let i3=0; i3<len2; i3++){
let data22 = data21[i3];
if(data22 && typeof data22 == "object" && !Array.isArray(data22)){
if(data22.key === undefined){
const err56 = {instancePath:instancePath+"/columns/" + i3,schemaPath:"#/$defs/column/required",keyword:"required",params:{missingProperty: "key"},message:"must have required property '"+"key"+"'"};
if(vErrors === null){
vErrors = [err56];
}
else {
vErrors.push(err56);
}
errors++;
}
if(data22.type === undefined){
const err57 = {instancePath:instancePath+"/columns/" + i3,schemaPath:"#/$defs/column/required",keyword:"required",params:{missingProperty: "type"},message:"must have required property '"+"type"+"'"};
if(vErrors === null){
vErrors = [err57];
}
else {
vErrors.push(err57);
}
errors++;
}
for(const key3 in data22){
if(!((((key3 === "key") || (key3 === "type")) || (key3 === "options")) || (key3 === "label"))){
const err58 = {instancePath:instancePath+"/columns/" + i3,schemaPath:"#/$defs/column/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key3},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err58];
}
else {
vErrors.push(err58);
}
errors++;
}
}
if(data22.key !== undefined){
if(typeof data22.key !== "string"){
const err59 = {instancePath:instancePath+"/columns/" + i3+"/key",schemaPath:"#/$defs/column/properties/key/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err59];
}
else {
vErrors.push(err59);
}
errors++;
}
}
if(data22.type !== undefined){
let data24 = data22.type;
if(!((((((data24 === "string") || (data24 === "int")) || (data24 === "bool")) || (data24 === "enum")) || (data24 === "duration")) || (data24 === "secret"))){
const err60 = {instancePath:instancePath+"/columns/" + i3+"/type",schemaPath:"#/$defs/column/properties/type/enum",keyword:"enum",params:{allowedValues: schema39.properties.type.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err60];
}
else {
vErrors.push(err60);
}
errors++;
}
}
if(data22.options !== undefined){
let data25 = data22.options;
if(Array.isArray(data25)){
const len3 = data25.length;
for(let i4=0; i4<len3; i4++){
let data26 = data25[i4];
if(((typeof data26 !== "string") && (!((typeof data26 == "number") && (isFinite(data26))))) && (typeof data26 !== "boolean")){
const err61 = {instancePath:instancePath+"/columns/" + i3+"/options/" + i4,schemaPath:"#/$defs/column/properties/options/items/type",keyword:"type",params:{type: schema39.properties.options.items.type},message:"must be string,number,boolean"};
if(vErrors === null){
vErrors = [err61];
}
else {
vErrors.push(err61);
}
errors++;
}
}
}
else {
const err62 = {instancePath:instancePath+"/columns/" + i3+"/options",schemaPath:"#/$defs/column/properties/options/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err62];
}
else {
vErrors.push(err62);
}
errors++;
}
}
if(data22.label !== undefined){
if(typeof data22.label !== "string"){
const err63 = {instancePath:instancePath+"/columns/" + i3+"/label",schemaPath:"#/$defs/column/properties/label/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err63];
}
else {
vErrors.push(err63);
}
errors++;
}
}
}
else {
const err64 = {instancePath:instancePath+"/columns/" + i3,schemaPath:"#/$defs/column/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err64];
}
else {
vErrors.push(err64);
}
errors++;
}
}
}
else {
const err65 = {instancePath:instancePath+"/columns",schemaPath:"#/properties/columns/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err65];
}
else {
vErrors.push(err65);
}
errors++;
}
}
if(data.default !== undefined){
let data28 = data.default;
if((((typeof data28 !== "string") && (!((typeof data28 == "number") && (isFinite(data28))))) && (typeof data28 !== "boolean")) && (data28 !== null)){
const err66 = {instancePath:instancePath+"/default",schemaPath:"#/properties/default/type",keyword:"type",params:{type: schema35.properties.default.type},message:"must be string,number,boolean,null"};
if(vErrors === null){
vErrors = [err66];
}
else {
vErrors.push(err66);
}
errors++;
}
}
if(data.required !== undefined){
if(typeof data.required !== "boolean"){
const err67 = {instancePath:instancePath+"/required",schemaPath:"#/properties/required/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err67];
}
else {
vErrors.push(err67);
}
errors++;
}
}
if(data.secret !== undefined){
if(typeof data.secret !== "boolean"){
const err68 = {instancePath:instancePath+"/secret",schemaPath:"#/properties/secret/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err68];
}
else {
vErrors.push(err68);
}
errors++;
}
}
if(data.discriminator !== undefined){
if(typeof data.discriminator !== "boolean"){
const err69 = {instancePath:instancePath+"/discriminator",schemaPath:"#/properties/discriminator/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err69];
}
else {
vErrors.push(err69);
}
errors++;
}
}
if(data.when !== undefined){
let data32 = data.when;
if(data32 && typeof data32 == "object" && !Array.isArray(data32)){
if(Object.keys(data32).length < 1){
const err70 = {instancePath:instancePath+"/when",schemaPath:"#/$defs/predicate/minProperties",keyword:"minProperties",params:{limit: 1},message:"must NOT have fewer than 1 properties"};
if(vErrors === null){
vErrors = [err70];
}
else {
vErrors.push(err70);
}
errors++;
}
for(const key4 in data32){
let data33 = data32[key4];
if(((typeof data33 !== "string") && (!((typeof data33 == "number") && (isFinite(data33))))) && (typeof data33 !== "boolean")){
const err71 = {instancePath:instancePath+"/when/" + key4.replace(/~/g, "~0").replace(/\//g, "~1"),schemaPath:"#/$defs/predicate/additionalProperties/type",keyword:"type",params:{type: schema40.additionalProperties.type},message:"must be string,number,boolean"};
if(vErrors === null){
vErrors = [err71];
}
else {
vErrors.push(err71);
}
errors++;
}
}
}
else {
const err72 = {instancePath:instancePath+"/when",schemaPath:"#/$defs/predicate/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err72];
}
else {
vErrors.push(err72);
}
errors++;
}
}
if(data.min !== undefined){
let data34 = data.min;
if(!((typeof data34 == "number") && (isFinite(data34)))){
const err73 = {instancePath:instancePath+"/min",schemaPath:"#/properties/min/type",keyword:"type",params:{type: "number"},message:"must be number"};
if(vErrors === null){
vErrors = [err73];
}
else {
vErrors.push(err73);
}
errors++;
}
}
if(data.max !== undefined){
let data35 = data.max;
if(!((typeof data35 == "number") && (isFinite(data35)))){
const err74 = {instancePath:instancePath+"/max",schemaPath:"#/properties/max/type",keyword:"type",params:{type: "number"},message:"must be number"};
if(vErrors === null){
vErrors = [err74];
}
else {
vErrors.push(err74);
}
errors++;
}
}
if(data.pattern !== undefined){
if(typeof data.pattern !== "string"){
const err75 = {instancePath:instancePath+"/pattern",schemaPath:"#/properties/pattern/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err75];
}
else {
vErrors.push(err75);
}
errors++;
}
}
if(data.yamlSubtree !== undefined){
if(typeof data.yamlSubtree !== "string"){
const err76 = {instancePath:instancePath+"/yamlSubtree",schemaPath:"#/properties/yamlSubtree/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err76];
}
else {
vErrors.push(err76);
}
errors++;
}
}
if(data.ui !== undefined){
let data38 = data.ui;
if(data38 && typeof data38 == "object" && !Array.isArray(data38)){
for(const key5 in data38){
if(!(func3.call(schema41.properties, key5))){
const err77 = {instancePath:instancePath+"/ui",schemaPath:"#/$defs/ui/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key5},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err77];
}
else {
vErrors.push(err77);
}
errors++;
}
}
if(data38.label !== undefined){
if(typeof data38.label !== "string"){
const err78 = {instancePath:instancePath+"/ui/label",schemaPath:"#/$defs/ui/properties/label/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err78];
}
else {
vErrors.push(err78);
}
errors++;
}
}
if(data38.section !== undefined){
if(typeof data38.section !== "string"){
const err79 = {instancePath:instancePath+"/ui/section",schemaPath:"#/$defs/ui/properties/section/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err79];
}
else {
vErrors.push(err79);
}
errors++;
}
}
if(data38.subSection !== undefined){
if(typeof data38.subSection !== "string"){
const err80 = {instancePath:instancePath+"/ui/subSection",schemaPath:"#/$defs/ui/properties/subSection/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err80];
}
else {
vErrors.push(err80);
}
errors++;
}
}
if(data38.order !== undefined){
let data42 = data38.order;
if(!(((typeof data42 == "number") && (!(data42 % 1) && !isNaN(data42))) && (isFinite(data42)))){
const err81 = {instancePath:instancePath+"/ui/order",schemaPath:"#/$defs/ui/properties/order/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err81];
}
else {
vErrors.push(err81);
}
errors++;
}
}
if(data38.advanced !== undefined){
if(typeof data38.advanced !== "boolean"){
const err82 = {instancePath:instancePath+"/ui/advanced",schemaPath:"#/$defs/ui/properties/advanced/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err82];
}
else {
vErrors.push(err82);
}
errors++;
}
}
if(data38.helperText !== undefined){
if(typeof data38.helperText !== "string"){
const err83 = {instancePath:instancePath+"/ui/helperText",schemaPath:"#/$defs/ui/properties/helperText/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err83];
}
else {
vErrors.push(err83);
}
errors++;
}
}
if(data38.readOnly !== undefined){
if(typeof data38.readOnly !== "boolean"){
const err84 = {instancePath:instancePath+"/ui/readOnly",schemaPath:"#/$defs/ui/properties/readOnly/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err84];
}
else {
vErrors.push(err84);
}
errors++;
}
}
if(data38.listColumn !== undefined){
if(typeof data38.listColumn !== "boolean"){
const err85 = {instancePath:instancePath+"/ui/listColumn",schemaPath:"#/$defs/ui/properties/listColumn/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err85];
}
else {
vErrors.push(err85);
}
errors++;
}
}
if(data38.promoteToDeclare !== undefined){
if(typeof data38.promoteToDeclare !== "boolean"){
const err86 = {instancePath:instancePath+"/ui/promoteToDeclare",schemaPath:"#/$defs/ui/properties/promoteToDeclare/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err86];
}
else {
vErrors.push(err86);
}
errors++;
}
}
}
else {
const err87 = {instancePath:instancePath+"/ui",schemaPath:"#/$defs/ui/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err87];
}
else {
vErrors.push(err87);
}
errors++;
}
}
if(data.provenance !== undefined){
let data48 = data.provenance;
if(data48 && typeof data48 == "object" && !Array.isArray(data48)){
for(const key6 in data48){
let data49 = data48[key6];
if(!(((data49 === "derived") || (data49 === "suggested")) || (data49 === "authored"))){
const err88 = {instancePath:instancePath+"/provenance/" + key6.replace(/~/g, "~0").replace(/\//g, "~1"),schemaPath:"#/$defs/provenanceTiers/additionalProperties/enum",keyword:"enum",params:{allowedValues: schema42.additionalProperties.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err88];
}
else {
vErrors.push(err88);
}
errors++;
}
}
}
else {
const err89 = {instancePath:instancePath+"/provenance",schemaPath:"#/$defs/provenanceTiers/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err89];
}
else {
vErrors.push(err89);
}
errors++;
}
}
}
else {
const err90 = {instancePath,schemaPath:"#/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err90];
}
else {
vErrors.push(err90);
}
errors++;
}
validate22.errors = vErrors;
return errors === 0;
}
validate22.evaluated = {"props":true,"dynamicProps":false,"dynamicItems":false};

const schema43 = {"type":"object","required":["key","kind"],"additionalProperties":false,"description":"UI-D22 — actions hold no value and appear in no config file.","properties":{"key":{"type":"string"},"kind":{"enum":["test-connection","manage-catalog-entry"]},"entity":{"enum":["connection","environment","instance","metric"]},"inputs":{"type":"array","items":{"type":"string"}},"when":{"$ref":"#/$defs/predicate"},"ui":{"$ref":"#/$defs/ui"}},"allOf":[{"description":"catalog management must say which entity it manages","if":{"properties":{"kind":{"const":"manage-catalog-entry"}},"required":["kind"]},"then":{"required":["entity"]}},{"description":"a connection test must name the inputs it tests","if":{"properties":{"kind":{"const":"test-connection"}},"required":["kind"]},"then":{"required":["inputs"]}}]};

function validate24(data, {instancePath="", parentData, parentDataProperty, rootData=data, dynamicAnchors={}}={}){
let vErrors = null;
let errors = 0;
const evaluated0 = validate24.evaluated;
if(evaluated0.dynamicProps){
evaluated0.props = undefined;
}
if(evaluated0.dynamicItems){
evaluated0.items = undefined;
}
const _errs2 = errors;
let valid1 = true;
const _errs3 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing0;
if((data.kind === undefined) && (missing0 = "kind")){
const err0 = {};
if(vErrors === null){
vErrors = [err0];
}
else {
vErrors.push(err0);
}
errors++;
}
else {
if(data.kind !== undefined){
if("manage-catalog-entry" !== data.kind){
const err1 = {};
if(vErrors === null){
vErrors = [err1];
}
else {
vErrors.push(err1);
}
errors++;
}
}
}
}
var _valid0 = _errs3 === errors;
errors = _errs2;
if(vErrors !== null){
if(_errs2){
vErrors.length = _errs2;
}
else {
vErrors = null;
}
}
if(_valid0){
const _errs5 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.entity === undefined){
const err2 = {instancePath,schemaPath:"#/allOf/0/then/required",keyword:"required",params:{missingProperty: "entity"},message:"must have required property '"+"entity"+"'"};
if(vErrors === null){
vErrors = [err2];
}
else {
vErrors.push(err2);
}
errors++;
}
}
var _valid0 = _errs5 === errors;
valid1 = _valid0;
}
if(!valid1){
const err3 = {instancePath,schemaPath:"#/allOf/0/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err3];
}
else {
vErrors.push(err3);
}
errors++;
}
const _errs7 = errors;
let valid3 = true;
const _errs8 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing1;
if((data.kind === undefined) && (missing1 = "kind")){
const err4 = {};
if(vErrors === null){
vErrors = [err4];
}
else {
vErrors.push(err4);
}
errors++;
}
else {
if(data.kind !== undefined){
if("test-connection" !== data.kind){
const err5 = {};
if(vErrors === null){
vErrors = [err5];
}
else {
vErrors.push(err5);
}
errors++;
}
}
}
}
var _valid1 = _errs8 === errors;
errors = _errs7;
if(vErrors !== null){
if(_errs7){
vErrors.length = _errs7;
}
else {
vErrors = null;
}
}
if(_valid1){
const _errs10 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.inputs === undefined){
const err6 = {instancePath,schemaPath:"#/allOf/1/then/required",keyword:"required",params:{missingProperty: "inputs"},message:"must have required property '"+"inputs"+"'"};
if(vErrors === null){
vErrors = [err6];
}
else {
vErrors.push(err6);
}
errors++;
}
}
var _valid1 = _errs10 === errors;
valid3 = _valid1;
}
if(!valid3){
const err7 = {instancePath,schemaPath:"#/allOf/1/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err7];
}
else {
vErrors.push(err7);
}
errors++;
}
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.key === undefined){
const err8 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "key"},message:"must have required property '"+"key"+"'"};
if(vErrors === null){
vErrors = [err8];
}
else {
vErrors.push(err8);
}
errors++;
}
if(data.kind === undefined){
const err9 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "kind"},message:"must have required property '"+"kind"+"'"};
if(vErrors === null){
vErrors = [err9];
}
else {
vErrors.push(err9);
}
errors++;
}
for(const key0 in data){
if(!((((((key0 === "key") || (key0 === "kind")) || (key0 === "entity")) || (key0 === "inputs")) || (key0 === "when")) || (key0 === "ui"))){
const err10 = {instancePath,schemaPath:"#/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key0},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err10];
}
else {
vErrors.push(err10);
}
errors++;
}
}
if(data.key !== undefined){
if(typeof data.key !== "string"){
const err11 = {instancePath:instancePath+"/key",schemaPath:"#/properties/key/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err11];
}
else {
vErrors.push(err11);
}
errors++;
}
}
if(data.kind !== undefined){
let data3 = data.kind;
if(!((data3 === "test-connection") || (data3 === "manage-catalog-entry"))){
const err12 = {instancePath:instancePath+"/kind",schemaPath:"#/properties/kind/enum",keyword:"enum",params:{allowedValues: schema43.properties.kind.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err12];
}
else {
vErrors.push(err12);
}
errors++;
}
}
if(data.entity !== undefined){
let data4 = data.entity;
if(!((((data4 === "connection") || (data4 === "environment")) || (data4 === "instance")) || (data4 === "metric"))){
const err13 = {instancePath:instancePath+"/entity",schemaPath:"#/properties/entity/enum",keyword:"enum",params:{allowedValues: schema43.properties.entity.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err13];
}
else {
vErrors.push(err13);
}
errors++;
}
}
if(data.inputs !== undefined){
let data5 = data.inputs;
if(Array.isArray(data5)){
const len0 = data5.length;
for(let i0=0; i0<len0; i0++){
if(typeof data5[i0] !== "string"){
const err14 = {instancePath:instancePath+"/inputs/" + i0,schemaPath:"#/properties/inputs/items/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err14];
}
else {
vErrors.push(err14);
}
errors++;
}
}
}
else {
const err15 = {instancePath:instancePath+"/inputs",schemaPath:"#/properties/inputs/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err15];
}
else {
vErrors.push(err15);
}
errors++;
}
}
if(data.when !== undefined){
let data7 = data.when;
if(data7 && typeof data7 == "object" && !Array.isArray(data7)){
if(Object.keys(data7).length < 1){
const err16 = {instancePath:instancePath+"/when",schemaPath:"#/$defs/predicate/minProperties",keyword:"minProperties",params:{limit: 1},message:"must NOT have fewer than 1 properties"};
if(vErrors === null){
vErrors = [err16];
}
else {
vErrors.push(err16);
}
errors++;
}
for(const key1 in data7){
let data8 = data7[key1];
if(((typeof data8 !== "string") && (!((typeof data8 == "number") && (isFinite(data8))))) && (typeof data8 !== "boolean")){
const err17 = {instancePath:instancePath+"/when/" + key1.replace(/~/g, "~0").replace(/\//g, "~1"),schemaPath:"#/$defs/predicate/additionalProperties/type",keyword:"type",params:{type: schema40.additionalProperties.type},message:"must be string,number,boolean"};
if(vErrors === null){
vErrors = [err17];
}
else {
vErrors.push(err17);
}
errors++;
}
}
}
else {
const err18 = {instancePath:instancePath+"/when",schemaPath:"#/$defs/predicate/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err18];
}
else {
vErrors.push(err18);
}
errors++;
}
}
if(data.ui !== undefined){
let data9 = data.ui;
if(data9 && typeof data9 == "object" && !Array.isArray(data9)){
for(const key2 in data9){
if(!(func3.call(schema41.properties, key2))){
const err19 = {instancePath:instancePath+"/ui",schemaPath:"#/$defs/ui/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key2},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err19];
}
else {
vErrors.push(err19);
}
errors++;
}
}
if(data9.label !== undefined){
if(typeof data9.label !== "string"){
const err20 = {instancePath:instancePath+"/ui/label",schemaPath:"#/$defs/ui/properties/label/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err20];
}
else {
vErrors.push(err20);
}
errors++;
}
}
if(data9.section !== undefined){
if(typeof data9.section !== "string"){
const err21 = {instancePath:instancePath+"/ui/section",schemaPath:"#/$defs/ui/properties/section/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err21];
}
else {
vErrors.push(err21);
}
errors++;
}
}
if(data9.subSection !== undefined){
if(typeof data9.subSection !== "string"){
const err22 = {instancePath:instancePath+"/ui/subSection",schemaPath:"#/$defs/ui/properties/subSection/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err22];
}
else {
vErrors.push(err22);
}
errors++;
}
}
if(data9.order !== undefined){
let data13 = data9.order;
if(!(((typeof data13 == "number") && (!(data13 % 1) && !isNaN(data13))) && (isFinite(data13)))){
const err23 = {instancePath:instancePath+"/ui/order",schemaPath:"#/$defs/ui/properties/order/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err23];
}
else {
vErrors.push(err23);
}
errors++;
}
}
if(data9.advanced !== undefined){
if(typeof data9.advanced !== "boolean"){
const err24 = {instancePath:instancePath+"/ui/advanced",schemaPath:"#/$defs/ui/properties/advanced/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err24];
}
else {
vErrors.push(err24);
}
errors++;
}
}
if(data9.helperText !== undefined){
if(typeof data9.helperText !== "string"){
const err25 = {instancePath:instancePath+"/ui/helperText",schemaPath:"#/$defs/ui/properties/helperText/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err25];
}
else {
vErrors.push(err25);
}
errors++;
}
}
if(data9.readOnly !== undefined){
if(typeof data9.readOnly !== "boolean"){
const err26 = {instancePath:instancePath+"/ui/readOnly",schemaPath:"#/$defs/ui/properties/readOnly/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err26];
}
else {
vErrors.push(err26);
}
errors++;
}
}
if(data9.listColumn !== undefined){
if(typeof data9.listColumn !== "boolean"){
const err27 = {instancePath:instancePath+"/ui/listColumn",schemaPath:"#/$defs/ui/properties/listColumn/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err27];
}
else {
vErrors.push(err27);
}
errors++;
}
}
if(data9.promoteToDeclare !== undefined){
if(typeof data9.promoteToDeclare !== "boolean"){
const err28 = {instancePath:instancePath+"/ui/promoteToDeclare",schemaPath:"#/$defs/ui/properties/promoteToDeclare/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err28];
}
else {
vErrors.push(err28);
}
errors++;
}
}
}
else {
const err29 = {instancePath:instancePath+"/ui",schemaPath:"#/$defs/ui/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err29];
}
else {
vErrors.push(err29);
}
errors++;
}
}
}
else {
const err30 = {instancePath,schemaPath:"#/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err30];
}
else {
vErrors.push(err30);
}
errors++;
}
validate24.errors = vErrors;
return errors === 0;
}
validate24.evaluated = {"props":true,"dynamicProps":false,"dynamicItems":false};

const schema46 = {"type":"object","required":["name","metricType","unit","source"],"additionalProperties":false,"properties":{"name":{"type":"string","pattern":"^[a-z][a-z0-9_.]{0,127}(?![\\s\\S])","description":"P11-1.1 - the same grammar as sm_metrics' metric_name CHECK (plan 011 P11-2.1/P11-2.2), so a package cannot load and then fail at declare."},"metricType":{"enum":["counter","gauge","histogram"]},"unit":{"type":"string","minLength":1,"maxLength":64,"description":"P11-1.1 - 1..64 characters, the sm_metrics unit CHECK."},"layer":{"enum":["package-baseline","user-extension"]},"when":{"$ref":"#/$defs/predicate"},"source":{"$ref":"#/$defs/source"},"defaultChart":{"type":"boolean","description":"OQ-7 (b): false keeps this metric out of the derived default dashboard layer; absent means true. The metric stays queryable."}},"allOf":[{"description":"P11-5.1 (OR-106): a count of matching events is a cumulative number the Data Plane synthesises, so the metric is a counter. A gauge or a histogram of it is refused at load.","if":{"required":["source"],"properties":{"source":{"type":"object","required":["extract"],"properties":{"extract":{"const":"count"}}}}},"then":{"properties":{"metricType":{"const":"counter"}}}}]};
const pattern5 = new RegExp("^[a-z][a-z0-9_.]{0,127}(?![\\s\\S])", "u");
const schema48 = {"type":"object","required":["kind","onUnavailable"],"description":"UI-D14 — transport mechanisms only, never a solution name. Canonical list lives here. P11-1.1 (M-Q4): a per-kind union, discriminated by `kind`: http_json is collected in v1 and has its own closed shape; prometheus, exec and jdbc_query are admitted but not collected (their branch is unchanged). P11-5.1 (OR-106, OR-107) splits solace_event out as the push path: broker-pushed events, attributed by a package-declared instanceKey, extracted as a number at a pointer or as a count of matching events.","oneOf":[{"$ref":"#/$defs/sourceHttpJson"},{"$ref":"#/$defs/sourceSolaceEvent"},{"$ref":"#/$defs/sourceUncollected"}]};
const schema49 = {"type":"object","additionalProperties":false,"required":["kind","path","onUnavailable"],"properties":{"kind":{"const":"http_json"},"path":{"type":"string","pattern":"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"},"port":{"type":"integer","minimum":1,"maximum":65535,"description":"The CONTAINER port; the host side comes from the declare body's metricsPorts (M-Q14). Absent: the metric is not collectable (OQ-24 (b))."},"pointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])","description":"A JSON-pointer-shaped path into the response body; no `.` or `..` segment. Absent: the whole body is the number."},"queryParams":{"type":"object","maxProperties":8,"propertyNames":{"pattern":"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"},"additionalProperties":{"type":"string","pattern":"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"},"description":"M-Q4: a closed grammar for keys AND values; no encoding, so nothing is ever interpolated into a URL unescaped."},"intervalSeconds":{"type":"integer","minimum":10,"maximum":3600,"description":"OQ-9 (a); the Data Plane default is 30."},"onUnavailable":{"enum":["degrade","error"]}}};
const schema50 = {"type":"object","additionalProperties":false,"required":["kind","topicPattern","instanceKey","onUnavailable"],"description":"P11-5.1 (OR-106, OR-107): the push path. The Data Plane holds ONE broker connection per (organization, environment) and one subscription per distinct topicPattern, never one per instance; each event it is delivered names its instance in the payload at instanceKey.pointer, matched exactly and case-sensitively against the instance's own value of the config field instanceKey.configField (gate 16: never a secret). Nothing is substituted into a topic. extract=value reads a finite number at valuePointer; extract=count counts the events that pass eventFilter (OR-106), a counter the Data Plane synthesises and treats as an inferred reset when it restarts. I1: it reads events the MI already emits.","properties":{"kind":{"const":"solace_event"},"topicPattern":{"type":"string","pattern":"^(([A-Za-z0-9_.-]{1,64}|\\*)/){0,15}[A-Za-z0-9_.-]{1,64}(?![\\s\\S])","description":"Levels joined by /, each a literal or * (exactly one level). I6: the LAST level is a literal - no >, no *, no substitution token - so a subscription names its states and an event of another state (a start event may carry a plaintext broker password) is never delivered to the Data Plane. No #, no +, no empty level."},"eventFilter":{"type":"object","additionalProperties":false,"required":["pointer","equals"],"properties":{"pointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])"},"equals":{"type":"string","pattern":"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"}},"description":"An event counts only when the value at pointer, read as text, equals this."},"extract":{"enum":["value","count"],"description":"Absent means value. value: valuePointer is required. count (OR-106): valuePointer is optional and ignored (the Data Plane counts the events that pass eventFilter), and the metric must be a counter."},"valuePointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])","description":"A JSON-pointer-shaped path to a finite number in the event body; no . or .. segment."},"instanceKey":{"type":"object","additionalProperties":false,"required":["configField","pointer"],"properties":{"configField":{"type":"string","pattern":"^[A-Za-z0-9_.-]{1,256}(?![\\s\\S])","description":"The key of a field of THIS component whose configured value identifies the instance in its events (gate 16: it must exist and may not be a secret)."},"pointer":{"type":"string","pattern":"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])","description":"Where the event body carries that value (File Mesh: /header/adapterID)."}}},"onUnavailable":{"enum":["degrade","error"]}},"if":{"required":["extract"],"properties":{"extract":{"const":"count"}}},"else":{"required":["valuePointer"]}};
const schema51 = {"type":"object","additionalProperties":false,"required":["kind","onUnavailable"],"description":"Admitted, not collected in v1. Keeps every property `$defs/source` carried before P11-1.1, unchanged: queryParams exists only on http_json.","properties":{"kind":{"enum":["prometheus","exec","jdbc_query"]},"path":{"type":"string"},"port":{"type":"integer"},"pointer":{"type":"string"},"topicPattern":{"type":"string"},"query":{"type":"string"},"command":{"type":"string"},"onUnavailable":{"enum":["degrade","error"]}}};
const pattern6 = new RegExp("^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])", "u");
const pattern7 = new RegExp("^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])", "u");
const pattern8 = new RegExp("^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])", "u");
const pattern10 = new RegExp("^(([A-Za-z0-9_.-]{1,64}|\\*)/){0,15}[A-Za-z0-9_.-]{1,64}(?![\\s\\S])", "u");
const pattern11 = new RegExp("^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])", "u");
const pattern14 = new RegExp("^[A-Za-z0-9_.-]{1,256}(?![\\s\\S])", "u");

function validate27(data, {instancePath="", parentData, parentDataProperty, rootData=data, dynamicAnchors={}}={}){
let vErrors = null;
let errors = 0;
const evaluated0 = validate27.evaluated;
if(evaluated0.dynamicProps){
evaluated0.props = undefined;
}
if(evaluated0.dynamicItems){
evaluated0.items = undefined;
}
const _errs1 = errors;
let valid0 = false;
let passing0 = null;
const _errs2 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.kind === undefined){
const err0 = {instancePath,schemaPath:"#/$defs/sourceHttpJson/required",keyword:"required",params:{missingProperty: "kind"},message:"must have required property '"+"kind"+"'"};
if(vErrors === null){
vErrors = [err0];
}
else {
vErrors.push(err0);
}
errors++;
}
if(data.path === undefined){
const err1 = {instancePath,schemaPath:"#/$defs/sourceHttpJson/required",keyword:"required",params:{missingProperty: "path"},message:"must have required property '"+"path"+"'"};
if(vErrors === null){
vErrors = [err1];
}
else {
vErrors.push(err1);
}
errors++;
}
if(data.onUnavailable === undefined){
const err2 = {instancePath,schemaPath:"#/$defs/sourceHttpJson/required",keyword:"required",params:{missingProperty: "onUnavailable"},message:"must have required property '"+"onUnavailable"+"'"};
if(vErrors === null){
vErrors = [err2];
}
else {
vErrors.push(err2);
}
errors++;
}
for(const key0 in data){
if(!(((((((key0 === "kind") || (key0 === "path")) || (key0 === "port")) || (key0 === "pointer")) || (key0 === "queryParams")) || (key0 === "intervalSeconds")) || (key0 === "onUnavailable"))){
const err3 = {instancePath,schemaPath:"#/$defs/sourceHttpJson/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key0},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err3];
}
else {
vErrors.push(err3);
}
errors++;
}
}
if(data.kind !== undefined){
if("http_json" !== data.kind){
const err4 = {instancePath:instancePath+"/kind",schemaPath:"#/$defs/sourceHttpJson/properties/kind/const",keyword:"const",params:{allowedValue: "http_json"},message:"must be equal to constant"};
if(vErrors === null){
vErrors = [err4];
}
else {
vErrors.push(err4);
}
errors++;
}
}
if(data.path !== undefined){
let data1 = data.path;
if(typeof data1 === "string"){
if(!pattern6.test(data1)){
const err5 = {instancePath:instancePath+"/path",schemaPath:"#/$defs/sourceHttpJson/properties/path/pattern",keyword:"pattern",params:{pattern: "^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"},message:"must match pattern \""+"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err5];
}
else {
vErrors.push(err5);
}
errors++;
}
}
else {
const err6 = {instancePath:instancePath+"/path",schemaPath:"#/$defs/sourceHttpJson/properties/path/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err6];
}
else {
vErrors.push(err6);
}
errors++;
}
}
if(data.port !== undefined){
let data2 = data.port;
if(!(((typeof data2 == "number") && (!(data2 % 1) && !isNaN(data2))) && (isFinite(data2)))){
const err7 = {instancePath:instancePath+"/port",schemaPath:"#/$defs/sourceHttpJson/properties/port/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err7];
}
else {
vErrors.push(err7);
}
errors++;
}
if((typeof data2 == "number") && (isFinite(data2))){
if(data2 > 65535 || isNaN(data2)){
const err8 = {instancePath:instancePath+"/port",schemaPath:"#/$defs/sourceHttpJson/properties/port/maximum",keyword:"maximum",params:{comparison: "<=", limit: 65535},message:"must be <= 65535"};
if(vErrors === null){
vErrors = [err8];
}
else {
vErrors.push(err8);
}
errors++;
}
if(data2 < 1 || isNaN(data2)){
const err9 = {instancePath:instancePath+"/port",schemaPath:"#/$defs/sourceHttpJson/properties/port/minimum",keyword:"minimum",params:{comparison: ">=", limit: 1},message:"must be >= 1"};
if(vErrors === null){
vErrors = [err9];
}
else {
vErrors.push(err9);
}
errors++;
}
}
}
if(data.pointer !== undefined){
let data3 = data.pointer;
if(typeof data3 === "string"){
if(!pattern7.test(data3)){
const err10 = {instancePath:instancePath+"/pointer",schemaPath:"#/$defs/sourceHttpJson/properties/pointer/pattern",keyword:"pattern",params:{pattern: "^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])"},message:"must match pattern \""+"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err10];
}
else {
vErrors.push(err10);
}
errors++;
}
}
else {
const err11 = {instancePath:instancePath+"/pointer",schemaPath:"#/$defs/sourceHttpJson/properties/pointer/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err11];
}
else {
vErrors.push(err11);
}
errors++;
}
}
if(data.queryParams !== undefined){
let data4 = data.queryParams;
if(data4 && typeof data4 == "object" && !Array.isArray(data4)){
if(Object.keys(data4).length > 8){
const err12 = {instancePath:instancePath+"/queryParams",schemaPath:"#/$defs/sourceHttpJson/properties/queryParams/maxProperties",keyword:"maxProperties",params:{limit: 8},message:"must NOT have more than 8 properties"};
if(vErrors === null){
vErrors = [err12];
}
else {
vErrors.push(err12);
}
errors++;
}
for(const key1 in data4){
const _errs15 = errors;
if(typeof key1 === "string"){
if(!pattern8.test(key1)){
const err13 = {instancePath:instancePath+"/queryParams",schemaPath:"#/$defs/sourceHttpJson/properties/queryParams/propertyNames/pattern",keyword:"pattern",params:{pattern: "^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"},message:"must match pattern \""+"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"+"\"",propertyName:key1};
if(vErrors === null){
vErrors = [err13];
}
else {
vErrors.push(err13);
}
errors++;
}
}
var valid3 = _errs15 === errors;
if(!valid3){
const err14 = {instancePath:instancePath+"/queryParams",schemaPath:"#/$defs/sourceHttpJson/properties/queryParams/propertyNames",keyword:"propertyNames",params:{propertyName: key1},message:"property name must be valid"};
if(vErrors === null){
vErrors = [err14];
}
else {
vErrors.push(err14);
}
errors++;
}
}
for(const key2 in data4){
let data5 = data4[key2];
if(typeof data5 === "string"){
if(!pattern8.test(data5)){
const err15 = {instancePath:instancePath+"/queryParams/" + key2.replace(/~/g, "~0").replace(/\//g, "~1"),schemaPath:"#/$defs/sourceHttpJson/properties/queryParams/additionalProperties/pattern",keyword:"pattern",params:{pattern: "^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"},message:"must match pattern \""+"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err15];
}
else {
vErrors.push(err15);
}
errors++;
}
}
else {
const err16 = {instancePath:instancePath+"/queryParams/" + key2.replace(/~/g, "~0").replace(/\//g, "~1"),schemaPath:"#/$defs/sourceHttpJson/properties/queryParams/additionalProperties/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err16];
}
else {
vErrors.push(err16);
}
errors++;
}
}
}
else {
const err17 = {instancePath:instancePath+"/queryParams",schemaPath:"#/$defs/sourceHttpJson/properties/queryParams/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err17];
}
else {
vErrors.push(err17);
}
errors++;
}
}
if(data.intervalSeconds !== undefined){
let data6 = data.intervalSeconds;
if(!(((typeof data6 == "number") && (!(data6 % 1) && !isNaN(data6))) && (isFinite(data6)))){
const err18 = {instancePath:instancePath+"/intervalSeconds",schemaPath:"#/$defs/sourceHttpJson/properties/intervalSeconds/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err18];
}
else {
vErrors.push(err18);
}
errors++;
}
if((typeof data6 == "number") && (isFinite(data6))){
if(data6 > 3600 || isNaN(data6)){
const err19 = {instancePath:instancePath+"/intervalSeconds",schemaPath:"#/$defs/sourceHttpJson/properties/intervalSeconds/maximum",keyword:"maximum",params:{comparison: "<=", limit: 3600},message:"must be <= 3600"};
if(vErrors === null){
vErrors = [err19];
}
else {
vErrors.push(err19);
}
errors++;
}
if(data6 < 10 || isNaN(data6)){
const err20 = {instancePath:instancePath+"/intervalSeconds",schemaPath:"#/$defs/sourceHttpJson/properties/intervalSeconds/minimum",keyword:"minimum",params:{comparison: ">=", limit: 10},message:"must be >= 10"};
if(vErrors === null){
vErrors = [err20];
}
else {
vErrors.push(err20);
}
errors++;
}
}
}
if(data.onUnavailable !== undefined){
let data7 = data.onUnavailable;
if(!((data7 === "degrade") || (data7 === "error"))){
const err21 = {instancePath:instancePath+"/onUnavailable",schemaPath:"#/$defs/sourceHttpJson/properties/onUnavailable/enum",keyword:"enum",params:{allowedValues: schema49.properties.onUnavailable.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err21];
}
else {
vErrors.push(err21);
}
errors++;
}
}
}
else {
const err22 = {instancePath,schemaPath:"#/$defs/sourceHttpJson/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err22];
}
else {
vErrors.push(err22);
}
errors++;
}
var _valid0 = _errs2 === errors;
if(_valid0){
valid0 = true;
passing0 = 0;
var props0 = true;
}
const _errs22 = errors;
const _errs25 = errors;
let valid6 = true;
const _errs26 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing0;
if((data.extract === undefined) && (missing0 = "extract")){
const err23 = {};
if(vErrors === null){
vErrors = [err23];
}
else {
vErrors.push(err23);
}
errors++;
}
else {
if(data.extract !== undefined){
if("count" !== data.extract){
const err24 = {};
if(vErrors === null){
vErrors = [err24];
}
else {
vErrors.push(err24);
}
errors++;
}
}
}
}
var _valid1 = _errs26 === errors;
errors = _errs25;
if(vErrors !== null){
if(_errs25){
vErrors.length = _errs25;
}
else {
vErrors = null;
}
}
if(!_valid1){
const _errs28 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.valuePointer === undefined){
const err25 = {instancePath,schemaPath:"#/$defs/sourceSolaceEvent/else/required",keyword:"required",params:{missingProperty: "valuePointer"},message:"must have required property '"+"valuePointer"+"'"};
if(vErrors === null){
vErrors = [err25];
}
else {
vErrors.push(err25);
}
errors++;
}
}
var _valid1 = _errs28 === errors;
valid6 = _valid1;
}
if(!valid6){
const err26 = {instancePath,schemaPath:"#/$defs/sourceSolaceEvent/if",keyword:"if",params:{failingKeyword: "else"},message:"must match \"else\" schema"};
if(vErrors === null){
vErrors = [err26];
}
else {
vErrors.push(err26);
}
errors++;
}
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.kind === undefined){
const err27 = {instancePath,schemaPath:"#/$defs/sourceSolaceEvent/required",keyword:"required",params:{missingProperty: "kind"},message:"must have required property '"+"kind"+"'"};
if(vErrors === null){
vErrors = [err27];
}
else {
vErrors.push(err27);
}
errors++;
}
if(data.topicPattern === undefined){
const err28 = {instancePath,schemaPath:"#/$defs/sourceSolaceEvent/required",keyword:"required",params:{missingProperty: "topicPattern"},message:"must have required property '"+"topicPattern"+"'"};
if(vErrors === null){
vErrors = [err28];
}
else {
vErrors.push(err28);
}
errors++;
}
if(data.instanceKey === undefined){
const err29 = {instancePath,schemaPath:"#/$defs/sourceSolaceEvent/required",keyword:"required",params:{missingProperty: "instanceKey"},message:"must have required property '"+"instanceKey"+"'"};
if(vErrors === null){
vErrors = [err29];
}
else {
vErrors.push(err29);
}
errors++;
}
if(data.onUnavailable === undefined){
const err30 = {instancePath,schemaPath:"#/$defs/sourceSolaceEvent/required",keyword:"required",params:{missingProperty: "onUnavailable"},message:"must have required property '"+"onUnavailable"+"'"};
if(vErrors === null){
vErrors = [err30];
}
else {
vErrors.push(err30);
}
errors++;
}
for(const key3 in data){
if(!(((((((key3 === "kind") || (key3 === "topicPattern")) || (key3 === "eventFilter")) || (key3 === "extract")) || (key3 === "valuePointer")) || (key3 === "instanceKey")) || (key3 === "onUnavailable"))){
const err31 = {instancePath,schemaPath:"#/$defs/sourceSolaceEvent/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key3},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err31];
}
else {
vErrors.push(err31);
}
errors++;
}
}
if(data.kind !== undefined){
if("solace_event" !== data.kind){
const err32 = {instancePath:instancePath+"/kind",schemaPath:"#/$defs/sourceSolaceEvent/properties/kind/const",keyword:"const",params:{allowedValue: "solace_event"},message:"must be equal to constant"};
if(vErrors === null){
vErrors = [err32];
}
else {
vErrors.push(err32);
}
errors++;
}
}
if(data.topicPattern !== undefined){
let data10 = data.topicPattern;
if(typeof data10 === "string"){
if(!pattern10.test(data10)){
const err33 = {instancePath:instancePath+"/topicPattern",schemaPath:"#/$defs/sourceSolaceEvent/properties/topicPattern/pattern",keyword:"pattern",params:{pattern: "^(([A-Za-z0-9_.-]{1,64}|\\*)/){0,15}[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"},message:"must match pattern \""+"^(([A-Za-z0-9_.-]{1,64}|\\*)/){0,15}[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err33];
}
else {
vErrors.push(err33);
}
errors++;
}
}
else {
const err34 = {instancePath:instancePath+"/topicPattern",schemaPath:"#/$defs/sourceSolaceEvent/properties/topicPattern/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err34];
}
else {
vErrors.push(err34);
}
errors++;
}
}
if(data.eventFilter !== undefined){
let data11 = data.eventFilter;
if(data11 && typeof data11 == "object" && !Array.isArray(data11)){
if(data11.pointer === undefined){
const err35 = {instancePath:instancePath+"/eventFilter",schemaPath:"#/$defs/sourceSolaceEvent/properties/eventFilter/required",keyword:"required",params:{missingProperty: "pointer"},message:"must have required property '"+"pointer"+"'"};
if(vErrors === null){
vErrors = [err35];
}
else {
vErrors.push(err35);
}
errors++;
}
if(data11.equals === undefined){
const err36 = {instancePath:instancePath+"/eventFilter",schemaPath:"#/$defs/sourceSolaceEvent/properties/eventFilter/required",keyword:"required",params:{missingProperty: "equals"},message:"must have required property '"+"equals"+"'"};
if(vErrors === null){
vErrors = [err36];
}
else {
vErrors.push(err36);
}
errors++;
}
for(const key4 in data11){
if(!((key4 === "pointer") || (key4 === "equals"))){
const err37 = {instancePath:instancePath+"/eventFilter",schemaPath:"#/$defs/sourceSolaceEvent/properties/eventFilter/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key4},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err37];
}
else {
vErrors.push(err37);
}
errors++;
}
}
if(data11.pointer !== undefined){
let data12 = data11.pointer;
if(typeof data12 === "string"){
if(!pattern11.test(data12)){
const err38 = {instancePath:instancePath+"/eventFilter/pointer",schemaPath:"#/$defs/sourceSolaceEvent/properties/eventFilter/properties/pointer/pattern",keyword:"pattern",params:{pattern: "^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])"},message:"must match pattern \""+"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err38];
}
else {
vErrors.push(err38);
}
errors++;
}
}
else {
const err39 = {instancePath:instancePath+"/eventFilter/pointer",schemaPath:"#/$defs/sourceSolaceEvent/properties/eventFilter/properties/pointer/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err39];
}
else {
vErrors.push(err39);
}
errors++;
}
}
if(data11.equals !== undefined){
let data13 = data11.equals;
if(typeof data13 === "string"){
if(!pattern8.test(data13)){
const err40 = {instancePath:instancePath+"/eventFilter/equals",schemaPath:"#/$defs/sourceSolaceEvent/properties/eventFilter/properties/equals/pattern",keyword:"pattern",params:{pattern: "^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"},message:"must match pattern \""+"^[A-Za-z0-9_.-]{1,64}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err40];
}
else {
vErrors.push(err40);
}
errors++;
}
}
else {
const err41 = {instancePath:instancePath+"/eventFilter/equals",schemaPath:"#/$defs/sourceSolaceEvent/properties/eventFilter/properties/equals/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err41];
}
else {
vErrors.push(err41);
}
errors++;
}
}
}
else {
const err42 = {instancePath:instancePath+"/eventFilter",schemaPath:"#/$defs/sourceSolaceEvent/properties/eventFilter/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err42];
}
else {
vErrors.push(err42);
}
errors++;
}
}
if(data.extract !== undefined){
let data14 = data.extract;
if(!((data14 === "value") || (data14 === "count"))){
const err43 = {instancePath:instancePath+"/extract",schemaPath:"#/$defs/sourceSolaceEvent/properties/extract/enum",keyword:"enum",params:{allowedValues: schema50.properties.extract.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err43];
}
else {
vErrors.push(err43);
}
errors++;
}
}
if(data.valuePointer !== undefined){
let data15 = data.valuePointer;
if(typeof data15 === "string"){
if(!pattern11.test(data15)){
const err44 = {instancePath:instancePath+"/valuePointer",schemaPath:"#/$defs/sourceSolaceEvent/properties/valuePointer/pattern",keyword:"pattern",params:{pattern: "^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])"},message:"must match pattern \""+"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err44];
}
else {
vErrors.push(err44);
}
errors++;
}
}
else {
const err45 = {instancePath:instancePath+"/valuePointer",schemaPath:"#/$defs/sourceSolaceEvent/properties/valuePointer/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err45];
}
else {
vErrors.push(err45);
}
errors++;
}
}
if(data.instanceKey !== undefined){
let data16 = data.instanceKey;
if(data16 && typeof data16 == "object" && !Array.isArray(data16)){
if(data16.configField === undefined){
const err46 = {instancePath:instancePath+"/instanceKey",schemaPath:"#/$defs/sourceSolaceEvent/properties/instanceKey/required",keyword:"required",params:{missingProperty: "configField"},message:"must have required property '"+"configField"+"'"};
if(vErrors === null){
vErrors = [err46];
}
else {
vErrors.push(err46);
}
errors++;
}
if(data16.pointer === undefined){
const err47 = {instancePath:instancePath+"/instanceKey",schemaPath:"#/$defs/sourceSolaceEvent/properties/instanceKey/required",keyword:"required",params:{missingProperty: "pointer"},message:"must have required property '"+"pointer"+"'"};
if(vErrors === null){
vErrors = [err47];
}
else {
vErrors.push(err47);
}
errors++;
}
for(const key5 in data16){
if(!((key5 === "configField") || (key5 === "pointer"))){
const err48 = {instancePath:instancePath+"/instanceKey",schemaPath:"#/$defs/sourceSolaceEvent/properties/instanceKey/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key5},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err48];
}
else {
vErrors.push(err48);
}
errors++;
}
}
if(data16.configField !== undefined){
let data17 = data16.configField;
if(typeof data17 === "string"){
if(!pattern14.test(data17)){
const err49 = {instancePath:instancePath+"/instanceKey/configField",schemaPath:"#/$defs/sourceSolaceEvent/properties/instanceKey/properties/configField/pattern",keyword:"pattern",params:{pattern: "^[A-Za-z0-9_.-]{1,256}(?![\\s\\S])"},message:"must match pattern \""+"^[A-Za-z0-9_.-]{1,256}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err49];
}
else {
vErrors.push(err49);
}
errors++;
}
}
else {
const err50 = {instancePath:instancePath+"/instanceKey/configField",schemaPath:"#/$defs/sourceSolaceEvent/properties/instanceKey/properties/configField/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err50];
}
else {
vErrors.push(err50);
}
errors++;
}
}
if(data16.pointer !== undefined){
let data18 = data16.pointer;
if(typeof data18 === "string"){
if(!pattern11.test(data18)){
const err51 = {instancePath:instancePath+"/instanceKey/pointer",schemaPath:"#/$defs/sourceSolaceEvent/properties/instanceKey/properties/pointer/pattern",keyword:"pattern",params:{pattern: "^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])"},message:"must match pattern \""+"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){1,8}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err51];
}
else {
vErrors.push(err51);
}
errors++;
}
}
else {
const err52 = {instancePath:instancePath+"/instanceKey/pointer",schemaPath:"#/$defs/sourceSolaceEvent/properties/instanceKey/properties/pointer/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err52];
}
else {
vErrors.push(err52);
}
errors++;
}
}
}
else {
const err53 = {instancePath:instancePath+"/instanceKey",schemaPath:"#/$defs/sourceSolaceEvent/properties/instanceKey/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err53];
}
else {
vErrors.push(err53);
}
errors++;
}
}
if(data.onUnavailable !== undefined){
let data19 = data.onUnavailable;
if(!((data19 === "degrade") || (data19 === "error"))){
const err54 = {instancePath:instancePath+"/onUnavailable",schemaPath:"#/$defs/sourceSolaceEvent/properties/onUnavailable/enum",keyword:"enum",params:{allowedValues: schema50.properties.onUnavailable.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err54];
}
else {
vErrors.push(err54);
}
errors++;
}
}
}
else {
const err55 = {instancePath,schemaPath:"#/$defs/sourceSolaceEvent/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err55];
}
else {
vErrors.push(err55);
}
errors++;
}
var _valid0 = _errs22 === errors;
if(_valid0 && valid0){
valid0 = false;
passing0 = [passing0, 1];
}
else {
if(_valid0){
valid0 = true;
passing0 = 1;
if(props0 !== true){
props0 = true;
}
}
const _errs51 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.kind === undefined){
const err56 = {instancePath,schemaPath:"#/$defs/sourceUncollected/required",keyword:"required",params:{missingProperty: "kind"},message:"must have required property '"+"kind"+"'"};
if(vErrors === null){
vErrors = [err56];
}
else {
vErrors.push(err56);
}
errors++;
}
if(data.onUnavailable === undefined){
const err57 = {instancePath,schemaPath:"#/$defs/sourceUncollected/required",keyword:"required",params:{missingProperty: "onUnavailable"},message:"must have required property '"+"onUnavailable"+"'"};
if(vErrors === null){
vErrors = [err57];
}
else {
vErrors.push(err57);
}
errors++;
}
for(const key6 in data){
if(!((((((((key6 === "kind") || (key6 === "path")) || (key6 === "port")) || (key6 === "pointer")) || (key6 === "topicPattern")) || (key6 === "query")) || (key6 === "command")) || (key6 === "onUnavailable"))){
const err58 = {instancePath,schemaPath:"#/$defs/sourceUncollected/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key6},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err58];
}
else {
vErrors.push(err58);
}
errors++;
}
}
if(data.kind !== undefined){
let data20 = data.kind;
if(!(((data20 === "prometheus") || (data20 === "exec")) || (data20 === "jdbc_query"))){
const err59 = {instancePath:instancePath+"/kind",schemaPath:"#/$defs/sourceUncollected/properties/kind/enum",keyword:"enum",params:{allowedValues: schema51.properties.kind.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err59];
}
else {
vErrors.push(err59);
}
errors++;
}
}
if(data.path !== undefined){
if(typeof data.path !== "string"){
const err60 = {instancePath:instancePath+"/path",schemaPath:"#/$defs/sourceUncollected/properties/path/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err60];
}
else {
vErrors.push(err60);
}
errors++;
}
}
if(data.port !== undefined){
let data22 = data.port;
if(!(((typeof data22 == "number") && (!(data22 % 1) && !isNaN(data22))) && (isFinite(data22)))){
const err61 = {instancePath:instancePath+"/port",schemaPath:"#/$defs/sourceUncollected/properties/port/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err61];
}
else {
vErrors.push(err61);
}
errors++;
}
}
if(data.pointer !== undefined){
if(typeof data.pointer !== "string"){
const err62 = {instancePath:instancePath+"/pointer",schemaPath:"#/$defs/sourceUncollected/properties/pointer/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err62];
}
else {
vErrors.push(err62);
}
errors++;
}
}
if(data.topicPattern !== undefined){
if(typeof data.topicPattern !== "string"){
const err63 = {instancePath:instancePath+"/topicPattern",schemaPath:"#/$defs/sourceUncollected/properties/topicPattern/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err63];
}
else {
vErrors.push(err63);
}
errors++;
}
}
if(data.query !== undefined){
if(typeof data.query !== "string"){
const err64 = {instancePath:instancePath+"/query",schemaPath:"#/$defs/sourceUncollected/properties/query/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err64];
}
else {
vErrors.push(err64);
}
errors++;
}
}
if(data.command !== undefined){
if(typeof data.command !== "string"){
const err65 = {instancePath:instancePath+"/command",schemaPath:"#/$defs/sourceUncollected/properties/command/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err65];
}
else {
vErrors.push(err65);
}
errors++;
}
}
if(data.onUnavailable !== undefined){
let data27 = data.onUnavailable;
if(!((data27 === "degrade") || (data27 === "error"))){
const err66 = {instancePath:instancePath+"/onUnavailable",schemaPath:"#/$defs/sourceUncollected/properties/onUnavailable/enum",keyword:"enum",params:{allowedValues: schema51.properties.onUnavailable.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err66];
}
else {
vErrors.push(err66);
}
errors++;
}
}
}
else {
const err67 = {instancePath,schemaPath:"#/$defs/sourceUncollected/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err67];
}
else {
vErrors.push(err67);
}
errors++;
}
var _valid0 = _errs51 === errors;
if(_valid0 && valid0){
valid0 = false;
passing0 = [passing0, 2];
}
else {
if(_valid0){
valid0 = true;
passing0 = 2;
if(props0 !== true){
props0 = true;
}
}
}
}
if(!valid0){
const err68 = {instancePath,schemaPath:"#/oneOf",keyword:"oneOf",params:{passingSchemas: passing0},message:"must match exactly one schema in oneOf"};
if(vErrors === null){
vErrors = [err68];
}
else {
vErrors.push(err68);
}
errors++;
}
else {
errors = _errs1;
if(vErrors !== null){
if(_errs1){
vErrors.length = _errs1;
}
else {
vErrors = null;
}
}
}
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.kind === undefined){
const err69 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "kind"},message:"must have required property '"+"kind"+"'"};
if(vErrors === null){
vErrors = [err69];
}
else {
vErrors.push(err69);
}
errors++;
}
if(data.onUnavailable === undefined){
const err70 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "onUnavailable"},message:"must have required property '"+"onUnavailable"+"'"};
if(vErrors === null){
vErrors = [err70];
}
else {
vErrors.push(err70);
}
errors++;
}
}
else {
const err71 = {instancePath,schemaPath:"#/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err71];
}
else {
vErrors.push(err71);
}
errors++;
}
validate27.errors = vErrors;
evaluated0.props = props0;
return errors === 0;
}
validate27.evaluated = {"dynamicProps":true,"dynamicItems":false};


function validate26(data, {instancePath="", parentData, parentDataProperty, rootData=data, dynamicAnchors={}}={}){
let vErrors = null;
let errors = 0;
const evaluated0 = validate26.evaluated;
if(evaluated0.dynamicProps){
evaluated0.props = undefined;
}
if(evaluated0.dynamicItems){
evaluated0.items = undefined;
}
const _errs2 = errors;
let valid1 = true;
const _errs3 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing0;
if((data.source === undefined) && (missing0 = "source")){
const err0 = {};
if(vErrors === null){
vErrors = [err0];
}
else {
vErrors.push(err0);
}
errors++;
}
else {
if(data.source !== undefined){
let data0 = data.source;
const _errs4 = errors;
if(errors === _errs4){
if(data0 && typeof data0 == "object" && !Array.isArray(data0)){
let missing1;
if((data0.extract === undefined) && (missing1 = "extract")){
const err1 = {};
if(vErrors === null){
vErrors = [err1];
}
else {
vErrors.push(err1);
}
errors++;
}
else {
if(data0.extract !== undefined){
if("count" !== data0.extract){
const err2 = {};
if(vErrors === null){
vErrors = [err2];
}
else {
vErrors.push(err2);
}
errors++;
}
}
}
}
else {
const err3 = {};
if(vErrors === null){
vErrors = [err3];
}
else {
vErrors.push(err3);
}
errors++;
}
}
}
}
}
var _valid0 = _errs3 === errors;
errors = _errs2;
if(vErrors !== null){
if(_errs2){
vErrors.length = _errs2;
}
else {
vErrors = null;
}
}
if(_valid0){
const _errs7 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.metricType !== undefined){
if("counter" !== data.metricType){
const err4 = {instancePath:instancePath+"/metricType",schemaPath:"#/allOf/0/then/properties/metricType/const",keyword:"const",params:{allowedValue: "counter"},message:"must be equal to constant"};
if(vErrors === null){
vErrors = [err4];
}
else {
vErrors.push(err4);
}
errors++;
}
}
}
var _valid0 = _errs7 === errors;
valid1 = _valid0;
if(valid1){
var props0 = {};
props0.metricType = true;
props0.source = true;
}
}
if(!valid1){
const err5 = {instancePath,schemaPath:"#/allOf/0/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err5];
}
else {
vErrors.push(err5);
}
errors++;
}
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.name === undefined){
const err6 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "name"},message:"must have required property '"+"name"+"'"};
if(vErrors === null){
vErrors = [err6];
}
else {
vErrors.push(err6);
}
errors++;
}
if(data.metricType === undefined){
const err7 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "metricType"},message:"must have required property '"+"metricType"+"'"};
if(vErrors === null){
vErrors = [err7];
}
else {
vErrors.push(err7);
}
errors++;
}
if(data.unit === undefined){
const err8 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "unit"},message:"must have required property '"+"unit"+"'"};
if(vErrors === null){
vErrors = [err8];
}
else {
vErrors.push(err8);
}
errors++;
}
if(data.source === undefined){
const err9 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "source"},message:"must have required property '"+"source"+"'"};
if(vErrors === null){
vErrors = [err9];
}
else {
vErrors.push(err9);
}
errors++;
}
for(const key0 in data){
if(!(((((((key0 === "name") || (key0 === "metricType")) || (key0 === "unit")) || (key0 === "layer")) || (key0 === "when")) || (key0 === "source")) || (key0 === "defaultChart"))){
const err10 = {instancePath,schemaPath:"#/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key0},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err10];
}
else {
vErrors.push(err10);
}
errors++;
}
}
if(data.name !== undefined){
let data3 = data.name;
if(typeof data3 === "string"){
if(!pattern5.test(data3)){
const err11 = {instancePath:instancePath+"/name",schemaPath:"#/properties/name/pattern",keyword:"pattern",params:{pattern: "^[a-z][a-z0-9_.]{0,127}(?![\\s\\S])"},message:"must match pattern \""+"^[a-z][a-z0-9_.]{0,127}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err11];
}
else {
vErrors.push(err11);
}
errors++;
}
}
else {
const err12 = {instancePath:instancePath+"/name",schemaPath:"#/properties/name/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err12];
}
else {
vErrors.push(err12);
}
errors++;
}
}
if(data.metricType !== undefined){
let data4 = data.metricType;
if(!(((data4 === "counter") || (data4 === "gauge")) || (data4 === "histogram"))){
const err13 = {instancePath:instancePath+"/metricType",schemaPath:"#/properties/metricType/enum",keyword:"enum",params:{allowedValues: schema46.properties.metricType.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err13];
}
else {
vErrors.push(err13);
}
errors++;
}
}
if(data.unit !== undefined){
let data5 = data.unit;
if(typeof data5 === "string"){
if(func1(data5) > 64){
const err14 = {instancePath:instancePath+"/unit",schemaPath:"#/properties/unit/maxLength",keyword:"maxLength",params:{limit: 64},message:"must NOT have more than 64 characters"};
if(vErrors === null){
vErrors = [err14];
}
else {
vErrors.push(err14);
}
errors++;
}
if(func1(data5) < 1){
const err15 = {instancePath:instancePath+"/unit",schemaPath:"#/properties/unit/minLength",keyword:"minLength",params:{limit: 1},message:"must NOT have fewer than 1 characters"};
if(vErrors === null){
vErrors = [err15];
}
else {
vErrors.push(err15);
}
errors++;
}
}
else {
const err16 = {instancePath:instancePath+"/unit",schemaPath:"#/properties/unit/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err16];
}
else {
vErrors.push(err16);
}
errors++;
}
}
if(data.layer !== undefined){
let data6 = data.layer;
if(!((data6 === "package-baseline") || (data6 === "user-extension"))){
const err17 = {instancePath:instancePath+"/layer",schemaPath:"#/properties/layer/enum",keyword:"enum",params:{allowedValues: schema46.properties.layer.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err17];
}
else {
vErrors.push(err17);
}
errors++;
}
}
if(data.when !== undefined){
let data7 = data.when;
if(data7 && typeof data7 == "object" && !Array.isArray(data7)){
if(Object.keys(data7).length < 1){
const err18 = {instancePath:instancePath+"/when",schemaPath:"#/$defs/predicate/minProperties",keyword:"minProperties",params:{limit: 1},message:"must NOT have fewer than 1 properties"};
if(vErrors === null){
vErrors = [err18];
}
else {
vErrors.push(err18);
}
errors++;
}
for(const key1 in data7){
let data8 = data7[key1];
if(((typeof data8 !== "string") && (!((typeof data8 == "number") && (isFinite(data8))))) && (typeof data8 !== "boolean")){
const err19 = {instancePath:instancePath+"/when/" + key1.replace(/~/g, "~0").replace(/\//g, "~1"),schemaPath:"#/$defs/predicate/additionalProperties/type",keyword:"type",params:{type: schema40.additionalProperties.type},message:"must be string,number,boolean"};
if(vErrors === null){
vErrors = [err19];
}
else {
vErrors.push(err19);
}
errors++;
}
}
}
else {
const err20 = {instancePath:instancePath+"/when",schemaPath:"#/$defs/predicate/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err20];
}
else {
vErrors.push(err20);
}
errors++;
}
}
if(data.source !== undefined){
if(!(validate27(data.source, {instancePath:instancePath+"/source",parentData:data,parentDataProperty:"source",rootData,dynamicAnchors}))){
vErrors = vErrors === null ? validate27.errors : vErrors.concat(validate27.errors);
errors = vErrors.length;
}
}
if(data.defaultChart !== undefined){
if(typeof data.defaultChart !== "boolean"){
const err21 = {instancePath:instancePath+"/defaultChart",schemaPath:"#/properties/defaultChart/type",keyword:"type",params:{type: "boolean"},message:"must be boolean"};
if(vErrors === null){
vErrors = [err21];
}
else {
vErrors.push(err21);
}
errors++;
}
}
}
else {
const err22 = {instancePath,schemaPath:"#/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err22];
}
else {
vErrors.push(err22);
}
errors++;
}
validate26.errors = vErrors;
return errors === 0;
}
validate26.evaluated = {"props":true,"dynamicProps":false,"dynamicItems":false};

const pattern19 = new RegExp("^[a-z][a-z0-9.-]{0,127}(?![\\s\\S])", "u");

function validate21(data, {instancePath="", parentData, parentDataProperty, rootData=data, dynamicAnchors={}}={}){
let vErrors = null;
let errors = 0;
const evaluated0 = validate21.evaluated;
if(evaluated0.dynamicProps){
evaluated0.props = undefined;
}
if(evaluated0.dynamicItems){
evaluated0.items = undefined;
}
const _errs1 = errors;
let valid0 = true;
const _errs2 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
let missing0;
if((data.logs === undefined) && (missing0 = "logs")){
const err0 = {};
if(vErrors === null){
vErrors = [err0];
}
else {
vErrors.push(err0);
}
errors++;
}
else {
if(data.logs !== undefined){
let data0 = data.logs;
const _errs3 = errors;
if(errors === _errs3){
if(data0 && typeof data0 == "object" && !Array.isArray(data0)){
let missing1;
if((data0.loggers === undefined) && (missing1 = "loggers")){
const err1 = {};
if(vErrors === null){
vErrors = [err1];
}
else {
vErrors.push(err1);
}
errors++;
}
}
else {
const err2 = {};
if(vErrors === null){
vErrors = [err2];
}
else {
vErrors.push(err2);
}
errors++;
}
}
}
}
}
var _valid0 = _errs2 === errors;
errors = _errs1;
if(vErrors !== null){
if(_errs1){
vErrors.length = _errs1;
}
else {
vErrors = null;
}
}
if(_valid0){
const _errs5 = errors;
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.loggerPrefix === undefined){
const err3 = {instancePath,schemaPath:"#/then/required",keyword:"required",params:{missingProperty: "loggerPrefix"},message:"must have required property '"+"loggerPrefix"+"'"};
if(vErrors === null){
vErrors = [err3];
}
else {
vErrors.push(err3);
}
errors++;
}
}
var _valid0 = _errs5 === errors;
valid0 = _valid0;
}
if(!valid0){
const err4 = {instancePath,schemaPath:"#/if",keyword:"if",params:{failingKeyword: "then"},message:"must match \"then\" schema"};
if(vErrors === null){
vErrors = [err4];
}
else {
vErrors.push(err4);
}
errors++;
}
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.type === undefined){
const err5 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "type"},message:"must have required property '"+"type"+"'"};
if(vErrors === null){
vErrors = [err5];
}
else {
vErrors.push(err5);
}
errors++;
}
if(data.cardinality === undefined){
const err6 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "cardinality"},message:"must have required property '"+"cardinality"+"'"};
if(vErrors === null){
vErrors = [err6];
}
else {
vErrors.push(err6);
}
errors++;
}
if(data.configPath === undefined){
const err7 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "configPath"},message:"must have required property '"+"configPath"+"'"};
if(vErrors === null){
vErrors = [err7];
}
else {
vErrors.push(err7);
}
errors++;
}
for(const key0 in data){
if(!(func3.call(schema33.properties, key0))){
const err8 = {instancePath,schemaPath:"#/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key0},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err8];
}
else {
vErrors.push(err8);
}
errors++;
}
}
if(data.type !== undefined){
if(typeof data.type !== "string"){
const err9 = {instancePath:instancePath+"/type",schemaPath:"#/properties/type/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err9];
}
else {
vErrors.push(err9);
}
errors++;
}
}
if(data.version !== undefined){
let data2 = data.version;
if(Array.isArray(data2)){
if(data2.length < 1){
const err10 = {instancePath:instancePath+"/version",schemaPath:"#/properties/version/minItems",keyword:"minItems",params:{limit: 1},message:"must NOT have fewer than 1 items"};
if(vErrors === null){
vErrors = [err10];
}
else {
vErrors.push(err10);
}
errors++;
}
const len0 = data2.length;
for(let i0=0; i0<len0; i0++){
let data3 = data2[i0];
const _errs13 = errors;
let valid6 = false;
let passing0 = null;
const _errs14 = errors;
if(!(((typeof data3 == "number") && (!(data3 % 1) && !isNaN(data3))) && (isFinite(data3)))){
const err11 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/0/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err11];
}
else {
vErrors.push(err11);
}
errors++;
}
if((typeof data3 == "number") && (isFinite(data3))){
if(data3 < 1 || isNaN(data3)){
const err12 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/0/minimum",keyword:"minimum",params:{comparison: ">=", limit: 1},message:"must be >= 1"};
if(vErrors === null){
vErrors = [err12];
}
else {
vErrors.push(err12);
}
errors++;
}
}
var _valid1 = _errs14 === errors;
if(_valid1){
valid6 = true;
passing0 = 0;
}
const _errs16 = errors;
if(typeof data3 === "string"){
if(func1(data3) < 1){
const err13 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/1/minLength",keyword:"minLength",params:{limit: 1},message:"must NOT have fewer than 1 characters"};
if(vErrors === null){
vErrors = [err13];
}
else {
vErrors.push(err13);
}
errors++;
}
}
else {
const err14 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/1/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err14];
}
else {
vErrors.push(err14);
}
errors++;
}
var _valid1 = _errs16 === errors;
if(_valid1 && valid6){
valid6 = false;
passing0 = [passing0, 1];
}
else {
if(_valid1){
valid6 = true;
passing0 = 1;
}
}
if(!valid6){
const err15 = {instancePath:instancePath+"/version/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf",keyword:"oneOf",params:{passingSchemas: passing0},message:"must match exactly one schema in oneOf"};
if(vErrors === null){
vErrors = [err15];
}
else {
vErrors.push(err15);
}
errors++;
}
else {
errors = _errs13;
if(vErrors !== null){
if(_errs13){
vErrors.length = _errs13;
}
else {
vErrors = null;
}
}
}
}
let i1 = data2.length;
let j0;
if(i1 > 1){
outer0:
for(;i1--;){
for(j0 = i1; j0--;){
if(func0(data2[i1], data2[j0])){
const err16 = {instancePath:instancePath+"/version",schemaPath:"#/properties/version/uniqueItems",keyword:"uniqueItems",params:{i: i1, j: j0},message:"must NOT have duplicate items (items ## "+j0+" and "+i1+" are identical)"};
if(vErrors === null){
vErrors = [err16];
}
else {
vErrors.push(err16);
}
errors++;
break outer0;
}
}
}
}
}
else {
const err17 = {instancePath:instancePath+"/version",schemaPath:"#/properties/version/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err17];
}
else {
vErrors.push(err17);
}
errors++;
}
}
if(data.cardinality !== undefined){
let data4 = data.cardinality;
if(!(((data4 === "exactly-one") || (data4 === "one-of-many")) || (data4 === "zero-or-more"))){
const err18 = {instancePath:instancePath+"/cardinality",schemaPath:"#/properties/cardinality/enum",keyword:"enum",params:{allowedValues: schema33.properties.cardinality.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err18];
}
else {
vErrors.push(err18);
}
errors++;
}
}
if(data.role !== undefined){
let data5 = data.role;
if((typeof data5 !== "string") && (data5 !== null)){
const err19 = {instancePath:instancePath+"/role",schemaPath:"#/properties/role/type",keyword:"type",params:{type: schema33.properties.role.type},message:"must be string,null"};
if(vErrors === null){
vErrors = [err19];
}
else {
vErrors.push(err19);
}
errors++;
}
if(typeof data5 === "string"){
if(func1(data5) < 1){
const err20 = {instancePath:instancePath+"/role",schemaPath:"#/properties/role/minLength",keyword:"minLength",params:{limit: 1},message:"must NOT have fewer than 1 characters"};
if(vErrors === null){
vErrors = [err20];
}
else {
vErrors.push(err20);
}
errors++;
}
}
}
if(data.configPath !== undefined){
if(typeof data.configPath !== "string"){
const err21 = {instancePath:instancePath+"/configPath",schemaPath:"#/properties/configPath/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err21];
}
else {
vErrors.push(err21);
}
errors++;
}
}
if(data.configFile !== undefined){
if(typeof data.configFile !== "string"){
const err22 = {instancePath:instancePath+"/configFile",schemaPath:"#/properties/configFile/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err22];
}
else {
vErrors.push(err22);
}
errors++;
}
}
if(data.loggerPrefix !== undefined){
if(typeof data.loggerPrefix !== "string"){
const err23 = {instancePath:instancePath+"/loggerPrefix",schemaPath:"#/properties/loggerPrefix/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err23];
}
else {
vErrors.push(err23);
}
errors++;
}
}
if(data.fields !== undefined){
let data9 = data.fields;
if(Array.isArray(data9)){
const len1 = data9.length;
for(let i2=0; i2<len1; i2++){
if(!(validate22(data9[i2], {instancePath:instancePath+"/fields/" + i2,parentData:data9,parentDataProperty:i2,rootData,dynamicAnchors}))){
vErrors = vErrors === null ? validate22.errors : vErrors.concat(validate22.errors);
errors = vErrors.length;
}
}
}
else {
const err24 = {instancePath:instancePath+"/fields",schemaPath:"#/properties/fields/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err24];
}
else {
vErrors.push(err24);
}
errors++;
}
}
if(data.actions !== undefined){
let data11 = data.actions;
if(Array.isArray(data11)){
const len2 = data11.length;
for(let i3=0; i3<len2; i3++){
if(!(validate24(data11[i3], {instancePath:instancePath+"/actions/" + i3,parentData:data11,parentDataProperty:i3,rootData,dynamicAnchors}))){
vErrors = vErrors === null ? validate24.errors : vErrors.concat(validate24.errors);
errors = vErrors.length;
}
}
}
else {
const err25 = {instancePath:instancePath+"/actions",schemaPath:"#/properties/actions/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err25];
}
else {
vErrors.push(err25);
}
errors++;
}
}
if(data.metrics !== undefined){
let data13 = data.metrics;
if(Array.isArray(data13)){
const len3 = data13.length;
for(let i4=0; i4<len3; i4++){
if(!(validate26(data13[i4], {instancePath:instancePath+"/metrics/" + i4,parentData:data13,parentDataProperty:i4,rootData,dynamicAnchors}))){
vErrors = vErrors === null ? validate26.errors : vErrors.concat(validate26.errors);
errors = vErrors.length;
}
}
}
else {
const err26 = {instancePath:instancePath+"/metrics",schemaPath:"#/properties/metrics/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err26];
}
else {
vErrors.push(err26);
}
errors++;
}
}
if(data.restartSignal !== undefined){
let data15 = data.restartSignal;
if(data15 && typeof data15 == "object" && !Array.isArray(data15)){
if(data15.kind === undefined){
const err27 = {instancePath:instancePath+"/restartSignal",schemaPath:"#/$defs/restartSignal/required",keyword:"required",params:{missingProperty: "kind"},message:"must have required property '"+"kind"+"'"};
if(vErrors === null){
vErrors = [err27];
}
else {
vErrors.push(err27);
}
errors++;
}
if(data15.path === undefined){
const err28 = {instancePath:instancePath+"/restartSignal",schemaPath:"#/$defs/restartSignal/required",keyword:"required",params:{missingProperty: "path"},message:"must have required property '"+"path"+"'"};
if(vErrors === null){
vErrors = [err28];
}
else {
vErrors.push(err28);
}
errors++;
}
if(data15.port === undefined){
const err29 = {instancePath:instancePath+"/restartSignal",schemaPath:"#/$defs/restartSignal/required",keyword:"required",params:{missingProperty: "port"},message:"must have required property '"+"port"+"'"};
if(vErrors === null){
vErrors = [err29];
}
else {
vErrors.push(err29);
}
errors++;
}
if(data15.onUnavailable === undefined){
const err30 = {instancePath:instancePath+"/restartSignal",schemaPath:"#/$defs/restartSignal/required",keyword:"required",params:{missingProperty: "onUnavailable"},message:"must have required property '"+"onUnavailable"+"'"};
if(vErrors === null){
vErrors = [err30];
}
else {
vErrors.push(err30);
}
errors++;
}
for(const key1 in data15){
if(!(((((key1 === "kind") || (key1 === "path")) || (key1 === "port")) || (key1 === "pointer")) || (key1 === "onUnavailable"))){
const err31 = {instancePath:instancePath+"/restartSignal",schemaPath:"#/$defs/restartSignal/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key1},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err31];
}
else {
vErrors.push(err31);
}
errors++;
}
}
if(data15.kind !== undefined){
if("http_json" !== data15.kind){
const err32 = {instancePath:instancePath+"/restartSignal/kind",schemaPath:"#/$defs/restartSignal/properties/kind/const",keyword:"const",params:{allowedValue: "http_json"},message:"must be equal to constant"};
if(vErrors === null){
vErrors = [err32];
}
else {
vErrors.push(err32);
}
errors++;
}
}
if(data15.path !== undefined){
let data17 = data15.path;
if(typeof data17 === "string"){
if(!pattern6.test(data17)){
const err33 = {instancePath:instancePath+"/restartSignal/path",schemaPath:"#/$defs/restartSignal/properties/path/pattern",keyword:"pattern",params:{pattern: "^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"},message:"must match pattern \""+"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err33];
}
else {
vErrors.push(err33);
}
errors++;
}
}
else {
const err34 = {instancePath:instancePath+"/restartSignal/path",schemaPath:"#/$defs/restartSignal/properties/path/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err34];
}
else {
vErrors.push(err34);
}
errors++;
}
}
if(data15.port !== undefined){
let data18 = data15.port;
if(!(((typeof data18 == "number") && (!(data18 % 1) && !isNaN(data18))) && (isFinite(data18)))){
const err35 = {instancePath:instancePath+"/restartSignal/port",schemaPath:"#/$defs/restartSignal/properties/port/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err35];
}
else {
vErrors.push(err35);
}
errors++;
}
if((typeof data18 == "number") && (isFinite(data18))){
if(data18 > 65535 || isNaN(data18)){
const err36 = {instancePath:instancePath+"/restartSignal/port",schemaPath:"#/$defs/restartSignal/properties/port/maximum",keyword:"maximum",params:{comparison: "<=", limit: 65535},message:"must be <= 65535"};
if(vErrors === null){
vErrors = [err36];
}
else {
vErrors.push(err36);
}
errors++;
}
if(data18 < 1 || isNaN(data18)){
const err37 = {instancePath:instancePath+"/restartSignal/port",schemaPath:"#/$defs/restartSignal/properties/port/minimum",keyword:"minimum",params:{comparison: ">=", limit: 1},message:"must be >= 1"};
if(vErrors === null){
vErrors = [err37];
}
else {
vErrors.push(err37);
}
errors++;
}
}
}
if(data15.pointer !== undefined){
let data19 = data15.pointer;
if(typeof data19 === "string"){
if(!pattern7.test(data19)){
const err38 = {instancePath:instancePath+"/restartSignal/pointer",schemaPath:"#/$defs/restartSignal/properties/pointer/pattern",keyword:"pattern",params:{pattern: "^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])"},message:"must match pattern \""+"^(/(?!\\.{1,2}(?:/|(?![\\s\\S])))[A-Za-z0-9_.-]{1,64}){0,8}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err38];
}
else {
vErrors.push(err38);
}
errors++;
}
}
else {
const err39 = {instancePath:instancePath+"/restartSignal/pointer",schemaPath:"#/$defs/restartSignal/properties/pointer/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err39];
}
else {
vErrors.push(err39);
}
errors++;
}
}
if(data15.onUnavailable !== undefined){
let data20 = data15.onUnavailable;
if(!((data20 === "degrade") || (data20 === "error"))){
const err40 = {instancePath:instancePath+"/restartSignal/onUnavailable",schemaPath:"#/$defs/restartSignal/properties/onUnavailable/enum",keyword:"enum",params:{allowedValues: schema52.properties.onUnavailable.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err40];
}
else {
vErrors.push(err40);
}
errors++;
}
}
}
else {
const err41 = {instancePath:instancePath+"/restartSignal",schemaPath:"#/$defs/restartSignal/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err41];
}
else {
vErrors.push(err41);
}
errors++;
}
}
if(data.health !== undefined){
let data21 = data.health;
if(data21 && typeof data21 == "object" && !Array.isArray(data21)){
if(data21.probe === undefined){
const err42 = {instancePath:instancePath+"/health",schemaPath:"#/$defs/health/required",keyword:"required",params:{missingProperty: "probe"},message:"must have required property '"+"probe"+"'"};
if(vErrors === null){
vErrors = [err42];
}
else {
vErrors.push(err42);
}
errors++;
}
for(const key2 in data21){
if(!(key2 === "probe")){
const err43 = {instancePath:instancePath+"/health",schemaPath:"#/$defs/health/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key2},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err43];
}
else {
vErrors.push(err43);
}
errors++;
}
}
if(data21.probe !== undefined){
let data22 = data21.probe;
if(data22 && typeof data22 == "object" && !Array.isArray(data22)){
if(data22.kind === undefined){
const err44 = {instancePath:instancePath+"/health/probe",schemaPath:"#/$defs/health/properties/probe/required",keyword:"required",params:{missingProperty: "kind"},message:"must have required property '"+"kind"+"'"};
if(vErrors === null){
vErrors = [err44];
}
else {
vErrors.push(err44);
}
errors++;
}
if(data22.strength === undefined){
const err45 = {instancePath:instancePath+"/health/probe",schemaPath:"#/$defs/health/properties/probe/required",keyword:"required",params:{missingProperty: "strength"},message:"must have required property '"+"strength"+"'"};
if(vErrors === null){
vErrors = [err45];
}
else {
vErrors.push(err45);
}
errors++;
}
for(const key3 in data22){
if(!((((key3 === "kind") || (key3 === "path")) || (key3 === "port")) || (key3 === "strength"))){
const err46 = {instancePath:instancePath+"/health/probe",schemaPath:"#/$defs/health/properties/probe/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key3},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err46];
}
else {
vErrors.push(err46);
}
errors++;
}
}
if(data22.kind !== undefined){
let data23 = data22.kind;
if(!(((data23 === "actuator-health") || (data23 === "http-status")) || (data23 === "broker-heartbeat"))){
const err47 = {instancePath:instancePath+"/health/probe/kind",schemaPath:"#/$defs/health/properties/probe/properties/kind/enum",keyword:"enum",params:{allowedValues: schema53.properties.probe.properties.kind.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err47];
}
else {
vErrors.push(err47);
}
errors++;
}
}
if(data22.path !== undefined){
if(typeof data22.path !== "string"){
const err48 = {instancePath:instancePath+"/health/probe/path",schemaPath:"#/$defs/health/properties/probe/properties/path/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err48];
}
else {
vErrors.push(err48);
}
errors++;
}
}
if(data22.port !== undefined){
let data25 = data22.port;
if(!(((typeof data25 == "number") && (!(data25 % 1) && !isNaN(data25))) && (isFinite(data25)))){
const err49 = {instancePath:instancePath+"/health/probe/port",schemaPath:"#/$defs/health/properties/probe/properties/port/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err49];
}
else {
vErrors.push(err49);
}
errors++;
}
}
if(data22.strength !== undefined){
let data26 = data22.strength;
if(!((data26 === "readiness") || (data26 === "liveness"))){
const err50 = {instancePath:instancePath+"/health/probe/strength",schemaPath:"#/$defs/health/properties/probe/properties/strength/enum",keyword:"enum",params:{allowedValues: schema53.properties.probe.properties.strength.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err50];
}
else {
vErrors.push(err50);
}
errors++;
}
}
}
else {
const err51 = {instancePath:instancePath+"/health/probe",schemaPath:"#/$defs/health/properties/probe/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err51];
}
else {
vErrors.push(err51);
}
errors++;
}
}
}
else {
const err52 = {instancePath:instancePath+"/health",schemaPath:"#/$defs/health/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err52];
}
else {
vErrors.push(err52);
}
errors++;
}
}
if(data.logs !== undefined){
let data27 = data.logs;
if(data27 && typeof data27 == "object" && !Array.isArray(data27)){
for(const key4 in data27){
if(!((((key4 === "logfile") || (key4 === "baselineExposure")) || (key4 === "requiresConfig")) || (key4 === "loggers"))){
const err53 = {instancePath:instancePath+"/logs",schemaPath:"#/$defs/logs/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key4},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err53];
}
else {
vErrors.push(err53);
}
errors++;
}
}
if(data27.logfile !== undefined){
let data28 = data27.logfile;
if(data28 && typeof data28 == "object" && !Array.isArray(data28)){
for(const key5 in data28){
if(!(key5 === "path")){
const err54 = {instancePath:instancePath+"/logs/logfile",schemaPath:"#/$defs/logs/properties/logfile/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key5},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err54];
}
else {
vErrors.push(err54);
}
errors++;
}
}
if(data28.path !== undefined){
if(typeof data28.path !== "string"){
const err55 = {instancePath:instancePath+"/logs/logfile/path",schemaPath:"#/$defs/logs/properties/logfile/properties/path/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err55];
}
else {
vErrors.push(err55);
}
errors++;
}
}
}
else {
const err56 = {instancePath:instancePath+"/logs/logfile",schemaPath:"#/$defs/logs/properties/logfile/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err56];
}
else {
vErrors.push(err56);
}
errors++;
}
}
if(data27.baselineExposure !== undefined){
let data30 = data27.baselineExposure;
if(Array.isArray(data30)){
const len4 = data30.length;
for(let i5=0; i5<len4; i5++){
if(typeof data30[i5] !== "string"){
const err57 = {instancePath:instancePath+"/logs/baselineExposure/" + i5,schemaPath:"#/$defs/logs/properties/baselineExposure/items/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err57];
}
else {
vErrors.push(err57);
}
errors++;
}
}
}
else {
const err58 = {instancePath:instancePath+"/logs/baselineExposure",schemaPath:"#/$defs/logs/properties/baselineExposure/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err58];
}
else {
vErrors.push(err58);
}
errors++;
}
}
if(data27.requiresConfig !== undefined){
let data32 = data27.requiresConfig;
if(Array.isArray(data32)){
const len5 = data32.length;
for(let i6=0; i6<len5; i6++){
if(typeof data32[i6] !== "string"){
const err59 = {instancePath:instancePath+"/logs/requiresConfig/" + i6,schemaPath:"#/$defs/logs/properties/requiresConfig/items/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err59];
}
else {
vErrors.push(err59);
}
errors++;
}
}
}
else {
const err60 = {instancePath:instancePath+"/logs/requiresConfig",schemaPath:"#/$defs/logs/properties/requiresConfig/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err60];
}
else {
vErrors.push(err60);
}
errors++;
}
}
if(data27.loggers !== undefined){
let data34 = data27.loggers;
if(data34 && typeof data34 == "object" && !Array.isArray(data34)){
for(const key6 in data34){
if(!((key6 === "path") || (key6 === "levelProperty"))){
const err61 = {instancePath:instancePath+"/logs/loggers",schemaPath:"#/$defs/logs/properties/loggers/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key6},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err61];
}
else {
vErrors.push(err61);
}
errors++;
}
}
if(data34.path !== undefined){
let data35 = data34.path;
if(typeof data35 === "string"){
if(!pattern6.test(data35)){
const err62 = {instancePath:instancePath+"/logs/loggers/path",schemaPath:"#/$defs/logs/properties/loggers/properties/path/pattern",keyword:"pattern",params:{pattern: "^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"},message:"must match pattern \""+"^/[A-Za-z0-9/_.-]{0,255}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err62];
}
else {
vErrors.push(err62);
}
errors++;
}
}
else {
const err63 = {instancePath:instancePath+"/logs/loggers/path",schemaPath:"#/$defs/logs/properties/loggers/properties/path/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err63];
}
else {
vErrors.push(err63);
}
errors++;
}
}
if(data34.levelProperty !== undefined){
let data36 = data34.levelProperty;
if(typeof data36 === "string"){
if(!pattern19.test(data36)){
const err64 = {instancePath:instancePath+"/logs/loggers/levelProperty",schemaPath:"#/$defs/logs/properties/loggers/properties/levelProperty/pattern",keyword:"pattern",params:{pattern: "^[a-z][a-z0-9.-]{0,127}(?![\\s\\S])"},message:"must match pattern \""+"^[a-z][a-z0-9.-]{0,127}(?![\\s\\S])"+"\""};
if(vErrors === null){
vErrors = [err64];
}
else {
vErrors.push(err64);
}
errors++;
}
}
else {
const err65 = {instancePath:instancePath+"/logs/loggers/levelProperty",schemaPath:"#/$defs/logs/properties/loggers/properties/levelProperty/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err65];
}
else {
vErrors.push(err65);
}
errors++;
}
}
}
else {
const err66 = {instancePath:instancePath+"/logs/loggers",schemaPath:"#/$defs/logs/properties/loggers/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err66];
}
else {
vErrors.push(err66);
}
errors++;
}
}
if(data27.loggers !== undefined){
if(data27.baselineExposure === undefined){
const err67 = {instancePath:instancePath+"/logs",schemaPath:"#/$defs/logs/dependentRequired",keyword:"dependentRequired",params:{property: "loggers",
    missingProperty: "baselineExposure",
    depsCount: 1,
    deps: "baselineExposure"},message:"must have property baselineExposure when property loggers is present"};
if(vErrors === null){
vErrors = [err67];
}
else {
vErrors.push(err67);
}
errors++;
}
}
}
else {
const err68 = {instancePath:instancePath+"/logs",schemaPath:"#/$defs/logs/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err68];
}
else {
vErrors.push(err68);
}
errors++;
}
}
}
else {
const err69 = {instancePath,schemaPath:"#/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err69];
}
else {
vErrors.push(err69);
}
errors++;
}
validate21.errors = vErrors;
return errors === 0;
}
validate21.evaluated = {"props":true,"dynamicProps":false,"dynamicItems":false};


function validate20(data, {instancePath="", parentData, parentDataProperty, rootData=data, dynamicAnchors={}}={}){
/*# sourceURL="https://solace.com/solution-manager/solution-package.schema.json" */;
let vErrors = null;
let errors = 0;
const evaluated0 = validate20.evaluated;
if(evaluated0.dynamicProps){
evaluated0.props = undefined;
}
if(evaluated0.dynamicItems){
evaluated0.items = undefined;
}
if(data && typeof data == "object" && !Array.isArray(data)){
if(data.solution === undefined){
const err0 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "solution"},message:"must have required property '"+"solution"+"'"};
if(vErrors === null){
vErrors = [err0];
}
else {
vErrors.push(err0);
}
errors++;
}
if(data.packageVersion === undefined){
const err1 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "packageVersion"},message:"must have required property '"+"packageVersion"+"'"};
if(vErrors === null){
vErrors = [err1];
}
else {
vErrors.push(err1);
}
errors++;
}
if(data.appliesTo === undefined){
const err2 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "appliesTo"},message:"must have required property '"+"appliesTo"+"'"};
if(vErrors === null){
vErrors = [err2];
}
else {
vErrors.push(err2);
}
errors++;
}
if(data.components === undefined){
const err3 = {instancePath,schemaPath:"#/required",keyword:"required",params:{missingProperty: "components"},message:"must have required property '"+"components"+"'"};
if(vErrors === null){
vErrors = [err3];
}
else {
vErrors.push(err3);
}
errors++;
}
for(const key0 in data){
if(!((((((key0 === "solution") || (key0 === "packageVersion")) || (key0 === "appliesTo")) || (key0 === "provenance")) || (key0 === "authoring")) || (key0 === "components"))){
const err4 = {instancePath,schemaPath:"#/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key0},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err4];
}
else {
vErrors.push(err4);
}
errors++;
}
}
if(data.solution !== undefined){
let data0 = data.solution;
if(typeof data0 === "string"){
if(!pattern4.test(data0)){
const err5 = {instancePath:instancePath+"/solution",schemaPath:"#/properties/solution/pattern",keyword:"pattern",params:{pattern: "^[a-z][a-z0-9-]*$"},message:"must match pattern \""+"^[a-z][a-z0-9-]*$"+"\""};
if(vErrors === null){
vErrors = [err5];
}
else {
vErrors.push(err5);
}
errors++;
}
}
else {
const err6 = {instancePath:instancePath+"/solution",schemaPath:"#/properties/solution/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err6];
}
else {
vErrors.push(err6);
}
errors++;
}
}
if(data.packageVersion !== undefined){
let data1 = data.packageVersion;
if(Array.isArray(data1)){
if(data1.length < 1){
const err7 = {instancePath:instancePath+"/packageVersion",schemaPath:"#/properties/packageVersion/minItems",keyword:"minItems",params:{limit: 1},message:"must NOT have fewer than 1 items"};
if(vErrors === null){
vErrors = [err7];
}
else {
vErrors.push(err7);
}
errors++;
}
const len0 = data1.length;
for(let i0=0; i0<len0; i0++){
let data2 = data1[i0];
const _errs8 = errors;
let valid4 = false;
let passing0 = null;
const _errs9 = errors;
if(!(((typeof data2 == "number") && (!(data2 % 1) && !isNaN(data2))) && (isFinite(data2)))){
const err8 = {instancePath:instancePath+"/packageVersion/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/0/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err8];
}
else {
vErrors.push(err8);
}
errors++;
}
if((typeof data2 == "number") && (isFinite(data2))){
if(data2 < 1 || isNaN(data2)){
const err9 = {instancePath:instancePath+"/packageVersion/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/0/minimum",keyword:"minimum",params:{comparison: ">=", limit: 1},message:"must be >= 1"};
if(vErrors === null){
vErrors = [err9];
}
else {
vErrors.push(err9);
}
errors++;
}
}
var _valid0 = _errs9 === errors;
if(_valid0){
valid4 = true;
passing0 = 0;
}
const _errs11 = errors;
if(typeof data2 === "string"){
if(func1(data2) < 1){
const err10 = {instancePath:instancePath+"/packageVersion/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/1/minLength",keyword:"minLength",params:{limit: 1},message:"must NOT have fewer than 1 characters"};
if(vErrors === null){
vErrors = [err10];
}
else {
vErrors.push(err10);
}
errors++;
}
}
else {
const err11 = {instancePath:instancePath+"/packageVersion/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf/1/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err11];
}
else {
vErrors.push(err11);
}
errors++;
}
var _valid0 = _errs11 === errors;
if(_valid0 && valid4){
valid4 = false;
passing0 = [passing0, 1];
}
else {
if(_valid0){
valid4 = true;
passing0 = 1;
}
}
if(!valid4){
const err12 = {instancePath:instancePath+"/packageVersion/" + i0,schemaPath:"#/$defs/packageVersionId/oneOf",keyword:"oneOf",params:{passingSchemas: passing0},message:"must match exactly one schema in oneOf"};
if(vErrors === null){
vErrors = [err12];
}
else {
vErrors.push(err12);
}
errors++;
}
else {
errors = _errs8;
if(vErrors !== null){
if(_errs8){
vErrors.length = _errs8;
}
else {
vErrors = null;
}
}
}
}
let i1 = data1.length;
let j0;
if(i1 > 1){
outer0:
for(;i1--;){
for(j0 = i1; j0--;){
if(func0(data1[i1], data1[j0])){
const err13 = {instancePath:instancePath+"/packageVersion",schemaPath:"#/properties/packageVersion/uniqueItems",keyword:"uniqueItems",params:{i: i1, j: j0},message:"must NOT have duplicate items (items ## "+j0+" and "+i1+" are identical)"};
if(vErrors === null){
vErrors = [err13];
}
else {
vErrors.push(err13);
}
errors++;
break outer0;
}
}
}
}
}
else {
const err14 = {instancePath:instancePath+"/packageVersion",schemaPath:"#/properties/packageVersion/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err14];
}
else {
vErrors.push(err14);
}
errors++;
}
}
if(data.appliesTo !== undefined){
if(typeof data.appliesTo !== "string"){
const err15 = {instancePath:instancePath+"/appliesTo",schemaPath:"#/properties/appliesTo/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err15];
}
else {
vErrors.push(err15);
}
errors++;
}
}
if(data.provenance !== undefined){
let data4 = data.provenance;
if(data4 && typeof data4 == "object" && !Array.isArray(data4)){
if(data4.author === undefined){
const err16 = {instancePath:instancePath+"/provenance",schemaPath:"#/properties/provenance/required",keyword:"required",params:{missingProperty: "author"},message:"must have required property '"+"author"+"'"};
if(vErrors === null){
vErrors = [err16];
}
else {
vErrors.push(err16);
}
errors++;
}
for(const key1 in data4){
if(!((key1 === "author") || (key1 === "signature"))){
const err17 = {instancePath:instancePath+"/provenance",schemaPath:"#/properties/provenance/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key1},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err17];
}
else {
vErrors.push(err17);
}
errors++;
}
}
if(data4.author !== undefined){
let data5 = data4.author;
if(!((data5 === "solace") || (data5 === "dev-mode"))){
const err18 = {instancePath:instancePath+"/provenance/author",schemaPath:"#/properties/provenance/properties/author/enum",keyword:"enum",params:{allowedValues: schema31.properties.provenance.properties.author.enum},message:"must be equal to one of the allowed values"};
if(vErrors === null){
vErrors = [err18];
}
else {
vErrors.push(err18);
}
errors++;
}
}
if(data4.signature !== undefined){
if(typeof data4.signature !== "string"){
const err19 = {instancePath:instancePath+"/provenance/signature",schemaPath:"#/properties/provenance/properties/signature/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err19];
}
else {
vErrors.push(err19);
}
errors++;
}
}
}
else {
const err20 = {instancePath:instancePath+"/provenance",schemaPath:"#/properties/provenance/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err20];
}
else {
vErrors.push(err20);
}
errors++;
}
}
if(data.authoring !== undefined){
let data7 = data.authoring;
if(data7 && typeof data7 == "object" && !Array.isArray(data7)){
for(const key2 in data7){
if(!(((key2 === "reviewedRows") || (key2 === "totalRows")) || (key2 === "source"))){
const err21 = {instancePath:instancePath+"/authoring",schemaPath:"#/properties/authoring/additionalProperties",keyword:"additionalProperties",params:{additionalProperty: key2},message:"must NOT have additional properties"};
if(vErrors === null){
vErrors = [err21];
}
else {
vErrors.push(err21);
}
errors++;
}
}
if(data7.reviewedRows !== undefined){
let data8 = data7.reviewedRows;
if(!(((typeof data8 == "number") && (!(data8 % 1) && !isNaN(data8))) && (isFinite(data8)))){
const err22 = {instancePath:instancePath+"/authoring/reviewedRows",schemaPath:"#/properties/authoring/properties/reviewedRows/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err22];
}
else {
vErrors.push(err22);
}
errors++;
}
if((typeof data8 == "number") && (isFinite(data8))){
if(data8 < 0 || isNaN(data8)){
const err23 = {instancePath:instancePath+"/authoring/reviewedRows",schemaPath:"#/properties/authoring/properties/reviewedRows/minimum",keyword:"minimum",params:{comparison: ">=", limit: 0},message:"must be >= 0"};
if(vErrors === null){
vErrors = [err23];
}
else {
vErrors.push(err23);
}
errors++;
}
}
}
if(data7.totalRows !== undefined){
let data9 = data7.totalRows;
if(!(((typeof data9 == "number") && (!(data9 % 1) && !isNaN(data9))) && (isFinite(data9)))){
const err24 = {instancePath:instancePath+"/authoring/totalRows",schemaPath:"#/properties/authoring/properties/totalRows/type",keyword:"type",params:{type: "integer"},message:"must be integer"};
if(vErrors === null){
vErrors = [err24];
}
else {
vErrors.push(err24);
}
errors++;
}
if((typeof data9 == "number") && (isFinite(data9))){
if(data9 < 0 || isNaN(data9)){
const err25 = {instancePath:instancePath+"/authoring/totalRows",schemaPath:"#/properties/authoring/properties/totalRows/minimum",keyword:"minimum",params:{comparison: ">=", limit: 0},message:"must be >= 0"};
if(vErrors === null){
vErrors = [err25];
}
else {
vErrors.push(err25);
}
errors++;
}
}
}
if(data7.source !== undefined){
if(typeof data7.source !== "string"){
const err26 = {instancePath:instancePath+"/authoring/source",schemaPath:"#/properties/authoring/properties/source/type",keyword:"type",params:{type: "string"},message:"must be string"};
if(vErrors === null){
vErrors = [err26];
}
else {
vErrors.push(err26);
}
errors++;
}
}
}
else {
const err27 = {instancePath:instancePath+"/authoring",schemaPath:"#/properties/authoring/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err27];
}
else {
vErrors.push(err27);
}
errors++;
}
}
if(data.components !== undefined){
let data11 = data.components;
if(Array.isArray(data11)){
if(data11.length < 1){
const err28 = {instancePath:instancePath+"/components",schemaPath:"#/properties/components/minItems",keyword:"minItems",params:{limit: 1},message:"must NOT have fewer than 1 items"};
if(vErrors === null){
vErrors = [err28];
}
else {
vErrors.push(err28);
}
errors++;
}
const len1 = data11.length;
for(let i2=0; i2<len1; i2++){
if(!(validate21(data11[i2], {instancePath:instancePath+"/components/" + i2,parentData:data11,parentDataProperty:i2,rootData,dynamicAnchors}))){
vErrors = vErrors === null ? validate21.errors : vErrors.concat(validate21.errors);
errors = vErrors.length;
}
}
}
else {
const err29 = {instancePath:instancePath+"/components",schemaPath:"#/properties/components/type",keyword:"type",params:{type: "array"},message:"must be array"};
if(vErrors === null){
vErrors = [err29];
}
else {
vErrors.push(err29);
}
errors++;
}
}
}
else {
const err30 = {instancePath,schemaPath:"#/type",keyword:"type",params:{type: "object"},message:"must be object"};
if(vErrors === null){
vErrors = [err30];
}
else {
vErrors.push(err30);
}
errors++;
}
validate20.errors = vErrors;
return errors === 0;
}
validate20.evaluated = {"props":true,"dynamicProps":false,"dynamicItems":false};

