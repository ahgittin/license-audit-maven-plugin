package org.heneveld.maven.license_audit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.License;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.graph.DependencyNode;
import org.heneveld.maven.license_audit.util.Coords;
import org.heneveld.maven.license_audit.util.LicenseCodes;
import org.heneveld.maven.license_audit.util.ProjectsOverrides;
import org.heneveld.maven.license_audit.util.SimpleMultiMap;

@Mojo( name = "report", defaultPhase = LifecyclePhase.COMPILE)
public class LicenseAuditMojo extends AbstractLicensingMojo {

    @Parameter( defaultValue = "tree", property = "format", required = true )
    private String format;

    @Parameter( defaultValue = "false", property = "suppressExcludedDependencies", required = true )
    private boolean suppressExcludedDependencies;
    
    @Parameter( defaultValue = "false", property = "listUnusedNestedOptionalDependencies", required = true )
    private boolean listUnusedNestedOptionalDependencies;

    @Parameter( defaultValue = "false", property = "listDependencyIdOnly", required = true )
    private boolean listDependencyIdOnly;

    @Parameter( defaultValue = "false", property = "suppressLicenseInfo", required = true )
    private boolean suppressLicenseInfo;

    protected void generateOutput() throws MojoExecutionException {
        if ("tree".equalsIgnoreCase(format)) {
            new TreeReport().run();
        } else if ("summary".equalsIgnoreCase(format)) {
            new SummaryReport().run();
        } else if ("report".equalsIgnoreCase(format)) {
            new ReportReport(false).run();
        } else if ("sorted-report".equalsIgnoreCase(format)) {
            new ReportReport(true).run();
        } else if ("list".equalsIgnoreCase(format)) {
            new ListReport(false).run();
        } else if ("sorted-list".equalsIgnoreCase(format)) {
            new ListReport(true).run();
        } else if ("csv".equalsIgnoreCase(format)) {
            new CsvReport(false).run();
        } else if ("sorted-csv".equalsIgnoreCase(format)) {
            new CsvReport(true).run();
        } else {
            throw new MojoExecutionException("Unknown format (use 'tree', 'list', 'csv', or 'summary'): "+format);
        }
    }

    protected enum DetailLevel { OMITTED, EXCLUDE_FROM_SUMMARY, INCLUDE_IN_SUMMARY_BUT_NOT_USED_OR_EXPANDED, INCLUDE_IN_SUMMARY_EXPANDED_ELSEWHERE, INCLUDE_WITH_DETAIL }
    protected static class DependencyDetail {
        public final String scope;
        public final boolean optional;
        public final DetailLevel level;
        public DependencyDetail(String scope, boolean optional, DetailLevel level) {
            this.scope = scope;
            this.optional = optional;
            this.level = level;
        }
        @Override
        public String toString() {
            return "DependencyDetail [scope=" + scope + ", optional=" + optional + ", level=" + level + "]";
        }
    }
    
    public abstract class AbstractReport {
        String currentProject;
        Set<String> ids;
        boolean doingExtras = false;
        ProjectsOverrides extras = null;
        
        public void setup() throws MojoExecutionException {
            ids = new LinkedHashSet<String>();
            if (!onlyExtras) {
                getLog().debug("Report collected projects for: "+ids);
                getLog().debug("Report collected project error reports for: "+projectErrors.keySet());
                ids.addAll(projectByIdCache.keySet());
                ids.addAll(projectErrors.keySet());
            }
            
            // load extras - just to confirm they are accessible
            extras = loadExtrasTo(null, "extras");
        }
        
        public abstract void run() throws MojoExecutionException;
        
        protected void runExtraProject(String projectId, ProjectsOverrides extras) throws MojoExecutionException {
            startProject(projectId, null);
            Map<String, Object> data = overrides.getOverridesForProject(projectId);
            addProjectEntry("Name", (String)data.get("name"));
            addProjectEntry("Description", (String)data.get("description"));
            addProjectEntry("URL", (String)data.get("url"));
            addProjectEntry("Organization", organizationString(data));
            // TODO contributors not yet elegantly formatted when from overrides (and overrides not used above)
            addProjectEntry("Contributors", toStringPoorMans(data.get("contributors")));
            if (!suppressLicenseInfo) {
                addLicenseInfoEntries(getLicenses(projectByIdCache.get(projectId), projectId));
                addCopyrightInfo(projectByIdCache.get(projectId), projectId);
            }
            // any other verbose info?
            endProject();
        }

        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            currentProject = id;
        }
        
        public void endProject() throws MojoExecutionException {}
        public abstract void addProjectEntry(String key, String value) throws MojoExecutionException;
        
        /** returns dependent projects and the level of detail required for each */
        protected SimpleMultiMap<String,DependencyDetail> runProject(String id) throws MojoExecutionException {
            SimpleMultiMap<String,DependencyDetail> depsResult = new SimpleMultiMap<String,DependencyDetail>();
            
            MavenProject p = getProject(id);
            startProject(id, p);
            
            Set<DependencyNode> dn0 = depNodesByIdCache.get(id);
            if (dn0==null) addError(id, "No dependency node in tree; should this be included?");
            
            Object err = projectErrors.get(id);
            if (err!=null) {
                addProjectEntry("ERROR", err.toString());
            }
            if (p!=null) {
                if (p.getArtifact()!=null && !p.getArtifact().getVersion().equals(p.getArtifact().getBaseVersion())) {
                    addProjectEntry("Version Resolved", p.getArtifact().getVersion());
                }
                addProjectEntry("Name", p.getName());
                addProjectEntry("URL",  overrides.getUrl(p));
                if (!suppressLicenseInfo) {
                    addLicenseInfoEntries(getLicenses(p, id));
                    addCopyrightInfo(p, id);
                }
                addVerboseEntries(p);
                
                Map<String,DependencyNode> depsInGraphHere = new LinkedHashMap<String,DependencyNode>();
                Set<String> artifactsIncluded = new LinkedHashSet<String>();
                if (dn0!=null) {
                    for (DependencyNode dn1: dn0) {
                        String artifact = 
                            (dn1.getArtifact()==null ? "unknown" : 
                                dn1.getArtifact().getExtension()+
                                (isNonEmpty(dn1.getArtifact().getClassifier()) ? ":"+dn1.getArtifact().getClassifier() : "")) 
                                + " "
                                + "("+(dn1.getDependency()==null ? "unknown" : dn1.getDependency().getScope())+")";
                        artifactsIncluded.add(artifact);
                        for (DependencyNode dn2: dn1.getChildren()) {
                            depsInGraphHere.put(Coords.of(dn2).baseArtifact(), dn2);
                        }
                    }
                    getLog().debug("Dependencies of "+id+": in graph: "+dn0+"->"+depsInGraphHere);
                }
                
                addProjectEntry("Artifacts Included", isRoot(id) ? "(root)" : isExtra(id) ? "(extra)" : join(artifactsIncluded, "\n"));
                
                String dep;
                List<Dependency> deps = p.getDependencies();
                
                if (deps==null || deps.isEmpty()) {
                    dep = "(none)";
                    
                } else {
                    List<String> depsLine = new ArrayList<String>();
                    for (Dependency d: deps) {
                        DependencyNode nodeInGraphHere = depsInGraphHere.remove(Coords.of(d).baseArtifact());
                        boolean excludedScope = !includeScope(d.getScope());
                        String reportInclusionMessage;
                        DetailLevel level = null;
                        if (nodeInGraphHere!=null) {
                            if (excludedScope) {
                                reportInclusionMessage = "used but excluded from report";
                                level = DetailLevel.EXCLUDE_FROM_SUMMARY;
                            } else {
                                if (includedBaseArtifactsCoordsToProject.containsKey(Coords.of(d).baseArtifact())) {
                                    reportInclusionMessage = "included"+getInclusionMessageForDetailFromThisNode();
                                    level = DetailLevel.INCLUDE_WITH_DETAIL;
                                } else {
                                    reportInclusionMessage = "not included in report";
                                    level = DetailLevel.INCLUDE_IN_SUMMARY_EXPANDED_ELSEWHERE;
                                }
                            }
                        } else if (excludedScope) {
                            level = DetailLevel.OMITTED;
                            reportInclusionMessage = "excluded from report scope";
                        } else {
                            level = DetailLevel.INCLUDE_IN_SUMMARY_EXPANDED_ELSEWHERE;
                            if (includedBaseArtifactsCoordsToProject.containsKey(Coords.of(d).baseArtifact())) {
                                reportInclusionMessage = "included"+getInclusionMessageForDetailElsewhere(Coords.of(d).normal());
                            } else if (includedProjects.contains(Coords.of(d).normal())) reportInclusionMessage = "project included"+getInclusionMessageForDetailElsewhere(Coords.of(d).normal());
                            else if (includedArtifactsUnversionedToBaseArtifactCoords.containsKey(Coords.of(d).unversionedArtifact())) 
                                reportInclusionMessage = "version "+v(includedArtifactsUnversionedToBaseArtifactCoords.get(Coords.of(d).unversionedArtifact()))+" included"+getInclusionMessageForDetailElsewhere(
                                    includedBaseArtifactsCoordsToProject.get(includedArtifactsUnversionedToBaseArtifactCoords.get(Coords.of(d).unversionedArtifact())));
                            else if (includedProjectsUnversionedToVersioned.containsKey(Coords.of(d).unversioned())) 
                                reportInclusionMessage = "version "+v(includedProjectsUnversionedToVersioned.get(Coords.of(d).unversioned()))+" included"+getInclusionMessageForDetailElsewhere(includedProjectsUnversionedToVersioned.get(Coords.of(d).unversioned()));
                            else {
                                if (maxDepth == Integer.MAX_VALUE) {
                                    if ("compile".equals(d.getScope()) || "runtime".equals(d.getScope())) {
                                        // if these two are not present it must be an exclusion
                                        reportInclusionMessage = "excluded from build";
                                    } else {
                                        // could be an exclusion rule or natural consequence e.g. a test dep in a compile dep
                                        // (we don't do the significant amount of work to distinguish)
                                        reportInclusionMessage = "not included in build";
                                    }
                                } else {
                                    reportInclusionMessage = "not included in report";
                                }
                                level = DetailLevel.EXCLUDE_FROM_SUMMARY;
                            }
                        }
                        
                        if (level == DetailLevel.EXCLUDE_FROM_SUMMARY) {
                            if (d.isOptional()) {
                                if (!listUnusedNestedOptionalDependencies) {
                                    getLog().debug("Omitting "+d+" because it is optional and listUnusedNested="+listUnusedNestedOptionalDependencies);
                                    // default is to omit nested optional deps
                                    level = DetailLevel.OMITTED;
                                } else {
                                    // show it
                                    level = DetailLevel.INCLUDE_IN_SUMMARY_BUT_NOT_USED_OR_EXPANDED;
                                }
                            } else if (suppressExcludedDependencies) {
                                // this flag removes any dependency which isn't used 
                                level = DetailLevel.OMITTED;
                            }
                        }
                        
                        if (level != DetailLevel.OMITTED) {
                            depsResult.put(Coords.of(d).normal(), new DependencyDetail(d.getScope(), d.isOptional(), level));
                            depsLine.add(Coords.of(d).baseArtifact()+
                                (listDependencyIdOnly ? "" :
                                " ("+d.getScope()+
                                    (d.isOptional() ? ", optional" : "")+
                                    ", "+reportInclusionMessage+")"));
                        }
                    }
                    if (!depsInGraphHere.isEmpty()) {
                        for (Map.Entry<String,DependencyNode> dd: depsInGraphHere.entrySet()) {
                            // shouldn't happen
                            org.eclipse.aether.graph.Dependency d = dd.getValue().getDependency();
                            depsLine.add(dd.getKey()+" ("+d.getScope()+", excluded from report because in graph but not on project)");
                            depsResult.put(Coords.of(dd.getValue()).normal(), new DependencyDetail(d.getScope(), d.isOptional(), DetailLevel.EXCLUDE_FROM_SUMMARY));
                        }
                    }
                    dep = "";
                    if (depsLine.isEmpty()) {
                        dep = "(none in report scope)";
                    } else for (String d: depsLine) {
                        if (dep.length()>0) dep += "\n";
                        dep += d;
                    }
                }
                addProjectEntry("Dependencies", dep);
            }
            
            endProject();
            
            return depsResult;
        }

        private boolean isExtra(String id) {
            return doingExtras;
        }

        private String v(String id) {
            if (id==null) return null;
            String[] parts = id.split(":");
            return parts[parts.length-1];
        }

        protected String getInclusionMessageForDetailElsewhere(String includedProjectId) {
            return "";
        }

        protected String getInclusionMessageForDetailFromThisNode() {
            return "";
        }

        protected abstract void addLicenseInfoEntries(List<License> ll) throws MojoExecutionException;
        
        protected void addCompleteLicenseInfoEntries(List<License> lics) throws MojoExecutionException {
            String code = licensesCode(lics);
            
            addProjectEntry("License Code", licensesCode(lics));
            addProjectEntry("License", licensesString(lics, true));

            // if code found or single license, extract simple info, preferring canonical (code) info
            License license = LicenseCodes.lookupCode(code);
            if (license!=null) {
                addProjectEntry("License Name", license.getName());
                addProjectEntry("License URL", license.getUrl());
            } else if (lics!=null && lics.size()==1) {
                license = lics.iterator().next();
                addProjectEntry("License Name", license.getName());
                addProjectEntry("License URL", license.getUrl());
                
                // comments and distribution removed; 
                // comments included in "License" string above, for all licenses,
                // and distribution not really useful (repo or manual)
            }
        }

        protected void addCopyrightInfo(MavenProject p, String projectId) throws MojoExecutionException {
            String result = null;

            Set<String> notices = new LinkedHashSet<String>();
            GenerateNoticesMojo.addAllNonEmptyStrings(notices, overrides.getOverridesForProject(projectId).get("notice"));
            GenerateNoticesMojo.addAllNonEmptyStrings(notices, overrides.getOverridesForProject(projectId).get("notices"));
            Iterator<String> ni = notices.iterator();
            while (ni.hasNext()) {
                String n = ni.next();
                if (n.toLowerCase().indexOf("copyright")<0) ni.remove();
            }
            if (!notices.isEmpty()) {
                result = join(notices, "\n");
            } else if (p!=null) {
                result = "Copyright (c)";
                if (p.getOrganization()!=null && p.getOrganization().getName()!=null && p.getOrganization().getName().length()>0) {
                    result += " " + p.getOrganization().getName();
                } else {
                    result += " " + "project contributors";
                }

                long releaseYear = -1;
                Set<Artifact> arts;
                if (p.getArtifact()!=null && p.getArtifact().getFile()!=null) {
                    arts = Collections.singleton(p.getArtifact());
                } else {
                    arts = projectArtifacts.get(Coords.of(p).normal());
                    if (arts==null || arts.isEmpty()) {
                        arts = p.getArtifacts();
                    }
                }
                for (Artifact art: arts) {
                    if (art.getFile()!=null && art.getFile().exists()) {
                        Calendar calendar = Calendar.getInstance();
                        calendar.setTime(new Date(art.getFile().lastModified()));
                        releaseYear = Math.max(releaseYear, calendar.get(Calendar.YEAR));
                    }
                }

                if (p.getInceptionYear()!=null && p.getInceptionYear().length()>0) {
                    result += " (" + p.getInceptionYear()+"-"+(releaseYear > 0 ? releaseYear : "")+")"; 
                } else if (releaseYear>0) {
                    result += " (" + releaseYear+")"; 
                }
            }

            if (result!=null) {
                addProjectEntry("Copyright", result);
            }
        }

        protected void addSummaryLicenseInfoEntries(List<License> ll) throws MojoExecutionException {
            addProjectEntry("License", licensesSummaryString(ll));
        }
        
        protected void addVerboseEntries(MavenProject p) throws MojoExecutionException {
            addProjectEntry("Description", p.getDescription());
            addProjectEntry("Organization", organizationString(p.getOrganization()));
            addProjectEntry("Contributors", contributorsString(p.getContributors()));
            addProjectEntry("Developers", contributorsString(p.getDevelopers()));
        }
        
        protected void output(String line) throws MojoExecutionException {
            if (outputFileWriter!=null) {
                try {
                    outputFileWriter.write(line);
                    outputFileWriter.write("\n");
                } catch (IOException e) {
                    throw new MojoExecutionException("Error writing to "+outputFilePath, e);
                }
            }
            getLog().info(line);
        }
    }
    
    public abstract class AbstractListReport extends AbstractReport {
        final boolean isSorted;
        public AbstractListReport(boolean isSorted) {
            this.isSorted = isSorted;
        }

        public void run() throws MojoExecutionException {
            setup();
            
            List<String> idsSorted = new ArrayList<String>();
            idsSorted.addAll(ids);
            if (extras!=null) idsSorted.addAll(extras.getProjects());
            if (isSorted) Collections.sort(idsSorted);

            for (String id: idsSorted) {
                if (ids.contains(id)) {
                    runProject(id);
                } else {
                    doingExtras = true;
                    runExtraProject(id, extras);
                    doingExtras = false;
                }
            }
        }
        
        public void runExtraProjects() throws MojoExecutionException {
            // not used
            throw new MojoExecutionException("Should not come here");
        }

        
    }
    public class ReportReport extends AbstractListReport {
        
        public ReportReport(boolean isSorted) { super(isSorted); }
        
        @Override
        protected void addLicenseInfoEntries(List<License> ll) throws MojoExecutionException {
            addSummaryLicenseInfoEntries(ll);
        }

        @Override
        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            super.startProject(id, p);
            output("Project: "+id);
        }
        
        @Override
        public void addProjectEntry(String key, String value) throws MojoExecutionException {
            if (value==null) {
                getLog().debug("Ignoring null entry for "+currentProject+" "+key);
                return;
            }
            boolean multiline = value.indexOf('\n')>=0;
            output("  "+key+":"+(multiline ? "" : " "+value));
            if (multiline) {
                for (Object v: value.split("\n")) {
                    output("    "+v);
                }
            }
        }
    }

    public class ListReport extends AbstractListReport {
        
        public ListReport(boolean isSorted) { super(isSorted); }
        
        @Override
        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            super.startProject(id, p);
            if (p==null) p = projectByIdCache.get(id);
            
            Set<Object> errs = projectErrors.get(id);

            // if we wanted to show where it was dragged in from
//            Set<String> parentDN = projectToDependencyGraphParent.get(id);
//            Set<DependencyNode> referencingDNs = depNodesByIdCache.get(id);

            List<License> lics = getLicenses(p, id);
            String licenseLine = (suppressLicenseInfo ? "" : ": "+((lics!=null && !lics.isEmpty()) || p!=null ? oneLine(licensesSummaryString(lics), "; ") : "<not loaded>"));
            
            output(id+
                (errs==null || errs.isEmpty() ? "" : " (ERROR)")+
                licenseLine);
        }
        
        @Override
        protected void addLicenseInfoEntries(List<License> ll) throws MojoExecutionException {
        }
        
        @Override
        public void addProjectEntry(String key, String value) throws MojoExecutionException {
        }
    }

    public class CsvReport extends AbstractListReport {

        public CsvReport(boolean isSorted) {
            super(isSorted);
        }

        Set<String> columns = new LinkedHashSet<String>();
        {
            columns.add("ID");
            columns.add("GroupId"); 
            columns.add("ArtifactId");  
            columns.add("Version"); 
            columns.add("Name");
            columns.add("Description"); 
            columns.add("URL"); 
            columns.add("Organization");    
            columns.add("Contributors");    
            columns.add("Developers");  
            columns.add("License Code");    
            columns.add("License"); 
            columns.add("License Name");    
            columns.add("License URL"); 
            columns.add("Artifacts Included");  
            columns.add("Dependencies");   
            columns.add("Copyright");
        }
        
        Map<String,Map<String,String>> allProjectsData = new LinkedHashMap<String, Map<String,String>>();
        
        Map<String,String> thisProjectData;

        @Override
        protected void addLicenseInfoEntries(List<License> ll) throws MojoExecutionException {
            addCompleteLicenseInfoEntries(ll);
        }

        @Override
        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            super.startProject(id, p);
            thisProjectData = new LinkedHashMap<String, String>();
            
            Map<String, Object> data = overrides.getOverridesForProject(id);
            String version = data==null ? null : (String)data.get("version");
            if (data==null && p!=null && p.getArtifact()!=null) {
                version = p.getArtifact().getBaseVersion();  
            }
            
            if (version!=null) {
                String idWithVersion = id;
                if (id.indexOf(version)<0) idWithVersion = id+":"+version;
                addProjectEntry("ID", idWithVersion);
                addProjectEntry("Version", version);
            } else {
                addProjectEntry("ID", id);
            }
            if (p!=null && p.getArtifact()!=null) {
                // the artifact is a more reliable indicator of its coordinates
                addProjectEntry("GroupId", p.getArtifact().getGroupId());
                addProjectEntry("ArtifactId", p.getArtifact().getArtifactId());
            }
            allProjectsData.put(id, thisProjectData);
        }
        
        public void addProjectEntry(String key, String value) {
            columns.add(key);
            
            if (value==null) {
                getLog().debug("Ignoring null entry for "+currentProject+" "+key);
                return;
            }
            thisProjectData.put(key, value);
        }

        @Override
        public void endProject() throws MojoExecutionException {
            thisProjectData = null;
        }
        
        @Override
        public void run() throws MojoExecutionException {
            super.run();
            
            for (String c: columns) csvEntry(c);
            csvRowEnd();
            
            for (Map<String,String> projectData : allProjectsData.values()) {
                for (String c: columns) {
                    String data = projectData.get(c);
                    if (data==null) data = "";
                    csvEntry(data);
                }
                csvRowEnd();
            }
        }

        protected StringBuilder row = new StringBuilder();
        protected void csvEntry(String entry) {
            if (row.length()>0) row.append(",");
            
            if (!entry.matches("[A-Za-z0-9 ]+")) {
                // unless entry is very simple, escape and quote it
                
                // double quotes deoubled
                entry = entry.replace("\"", "\"\"");
                
                entry = "\""+entry+"\"";
            }
            row.append(entry);
        }
        protected void csvRowEnd() throws MojoExecutionException {
            output(row.toString());
            row = new StringBuilder();
        }
    }

    public abstract class AbstractTreeReport extends AbstractReport {

        final String projectPrefix;
        final String linePrefix;
        final boolean deferSummary;
        
        public AbstractTreeReport(String projectPrefix, String linePrefix, boolean deferSummary) {
            this.projectPrefix = projectPrefix;
            this.linePrefix = linePrefix;
            this.deferSummary = deferSummary;
        }
        
        Set<String> reportedProjects = new LinkedHashSet<String>();
        String currentPrefix = "";

        protected String prefixWithPlus() {
            return currentPrefix.length() >= linePrefix.length() ? currentPrefix.substring(0, currentPrefix.length()-linePrefix.length()) + projectPrefix : currentPrefix;
        }
        
        public void run() throws MojoExecutionException {
            setup();
            
            runProjectRecursively(Coords.of(project).normal(), project, 0, Collections.singleton(new DependencyDetail(null, false, DetailLevel.INCLUDE_WITH_DETAIL)));

            for (String id: ids) {
                if (!reportedProjects.contains(id)) {
                    // shouldn't happen; will log errors because no DN info avail and project not supplied
                    runProjectRecursively(id, null, 0, Collections.singleton(new DependencyDetail(null, false, DetailLevel.INCLUDE_WITH_DETAIL)));
                }
            }
            if (extras!=null) {
                for (String projectId: extras.getProjects()) {
                    runExtraProject(projectId, extras);
                }
            }
        }
        
        @Override
        protected void runExtraProject(String projectId, ProjectsOverrides extras) throws MojoExecutionException {
            currentPrefix = "";
            showExtraProjectHeader(projectId, extras);
            currentPrefix = "  ";
            super.runExtraProject(projectId, extras);
        }
        protected void showExtraProjectHeader(String id, ProjectsOverrides extras) throws MojoExecutionException {
            output(id);
        }

        protected void runProjectRecursively(String id, MavenProject p, int depth, Set<DependencyDetail> details) throws MojoExecutionException {
            if (!reportedProjects.contains(id)) {
                if (ids.contains(id)) {
                    DetailLevel level = best(details);
                    getLog().debug("Details of "+id+": "+details+" ("+level+")");
                    if (level==DetailLevel.INCLUDE_WITH_DETAIL) {
                        if (!deferSummary) output(prefixWithPlus() + id + extraInfoForProjectLineInfo(id, p, details, null));
                        reportedProjects.add(id);
                        
                        SimpleMultiMap<String, DependencyDetail> deps = runProject(id);
                        boolean depsShown = false;
                        if (!deps.isEmpty() && depth <= maxDepth) {
                            for (Map.Entry<String,Set<DependencyDetail>> d: deps.entrySet()) {
                                DetailLevel dl = best(d.getValue());
                                if (isIncluded(dl)) {
                                    if (!depsShown) {
                                        depsShown = true;
                                        if (deferSummary) output(prefixWithPlus() + id + extraInfoForProjectLineInfo(id, p, details, null));
                                        introduceDependenciesDetail();
                                        currentPrefix = currentPrefix + linePrefix;
                                    }
                                    runProjectRecursively(d.getKey(), null, depth+1, d.getValue());
                                }
                            }
                        }
                        if (depsShown) {
                            currentPrefix = currentPrefix.substring(0, currentPrefix.length()-linePrefix.length());
                        } else {
                            if (deferSummary) output(prefixWithPlus() + id + extraInfoForProjectLineInfo(id, p, details, "no dependencies"));
                        }
                    } else if (level==DetailLevel.INCLUDE_IN_SUMMARY_EXPANDED_ELSEWHERE) {
                        // reported later
                        output(prefixWithPlus() + id + extraInfoForProjectLineInfo(id, p, details, "reported below"));
                    } else {
                        if (level==DetailLevel.INCLUDE_IN_SUMMARY_BUT_NOT_USED_OR_EXPANDED) {
                            // shouldn't happen -- unincluded optionals should not be in 'ids' and so will be treated as unexpected
                        }
                        // for optional dependencies we want to include
                        output(prefixWithPlus() + id + extraInfoForProjectLineInfo(id, p, details, "unknown inclusion"));
                    }
                } else {
                    onUnexpectedDependency(id, p, details);
                }
            } else {
                output(prefixWithPlus() + id + extraInfoForProjectLineInfo(id, p, details, "reported above"));
            }
        }
        
        protected void onUnexpectedDependency(String id, MavenProject p, Set<DependencyDetail> details) throws MojoExecutionException {
            String message;
            if (best(details)==DetailLevel.INCLUDE_IN_SUMMARY_BUT_NOT_USED_OR_EXPANDED) {
                message = "not used";
            } else {
                // due to depth etc; would be good to get confirmation of which cases this comes up and more elaborate messages
                message = "excluded from report";
            }
            output(prefixWithPlus() + id + extraInfoForProjectLineInfo(id, p, details, message));
        }

        protected abstract void introduceDependenciesDetail() throws MojoExecutionException;

        protected abstract String extraInfoForProjectLineInfo(String id, MavenProject p, Set<DependencyDetail> details, String exclusionInfo);
        
        protected abstract boolean isIncluded(DetailLevel level);
    }

    public class TreeReport extends AbstractTreeReport {
        
        public TreeReport() { super("  +-", "  | ", false); }

        @Override
        public void addProjectEntry(String key, String value) throws MojoExecutionException {
            if (value==null) {
                getLog().debug("Ignoring null entry for "+currentProject+" "+key);
                return;
            }
            boolean multiline = value.indexOf('\n')>=0;
            output(currentPrefix + "  "+key+":"+(multiline ? "" : " "+value));
            if (multiline) {
                for (Object v: value.split("\n")) {
                    output(currentPrefix + "    "+v);
                }
            }
        }
        
        @Override
        protected void addLicenseInfoEntries(List<License> ll) throws MojoExecutionException {
            addSummaryLicenseInfoEntries(ll);
        }
        
        @Override
        protected void addVerboseEntries(MavenProject p) {}
        
        @Override
        protected void introduceDependenciesDetail() throws MojoExecutionException {
            output(currentPrefix + "  Dependencies pulled in here detail:");
        }

        @Override
        protected String extraInfoForProjectLineInfo(String id, MavenProject p, Set<DependencyDetail> detail, String exclusionInfo) {
            if (exclusionInfo==null) return "";
            return " ("+exclusionInfo+")";
        }

        @Override
        protected String getInclusionMessageForDetailElsewhere(String id) {
            return ", from "+join(projectToDependencyGraphParent.get(id), " ");
        }

        @Override
        protected String getInclusionMessageForDetailFromThisNode() {
            return ", detail below";
        }
        
        @Override
        protected boolean isIncluded(DetailLevel level) {
            return level==DetailLevel.INCLUDE_WITH_DETAIL;
        }
    }
    
    public class SummaryReport extends AbstractTreeReport {

        public SummaryReport() { super("+-", "| ", true); }
        
        @Override
        public void addProjectEntry(String key, String value) {
        }
        
        @Override
        protected void addLicenseInfoEntries(List<License> ll) {}
        
        @Override
        protected void addVerboseEntries(MavenProject p) {}
        
        @Override
        protected void introduceDependenciesDetail() {}
        
        protected void onUnexpectedDependency(String id, MavenProject p, Set<DependencyDetail> details) throws MojoExecutionException {
            // will happen for wrong version of project due to how summary tree is made
            String unversionedId = id.substring(0, id.lastIndexOf(":"));
            String realP = includedProjectsUnversionedToVersioned.get(unversionedId);
            if (realP!=null) {
                String v = realP.split(":")[realP.split(":").length-1];
                output(prefixWithPlus() + id + extraInfoForProjectLineInfo(id, p, details, "v "+v+" used"));
            } else {
                super.onUnexpectedDependency(id, p, details);
            }
        }

        @Override
        protected String extraInfoForProjectLineInfo(String id, MavenProject p, Set<DependencyDetail> details, String exclusionInfo) {
            if (p==null) p = projectByIdCache.get(id);
            List<License> lics = getLicenses(p, id);
            if (p==null) {
                getLog().debug("No project loaded: "+id);
            }
            String licenseLine = (suppressLicenseInfo ? "" : ": "+(p!=null ? oneLine(licensesSummaryString(lics), "; ") : "<not loaded>"));
            if (isRoot(id)) {
                // root project, no need to show deps info
                return
                    (isNonEmpty(exclusionInfo) ? "("+exclusionInfo+")" : "")+
                    licenseLine;                
            }

            return " ("+
                (projectErrors.get(id)!=null ? "ERROR; " : "") +
                allScopesFromDetails(details) + 
                (isOptionalFromDetails(details) ? ", optional" : "")+
                (isNonEmpty(exclusionInfo) ? ", "+exclusionInfo : "")+")"+
                licenseLine;
        }
        
        @Override
        protected void runExtraProject(String projectId, ProjectsOverrides extras) throws MojoExecutionException {
            super.runExtraProject(projectId, extras);
        }

        @Override
        protected void showExtraProjectHeader(String id, ProjectsOverrides extras) throws MojoExecutionException {
            List<License> lics = getLicenses(projectByIdCache.get(id), id);
            String licenseLine = (suppressLicenseInfo ? "" : ": "+oneLine(licensesSummaryString(lics), "; "));
            output(id + " (extra)"+licenseLine);
        }

        // if we use dependency nodes instead of DependencyDetail:
//        private boolean isOptional(Set<DependencyNode> deps) {
//            if (deps==null || deps.isEmpty()) return false;
//            for (DependencyNode d: deps) {
//                if (d.getDependency()!=null && !d.getDependency().isOptional()) return false;
//                if (d.getDependency()==null) {
//                    getLog().warn("Missing dependency info for "+d+"; assuming not optional");
//                    return false;
//                }
//            }
//            // at least one, and all explicitly optional
//            return true;
//        }
//
//        private String allScopes(Set<DependencyNode> deps) {
//            if (deps==null || deps.isEmpty()) {
//                return "unknown scope";
//            }
//            Set<String> scopes = new LinkedHashSet<String>();
//            for (DependencyNode d: deps) {
//                if (d.getDependency()==null) {
//                    scopes.add("unknown");
//                } else {
//                    scopes.add(d.getDependency().getScope());
//                }
//            }
//            return join(scopes, "+");
//        }

        private boolean isOptionalFromDetails(Set<DependencyDetail> deps) {
            if (deps==null || deps.isEmpty()) return false;
            for (DependencyDetail d: deps) {
                if (!d.optional) return false;
            }
            // at least one, and all explicitly optional
            return true;
        }

        private String allScopesFromDetails(Set<DependencyDetail> deps) {
            if (deps==null || deps.isEmpty()) {
                return "unknown scope";
            }
            Set<String> scopes = new LinkedHashSet<String>();
            for (DependencyDetail d: deps) {
                if (d.scope==null) {
                    scopes.add("unknown");
                } else {
                    scopes.add(d.scope);
                }
            }
            return join(scopes, "+");
        }

        @Override
        protected boolean isIncluded(DetailLevel level) {
            return level.compareTo(DetailLevel.INCLUDE_IN_SUMMARY_BUT_NOT_USED_OR_EXPANDED) >= 0;
        }
    }

}
