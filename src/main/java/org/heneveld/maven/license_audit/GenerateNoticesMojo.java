package org.heneveld.maven.license_audit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
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
public class GenerateNoticesMojo extends AbstractLicensingMojo
{

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
        // if the overrides declares a parent project, prefer it:
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
            if (!projectsByGroup.isEmpty()) {
                projectsByGroup.putAll(overrideId, projectsMatchingPrefix);
            }
        }
        
        // load extras
        ProjectsOverrides extras = new ProjectsOverrides();
        if (extrasFile!=null) {
            try {
                extras = ProjectsOverrides.fromFile(extrasFile);
            } catch (IOException e) {
                throw new MojoExecutionException("Could not load extras from "+extrasFile+": "+e);
            }
        }
        for (String id: extras.getProjects()) {
            projectsByGroup.put(id, extras.getOverridesForProject(id));
        }
        
        List<String> ids = new ArrayList<String>();
        ids.addAll(projectsByGroup.keySet());
        Collections.sort(ids, new Comparator<String>() {
            public int compare(String o1, String o2) {
                return o1.toLowerCase().compareTo(o2.toLowerCase());
            }
        });
        
        for (String id: ids) {
            output("This project includes the software: "+id);
            
            Set<Object> projects = projectsByGroup.get(id);
            
            outputIfLastNonEmpty("  Available at: ", getUrls(id, projects));
            outputIfLastNonEmpty("  Developed by: ", getOrganizations(projects));
            Set<String> l = getLicenses(projects);
            outputIfLastNonEmpty("  Used under the following license"+(l.size()>1 ? "s:\n    " : ": "), join(l, "\n    "));
            Set<String> notices = getFields(projects, "notice");
            if (!notices.isEmpty()) output("  "+join(notices, "\n  "));
            output("");
        }
    }

    protected Set<String> getLicenses(Set<Object> projects) {
        Set<String> result = new LinkedHashSet<String>();
        for (Object p: projects) {
            List<License> lics;
            if (p instanceof MavenProject) lics = overrides.getLicense((MavenProject)p);
            else lics = ProjectsOverrides.parseAsLicenses( ((Map<?,?>)p).get("license") );
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
        return result;
    }

    protected Set<String> getFields(Set<Object> projects, String field) {
        Set<String> result = new LinkedHashSet<String>();
        for (Object o: projects) {
            if (o instanceof Map) {
                addFirstNonEmptyString(result, ((Map<?,?>)o).get(field));
            }
        }
        return result;
    }

    private void outputIfLastNonEmpty(String leader, String tail) throws MojoExecutionException {
        if (isNonEmpty(tail)) output(leader + tail);
    }

    private String getUrls(String groupId, Set<Object> projects) throws MojoExecutionException {
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
            // looks like a url - does it contain anything most significant group id keyword?
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

    private boolean addFirstNonEmptyString(Collection<String> set, Object ...objects) {
        for (Object object: objects) {
            if (object!=null && !"".equals(object)) {
                set.add(toStringPoorMans(object));
                return true;
            }
        }
        return false;
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
