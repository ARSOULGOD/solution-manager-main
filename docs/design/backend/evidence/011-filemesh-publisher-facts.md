# File Mesh publisher facts for the push path (plan 011, P11-5.0 / MF13)

**Status: measured from a local, read-only source read, 2026-10-05. Facts only.** Nothing here decides an owner
question. OQ-5, OQ-15 and OQ-20 stay OPEN for the owner sitting; each section below states what the facts support
and why, and no more. Authority: OR-91 (MW0 may run now, docs only); the monitoring rulings M-Q6, M-Q7, M-Q12 and
M-Q21 are the context (`docs/design/DECISIONS.md`, OR-71 to OR-81 and [the register](../../DECISIONS.md)).

**Sources, pinned** (both on disk, never modified):

| Short name | Repository | Revision |
|---|---|---|
| `commons@cd52e31` | `/Users/parikshit/Work/Development/pubsubplus-connector-file-common` | `cd52e3125337b9331fc1671c6c942fb1a617b3f9` |
| `fmui@91879c2` | `/Users/parikshit/Work/Development/file-mesh-ui` | `91879c2a2426c195b687bc47a30076971a109050` |

Citations are `path:line` relative to the repository root named by the short name. `commons` paths are shortened
from `src/main/java/com/solacecoe/connectors/file/common/models/commandcenter/events/` to `…/events/`.

## 1. The plan's premise is wrong, and what replaces it

P11-5.0 Step 1 asks for "the publisher's topic builder and serializer" in `commons@cd52e31`. **Neither is there.**

- `commons` holds the event **model classes** (Lombok `@Value @Builder` POJOs) and the `EventState` string
  constants, nothing that builds the topic of a command-center event or serializes one. The only topic code in the
  tree is `FileUtils.buildCustomTopicDestination` and its helpers (`util/FileUtils.java:138-204`), which expand
  `${fpath_a}`, `${fname}` and similar into the **data-plane** file topic; no code mentions `heartbeat`,
  `command center`, `EventState` or the adapter id as a topic level. Its only serializer call is
  `new Gson().toJson(this.jsonObject)` in `models/FileAdapterJson.java:44`, a generic key/value bag, not a
  command-center event. `EventState` is not referenced by any other class in the repository
  (`…/events/common/EventState.java:20-27` are bare constants). Gson 2.10.1 is on the classpath (`pom.xml:90-92`).
- The code that composes the topic and calls the serializer lives in the source and sink **connectors**, which are
  **not on this machine** (a filesystem search finds no other `pubsubplus-connector-file*` tree, and no
  `com/solacecoe` artifact in `~/.m2`). **Nothing below is read from publisher code.**
- What replaces it, in descending strength: (1) **captured wire data**, because `fmui@91879c2` commits the
  listeners' own log files, which contain 34,509 heartbeat events and 18 file-complete events exactly as the broker
  delivered them, with their topics (`connector-configuration/logs/`); (2) **the consumer code** that parses those
  events (`solace-*-listener-service`); (3) **the producer's configuration contract**, the FM Manager's template and
  default-value generator that tell the connector what base topic to use; (4) the `commons` model classes, which
  the captured JSON matches field for field.

Whatever is not in (1) is marked **INFERRED** or **UNVERIFIED** below.

## 2. Facts

### 2.1 Topic

| # | Fact | Basis | Cite |
|---|---|---|---|
| F1 | The topic of a command-center event is `<solace_base_publish_topic>/<adapter_id>/<event_state>`; heartbeats use `<solace_base_publish_topic_heartbeat>/<adapter_id>/heartbeat`. The connector appends the last two levels; the base is a configuration field. The subscription list the manager asks the operator to bind is `<base>/*/start`, `/complete`, `/error`, `/warning`. | Producer contract (template text) | `fmui` `database/file-mesh-schema.sql:4349-4359` (field `SOLACE_BASE_PUBLISH_TOPIC`), `:4366` (field `SOLACE_BASE_PUBLISH_TOPIC_HEARTBEAT`, "Ex(<…heartbeat>/<adapter_id>/<event_state>)") |
| F2 | **Measured topics** carry nine levels: `DEFAULT/NONE/Alice/SE/FF/SOURCE/NWFILE/SOURCE_DEFAULT_Alice_FF_NWFILE_V1/fcomplete` (file-complete) and `…/SOURCE_DEFAULT_Alice_FF_NWFILE_v1/heartbeat` (heartbeat). Level 7 is the adapter id, level 8 the state. | Captured | `fmui` `connector-configuration/logs/fm_file_sl/info_2026_02_16.log:42`; `…/fm_shl/info_2026_02_16.log:36` |
| F3 | The FM Manager generates the default base as seven levels, `{organization}/{subOrganization}/{fileOwner}/SE/{FF or FE}/{SOURCE or SINK}/{connector kind}`. Levels 0-2 are org, sub-org, file owner; `FF` is file-to-file, `FE` file-events. With the two appended levels the default is nine levels, matching F2. | Producer contract | `fmui` `file-mesh-services/src/controllers/controller-cd-defaultDataFlowConfiguration.ts:33,37,362,388,440,466` |
| F4 | The base-topic fields are free text. The generator writes a default only when the field is empty (`!solaceBasePublishTopicValue && …`), and the template's UI type is `text` with no pattern. An operator can set a base of any depth. The heartbeat base is a **separate** field from the command-center base. | Producer contract | `…defaultDataFlowConfiguration.ts:346,424` (the `!solaceBasePublishTopicValue &&` guards), `:362,440` (values); `fmui` `database/file-mesh-schema.sql:4359` (UI meta `"type": "text"`) |
| F5 | The listeners locate the state and the "connector type" by **fixed index** from `SOLACE_SE_TOPIC_CONF_V1/V2`, read as `<last level index>:<state index>:<type index>`. The sample env says `6:6:2` / `7:7:2`; the committed PM2 deployment config says `8:8:4` / `9:9:4`; the log of a real run says `Topic Version: V1 - 8,8,4`. Only 8:8:4 matches F2 (state = index 8; index 4 = `FF`). `6:6:2` would need seven-level topics, which F3's generator does not produce (**INFERRED**). | Consumer code + captured | `fmui` `env.sample:11-12`; `connector-configuration/ecosystem.config.js:31-32`; `solace-listener-service/src/controllers/controller-adp-se-master.ts:73-78`; `…/fm_file_sl/info_2026_02_16.log:46` |
| F6 | The listeners' own fallback path is index-fragile: for a payload that fails to parse it takes `connectorId = topicHierarchies[8]` (V1) or `[9]` (V2), which on the nine-level topic is the **state** level, not the adapter id (level 7). A topic of any other depth is logged `Not a Valid Topic Configuration`. | Consumer code | `fmui` `solace-listener-service/src/controllers/controller-adp-se-master.ts:197-211` |
| F7 | The heartbeat listener ignores the topic and keys on the payload. | Consumer code | `fmui` `solace-heartbeat-listener-service/src/controllers/controller-adp-heartbeat.ts:17-25`; the topic is read at `…/solace-services/queue-consumer.ts:146` but not passed on |
| F8 | **Topic level 7 equalled `header.adapterID` in all 34,509 heartbeat events and all 18 file-complete events**, paired line by line (payload line, then topic line). Zero mismatches. | Measured, this read (script over the two logs) | the two log files above |

### 2.2 Payload

| # | Fact | Basis | Cite |
|---|---|---|---|
| F9 | Every command-center event is `{ "header": {…}, <body> }`. `Header` has eleven fields in the order `timestamp, adapterType, channel, adapterVersion, adapterID, runID, host, user, ip, eventType, adapterStartTime`. The captured heartbeat header has the same order and omits `channel` (null, as Gson omits nulls). | Model + captured | `commons` `…/events/common/Header.java:25-35`; `fmui` `…/fm_shl/info_2026_02_16.log:35` |
| F10 | `EventState` has eight values: `init`, `start`, `complete`, `error`, `warning`, `heartbeat`, `fstart`, `fcomplete`. There is **no payload class** for `init` or `warning` in `commons`. | Model | `commons` `…/events/common/EventState.java:20-27` (and the class list under `…/events/`) |
| F11 | **Heartbeat** body: `{"adapterState": {"heartBeatTimeStamp": "<string>"}}`; no number is carried. `eventType` is `6`. All 34,509 captured events have this shape. | Model + captured | `commons` `…/events/common/HeartBeat.java:25-26`, `AdapterStateHeartBeatEvent.java:25`; `fmui` `…/fm_shl/info_2026_02_16.log:35` |
| F12 | **File-complete** body: `{"fileStateEvent": {currentPath{src,dest}, file{src,dest}, isDir, fileId, size, totalParts, startTime, completionTime, runTime, runTimeMs}}`, `eventType` `12`. The key is `isDir` (the field name), consistent with field-based Gson output (**INFERRED**; a bean-property serializer would emit `dir`). All 18 captured. | Model + captured | `commons` `…/events/common/FileStateEvent.java:26-35`, `FilePath.java:26-29`, `CommandCenterFileStateEvent.java:26-27`; `fmui` `connector-configuration/logs/fm_file_sl/info_2026_02_16.log:39` |
| F13 | **Numeric fields by state, from the model classes only (no captured sample for these states)**: complete (source) `totalFiles, totalMultiparts, totalDataProcessed, adapterRuntime, adapterRuntimeMs`; complete (sink) adds `totalRuntime, totalRuntimeMs`; error `totalParts, currentPart, totalDataProcessed, reason.reasonCode`; start (source) `fileType, copyModePermissions, maxMultiPartSize, maxFiles, clearStateEOD, eodTimeSec, wildCard.wildCardType, dirRecurring.*`. The listener reads these same paths for `complete`/`error`. | Model + consumer | `commons` `…/events/source/complete/AdapterStateCompleteEvent.java:26-31`, `…/events/sink/complete/AdapterStateCompleteEvent.java:26-33`, `…/events/common/AdapterStateErrorEvent.java:26-31`; `fmui` `solace-listener-service/src/controllers/controller-adp-se-master.ts:56-69` |
| F14 | **Header timestamp zone.** `header.timestamp` is a string `yyyy/MM/dd HH:mm:ss.SSS` with **no zone designator**, in the **connector host's local time**. Measured: on the file-complete event, `timestamp` `2026/02/05 11:23:32.594` equals `completionTime` `1770270812594` (2026-02-05T05:53:32.594Z) plus 5 h 30 min; `adapterStartTime` shows the same offset on three captures. The capturing host was in UTC+05:30; another host's offset is not knowable from the event. The fractional part is **not zero-padded**: 14 of 34,509 heartbeat timestamps have two fractional digits (`11:23:30.56`). `adapterStartTime` is epoch milliseconds as a **string**. | Measured | `fmui` `…/fm_file_sl/info_2026_02_16.log:39` (`completionTime` and `timestamp`); `…/fm_shl/info_2026_02_16.log:35` |
| F15 | **`start`'s `password` (`source/start/Solace.java:28`; the sink twin at the same line).** Both `start` models carry `String password` for the output broker connection. Whether the connector populates it is **UNVERIFIED**: no `start` event appears in any captured log (the command-center and error listener logs hold no payloads), and the serializer is not on disk. The only written sample, a 2021 commented-out sketch, shows `"password":"******"` (masked), and the FM Manager stores the **raw** payload, so a populated password would be persisted there. With Gson's defaults a non-null field is serialized, so the model does not exclude it (**INFERRED**). **Treat `start` as possibly carrying a plaintext broker password.** | Model + consumer | `commons` `…/events/source/start/Solace.java:28`, `…/events/sink/start/Solace.java:28`; `fmui` `solace-error-listener-service/src/app.ts:112`; storing the raw payload: `solace-listener-service/src/controllers/controller-adp-se-master.ts:122-123` |
| F16 | The state (`start`/`complete`/`error`/`fcomplete`/`heartbeat`) is the **last topic level**, so a subscription ending in `…/<state>` selects states at the broker. `fcomplete` was observed on its own queue (`file_complete_events`); the manager's documented bindings for the command-center queue are `start`, `complete`, `error`, `warning` only. Where `init` and `fstart` are published is not shown by any file read. | Consumer + producer contract | `fmui` `env.sample:19-22` (four queues); `database/file-mesh-schema.sql:4351-4359` |
| F17 | **Rate.** The heartbeat interval default is 5 seconds (template default `5`; the SM fixture also defaults 5). The captured heartbeats are 5 s apart (`…14.383`, `…19.383`, `…24.383`). One running adapter is therefore 17,280 heartbeats a day. | Producer contract + captured | `fmui` `database/file-mesh-schema.sql:4365`; `contracts/validation/fixtures/filemesh-ftp-solace.package.json:2409,2965`; `…/fm_shl/info_2026_02_16.log:35,47,59` |

### 2.3 What SM already holds

| # | Fact | Cite |
|---|---|---|
| F18 | The fields that fix the topic and the payload id are **ordinary, non-secret config fields**: `ADAPTER_ID` (path `file/<source|sink>/general/adapter_id`, UI `input`, `required: true`, no `password` flag) and `SOLACE_BASE_PUBLISH_TOPIC[_HEARTBEAT]` (`…/command_center/solace_base_publish_topic[_heartbeat]`, UI `text`). File-events flows use a different yaml root (`file-events/<kind>/general/adapter_id`, `…/file_mesh_manager/solace_base_publish_topic`). | `fmui` `database/file-mesh-schema.sql:5068,5320,5396,7102` (adapter id), `:4349,4366` (topics); `…defaultDataFlowConfiguration.ts:364,390,442,468` (yaml paths) |
| F19 | SM's own File Mesh fixture already declares them, **per component, under two roots**: `file.source.general.adapter_id` (line 3344) and `file-events.source.general.adapter_id` (line 1995), plus both `…solace_base_publish_topic` and `…_heartbeat` fields (lines 2374, 2425, 2912, 2981), all unflagged `secret`. The fixture's sink components are the Solace sink only: **no sink-FTP/GCS/NWFILE component, so no `file/sink/general/adapter_id` field exists in the fixture**. | `contracts/validation/fixtures/filemesh-ftp-solace.package.json:1995,2374,2425,2912,2981,3344` |
| F20 | The header's `host`, `user` and `ip` are the **connector machine's** OS values, not SM-held configuration. Captured `ip` was `127.0.1.1`, a loopback alias, useless as a key. `runID` changes on every adapter run (three run ids for one adapter id in the captured heartbeats). | `commons` `…/events/common/Header.java:31-33`; `fmui` `…/fm_shl/info_2026_02_16.log:35` and the run-id census in §2.4 |

### 2.4 Census of the captured sample (this read)

`connector-configuration/logs/fm_shl/info_2026_02_16.log`: **34,509** heartbeats, **3 adapter ids** and **9
(adapterID, runID) pairs**: `…NWFILE_v1` 15,866, `…NWFILE_V1` 11,335, `…NWFILE_TESTING12` 7,308. The first two differ
**only in case**. `…/fm_file_sl/info_2026_02_16.log`: **18** `fcomplete` events, one adapter id (`…_V1`). Total
**34,527 events: 34,527 carried `header.adapterID` and a nine-level topic whose level 7 matched it.** The sample is
**one organization, one connector kind (NWFILE source), file-to-file flows, two of eight states**; it does not
represent sinks, `FE` flows, or the other six states.

## 3. MF13, answered

| MF13 item | Answer | Status |
|---|---|---|
| Topics published | `<base>/<adapter_id>/<state>` (F1); default base seven levels, nine in total (F2, F3); base is free text per flow, heartbeat base separate (F4). The `6:6:2`/`7:7:2` in `fmui` `env.sample:11-12` is **not** what a real run uses; the run used `8:8:4` (F5). | Measured for 2 states; contract for the rest; publisher code not read |
| Payload per `EventState` | `heartbeat` and `fcomplete` measured (F11, F12). `start`, `complete`, `error` from model classes and the listener's reads (F13). `init`, `warning`: no payload class (F10). `fstart`: no class either; presumed to share the `fcomplete` shape (**UNVERIFIED**). | Partly measured |
| Header timestamp zone | Local time of the connector host, no zone marker, variable-width fraction (F14). | Measured on one host |
| `start`'s `password` published | **UNVERIFIED** (F15). | Open |
| Running File Mesh available to capture | None used; the captured logs stand in. A capture of one event of each state on a spike broker is the way to close `start`, `complete`, `error`, `warning`, `init`, `fstart` and the password question. | Not done |

## 4. OQ-5, what the facts support

**Question:** how a pushed event is attributed to an instance without an MI change (I1).

- **Both candidate keys exist and agree.** `ADAPTER_ID` is an SM-writable, non-secret field (F18, F19). It is the
  topic's second-to-last level (F1, F2) **and** `header.adapterID` in the payload (F9, F11, F12), and the two were
  identical in 34,527 of 34,527 events (F8). Gate 16 would **not** refuse it (not `type: "secret"`, not
  `secret: true`, F19). Option (a) as written, `instanceKey: {configField: "<key>"}` matched against a topic level or
  a payload pointer, is **supported**.
- **Topic route.** A pattern with `{instanceKey}` substitutes one level only. The other seven levels are the base
  topic (F3), which is a second config field (F4) and may be customized. A depth-pinned pattern such as
  `*/*/*/*/*/*/*/{instanceKey}/heartbeat` works for the default nine-level topic and **silently selects nothing** for
  any other depth. Solace `*` matches exactly one level and `>` only the last level (verified, section 6), so there
  is no wildcard that spans an unknown depth and still fixes the second-to-last level.
- **Payload route.** The pointer `/header/adapterID` is depth-independent (F7 shows the heartbeat listener already
  relies on it), but it needs a broader subscription and a decode before the match. This interacts with OQ-20
  (section 5).
- **So:** the facts support (a) with **both** match forms available. (b) topic-only is sufficient only where the
  base topic is the generated default, and fails closed for a customized base. If the sitting rules (b), the
  package author must be told the nine-level assumption. A multi-field substitution (base topic plus adapter id)
  is a shape OQ-5 does not name; whether to want it is for the sitting (**option (c)** territory).
- **Per-component fields.** There are two yaml roots (F18), so `instanceKey.configField` is per component, and the
  fixture has no sink adapter-id field (F19); a File Mesh package that wants sink metrics must declare one.
- **Case.** Two adapter ids differ only in case in the same capture (§2.4). Whether Solace matches topics
  case-sensitively is **not established by the documentation fetched** (section 6); measure it on the spike broker
  before the match is specified, and decide whether the payload match is exact-string.
- **I6 / state narrowing.** `start` may carry a plaintext broker password (F15). Because the state is the last
  topic level (F16), a subscription fixed to the states that carry a declared metric keeps `start` payloads from
  reaching the DP at all, which is stronger than the decoder's "read only the declared pointer, discard the rest".
  The decoder rule still applies. (A fact for the design, not a decision.)
- **I1 audit.** Every option reads fields the MI already emits and config SM already owns. None asks the MI team
  for a new id, endpoint or emission. Writing `ADAPTER_ID` or the base topic is deployment configuration.

## 5. OQ-15 and OQ-20, what the facts support

**OQ-15 (placement attribution).** The event does not name a placement. Topic and payload carry the adapter id
(F2, F8) but no SM placement id; the only host-like fields are the connector machine's `host` and `ip` (F20),
where `ip` was a loopback alias and `host` is an OS hostname whose equality with the SM target's registered address
is **not established**. `runID` changes per run (§2.4) and cannot key. During a re-target overlap, two adapters with
the same adapter id publish indistinguishable topics (F1, F8). **Supported: (a)/(b)**, an ambiguous event is never
attributed to a guessed placement; **(c)** "newest live placement" is not supported by any field in the event.
Whether the drop is named `ambiguous_placement` or `undeclared_dropped` is a naming question the facts do not touch.

**OQ-20 (where a placement-less drop is counted).** The size of the blind spot depends on **OQ-5's route**:

- **Topic-narrowed subscription** (OQ-5 topic route): the broker delivers only events whose adapter id SM declared,
  so an event for an undeclared adapter never reaches the DP and there is nothing to count. The drops that remain
  are an instance whose events arrive with more than one live placement.
- **Wildcard or payload route**: every adapter on the VPN that matches the subscription is delivered, and each
  **undeclared** running adapter is one full heartbeat stream: **17,280 events a day at the default interval**
  (F17), 51,840 a day for the three adapter ids in the captured sample. Under option (a) ("counted nowhere,
  documented") that volume is invisible; under (b) or (c) it is counted at tenant or connection level.
- **No usable key.** In the measured sample, 0 of 34,527 events lacked a key (both keys present and agreeing).
  That is a floor, not an estimate: one organization, one connector kind, two of eight states (§2.4). The structural
  causes that remain are an undeclared adapter, a customized base topic that a depth-pinned pattern misses (F4),
  and case variants of one adapter id (§2.4).
- **Supported:** with the topic route the facts support (a), because the blind spot is nearly empty; with the
  payload or wildcard route they support (b), because (a) would hide a stream the size of the real traffic.
  The sitting should rule OQ-5 and OQ-20 together.

## 6. External facts (I2)

Fetched 2026-10-05:

- <https://docs.solace.com/Messaging/Wildcard-Charaters-Topic-Subs.htm> (the page name carries the vendor's own
  spelling): "When `*` appears by itself at a level within a topic subscription (as in, `animals/*/cats`, or
  `animals/domestic/*`), it indicates a wildcard match at that level."; "When `>` appears by itself at the last level
  of a topic subscription (as in, `animals/domestic/>`), it provides a 'one or more' wildcard match for any topics
  with an identical prefix."; "A `>` that appears anywhere other than by itself at the last level of a topic
  subscription in the string is treated as the `>` character rather than a wildcard."; `*` inside a level next to
  other characters is a prefix match or a literal. **Verified.**
- **Topic case sensitivity: NOT verified.** The same page gives `My/Test/Topic` as an example distinct from
  `my/test/topic` but does not state the rule; two other documentation URLs tried returned a 404 page. Left
  **UNVERIFIED**; measure on the local spike broker (`solace-pubsub-enterprise:10.25.0.177`, never the `oms-*`
  containers) before the match is specified.

## 7. Not established, and how to close it

| Gap | Close by |
|---|---|
| Publisher code (topic composition, serializer, whether `password` is set) | Obtain the source/sink connector repositories (not on disk), or capture on a spike broker with a running File Mesh |
| Payload of `start`, `complete`, `error`, `warning`, `init`, `fstart`; sink events | The same capture |
| Timestamp zone on other hosts | The same capture on a host in another zone |
| Topic case sensitivity | One subscription test on the spike broker |
| Whether `header.host` can map to an SM target | Compare one captured `host` with the target address recorded by SM for the same machine |

## 8. Handoff items for the integrator (not applied; plan 011 is another lane's file)

1. **P11-5.0 Step 1 premise.** It names "the publisher's topic builder and serializer" in `commons@cd52e31`; they
   are not there (section 1). Reword Step 1 to "the model classes, the captured listener logs and the FM Manager
   generator", and the MF13 row's "Local source read of `pubsubplus-connector-file-common@cd52e31`" likewise.
2. **MF13 row.** Replace "UNVERIFIED" with the status column of section 3 (partly measured; `password` open).
3. **OQ-5 row** says P11-5.0 "settled as a fact" the attribution. It settled the key's existence, secrecy and
   agreement (section 4), not the publisher's topic-building code or the six unmeasured states.
4. **`{instanceKey}` grammar** (P11-1.1): record that one substituted level cannot span an unknown base depth
   (section 4), and that `instanceKey.configField` is per component with two yaml roots.
5. **OQ-20 and OQ-5 are coupled** (section 5); the plan's separate treatment loses that.
6. **Fixture gap:** `filemesh-ftp-solace.package.json` has no sink-FTP/GCS/NWFILE component, hence no sink adapter-id
   field (F19). Outside this task; noted for whoever extends the fixture.
7. **Plan 010:** no wording change is needed from this task.

## 9. Rejected or unusable sources

`fmui` `connector-configuration/logs/**` are committed run logs, not designed fixtures; their hostnames, usernames
and paths are omitted here and the events are used for shape, ordering and counts only. Nothing in them is a
credential, and none is quoted.
