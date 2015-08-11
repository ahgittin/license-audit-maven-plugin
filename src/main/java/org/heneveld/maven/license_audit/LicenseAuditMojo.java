package org.heneveld.maven.license_audit;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.Maven;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Contributor;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.DependencyNode;

@Mojo( name = "report", defaultPhase = LifecyclePhase.COMPILE)
public class LicenseAuditMojo extends AbstractMojo
{

    @Parameter( defaultValue = "", property = "output", required = false )
    private String outputFilePath;
    protected FileWriter outputFileWriter = null;

    @Parameter( defaultValue = "tree", property = "format", required = true )
    private String format;

    @Parameter( defaultValue = "-1", property = "depth", required = true )
    private int maxDepth;

    @Parameter( defaultValue = "compile,runtime", property = "includeDependencyScopes", required = true )
    private String includeDependencyScopes;
    private boolean includeAllDependencyScopes;

    @Parameter( defaultValue = "false", property = "excludeRootOptionalDependencies", required = true )
    private boolean excludeRootOptionalDependencies;

    @Parameter( defaultValue = "false", property = "suppressExcludedDependencies", required = true )
    private boolean suppressExcludedDependencies;
    
    @Parameter( defaultValue = "false", property = "listUnusedNestedOptionalDependencies", required = true )
    private boolean listUnusedNestedOptionalDependencies;

    @Parameter( defaultValue = "false", property = "listDependencyIdOnly", required = true )
    private boolean listDependencyIdOnly;

    @Parameter( defaultValue = "false", property = "suppressLicenseInfo", required = true )
    private boolean suppressLicenseInfo;

//    @Requirement
//    private DefaultRepositorySystemSessionFactory repositorySessionFactory;
    @Component
    private Maven defaultMaven;
    
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
    
    protected DependencyNode rootDependencyGraph;
    // keyed on groupId + artifactId + version 
    protected Set<String> includedProjects = new LinkedHashSet<String>();
    // keyed on groupId + artifactId + packaging + classifier + version, to project id 
    protected Map<String,String> includedBaseArtifactsCoordsToProject = new LinkedHashMap<String,String>();
    // keyed on groupId + artifactId + packaging + classifier 
    protected Map<String,String> includedArtifactsUnversionedToBaseArtifactCoords = new LinkedHashMap<String,String>();
    // keyed on groupId + artifactId 
    protected Map<String,String> includedProjectsUnversionedToVersioned = new LinkedHashMap<String,String>();
    protected SimpleMultiMap<String,String> projectToDependencyGraphParent = new SimpleMultiMap<String,String>();

    // keyed by groupId + artifactId + version
    Map<String,MavenProject> projectByIdCache = new LinkedHashMap<String,MavenProject>();
    SimpleMultiMap<String,Object> projectErrors = new SimpleMultiMap<String,Object>();
    SimpleMultiMap<String,DependencyNode> depNodesByIdCache = new SimpleMultiMap<String,DependencyNode>();
    
    public void execute() throws MojoExecutionException {
        includeDependencyScopes = ","+includeDependencyScopes.toLowerCase()+",";
        includeAllDependencyScopes = includeScope("all");
            
        if (isNonEmpty(outputFilePath)) {
            try {
                outputFileWriter = new FileWriter(outputFilePath);
            } catch (IOException e) {
                throw new MojoExecutionException("Error creating "+outputFilePath);
            }
        }
        
        if (maxDepth<0) {
            maxDepth = Integer.MAX_VALUE;
        }
        
        DependencyResolutionResult depRes;
        try {
            DefaultRepositorySystemSession repositorySession = new DefaultRepositorySystemSession(mavenSession.getRepositorySession());
            ((DefaultRepositorySystemSession)repositorySession).setDependencySelector(newRootScopeDependencySelector(repositorySession.getDependencySelector(), 0));
            DefaultDependencyResolutionRequest depReq = new DefaultDependencyResolutionRequest(project, repositorySession);
            depRes = depsResolver.resolve(depReq);
        } catch (DependencyResolutionException e) {
            throw new MojoExecutionException("Cannot resolve dependencies for "+project, e);
        }
        rootDependencyGraph = depRes.getDependencyGraph();
        getLog().debug("Dependency graph with scopes "+includeDependencyScopes+":");
        dump("", rootDependencyGraph);
        
        projectByIdCache.put(Coords.of(project).normal(), project);
        collectDeps(rootDependencyGraph, project, 0);

        if ("tree".equalsIgnoreCase(format)) {
            new TreeReport().run();
        } else if ("summary".equalsIgnoreCase(format)) {
            new SummaryReport().run();
        } else if ("report".equalsIgnoreCase(format)) {
            new ReportReport().run();
        } else if ("list".equalsIgnoreCase(format)) {
            new ListReport().run();
        } else if ("sorted-list".equalsIgnoreCase(format)) {
            new SortedListReport().run();
        } else if ("csv".equalsIgnoreCase(format)) {
            new CsvReport().run();
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

    private DependencySelector newRootScopeDependencySelector(final DependencySelector base, final int depth) {
        return new DependencySelector() {
            public boolean selectDependency(org.eclipse.aether.graph.Dependency dependency) {
                // use these root scopes
                return includeScope(dependency.getScope()) && (!dependency.isOptional() || !excludeRootOptionalDependencies);
            }
            public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
                // scope is applied to root, thereafter we revert to the base scopes
                if (depth<1)
                    return newRootScopeDependencySelector( base.deriveChildSelector(context), depth+1 );
                else
                    return base.deriveChildSelector(context);
            }
        };
    }
    
    private void dump(String prefix, DependencyNode n) {
        getLog().debug(prefix+n+": "+n.getDependency()+" / "+n.getArtifact());
        for (DependencyNode nn: n.getChildren())
            dump(prefix+"  ", nn);
    }

    protected void addError(String id, Object error) {
        Set<Object> ee = projectErrors.get(id);
        if (ee==null) ee = new LinkedHashSet<Object>();
        ee.add(error);
        projectErrors.put(id, ee);
    }
    
    protected MavenProject getProject(String projectId) {
        return projectByIdCache.get(projectId);
    }
    
    protected MavenProject loadProject(org.apache.maven.artifact.Artifact mda) {
        // older code creates a new PBReq but it lacks user props; this seems to work better
        String projectId = Coords.of(mda).normal();
        MavenProject p = projectByIdCache.get(projectId);
        if (p!=null) return p;
        
        try {
            getLog().debug("Loading project for "+mda);
            ProjectBuildingResult res = projectBuilder.build(mda, true, mavenSession.getProjectBuildingRequest());
            p = res.getProject();
        } catch (ProjectBuildingException e) {
            getLog().error("Unable to load project of "+mda+": "+e);
            addError(projectId, e);
            return null;
        }
        if (p==null) {
            addError(projectId, "Failure with no data when trying to load project");
            return null;
        }
        
        projectByIdCache.put(projectId, p);
        return p;
    }
    
    protected void collectDeps(DependencyNode n0, MavenProject p, int depth) {
        getLog().debug("Collecting dependencies of "+n0+"/"+p+" at depth "+depth);
        depNodesByIdCache.put(Coords.of(n0).normal(), n0);
        
        if (n0.getDependency()!=null) {
            if (n0.getDependency().isOptional() && (depth>1 || excludeRootOptionalDependencies)) {
                getLog().warn("Optional dependency found in dependency tree: "+n0);
                return;
            }
            if (!includeScope(n0.getDependency().getScope())) {
                getLog().debug("Skipping "+n0.getDependency().getScope()+" dependency: "+n0);
                return;
            }
        }

        if (p==null && n0.getArtifact()!=null) p = loadProject(newMavenArtifact(n0.getArtifact()));
        
        includedBaseArtifactsCoordsToProject.put(Coords.of(n0).baseArtifact(), Coords.of(n0).normal());
        includedProjects.add(Coords.of(n0).normal());
        includedArtifactsUnversionedToBaseArtifactCoords.put(Coords.of(n0).unversionedArtifact(), Coords.of(n0).baseArtifact());
        includedProjectsUnversionedToVersioned.put(Coords.of(n0).unversioned(), Coords.of(n0).normal());
        
        if (depth>=this.maxDepth) return;
        
        for (DependencyNode n: n0.getChildren()) {
            projectToDependencyGraphParent.put(Coords.of(n).normal(), Coords.of(n0).normal());
            collectDeps(n, null, depth+1);
        }
    }

    protected boolean includeScope(String scope) {
        if (includeAllDependencyScopes) return true;
        return includeDependencyScopes.contains(","+scope+",");
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
        
        public void setup() {
            ids = new LinkedHashSet<String>();
            ids.addAll(projectByIdCache.keySet());
            getLog().debug("Report collected projects for: "+ids);
            getLog().debug("Report collected project error reports for: "+projectErrors.keySet());
            ids.addAll(projectErrors.keySet());
        }
        
        public abstract void run() throws MojoExecutionException;
        
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
                addProjectEntry("URL", p.getUrl());
                if (!suppressLicenseInfo) addLicenseInfoEntries(p);
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
                
                addProjectEntry("Artifacts Included", isRoot(id) ? "(root)" : join(artifactsIncluded, "\n"));
                
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

        protected abstract void addLicenseInfoEntries(MavenProject p) throws MojoExecutionException;
        
        protected void addCompleteLicenseInfoEntries(MavenProject p) throws MojoExecutionException {
            addProjectEntry("License Code", licensesCode(p.getLicenses()));
            addProjectEntry("License", licensesString(p.getLicenses()));
            if (p.getLicenses()!=null && p.getLicenses().size()==1) {
                License license = p.getLicenses().iterator().next();
                addProjectEntry("License Name", license.getName());
                addProjectEntry("License URL", license.getUrl());
                addProjectEntry("License Comments", license.getComments());
                addProjectEntry("License Distribution", license.getDistribution());
            }
        }
        
        protected void addSummaryLicenseInfoEntries(MavenProject p) throws MojoExecutionException {
            addProjectEntry("License", licensesSummaryString(p.getLicenses()));
        }
        
        protected void addVerboseEntries(MavenProject p) throws MojoExecutionException {
            addProjectEntry("Description", p.getDescription());
            addProjectEntry("Contributors", contributorsString(p.getContributors()));
            addProjectEntry("Developers", contributorsString(p.getDevelopers()));
        }

        protected String contributorsString(Iterable<? extends Contributor> contributors) {
            if (contributors==null) return null;
            Set<String> result = new LinkedHashSet<String>();
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
                
                result.add(ri.toString().trim());
            }
            if (result.size()>0) return join(result, "\n");
            return null;
        }

        protected String licensesString(Iterable<? extends License> licenses) {
            return licensesStringInternal(licenses, false);
        }

        private String licensesStringInternal(Iterable<? extends License> licenses, boolean summaryOnly) {
            // NB: subtly different messages if things are empty 
            if (licenses==null) return "<no license info>";
            Set<String> result = new LinkedHashSet<String>();
            for (License l: licenses) {
                StringBuilder ri = new StringBuilder();
                if (isNonEmpty(l.getName())) {
                    if (summaryOnly) {
                        String code = LicenseCodes.getLicenseCode(l.getName());
                        ri.append(isNonEmpty(code) ? code : l.getName());
                    } else {
                        ri.append(l.getName());
                    }
                }
                if (isNonEmpty(l.getUrl())) {
                    if (ri.length()>0) {
                        if (summaryOnly) { /* nothing */ }
                        else { ri.append(" ("+l.getUrl()+")"); }
                    } else {
                        ri.append(l.getUrl());
                    }
                }
                if (isNonEmpty(l.getComments())) {
                    if (ri.length()>0) {
                        if (summaryOnly) { /* nothing */ }
                        else { ri.append("; "+l.getComments()); }
                    } else {
                        ri.append("Comment: "+l.getComments());
                    }
                }

                if (ri.toString().trim().length()>0) {
                    result.add(ri.toString().trim());
                } else {
                    result.add("<no info on license>");
                }
            }
            if (result.size()>0) return join(result, "\n");
            return "<no licenses>";
        }

        protected String licensesSummaryString(Iterable<? extends License> licenses) {
            String summary = licensesStringInternal(licenses, true);
            String code = LicenseCodes.getLicenseCode(summary);
            if (code==null) return summary;
            if (code.length()==0) return "<unknown>";
            return code;
        }

        /** null unless there is a single entry with code are known for all entries */
        protected String licensesCode(Iterable<? extends License> licenses) {
            if (licenses==null) return null;
            Iterator<? extends License> li = licenses.iterator();
            // no entries?
            if (!li.hasNext()) return null;
            li.next();
            // 2 or more entries?
            if (li.hasNext()) return null;
            // now see if there is a code for this one
            li = licenses.iterator();
            String code = LicenseCodes.getLicenseCode(li.next().getName());
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
    
    public class ReportReport extends AbstractReport {
        
        @Override
        protected void addLicenseInfoEntries(MavenProject p) throws MojoExecutionException {
            addSummaryLicenseInfoEntries(p);
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
        
        public void run() throws MojoExecutionException {
            setup();
            for (String id: ids) {
                runProject(id);
            }
        }

    }

    public class ListReport extends AbstractReport {
        
        @Override
        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            super.startProject(id, p);
            if (p==null) p = projectByIdCache.get(id);
            
            Set<Object> errs = projectErrors.get(id);

            // if we wanted to show where it was dragged in from
//            Set<String> parentDN = projectToDependencyGraphParent.get(id);
//            Set<DependencyNode> referencingDNs = depNodesByIdCache.get(id);

            List<License> lics = p!=null ? p.getLicenses() : null;
            String licenseLine = (suppressLicenseInfo ? "" : ": "+(p!=null ? oneLine(licensesSummaryString(lics), "; ") : "<not loaded>"));
            
            output(id+
                (errs==null || errs.isEmpty() ? "" : " (ERROR)")+
                licenseLine);
        }
        
        @Override
        protected void addLicenseInfoEntries(MavenProject p) throws MojoExecutionException {
        }
        
        @Override
        public void addProjectEntry(String key, String value) throws MojoExecutionException {
        }
        
        public void run() throws MojoExecutionException {
            setup();
            for (String id: ids) {
                runProject(id);
            }
        }

    }

    public class SortedListReport extends ListReport {
        
        @Override
        public void setup() {
            super.setup();
            
            List<String> idsSorted = new ArrayList<String>();
            idsSorted.addAll(ids);
            Collections.sort(idsSorted);
            ids.clear();
            ids.addAll(idsSorted);
        }

    }
    
    public class CsvReport extends AbstractReport {

        Set<String> columns = new LinkedHashSet<String>();
        
        Map<String,Map<String,String>> allProjectsData = new LinkedHashMap<String, Map<String,String>>();
        
        Map<String,String> thisProjectData;

        @Override
        protected void addLicenseInfoEntries(MavenProject p) throws MojoExecutionException {
            addCompleteLicenseInfoEntries(p);
        }

        @Override
        public void startProject(String id, MavenProject p) throws MojoExecutionException {
            super.startProject(id, p);
            thisProjectData = new LinkedHashMap<String, String>();
            addProjectEntry("ID", id);
            if (p!=null && p.getArtifact()!=null) {
                // the artifact is a more reliable indicator of its coordinates
                addProjectEntry("GroupId", p.getArtifact().getGroupId());
                addProjectEntry("ArtifactId", p.getArtifact().getArtifactId());
                addProjectEntry("Version", p.getArtifact().getBaseVersion());
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
        protected void addLicenseInfoEntries(MavenProject p) throws MojoExecutionException {
            addSummaryLicenseInfoEntries(p);
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
        protected void addLicenseInfoEntries(MavenProject p) {}
        
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
            List<License> lics = p!=null ? p.getLicenses() : null;
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

    private boolean isRoot(String id) {
        return project!=null && Coords.of(project).normal().equals(id);
    }
    
    static DetailLevel best(Set<DependencyDetail> set) {
        if (set==null) return null;
        DetailLevel best = null;
        for (DependencyDetail l: set) {
            if (l!=null && l.level!=null) {
                if (best==null || l.level.compareTo(best)>0) best = l.level;
            }
        }
        return best;
    }

    static String join(Collection<String> words, String separator) {
        return join(words, separator, false);
    }
    static String join(Collection<String> words, String separator, boolean trim) {
        StringBuilder result = new StringBuilder();
        for (String w: words) {
            if (result.length()>0) result.append(separator);
            result.append(trim ? w.trim() : w);
        }
        return result.toString();
    }

    static String oneLine(String multiLineString, String separator) {
        return join(Arrays.asList(multiLineString.split("\n")), separator, true);
    }
    
    static boolean isNonEmpty(String s) {
        return (s!=null && s.length()>0);
    }

    protected DefaultArtifact newMavenArtifact(Artifact da) {
        return new org.apache.maven.artifact.DefaultArtifact(
            da.getGroupId(), da.getArtifactId(), da.getVersion(),
            null, da.getExtension(), da.getClassifier(), artifactHandler);
    }

}
