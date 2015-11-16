package org.heneveld.maven.license_audit.util;

import org.apache.maven.project.MavenProject;

public class MavenUtil {

    /** If the URL is not declared on the project's original model, 
     * take the parent's declared URL; NOT the inference done by maven
     * (which appends the child artifactId, often wrongly.
     */
    public static String getDeclaredUrl(MavenProject p) {
        String result = p.getUrl();
        if (result==null) return null;
        if (p.getOriginalModel()==null || p.getOriginalModel().getUrl()!=null) return result;
        // if url is inferred, take the parent's url instead
        MavenProject pp = p.getParent();
        if (pp==null) return result;
        return getDeclaredUrl(pp);
    }
    
}
