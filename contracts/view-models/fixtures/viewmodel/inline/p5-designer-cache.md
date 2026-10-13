
```jsonc
{
  "solution": "cache",
  "packageVersion": 1,
  "components": [
    {
      "instanceKey": "i1",
      "type": "cache-engine",
      "role": "none",
      "_sectionsInRenderOrder": [
        {"title":"General","order":0,"fieldCount":3,"allVisibleFieldsAdvanced":false},
        {"title":"Keying","order":1,"fieldCount":3,"allVisibleFieldsAdvanced":false},
        {"title":"Expiry","order":2,"fieldCount":3,"allVisibleFieldsAdvanced":true},
        {"title":"Sizing","order":3,"fieldCount":2,"allVisibleFieldsAdvanced":false},
        {"title":"Topics","order":4,"fieldCount":2,"allVisibleFieldsAdvanced":false},
        {"title":"Hit response","order":5,"fieldCount":2,"allVisibleFieldsAdvanced":true},
        {"title":"Broker","order":6,"fieldCount":8,"allVisibleFieldsAdvanced":false},
        {"title":"Management","order":7,"fieldCount":3,"allVisibleFieldsAdvanced":true}
      ],
      "_note": "14 of 26 fields shown — one per control kind and per state. Real forms reach 206.",
      "fields": [
        {"key":"mode","renderedPath":"cache-engine.mode","controlKind":"select","value":{"kind":"value","value":"READ","source":"default"},"options":["READ","READ_WRITE","ADMIN"],"visible":true,"enabled":true,"label":"Mode","helperText":"READ_WRITE exposes the write endpoint; ADMIN additionally exp…"},
        {"key":"provider","renderedPath":"cache-engine.provider","controlKind":"select","value":{"kind":"value","value":"caffeine","source":"default"},"options":["caffeine","redis","jcache"],"visible":true,"enabled":true,"discriminator":true,"label":"Provider","helperText":"Only caffeine reports stats and size. Java type is String, no…"},
        {"key":"namespace","renderedPath":"cache-engine.namespace","controlKind":"text","value":{"kind":"unset"},"visible":true,"enabled":true,"label":"Namespace","validations":[{"severity":"block","code":"REQUIRED","message":"This field is required."}]},
        {"key":"key-source","renderedPath":"cache-engine.key-source","controlKind":"select","value":{"kind":"unset"},"options":["TOPIC","PAYLOAD","HEADER","COMPOSITE"],"visible":true,"enabled":true,"discriminator":true,"label":"Key source","validations":[{"severity":"block","code":"REQUIRED","message":"This field is required."}]},
        {"key":"topic-key-levels","renderedPath":"cache-engine.topic-key-levels","controlKind":"text","value":{"kind":"unset"},"visible":false,"enabled":true,"label":"Topic key levels","helperText":"Java type is String, not int, and there is no field initializ…","hiddenBy":["key-source"]},
        {"key":"max-size","renderedPath":"cache-engine.max-size","controlKind":"number","value":{"kind":"unset"},"visible":true,"enabled":true,"label":"Max entries","helperText":"No MI default: Java `int maxSize` is 0, which evicts everythi…","validations":[{"severity":"block","code":"REQUIRED","message":"This field is required."}]},
        {"key":"ttl","renderedPath":"cache-engine.ttl","controlKind":"duration","value":{"kind":"unset"},"visible":true,"enabled":true,"label":"TTL","helperText":"Spring Duration syntax, e.g. 120m or PT2H. No MI default: Jav…"},
        {"key":"expiry-header-key-pointer","renderedPath":"cache-engine.expiry-header-key-pointer","controlKind":"text","value":{"kind":"unset"},"visible":true,"enabled":true,"advanced":true,"label":"Expiry header key pointer"},
        {"key":"cache-hit-response.template-mappings","renderedPath":"cache-engine.cache-hit-response.template-mappings","controlKind":"key-value","value":{"kind":"unset"},"visible":true,"enabled":true,"advanced":true,"label":"Template mappings","helperText":"Map<String,String>. The MI's own sample ships an absolute pat…"},
        {"key":"solace.enabled","renderedPath":"cache-engine.solace.enabled","controlKind":"switch","value":{"kind":"value","value":true,"source":"draft"},"visible":true,"enabled":true,"discriminator":true,"label":"Solace enabled","helperText":"Java default is false (no field initializer), so an instance …"},
        {"key":"solace.host","renderedPath":"cache-engine.solace.host","controlKind":"text","value":{"kind":"unset"},"visible":true,"enabled":true,"label":"Host","validations":[{"severity":"block","code":"REQUIRED","message":"This field is required."}]},
        {"key":"solace.password","renderedPath":"cache-engine.solace.password","controlKind":"secret","value":{"kind":"secretUnset"},"visible":true,"enabled":true,"masked":true,"label":"Password","helperText":"secretRef. Masked in form, diff, export and history (I6)."},
        {"key":"solace.management.enabled","renderedPath":"cache-engine.solace.management.enabled","controlKind":"switch","value":{"kind":"value","value":true,"source":"draft"},"visible":true,"enabled":true,"advanced":true,"discriminator":true,"label":"Management plane enabled"},
        {"key":"solace.management.management-queue","renderedPath":"cache-engine.solace.management.management-queue","controlKind":"text","value":{"kind":"value","value":"mgmt-q","source":"draft"},"visible":true,"enabled":true,"advanced":true,"label":"Management queue","helperText":"Two-level nesting, and the predicate carries the full ancesto…"}
      ],
      "actions": [{"key":"TEST_BROKER_CONNECTION","kind":"test-connection","visible":true,"enabled":true,"inputs":["solace.host","solace.vpn","solace.username","solace.password"],"label":"Test connection"}]
    }
  ],
  "rendered": [
    {"instanceKey":"i1","renderedPath":"cache-engine.mode","value":"READ"},
    {"instanceKey":"i1","renderedPath":"cache-engine.provider","value":"caffeine"},
    {"instanceKey":"i1","renderedPath":"cache-engine.solace.enabled","value":true},
    {"instanceKey":"i1","renderedPath":"cache-engine.solace.management.enabled","value":true},
    {"instanceKey":"i1","renderedPath":"cache-engine.solace.management.leader-election","value":"STANDALONE"},
    {"instanceKey":"i1","renderedPath":"cache-engine.solace.management.management-queue","value":"mgmt-q"},
    {"instanceKey":"i1","renderedPath":"cache-engine.solace.messaging-mode","value":"MESSAGING"}
  ],
  "excluded": [{"instanceKey":"i1","key":"TEST_BROKER_CONNECTION","reason":"action"},{"instanceKey":"i1","key":"payload-key-json-pointer","reason":"hidden"},{"instanceKey":"i1","key":"topic-key-levels","reason":"hidden"}],
  "metrics": [
    {"instanceKey":"i1","name":"cache.hit_rate","metricType":"gauge","unit":"ratio","collectionState":"pending-config-change"},
    {"instanceKey":"i1","name":"cache.hit_count","metricType":"counter","unit":"events","collectionState":"pending-config-change"},
    {"instanceKey":"i1","name":"cache.miss_count","metricType":"counter","unit":"events","collectionState":"pending-config-change"},
    {"instanceKey":"i1","name":"cache.eviction_count","metricType":"counter","unit":"events","collectionState":"pending-config-change"},
    {"instanceKey":"i1","name":"cache.size","metricType":"gauge","unit":"entries","collectionState":"pending-config-change"}
  ],
  "status": {"version":{"state":"OK","declared":"2.4","observed":"2.4","editingBlocked":false},"config":{},"replicas":[{"id":"r1","health":"GREEN"}],"healthRollup":"GREEN","healthAvailability":"declared"},
  "diagnostics": [
    {"code":"GATING_STATE_UNKNOWN","message":"No restart-observed values for i1; cache.hit_rate is reported pending rather than asserted collectable.","instanceKey":"i1"},
    {"code":"GATING_STATE_UNKNOWN","message":"No restart-observed values for i1; cache.hit_count is reported pending rather than asserted collectable.","instanceKey":"i1"},
    {"code":"GATING_STATE_UNKNOWN","message":"No restart-observed values for i1; cache.miss_count is reported pending rather than asserted collectable.","instanceKey":"i1"},
    {"code":"GATING_STATE_UNKNOWN","message":"No restart-observed values for i1; cache.eviction_count is reported pending rather than asserted collectable.","instanceKey":"i1"},
    {"code":"GATING_STATE_UNKNOWN","message":"No restart-observed values for i1; cache.size is reported pending rather than asserted collectable.","instanceKey":"i1"}
  ]
}
```
