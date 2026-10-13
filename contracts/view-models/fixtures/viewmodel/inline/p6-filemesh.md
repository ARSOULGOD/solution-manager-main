
```jsonc
{
  "_note": "Two composed components. 103 fields, 27 virtual, 4 hidden by predicates.",
  "components": [
    {"instanceKey":"src","type":"COMP_SOURCE_FTP@FILETOFILE","role":"source","sectionCount":8,"fieldCount":65,"sectionTitles":["FILE MESH MANAGER","SOLACE LVQ","DIRECTORY REPLICATION","FTP_SETTINGS","GENERAL","INFO","DIRECTORY WILDCARD","SCHEDULER"]},
    {"instanceKey":"snk","type":"COMP_SINK_SOLACE@FILETOFILE","role":"sink","sectionCount":3,"fieldCount":38,"sectionTitles":["BROKER_LIST","DATA_PLANE","MANAGEMENT_PLANE"]}
  ],
  "_virtualFieldsLookIdenticalToRealOnes": [
    {"key":"SELECT_BROKER","renderedPath":null,"controlKind":"select","value":{"kind":"unset"},"visible":true,"enabled":true,"virtual":true,"advanced":true,"label":"SELECT_BROKER","validations":[{"severity":"block","code":"REQUIRED","message":"This field is required."}]},
    {"key":"AUTO_CREATE_TOPIC","renderedPath":null,"controlKind":"switch","value":{"kind":"unset"},"visible":true,"enabled":false,"virtual":true,"label":"AUTO_CREATE_TOPIC"},
    {"key":"AUTO_CREATE_QUEUE","renderedPath":null,"controlKind":"switch","value":{"kind":"unset"},"visible":true,"enabled":false,"virtual":true,"label":"AUTO_CREATE_QUEUE"}
  ],
  "_ordinaryFieldsForComparison": [
    {"key":"file.source.command_center.enabled","renderedPath":"file.source.command_center.enabled","controlKind":"text","value":{"kind":"value","value":"1","source":"default"},"visible":true,"enabled":true,"label":"ENABLED","helperText":"0 -> disabled, 1 -> enabled, sends data to Command Center"},
    {
      "key": "file.source.command_center.useoutputdestinationsolacecredentials",
      "renderedPath": "file.source.command_center.useoutputdestinationsolacecredentials",
      "controlKind": "text",
      "value": {"kind":"value","value":"true","source":"default"},
      "visible": true,
      "enabled": true,
      "advanced": true,
      "label": "USEOUTPUTDESTINATIONSOLACECREDENTIALS",
      "helperText": "true ->  uses output destination solace credentials, false ->…"
    },
    {"key":"file.source.command_center.solace_base_publish_topic","renderedPath":"file.source.command_center.solace_base_publish_topic","controlKind":"text","value":{"kind":"unset"},"visible":true,"enabled":true,"label":"SOLACE_BASE_PUBLISH_TOPIC","helperText":"base publish topic for command center event"}
  ],
  "_renderedCount": 45,
  "_excludedByReason": {"action":3,"virtual":27},
  "status": {"version":{"state":"VERSION_UNVERIFIED","declared":"2.4","editingBlocked":false},"config":{},"replicas":[],"healthRollup":"NOT_APPLICABLE","healthAvailability":"not-declared"}
}
```
