package org.heneveld.maven.license_audit.util;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.License;
import org.apache.maven.project.MavenProject;
import org.yaml.snakeyaml.Yaml;

public class ProjectsOverrides {

    Map<String,Map<String,Object>> overridesByProject = new LinkedHashMap<String,Map<String,Object>>();
    
    public ProjectsOverrides() {}
    
    public static ProjectsOverrides fromReader(Reader r) {
        return new ProjectsOverrides().addFromYaml(r);
    }

    public static ProjectsOverrides fromFile(String path) throws IOException {
        FileReader fr = new FileReader(path);
        try {
            return new ProjectsOverrides().addFromYaml(fr);
        } finally {
            fr.close();
        }
    }

    public Collection<String> getProjects() {
        return overridesByProject.keySet();
    }

    public Map<String,Object> getOverridesForProject(String projectId) {
        Map<String, Object> result = new LinkedHashMap<>();
        
        String idBase = projectId;
        while (true) {
            Map<String, Object> lo = getOverridesForProjectExactlyOrNull(idBase);
            if (lo!=null) {
                lo = new LinkedHashMap<>(lo);
                lo.putAll(result);
                result = lo;
            }
            int li = idBase.lastIndexOf(':');
            if (li>0) {
                idBase = idBase.substring(0, li);
            } else {
                break;
            }
        }
        return result;
    }
    public Map<String,Object> getOverridesForProjectExactlyOrNull(String projectId) {
        if (projectId==null) return null;
        Map<String, Object> result = overridesByProject.get(projectId.trim());
        return result;
    }
    public Map<?, ?> getOverridesForProject(MavenProject p) {
        Map<Object,Object> result = new LinkedHashMap<Object, Object>();
        // wildcard can be specified for project and version trumps next
        result.putAll(getOverridesForProject(p.getGroupId()+":*"+":*"));
        // but that is trumped by wildcard specified for version trumps
        result.putAll(getOverridesForProject(Coords.of(p).unversioned()+":*"));
        // which is trumped by exact version match
        result.putAll(getOverridesForProject(Coords.of(p).normal()));
        
        // (if no wildcards are specified, they aren't treated as overrides, 
        // but they are treated as defaults, for things like license and urls)
        
        return result;
    }
    
    public List<License> getLicense(MavenProject p) {
        if (p==null) return null;
        List<License> result = null;
        result = parseAsLicenses(getOverridesForProject(p).get("license"));
        if (result!=null) return result;
        
        // anything on project trumps something unversioned
        result = p.getLicenses();
        // semantics of MavenProject is never to return null
        if (result!=null && !result.isEmpty()) return result;
        
        // next look up unversioned (as a default if nothing specified)
        result = getLicense(Coords.of(p).unversioned());
        if (result!=null) return result;
        return Collections.emptyList();
    }
    public List<License> getLicense(String projectId) {
        return parseAsLicenses(getOverridesForProject(projectId).get("license"));
    }
    public String getUrl(MavenProject p) {
        if (p==null) return null;
        String result = null;
        result = (String)getOverridesForProject(p).get("url");
        if (result!=null) return result;
        
        // anything on project trumps something unversioned
        result = MavenUtil.getDeclaredUrl(p);
        if (result!=null) return result;
        // next look up unversioned (as a default if nothing specified)
        result = getUrl(Coords.of(p).unversioned());
        return result;
    }
    public String getUrl(String projectId) {
        return (String) getOverridesForProject(projectId).get("url");
    }
    
    public ProjectsOverrides addFromYaml(Reader r) {
        Object data = new Yaml().load(r);
        if (!(data instanceof Iterable)) {
            throw new IllegalArgumentException("Input data invalid; file should be a YAML list (not "+data.getClass()+"), each containing an 'entry' map");
        }
        Set<String> projectsToValidate = new LinkedHashSet<>();
        for (Object entry: ((Iterable<?>)data)) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Invalid entry; entry should be a YAML map (not "+entry.getClass()+": "+entry+")");
            }
            @SuppressWarnings("unchecked")
            Map<String,Object> emap = new LinkedHashMap<>( (Map<String,Object>)entry );
            String id = (String)emap.get("id");
            @SuppressWarnings("unchecked")
            Iterable<String> ids = (Iterable<String>)emap.remove("ids");
            if (id!=null) {
                if (ids!=null) throw new IllegalArgumentException("Invalid entry; entry cannot contain 'id' and 'ids' ("+id+")");
                ids = Collections.singletonList(id);
            }
            if (ids!=null) {
                for (String projectId: ids) {
                    projectId = projectId.trim();
                    Map<String, Object> result = overridesByProject.get(projectId);
                    if (result==null) {
                        result = new LinkedHashMap<String, Object>();
                        overridesByProject.put(projectId, result);
                    }
                    result.putAll(emap);
                    projectsToValidate.add(projectId);
                }
            } else {
                throw new IllegalArgumentException("Invalid entry; entry must contain 'id' or 'ids' "+emap);
            }
        }
        
        // do this after in case invalid licenses are overridden, and collect all errors
        List<String> errors = new ArrayList<>();
        for (String projectId: projectsToValidate) {
            // do this to ensure it is the right type (retrieval will throw if malformed)
            try {
                getLicense(projectId);
            } catch (Exception e) {
                errors.add(projectId+": "+e);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("One or more projects was invalid.\n  - "+String.join("\n  - ", errors));
        }

        return this;
    }
    
    public static List<License> parseAsLicenses(Object ll) {
        if (ll==null) return null;
        if (ll instanceof Iterable) {
            List<License> result = new ArrayList<License>();
            for (Object l: ((Iterable<?>)ll)) {
                result.add(parseSingleLicense(l));
            }
            return result;
        } else {
            return Collections.singletonList(parseSingleLicense(ll));
        }
    }

    public static License parseSingleLicense(Object l) {
        if (l instanceof String) {
            final License lookup = LicenseCodes.lookupCode((String) l);
            if (null != lookup) return lookup;
            throw new IllegalArgumentException("Invalid license; it should be a map or a known code (string), not "+l);
        }
        if (l instanceof Map) {
            Map<?, ?> lmap = ((Map<?,?>)l);
            return LicenseCodes.newLicense(getRequired(lmap, String.class, "name"), (String)lmap.get("url"), (String)lmap.get("comments"));
        }
        throw new IllegalArgumentException("Invalid license; it should be a map or a known code (string), not "+l);
    }

    @SuppressWarnings("unchecked")
    protected static <T> T getRequired(Map<?, ?> emap, Class<T> type, String key) {
        Object result = emap.get(key);
        if (result==null) throw new IllegalArgumentException("Key '"+key+"' required in "+emap);
        if (!type.isInstance(result)) throw new IllegalArgumentException("Key '"+key+"' value '"+result+"' is not expected type "+type);
        return (T)result;
    }
    
}
