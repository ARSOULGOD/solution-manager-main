# Lessons — solution-manager (repo-local)

Patterns specific to working **in this repo**: authoring design docs, maintaining the decision register, keeping
requirements traced.

> **Which file?**
> - **Cross-cutting** — affects more than one repo, or is architectural/contract-level →
>   [`../design/common/lessons.md`](../design/common/lessons.md). Every repo reads that one.
> - **Only about working here** → this file.
>
> When in doubt, put it in the cross-cutting file. The cost of a lesson being seen by people who didn't need it is
> far lower than the cost of it being invisible to someone who did.

**Review this at session start.** Each entry exists because something went wrong once.

---

## R1 — Verify a mechanism works before designing on top of it

**What happened:** a design specified `info.sm.instanceId` as its *only* instance-correlation primitive, asserting
that `/actuator/info` was exposed by default and that `info.*` properties surfaced automatically. Both were false in
Spring Boot 3.x, and a third claim ("carries only what SM put there") was a security statement that was simply
untrue. The mechanism was load-bearing for two separate decisions.

**Rule:** when a design depends on a specific platform behaviour, verify that behaviour *before* building the
design around it — not after review. See `L1` in the cross-cutting lessons for the general form.

---

## R2 — Test the verification, not just the thing being verified

**What happened:** `sync-conventions.sh`'s drift check appeared to work — a tamper test reported DRIFT. It only
passed because the test had modified the hash marker *as well as* the block body. The check never compared the
body at all, so the actual failure mode it existed to catch (hand-edit, marker untouched) was undetected.

**Rule:** when testing a guard, make the test simulate the *realistic* failure, not a convenient one. Ask "what
would someone actually do wrong here?" — a person editing a synced block will not helpfully update its checksum.

---

## R3 — Don't leave an empty folder where a document might wrongly land

**What happened:** after moving plans into the component repos, a central `plans/backend/` folder would have
remained. An empty folder in a familiar location is an invitation to file something there, recreating the
two-copies problem in a new spot.

**Rule:** when relocating a class of document, delete the old location outright and put the *convention* (template,
rules) somewhere that is clearly not an artifact folder.

---

## R4 — Check the ID prefix before writing a decision down

**What happened:** `B/D17` (editions) was recorded as `D17` (first vertical slice) while consolidating — with the
no-renumbering rule on screen at the time. Caught only by a scripted completeness check over all 53 IDs.

**Rule:** when touching the decision register, run a coverage check over every ID rather than trusting a read-through.
