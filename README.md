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
including license information and project authors/URL info.

Or you can create a summary tree showing info for *all* dependencies,
including those which won't be bundled (test, provided, and runtime):

    mvn org.heneveld.maven:license-audit-maven-plugin:report \
        -Dlicense-audit.format=summary \
        -Dlicense-audit.includeDependencyScopes=all

Or you can generate a CSV report to a file,
omitting any mention of dependencies which are not bundled:

    mvn org.heneveld.maven:license-audit-maven-plugin:report \
        -Dlicense-audit.format=csv \
        -Dlicense-audit.listOnlyIncludedDependencies=true \
        -Dlicense-audit.outputFile=dependencies-licenses.csv


# Report Types

The following report types are supported:

* `tree` - a fairly detailed tree view of all dependencies
* `summary` - a summary tree with one line for each dependency;
  similar to `mvn dependency:tree`, but including license info
  (and explicitly showing dependencies everywhere they are referenced)
* `list` - even more detail of each dependency, listed one after the other (no tree structure)
* `csv` - a comma-separated-values file with all details from `list`, suitable for importing into a spreadsheet

These can be set with `-Dlicense-audit.format=csv`. The default is `tree`.


# Other Configuration

This plugin supports the following additional configuration:

* `license-audit.format` - the format of the report (see above)
* `license-audit.outputFile` - write a report to this file, in addition to logging it
* `license-audit.recurse` - whether to recurse; set false to show only one-level deep children
* `license-audit.includeDependencyScopes` - comma-separated list of dependency scopes to traverse,
  defaulting to `compile,runtime`, with `all` understood to follow all scopes (but not optional dependencies, see the next config)
* `license-audit.includeOptionalDependencies` - whether to traverse optional dependencies, defaulting to `false`
* `license-audit.listOnlyIncludedDependencies` - whether to omit any mention of dependencies which are not included;
  by default (`false`), the report mentions all dependencies, not just those which are included in the report's traversal
  (i.e. by default *test* dependencies will be mentioned, but will not be included as nodes with their dependencies);
  this can be useful for generating reports for audiences who might be scared by a LGPL test depencency


# Copyright and License

This software is copyright (c) 2015 by Alex Heneveld.

This software is released under the Apache Software License, v2.
