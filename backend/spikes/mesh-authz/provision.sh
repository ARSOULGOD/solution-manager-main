#!/bin/bash
#
# Gate-G1 spike broker provisioning — throwaway harness, NOT product code.
#
# Adapted from ~/Work/Development/RapidPilot/webAppSDK/tools/setup-broker.sh (the readiness
# wait and the curl/SEMP shape). Everything Keycloak- and queue-fixture-specific is gone;
# this script provisions exactly what G1 needs.
#
# I2 DISCIPLINE: every SEMP resource path and every property name below was read out of the
# broker's OWN served spec (`GET /SEMP/v2/config/spec`, SEMP 2.48), not from memory. See the
# README's "SEMP verification evidence" section for the fetch and its output.
#
# Provisions:
#   - Message VPN `sm-spike` with BOTH basic and OAuth authentication enabled (the design's
#     server-side identities use basic/cert; browsers use OAuth — the two coexist per VPN).
#   - OAuth profile `sm_spike_oauth` (resource-server role) trusting the local issuer's JWKS.
#   - ACL profile `acl-internal`  — publish AND subscribe on sm/v1/>, defaults disallow.
#   - ACL profile `acl-browser-baseline` — subscribe on sm/v1/>, publish default disallow with
#     NO publish exceptions. Browsers subscribe only (design §3).
#   - Client username `sm-internal` (basic auth) on acl-internal — the ONLY identity that may
#     publish. Stands in for mesh-bridge/the backend; Task 10's IT reuses it.
#   - The VPN's built-in `default` client username points at acl-browser-baseline, so every
#     OAuth subject that Tasks 2-4 have not given a dedicated identity lands subscribe-only.
#
# Task 2 (candidate (a) — substitution-variable ACL) appends:
#   - ACL profile `acl-subst` — subscribe exception sm/v1/acme/stream/$client-username/>,
#     plus a grammar control on the plan's guessed ${clientUsername} spelling. Publish
#     default disallow with NO publish exceptions, like every browser-facing profile.
#   - Client usernames `grant-111` and `grant-222` on acl-subst (OAuth only, no password).
#   - A spike/probe/$client-username/> subscribe exception on acl-browser-baseline, which
#     measures what the broker substitutes for a token whose `sub` matches no client
#     username. Subscribe-only and outside sm/v1 — Task 1's assertions are untouched.
#
# Task 3 (candidate (b) — authorization groups from token claims) appends:
#   - ACL profile `acl-alpha` — one subscribe exception, sm/v1/acme/stream/alpha/>. Publish
#     default disallow with NO publish exceptions, like every browser-facing profile.
#   - Authorization group `g-stream-alpha` -> acl-alpha. A broker OBJECT, which is the whole
#     cost story for candidate (b).
#   - A SECOND OAuth profile `sm_spike_oauth_b`, identical to `sm_spike_oauth` except that
#     authorizationGroupsClaimName is "groups". The mapping is a per-PROFILE switch with no
#     per-token opt-out, so it cannot be turned on for `sm_spike_oauth` without refusing every
#     token Tasks 1-2 mint. `sm_spike_oauth` is therefore left byte-for-byte as Task 1 wrote
#     it. See the block above section [9/14] for the measurement that forced this.
#
# Task 4 (candidate (c) — tenant-coarse ACL + unguessable grant-ids) appends:
#   - ACL profile `acl-acme`   — subscribe exceptions sm/v1/acme/notify/> AND sm/v1/acme/stream/>.
#   - ACL profile `acl-globex` — the same two, for the `globex` tenant. The SECOND tenant is
#     what makes the I5 cross-tenant deny a measurement rather than an assumption.
#   - Client usernames `tenant-acme` / `tenant-globex` on them (OAuth only, no password).
#   - ACL profile `acl-acme-notify` + client username `tenant-acme-notify` — ONLY the notify
#     exception. Under candidate (c) the token's `scopes` claim authorizes nothing at the
#     broker, so notify-vs-stream scope separation costs a SECOND ACL PROFILE PER SCOPE. This
#     profile exists to measure exactly that (design §14's second security fixture).
#   All three keep publish default-disallow with NO publish exceptions, like every
#   browser-facing profile.
#
# Idempotent: POST, and PATCH instead when the object already exists.

set -uo pipefail

cd "$(dirname "$0")"

SEMP_URL="http://localhost:8080/SEMP/v2/config"
ADMIN_USER="admin"
ADMIN_PASS="admin"
VPN="sm-spike"

# The broker runs in Docker and reaches the host-side issuer through host.docker.internal.
JWKS_URL="http://host.docker.internal:8090/jwks.json"
ISSUER="sm-spike-issuer"
AUDIENCE="sm-mesh"

# Candidate (b)'s dedicated OAuth profile (Task 3). OAUTH_B_ISSUER_ID is the profile SELECTOR
# the client sends as solclientjs' `issuerIdentifier` — NOT the `iss` claim, which stays
# $ISSUER for both profiles. Underscores only: finding 4 (^[A-Za-z0-9_]*$ on oauthProfileName).
OAUTH_PROFILE_B="sm_spike_oauth_b"
OAUTH_B_ISSUER_ID="sm-spike-oauth-b"

# Spike-local throwaway credential. Committed deliberately; it exists only in this harness.
INTERNAL_USER="sm-internal"
INTERNAL_PASS="sm-internal"

fail=0

# semp <METHOD> <relative-path> <json> <description>
# POSTs, and retries as PATCH when the object already exists, so re-running is safe.
semp() {
  local method="$1" path="$2" body="$3" desc="$4"
  local resp
  resp=$(curl -s -X "$method" "$SEMP_URL/$path" \
    -u "$ADMIN_USER:$ADMIN_PASS" -H "Content-Type: application/json" -d "$body")
  if echo "$resp" | grep -q '"responseCode" *: *200'; then
    echo "  ok    $desc"
  else
    echo "  FAIL  $desc"
    echo "$resp" | python3 -c 'import json,sys; print("        ", json.load(sys.stdin)["meta"].get("error"))' 2>/dev/null \
      || echo "        $resp"
    fail=1
  fi
}

# create <collection-path> <object-path> <json> <description> [immutable]
# POST to the collection; on ALREADY_EXISTS, PATCH the object instead.
# Pass a 5th argument for objects the broker serves as GET/DELETE only — the ACL topic
# exceptions are keyed entirely by their own content, so "already there" IS the desired state
# and a PATCH is rejected with NOT_SUPPORTED.
create() {
  local coll="$1" obj="$2" body="$3" desc="$4" immutable="${5:-}"
  local resp
  resp=$(curl -s -X POST "$SEMP_URL/$coll" \
    -u "$ADMIN_USER:$ADMIN_PASS" -H "Content-Type: application/json" -d "$body")
  if echo "$resp" | grep -q '"responseCode" *: *200'; then
    echo "  ok    $desc (created)"
  elif echo "$resp" | grep -q 'ALREADY_EXISTS'; then
    if [ -n "$immutable" ]; then
      echo "  ok    $desc (already present)"
    else
      semp PATCH "$obj" "$body" "$desc (updated)"
    fi
  else
    echo "  FAIL  $desc"
    echo "$resp" | python3 -c 'import json,sys; print("        ", json.load(sys.stdin)["meta"].get("error"))' 2>/dev/null \
      || echo "        $resp"
    fail=1
  fi
}

echo "Waiting for SEMP..."
until curl -sf -u "$ADMIN_USER:$ADMIN_PASS" "$SEMP_URL/about/api" >/dev/null; do sleep 5; done
echo "SEMP ready: $(curl -s -u "$ADMIN_USER:$ADMIN_PASS" "$SEMP_URL/about/api" \
  | python3 -c 'import json,sys; print("SEMP", json.load(sys.stdin)["data"]["sempVersion"])')"
echo ""

# ---------------------------------------------------------------- Message VPN
# Ports on the software broker are broker-wide, not per-VPN: clients select the VPN by name
# on the same 8008 websocket listener. serviceWebPlainTextEnabled is what opens web transport
# for this VPN. maxMsgSpoolUsage must be non-zero or guaranteed messaging is unavailable.
echo "[1/14] Message VPN '$VPN'"
create "msgVpns" "msgVpns/$VPN" "{
  \"msgVpnName\": \"$VPN\",
  \"enabled\": true,
  \"maxMsgSpoolUsage\": 500,
  \"serviceWebPlainTextEnabled\": true,
  \"authenticationBasicEnabled\": true,
  \"authenticationBasicType\": \"internal\",
  \"authenticationOauthEnabled\": true
}" "VPN $VPN (basic + OAuth both enabled)"

# ------------------------------------------------------------- OAuth profile
# resourceServerValidateTypeEnabled is turned OFF: the broker's default requires the access
# token header to carry typ="at+jwt" (resourceServerRequiredType), and the spike issuer signs
# with only {alg, kid}. Issuer and audience validation stay ON — those are the claims the
# design actually relies on.
#
# authorizationGroupsClaimName is set to "" ON PURPOSE. Its broker default is "groups", which
# makes the broker demand an authorization-groups claim in every access token and reject the
# login with 403 "No Authorization Groups were retrieved from the Authorizing Server" when the
# claim is absent. Empty means "fall back to the VPN's internal authorization" — i.e. the
# client username derived from `sub` selects the ACL profile. Deriving groups FROM the token
# is spike candidate (b), which is Task 3's job, not the baseline's.
#
# TASK 3 CONFIRMS THIS AND LEAVES IT ALONE. Candidate (b) needs the mapping ON, but the
# setting is per-PROFILE with no per-token opt-out, so switching it here refuses every token
# Tasks 1-2 mint (measured: 3 of 8 tests fail — see the block above section [9/10]). Candidate
# (b) therefore runs on its own profile, `sm_spike_oauth_b`, provisioned at [10/10]. This
# profile stays exactly as Task 1 wrote it.
echo "[2/14] OAuth profile 'sm_spike_oauth'"
create "msgVpns/$VPN/authenticationOauthProfiles" \
       "msgVpns/$VPN/authenticationOauthProfiles/sm_spike_oauth" "{
  \"msgVpnName\": \"$VPN\",
  \"oauthProfileName\": \"sm_spike_oauth\",
  \"oauthRole\": \"resource-server\",
  \"issuer\": \"$ISSUER\",
  \"endpointJwks\": \"$JWKS_URL\",
  \"usernameClaimName\": \"sub\",
  \"authorizationGroupsClaimName\": \"\",
  \"resourceServerParseAccessTokenEnabled\": true,
  \"resourceServerValidateIssuerEnabled\": true,
  \"resourceServerRequiredIssuer\": \"$ISSUER\",
  \"resourceServerValidateAudienceEnabled\": true,
  \"resourceServerRequiredAudience\": \"$AUDIENCE\",
  \"resourceServerValidateScopeEnabled\": false,
  \"resourceServerValidateTypeEnabled\": false,
  \"enabled\": true
}" "OAuth profile sm_spike_oauth -> $JWKS_URL"

semp PATCH "msgVpns/$VPN" "{\"authenticationOauthDefaultProfileName\": \"sm_spike_oauth\"}" \
     "default OAuth profile = sm_spike_oauth"

# --------------------------------------------------------------- ACL profiles
echo "[3/14] ACL profile 'acl-internal' — the only publisher"
create "msgVpns/$VPN/aclProfiles" "msgVpns/$VPN/aclProfiles/acl-internal" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-internal\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-internal (defaults disallow)"
create "msgVpns/$VPN/aclProfiles/acl-internal/publishTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-internal/publishTopicExceptions/smf,sm%2Fv1%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-internal\",
  \"publishTopicExceptionSyntax\": \"smf\",
  \"publishTopicException\": \"sm/v1/>\"
}" "acl-internal publish exception sm/v1/>" immutable
create "msgVpns/$VPN/aclProfiles/acl-internal/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-internal/subscribeTopicExceptions/smf,sm%2Fv1%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-internal\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/>\"
}" "acl-internal subscribe exception sm/v1/>" immutable

echo "[4/14] ACL profile 'acl-browser-baseline' — subscribe only, NO publish exception"
create "msgVpns/$VPN/aclProfiles" "msgVpns/$VPN/aclProfiles/acl-browser-baseline" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-browser-baseline\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-browser-baseline (publish disallow, no exceptions — ever)"
create "msgVpns/$VPN/aclProfiles/acl-browser-baseline/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-browser-baseline/subscribeTopicExceptions/smf,sm%2Fv1%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-browser-baseline\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/>\"
}" "acl-browser-baseline subscribe exception sm/v1/>" immutable

# ------------------------------------------------------------- client profile
# The stock 'default' client profile forbids guaranteed messaging. The negative publish test
# needs persistent delivery, because the broker acknowledges a DIRECT publish before the ACL
# check and drops denied direct messages silently (see README, "How publish denial surfaces").
echo "[5/14] client profile 'default' — allow guaranteed messaging"
semp PATCH "msgVpns/$VPN/clientProfiles/default" "{
  \"allowGuaranteedMsgSendEnabled\": true,
  \"allowGuaranteedMsgReceiveEnabled\": true,
  \"allowGuaranteedEndpointCreateEnabled\": true
}" "default client profile allows guaranteed send/receive"

# ----------------------------------------------------------- client usernames
echo "[6/14] client usernames"
create "msgVpns/$VPN/clientUsernames" "msgVpns/$VPN/clientUsernames/$INTERNAL_USER" "{
  \"msgVpnName\": \"$VPN\",
  \"clientUsername\": \"$INTERNAL_USER\",
  \"password\": \"$INTERNAL_PASS\",
  \"aclProfileName\": \"acl-internal\",
  \"clientProfileName\": \"default\",
  \"enabled\": true
}" "$INTERNAL_USER (basic auth) -> acl-internal"

# Every OAuth subject without a dedicated identity falls through to 'default'.
semp PATCH "msgVpns/$VPN/clientUsernames/default" "{
  \"aclProfileName\": \"acl-browser-baseline\",
  \"clientProfileName\": \"default\",
  \"enabled\": true
}" "default client username -> acl-browser-baseline"

# ============================================================================
# TASK 2 — spike candidate (a): substitution-variable ACL
# ============================================================================
#
# Question: can ONE ACL profile give every grant-id its own stream branch, using a
# substitution variable, instead of one hand-written topic exception per grant?
#
# I2 — the grammar is NOT what the plan's task text guessed. The plan wrote
# `sm/v1/acme/stream/${clientUsername}/>`. The broker's own SEMP 2.48 spec documents no
# substitution at all for subscribeTopicException ("The topic for the exception to the
# default action taken. May include wildcard characters."), and the broker's own CLI help
# for `acl-profile / subscribe-topic / exceptions` documents only `<topic>[1..250 chars]`.
# The real grammar is in Solace's docs, verbatim
# (https://docs.solace.com/Security/Managing-Access-Control-Lists.htm and
#  https://docs.solace.com/Security/Granting-Clients-Access.htm#Using):
#
#   "$client-username —For this variable, the event broker will substitute the client
#    username provided by the client or retrieved from the TLS client-certificate during
#    client login."
#   "Substitution variables must appear alone at any level in the topic exception, but they
#    can appear multiple times in a single exception."
#   "The strings $client-id, $client-username, and $client-username-hash have special
#    meaning only for ACL exception topics."
#
# So the exception below is `sm/v1/acme/stream/$client-username/>`.

echo "[7/14] candidate (a) — ACL profile 'acl-subst' (substitution-variable stream branch)"
create "msgVpns/$VPN/aclProfiles" "msgVpns/$VPN/aclProfiles/acl-subst" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-subst\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-subst (publish disallow, no publish exceptions — ever; subscribe disallow)"

# The candidate itself: one exception, every grant gets its own branch.
create "msgVpns/$VPN/aclProfiles/acl-subst/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-subst/subscribeTopicExceptions/smf,sm%2Fv1%2Facme%2Fstream%2F%24client-username%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-subst\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/acme/stream/\$client-username/>\"
}" "acl-subst subscribe exception sm/v1/acme/stream/\$client-username/>" immutable

# Negative control for the GRAMMAR, on a parallel namespace: the plan's guessed
# `${clientUsername}` spelling. If the broker substituted it too, the test asserting that
# grant-111 CANNOT reach sm/v1/acme/stream2/grant-111/s1 would go red. It is here so the
# grammar finding is measured, not merely quoted from a doc.
create "msgVpns/$VPN/aclProfiles/acl-subst/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-subst/subscribeTopicExceptions/smf,sm%2Fv1%2Facme%2Fstream2%2F%24%7BclientUsername%7D%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-subst\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/acme/stream2/\${clientUsername}/>\"
}" "acl-subst grammar control: literal \${clientUsername} spelling" immutable

echo "[8/14] candidate (a) — grant client usernames + unmatched-subject probe"
# Hyphens are fine here: README finding 4 is specific to oauthProfileName
# ('^[A-Za-z0-9_]*$'); clientUsername accepts the house style. Verified by these two
# objects being created at all.
#
# No password is set: these identities authenticate by OAuth only. The OAuth profile's
# usernameClaimName is "sub", so a token minted with sub=grant-111 selects this object and
# therefore acl-subst.
for g in grant-111 grant-222; do
  create "msgVpns/$VPN/clientUsernames" "msgVpns/$VPN/clientUsernames/$g" "{
    \"msgVpnName\": \"$VPN\",
    \"clientUsername\": \"$g\",
    \"aclProfileName\": \"acl-subst\",
    \"clientProfileName\": \"default\",
    \"enabled\": true
  }" "$g (OAuth sub) -> acl-subst"
done

# THE decisive measurement for candidate (a)'s cost. Task 1 finding 3: an OAuth subject with
# no matching client-username object is mapped to the VPN's `default` client username. What
# is unknown — and what decides whether BE-D5 needs a SEMP write per grant — is which string
# the broker then substitutes for $client-username: the token's `sub`, or the resolved
# object name `default`.
#
# Measured on a spike-only namespace OUTSIDE sm/v1 so that acl-browser-baseline's existing
# `sm/v1/>` subscribe exception (which allows everything under sm/v1 regardless) cannot mask
# the answer. Subscribe-only and additive: acl-browser-baseline keeps publish
# default-disallow with no publish exceptions, and every Task 1 assertion is untouched.
create "msgVpns/$VPN/aclProfiles/acl-browser-baseline/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-browser-baseline/subscribeTopicExceptions/smf,spike%2Fprobe%2F%24client-username%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-browser-baseline\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"spike/probe/\$client-username/>\"
}" "acl-browser-baseline probe exception spike/probe/\$client-username/>" immutable

# ============================================================================
# TASK 3 — spike candidate (b): authorization groups from token claims
# ============================================================================
#
# Question: can the OAuth profile's groups-claim mapping bind a token to a broker
# AUTHORIZATION GROUP that carries an ACL profile — i.e. authorize by claim rather than by
# client-username object?
#
# I2 — both halves were read out of the broker's own served SEMP 2.48 spec before being
# scripted here:
#
#   /msgVpns/{msgVpnName}/authorizationGroups          (collection)
#   /msgVpns/{msgVpnName}/authorizationGroups/{authorizationGroupName}
#     MsgVpnAuthorizationGroup: authorizationGroupName, aclProfileName, clientProfileName,
#       enabled, orderBeforeAuthorizationGroupName, orderAfterAuthorizationGroupName
#
#   MsgVpnAuthenticationOauthProfile.authorizationGroupsClaimName — verbatim from the spec:
#     "The name of the groups claim. If non-empty, the specified claim will be used to
#      determine groups for authorization. If empty, the authorizationType attribute of the
#      Message VPN will be used to determine authorization. ... The default value is
#      `\"groups\"`."
#   MsgVpnAuthenticationOauthProfile.authorizationGroupsClaimStringFormat — "single" (default)
#     or "space-delimited"; only relevant when the claim value is a STRING. Set explicitly
#     below so the string-form test asserts against provisioned config, not a default.
#     "Available since 2.32", which is what pins candidate (b)'s SEMP floor.
#
# Solace docs (https://docs.solace.com/Security/Configuring-Client-Authentication.htm,
# "Authorization Groups Claim") state: "By default, Solace event brokers use the `groups`
# field in OAuth tokens to determine the client authorization group", and that claim values
# must be strings or arrays of strings. The docs do NOT state what happens when the claim is
# absent, or present but matching no configured group — so tests/02-authzgroup.test.mjs
# MEASURES both instead of asserting a remembered answer.
#
# WHY A SECOND OAUTH PROFILE (this is candidate (b)'s coexistence cost, and it is measured).
# authorizationGroupsClaimName is a property of the OAUTH PROFILE, and the profile has no
# per-client and no per-token opt-out — the only two group-related properties it has are the
# two named above, and neither makes the claim optional. Turning it on for `sm_spike_oauth`
# therefore refuses EVERY token that lacks the claim, which is Task 1 finding 2. Measured on
# this broker, with the mapping switched on for `sm_spike_oauth`:
#
#   npm test -> Tests  3 failed | 5 passed (8)
#     x G1 baseline > browser subscribes over OAuth+TLS ...                 (sub op-1)
#     x G1 baseline > a browser OAuth token can NEVER publish ...           (sub op-1)
#     x G1 candidate (a) > an UNMATCHED subject still gets its OWN branch   (sub grant-999)
#   Broker log for each: "Login failed. Subcode: 13 respCode: 403 respString: No
#   Authorization Groups were retrieved from the Authorizing Server"
#
# Exactly the three cases whose `sub` has no client-username object. Candidate (a)'s own
# grant-111/grant-222 tests survived, which is itself the precedence finding below.
#
# So candidate (b) gets its OWN OAuth profile, `sm_spike_oauth_b`, and the client selects it
# per connection. Two facts make that work, both measured here rather than recalled:
#   - The profile's `issuer` ("The Issuer Identifier for the OAuth provider") is the SELECTOR,
#     independent of `resourceServerRequiredIssuer` (the `iss` claim that is validated). So
#     profile B can be selected as "sm-spike-oauth-b" while still requiring iss=sm-spike-issuer
#     from the same spike issuer. The VPN's authenticationOauthDefaultProfileName is, verbatim
#     from the spec, "The name of the profile to use when the client does not supply a profile
#     name" — so a client that supplies nothing still lands on `sm_spike_oauth`.
#   - The client supplies it as solclientjs SessionProperties.issuerIdentifier, surfaced by the
#     WebApp SDK as OAuthConfig.issuerIdentifier (src/config.ts, forwarded in src/client.ts and
#     src/protocols/smf/smf-adapter.ts).
# Net effect: `sm_spike_oauth` is left EXACTLY as Task 1 provisioned it, Tasks 1/2/4 are
# untouched, and candidate (b) is still measured in full.

echo "[9/14] candidate (b) — ACL profile 'acl-alpha' + authorization group 'g-stream-alpha'"
create "msgVpns/$VPN/aclProfiles" "msgVpns/$VPN/aclProfiles/acl-alpha" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-alpha\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-alpha (publish disallow, no publish exceptions — ever; subscribe disallow)"

# acl-alpha's ONLY subscribe exception. Deliberately a fixed literal branch, not a
# substitution: candidate (b) authorizes by GROUP, so the branch is a property of the group,
# not of the session. That is exactly what makes it coarse-grained — see the per-grant cost
# note in the README.
create "msgVpns/$VPN/aclProfiles/acl-alpha/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-alpha/subscribeTopicExceptions/smf,sm%2Fv1%2Facme%2Fstream%2Falpha%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-alpha\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/acme/stream/alpha/>\"
}" "acl-alpha subscribe exception sm/v1/acme/stream/alpha/>" immutable

# The candidate itself. An authorization group is a first-class broker OBJECT — this create
# call IS the per-grant cost the README's verdict quantifies.
create "msgVpns/$VPN/authorizationGroups" \
       "msgVpns/$VPN/authorizationGroups/g-stream-alpha" "{
  \"msgVpnName\": \"$VPN\",
  \"authorizationGroupName\": \"g-stream-alpha\",
  \"aclProfileName\": \"acl-alpha\",
  \"clientProfileName\": \"default\",
  \"enabled\": true
}" "authorization group g-stream-alpha -> acl-alpha"

echo "[10/14] candidate (b) — OAuth profile 'sm_spike_oauth_b' with the groups mapping ON"
# This IS candidate (b)'s switch: authorizationGroupsClaimName = "groups". It lives on a
# dedicated profile for the reason argued at length above. Everything else is copied from
# `sm_spike_oauth` so the ONLY difference between the two profiles is the groups mapping —
# that is what makes tests/02's default-profile control meaningful.
create "msgVpns/$VPN/authenticationOauthProfiles" \
       "msgVpns/$VPN/authenticationOauthProfiles/$OAUTH_PROFILE_B" "{
  \"msgVpnName\": \"$VPN\",
  \"oauthProfileName\": \"$OAUTH_PROFILE_B\",
  \"oauthRole\": \"resource-server\",
  \"issuer\": \"$OAUTH_B_ISSUER_ID\",
  \"endpointJwks\": \"$JWKS_URL\",
  \"usernameClaimName\": \"sub\",
  \"authorizationGroupsClaimName\": \"groups\",
  \"authorizationGroupsClaimStringFormat\": \"single\",
  \"resourceServerParseAccessTokenEnabled\": true,
  \"resourceServerValidateIssuerEnabled\": true,
  \"resourceServerRequiredIssuer\": \"$ISSUER\",
  \"resourceServerValidateAudienceEnabled\": true,
  \"resourceServerRequiredAudience\": \"$AUDIENCE\",
  \"resourceServerValidateScopeEnabled\": false,
  \"resourceServerValidateTypeEnabled\": false,
  \"enabled\": true
}" "OAuth profile $OAUTH_PROFILE_B (groups claim ON, selected by issuerIdentifier=$OAUTH_B_ISSUER_ID)"

# ============================================================================
# TASK 4 — spike candidate (c): tenant-coarse ACL + unguessable grant-ids
# ============================================================================
#
# The candidate: do NOT try to authorize per grant at the broker at all. Give each tenant ONE
# ACL profile whose exceptions cover that tenant's whole notify and stream namespace, and rely
# on grant-ids being unguessable for the isolation BETWEEN grants of the same tenant.
#
# It therefore always "works" — the interesting output is not a pass/fail but the EXPOSURE
# BOUNDARY: exactly what a legitimately-issued token can reach beyond what it was issued for.
# The tests state it by measurement (tests/03-coarse-and-i5.test.mjs).
#
# I2 — no new SEMP resource or property is introduced by this section. `aclProfiles`,
# `aclProfiles/{n}/subscribeTopicExceptions` and `clientUsernames`, with exactly the property
# sets used above, were all read out of the broker's own served SEMP 2.48 spec (see the
# README's "SEMP verification evidence" table, re-confirmed for Task 4).
#
# WHY A SECOND TENANT IS PROVISIONED. I5 (server-side tenant isolation) is the invariant the
# design's §14 fixtures have to prove at the mesh. "acme cannot read globex" is only a
# measurement if globex's topics are ones the broker would otherwise serve — so `acl-globex`
# and `tenant-globex` exist to make the cross-tenant deny a real negative rather than a deny
# of a namespace nobody configured.

echo "[11/14] candidate (c) — ACL profile 'acl-acme' + client username 'tenant-acme'"
create "msgVpns/$VPN/aclProfiles" "msgVpns/$VPN/aclProfiles/acl-acme" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-acme\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-acme (publish disallow, no publish exceptions — ever; subscribe disallow)"

# Tenant-COARSE: the whole tenant's notify tier and the whole tenant's stream tier, with no
# grant level in either exception. That breadth IS candidate (c).
create "msgVpns/$VPN/aclProfiles/acl-acme/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-acme/subscribeTopicExceptions/smf,sm%2Fv1%2Facme%2Fnotify%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-acme\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/acme/notify/>\"
}" "acl-acme subscribe exception sm/v1/acme/notify/>" immutable
create "msgVpns/$VPN/aclProfiles/acl-acme/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-acme/subscribeTopicExceptions/smf,sm%2Fv1%2Facme%2Fstream%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-acme\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/acme/stream/>\"
}" "acl-acme subscribe exception sm/v1/acme/stream/>" immutable

# Pre-created ON PURPOSE. Without the object the subject falls through to the VPN's `default`
# client username and lands on `acl-browser-baseline`, whose blanket `sm/v1/>` exception would
# make every candidate-(c) positive pass for the wrong reason (Task 1 finding 3). The tests
# assert the effective clientUsername/aclProfileName per session so that cannot go unnoticed.
create "msgVpns/$VPN/clientUsernames" "msgVpns/$VPN/clientUsernames/tenant-acme" "{
  \"msgVpnName\": \"$VPN\",
  \"clientUsername\": \"tenant-acme\",
  \"aclProfileName\": \"acl-acme\",
  \"clientProfileName\": \"default\",
  \"enabled\": true
}" "tenant-acme (OAuth sub) -> acl-acme"

echo "[12/14] candidate (c) — second tenant: 'acl-globex' + client username 'tenant-globex'"
create "msgVpns/$VPN/aclProfiles" "msgVpns/$VPN/aclProfiles/acl-globex" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-globex\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-globex (publish disallow, no publish exceptions — ever; subscribe disallow)"

create "msgVpns/$VPN/aclProfiles/acl-globex/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-globex/subscribeTopicExceptions/smf,sm%2Fv1%2Fglobex%2Fnotify%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-globex\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/globex/notify/>\"
}" "acl-globex subscribe exception sm/v1/globex/notify/>" immutable
create "msgVpns/$VPN/aclProfiles/acl-globex/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-globex/subscribeTopicExceptions/smf,sm%2Fv1%2Fglobex%2Fstream%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-globex\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/globex/stream/>\"
}" "acl-globex subscribe exception sm/v1/globex/stream/>" immutable

create "msgVpns/$VPN/clientUsernames" "msgVpns/$VPN/clientUsernames/tenant-globex" "{
  \"msgVpnName\": \"$VPN\",
  \"clientUsername\": \"tenant-globex\",
  \"aclProfileName\": \"acl-globex\",
  \"clientProfileName\": \"default\",
  \"enabled\": true
}" "tenant-globex (OAuth sub) -> acl-globex"

echo "[13/14] candidate (c) — scope separation needs a SECOND profile: 'acl-acme-notify'"
# Design §14's second security fixture is "a notify-scope token must not reach a stream topic".
# Under candidates (a)/(b) that is expressible against ONE identity. Under candidate (c) it is
# not expressible at all by the token: the broker authorizes off the client-username object, and
# the token's `scopes` claim is inert as far as the broker is concerned. So the only way to
# enforce scope separation under (c) is to mint a DIFFERENT `sub`, bound to a DIFFERENT ACL
# profile that carries only the notify exception. This object is that profile, and provisioning
# it is itself the cost finding — one profile + one username PER (tenant x scope).
create "msgVpns/$VPN/aclProfiles" "msgVpns/$VPN/aclProfiles/acl-acme-notify" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-acme-notify\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-acme-notify (publish disallow, no publish exceptions — ever; subscribe disallow)"

# ONE exception, and deliberately NO stream exception — that absence is what the fixture tests.
create "msgVpns/$VPN/aclProfiles/acl-acme-notify/subscribeTopicExceptions" \
       "msgVpns/$VPN/aclProfiles/acl-acme-notify/subscribeTopicExceptions/smf,sm%2Fv1%2Facme%2Fnotify%2F%3E" "{
  \"msgVpnName\": \"$VPN\",
  \"aclProfileName\": \"acl-acme-notify\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/acme/notify/>\"
}" "acl-acme-notify subscribe exception sm/v1/acme/notify/> (no stream exception — on purpose)" immutable

create "msgVpns/$VPN/clientUsernames" "msgVpns/$VPN/clientUsernames/tenant-acme-notify" "{
  \"msgVpnName\": \"$VPN\",
  \"clientUsername\": \"tenant-acme-notify\",
  \"aclProfileName\": \"acl-acme-notify\",
  \"clientProfileName\": \"default\",
  \"enabled\": true
}" "tenant-acme-notify (OAuth sub) -> acl-acme-notify"

# ------------------------------------------------- fail-safe: the stock 'default' ACL PROFILE
# Every ACL profile this script CREATES sets its three default actions explicitly. The VPN's
# stock `default` ACL profile is the one nobody wrote, and it ships with the vendor's defaults.
#
# This is not the same object as the `default` CLIENT USERNAME, which sections [4/14]/[6/14]
# already bind to `acl-browser-baseline` — that binding is what makes an unmatched OAuth subject
# fall through to a known, subscribe-only profile (Task 1 finding 3). But a client username is
# only bound to a profile if something bound it: any username created later without an explicit
# `aclProfileName` lands on the stock `default` PROFILE and inherits whatever the vendor chose.
#
# So pin it shut. Nothing in this spike should ever resolve here, and if something does, it must
# get nothing rather than get the vendor's defaults. A profile whose posture was never stated is
# exactly the gap a browser-facing broker cannot afford (`I5`/`I6` posture, and Task 1 finding 1:
# a publish denial is invisible to the client, so an accidental allow would never surface).
echo "[14/14] fail-safe — stock 'default' ACL profile: all three defaults disallow"
semp PATCH "msgVpns/$VPN/aclProfiles/default" "{
  \"clientConnectDefaultAction\": \"disallow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "default ACL profile (connect/publish/subscribe all disallow)"

echo ""
if [ "$fail" -ne 0 ]; then
  echo "provision.sh: FAILED — see above"
  exit 1
fi

# Prove the OAuth profile actually came up active. A profile with a bad issuer or an
# unreachable JWKS stays inactive and every OAuth connect then fails with a bare 401.
echo "OAuth profile runtime state:"
curl -s -u "$ADMIN_USER:$ADMIN_PASS" \
  "http://localhost:8080/SEMP/v2/monitor/msgVpns/$VPN/authenticationOauthProfiles" \
  | python3 -c '
import json, sys
for p in json.load(sys.stdin).get("data", []):
    print("  {}: enabled={} role={} issuer={}".format(
        p.get("oauthProfileName"), p.get("enabled"), p.get("oauthRole"), p.get("issuer")))
'

# Prove the substitution exceptions were stored verbatim — the broker must not have
# mangled, escaped or rejected the `$client-username` level (candidate (a)'s whole premise).
echo ""
echo "Subscribe topic exceptions as the broker stores them:"
for prof in acl-subst acl-browser-baseline acl-alpha acl-acme acl-globex acl-acme-notify; do
  curl -s -u "$ADMIN_USER:$ADMIN_PASS" \
    "$SEMP_URL/msgVpns/$VPN/aclProfiles/$prof/subscribeTopicExceptions" \
    | python3 -c "
import json, sys
for e in json.load(sys.stdin).get('data', []):
    print('  {:22s} {:5s} {}'.format(e['aclProfileName'], e['subscribeTopicExceptionSyntax'],
                                     e['subscribeTopicException']))
"
done

# Candidate (b) evidence. The two profiles must differ in EXACTLY one field — the groups-claim
# mapping — or tests/02's "same token, default profile, groups ignored" control is measuring
# something else.
echo ""
echo "OAuth profiles, groups-claim mapping (candidate (b) switch) and selector:"
curl -s -u "$ADMIN_USER:$ADMIN_PASS" \
  "$SEMP_URL/msgVpns/$VPN/authenticationOauthProfiles" \
  | python3 -c '
import json, sys
for d in json.load(sys.stdin).get("data", []):
    print("  {:18s} issuer(selector)={:18s} groupsClaim={!r:10s} stringFormat={!r:8s} usernameClaim={!r} requiredIssuer={!r}".format(
        d["oauthProfileName"], d.get("issuer"), d.get("authorizationGroupsClaimName"),
        d.get("authorizationGroupsClaimStringFormat"), d.get("usernameClaimName"),
        d.get("resourceServerRequiredIssuer")))
'
echo ""
echo "Authorization groups as the broker stores them:"
curl -s -u "$ADMIN_USER:$ADMIN_PASS" "$SEMP_URL/msgVpns/$VPN/authorizationGroups" \
  | python3 -c '
import json, sys
rows = json.load(sys.stdin).get("data", [])
if not rows:
    print("  (none)")
for g in rows:
    print("  {:16s} acl={:22s} clientProfile={:10s} enabled={}".format(
        g["authorizationGroupName"], g["aclProfileName"], g["clientProfileName"], g["enabled"]))
'

# Candidate (c) evidence. The username -> ACL binding is the whole authorization decision under
# (c), so it is printed rather than assumed: a `tenant-*` object that failed to bind would send
# the session to `default`/`acl-browser-baseline` and its blanket sm/v1/> exception.
echo ""
echo "Client usernames and the ACL profile each one binds:"
curl -s -u "$ADMIN_USER:$ADMIN_PASS" \
  "$SEMP_URL/msgVpns/$VPN/clientUsernames?select=clientUsername,aclProfileName,enabled&count=100" \
  | python3 -c '
import json, sys
for u in sorted(json.load(sys.stdin).get("data", []), key=lambda d: d["clientUsername"]):
    print("  {:22s} acl={:22s} enabled={}".format(
        u["clientUsername"], u["aclProfileName"], u["enabled"]))
'
echo ""
echo "provision.sh: complete"
