#!/bin/bash
#
# §11.3 broker ACL spike provisioning — throwaway harness, NOT product code.
#
# Adapted from backend/spikes/mesh-authz/provision.sh (G1) for the two-level-deeper case:
# G1 measured ONE substitutable level (grant-id, tenant a literal). §11.3 measures whether a
# subscriber whose identity carries only ONE substitutable level (its client username / OAuth
# `sub`) can be confined to a NARROW two-level scope (sub-organization AND environment) inside
# the notify tier, where §11.2's shape has no substitution point at all for those two levels —
# so the notify-tier grant below is a per-grant, hand-provisioned ACL profile with LITERAL
# (non-substituted) topic exceptions, exactly the shape §11.3 says is unmeasured. The stream
# tier (leg 14) is the one place `$client-username` substitution still applies, and is measured
# separately at the end of this script, reusing G1's already-established mechanism.
#
# I2 DISCIPLINE: every SEMP resource path and property name below matches what G1 already
# verified against the broker's own served spec (SEMP 2.48) — see backend/spikes/mesh-authz's
# README "SEMP verification evidence". No new SEMP surface is used here.
#
# Topology (§11.3's fixture, exactly):
#   Two Message VPNs — one per Organization: 'orgA', 'orgB'.
#   Inside each: sub-organizations su1/su2, environments dev/prod (topic levels, not broker
#   objects — the topic shape from console-integration.md §11.2 carries them).
#
# Provisions PER VPN (orgA and orgB, via provision_vpn below):
#   - The Message VPN itself, with BOTH basic and OAuth authentication enabled.
#   - OAuth profile 'sm_scope_oauth' trusting the local issuer's JWKS (issuer :8091).
#   - ACL profile 'acl-internal' — publish AND subscribe on sm/v1/>, defaults disallow. Stands
#     in for the backend/mesh-bridge publisher for THAT organization, and doubles as the
#     broad-ACL probe leg 11a/11b needs (a subscriber whose OWN exception is wide enough that
#     a same-looking-but-other-org topic string is allowed to SUBSCRIBE, so any refusal to
#     DELIVER is attributable to the VPN boundary, not to the ACL).
#   - CLIENT PROFILES (review round 1, BLOCKER 1 / owner-authorised fix 1). Three kinds:
#       'sm-browser'  every browser-facing identity. Guaranteed send, guaranteed receive and
#                     guaranteed endpoint create all FALSE, plus shared subscriptions,
#                     transacted sessions and bridge connections FALSE and zero flow /
#                     endpoint counts. Reason (I2, docs.solace.com/Security/Granting-Clients-
#                     Access.htm): "Subscribe-topic access controls are not considered when a
#                     consuming client binds directly to a durable endpoint" — a browser that
#                     can bind or create a queue reads whatever that queue is subscribed to,
#                     whatever its subscribe ACL says. The subscribe ACL alone is therefore NOT
#                     a confinement mechanism unless the client profile also removes bind.
#       'sm-internal' the org publisher / control identities. Guaranteed everything ON. This is
#                     the "non-browser profile" of the positive control.
#       'default'     the stock profile, PINNED CLOSED (same fail-safe idea as the stock 'default'
#                     ACL profile): any client username created later with no explicit client
#                     profile lands on a profile that cannot bind or create endpoints.
#     plus three ABLATION profiles (each = sm-internal with exactly ONE of the three flags off)
#     used only by tests/05 to show which flag is load-bearing for which operation.
#   - Client username 'sm-internal-<vpn>' (basic auth) on acl-internal + sm-internal.
#
# Provisions on orgA ONLY (the organization under test, per §11.3's fixture table):
#   - ACL profile 'acl-orgA-su1-dev' — the grant under test: subscribe exception
#     sm/v1/orgA/su1/dev/notify/> ONLY. Publish default disallow, NO publish exceptions ever
#     (browsers never publish — console-integration.md §2 rule 9 / §3).
#   - Client username 'br-000000000000d001' (the [orgA,su1,dev] grant; OAuth only) on it.
#   - ACL profile 'acl-orgA-su1-dev-catalog' — leg 13's candidate (a) measurement: the SAME
#     scoped exception PLUS one exact-topic organization-level exception,
#     sm/v1/orgA/notify/catalog/package (no wildcard). A SEPARATE profile from the one above so
#     the leg 1-10 matrix result is not entangled with the leg 13 measurement.
#   - Client username 'br-000000000000c001' (the su1/dev + catalog grant) (OAuth only) on it.
#   - ACL profile 'acl-orgA-stream-subst' — leg 14: ONE subscribe exception,
#     sm/v1/orgA/stream/$client-username/>. Bound to orgA's VPN-level `default` client
#     username (the fallthrough every unrecognized OAuth subject lands on), which is the whole
#     zero-broker-write claim under test. Publish default disallow, no exceptions.
#
# ROUND 2 (2026-09-30) changes:
#   * BROWSER GRAMMAR. Every browser-facing client username is now  br-<16 lowercase hex>  (the
#     only shape the ticket issuer will mint as `sub`). Internal / control / ablation usernames
#     (sm-internal-*, *-ctl, *-abl-*) do NOT match it. A final section ASSERTS this from SEMP.
#   * PER-ORGANIZATION AUDIENCE on each VPN's OAuth profile (sm-mesh-acl-orgA / -orgB).
#   * orgB gets the same zero-write stream fallthrough as orgA (the production shape), and its
#     `default` client username is pinned and asserted, not left stock.
#   * Composed grants (notify literal exception + stream $client-username exception on one
#     dedicated profile) for tests/08.
#
# Idempotent: POST, and PATCH instead when the object already exists.

set -uo pipefail

cd "$(dirname "$0")"

SEMP_URL="http://127.0.0.1:8081/SEMP/v2/config"
ADMIN_USER="admin"
ADMIN_PASS="admin"

# The broker runs in Docker and reaches the host-side issuer through host.docker.internal.
JWKS_URL="http://host.docker.internal:8091/jwks.json"
ISSUER="sm-spike-acl-issuer"
# Round 2 MAJOR: one audience PER ORGANIZATION. A ticket minted for orgA is not valid at VPN orgB,
# whichever VPN name the client chooses at login. The issuer stamps aud=sm-mesh-acl-<org>.
audience_for() { echo "sm-mesh-acl-$1"; }

fail=0

# semp <METHOD> <relative-path> <json> <description>
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

# client_profile <vpn> <name> <send> <recv> <endpointCreate> <shared> <transacted> <bridge> <maxEgress> <maxIngress> <maxEndpoints>
client_profile() {
  local vpn="$1" name="$2"
  create "msgVpns/$vpn/clientProfiles" "msgVpns/$vpn/clientProfiles/$name" "{
    \"msgVpnName\": \"$vpn\",
    \"clientProfileName\": \"$name\",
    \"allowGuaranteedMsgSendEnabled\": $3,
    \"allowGuaranteedMsgReceiveEnabled\": $4,
    \"allowGuaranteedEndpointCreateEnabled\": $5,
    \"allowSharedSubscriptionsEnabled\": $6,
    \"allowTransactedSessionsEnabled\": $7,
    \"allowBridgeConnectionsEnabled\": $8,
    \"maxEgressFlowCount\": $9,
    \"maxIngressFlowCount\": ${10},
    \"maxEndpointCountPerClientUsername\": ${11}
  }" "client profile $name (send=$3 recv=$4 endpointCreate=$5 shared=$6 txn=$7 bridge=$8)"
}

# --------------------------------------------------------- per-organization VPN provisioning
provision_vpn() {
  local VPN="$1"

  echo "[VPN $VPN] Message VPN"
  create "msgVpns" "msgVpns/$VPN" "{
    \"msgVpnName\": \"$VPN\",
    \"enabled\": true,
    \"maxMsgSpoolUsage\": 500,
    \"serviceWebPlainTextEnabled\": true,
    \"authenticationBasicEnabled\": true,
    \"authenticationBasicType\": \"internal\",
    \"authenticationOauthEnabled\": true
  }" "VPN $VPN (basic + OAuth both enabled)"

  echo "[VPN $VPN] OAuth profile 'sm_scope_oauth'"
  create "msgVpns/$VPN/authenticationOauthProfiles" \
         "msgVpns/$VPN/authenticationOauthProfiles/sm_scope_oauth" "{
    \"msgVpnName\": \"$VPN\",
    \"oauthProfileName\": \"sm_scope_oauth\",
    \"oauthRole\": \"resource-server\",
    \"issuer\": \"$ISSUER\",
    \"endpointJwks\": \"$JWKS_URL\",
    \"usernameClaimName\": \"sub\",
    \"authorizationGroupsClaimName\": \"\",
    \"resourceServerParseAccessTokenEnabled\": true,
    \"resourceServerValidateIssuerEnabled\": true,
    \"resourceServerRequiredIssuer\": \"$ISSUER\",
    \"resourceServerValidateAudienceEnabled\": true,
    \"resourceServerRequiredAudience\": \"$(audience_for "$VPN")\",
    \"resourceServerValidateScopeEnabled\": false,
    \"resourceServerValidateTypeEnabled\": false,
    \"enabled\": true
  }" "OAuth profile sm_scope_oauth -> $JWKS_URL"

  semp PATCH "msgVpns/$VPN" "{\"authenticationOauthDefaultProfileName\": \"sm_scope_oauth\"}" \
       "$VPN default OAuth profile = sm_scope_oauth"

  echo "[VPN $VPN] ACL profile 'acl-internal' — org publisher + VPN-boundary probe"
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

  echo "[VPN $VPN] client profiles: sm-browser (closed), sm-internal (open), ablations, stock default (pinned closed)"
  client_profile "$VPN" sm-browser  false false false false false false 0 0 0
  client_profile "$VPN" sm-internal true  true  true  true  true  true  1000 1000 1000
  # Ablations: sm-internal with EXACTLY ONE guaranteed flag off (measurement fixtures only).
  client_profile "$VPN" sm-abl-create-off true  true  false true true true 1000 1000 1000
  client_profile "$VPN" sm-abl-recv-off   true  false true  true true true 1000 1000 1000
  client_profile "$VPN" sm-abl-send-off   false true  true  true true true 1000 1000 1000
  # Pin the stock profile closed. (The stock profile of the broker's built-in 'default' Message VPN reads guaranteed send/receive/endpoint-create, shared, transacted and bridge all ON on this broker; a profile that is not pinned would hand those to any client username created without an explicit client profile.)
  semp PATCH "msgVpns/$VPN/clientProfiles/default" "{
    \"allowGuaranteedMsgSendEnabled\": false,
    \"allowGuaranteedMsgReceiveEnabled\": false,
    \"allowGuaranteedEndpointCreateEnabled\": false,
    \"allowSharedSubscriptionsEnabled\": false,
    \"allowTransactedSessionsEnabled\": false,
    \"allowBridgeConnectionsEnabled\": false
  }" "$VPN stock 'default' client profile pinned closed"

  echo "[VPN $VPN] client username 'sm-internal-$VPN'"
  create "msgVpns/$VPN/clientUsernames" "msgVpns/$VPN/clientUsernames/sm-internal-$VPN" "{
    \"msgVpnName\": \"$VPN\",
    \"clientUsername\": \"sm-internal-$VPN\",
    \"password\": \"sm-internal-$VPN\",
    \"aclProfileName\": \"acl-internal\",
    \"clientProfileName\": \"sm-internal\",
    \"enabled\": true
  }" "sm-internal-$VPN (basic auth) -> acl-internal + sm-internal"

  # Fail-safe carried from G1 (backend/spikes/mesh-authz/provision.sh section [14/14]): the
  # stock 'default' ACL PROFILE (not the 'default' CLIENT USERNAME) is the object nobody in
  # this script explicitly writes unless pinned here, and it ships with the vendor's defaults.
  # Any client username created later without an explicit aclProfileName would otherwise
  # inherit whatever the vendor chose. Pin all three defaults to disallow.
  echo "[VPN $VPN] fail-safe — stock 'default' ACL profile: all three defaults disallow"
  semp PATCH "msgVpns/$VPN/aclProfiles/default" "{
    \"clientConnectDefaultAction\": \"disallow\",
    \"publishTopicDefaultAction\": \"disallow\",
    \"subscribeTopicDefaultAction\": \"disallow\"
  }" "$VPN default ACL profile (connect/publish/subscribe all disallow)"
}

echo "[1/4] Provisioning orgA"
provision_vpn orgA
echo ""
echo "[2/4] Provisioning orgB"
provision_vpn orgB
echo ""

# ============================================================================
# orgA-ONLY: the notify-tier grant under test — [orgA, su1, dev]
# ============================================================================
echo "[3/4] orgA notify-tier grant: acl-orgA-su1-dev (legs 1-10, 11a/11b)"
create "msgVpns/orgA/aclProfiles" "msgVpns/orgA/aclProfiles/acl-orgA-su1-dev" "{
  \"msgVpnName\": \"orgA\",
  \"aclProfileName\": \"acl-orgA-su1-dev\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-orgA-su1-dev (publish disallow, no exceptions — ever; subscribe disallow)"
create "msgVpns/orgA/aclProfiles/acl-orgA-su1-dev/subscribeTopicExceptions" \
       "msgVpns/orgA/aclProfiles/acl-orgA-su1-dev/subscribeTopicExceptions/smf,sm%2Fv1%2ForgA%2Fsu1%2Fdev%2Fnotify%2F%3E" "{
  \"msgVpnName\": \"orgA\",
  \"aclProfileName\": \"acl-orgA-su1-dev\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/orgA/su1/dev/notify/>\"
}" "acl-orgA-su1-dev subscribe exception sm/v1/orgA/su1/dev/notify/>" immutable

create "msgVpns/orgA/clientUsernames" "msgVpns/orgA/clientUsernames/br-000000000000d001" "{
  \"msgVpnName\": \"orgA\",
  \"clientUsername\": \"br-000000000000d001\",
  \"aclProfileName\": \"acl-orgA-su1-dev\",
  \"clientProfileName\": \"sm-browser\",
  \"enabled\": true
}" "br-000000000000d001 = orgA-su1-dev grant (OAuth sub) -> acl-orgA-su1-dev + sm-browser"

# Control / ablation identities for tests/05: IDENTICAL ACL profile to the browser grant
# (acl-orgA-su1-dev), differing ONLY in client profile, so any behavioural difference is
# attributable to the client profile and nothing else.
for spec in "orgA-su1-dev-ctl:sm-internal" "orgA-abl-create-off:sm-abl-create-off" \
            "orgA-abl-recv-off:sm-abl-recv-off" "orgA-abl-send-off:sm-abl-send-off"; do
  u="${spec%%:*}"; cp="${spec##*:}"
  create "msgVpns/orgA/clientUsernames" "msgVpns/orgA/clientUsernames/$u" "{
    \"msgVpnName\": \"orgA\",
    \"clientUsername\": \"$u\",
    \"aclProfileName\": \"acl-orgA-su1-dev\",
    \"clientProfileName\": \"$cp\",
    \"enabled\": true
  }" "$u -> acl-orgA-su1-dev + $cp (control/ablation)"
done

# Isolating fixture for the guaranteed-SEND flag: every browser ACL denies publish outright, so
# the ACL and the client-profile flag can never be told apart on a browser identity. This
# identity has a publish-capable ACL (acl-internal) and ONLY allowGuaranteedMsgSendEnabled=false,
# so a persistent publish that fails for it fails because of the flag alone (tests/02).
create "msgVpns/orgA/clientUsernames" "msgVpns/orgA/clientUsernames/orgA-abl-pub-send-off" "{
  \"msgVpnName\": \"orgA\",
  \"clientUsername\": \"orgA-abl-pub-send-off\",
  \"password\": \"orgA-abl-pub-send-off\",
  \"aclProfileName\": \"acl-internal\",
  \"clientProfileName\": \"sm-abl-send-off\",
  \"enabled\": true
}" "orgA-abl-pub-send-off (basic) -> acl-internal + sm-abl-send-off (send-flag isolation)"

echo ""
echo "[3b/4] leg 13 candidate (a): acl-orgA-su1-dev-catalog (scoped exception + ONE exact org-terminating exception)"
create "msgVpns/orgA/aclProfiles" "msgVpns/orgA/aclProfiles/acl-orgA-su1-dev-catalog" "{
  \"msgVpnName\": \"orgA\",
  \"aclProfileName\": \"acl-orgA-su1-dev-catalog\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-orgA-su1-dev-catalog (publish disallow, no exceptions — ever; subscribe disallow)"
create "msgVpns/orgA/aclProfiles/acl-orgA-su1-dev-catalog/subscribeTopicExceptions" \
       "msgVpns/orgA/aclProfiles/acl-orgA-su1-dev-catalog/subscribeTopicExceptions/smf,sm%2Fv1%2ForgA%2Fsu1%2Fdev%2Fnotify%2F%3E" "{
  \"msgVpnName\": \"orgA\",
  \"aclProfileName\": \"acl-orgA-su1-dev-catalog\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/orgA/su1/dev/notify/>\"
}" "acl-orgA-su1-dev-catalog subscribe exception sm/v1/orgA/su1/dev/notify/>" immutable
# THE candidate (a) exception §11.4 asks about: ONE exact topic, no wildcard.
create "msgVpns/orgA/aclProfiles/acl-orgA-su1-dev-catalog/subscribeTopicExceptions" \
       "msgVpns/orgA/aclProfiles/acl-orgA-su1-dev-catalog/subscribeTopicExceptions/smf,sm%2Fv1%2ForgA%2Fnotify%2Fcatalog%2Fpackage" "{
  \"msgVpnName\": \"orgA\",
  \"aclProfileName\": \"acl-orgA-su1-dev-catalog\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/orgA/notify/catalog/package\"
}" "acl-orgA-su1-dev-catalog EXACT-topic exception sm/v1/orgA/notify/catalog/package (no wildcard)" immutable

create "msgVpns/orgA/clientUsernames" "msgVpns/orgA/clientUsernames/br-000000000000c001" "{
  \"msgVpnName\": \"orgA\",
  \"clientUsername\": \"br-000000000000c001\",
  \"aclProfileName\": \"acl-orgA-su1-dev-catalog\",
  \"clientProfileName\": \"sm-browser\",
  \"enabled\": true
}" "br-000000000000c001 = orgA-su1-dev-catalog grant (OAuth sub) -> acl-orgA-su1-dev-catalog + sm-browser"

echo ""
echo "[4/4] leg 14 (stream tier, EXPLORATORY): acl-orgA-stream-subst on orgA's default fallthrough"
create "msgVpns/orgA/aclProfiles" "msgVpns/orgA/aclProfiles/acl-orgA-stream-subst" "{
  \"msgVpnName\": \"orgA\",
  \"aclProfileName\": \"acl-orgA-stream-subst\",
  \"clientConnectDefaultAction\": \"allow\",
  \"publishTopicDefaultAction\": \"disallow\",
  \"subscribeTopicDefaultAction\": \"disallow\"
}" "acl-orgA-stream-subst (publish disallow, no exceptions — ever; subscribe disallow)"
create "msgVpns/orgA/aclProfiles/acl-orgA-stream-subst/subscribeTopicExceptions" \
       "msgVpns/orgA/aclProfiles/acl-orgA-stream-subst/subscribeTopicExceptions/smf,sm%2Fv1%2ForgA%2Fstream%2F%24client-username%2F%3E" "{
  \"msgVpnName\": \"orgA\",
  \"aclProfileName\": \"acl-orgA-stream-subst\",
  \"subscribeTopicExceptionSyntax\": \"smf\",
  \"subscribeTopicException\": \"sm/v1/orgA/stream/\$client-username/>\"
}" "acl-orgA-stream-subst subscribe exception sm/v1/orgA/stream/\$client-username/>" immutable

# THE zero-broker-write claim under test: point orgA's VPN-level 'default' client username at
# this profile, so every OAuth subject WITHOUT a dedicated client-username object (i.e. every
# subject other than br-000000000000d001 / br-000000000000c001 above, which DO have dedicated
# objects and therefore take precedence — G1's measured precedence order) falls through here.
semp PATCH "msgVpns/orgA/clientUsernames/default" "{
  \"aclProfileName\": \"acl-orgA-stream-subst\",
  \"clientProfileName\": \"sm-browser\",
  \"enabled\": true
}" "orgA default client username -> acl-orgA-stream-subst + sm-browser (zero-write fallthrough under test)"

# ---------------------------------------------------------------------------------------------
# ROUND 2 additions
# ---------------------------------------------------------------------------------------------
# stream_fallthrough <vpn> — the SAME zero-write stream route orgA has, on another VPN. Round 2
# MAJOR: orgB's `default` client username used to be the STOCK one (enabled=false, acl 'default',
# client profile 'default'), which is the only reason an orgA ticket presented to orgB was
# refused. Production needs the same stream fallthrough on every organization's VPN, so the
# fixture has it, and the audience binding (not a stock default) has to carry the isolation.
stream_fallthrough() {
  local VPN="$1" ACL="acl-$1-stream-subst"
  create "msgVpns/$VPN/aclProfiles" "msgVpns/$VPN/aclProfiles/$ACL" "{
    \"msgVpnName\": \"$VPN\",
    \"aclProfileName\": \"$ACL\",
    \"clientConnectDefaultAction\": \"allow\",
    \"publishTopicDefaultAction\": \"disallow\",
    \"subscribeTopicDefaultAction\": \"disallow\"
  }" "$ACL (publish disallow, no exceptions — ever; subscribe disallow)"
  create "msgVpns/$VPN/aclProfiles/$ACL/subscribeTopicExceptions" \
         "msgVpns/$VPN/aclProfiles/$ACL/subscribeTopicExceptions/smf,sm%2Fv1%2F$VPN%2Fstream%2F%24client-username%2F%3E" "{
    \"msgVpnName\": \"$VPN\",
    \"aclProfileName\": \"$ACL\",
    \"subscribeTopicExceptionSyntax\": \"smf\",
    \"subscribeTopicException\": \"sm/v1/$VPN/stream/\$client-username/>\"
  }" "$ACL subscribe exception sm/v1/$VPN/stream/\$client-username/>" immutable
  semp PATCH "msgVpns/$VPN/clientUsernames/default" "{
    \"aclProfileName\": \"$ACL\",
    \"clientProfileName\": \"sm-browser\",
    \"enabled\": true
  }" "$VPN default client username -> $ACL + sm-browser (pinned, not stock)"
}
echo "[5/6] round 2: orgB gets the same zero-write stream fallthrough; orgB default client username pinned"
stream_fallthrough orgB

# composed grant: ONE dedicated profile carrying BOTH the notify literal exception AND the stream
# $client-username exception — the real shape of a browser that holds a notify grant AND streams
# (round-2 MINOR: leg 14 had only measured stream on default-fallthrough subjects).
composed_grant() {
  local USER="$1" SU="$2" ENV="$3" ACL="acl-$1"   # NB: a broker ACL profile name is limited to 32 characters (measured: SEMP INVALID_PARAMETER)
  create "msgVpns/orgA/aclProfiles" "msgVpns/orgA/aclProfiles/$ACL" "{
    \"msgVpnName\": \"orgA\",
    \"aclProfileName\": \"$ACL\",
    \"clientConnectDefaultAction\": \"allow\",
    \"publishTopicDefaultAction\": \"disallow\",
    \"subscribeTopicDefaultAction\": \"disallow\"
  }" "$ACL (publish disallow, no exceptions)"
  create "msgVpns/orgA/aclProfiles/$ACL/subscribeTopicExceptions" \
         "msgVpns/orgA/aclProfiles/$ACL/subscribeTopicExceptions/smf,sm%2Fv1%2ForgA%2F$SU%2F$ENV%2Fnotify%2F%3E" "{
    \"msgVpnName\": \"orgA\",
    \"aclProfileName\": \"$ACL\",
    \"subscribeTopicExceptionSyntax\": \"smf\",
    \"subscribeTopicException\": \"sm/v1/orgA/$SU/$ENV/notify/>\"
  }" "$ACL notify exception sm/v1/orgA/$SU/$ENV/notify/>" immutable
  create "msgVpns/orgA/aclProfiles/$ACL/subscribeTopicExceptions" \
         "msgVpns/orgA/aclProfiles/$ACL/subscribeTopicExceptions/smf,sm%2Fv1%2ForgA%2Fstream%2F%24client-username%2F%3E" "{
    \"msgVpnName\": \"orgA\",
    \"aclProfileName\": \"$ACL\",
    \"subscribeTopicExceptionSyntax\": \"smf\",
    \"subscribeTopicException\": \"sm/v1/orgA/stream/\$client-username/>\"
  }" "$ACL stream exception sm/v1/orgA/stream/\$client-username/>" immutable
  create "msgVpns/orgA/clientUsernames" "msgVpns/orgA/clientUsernames/$USER" "{
    \"msgVpnName\": \"orgA\",
    \"clientUsername\": \"$USER\",
    \"aclProfileName\": \"$ACL\",
    \"clientProfileName\": \"sm-browser\",
    \"enabled\": true
  }" "$USER (OAuth sub) -> $ACL + sm-browser"
}
echo "[6/6] round 2: composed grants (notify literal + stream substitution on ONE dedicated profile)"
composed_grant br-000000000000c101 su1 dev
composed_grant br-000000000000c102 su1 prod

echo ""
echo "[7/7] round 3 MINOR: every OTHER Message VPN the browser web transport can reach"
# The nginx /mesh endpoint forwards the SMF WebSocket for ANY Message VPN name the client puts in
# its login, and a broker's built-in `default` VPN is enabled with web transport on and basic
# authentication type `none` (stock state printed below — measured on this broker, not assumed).
# The install check therefore ENUMERATES every VPN (following SEMP paging) and turns the browser
# web transport off on every VPN that is not an organization's own. tests/10 re-opens the stock
# state, measures the hole, measures three mitigations, and re-applies this one.
python3 - <<'PYEOF' || fail=1
import json, sys, urllib.request, base64
AUTH = 'Basic ' + base64.b64encode(b'admin:admin').decode()
BASE = 'http://127.0.0.1:8081/SEMP/v2/config'
def call(method, url, body=None):
    r = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode(),
                               headers={'Authorization': AUTH, 'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(r))
def get_all(path, page=100):
    url = f'{BASE}/{path}?count={page}'; out = []
    while url:
        d = call('GET', url)
        if d['meta']['responseCode'] != 200: raise SystemExit('SEMP error ' + json.dumps(d['meta']))
        out += d.get('data', []); url = d['meta'].get('paging', {}).get('nextPageUri')
    return out
ORGS = {'orgA', 'orgB'}
ok = True
for v in get_all('msgVpns'):
    n = v['msgVpnName']
    if n in ORGS: continue
    state = {k: v.get(k) for k in ('enabled', 'serviceWebPlainTextEnabled', 'serviceWebTlsEnabled', 'authenticationBasicEnabled', 'authenticationBasicType')}
    print(f'  --    VPN {n}: STOCK state {json.dumps(state)}')
    if v.get('enabled') and (v.get('serviceWebPlainTextEnabled') or v.get('serviceWebTlsEnabled')):
        r = call('PATCH', f'{BASE}/msgVpns/{n}', {'serviceWebPlainTextEnabled': False, 'serviceWebTlsEnabled': False})
        if r['meta']['responseCode'] != 200: print('  FAIL  cannot turn web transport off on VPN', n, r['meta'].get('error')); ok = False; continue
    after = call('GET', f'{BASE}/msgVpns/{n}')['data']
    if after.get('enabled') and (after.get('serviceWebPlainTextEnabled') or after.get('serviceWebTlsEnabled')):
        print(f'  FAIL  VPN {n} is still reachable by the browser web transport'); ok = False
    else:
        print(f'  ok    VPN {n}: browser web transport off (plain={after.get("serviceWebPlainTextEnabled")} tls={after.get("serviceWebTlsEnabled")} enabled={after.get("enabled")})')
sys.exit(0 if ok else 1)
PYEOF

echo ""
if [ "$fail" -ne 0 ]; then
  echo "provision.sh: FAILED — see above"
  exit 1
fi

# Prove the substitution exception was stored verbatim (candidate (a)'s whole premise) and
# that the leg-13 exact-topic exception carries no wildcard character.
echo "Subscribe topic exceptions as the broker stores them (orgA):"
for prof in acl-orgA-su1-dev acl-orgA-su1-dev-catalog acl-orgA-stream-subst; do
  curl -s -u "$ADMIN_USER:$ADMIN_PASS" \
    "$SEMP_URL/msgVpns/orgA/aclProfiles/$prof/subscribeTopicExceptions" \
    | python3 -c "
import json, sys
for e in json.load(sys.stdin).get('data', []):
    print('  {:28s} {:5s} {}'.format(e['aclProfileName'], e['subscribeTopicExceptionSyntax'],
                                     e['subscribeTopicException']))
"
done

echo ""
echo "Client usernames -> ACL profile resolution (both VPNs):"
for vpn in orgA orgB; do
  curl -s -u "$ADMIN_USER:$ADMIN_PASS" \
    "$SEMP_URL/msgVpns/$vpn/clientUsernames?select=clientUsername,aclProfileName,clientProfileName,enabled&count=100" \
    | python3 -c "
import json, sys
for c in json.load(sys.stdin).get('data', []):
    print('  {:6s} {:26s} -> acl={:26s} client-profile={:18s} enabled={}'.format('$vpn', c['clientUsername'], c.get('aclProfileName'), c.get('clientProfileName'), c.get('enabled')))
"
done

echo ""
echo "Client profiles as the broker stores them (both VPNs):"
for vpn in orgA orgB; do
  curl -s -u "$ADMIN_USER:$ADMIN_PASS" \
    "$SEMP_URL/msgVpns/$vpn/clientProfiles?count=100" \
    | python3 -c "
import json, sys
for c in json.load(sys.stdin).get('data', []):
    print('  {:6s} {:16s} send={!s:5} recv={!s:5} endpointCreate={!s:5} shared={!s:5} txn={!s:5} bridge={!s:5} maxEgress={} maxIngress={} maxEndpoints={}'.format('$vpn', c['clientProfileName'], c['allowGuaranteedMsgSendEnabled'], c['allowGuaranteedMsgReceiveEnabled'], c['allowGuaranteedEndpointCreateEnabled'], c['allowSharedSubscriptionsEnabled'], c['allowTransactedSessionsEnabled'], c['allowBridgeConnectionsEnabled'], c['maxEgressFlowCount'], c['maxIngressFlowCount'], c['maxEndpointCountPerClientUsername']))
"
done

echo ""
echo "IDENTITY-BINDING ASSERTION (round 2 BLOCKER/MAJOR, extended round 3; round 4 MINOR 2: the SAME check the suite asserts and tests/11 plants violations against) — read back from SEMP, both VPNs, following paging:"
# ROUND 4 (MINOR 2): this used to be a separate Python re-implementation that never checked the
# stock `default` ACL profile or the exception syntax and had no planted violation run against it.
# It is now the one SDK-free JS function (tests/lib/acl-check.mjs); tests/11 runs every planted
# violation through this exact entry point as well as through the function.
BIND_FAIL=0
node tools/acl-check.mjs orgA orgB || BIND_FAIL=1
if [ "$BIND_FAIL" -ne 0 ]; then echo "provision.sh: IDENTITY-BINDING ASSERTION FAILED"; exit 1; fi
echo ""
echo "Provisioning OK."
exit 0
