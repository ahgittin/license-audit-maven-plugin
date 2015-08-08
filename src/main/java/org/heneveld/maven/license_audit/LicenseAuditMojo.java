package org.heneveld.maven.license_audit;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Contributor;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;

@Mojo( name = "report", defaultPhase = LifecyclePhase.PROCESS_SOURCES,
    requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class LicenseAuditMojo extends AbstractMojo
{

    @Parameter( defaultValue = "", property = "license-audit.outputFile", required = false )
    private String outputFilePath;
    protected FileWriter outputFileWriter = null;

    @Parameter( defaultValue = "tree", property = "license-audit.format", required = true )
    private String format;

    @Parameter( defaultValue = "true", property = "license-audit.recurse", required = true )
    private boolean recurse;

    @Parameter( defaultValue = "compile,runtime", property = "license-audit.includeDependencyScopes", required = true )
    private String scopes;
    private boolean allScopes = false;

    @Parameter( defaultValue = "false", property = "license-audit.includeOptionalDependencies", required = true )
    private boolean includeOptionalDependencies;

    @Parameter( defaultValue = "false", property = "license-audit.listOnlyIncludedDependencies", required = true )
    private boolean listOnlyIncludedDependencies;

    @Component
    MavenProject project;

    @Component
    MavenSession mavenSession;
    
    @Component
    private ProjectBuilder projectBuilder;
    @Component
    private ProjectDependenciesResolver depsResolver;
    @Component
    private ArtifactHandler artifactHandler;

    @Parameter(property = "project.remoteArtifactRepositories")
    protected List<ArtifactRepository> remoteRepositories;

    public void execute() throws MojoExecutionException {
        // wrap in commas lower case so our contains works
        scopes = ","+scopes.toLowerCase()+",";
        if (scopes.contains(",all,")) allScopes = true;
        
        if (isNonEmpty(outputFilePath)) {
            try {
                outputFileWriter = new FileWriter(outputFilePath);
            } catch (IOException e) {
                throw new MojoExecutionException("Error creating "+outputFilePath);
            }
        }
        buildDeps(Collections.singleton(project), recurse ? -1 : 1);

        if ("tree".equalsIgnoreCase(format)) {
            new TreeReport().run();
        } else if ("list".equalsIgnoreCase(format)) {
            new ListReport().run();
        } else if ("csv".equalsIgnoreCase(format)) {
            new CsvReport().run();
        } else if ("summary".equalsIgnoreCase(format)) {
            new SummaryReport().run();
        } else {
            throw new MojoExecutionException("Unknown format (use 'tree', 'list', 'csv', or 'summary'): "+format);
        }
        
        if (outputFileWriter!=null) {
            try {
                outputFileWriter.close();
            } catch (IOException e) {
                throw new MojoExecutionException("Error closing "+outputFilePath, e);
            }
        }
    }

    // contains MavenProject or Throwable
    Map<String,MavenProject> projectByIdCache = new LinkedHashMap<String,MavenProject>();
    Map<String,Set<Object>> projectErrors = new LinkedHashMap<String,Set<Object>>();
    Map<String,List<DependencyNode>> projectDepsCache = new LinkedHashMap<String, List<DependencyNode>>();
    Set<String> unversionedProjectsLoaded = new LinkedHashSet<String>();
    
    protected void addError(String id, Object error) {
        Set<Object> ee = projectErrors.get(id);
        if (ee==null) ee = new LinkedHashSet<Object>();
        ee.add(error);
        projectErrors.put(id, ee);
    }
    
    protected boolean acceptScope(String scope) {
        if (allScopes) return true;
        if (scopes.contains(","+scope.toLowerCase()+",")) return true;
        return false;
    }
    
    protected void buildDeps(Collection<MavenProject> projects, int depth) {
        Set<MavenProject> nextDepthProjects = new LinkedHashSet<MavenProject>();
        
        for (MavenProject p: projects) {
            getLog().debug("Loaded "+id(p));
            projectByIdCache.put(id(p), p);
            unversionedProjectsLoaded.add(p.getGroupId()+":"+p.getArtifactId());

            final List<DependencyNode> backupDepsList = new ArrayList<DependencyNode>();
            DependencyResolutionResult depRes = null;
            DefaultDependencyResolutionRequest depReq = new DefaultDependencyResolutionRequest(p, mavenSession.getRepositorySession());
            try {
                depRes = depsResolver.resolve(depReq);

            } catch (DependencyResolutionException e) {
                getLog().debug("Error resolving "+id(p)+", now trying with scope partially limited: "+e, e);
                getLog().warn("Error resolving "+id(p)+", now trying with scope partially limited: "+e);
                
                depReq.setResolutionFilter(new DependencyFilter() {
                    public boolean accept(DependencyNode node, List<DependencyNode> parents) {
                        if (node==null) return false;
                        if (node.getDependency()==null) {
                            getLog().warn("Missing dependency for "+node);
                            return false;
                        }
                        backupDepsList.add(node);
                        if (!acceptScope(node.getDependency().getScope())) return false;
                        if (Boolean.TRUE.equals(node.getDependency().getOptional())) return false;
                        return true;
                    }
                });
                try {
                    depRes = depsResolver.resolve(depReq);
                    getLog().debug("Successfully resolved "+id(p)+" with scope limited");
                    addError(id(p), "One or more excluded scope or optional dependencies could not be resolved: "+e);

                } catch (DependencyResolutionException e2) { 
                    getLog().debug("Dependency resolution failed for "+id(p)+", even with limited scope: "+e2, e2);
                    getLog().error("Dependency resolution failed for "+id(p)+", even with limited scope: "+e2);
                    addError(id(p), "Dependency resolution failed: "+e2);
                }
            }
            List<DependencyNode> depsList;
            if (depRes!=null) depsList = depRes.getDependencyGraph().getChildren();
            else depsList = backupDepsList;
            projectDepsCache.put(id(p), depsList);

            for (DependencyNode dn: depsList) {
                boolean acceptsScope = acceptScope(dn.getDependency().getScope());
                boolean excludeBecauseOptional = !includeOptionalDependencies && Boolean.TRUE.equals(dn.getDependency().getOptional());
                
                Artifact da = dn.getArtifact();
                org.apache.maven.artifact.Artifact mda = newMavenArtifact(da);
                
                if (!id(mda).equals(id(dn))) getLog().warn("ID mismatch: dependency "+dn+" and artifact "+mda+" ("+id(dn)+" / "+id(mda)+")");
                MavenProject cdp = projectByIdCache.get(id(mda));
                Object cacheError = projectErrors.get(id(mda));
                getLog().debug("Resolving dependency "+id(mda)+" dependency of "+id(p)+": "+
                    (!acceptsScope ? "ignored scope ("+dn.getDependency().getScope()+")"
                        : excludeBecauseOptional ? "excluded because optional"
                        : cdp!=null ? "already loaded in cache" 
                        : cacheError!=null ? "previous failure cached" 
                        : "loading required"));
                if (!acceptsScope) continue;
                if (excludeBecauseOptional) continue;
                if (cdp!=null || cacheError!=null) continue;
                
                try {
                    // older code creates a new PBReq but it lacks user props; this seems to work better
                    ProjectBuildingResult res = projectBuilder.build(mda, true, mavenSession.getProjectBuildingRequest());
                    if (res.getProject()==null) {
                        throw new IllegalStateException("No project was built");
                    } else {
                        if (!id(res.getProject()).equals(id(dn))) {
                            getLog().warn("ID mismatch: dependency "+dn+" and project "+res.getProject()+" ("+id(dn)+" / "+id(res.getProject())+"); "
                                + "replacing artifact "+res.getProject().getArtifact()+" with "+mda);
                            addError(id(dn), "Project POM declares ID "+id(res.getProject())+" different to artifact");
                            res.getProject().setArtifact(mda);
                        }
                        nextDepthProjects.add(res.getProject());
                    }
                } catch (ProjectBuildingException e) {
                    getLog().warn("Error resolving "+id(mda)+" dependency of "+id(p)+": "+e);
                    addError(id(mda), "Error resolving "+id(mda)+" dependency of "+id(p)+": "+e);
                }
            }
        }
        
        getLog().debug("Next depth ("+depth+") unresolved project count: "+nextDepthProjects);
        if (depth!=0 && !nextDepthProjects.isEmpty()) {
            buildDeps(nextDepthProjects, depth>0 ? depth-1 : -1);
        }
    }

    public abstract class AbstractReport {
        String currentProject;
        Set<String> ids;
        
        public void setup() {
            ids = new LinkedHashSet<String>();
            ids.addAll(projectByIdCache.keySet());
            ids.addAll(projectErrors.keySet());
        }
        
        public abstract void run() throws MojoExecutionException;
        
        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            currentProject = id;
        }
        
        public void endProject() throws MojoExecutionException {}
        public abstract void addProjectEntry(String key, String value) throws MojoExecutionException;
        
        protected List<DependencyNode> runProject(String id) throws MojoExecutionException {
            List<DependencyNode> depsResult = new ArrayList<DependencyNode>();
            
            MavenProject p = projectByIdCache.get(id);
            startProject(id, p);
            
            Object err = projectErrors.get(id);
            if (err!=null) {
                addProjectEntry("ERROR", err.toString());
            }
            if (p!=null) {
                addProjectEntry("Name", p.getName());
                addLicenseInfoEntries(p);
                addProjectEntry("URL", p.getUrl());
                addVerboseEntries(p);
                
                String dep;
                List<DependencyNode> deps = projectDepsCache.get(id(p));
                if (deps==null || deps.isEmpty()) {
                    if (p.getDependencies()==null || p.getDependencies().isEmpty()) {
                        dep = "(none)";
                    } else {
                        getLog().error("No dependencies cached for "+id(p)+" but it reports "+p.getDependencies());
                        dep = "(errors)";
                    }
                } else {
                    int count = 0;
                    List<String> depsLine = new ArrayList<String>();
                    for (DependencyNode dn: deps) {
                        org.eclipse.aether.graph.Dependency d = dn.getDependency();
                        boolean excludeBecauseOptional = !includeOptionalDependencies && Boolean.TRUE.equals(d.getOptional());
                        boolean included = !excludeBecauseOptional && acceptScope(d.getScope());
                        if (!listOnlyIncludedDependencies || included) {
                            String depId = id(dn);
                            depsLine.add(depId
                                +(d.getArtifact()!=null && d.getArtifact().getClassifier()!=null && d.getArtifact().getClassifier().length()>0 ? " "+d.getArtifact().getClassifier() : "")
                                +" ("+d.getScope()+")"
                                +(Boolean.TRUE.equals(d.getOptional()) ? " OPTIONAL" : "")
                                );
                            if (included) {
                                depsResult.add(dn);
                            }
                            count++;
                        }
                    }
                    if (count==0) {
                        dep = "(none relevant)";
                    } else {
                        dep = "";
                        for (String d: depsLine) {
                            if (dep.length()>0) dep += "\n";
                            dep += d;
                        }
                    }
                }
                addProjectEntry("Dependencies", dep);
            }
            
            endProject();
            
            return depsResult;
        }

        protected void addLicenseInfoEntries(MavenProject p) throws MojoExecutionException {
            addProjectEntry("LicenseCode", licensesCode(p.getLicenses()));
            addProjectEntry("License", licensesString(p.getLicenses()));
            if (p.getLicenses()!=null && p.getLicenses().size()==1) {
                License license = p.getLicenses().iterator().next();
                addProjectEntry("LicenseName", license.getName());
                addProjectEntry("LicenseUrl", license.getUrl());
                addProjectEntry("LicenseComments", license.getComments());
                addProjectEntry("LicenseDistribution", license.getDistribution());
            }
        }
        
        protected void addVerboseEntries(MavenProject p) throws MojoExecutionException {
            addProjectEntry("Description", p.getDescription());
            addProjectEntry("Contributors", contributorsString(p.getContributors()));
            addProjectEntry("Developers", contributorsString(p.getDevelopers()));
        }

        protected String contributorsString(Iterable<? extends Contributor> contributors) {
            if (contributors==null) return null;
            StringBuilder result = new StringBuilder();
            for (Contributor c: contributors) {
                StringBuilder ri = new StringBuilder();
                if (isNonEmpty(c.getName())) ri.append(c.getName());
                if (isNonEmpty(c.getOrganization())) {
                    if (ri.length()>0) ri.append(" / ");
                    ri.append(c.getOrganization());
                }
                int nameOrgLen = ri.length();
                
                if (isNonEmpty(c.getUrl())) {
                    if (nameOrgLen>0) ri.append(" (");
                    ri.append(c.getUrl());
                }
                if (isNonEmpty(c.getOrganizationUrl())) {
                    if (ri.length() > nameOrgLen) ri.append(" / ");
                    else if (nameOrgLen>0) ri.append(" (");
                    ri.append(c.getOrganizationUrl());
                }
                if (ri.length() > nameOrgLen) ri.append(")");
                
                if (result.length()>0) result.append(", ");
                result.append(ri.toString().trim());
            }
            if (result.length()>0) return result.toString();
            return null;
        }

        protected String licensesString(Iterable<? extends License> licenses) {
            return licensesStringInternal(licenses, false);
        }

        protected String licensesStringInternal(Iterable<? extends License> licenses, boolean summaryOnly) {
            // NB: subtly different messages if things are empty 
            if (licenses==null) return "<no license info>";
            StringBuilder result = new StringBuilder();
            for (License l: licenses) {
                StringBuilder ri = new StringBuilder();
                if (isNonEmpty(l.getName())) ri.append(l.getName());
                if (isNonEmpty(l.getUrl())) {
                    if (ri.length()>0) {
                        if (summaryOnly) { /* nothing */ }
                        else { ri.append(" ("+l.getUrl()+")"); }
                    } else {
                        ri.append(l.getUrl());
                    }
                }

                if (result.length()>0) result.append(", ");
                if (ri.toString().trim().length()>0) {
                    result.append(ri.toString().trim());
                } else {
                    result.append("<no info on license>");
                }
            }
            if (result.length()>0) return result.toString();
            return "<no licenses>";
        }

        protected String licensesSummaryString(Iterable<? extends License> licenses) {
            String summary = licensesStringInternal(licenses, true);
            String code = LicenseCodes.getLicenseCode(summary);
            if (code==null) return summary;
            if (code.length()==0) return "<unknown>";
            return code;
        }

        /** null unless there is a known code */
        protected String licensesCode(Iterable<? extends License> licenses) {
            String summary = licensesStringInternal(licenses, true);
            String code = LicenseCodes.getLicenseCode(summary);
            if (code==null || code.length()==0) return null;
            return code;
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
    
    protected static String id(org.apache.maven.model.Dependency d) {
        return d.getGroupId()+":"+d.getArtifactId()+":"+d.getVersion();
    }
    
    protected static String id(org.apache.maven.artifact.Artifact a) {
        return a.getGroupId()+":"+a.getArtifactId()+":"+a.getVersion();
    }

    protected String id(DependencyNode dn) {
        return id(newMavenArtifact(dn.getArtifact()));
    }

    protected static String id(MavenProject p) {
        if (p.getArtifact()!=null) return id(p.getArtifact());
        // fall back to this, but the artifact ID is the canonical one; it may differ from what is declared in the project pom
        return p.getGroupId()+":"+p.getArtifactId()+":"+p.getVersion();
    }
    
    public class ListReport extends AbstractReport {
        
        @Override
        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            super.startProject(id, p);
            output("Project: "+id);
        }
        
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
        
        public void run() throws MojoExecutionException {
            setup();
            for (String id: ids) {
                runProject(id);
            }
        }

    }

    public class CsvReport extends AbstractReport {

        Set<String> columns = new LinkedHashSet<String>();
        
        Map<String,Map<String,String>> allProjectsData = new LinkedHashMap<String, Map<String,String>>();
        
        Map<String,String> thisProjectData;
        
        @Override
        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            super.startProject(id, p);
            thisProjectData = new LinkedHashMap<String, String>();
            addProjectEntry("ID", id);
            if (p!=null && p.getArtifact()!=null) {
                // the artifact is a more reliable indicator of its coordinates
                addProjectEntry("GroupId", p.getArtifact().getGroupId());
                addProjectEntry("ArtifactId", p.getArtifact().getArtifactId());
                addProjectEntry("Version", p.getArtifact().getVersion());
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
        
        public void run() throws MojoExecutionException {
            setup();
            for (String id: ids) {
                runProject(id);
            }
            
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
        
        public AbstractTreeReport(String projectPrefix, String linePrefix) {
            this.projectPrefix = projectPrefix;
            this.linePrefix = linePrefix;
        }
        
        Set<String> reportedProjects = new LinkedHashSet<String>();
        String currentPrefix = "";

        private String prefixWithPlus() {
            return currentPrefix.length() >= linePrefix.length() ? currentPrefix.substring(0, currentPrefix.length()-linePrefix.length()) + projectPrefix : currentPrefix;
        }
        
        public void run() throws MojoExecutionException {
            setup();
            runProjects(ids);
        }
        
        protected void runProjects(Collection<String> projects) throws MojoExecutionException {
            Iterator<String> pi = projects.iterator();
            runProjectRecursively(pi.next(), null, true);
            
            for (String id: projects) {
                if (!reportedProjects.contains(id)) {
                    runProjectRecursively(id, null, false);
                }
            }            
        }
        
        protected void runProjectRecursively(String id, DependencyNode dep, boolean isFirst) throws MojoExecutionException {
            if (!reportedProjects.contains(id)) {
                if (ids.contains(id)) {
                    reportedProjects.add(id);
                    output(prefixWithPlus() + id + extraInfoForProjectLineInfo(dep, null, isFirst));

                    List<DependencyNode> deps = runProject(id);
                    if (!deps.isEmpty()) {
                        introduceDependenciesDetail();
                        currentPrefix = currentPrefix + linePrefix;
                        for (DependencyNode d: deps) {
                            runProjectRecursively(id(d), d, false);
                        }
                        currentPrefix = currentPrefix.substring(0, currentPrefix.length()-linePrefix.length());
                    }
                } else {
                    String reasonNotInScope =
                        (dep!=null && unversionedProjectsLoaded.contains(dep.getArtifact().getGroupId()+":"+dep.getArtifact().getArtifactId())
                            ? "other version loaded"
                            : "excluded from report");
                    output(prefixWithPlus() + id + extraInfoForProjectLineInfo(dep, reasonNotInScope, isFirst));
                }
            } else {
                output(prefixWithPlus() + id + extraInfoForProjectLineInfo(dep, "reported above", isFirst));
            }
        }

        protected abstract void introduceDependenciesDetail() throws MojoExecutionException;

        protected abstract String extraInfoForProjectLineInfo(DependencyNode dep, String exclusionInfo, boolean isFirst);
    }

    public class TreeReport extends AbstractTreeReport {
        
        public TreeReport() { super("  +-", "  | "); }

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
        protected void addLicenseInfoEntries(MavenProject p) throws MojoExecutionException {
            addProjectEntry("License", licensesSummaryString(p.getLicenses()));
        }
        
        protected void addVerboseEntries(MavenProject p) {}
        
        protected void introduceDependenciesDetail() throws MojoExecutionException {
            output(currentPrefix + "  Dependencies detail:");
        }

        protected String extraInfoForProjectLineInfo(DependencyNode dep, String exclusionInfo, boolean isFirst) {
            if (exclusionInfo==null) return "";
            return " ("+exclusionInfo+")";
        }
    }
    
    public class SummaryReport extends AbstractTreeReport {

        public SummaryReport() { super("+-", "| "); }
        
        public void addProjectEntry(String key, String value) {
        }
        
        protected void addLicenseInfoEntries(MavenProject p) {}
        protected void addVerboseEntries(MavenProject p) {}
        
        protected void introduceDependenciesDetail() {}
        
        protected String extraInfoForProjectLineInfo(DependencyNode dep, String exclusionInfo, boolean isFirst) {
            if (dep==null) {
                if (isFirst) {
                    return (isNonEmpty(exclusionInfo) ? "("+exclusionInfo+")" : "")+": "+
                        licensesSummaryString(project.getLicenses());
                } else { 
                    return " (unknown scope"+(isNonEmpty(exclusionInfo) ? ", "+exclusionInfo : "")+")";
                }
            }
            
            MavenProject p = projectByIdCache.get(id(dep));
            List<License> lics = p!=null ? p.getLicenses() : null;
            if (p==null) {
                getLog().debug("Not loaded "+id(dep));
            }
            
            return " ("+dep.getDependency().getScope()+
                (Boolean.TRUE.equals(dep.getDependency().getOptional()) ? ", optional" : "")+
                (isNonEmpty(exclusionInfo) ? ", "+exclusionInfo : "")+"): "+
                (p!=null ? licensesSummaryString(lics) : "<not loaded>");
        }
    }

    protected static boolean isNonEmpty(String s) {
        return (s!=null && s.length()>0);
    }

    protected DefaultArtifact newMavenArtifact(Artifact da) {
        return new org.apache.maven.artifact.DefaultArtifact(
            da.getGroupId(), da.getArtifactId(), da.getVersion(),
            null, da.getExtension(), da.getClassifier(), artifactHandler);
    }
    
}
