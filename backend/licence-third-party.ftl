<#--
  The licence gate's report template (plan 010 T0.4b). It renders the same report as the plugin's default, and it
  also enforces two rules the plugin's own check cannot express. Both rules abort the build with <#stop>.

  RULE 1 -- Q-L2 (a'), fail closed (OR-66). The plugin's own check passes a dependency when ANY one of its
  declared licences is on the allow-list ("is OK since it is also licensed under ..."). The owner ruled the
  opposite: a dependency that declares several licences passes only when every one is on the allow-list, or its
  own text states a choice and that election is recorded in licence-exceptions.txt -- which replaces its
  licence list with ONE approved name. So a dependency that still has several licence names here, one of them
  off the allow-list, has no recorded election.
  This template does NOT read licence text, so it cannot tell a STATED choice (OR-66: elect it, record it, no
  new ruling) from NO stated connective (OR-66: fail closed, the owner approves). A person reads the text and
  writes the line (OR-84: an election is an explicit reviewed line, one per artifact and version); the message below says which of the two paths applies. (allowList below is the REQ-014
  allow-list; scripts/check-licences.py fails when it differs from licence-allowlist.txt and the pom.)

  RULE 2 -- exact version. Every exception line approves one exact coordinate AND version. If any version of a
  group:artifact is listed in licence-exceptions.txt, the resolved version must be one of the listed ones.
  (An embedded-content approval, e.g. for jackson-core, does not otherwise fail on a version bump, because the
  jar's own POM licence stays allow-listed. This rule is what makes a bump red.)

  RULE 3 -- first-party. The name "Solace first-party" is accepted only for this reactor's own group
  (com.solace.sm) or an exact line in licence-exceptions.txt (JCSMP).

  Context from the plugin: dependencyMap = entries of (MavenProject -> array of licence names, after
  licenseMerges and overrides).
-->
<#assign allowList = ["Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "PostgreSQL"]>
<#assign rawExceptions><#include "licence-exceptions.txt" parse=false></#assign>
<#assign approved = {}>
<#assign approvedGa = {}>
<#list rawExceptions?split("\n") as rawLine>
    <#assign line = rawLine?trim>
    <#if line != "" && !line?starts_with("#") && line?contains("=")>
        <#assign key = line?keep_before("=")?trim>
        <#assign approved = approved + {key: true}>
        <#assign approvedGa = approvedGa + {key?keep_before("--")?trim + "--" + key?keep_after("--")?keep_before("--")?trim: true}>
    </#if>
</#list>
<#function licenseFormat licenses>
    <#assign result = ""/>
    <#list licenses as license>
        <#assign result = result + " (" +license + ")"/>
    </#list>
    <#return result>
</#function>
<#function artifactFormat p>
    <#return p.groupId + ":" + p.artifactId + ":" + p.version + " - " + (p.name!"no name") + " - " + (p.url!"no url defined")>
</#function>

<#if dependencyMap?size == 0>
The project has no dependencies.
<#else>
Lists of ${dependencyMap?size} third-party dependencies.
    <#list dependencyMap as e>
        <#assign project = e.getKey()/>
        <#assign licenses = e.getValue()/>
        <#assign coord = project.groupId + "--" + project.artifactId + "--" + project.version>
        <#assign ga = project.groupId + "--" + project.artifactId>
        <#if licenses?size gt 1>
            <#list licenses as declared>
                <#if !allowList?seq_contains(declared)>
                    <#stop "LICENCE GATE (Q-L2 a'): " + project.groupId + ":" + project.artifactId + ":" + project.version + " declares " + licenses?size + " licences" + licenseFormat(licenses) + ", at least one off the allow-list, and no election is recorded. Read the artifact's own licence text. If it STATES the choice (an exact SPDX OR, or 'at your option'), add one explicit reviewed line to backend/licence-exceptions.txt (OR-84: the gate never detects the connective itself) that elects the allow-listed option, citing OR-66 and quoting the file and the connective verbatim in the comment: no new ruling is needed. If it states NO connective, an owner ruling is required, or replace the dependency.">
                </#if>
            </#list>
        </#if>
        <#if licenses?seq_contains("Solace first-party") && project.groupId != "com.solace.sm" && !approved[coord]??>
            <#stop "LICENCE GATE (first-party): " + project.groupId + ":" + project.artifactId + ":" + project.version + " claims the name 'Solace first-party' but is neither in group com.solace.sm nor an exact line in licence-exceptions.txt.">
        </#if>
        <#if approvedGa[ga]?? && !approved[coord]??>
            <#stop "LICENCE GATE (exact version): " + ga?replace("--", ":") + " resolves to version " + project.version + ", but licence-exceptions.txt approves only other versions of it. A version change needs a new owner ruling and a new exception line.">
        </#if>
    ${licenseFormat(licenses)} ${artifactFormat(project)}
    </#list>
</#if>
