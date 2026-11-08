---
tags: [skill-creator]
max_turns: 60
timeout_seconds: 1800
allowed_tools: [Read, Glob, Grep, Skill, Write, Edit, Bash]
---

deploy the file mesh SOURCE over ssh to deploy@mi.example.invalid, port 22, using my ssh agent. install it in /opt/fm/source, it's the docker image registry.example.invalid/file-mesh:2.2.0. use the package.json and the source yaml here, version 3.

Input files: package.json, application-source-file-v2.yml
