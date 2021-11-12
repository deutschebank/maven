package org.apache.maven.extensions.caching;

import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;

/**
 * Calculate effective raw model for project
 * The idea is to have model where not all properties resolved
 * In particular ${project....} and ${pom....} left as is
 * This helps to calculate correct checksum when changed only project version
 * for example build 1 : 1.0-SNAPSHOT, build 2 : 2.0-SNAPSHOT
 * in this case 2nd build could be completely restored from cached
 */
@Component( role = RawModelProvider.class )
public interface RawModelProvider {

    /**
     *
     * @param project - the project which model will be calculated for
     * @return effective raw model for project
     */
    Model effectiveModel(MavenProject project );

}
