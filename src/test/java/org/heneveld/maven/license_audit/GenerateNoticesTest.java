package org.heneveld.maven.license_audit;

import java.util.Arrays;

import junit.framework.TestCase;

public class GenerateNoticesTest extends TestCase {

    public void testLongestUrl() {
        assertEquals("http://foo.com/bar/", GenerateNoticesMojo.longestRelevantUrl("org.foo.bar", 
            Arrays.asList("http://foo.com/bar/1", "http://foo.com/bar/2")));
        assertEquals("http://foo.com/bar/", GenerateNoticesMojo.longestRelevantUrl("org.foo", 
            Arrays.asList("http://foo.com/bar/1", "http://foo.com/bar/2")));
        assertEquals(null, GenerateNoticesMojo.longestRelevantUrl("org.foo.bar", 
            Arrays.asList("http://foo.com/", "http://foo.com/bar/2", "http://foo.com/baz/")));
        assertEquals("http://foo.com/", GenerateNoticesMojo.longestRelevantUrl("org.foo", 
            Arrays.asList("http://foo.com/bar/1", "http://foo.com/bar/2", "http://foo.com/baz/")));
        
        // /bar is not accepted:
        assertEquals("http://foo.com/", GenerateNoticesMojo.longestRelevantUrl("org.foo", 
            Arrays.asList("http://foo.com/bar1", "http://foo.com/bar2")));
        
        // some real ones
        assertEquals("http://plexus.codehaus.org/", GenerateNoticesMojo.longestRelevantUrl("org.codehaus.plexus",
            Arrays.asList("http://plexus.codehaus.org/plexus-components/plexus-interpolation http://plexus.codehaus.org/plexus-containers/plexus-component-annotations/ http://plexus.codehaus.org/plexus-utils http://plexus.codehaus.org/plexus-classworlds/".split(" "))));
        assertEquals("http://brooklyn.incubator.apache.org/", GenerateNoticesMojo.longestRelevantUrl("org.apache.brooklyn", 
            Arrays.asList("http://brooklyn.incubator.apache.org/", "http://brooklyn.incubator.apache.org/foo/bar/")));
    }
    
}
