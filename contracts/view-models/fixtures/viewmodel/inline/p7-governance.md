
```jsonc
{
  "_note": "The status block is the whole contract for this screen. Three real cases.",
  "healthy": {"version":{"state":"OK","declared":"2.4","observed":"2.4","editingBlocked":false},"config":{},"replicas":[{"id":"r1","health":"GREEN"}],"healthRollup":"GREEN","healthAvailability":"declared"},
  "twoStandbyReplicas": {"version":{"state":"OK","declared":"2.4","observed":"2.4","editingBlocked":false},"config":{},"replicas":[{"id":"r1","health":"INACTIVE_REPLICA"},{"id":"r2","health":"INACTIVE_REPLICA"}],"healthRollup":"INACTIVE_REPLICA","healthAvailability":"declared"},
  "noHealthBlockDeclared": {"version":{"state":"VERSION_UNVERIFIED","declared":"2.4","editingBlocked":false},"config":{},"replicas":[],"healthRollup":"NOT_APPLICABLE","healthAvailability":"not-declared"},
  "_metricCollectionStates": [
    {"instanceKey":"i1","name":"cache.hit_rate","metricType":"gauge","unit":"ratio","collectionState":"pending-config-change"},
    {"instanceKey":"i1","name":"cache.hit_count","metricType":"counter","unit":"events","collectionState":"pending-config-change"},
    {"instanceKey":"i1","name":"cache.miss_count","metricType":"counter","unit":"events","collectionState":"pending-config-change"},
    {"instanceKey":"i1","name":"cache.eviction_count","metricType":"counter","unit":"events","collectionState":"pending-config-change"},
    {"instanceKey":"i1","name":"cache.size","metricType":"gauge","unit":"entries","collectionState":"pending-config-change"}
  ]
}
```
