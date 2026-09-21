# SM-D11a Demo Waiver - 2026-09-14

> ⏹ **EXPIRED 2026-09-14 23:59 Asia/Kolkata.** This document's **substantive body is preserved as historical evidence** of the local-demo authority that existed on that date; only this archival banner and any relative links rebased for relocation differ from the original. Nothing below is current authority.

| | |
|---|---|
| **Status** | Active only for the scope and time stated below. It is not ratification of `SM-D11a`. |
| **Authorizing owner** | Parikshit Tyagi, written approval in the project session on 2026-09-14. |
| **Expires** | 2026-09-14 23:59 Asia/Kolkata. |
| **Waives** | The `SM-D11a` ratification gate for one local-demo implementation and one declared EC2 Cache host. |
| **Does not waive** | `SM-D11a`'s stated technical controls, any other host, arbitrary remote execution, or production deployment. |

## Permitted implementation

Solution Manager may reach the declared Cache Docker host only through an `SshDockerRuntimeAdapter` behind `RuntimeAdapter`. The browser, controllers and package layer do not execute SSH or Docker commands and do not receive the private key.

The adapter may perform only its typed runtime operations with fixed command templates: inspect/list the declared container, read the declared config, atomically place rendered config, restart or stop the declared container, and retrieve a bounded diagnostic log slice. It must not pull, build, tag, create, replace or remove a container.

## Mandatory controls

- Strict pinned-host-key verification; a mismatch refuses the operation.
- The private key is supplied only by a runtime secret/path reference, never by the database, package, browser, API response, log, test fixture or git.
- Strict value grammars and a closed operation-template set; no user-supplied shell command or
  shell concatenation. SSH's exec protocol carries a command string, so every dynamic token is
  grammar-validated before it is inserted and the adapter exposes no generic command method.
- Config writes use a same-directory temporary name, SFTP atomic rename and canonical-path/symlink
  refusal. The standard SFTP API used by this demo exposes no remote fsync; this waiver does not make
  a durability claim stronger than that protocol can prove. The permanent `SM-D11a` decision remains
  responsible for a host-side fsync mechanism.
- Log reads have fixed line and byte bounds. Raw remote stderr, config bytes and credentials are never returned or logged.
- Every operation records the target identity, pinned key fingerprint, fixed operation name and classified outcome without raw secret-bearing values.

## Follow-up

Open item 2 and `SM-D11a` remain the permanent security decision. This waiver ends at expiry and does not authorize a shared or production remote-control plane.
