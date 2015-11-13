package org.heneveld.maven.license_audit;

import java.io.File;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingResult;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

public class BetterAbstractMojoTestCaseTest extends BetterAbstractMojoTestCase {

    public void testSensibleSession() throws Exception {
        testSensibleSession(newMavenSession());
    }

    public void testSensibleSession(MavenSession s) throws Exception {
        MavenSession ms = newMavenSession();  //((LicenseAuditMojo)mojo).mavenSession;
        assertNotNull( ms );
        assertNotNull( ms.getLocalRepository().getBasedir() );
        System.out.println("Basedir: "+ms.getLocalRepository().getBasedir());
        // The following artifacts could not be resolved: org.apache.maven:maven-core:jar:2.0.7

        RepositorySystem rs = lookup(RepositorySystem.class);
        assertNotNull( rs );

        // check we can access stuff in remote repos
        // (fails if we don't have proper aether connectors + transforms,
        // or if we've not "populated" with default remote repos)
        ArtifactResult art;
        art = resolveArtifact(rs, ms, "org.apache.maven:maven-core:jar:2.0.7");
        System.out.println(art);
        assertNotNull(art.getArtifact());

        // check we avoid this:
        // [ERROR] Failed to determine Java version for profile java-1.5-detected @ org.apache.commons:commons-parent:22, /Users/alex/.m2/repository/org/apache/commons/commons-parent/22/commons-parent-22.pom, line 909, column 14
        art = resolveArtifact(rs, ms, "org.apache.commons:commons-lang3:3.1");
        System.out.println(art);
        assertNotNull(art.getArtifact());
        
        ProjectBuilder projectBuilder = lookup(ProjectBuilder.class);
        ProjectBuildingResult res = projectBuilder.build(RepositoryUtils.toArtifact(art.getArtifact()), true, ms.getProjectBuildingRequest());
        System.out.println(res);
        if (!res.getProblems().isEmpty()) fail("Problems: "+res.getProblems());
    }

    static ArtifactResult resolveArtifact(RepositorySystem rs, MavenSession ms, String coords) throws ArtifactResolutionException {
        return rs.resolveArtifact(
            ms.getRepositorySession(),
            new ArtifactRequest(new DefaultArtifact(coords), 
                RepositoryUtils.toRepos(ms.getRequest().getRemoteRepositories()), 
                null));
    }

    public void testLoadMojoAndSensibleSession() throws Exception {
        File f = getTestFile( "src/test/resources/org/heneveld/maven/license_audit/poms_to_test_mojo/simple_pom/pom.xml" );
        Mojo mojo = lookupConfiguredMojo(f, "report");
        assertNotNull( mojo );
        assertTrue("Wrong class: "+mojo, mojo instanceof LicenseAuditMojo);
    }

}