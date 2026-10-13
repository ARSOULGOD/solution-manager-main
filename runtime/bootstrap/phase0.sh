#!/bin/sh
# Phase 0, once per cluster, as the database superuser; and, in `rotate` mode, a password change for named roles.
#
#   phase0.sh                     create the roles and schemas, then give the four LOGIN roles their passwords
#   phase0.sh rotate ROLE...      set the named roles' passwords from /secrets/<ROLE>_password.next, ALL OR NONE
#                                 (ROLE is one of cp_migrator cp_runtime dp_migrator dp_runtime; written by init-secrets.sh)
#
# Secrets (I6, OR-138). Every password is read from a file under /secrets (the host's secrets directory, mounted read-only),
# never from the environment. The role passwords are NEVER sent to the server in plaintext: this script computes the
# SCRAM-SHA-256 verifier (RFC 5802/5803; the format PostgreSQL stores, pg_authid.rolpassword) client-side with perl's core
# Digest::SHA, which the postgres image already carries, and sends `ALTER ROLE ... PASSWORD 'SCRAM-SHA-256$...'`. PostgreSQL
# stores a password already in SCRAM form as is (CREATE ROLE reference). THE VERIFIER is what keeps the plaintext out of the
# server log; the session SETs below (superuser-settable, PostgreSQL 14 logging docs) keep the verifier out of it too. The
# verifier travels on psql's stdin, not argv. All ALTERs of one run are ONE transaction in ONE session: a failure leaves no
# role changed. Passwords are generated ASCII alphanumeric by init-secrets.sh, so SASLprep leaves them unchanged.
# Re-running the Phase 0 SQL is refused by PostgreSQL at CREATE ROLE, by design: Phase 0 is not idempotent. If it fails
# AFTER its SQL ran, the roles exist without passwords: run `init-secrets.sh rotate all`.
set -eu
umask 077
S=/secrets
[ -r "$S/db_superuser_password" ] || { echo "phase0: $S/db_superuser_password is not readable" >&2; exit 64; }
PGPASSWORD="$(cat "$S/db_superuser_password")"
export PGPASSWORD
psql_ () { psql -v ON_ERROR_STOP=1 -h db -U postgres -d sm "$@"; }

# scram FILE -> SCRAM-SHA-256$4096:<salt>$<StoredKey>:<ServerKey>; the password is read from FILE, never from argv.
scram () {
  perl -MDigest::SHA=sha256,hmac_sha256 -MMIME::Base64 -e '
    open(my $f, "<", $ARGV[0]) or die "cannot read $ARGV[0]\n"; local $/; my $pw = <$f>; close $f;
    $pw =~ s/\n+\z//; length($pw) >= 32 or die "password shorter than 32 characters\n";
    open(my $r, "<", "/dev/urandom") or die; read($r, my $salt, 16) == 16 or die; close $r;
    my $n = 4096; my $u = hmac_sha256($salt . pack("N", 1), $pw); my $sp = $u;
    for (2 .. $n) { $u = hmac_sha256($u, $pw); $sp ^= $u }
    my $ck = hmac_sha256("Client Key", $sp);
    print "SCRAM-SHA-256\$$n:", encode_base64($salt, ""), "\$", encode_base64(sha256($ck), ""), ":",
          encode_base64(hmac_sha256("Server Key", $sp), ""), "\n";' "$1"
}

# applypw ROLE FILE ... : one session, one transaction, every pair or none. Verifiers are computed first, so a bad file
# fails before the database is touched.
applypw () {
  sql="$(mktemp)"
  trap 'rm -f "${sql:?}"' EXIT
  preload="$(psql_ -tA -c 'SHOW shared_preload_libraries')"
  {
    echo "SET log_statement = 'none';"
    echo "SET log_min_error_statement = 'panic';"
    echo "SET log_min_duration_statement = -1;"
    echo "SET log_min_duration_sample = -1;"
    echo "SET log_statement_sample_rate = 0;"
    echo "SET log_transaction_sample_rate = 0;"
    case "$preload" in *pg_stat_statements*) echo "SET pg_stat_statements.track_utility = off;" ;; esac
    echo "BEGIN;"
    while [ "$#" -gt 0 ]; do
      verifier="$(scram "$2")" || { echo "phase0: could not compute the verifier for $1" >&2; exit 1; }
      # Its alphabet cannot hold a quote; refuse anything else rather than embed it in SQL.
      printf '%s' "$verifier" | grep -Eq '^SCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$' \
        || { echo "phase0: unexpected verifier shape for $1" >&2; exit 1; }
      echo "ALTER ROLE $1 PASSWORD '$verifier';"
      shift 2
    done
    echo "COMMIT;"
  } > "$sql"
  psql_ -q -f - < "$sql" >/dev/null
}

ROLES="cp_migrator cp_runtime dp_migrator dp_runtime"
case "${1:-}" in
  rotate)
    shift
    [ "$#" -gt 0 ] || { echo "phase0: rotate needs at least one role" >&2; exit 64; }
    for r in "$@"; do
      case "$r" in cp_migrator|cp_runtime|dp_migrator|dp_runtime) ;; *) echo "phase0: unknown role '$r'" >&2; exit 64 ;; esac
      [ -r "$S/${r}_password.next" ] || { echo "phase0: $S/${r}_password.next is not readable" >&2; exit 64; }
    done
    pairs=""
    for r in "$@"; do pairs="$pairs sm_$r $S/${r}_password.next"; done
    # shellcheck disable=SC2086
    applypw $pairs
    echo "phase0: rotated $*"
    ;;
  "")
    for r in $ROLES; do
      [ -r "$S/${r}_password" ] || { echo "phase0: $S/${r}_password is not readable (run runtime/scripts/init-secrets.sh)" >&2; exit 64; }
    done
    # The Phase 0 SQL is mounted from where it lives, never copied, so it cannot drift from what the tests execute.
    psql_ -f /bootstrap/sm_control_phase0.sql
    psql_ -f /bootstrap/sm_data_phase0.sql
    pairs=""
    for r in $ROLES; do pairs="$pairs sm_$r $S/${r}_password"; done
    # shellcheck disable=SC2086
    applypw $pairs
    echo "phase 0 complete"
    ;;
  *) echo "phase0: unknown mode '$1'" >&2; exit 64 ;;
esac
