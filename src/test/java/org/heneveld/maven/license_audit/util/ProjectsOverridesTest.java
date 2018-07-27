package org.heneveld.maven.license_audit.util;

import java.io.InputStreamReader;
import java.io.StringReader;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;

import junit.framework.TestCase;

public class ProjectsOverridesTest extends TestCase {

    public void testParse() {
        ProjectsOverrides l = ProjectsOverrides.fromReader(new InputStreamReader(getClass().getResourceAsStream("overrides-sample-1.yaml")));
        assertEquals("Apache-2.0", LicenseCodes.getLicenseCode(l.getLicense("foo.group:code:1.0").get(0).getName()));
        assertEquals("Apache-2.0", LicenseCodes.getLicenseCode(l.getLicense("foo.group:code:1.1").get(0).getName()));
        assertEquals("Apache-2.0", LicenseCodes.getLicenseCode(l.getLicense("foo.group:code:1.2").get(0).getName()));
        assertEquals("http://group.foo/code", l.getUrl("foo.group:code:1.0"));
        assertNull(l.getLicense("foo.group:code:0.9"));
        assertEquals("Custom License", l.getLicense("foo.group:map:1.0").get(0).getName());
        assertEquals("http://group.foo/license", l.getLicense("foo.group:list:1.0").get(0).getUrl());
        assertEquals("http://www.eclipse.org/legal/epl-v10.html", l.getLicense("foo.group:list:1.0").get(1).getUrl());
    }
    
    public void testParseReadmeExample() {
        // wildcard chars have no significance here but the caller might pass them
        ProjectsOverrides l = ProjectsOverrides.fromReader(new InputStreamReader(getClass().getResourceAsStream("overrides-sample-2.yaml")));
        assertEquals("Apache-2.0", LicenseCodes.getLicenseCode(l.getLicense("org.codehaus.jettison:jettison").get(0).getName()));
        assertEquals("BSD-2-Clause", LicenseCodes.getLicenseCode(l.getLicense("dom4j:dom4j:*").get(0).getName()));
        assertEquals("http://dom4j.sourceforge.net/", l.getUrl("dom4j:dom4j-core:1.4-dev-8"));
        assertNull("http://dom4j.sourceforge.net/", l.getUrl("dom4j:dom4j-core:1.4-dev-null"));
        
        assertEquals("http://dom4j.sourceforge.net/dom4j-1.6.1/license.html", 
            l.getLicense(newMavenProject("dom4j", "dom4j", "1.6.1")).get(0).getUrl());
        assertEquals("http://dom4j.sourceforge.net/dom4j-1.6.1/license.html", 
            l.getLicense(newMavenProject("dom4j", "dom4j", "0.0.0")).get(0).getUrl());
        assertEquals("http://dom4j.sourceforge.net/dom4j-1.6.1/license.html", 
            l.getLicense(newMavenProject("dom4j", "dom4j-core", "1.4-dev-8")).get(0).getUrl());
        assertTrue(l.getLicense(newMavenProject("dom4j", "dom4j-core", "1.4-dev-null")).isEmpty());
    }

    private MavenProject newMavenProject(String groupId, String artifactId, String version) {
        Model mm = new Model();
        mm.setGroupId(groupId);
        mm.setArtifactId(artifactId);
        mm.setVersion(version);
        return new MavenProject(mm);
    }

    public void testMultipleEntries() {
        ProjectsOverrides l = ProjectsOverrides.fromReader(new StringReader(
            "[{ id: one, license: Apache-2.0 }]"));
        l.addFromYaml(new StringReader(
            "[{ id: one, url: \"http://foo\" }]"));
        assertEquals(1, l.getLicense("one").size());
        assertEquals("http://foo", l.getOverridesForProject("one").get("url"));
    }

}
