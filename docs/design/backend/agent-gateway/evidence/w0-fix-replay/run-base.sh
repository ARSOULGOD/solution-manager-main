#!/bin/bash
set -u
R=/Users/parikshit/Work/Development/solution-manager-worktrees/i015-backend
B=$R/backend
C=w0fix-pg14
docker rm -f $C >/dev/null 2>&1
docker run -d --name $C -e POSTGRES_PASSWORD=pw postgres:14 >/dev/null || exit 1
for i in $(seq 1 40); do docker exec $C pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
sleep 3
ps() { docker exec -i $C psql -U postgres -v ON_ERROR_STOP=1 -q "$@"; }
pst() { docker exec -i $C psql -U postgres -v ON_ERROR_STOP=1 -q -1 "$@"; }
ps < $B/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql || { echo PHASE0-DP FAIL; exit 1; }
ps < $B/cp-core/src/main/resources/db/bootstrap/sm_control_phase0.sql || { echo PHASE0-CP FAIL; exit 1; }
HASH='$argon2id$v=19$m=65536,t=3,p=4$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2hoYXNoaGFzaGhhc2g'
apply() { # schema role dir upto skip
  local role=$1 dir=$2 pat=$3
  for f in $(ls $dir/V*.sql | sort -V); do
    n=$(basename $f)
    if [ -n "${4:-}" ] && [[ "$n" == $4* ]]; then continue; fi
    { echo "SET ROLE $role;"; sed "s|\${bootstrap_credential_hash}|$HASH|" $f; } | pst >/dev/null || { echo "FAIL $n"; return 1; }
    echo "ok $n"
  done
}
apply sm_dp_owner $B/dp-core/src/main/resources/db/migration/sm_data x
apply sm_dp_owner $B/dp-core/src/main/resources/db/migration/sm_metrics x
apply sm_cp_owner $B/cp-core/src/main/resources/db/migration/sm_control x
echo BASE-DONE
