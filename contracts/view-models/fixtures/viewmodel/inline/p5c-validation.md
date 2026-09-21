
```jsonc
{
  "_note": "Same view model as P5, with these two fields carrying validations.",
  "blockedField": {"key":"namespace","renderedPath":"cache-engine.namespace","controlKind":"text","value":{"kind":"unset"},"visible":true,"enabled":true,"label":"Namespace","validations":[{"severity":"block","code":"REQUIRED","message":"This field is required."}]},
  "warnedField": {
    "key": "max-size",
    "renderedPath": "cache-engine.max-size",
    "controlKind": "number",
    "value": {"kind":"value","value":5000000,"source":"draft"},
    "visible": true,
    "enabled": true,
    "label": "Max entries",
    "helperText": "No MI default: Java `int maxSize` is 0, which evicts everythi…",
    "validations": [{"severity":"warn","code":"ABOVE_RECOMMENDED","message":"Above the range Solace recommends (1–1000000)."}]
  },
  "_renderedExcludesTheBlockedField": ["cache-engine.mode","cache-engine.provider","cache-engine.solace.enabled","cache-engine.ttl"]
}
```
