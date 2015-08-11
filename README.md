License Audit Maven Plugin
---

This maven plugin shows dependencies of a project and metadata including license information,
either as a detailed *tree*, a *summary* tree, a detailed *list*, or a *CSV* formatted text file.
The last one, and some of the options, are designed to facilitate making
the types of spreadsheets and reports big companies typically want.

It's also useful -- the *summary* tree in particular -- to debug version conflict woes,
making it easy to see where different versions are coming from.
An irritation with `mvn dependency:tree` is that it only lists each dependency once,
where it is actually pulled in; other items which depend on it are suppressed.
It quickly gets very complicated; this seems to work nicely even on a big project,
but there may be bugs. 

Note this requires Maven version 3.1.


# Installation

This project is not currently uploaded to maven central, so you'll need to download it and then

    mvn clean install

It should now be ready for use.


# Usage Examples

## Detail Tree View

To run the plugin and show dependency information, once it's installed,
simply go to the directory of the project you're interested in, 
and run:

    mvn org.heneveld.maven:license-audit-maven-plugin:report

This generates a detailed **tree** view of the project and 
all the dependencies (transitive) which will bundled with it.
For each, license information and project authors/URL info is included,
a summary explanation of every dependency it declares,
and detail of those dependencies which maven/aether deems "pulled in" by this node.
(Where a dependency is referenced in multiple places, it is "pulled in" by exactly one referent,
usually the first, as shown by `mvn dependency:tree`.)  
This tree looks like:

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
  Dependencies pulled in here detail:
  +-org.apache.maven:maven-compat:3.3.3
  |   ...
  |   Dependencies:
  |     org.apache.maven:maven-model:jar:3.3.3 (compile, included, detail below)
  |     org.codehaus.plexus:plexus-utils:jar:3.0.20 (compile, version 3.0.15 included, from org.heneveld.maven:license-audit-maven-plugin:1.0-SNAPSHOT)
  |     ...
  |   Dependencies pulled in here detail:
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
  |   Dependencies pulled in here detail:
  |   +-org.apache.maven:maven-settings-builder:3.3.3
  |   |  ...
  :
```

## Summary Tree View

You can also create a **summary** tree giving one-line info for each dependency,
merging the info of the detail tree so that all included dependencies are shown,
each expanded only once, where it is pulled in.
This example creates such a tree with all dependencies in scope at the root,
including those which won't be bundled (test, provided, and runtime),
without license information, and 
with info on optional dependencies in dependent projects:

    mvn org.heneveld.maven:license-audit-maven-plugin:report \
        -Dformat=summary \
        -DincludeDependencyScopes=all \
        -DsuppressLicenseInfo=true \
        -DlistUnusedNestedOptionalDependencies=true

It's like `mvn dependency:tree` on steroids,
much terser than *tree* (or *list*) but giving much of the same information
once you know how to read it.
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

## CSV for Enterprise License Reports

Here's a syntax for generating a **CSV** report to a file,
omitting any mention of dependencies which are not bundled: 

    mvn org.heneveld.maven:license-audit-maven-plugin:report \
        -Dformat=csv \
        -DlistDependencyIdOnly=true \
        -DsuppressExcludedDependencies=true \
        -Doutput=dependencies-licenses.csv

If you're generating a report for a project used as a dependency instead of as a binary,
you would normally also add `-DsuppressExcludedDependencies=true` (because the optional
dependencies won't be transitively required).


# Report Types

The following report types are supported:

* `tree` - a fairly detailed tree view of all dependencies
* `summary` - a summary tree with one line for each dependency;
  similar to `mvn dependency:tree`, but showing more useful info
  and explicitly showing dependencies everywhere they are referenced
* `report` - even more detail of each dependency, listed one after the other (no tree structure)
* `list` - one line on each dependency, listed one after the other (no tree structure)
* `sorted-list` - as `list` but sorted
* `csv` - a comma-separated-values file with all details from `report`, suitable for importing into a spreadsheet

These can be set with `-Dformat=csv`. The default is `tree`.


# Other Configuration

This plugin supports the following additional configuration:

* `output` - write a report to this file, in addition to logging it
* `format` - the format of the report (see above)
* `depth` - maximum depth to traverse, or -1 for full depth
* `includeDependencyScopes` - which dependency scopes should be reported, 
  defaulting to `compile,runtime`,
  with `all` recognized as a synonym for `compile,runtime,test,system,provided`;
  this affects both resolution and reporting,
  with dependencies restricted to these scopes on the root project (but not transitive) to resolve dependencies,
  and then these scopes shown on all nodes (transitively) when reporting
  (NB: omitting `compile` is likely to yield a rather useless report) 
* `excludeRootOptionalDependencies` - whether to report on optional dependencies on the project, defaulting to `false`
* `listUnusedNestedOptionalDependencies` - whether to show optional dependencies below the root which are not used, 
  defaulting to `false`,
  but useful when you want to see what optional dependencies and versions are suggested by included projects;
  if an optional dependency *is* included elsewhere, even if a different version, it will always be listed;
  if this is specified along with `suppressExcludedDependencies`, this one dominates, 
  and all optional dependencies will be listed 
* `suppressExcludedDependencies` - whether to omit any mention of dependencies which are not included;
  by default (`false`), the report mentions non-optional excluded dependencies
  (i.e. by default *test* dependencies will be mentioned, but *their* dependencies will not be listed);
  this can be useful for generating reports for audiences who might be scared by a LGPL test dependency
* `listDependencyIdOnly` - whether to omit detail of dependencies in the dependencies list, 
  again useful for some audiences and for CSV reports; default `false` (no effect on the *summary* or *list* formats)
* `suppressLicenseInfo` - don't show any license details


# Copyright and License

This software is copyright (c) 2015 by Alex Heneveld and Cloudsoft Corporation.

This software is released under the Apache Software License, v2.
