package org.heneveld.maven.license_audit.util;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        if (projectId==null) return null;
        Map<String, Object> result = overridesByProject.get(projectId.trim());
        if (result==null) return Collections.emptyMap();
        return result;
    }
    public Map<?, ?> getOverridesForProject(MavenProject p) {
        return getOverridesForProject(Coords.of(p).normal());
    }
    
    public List<License> getLicense(MavenProject p) {
        if (p==null) return null;
        List<License> result = null;
        result = getLicense(Coords.of(p).normal());
        if (result!=null) return result;
        // wildcard for project trumps project and unversioned
        result = getLicense(Coords.of(p).unversioned()+":*");
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
        result = getUrl(Coords.of(p).normal());
        if (result!=null) return result;
        // wildcard for project trumps project and unversioned
        result = getUrl(Coords.of(p).unversioned()+":*");
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
        for (Object entry: ((Iterable<?>)data)) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException("Invalid entry; entry should be a YAML map (not "+entry.getClass()+": "+entry+")");
            }
            @SuppressWarnings("unchecked")
            Map<String,Object> emap = (Map<String,Object>)entry;
            String id = (String)emap.get("id");
            @SuppressWarnings("unchecked")
            Iterable<String> ids = (Iterable<String>)emap.get("ids");
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
                    // do this to ensure it is the right type (retrieval will throw if malformed)
                    getLicense(projectId);
                }
            } else {
                throw new IllegalArgumentException("Invalid entry; entry must contain 'id' or 'ids' "+emap);
            }
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
        if (l instanceof String) return LicenseCodes.lookupCode((String)l);
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
