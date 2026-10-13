#!/bin/sh
# Generates the database passwords as secret files (OR-138, I6), and rotates the four role passwords.
#
#   init-secrets.sh [--dir DIR] [--group GID]                       create every MISSING file (the five passwords and dp_master_key); an existing file is never replaced
#   init-secrets.sh [--dir DIR] [--group GID] rotate all|ROLE...    ROLE: cp_migrator cp_runtime dp_migrator dp_runtime
#
# DIR: --dir, else SM_SECRETS_DIR from the environment, else SM_SECRETS_DIR from runtime/.env (a plain KEY=VALUE read; the
# file is never sourced), else ../secrets next to compose.yml. A relative value resolves against the compose directory, as
# compose resolves it. NOTE: sudo drops SM_SECRETS_DIR from the environment (env_reset); under sudo set it in .env or pass --dir.
# The directory is created 0700 and its owner and mode are never changed afterwards. Files are 0600 (0640 with --group),
# 48 random alphanumeric characters (about 285 bits) from /dev/urandom, written to a temp file and linked into place without
# replacing anything, so a re-run changes nothing and a crash leaves no partial file. db_superuser_password is the database
# container's own init secret (POSTGRES_PASSWORD_FILE): generated here, never rotated here, never given a group.
# --group GID (needs privilege to a group you are not in): the four ROLE files get that group and mode 0640, owner unchanged.
# The launcher image's `sm` user is 10001, and on Linux a bind-mounted file keeps its host owner, group and mode, so
# `--group 10001` is what lets that user read its files while the directory stays 0700 and the superuser file stays 0600.
# `rotate` copies the live file's owner, group and mode onto the new file (cp -p), so a rotation never changes them.
# This runs on the host, before `docker compose up`: compose creates its secret mounts from files that must already exist,
# and `db` itself needs db_superuser_password, so a container could not create them first.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
cdir="$(cd "$here/.." && pwd)"          # the compose directory
group=""
dir_arg=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dir) dir_arg="$2"; shift 2 ;;
    --group) group="$2"; shift 2 ;;
    *) break ;;
  esac
done
case "$group" in ""|*[!0-9]*) [ -z "$group" ] || { echo "init-secrets: --group takes a numeric gid" >&2; exit 64; } ;; esac

# SM_SECRETS_DIR from runtime/.env without sourcing it: the last KEY=VALUE line, optional matching quotes stripped.
env_file_value () {
  [ -f "$cdir/.env" ] || return 0
  v="$(grep -E '^[[:space:]]*SM_SECRETS_DIR=' "$cdir/.env" | tail -n 1 | sed -E 's/^[[:space:]]*SM_SECRETS_DIR=//; s/[[:space:]]+$//')" || true
  case "$v" in \"*\") v="${v#\"}"; v="${v%\"}" ;; \'*\') v="${v#\'}"; v="${v%\'}" ;; esac
  printf '%s' "$v"
}
if [ -n "$dir_arg" ]; then dir="$dir_arg"
elif [ -n "${SM_SECRETS_DIR:-}" ]; then dir="$SM_SECRETS_DIR"
else dir="$(env_file_value)"; fi
[ -n "$dir" ] || dir="$cdir/secrets"
case "$dir" in /*) ;; *) if [ -n "$dir_arg" ]; then dir="$PWD/$dir"; else dir="$cdir/$dir"; fi ;; esac

umask 077
mkdir -p "$dir"
[ -d "$dir" ] || { echo "init-secrets: $dir is not a directory" >&2; exit 1; }
chmod 0700 "$dir" 2>/dev/null || echo "init-secrets: warning: could not set 0700 on $dir" >&2
dir="$(cd "$dir" && pwd)"

ROLES="cp_migrator cp_runtime dp_migrator dp_runtime"
gen () { LC_ALL=C head -c 96 /dev/urandom | base64 | LC_ALL=C tr -d '/+=\n' | head -c 48; }

# warn_unless_good NAME: an existing secret file is left alone, but flagged unless a plain 48-character alphanumeric file.
check_existing () {
  p="$dir/$1"
  if [ -L "$p" ] && [ ! -e "$p" ]; then echo "init-secrets: ERROR: $p is a dangling symlink" >&2; exit 1; fi
  if [ -L "$p" ] || [ ! -f "$p" ]; then echo "init-secrets: warning: $p exists but is not a regular, non-symlink file; left untouched" >&2; return 0; fi
  if [ "$(LC_ALL=C tr -d 'A-Za-z0-9' < "$p" | wc -c | tr -d ' ')" -ne 0 ] || [ "$(wc -c < "$p" | tr -d ' ')" -ne 48 ]; then
    echo "init-secrets: warning: $p is not 48 alphanumeric characters (an operator-supplied value?); left untouched" >&2
  fi
}

# put NAME [grouped]: write a new secret to NAME without replacing an existing file. Returns 1 if it existed.
put () {
  if [ -e "$dir/$1" ] || [ -L "$dir/$1" ]; then check_existing "$1"; return 1; fi
  tmp="$(mktemp "$dir/.tmp.XXXXXX")"
  gen > "$tmp"
  if [ "$(wc -c < "$tmp" | tr -d ' ')" -ne 48 ]; then rm -f "${tmp:?}"; echo "init-secrets: random source failed" >&2; exit 1; fi
  if [ -n "$group" ] && [ "${2:-}" = grouped ]; then
    chgrp "$group" "$tmp" && chmod 0640 "$tmp" || { rm -f "${tmp:?}"; echo "init-secrets: could not set group $group" >&2; exit 1; }
  else
    chmod 0600 "$tmp"
  fi
  if ln "$tmp" "$dir/$1" 2>/dev/null; then rm -f "${tmp:?}"; return 0; fi
  rm -f "${tmp:?}"
  return 1
}

if [ "${1:-}" = "rotate" ]; then
  shift
  [ "$#" -gt 0 ] || { echo "init-secrets: rotate needs 'all' or role names" >&2; exit 64; }
  if [ "$1" = "all" ]; then
    [ "$#" -eq 1 ] || { echo "init-secrets: 'all' takes no further arguments" >&2; exit 64; }
    set -- $ROLES
  fi
  wanted=""
  for r in "$@"; do
    case "$r" in cp_migrator|cp_runtime|dp_migrator|dp_runtime) ;; *) echo "init-secrets: unknown role '$r'" >&2; exit 64 ;; esac
    case " $wanted " in *" $r "*) ;; *) wanted="$wanted $r" ;; esac
    [ -f "$dir/${r}_password" ] || { echo "init-secrets: $dir/${r}_password does not exist; run without 'rotate' first" >&2; exit 64; }
  done
  # shellcheck disable=SC2086
  set -- $wanted
  lock="$dir/.rotate.lock"
  mkdir "$lock" 2>/dev/null || { echo "init-secrets: another rotation holds $lock (remove it only if none is running)" >&2; exit 1; }
  trap 'rmdir "${lock:?}" 2>/dev/null || true' EXIT INT TERM
  # Stale candidates from an earlier failed rotation are discarded.
  for r in $ROLES; do rm -f "${dir:?}/${r:?}_password.next"; done
  for r in "$@"; do
    # cp -p carries the live file's owner, group and mode onto the new file; the write below keeps them (truncate in place).
    cp -p "$dir/${r}_password" "$dir/${r}_password.next" || { echo "init-secrets: could not prepare ${r}_password.next" >&2; exit 1; }
    gen > "$dir/${r}_password.next"
    [ "$(wc -c < "$dir/${r}_password.next" | tr -d ' ')" -eq 48 ] || { echo "init-secrets: random source failed" >&2; exit 1; }
  done
  # All named roles are applied in one transaction. The live files are replaced only after the database accepted it.
  if SM_SECRETS_DIR="$dir" docker compose -f "$cdir/compose.yml" --profile bootstrap run --rm bootstrap rotate "$@"; then
    for r in "$@"; do mv -f "$dir/${r}_password.next" "$dir/${r}_password"; done
    echo "init-secrets: rotated: $*"
    case " $* " in
      *" cp_runtime "*|*" dp_runtime "*)
        running="$(SM_SECRETS_DIR="$dir" docker compose -f "$cdir/compose.yml" ps -q launcher 2>/dev/null || true)"
        echo "init-secrets: ================================================================" >&2
        echo "init-secrets: WARNING: a runtime role's password changed. The launcher still holds the OLD one:" >&2
        echo "init-secrets: its open connections work, but any NEW database connection is refused until it is" >&2
        echo "init-secrets: recreated. Recreating it is a MAINTENANCE ACTION (the console and gateway restart," >&2
        echo "init-secrets: with a drain); it is not done automatically. When ready:" >&2
        [ -z "$running" ] || echo "init-secrets: (a launcher container is running now)" >&2
        echo "init-secrets:     docker compose up -d --force-recreate launcher" >&2
        echo "init-secrets: ================================================================" >&2 ;;
    esac
    case " $* " in *" cp_migrator "*|*" dp_migrator "*) echo "init-secrets: migrator passwords are read by 'migrate' on each run; nothing to restart" ;; esac
  else
    echo "init-secrets: rotate FAILED. The roles were changed in one transaction, so normally none changed; a connection lost at the" >&2
    echo "init-secrets: very end can leave the outcome unknown. In every case the recovery is the same: run" >&2
    echo "init-secrets: '$0 rotate $*' again (it regenerates and applies afresh). Live files were not replaced." >&2
    exit 1
  fi
  exit 0
fi

# put_master_key: the data-plane master key (OR-85): 32 random bytes, standard base64 (44 characters), NO trailing newline (the decoder is
# strict). Written like the role files (0600, or 0640 with --group so uid 10001 can read it), never replacing an existing file.
put_master_key () {
  if [ -e "$dir/dp_master_key" ] || [ -L "$dir/dp_master_key" ]; then return 1; fi
  tmp="$(mktemp "$dir/.tmp.XXXXXX")"
  head -c 32 /dev/urandom | base64 | tr -d '\n' > "$tmp"
  if [ "$(wc -c < "$tmp" | tr -d ' ')" -ne 44 ]; then rm -f "${tmp:?}"; echo "init-secrets: random source failed" >&2; exit 1; fi
  if [ -n "$group" ]; then
    chgrp "$group" "$tmp" && chmod 0640 "$tmp" || { rm -f "${tmp:?}"; echo "init-secrets: could not set group $group" >&2; exit 1; }
  else
    chmod 0600 "$tmp"
  fi
  if ln "$tmp" "$dir/dp_master_key" 2>/dev/null; then rm -f "${tmp:?}"; return 0; fi
  rm -f "${tmp:?}"
  return 1
}

created=""
if put_master_key; then created="$created dp_master_key"; fi
for f in db_superuser_password cp_migrator_password cp_runtime_password dp_migrator_password dp_runtime_password; do
  mode=grouped; [ "$f" != db_superuser_password ] || mode=plain
  if put "$f" "$mode"; then created="$created $f"; fi
done
if [ -n "$created" ]; then echo "init-secrets: created:$created (in $dir)"; else echo "init-secrets: nothing to do (in $dir)"; fi
echo "init-secrets: back this directory up like a key; it is not configuration"
echo "init-secrets: dp_master_key seals every secret value in the data plane: losing it loses them all; never regenerate it over a live installation"
