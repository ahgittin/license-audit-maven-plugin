package org.heneveld.maven.license_audit;

import org.apache.maven.project.MavenProject;
import org.eclipse.aether.graph.DependencyNode;

class Coords {
    public final String groupId, artifactId, version, baseVersion, packagingExtensionType, classifier;

    public Coords(String groupId, String artifactId, String version, String baseVersion, String packagingExtensionType, String classifier) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.baseVersion = baseVersion;
        this.packagingExtensionType = packagingExtensionType;
        this.classifier = classifier;
    }
    
    public static Coords of(MavenProject x) {
        // prefer artifact coords as that will be canonical, but it might not be available, e.g. for root project
        if (x.getArtifact()!=null) return of(x.getArtifact());
        return new Coords(x.getGroupId(), x.getArtifactId(), x.getVersion(), x.getVersion(), "", "");
    }

    public static Coords of(org.apache.maven.model.Dependency x) {
        return new Coords(x.getGroupId(), x.getArtifactId(), x.getVersion(), x.getVersion(), x.getType(), x.getClassifier());
    }
    public static Coords of(org.apache.maven.artifact.Artifact x) {
        return new Coords(x.getGroupId(), x.getArtifactId(), x.getVersion(), x.getBaseVersion(), x.getType(), x.getClassifier());
    }
    public static Coords of(DependencyNode x) {
        return new Coords(x.getArtifact().getGroupId(), x.getArtifact().getArtifactId(), x.getArtifact().getVersion(), 
            x.getArtifact().getBaseVersion(), x.getArtifact().getExtension(), x.getArtifact().getClassifier());
    }
    
    public String baseArtifact() {
        return toString(true, true, false);
    }
    
    public String realArtifact() {
        return toString(true, true, true);
    }
    
    public String unversionedArtifact() {
        return toString(false, true, false);
    }
    
    public String normal() {
        return toString(true, false, false);
    }
    
    public String unversioned() {
        return toString(false, false, false);
    }
    
    private String toString(boolean includeVersion, boolean includeTypeAndClassifier, boolean useRealVersion) {
        StringBuilder result = new StringBuilder();
        result.append(groupId);
        result.append(":");
        result.append(artifactId);
        if (includeTypeAndClassifier) {
            result.append(":");
            if (isNonEmpty(packagingExtensionType)) result.append(packagingExtensionType);
            else result.append("jar");
            if (isNonEmpty(classifier)) {
                result.append(":");
                result.append(classifier);
            }
        }
        if (includeVersion) {
            result.append(":");
            if (useRealVersion || !isNonEmpty(baseVersion))
                result.append(version);
            else 
                result.append(baseVersion);
        }
        return result.toString();
    }
    
    protected static boolean isNonEmpty(String s) {
        return (s!=null && s.length()>0);
    }
}

