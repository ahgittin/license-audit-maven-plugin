package org.heneveld.maven.license_audit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.License;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.project.MavenProject;
import org.heneveld.maven.license_audit.util.Coords;
import org.heneveld.maven.license_audit.util.LicenseCodes;
import org.heneveld.maven.license_audit.util.ProjectsOverrides;
import org.heneveld.maven.license_audit.util.SimpleMultiMap;

@Mojo( name = "notices", defaultPhase = LifecyclePhase.COMPILE)
public class GenerateNoticesMojo extends AbstractLicensingMojo {

    private static final String URL_REGEX = "(\\w+:)?//([a-zA-Z0-9\\-]+\\.[a-zA-Z0-9\\-]+)+(\\:[0-9]+)?(/.*)?";
    
    @Override
    protected void generateOutput() throws MojoExecutionException {
        SimpleMultiMap<String, Object> projectsByGroup = new SimpleMultiMap<String, Object>();
        
        if (!projectErrors.isEmpty()) {
            throw new MojoExecutionException("Refusing to generate output; there are project errors: "+projectErrors);
        }
        
        for (String id: projectByIdCache.keySet()) {
            MavenProject p = projectByIdCache.get(id);
            String groupId = p.getGroupId();
            projectsByGroup.put(groupId, p);
        }
        
        // load extras
        ProjectsOverrides extras = loadExtras();
        for (String id: extras.getProjects()) {
            Map<String, Object> projectOverrides = overrides.getOverridesForProject(id);
            Map<String, Object> projectData = new LinkedHashMap<String,Object>(extras.getOverridesForProject(id));
            if (projectOverrides!=null) {
                // anything from extras is merged on top of any existing overrides
                projectOverrides = new LinkedHashMap<String,Object>(projectOverrides);
                projectOverrides.putAll(projectData);
                projectData = projectOverrides;
            }
            projectsByGroup.put(id, projectData);
        }
        
        // if the overrides declares a parent project, prefer it:
        // TODO allow overrides to be subsumed; only include if it is subsuming.
        for (String overrideId: overrides.getProjects()) {
            Set<Object> projectsMatchingPrefix = new LinkedHashSet<Object>();
            Iterator<String> pki = projectsByGroup.keySet().iterator();
            while (pki.hasNext()) {
                String pk = pki.next();
                if (pk.startsWith(overrideId+".")) {
                    projectsMatchingPrefix.addAll(projectsByGroup.get(pk));
                    pki.remove();
                }
            }
            if (!projectsMatchingPrefix.isEmpty()) {
                projectsByGroup.put(overrideId, overrides.getOverridesForProject(overrideId));
                projectsByGroup.putAll(overrideId, projectsMatchingPrefix);
            }
        }
        
        List<String> ids = new ArrayList<String>();
        getLog().debug("Generating notices, projects="+projectsByGroup.keySet()+"; extras="+extras.getProjects());
        if (onlyExtras) {
            ids.addAll(extras.getProjects());
        } else {
            ids.addAll(projectsByGroup.keySet());
        }
        Collections.sort(ids, new Comparator<String>() {
            public int compare(String o1, String o2) {
                return o1.toLowerCase().compareTo(o2.toLowerCase());
            }
        });
        
        for (String id: ids) {
            Set<Object> projects = projectsByGroup.get(id);
            Set<String> internal = getFields(projects, "internal");
            if (!internal.isEmpty() && "true".equalsIgnoreCase(internal.iterator().next())) {
                continue;
            }
            
            if (projects==null) 
                throw new MojoExecutionException("Could not find project '"+id+"'.");
            String name = join(getFields(projects, "name"), " / ");
            if (name==null || name.length()==0) name = id;
            
            output("This project includes the software: "+name);
            
            outputIfLastNonEmpty("  Available at: ", getUrls(id, projects));
            outputIfLastNonEmpty("  Developed by: ", getOrganizations(projects));
            String files = joinOr(getFields(projects, "files"), "; ", null);
            if (files==null) {
                // if not listed, assume it's the id; but empty string means never show
                files=id;
            }
            if (!name.equals(files)) {
                // don't show if it's the same as what we just showed
                outputIfLastNonEmpty("  Inclusive of: ", files);
            }
            outputIfLastNonEmpty("  Version used: ", joinOr(getVersions(id, projects), "; ", null));
            
            Set<String> l = getLicenses(id, projects);
            output("  Used under the following license"+(l.size()>1 ? "s:\n    " : ": ") + join(l, "\n    "));
            
            List<String> notices = getFieldsAsList(projects, "notice");
            notices.addAll(getFieldsAsList(projects, "notices"));
            if (!notices.isEmpty()) {
                // TODO currently requires note entries to be one per line;
                // ideally accept long lines and maps, both formatted nicely.
                output("  "+join(notices, "\n  "));
            }
            output("");
        }
    }
    
    private static String joinOr(Set<String> fields, String separator, String ifNone) {
        if (fields==null || fields.isEmpty()) return ifNone;
        return join(fields, separator);
    }

    protected Set<String> getLicenses(String groupId, Set<Object> projects) {
        List<License> overrideLic = overrides.getLicense(groupId);
        if (overrideLic!=null && !overrideLic.isEmpty()) {
            // replace projects with the singleton set of the override
            projects = new LinkedHashSet<Object>();
            projects.add(overrides.getOverridesForProject(groupId));
        }
        
        Set<String> result = new LinkedHashSet<String>();
        for (Object p: projects) {
            List<License> lics;
            if (p instanceof MavenProject) lics = overrides.getLicense((MavenProject)p);
            else {
                lics = ProjectsOverrides.parseAsLicenses( ((Map<?,?>)p).get("license") );
                // if licenses left out of maps, don't generate error yet
                if (lics==null) continue;
            }
            String singleCode = licensesCode(lics);
            String url;
            if (singleCode!=null) {
                // look for a url declared in a license
                url = lics.iterator().next().getUrl();
                if (url==null) {
                    url = LicenseCodes.lookupCode(singleCode).getUrl();
                } else {
                    if (!url.matches(URL_REGEX)) {
                        // not a valid URL; assume in project
                        url = "in-project reference: "+url;
                    }
                }
                result.add(LicenseCodes.lookupCode(singleCode).getName()+" ("+url+")");
            } else {
                result.addAll(Arrays.asList(licensesString(lics, false).split("\n")));
            }
        }
        if (result.isEmpty() && !projects.isEmpty())
            result.addAll(Arrays.asList(licensesString(null, false).split("\n")));
        return result;
    }

    protected Set<String> getFields(Set<Object> projects, String field) {
        Set<String> result = new LinkedHashSet<String>();
        for (Object o: projects) {
            if (o instanceof Map) {
                addAllNonEmptyStrings(result, ((Map<?,?>)o).get(field));
            } else {
                // MavenProject -- no notices to include
            }
        }
        return result;
    }

    protected List<String> getFieldsAsList(Set<Object> projects, String field) {
        List<String> result = new ArrayList<String>();
        for (Object o: projects) {
            if (o instanceof Map) {
                addAllNonEmptyStrings(result, ((Map<?,?>)o).get(field));
            } else {
                // MavenProject -- no notices to include
            }
        }
        return result;
    }

    private void outputIfLastNonEmpty(String leader, String tail) throws MojoExecutionException {
        if (isNonEmpty(tail)) output(leader + tail);
    }

    private Set<String> getVersions(String groupId, Set<Object> projects) throws MojoExecutionException {
        Object overrideUrl = overrides.getOverridesForProject(groupId).get("version");
        if (overrideUrl!=null && overrideUrl.toString().length()>0) {
            return Collections.singleton(overrideUrl.toString());
        }
        
        Set<String> result = new LinkedHashSet<String>();
        for (Object p: projects) {
            if (p instanceof Map) {
                addFirstNonEmptyString(result, ((Map<?,?>)p).get("version"));
            } else if (p instanceof MavenProject) {
                if (addFirstNonEmptyString(result, overrides.getOverridesForProject((MavenProject)p).get("version")));
                else addFirstNonEmptyString(result, ((MavenProject)p).getVersion());
            }
        }
        return result;
    }

    private String getUrls(String groupId, Set<Object> projects) throws MojoExecutionException {
        Object overrideUrl = overrides.getOverridesForProject(groupId).get("url");
        if (overrideUrl!=null && overrideUrl.toString().length()>0) {
            return overrideUrl.toString();
        }
        
        Set<String> result = new LinkedHashSet<String>();
        for (Object p: projects) {
            if (p instanceof Map) {
                addFirstNonEmptyString(result, ((Map<?,?>)p).get("url"));
            } else if (p instanceof MavenProject) {
                if (addFirstNonEmptyString(result, overrides.getOverridesForProject((MavenProject)p).get("url")));
                else addFirstNonEmptyString(result, ((MavenProject)p).getUrl());
            }
        }
        String commonRoot = longestRelevantUrl(groupId, result);
        getLog().debug("Analysing URLs for "+groupId+": from "+result+" found common root "+commonRoot);
        if (commonRoot!=null) return commonRoot;

        // else if any are prefixes of others, remove the longest ones
        // (maven has an annoying habit of appending artifactId's to inherited url's, meaning we get URL/parent/art1 URL/parent/art2 !)
        for (String prefix: new LinkedHashSet<String>(result)) {
            Iterator<String> ri = result.iterator();
            while (ri.hasNext()) {
                String url = ri.next();
                if (url.startsWith(prefix) && !url.equals(prefix)) ri.remove(); 
            }
        }
        Set<String> result2 = new LinkedHashSet<String>();
        for (String r: result) {
            if (r.matches(URL_REGEX)) result2.add(r);
            else if (r.startsWith("www.")) result2.add("http://"+r);
            else result2.add("(invalid url reported: "+r+")");
        }
        return join(result2, " ");
    }

    static String longestRelevantUrl(String groupId, Iterable<String> urls) {
        String commonPrefix = null;
        for (String url: urls) {
            if (commonPrefix==null) commonPrefix = url;
            else {
                String newLongest = "";
                for (int i=0; i<=commonPrefix.length() && i<=url.length(); i++) {
                    if (commonPrefix.substring(0, i).equals(url.substring(0, i))) {
                        newLongest = commonPrefix.substring(0, i);
                    } else {
                        break;
                    }
                }
                String oldLongest = commonPrefix;
                commonPrefix = newLongest;
                if (commonPrefix.length()==0) break;
                if (newLongest.indexOf("/")==-1) {
                    // not a valid URL; ignore
                    commonPrefix = "";
                    break;
                }
                if (commonPrefix.length()==oldLongest.length() || commonPrefix.length()==url.length() || commonPrefix.endsWith("/")) continue;
                // it includes something common to both of them but which isn't a complete url and doesn't end in "/";
                // truncate to last /
                commonPrefix = commonPrefix.substring(0, commonPrefix.lastIndexOf("/")+1);
                
            }
        }
        if (commonPrefix!=null && commonPrefix.matches(URL_REGEX)) {
            // looks like a url - does it contain the most significant group id keyword?
            // (if not we have something like http://github.com/ which is unuseful!)
            String groupSigWord = groupId.substring(groupId.lastIndexOf(".")+1);
            if (commonPrefix.contains(groupSigWord))
                return commonPrefix;
        }
        return null;
    }

    private String getOrganizations(Set<Object> projects) throws MojoExecutionException {
        Set<String> result = new LinkedHashSet<String>();
        for (Object p: projects) {
            addFirstNonEmptyString(result,
                // first look in overrides
                organizationString(p instanceof MavenProject ? overrides.getOverridesForProject(Coords.of((MavenProject)p).normal()) : null), 
                organizationString(p));
        }
        return join(result, " ");
    }

    private boolean addFirstNonEmptyString(Collection<String> target, Object ...objects) {
        for (Object object: objects) {
            if (object!=null && !"".equals(object)) {
                target.add(toStringPoorMans(object));
                return true;
            }
        }
        return false;
    }

    static void addAllNonEmptyStrings(Collection<String> target, Object ...objects) {
        for (Object object: objects) {
            if (object==null) continue;
            if ("".equals(object)) continue;
            if (object instanceof Iterable) {
                for (Object o : (Iterable<?>)object) {
                    addAllNonEmptyStrings(target, o);
                }
                continue;
            }
            target.add(toStringPoorMans(object));
        }
    }

    protected void output(String line) throws MojoExecutionException {
        if (outputWriter!=null) {
            try {
                outputWriter.write(line);
                outputWriter.write("\n");
            } catch (IOException e) {
                throw new MojoExecutionException("Error writing to "+outputFilePath, e);
            }
        }
        getLog().info(line);
    }

}
