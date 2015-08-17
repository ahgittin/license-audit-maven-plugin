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
and run:sort

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
omitting any mention of dependencies which are not bundled,
and preferring certain licenses for reporting purposes when
products are dual- or multi- licensed: 

    mvn org.heneveld.maven:license-audit-maven-plugin:report \
        -Dformat=csv \
        -DlistDependencyIdOnly=true \
        -DsuppressExcludedDependencies=true \
        -DlicensesPreferred=ASL2,ASL,EPL1,BSD-2-Clause,BSD-3-Clause \
        -DoutputFile=dependencies-licenses.csv

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
* `csv` - a comma-separated-values file with all details from `report`, suitable for importing into a spreadsheet
* `sorted-{report,list,csv}` - as `report` or `list` or `csv` but sorted

These can be set with `-Dformat=csv`. The default is `tree`.


# Other Configuration

This plugin supports the following additional configuration:

* `outputFile` - write a report to this file, in addition to logging it
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
* `licensesPreferred` - specifies the preference order of licenses; this is used to extract a single code when
  multiple licenses are supplied to maven (with no comments, as comments sometimes indicate mixed licensing;
  the absence of comments is interpreted to mean multiply licensed);
  the format should be a comma-separated list of license codes, e.g. `ASL2,EPL1`
* `overridesFile` - allow project information (licenses or URLs) to come from a file;
  useful if a project's pom is missing data (or has wrong information);
  the format should be YAML specifying a list of entries each with a project `id` or `ids` and 
  metadata to override, such as `license` or `url`;
  e.g. `[ { id: "org.codehaus.jettison:jettison", license: ASL2, url: "https://github.com/codehaus/jettison" }, 
  { ids: [ "dom4j:dom4j:*", "dom4j:dom4j-core:1.4-dev-8" ], 
    license: { name: "BSD style", url: "http://dom4j.sourceforge.net/dom4j-1.6.1/license.html" },
    url: "http://dom4j.sourceforge.net/" } ]`
* `extrasFile` - allow info for additional projects to be supplied from a file
  and included in the report;
  useful if you want to include non-java dependencies;
  the format is the same as for `overridesFile`, but `name` and `version` are also supported
* `extrasFiles` - as `extrasFile` but allowing a list (using the system's path separator character) 
* `onlyExtras` - whether only to show info for items in `extraFile` or `extraFiles`, 
  i.e. ignoring maven dependencies (no tree/dependency structure will be shown);
  this is useful esp with `notices` for a JAR or source build (where dependencies are not included)

# Other Mojos

There is also `notices` available which takes most of the same config options,
and generates a notices report including attribution requirements.  The `notice` key can be used 
in `extrasFile` and `overridesFile` to provide custom notices (e.g. copyright requirements). 


# Enhancements

The following things would be nice to add/change:

* Re-use the options in `dependency:build-classpath` and the code there to scan dependencies in scope
  (or even contribute this to the maven dependency plugin); but note that target seems to mangle the order

* Show what drags in a dependency (esp in CSV view)
 
* Produce an HTML tree

* Consider using SPDX (standard metadata for licensing)


# Copyright and License

This software is copyright (c) 2015 by Alex Heneveld and Cloudsoft Corporation.

This software is released under the Apache Software License, v2.
