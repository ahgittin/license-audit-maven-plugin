License Audit Maven Plugin
---

This maven plugin shows dependencies of a project and metadata including license information,
either as a *tree*, a *list*, or as a *CSV* formatted text file suitable for inclusion in
the types of spreadsheets and reports big companies typically want.

Note this requires Maven version 3.1.


# Installation

This project is not currently uploaded to maven central, so you'll need to download it and then

    mvn clean install

It should now be ready for use.


# Basic Usage

To run the plugin and show dependency information, once it's installed,
simply go to the directory of the project you're interested in, 
and run:

    mvn org.heneveld.maven:license-audit-maven-plugin:report

This generates a detailed tree view of the project and 
all the dependencies (transitive) which will bundled with it,
including license information and project authors/URL info, of the form:

```
org.heneveld.maven:license-audit-maven-plugin:1.0-SNAPSHOT
  Name: license-audit-maven-plugin Maven Plugin
  License: ASL2
  URL: http://maven.apache.org
  Dependencies:
    org.apache.maven:maven-compat:jar:3.3.3 (compile, included, detail below)
    ...
    org.codehaus.plexus:plexus-utils:jar:3.0.15 (compile, included, detail below)
    org.apache.maven:maven-core:jar:3.3.3 (compile, included, detail below)
    ...
    junit:junit:jar:4.8.2 (test, excluded from report)
  Dependent projects detail:
  +-org.apache.maven:maven-compat:3.3.3
  |   ...
  |   Dependencies:
  |     org.apache.maven:maven-model:jar:3.3.3 (compile, included, detail below)
  |     org.codehaus.plexus:plexus-utils:jar:3.0.20 (compile, version 3.0.15 included, from org.heneveld.maven:license-audit-maven-plugin:1.0-SNAPSHOT)
  |     ...
  |   Dependent projects detail:
  |   +-org.apache.maven:maven-model:3.3.3
  |   |   ...
  :
  +-org.codehaus.plexus:plexus-utils:3.0.15
  |   ...
  +-org.apache.maven:maven-core:3.3.3
  |   ...
  |   Dependencies:
  |     org.apache.maven:maven-model:jar:3.3.3 (compile, included, from org.apache.maven:maven-compat:3.3.3)
  |     org.apache.maven:maven-settings-builder:jar:3.3.3 (compile, included, detail below)
  |     ...
  |   Dependent projects detail:
  |   +-org.apache.maven:maven-settings-builder:3.3.3
  |   |  ...
  :
```

## Summary

You can also create a summary tree showing info for test and optional dependencies,
including those which won't be bundled (test, provided, and runtime),
without license information -- like `mvn dependency:tree` on steroids:

    mvn org.heneveld.maven:license-audit-maven-plugin:report \
        -Dlicense-audit.format=summary \
        -Dlicense-audit.suppressLicenseInfo=true \
        -Dlicense-audit.includeDependencyScopes=all \
        -Dlicense-audit.includeOptionalDependencies=true

The output looks like this:

```
org.heneveld.maven:license-audit-maven-plugin:1.0-SNAPSHOT
+-org.apache.maven:maven-compat:3.3.3 (compile)
| +-org.apache.maven:maven-model:3.3.3 (compile)
| | +-org.codehaus.plexus:plexus-utils:3.0.20 (compile, v 3.0.15 used)
| | +-junit:junit:4.11 (test, v 4.8.2 used)
| :
:
+-org.codehaus.plexus:plexus-utils:3.0.15 (compile)
:
+-org.apache.maven:maven-core:3.3.3 (compile)
| +-org.apache.maven:maven-model:3.3.3 (compile, reported above)
| +-org.apache.maven:maven-settings-builder:3.3.3 (compile)
| | ...
:
```

## CSV

Or you can generate a CSV report to a file,
omitting dependencies which are not bundled
and simplifying the dependency list to show only the dependency ID's: 

    mvn org.heneveld.maven:license-audit-maven-plugin:report \
        -Dlicense-audit.format=csv \
        -Dlicense-audit.listOnlyIncludedDependencies=true \
        -Dlicense-audit.listIdsOnly=true \
        -Dlicense-audit.outputFile=dependencies-licenses.csv


# Report Types

The following report types are supported:

* `tree` - a fairly detailed tree view of all dependencies
* `summary` - a summary tree with one line for each dependency;
  similar to `mvn dependency:tree`, but showing more useful info
  and explicitly showing dependencies everywhere they are referenced
* `list` - even more detail of each dependency, listed one after the other (no tree structure)
* `csv` - a comma-separated-values file with all details from `list`, suitable for importing into a spreadsheet

These can be set with `-Dlicense-audit.format=csv`. The default is `tree`.


# Other Configuration

This plugin supports the following additional configuration:

* `license-audit.format` - the format of the report (see above)
* `license-audit.outputFile` - write a report to this file, in addition to logging it
* `license-audit.depth` - maximum depth to traverse, or -1 for full depth
* `license-audit.includeDependencyScopes` - which dependency scopes should be reported, defaulting to `compile,runtime`,
  with `all` recognized as a synonym for `compile,runtime,test,system,provided`
* `license-audit.includeOptionalDependencies` - whether to report on optional dependencies on the project, defaulting to `false`
* `license-audit.listOnlyIncludedDependencies` - whether to omit any mention of dependencies which are not included;
  by default (`false`), the report mentions all dependencies, not just those which are included in the report's traversal
  (i.e. by default *test* dependencies will be mentioned, but will not be included as nodes with their dependencies);
  this can be useful for generating reports for audiences who might be scared by a LGPL test depencency
* `license-audit.listIdsOnly` - whether to omit detail of dependencies in the dependencies list, 
  again useful for some audiences, and for CSV reports
* `license-audit.suppressLicenseInfo` - don't show any license details


# Copyright and License

This software is copyright (c) 2015 by Alex Heneveld.

This software is released under the Apache Software License, v2.


# TODO

* "reported below" mistaken
* compile dep in test dep not included 
