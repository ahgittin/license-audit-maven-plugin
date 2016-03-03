package org.heneveld.maven.license_audit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import junit.framework.Assert;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.io.Files;

public class LicenseAuditMojoTest extends BetterAbstractMojoTestCase {

    static public void fail(String message) {
        System.err.println(message);
        Assert.fail(message);
    }
    
    protected String currentTestProjectSubdir;
    protected StringWriter mojoOutputWriter;
    protected Map<String,Map<String,String>> mojoReportedData;
    
    protected String getMojoOutput() {
        return mojoOutputWriter.toString();
    }
    
    protected String getMojoReportedData(String project, String key) {
        Map<String, String> m = mojoReportedData.get(project);
        if (m==null) return null;
        return m.get(key);
    }
    
    protected File getTestFileInCurrentTestProject(String file) {
        return getTestFile( "src/test/resources/org/heneveld/maven/license_audit/poms_to_test_mojo/"+currentTestProjectSubdir+"/"+file );
    }

    protected void assertOutputEqualsFileInCurrentTestProject(String file) {
        File f = getTestFileInCurrentTestProject(file);
        String expected;
        try {
            expected = Joiner.on("\n").join(Files.readLines(f, Charsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (expected.trim().equals(mojoOutputWriter.toString().trim())) return;
        
        File f2 = new File(f.getAbsolutePath()+".current-test");
        try {
            FileWriter f2w = new FileWriter(f2);
            f2w.write(getMojoOutput());
            f2w.close();
        } catch (IOException e) {
            throw new RuntimeException("Unable to write actual file, after test failure where output is different to expected", e);
        }
        // run this for more info, and replace with `cp` if your output is better:  
        // for x in `find . -name *.current-test` ; do diff $x ${x%%.current-test}; done
        fail("Output is different to what is expected. See test for instructions. See the difference by running:\n\n"+
            "    diff "+f.getAbsolutePath()+" "+f2.getAbsolutePath()+"\n");
    }
    
    protected LicenseAuditMojo getMojo(String projectSubdir) throws Exception {
        currentTestProjectSubdir = projectSubdir;
        LicenseAuditMojo myMojo = (LicenseAuditMojo) lookupConfiguredMojo(
            getTestFileInCurrentTestProject("pom.xml"),
            "report");
        assertNotNull( myMojo );
        myMojo.outputWriter = mojoOutputWriter = new StringWriter();
        myMojo.reportedData = mojoReportedData = new LinkedHashMap<String,Map<String,String>>();
        myMojo.setForcedReleaseYear(2016);
        return myMojo;
    }
    
    
    public void testSimple() throws Exception {
        LicenseAuditMojo mojo = getMojo("simple_pom");
        mojo.execute();
        System.out.println(getMojoOutput());
        assertTrue("Output:\n"+getMojoOutput(), !getMojoOutput().contains("junit:junit:4.8.2"));
        assertOutputEqualsFileInCurrentTestProject("expected-report.txt");
    }

    public void testSimpleTests() throws Exception {
        LicenseAuditMojo mojo = getMojo("simple_pom");
        mojo.includeDependencyScopes = "compile,runtime,test";
        mojo.execute();
        assertTrue("Output:\n"+getMojoOutput(), getMojoOutput().contains("junit:junit:4.8.2"));
        
        assertEquals("CPL1", getMojoReportedData("junit:junit:4.8.2", "License"));

        assertOutputEqualsFileInCurrentTestProject("expected-report-test-scope.txt");
    }

    public void testBrooklyn() throws Exception {
        LicenseAuditMojo mojo = getMojo("brooklyn_pom");
        mojo.overridesFile = getTestFileInCurrentTestProject("overrides.yaml").getAbsolutePath();
        mojo.execute();
        assertFalse(
            "Detected jclouds.api:filesystem dependency included. This occurs with JDK1.7; test assumes Java 8 and this dependency not included",
            getMojoOutput().contains("jclouds.api:filesystem"));
        assertTrue("Output:\n"+getMojoOutput(), getMojoOutput().contains("org.yaml:snakeyaml:jar:1.11 (compile, included, from org.apache.brooklyn:brooklyn-utils-common:0.8.0-incubating)"));
        assertFalse("Output:\n"+getMojoOutput(), getMojoOutput().toLowerCase().contains("error"));
        
        // we don't report inferred URL's, preferring their parent
        assertEquals("https://brooklyn.incubator.apache.org/", mojo.overrides.getUrl(
            mojo.projectByIdCache.get("org.apache.brooklyn:brooklyn-core:0.8.0-incubating") ));
        // but when parent and child report a URL, prefer the child
        assertEquals("http://commons.apache.org/proper/commons-logging/", mojo.overrides.getUrl(
            mojo.projectByIdCache.get("commons-logging:commons-logging:1.2") ));
        // and if something is overridden, take it
        assertOutputEqualsFileInCurrentTestProject("expected-report.txt");

    }

    public void testBrooklynCsv() throws Exception {
        LicenseAuditMojo mojo = getMojo("brooklyn_pom");
        mojo.format = "csv";
        mojo.execute();
        
        assertEquals("0.8.0-incubating", getMojoReportedData("org.apache.brooklyn:brooklyn-core:0.8.0-incubating", "Version"));
        
        assertOutputEqualsFileInCurrentTestProject("expected-report-csv.txt");
    }

}
