package org.apache.maven.extensions.caching;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.extensions.caching.jaxb.CompletedExecutionType;
import org.apache.maven.extensions.caching.jaxb.TrackedPropertyType;
import org.apache.maven.extensions.caching.xml.BuildInfo;
import org.apache.maven.extensions.caching.xml.CacheConfig;
import org.apache.maven.extensions.caching.xml.CacheState;
import org.apache.maven.extensions.caching.xml.DtoUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionStrategy;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginContainerException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.ReflectionUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.maven.extensions.caching.ProjectUtils.isLaterPhase;
import static org.apache.maven.extensions.caching.ProjectUtils.mojoExecutionKey;
import static org.apache.maven.extensions.caching.checksum.KeyUtils.getVersionlessProjectKey;
import static org.apache.maven.extensions.caching.xml.CacheState.DISABLED;
import static org.apache.maven.extensions.caching.xml.CacheState.INITIALIZED;

/**
 * CachingMojoExecutionStrategy
 */
@Component( role = MojoExecutionStrategy.class )
public class CachingMojoExecutionStrategy implements MojoExecutionStrategy
{
    @Requirement
    private Logger logger;

    @Requirement
    private CacheController cacheController;

    @Requirement
    private CacheConfig cacheConfig;

    @Requirement
    private MavenPluginManager mavenPluginManager;

    @Requirement( role = MojoExecutionListener.class, hint = "MojoParametersListener" )
    private MojoParametersListener mojoListener;

    @Override
    public void execute( MavenSession session, List<MojoExecution> mojoExecutions, Executor executor )
            throws LifecycleExecutionException
    {
        final MavenProject project = session.getCurrentProject();
        final MojoExecution.Source source = getSource( mojoExecutions );

        // execute clean bound goals before restoring to not interfere/slowdown clean
        CacheState cacheState = DISABLED;
        CacheResult result = CacheResult.empty();
        if ( source == MojoExecution.Source.LIFECYCLE )
        {
            List<MojoExecution> cleanPhase = getCleanPhase( mojoExecutions );
            for ( MojoExecution mojoExecution : cleanPhase )
            {
                executor.execute( mojoExecution );
            }
            cacheState = cacheConfig.initialize( project, session );
            if ( cacheState == INITIALIZED )
            {
                result = cacheController.findCachedBuild( session, project, mojoExecutions );
            }
        }

        boolean restorable = result.isSuccess() || result.isPartialSuccess();
        boolean restored = result.isSuccess(); // if partially restored need to save increment
        if ( restorable )
        {
            restored &= restoreProject( result, mojoExecutions, executor );
        }
        else
        {
            for ( MojoExecution mojoExecution : mojoExecutions )
            {
                if ( source == MojoExecution.Source.CLI
                        || isLaterPhase( mojoExecution.getLifecyclePhase(), "post-clean" ) )
                {
                    executor.execute( mojoExecution );
                }
            }
        }

        if ( cacheState == INITIALIZED && ( !restorable || !restored ) )
        {
            final Map<String, MojoExecutionEvent> executionEvents = mojoListener.getProjectExecutions( project );
            cacheController.save( result, mojoExecutions, executionEvents );
        }

        if ( cacheConfig.isFailFast() && !result.isSuccess() )
        {
            throw new LifecycleExecutionException(
                    "Failed to restore project[" + getVersionlessProjectKey( project ) + "] from cache, failing build.",
                    project );
        }
    }

    private MojoExecution.Source getSource( List<MojoExecution> mojoExecutions )
    {
        if ( mojoExecutions == null || mojoExecutions.isEmpty() )
        {
            return null;
        }
        for ( MojoExecution mojoExecution : mojoExecutions )
        {
            if ( mojoExecution.getSource() == MojoExecution.Source.CLI )
            {
                return MojoExecution.Source.CLI;
            }
        }
        return MojoExecution.Source.LIFECYCLE;
    }

    private List<MojoExecution> getCleanPhase( List<MojoExecution> mojoExecutions )
    {
        List<MojoExecution> list = new ArrayList<>();
        for ( MojoExecution mojoExecution : mojoExecutions )
        {
            if ( isLaterPhase( mojoExecution.getLifecyclePhase(), "post-clean" ) )
            {
                break;
            }
            list.add( mojoExecution );
        }
        return list;
    }

    private boolean restoreProject( CacheResult cacheResult,
                                    List<MojoExecution> mojoExecutions,
                                    Executor executor ) throws LifecycleExecutionException
    {

        final BuildInfo buildInfo = cacheResult.getBuildInfo();
        final MavenProject project = cacheResult.getContext().getProject();
        final MavenSession session = cacheResult.getContext().getSession();
        final List<MojoExecution> cachedSegment = buildInfo.getCachedSegment( mojoExecutions );

        boolean restored = cacheController.restoreProjectArtifacts( cacheResult );
        if ( !restored )
        {
            logger.info(
                    "[CACHE][" + project.getArtifactId()
                            + "] Cannot restore project artifacts, continuing with non cached build" );
            return false;
        }

        for ( MojoExecution cacheCandidate : cachedSegment )
        {

            if ( cacheController.isForcedExecution( project, cacheCandidate ) )
            {
                logger.info(
                        "[CACHE][" + project.getArtifactId() + "] Mojo execution is forced by project property: "
                                + cacheCandidate.getMojoDescriptor().getFullGoalName() );
                executor.execute( cacheCandidate );
            }
            else
            {
                restored = verifyCacheConsistency( cacheCandidate, buildInfo, project, session );
                if ( !restored )
                {
                    break;
                }
            }
        }

        if ( !restored )
        {
            // cleanup partial state
            project.getArtifact().setFile( (File) null );
            project.getArtifact().setResolved( false );
            mojoListener.remove( project );
            // build as usual
            for ( MojoExecution mojoExecution : cachedSegment )
            {
                executor.execute( mojoExecution );
            }
        }

        for ( MojoExecution mojoExecution : buildInfo.getPostCachedSegment( mojoExecutions ) )
        {
            executor.execute( mojoExecution );
        }
        return restored;
    }

    private boolean verifyCacheConsistency( MojoExecution cacheCandidate,
                                            BuildInfo buildInfo,
                                            MavenProject project,
                                            MavenSession session ) throws LifecycleExecutionException
    {

        long createdTimestamp = System.currentTimeMillis();
        boolean consistent = true;

        if ( !cacheConfig.getTrackedProperties( cacheCandidate ).isEmpty() )
        {
            Mojo mojo = null;
            try
            {
                mojo = mavenPluginManager.getConfiguredMojo( Mojo.class, session, cacheCandidate );
                final CompletedExecutionType completedExecution = buildInfo.findMojoExecutionInfo( cacheCandidate );
                final String fullGoalName = cacheCandidate.getMojoDescriptor().getFullGoalName();

                if ( completedExecution != null
                        && !isParamsMatched( project, cacheCandidate, mojo, completedExecution ) )
                {
                    logInfo( project,
                            "Mojo cached parameters mismatch with actual, forcing full project build. Mojo: "
                                    + fullGoalName );
                    consistent = false;
                }

                if ( consistent )
                {
                    long elapsed = System.currentTimeMillis() - createdTimestamp;
                    logInfo( project, "Skipping plugin execution (reconciled in "
                            + elapsed + " millis): " + fullGoalName );
                }

                if ( logger.isDebugEnabled() )
                {
                    logger.debug(
                            "[CACHE][" + project.getArtifactId() + "] Checked "
                                    + fullGoalName + ", resolved mojo: " + mojo
                                    + ", cached params:" + completedExecution );
                }
            }
            catch ( PluginContainerException | PluginConfigurationException e )
            {
                throw new LifecycleExecutionException( "Cannot get configured mojo", e );
            }
            finally
            {
                if ( mojo != null )
                {
                    mavenPluginManager.releaseMojo( mojo, cacheCandidate );
                }
            }
        }
        else
        {
            logger.info(
                    "[CACHE][" + project.getArtifactId() + "] Skipping plugin execution (cached): "
                            + cacheCandidate.getMojoDescriptor().getFullGoalName() );
        }

        return consistent;
    }

    private boolean isParamsMatched( MavenProject project,
                                     MojoExecution mojoExecution,
                                     Mojo mojo,
                                     CompletedExecutionType completedExecution )
    {

        List<TrackedPropertyType> tracked = cacheConfig.getTrackedProperties( mojoExecution );

        for ( TrackedPropertyType trackedProperty : tracked )
        {
            final String propertyName = trackedProperty.getPropertyName();

            String expectedValue = DtoUtils.findPropertyValue( propertyName, completedExecution );
            if ( expectedValue == null && trackedProperty.isSetDefaultValue() )
            {
                expectedValue = trackedProperty.getDefaultValue();
            }

            final String currentValue;
            try
            {
                currentValue = String.valueOf( ReflectionUtils.getValueIncludingSuperclasses( propertyName, mojo ) );
            }
            catch ( IllegalAccessException e )
            {
                logError( project, "Cannot extract plugin property " + propertyName + " from mojo " + mojo, e );
                return false;
            }

            if ( !StringUtils.equals( currentValue, expectedValue ) )
            {
                if ( !StringUtils.equals( currentValue, trackedProperty.getSkipValue() ) )
                {
                    logInfo( project,
                            "Plugin parameter mismatch found. Parameter: " + propertyName + ", expected: "
                                    + expectedValue + ", actual: " + currentValue );
                    return false;
                }
                else
                {
                    logWarn( project,
                            "Cache contains plugin execution with skip flag and might be incomplete. Property: "
                                    + propertyName + ", execution: " + mojoExecutionKey( mojoExecution ) );
                }
            }
        }
        return true;
    }

    private void logInfo( MavenProject project, String message )
    {
        logger.info( "[CACHE][" + project.getArtifactId() + "] " + message );
    }

    private void logError( MavenProject project, String message, Exception e )
    {
        logger.error( "[CACHE][" + project.getArtifactId() + "] " + message, e );
    }

    private void logWarn( MavenProject project, String message )
    {
        logger.warn( "[CACHE][" + project.getArtifactId() + "] " + message );
    }
}
