package org.heneveld.maven.license_audit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.maven.model.License;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.heneveld.maven.license_audit.util.Coords;
import org.heneveld.maven.license_audit.util.LicenseCodes;
import org.heneveld.maven.license_audit.util.MavenUtil;
import org.heneveld.maven.license_audit.util.ProjectsOverrides;
import org.heneveld.maven.license_audit.util.SimpleMultiMap;

@Mojo( name = "notices", defaultPhase = LifecyclePhase.COMPILE)
public class GenerateNoticesMojo extends AbstractLicensingMojo {

    private static final String URL_REGEX = "(\\w+:)?//([a-zA-Z0-9\\-]+\\.[a-zA-Z0-9\\-]+)+(\\:[0-9]+)?(/.*)?";
    
    @Parameter( defaultValue = "false", property = "outputYaml", required = true )
    protected boolean outputYaml;
    
    protected Map<String,Map<String,Object>> yamlFull = new LinkedHashMap<>();
    protected Map<String,Object> yamlCurrent = null;

    @Override
    protected void generateOutput() throws MojoExecutionException {
        // load extras
        ProjectsOverrides extras = loadExtras();
        
        SimpleMultiMap<String, Object> projectsByGroup = new SimpleMultiMap<String, Object>();
        
        if (!projectErrors.isEmpty()) {
            throw new MojoExecutionException("Refusing to generate output; there are project errors: "+projectErrors);
        }
        
        for (String id: projectByIdCache.keySet()) {
            MavenProject p = projectByIdCache.get(id);
            String groupId = p.getGroupId()+":"+p.getVersion();
            projectsByGroup.put(groupId, p);
        }
        
        // switch to artifact name where it is the only thing in the group
        SimpleMultiMap<String, Object> projectsByGroupOld = projectsByGroup;
        projectsByGroup = new SimpleMultiMap<String, Object>();
        for (String groupingId: projectsByGroupOld.keySet()) {
            Set<Object> pp = projectsByGroupOld.get(groupingId);
            if (pp.size()==1) {
                MavenProject p = (MavenProject) pp.iterator().next();
                String id = p.getGroupId();
                if (!id.endsWith(p.getArtifactId())) id += "."+p.getArtifactId();
                id += ":"+p.getVersion();
                projectsByGroup.put(id, p);
            } else {
                projectsByGroup.putAll(groupingId, pp);
            }
        }
        
        
        // merge up where there is an existing entry
        projectsByGroupOld = projectsByGroup;
        projectsByGroup = new SimpleMultiMap<String, Object>();
        for (String groupingId: projectsByGroupOld.keySet()) {
            Set<Object> pp = projectsByGroupOld.get(groupingId);
            MavenProject p = (MavenProject) pp.iterator().next();
            String groupId = p.getGroupId();
            int i = groupId.indexOf('.');
            String parentGroup = null;
            while (i>0) {
                String candidateParentGroup = groupId.substring(0, i);
                Map<String, Object> parentOverrides = overrides.getOverridesForProjectExactlyOrNull(candidateParentGroup+":"+p.getVersion());
                if (parentOverrides==null) {
                    parentOverrides = overrides.getOverridesForProjectExactlyOrNull(candidateParentGroup);
                }
                Set<Object> cpp = projectsByGroupOld.get(candidateParentGroup+":"+p.getVersion());
                if (parentOverrides!=null || (cpp!=null && !cpp.isEmpty())) {
                    parentGroup = candidateParentGroup;
                    break;
                }
                i = groupId.indexOf('.', i+1);
            }
            if (parentGroup!=null) {
                projectsByGroup.putAll(parentGroup+":"+p.getVersion(), pp);
            } else {
                projectsByGroup.putAll(groupingId, pp);
            }
        }
        
        // add extras
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
            Set<String> internal = getFields(id, projects, "internal");
            if (!internal.isEmpty() && "true".equalsIgnoreCase(internal.iterator().next())) {
                continue;
            }
            
            if (projects==null) 
                throw new MojoExecutionException("Could not find project '"+id+"'.");
            
            onProjectStart(id);
            
            onProjectDetailIfNonEmptyPreferInline("Project", 
                getFields(id, projects, "name", p->p.getName()), 
                set -> join(set, " / ") );
            
            onProjectDetailIfNonEmptyPreferInline("Version", getVersions(id, projects), (set) -> joinOr(set, "; ", null));
            onProjectDetailIfNonEmptyPreferInline("Available at", getUrls(id, projects), (set) -> join(set, " "));
            onProjectDetailIfNonEmptyPreferInline("Developed by", getOrganizations(projects), (set) -> join(set, " "));
            
            Set<String> files = getFields(id, projects, "files");
            String filesS = joinOr(files, "; ", null);
            if (filesS==null) {
                // if not listed, assume it's the id; but empty string means never show
                filesS=id;
            }
            if (!id.equals(filesS)) {
                // don't show if it's the same as what we just showed
                onProjectDetailIfNonEmptyPreferInline("Inclusive of", files, (set) -> joinOr(set, "; ", null));
            }
            
            Set<Map<String, String>> ll = getLicenses(id, projects);
            if (!ll.isEmpty()) {
                if (ll.size()==1) {
                    Map<String, String> l = ll.iterator().next();
                    String name = l.get("name");
                    if (isEmpty(name)) name = l.get("code");
                    if (isNonEmpty(name)) {
                        onProjectDetailIfNonEmptyPreferInline("License name", name, (x) -> x);
                    }
                    String url = l.get("url");
                    if (isNonEmpty(url)) {
                        onProjectDetailIfNonEmptyPreferInline("License URL", url, (x) -> x);
                    }
                } else {
                    Set<Map<String, String>> ll2 = new LinkedHashSet<>();
                    for (Map<String, String> l: ll) {
                        Map<String, String> m2 = new LinkedHashMap<>();
                        String name = l.get("name");
                        if (isEmpty(name)) {
                            name = l.get("url");
                            if (!isEmpty(name)) name = "License from "+name;
                        }
                        putIfNotNull(m2, "License name", l.get("name"));
                        putIfNotNull(m2, "License URL", l.get("url"));
                        putIfNotNull(m2, "Comment", l.get("comment"));
                        ll2.add(m2);
                    }
                    onProjectDetailIfNonEmptyPreferInline("License" + (ll2.size()!=1 ? "s" : ""), ll2, 
                        (set) -> joinOr(set, (m) -> {
                                String result = m.get("License name");
                                if (isNonEmpty(m.get("License URL"))) {
                                    if (isEmpty(result)) result = m.get("License URL");
                                    else result += " ("+m.get("License URL")+")";
                                }
                                if (isEmpty(result)) result = "<no info on license>";
                                return result;
                            }, "; ", null));
                }
            }
            
            Set<String> notices = new TreeSet<>();
            notices.addAll(getFields(id, projects, "notice"));
            notices.addAll(getFields(id, projects, "notices"));
            notices.addAll(getFields(id, projects, "copyright_by").stream().map(s -> s.toLowerCase().startsWith("copyright") || s.toLowerCase().startsWith("(c)") ? s : "Copyright (c) "+s).collect(Collectors.toList()));
            if (!notices.isEmpty()) {
                onProjectDetailIfNonEmptyPreferInline("Notice", notices, set -> join(set, "\n    "));
            }
            
            if (!outputYaml) output("");
        }
        
        if (outputYaml) {
            dumpYamlForNotice(yamlFull, "");
        }
    }

    private void dumpYamlForNotice(Object obj, String prefix) throws MojoExecutionException {
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String,Object>)obj;
            for (String k: m.keySet()) {
                String kk = prefix+k+":";
                Object v = m.get(k);
                kk += " ";
                if (v instanceof String) {
                    String vs = (String)v;
                    vs = ((String) v).trim();
                    if (vs.indexOf("\n")==-1) {
                        if ("  ".equals(prefix)) {
                            kk = extraSpacesToLength(kk, 16);
                        }
                        output(kk+vs);
                    } else {
                        output(kk+"|");
                        for (String line: vs.split("\n")) {
                            output(prefix+"  "+line);
                        }
                    }
                } else {
                    output(kk);
                    dumpYamlForNotice(v, prefix+"  ");
                    if (prefix.length()==0) output("");
                }
            }
        } else if (obj instanceof Iterable) {
            for (Object v: ((Iterable<?>)obj)) {
                if (v instanceof String) {
                    String vs = (String)v;
                    vs = ((String) v).trim();
                    if (vs.indexOf("\n")==-1) {
                        output(prefix+"- "+vs);
                    } else {
                        output(prefix+"- |");
                        for (String line: vs.split("\n")) {
                            output(prefix+"  "+line);
                        }
                    }
                } else {
                    output(prefix+"-");
                    dumpYamlForNotice(v, prefix+"  ");
                }                
            }
        } else {
            output(prefix+obj);
        }
    }

    private static <T,U> void putIfNotNull(Map<T, U> map, T key, U value) {
        if (value!=null) map.put(key, value);
    }
    
    protected void onProjectStart(String name) throws MojoExecutionException {
        if (outputYaml) {
            yamlCurrent = new LinkedHashMap<>();
            yamlFull.put(name, yamlCurrent);
        } else {
            output("This project includes the software: "+name);
        }
    }
    
    protected <T> void onProjectDetailIfNonEmptyPreferInline(String key, T value, Function<T,String> stringer) throws MojoExecutionException {
        String v = stringer.apply(value);
        if (isNonEmpty(v)) { 
            if (outputYaml) {
                if (value instanceof Collection<?>) {
                    int size = ((Collection<?>)value).size();
                    if (size==0) return;
                    if (size==1) {
                        yamlCurrent.put(key, ((Collection<?>)value).iterator().next());
                        return;
                    }
                }
                yamlCurrent.put(key, value);
            } else {
                if (v.indexOf("\n")>=0) {
                    output("  "+key+":");
                    output("    "+v);
                } else {
                    output(extraSpacesToLength("  "+key+": ", 16) + v);
                }
            }
        }
    }
    
    private static String extraSpacesToLength(String s, int len) {
        while (s.length()<len) s+=" ";
        return s;
    }
    
    private static String joinOr(Set<String> fields, String separator, String ifNone) {
        return joinOr(fields, (x) -> x, separator, ifNone);
    }
    
    private static <T> String joinOr(Set<T> fields, Function<T,String> itemString, String separator, String ifNone) {
        if (fields==null || fields.isEmpty()) return ifNone;
        return join(fields.stream().map(itemString).collect(Collectors.toList()), separator);
    }

    protected Set<Map<String,String>> getLicenses(String groupId, Set<Object> projects) {
        List<License> overrideLic = overrides.getLicense(groupId);
        if (overrideLic!=null && !overrideLic.isEmpty()) {
            // replace projects with the singleton set of the override
            projects = new LinkedHashSet<Object>();
            projects.add(overrides.getOverridesForProject(groupId));
        }
        
        Set<Map<String,String>> result = new LinkedHashSet<Map<String,String>>();
        for (Object p: projects) {
            List<License> lics;
            if (p instanceof MavenProject) lics = overrides.getLicense((MavenProject)p);
            else {
                lics = ProjectsOverrides.parseAsLicenses( ((Map<?,?>)p).get("license") );
                // if licenses left out of maps, don't generate error yet
                if (lics==null) continue;
            }
            String singleCode = licensesCode(lics);
            String url = null;
            if (singleCode!=null) {
                // look for a url declared in a license
                for (License lic: lics) {
                    if (singleCode.equals(licensesCode(Collections.singleton(lic))) && lic.getUrl()!=null) {
                        url = lic.getUrl();
                        break;
                    } else if (url==null) {
                        url = lic.getUrl();
                    }
                }
                License code = LicenseCodes.lookupCode(singleCode);
                if (url==null) {
                    // take url from code otherwise
                    url = code.getUrl();
                } else {
                    if (!url.matches(URL_REGEX)) {
                        // not a valid URL; assume in project
                        url = "in-project reference: "+url;
                    }
                }
                Map<String,String> lm = new LinkedHashMap<>();
                lm.put("name", code.getName());
                lm.put("url", url);
                result.add(lm);
            } else {
                for (License l: lics) {
                    result.add(licenseMap(l));
                }
            }
        }
        return result;
    }

    protected Set<String> getFields(String id, Set<Object> projects, String field) {
        return getFields(id, projects, field, (p)->null);
    }
    
    protected Set<String> getFields(String id, Set<Object> projects, String field, Function<MavenProject,String> f) {
        Set<String> result = new TreeSet<String>();
        Map<?, ?> lo = overrides.getOverridesForProject(id);
        if (lo.containsKey(field)) {
            addAllNonEmptyStrings(result, lo.get(field));
            return result;
        }
        for (Object o: projects) {
            if (o instanceof Map) {
                addAllNonEmptyStrings(result, ((Map<?,?>)o).get(field));
            } else if (o instanceof MavenProject) {
                lo = overrides.getOverridesForProject((MavenProject)o);
                if (lo.containsKey(field)) {
                    addAllNonEmptyStrings(result, lo.get(field));
                } else {
                    addAllNonEmptyStrings(result, f.apply((MavenProject)o));
                }
            }
        }
        return result;
    }

    private Set<String> getVersions(String groupId, Set<Object> projects) throws MojoExecutionException {
        Object overrideUrl = overrides.getOverridesForProject(groupId).get("version");
        if (overrideUrl!=null && overrideUrl.toString().length()>0) {
            return Collections.singleton(overrideUrl.toString());
        }
        
        Set<String> result = new TreeSet<String>();
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

    private Set<String> getUrls(String groupId, Set<Object> projects) throws MojoExecutionException {
        Set<String> result = new TreeSet<String>();
        
        Object overrideUrl = overrides.getOverridesForProject(groupId).get("url");
        if (overrideUrl!=null && overrideUrl.toString().length()>0) {
            if (overrideUrl instanceof Collection<?>) {
                for (Object url: ((Collection<?>)overrideUrl)) {
                    result.add(url.toString());
                }
            } else {
                result.add(overrideUrl.toString());
            }
            return result;
        }
        
        for (Object p: projects) {
            if (p instanceof Map) {
                addFirstNonEmptyString(result, ((Map<?,?>)p).get("url"));
            } else if (p instanceof MavenProject) {
                if (addFirstNonEmptyString(result, overrides.getOverridesForProject((MavenProject)p).get("url")));
                else addFirstNonEmptyString(result, MavenUtil.getDeclaredUrl( (MavenProject)p ));
            }
        }
        String commonRoot = longestRelevantUrl(groupId, result);
        getLog().debug("Analysing URLs for "+groupId+": from "+result+" found common root "+commonRoot);
        if (commonRoot!=null) {
            result.clear();
            result.add(commonRoot);
            return result;
        }

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
        return result2;
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

    private Set<String> getOrganizations(Set<Object> projects) throws MojoExecutionException {
        Set<String> result = new LinkedHashSet<String>();
        for (Object p: projects) {
            addFirstNonEmptyString(result,
                // first look in overrides
                organizationString(p instanceof MavenProject ? overrides.getOverridesForProject(Coords.of((MavenProject)p).normal()) : null), 
                organizationString(p));
        }
        return result;
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
