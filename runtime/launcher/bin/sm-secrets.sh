# Sourced (not executed) by sm-launcher, sm-migrate and sm-tool: one reader for "secret as a file" (I6, OR-138).
#
#   sm_load_secret NAME     if NAME_FILE is set, read that file into NAME (exported) and unset NAME_FILE.
#
# A named file that is not a regular, readable file, or whose content is empty AFTER reading (an empty file and a file of
# only newlines both count), stops the caller with exit 64; a NAME already set directly is refused alongside a file, so
# one source of truth exists. The value is never echoed. Callers set SM_PROG (their name) for the messages.
sm_load_secret() {
  eval "_f=\${${1}_FILE:-}; _d=\${$1:-}"
  [ -n "$_f" ] || return 0
  [ -z "$_d" ] || { echo "${SM_PROG:-sm}: set only one of $1 and ${1}_FILE" >&2; exit 64; }
  { [ -f "$_f" ] && [ -r "$_f" ]; } || { echo "${SM_PROG:-sm}: ${1}_FILE is not a readable regular file" >&2; exit 64; }
  _v="$(cat "$_f")" || { echo "${SM_PROG:-sm}: ${1}_FILE could not be read" >&2; exit 64; }
  [ -n "$_v" ] || { echo "${SM_PROG:-sm}: ${1}_FILE is empty" >&2; exit 64; }
  eval "$1=\$_v"
  export "$1"
  unset "${1}_FILE" _v _f _d
}
