package org.heneveld.maven.license_audit;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import org.apache.maven.model.License;
import org.apache.maven.model.Organization;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
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
import org.heneveld.maven.license_audit.LicenseAuditMojo.DependencyDetail;
import org.heneveld.maven.license_audit.LicenseAuditMojo.DetailLevel;
import org.heneveld.maven.license_audit.util.Coords;
import org.heneveld.maven.license_audit.util.LicenseCodes;
import org.heneveld.maven.license_audit.util.ProjectsOverrides;
import org.heneveld.maven.license_audit.util.SimpleMultiMap;

public abstract class AbstractLicensingMojo extends AbstractMojo {

    @Parameter( defaultValue = "", property = "outputFile", required = false )
    protected String outputFilePath;
    protected Writer outputWriter = null;

    @Parameter( defaultValue = "-1", property = "depth", required = true )
    protected int maxDepth;

    @Parameter( defaultValue = "compile,runtime", property = "includeDependencyScopes", required = true )
    protected String includeDependencyScopes;
    protected boolean includeAllDependencyScopes;

    @Parameter( defaultValue = "false", property = "excludeRootOptionalDependencies", required = true )
    protected boolean excludeRootOptionalDependencies;

    @Parameter( defaultValue = "", property = "licensesPreferred", required = false )
    protected String licensesPreferredRaw;
    protected List<String> licensesPreferred;
    
    @Parameter( defaultValue = "", property = "overridesFile", required = false )
    protected String overridesFile;
    protected ProjectsOverrides overrides = new ProjectsOverrides();
    
    @Parameter( defaultValue = "", property = "extrasFile", required = false )
    protected String extrasFile;
    
    @Parameter( defaultValue = "", property = "extrasFiles", required = false )
    protected String extrasFiles;
    
    @Parameter( defaultValue = "false", property = "onlyExtras", required = true )
    protected boolean onlyExtras;
    
    @Component
    Maven defaultMaven;
    
    @Component
    MavenProject project;

    @Component
    MavenSession mavenSession;
    
    @Component
    ProjectBuilder projectBuilder;
    @Component
    ProjectDependenciesResolver depsResolver;
    @Component
    ArtifactHandler artifactHandler;

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
    protected SimpleMultiMap<String,org.apache.maven.artifact.Artifact> projectArtifacts = new SimpleMultiMap<String,org.apache.maven.artifact.Artifact>();

    // keyed by groupId + artifactId + version
    Map<String,MavenProject> projectByIdCache = new LinkedHashMap<String,MavenProject>();
    SimpleMultiMap<String,Object> projectErrors = new SimpleMultiMap<String,Object>();
    SimpleMultiMap<String,DependencyNode> depNodesByIdCache = new SimpleMultiMap<String,DependencyNode>();

    int forcedReleaseYear = -1;

    public void execute() throws MojoExecutionException {
        setupMojo();
        
        resolveDependencies();
        
        generateOutput();
        
        finishMojo();
    }

    protected void setupMojo() throws MojoExecutionException {
        includeDependencyScopes = ","+includeDependencyScopes.toLowerCase()+",";
        includeAllDependencyScopes = includeScope("all");
            
        if (isNonEmpty(outputFilePath)) {
            try {
                outputWriter = new FileWriter(outputFilePath);
            } catch (IOException e) {
                throw new MojoExecutionException("Error creating "+outputFilePath+": "+e);
            }
        }
        
        if (isNonEmpty(overridesFile)) addOverridesFromFile("overrides", overrides, overridesFile);
        loadExtrasTo(overrides, "overrides (extras)");
        
        if (isNonEmpty(licensesPreferredRaw)) {
            licensesPreferred = Arrays.asList(licensesPreferredRaw.split("\\s*,\\s*"));
        }
        
        if (maxDepth<0) {
            maxDepth = Integer.MAX_VALUE;
        }
    }
    
    protected ProjectsOverrides loadExtras() throws MojoExecutionException {
        return loadExtrasTo(null, "extras");
    }

    protected ProjectsOverrides loadExtrasTo(ProjectsOverrides target, String context) throws MojoExecutionException {
        if (target==null) target = new ProjectsOverrides();
        if (isNonEmpty(extrasFiles)) {
            String ef = extrasFiles;
            // allow ; to be used everywehre; on unix also :
            if (!File.pathSeparator.equals(";")) ef=ef.replace(File.pathSeparator, ";");
            // split("[..]") and splite("(a|b)") don't work!
            for (String f: ef.split(";")) addOverridesFromFile(context, target, f);
        }
        addOverridesFromFile(context, target, extrasFile);
        return target;
    }

    protected void addOverridesFromFile(String context, ProjectsOverrides overrides, String file) throws MojoExecutionException {
        if (file!=null && file.length()>0) {
            // is loaded again below, but add to overrides so info is available
            try {
                getLog().debug("Reading "+context+" file: "+file);
                FileReader fr = new FileReader(file);
                overrides.addFromYaml(fr);
                fr.close();
            } catch (Exception e) {
                throw new MojoExecutionException("Error reading "+file+": "+e);
            }
        }
    }

    protected void resolveDependencies() throws MojoExecutionException {
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
    }

    protected void finishMojo() throws MojoExecutionException {
        if (outputWriter!=null) {
            try {
                outputWriter.close();
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
        projectErrors.put(id, error);
        if (error instanceof Throwable) {
            getLog().warn("Error found in "+id+" (attempting to continue): "+error, (Throwable) error);
        } else { 
            getLog().warn("Error found in "+id+" (attempting to continue): "+error);
        }
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

        DefaultArtifact n0art = newMavenArtifact(n0.getArtifact());
        if (p==null && n0.getArtifact()!=null) p = loadProject(n0art);
        
        includedBaseArtifactsCoordsToProject.put(Coords.of(n0).baseArtifact(), Coords.of(n0).normal());
        includedProjects.add(Coords.of(n0).normal());
        includedArtifactsUnversionedToBaseArtifactCoords.put(Coords.of(n0).unversionedArtifact(), Coords.of(n0).baseArtifact());
        includedProjectsUnversionedToVersioned.put(Coords.of(n0).unversioned(), Coords.of(n0).normal());
        projectArtifacts.put(Coords.of(n0).normal(), n0art);
        
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

    protected boolean isRoot(String id) {
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

    // cheap and cheerful pretty-printing
    static String toStringPoorMans(Object object) {
        if (object==null) return null;
        if (object instanceof Map) return toStringPoorMans( ((Map<?,?>)object).entrySet() );
        if (object instanceof Map.Entry) 
            return toStringPoorMans( ((Map.Entry<?,?>)object).getKey() )+": "+toStringPoorMans( ((Map.Entry<?,?>)object).getValue() );
        if (object instanceof Iterable) {
            Iterator<?> oi = ((Iterable<?>)object).iterator();
            StringBuilder result = new StringBuilder();
            result.append("[");
            if (oi.hasNext()) {
                result.append(" ");
                result.append(toStringPoorMans(oi.next()));
            }
            while (oi.hasNext()) {
                result.append(", ");
                result.append(toStringPoorMans(oi.next()));
            }
            if (result.length()>1) result.append(" ");
            result.append("]");
            return result.toString();
        }
        return object.toString();
    }

    protected DefaultArtifact newMavenArtifact(Artifact da) {
        DefaultArtifact result = new org.apache.maven.artifact.DefaultArtifact(
            da.getGroupId(), da.getArtifactId(), da.getVersion(),
            null, da.getExtension(), da.getClassifier(), artifactHandler);
        result.setFile(da.getFile());
        return result;
    }

    protected static String organizationString(Object org) {
        if (org==null) return null;
        if (org instanceof Organization) {
            Organization org3 = (Organization)org;
            StringBuilder ri = new StringBuilder();
            if (isNonEmpty(org3.getName())) ri.append(org3.getName());
            int nameOrgLen = ri.length();

            if (isNonEmpty(org3.getUrl())) {
                if (nameOrgLen>0) ri.append(" (");
                ri.append(org3.getUrl());
            }
            if (ri.length() > nameOrgLen) ri.append(")");

            String result = ri.toString().trim();
            if (result.length()>0) return result;
            return null;
        }
        if (org instanceof Map) {
            Object org2 = ((Map<?,?>) org).get("organization");
            if (org2!=null) org = org2;
            Organization org3;
            if (org2 instanceof Organization) {
                org3 = (Organization) org2;
            } else {
                org3 = new Organization();
                if (org2 instanceof String) {
                    org3.setName((String)org2);
                } else if (org2 instanceof Map) {
                    org3.setName(toStringPoorMans(((Map<?,?>)org2).get("name")));
                    org3.setUrl(toStringPoorMans(((Map<?,?>)org2).get("url")));
                }
            }
            return organizationString(org3);
        }
        if (org instanceof MavenProject) {
            return organizationString( ((MavenProject)org).getOrganization() );
        }
        throw new IllegalArgumentException("Invalid organization: "+org);
    }
    
    protected static String contributorsString(Iterable<? extends Contributor> contributors) {
        if (contributors==null) return null;
        Set<String> result = new LinkedHashSet<String>();
        for (Contributor c: contributors) {
            StringBuilder ri = new StringBuilder();
            if (isNonEmpty(c.getName())) ri.append(c.getName());
            if (isNonEmpty(c.getOrganization()) && !c.getOrganization().startsWith(Organization.class.getName())) {
                // ignore org strings of the form org.apache.maven.model.Organization@4b713040
                // these come from Developer subclass of Contributor which seems to do an Organization.toString() :(
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

    protected static String licensesString(Iterable<? extends License> licenses, boolean includeComments) {
        return licensesStringInternal(licenses, false, true, includeComments);
    }

    private static String licensesStringInternal(Iterable<? extends License> licenses, boolean preferSummaryCodeOverName, boolean includeUrl, boolean includeComments) {
        // NB: subtly different messages if things are empty 
        if (licenses==null) return "<no license info>";
        Set<String> result = new LinkedHashSet<String>();
        for (License l: licenses) {
            StringBuilder ri = new StringBuilder();
            if (isNonEmpty(l.getName())) {
                if (preferSummaryCodeOverName) {
                    String code = LicenseCodes.getLicenseCode(l.getName());
                    ri.append(isNonEmpty(code) ? code : l.getName());
                } else {
                    ri.append(l.getName());
                }
            }
            if (isNonEmpty(l.getUrl())) {
                if (ri.length()>0) {
                    if (!includeUrl) { /* nothing */ }
                    else { ri.append(" ("+l.getUrl()+")"); }
                } else {
                    ri.append(l.getUrl());
                }
            }
            if (isNonEmpty(l.getComments())) {
                if (ri.length()>0) {
                    if (!includeComments) { /* nothing */ }
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

    protected static String licensesSummaryString(Iterable<? extends License> licenses) {
        String summary = licensesStringInternal(licenses, true, false, false);
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
        if (li.hasNext()) {
            //eval each and get the preference
            List<String> codes = new ArrayList<String>();
            for (License l: licenses) {
                if (isNonEmpty(l.getComments()))
                    // if any have comment then disallow
                    return null;
                String code = LicenseCodes.getLicenseCode(l.getName());
                if (isNonEmpty(code)) codes.add(code);
            }
            if (codes.isEmpty()) return null;
            if (codes.size()==1) return codes.get(0);
            if (licensesPreferred!=null) {
                for (String preferredCode: licensesPreferred) {
                    if (codes.contains(preferredCode)) return preferredCode;
                }
            }
            return null;
        }
        // now see if there is a code for this one
        li = licenses.iterator();
        String code = LicenseCodes.getLicenseCode(li.next().getName());
        if (isNonEmpty(code)) return code;
        return null;
    }
    
    List<License> getLicenses(MavenProject p, String idIfProjectMightBeNull) {
        if (p!=null) return overrides.getLicense(p);
        return overrides.getLicense(idIfProjectMightBeNull);
    }

    protected int getForcedReleaseYear() {
        return forcedReleaseYear;
    }
    
    /** For use in tests, where generated docs may have 2015 hard-coded. */
    public void setForcedReleaseYear(int forcedReleaseYear) {
        this.forcedReleaseYear = forcedReleaseYear;
    }
    
    protected abstract void generateOutput() throws MojoExecutionException;

}
