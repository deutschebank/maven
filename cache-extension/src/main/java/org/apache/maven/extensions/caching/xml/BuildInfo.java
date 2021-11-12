package org.apache.maven.extensions.caching.xml;

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

import com.google.common.collect.Iterables;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.extensions.caching.ProjectUtils;
import org.apache.maven.extensions.caching.checksum.MavenProjectInput;
import org.apache.maven.extensions.caching.hash.HashAlgorithm;
import org.apache.maven.extensions.caching.jaxb.ArtifactType;
import org.apache.maven.extensions.caching.jaxb.BuildInfoType;
import org.apache.maven.extensions.caching.jaxb.CompletedExecutionType;
import org.apache.maven.extensions.caching.jaxb.DigestItemType;
import org.apache.maven.extensions.caching.jaxb.ProjectsInputInfoType;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.logging.Logger;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.apache.maven.extensions.caching.ProjectUtils.isLaterPhase;
import static org.apache.maven.extensions.caching.ProjectUtils.mojoExecutionKey;

/**
 * BuildInfo
 */
public class BuildInfo
{

    final BuildInfoType dto;
    CacheSource source;
    volatile Map<String, CompletedExecutionType> execMap;

    public BuildInfo( List<String> goals,
                      ArtifactType artifact,
                      List<ArtifactType> attachedArtifacts,
                      ProjectsInputInfoType projectsInputInfo,
                      List<CompletedExecutionType> completedExecutions,
                      String hashAlgorithm )
    {
        this.dto = new BuildInfoType();
        this.dto.setCacheImplementationVersion( MavenProjectInput.CACHE_IMPLEMENTATION_VERSION);
        try
        {
            this.dto.setBuildTime( DatatypeFactory.newInstance().newXMLGregorianCalendar( new GregorianCalendar() ) );
        }
        catch ( DatatypeConfigurationException ignore )
        {
        }
        try
        {
            this.dto.setBuildServer( InetAddress.getLocalHost().getCanonicalHostName() );
        }
        catch ( UnknownHostException ignore )
        {
            this.dto.setBuildServer( "unknown" );
        }
        this.dto.setHashFunction( hashAlgorithm );
        this.dto.setArtifact( artifact );
        this.dto.setGoals( createGoals( goals ) );
        this.dto.setAttachedArtifacts( new BuildInfoType.AttachedArtifacts() );
        this.dto.getAttachedArtifacts().getArtifact().addAll( attachedArtifacts );
        this.dto.setExecutions( createExecutions( completedExecutions ) );
        this.dto.setProjectsInputInfo( projectsInputInfo );
        this.source = CacheSource.BUILD;
    }

    public CacheSource getSource()
    {
        return source;
    }

    private BuildInfoType.Executions createExecutions( List<CompletedExecutionType> completedExecutions )
    {
        BuildInfoType.Executions executions = new BuildInfoType.Executions();
        executions.getExecution().addAll( completedExecutions );
        return executions;
    }

    public BuildInfo( BuildInfoType buildInfo, CacheSource source )
    {
        this.dto = buildInfo;
        this.source = source;
    }

    public static BuildInfoType.Goals createGoals( List<String> list )
    {
        BuildInfoType.Goals goals = new BuildInfoType.Goals();
        goals.getGoal().addAll( list );
        return goals;
    }

    public static BuildInfoType.AttachedArtifacts createAttachedArtifacts( List<Artifact> artifacts,
                                                                           HashAlgorithm algorithm ) throws IOException
    {
        BuildInfoType.AttachedArtifacts attachedArtifacts = new BuildInfoType.AttachedArtifacts();
        for ( Artifact artifact : artifacts )
        {
            final ArtifactType dto = DtoUtils.createDto( artifact );
            if ( artifact.getFile() != null )
            {
                dto.setFileHash( algorithm.hash( artifact.getFile().toPath() ) );
            }
            attachedArtifacts.getArtifact().add( dto );
        }
        return attachedArtifacts;
    }

    public boolean isAllExecutionsPresent( List<MojoExecution> mojos, Logger logger )
    {
        for ( MojoExecution mojo : mojos )
        {
            // TODO for strict check we might want exact match
            if ( !hasCompletedExecution( mojoExecutionKey( mojo ) ) )
            {
                logger.error( "Build mojo is not cached: " + mojo );
                return false;
            }
        }
        return true;
    }

    private boolean hasCompletedExecution( String mojoExecutionKey )
    {
        return getExecMap().containsKey( mojoExecutionKey );
    }

    @Override
    public String toString()
    {
        return "BuildInfo{" + "dto=" + dto + '}';
    }

    public CompletedExecutionType findMojoExecutionInfo( MojoExecution mojoExecution )
    {
        return getExecMap().get( mojoExecutionKey( mojoExecution ) );
    }

    private Map<String, CompletedExecutionType> getExecMap()
    {
        if ( execMap != null )
        {
            return execMap;
        }
        if ( !dto.isSetExecutions() )
        {
            execMap = Collections.emptyMap();
            return execMap;
        }
        execMap = dto.getExecutions().getExecution().stream()
                .collect(
                        Collectors.toMap(
                                CompletedExecutionType::getExecutionKey,
                                v -> v
                        )
                );
        return execMap;
    }

    public String getCacheImplementationVersion()
    {
        return dto.getCacheImplementationVersion();
    }

    public ArtifactType getArtifact()
    {
        return dto.getArtifact();
    }

    public List<ArtifactType> getAttachedArtifacts()
    {
        if ( dto.isSetAttachedArtifacts() )
        {
            return dto.getAttachedArtifacts().getArtifact();
        }
        return Collections.emptyList();
    }

    public BuildInfoType getDto()
    {
        return dto;
    }

    public String getHighestCompletedGoal()
    {
        return Iterables.getLast( dto.getGoals().getGoal() );
    }

    public List<MojoExecution> getCachedSegment( List<MojoExecution> mojoExecutions )
    {
        List<MojoExecution> list = new ArrayList<>();
        for ( MojoExecution mojoExecution : mojoExecutions )
        {
            if ( !isLaterPhase( mojoExecution.getLifecyclePhase(), "post-clean" ) )
            {
                continue;
            }
            if ( isLaterPhase( mojoExecution.getLifecyclePhase(), getHighestCompletedGoal() ) )
            {
                break;
            }
            list.add( mojoExecution );
        }
        return list;

    }

    public List<MojoExecution> getPostCachedSegment( List<MojoExecution> mojoExecutions )
    {
        List<MojoExecution> list = new ArrayList<>();
        for ( MojoExecution mojoExecution : mojoExecutions )
        {
            if ( isLaterPhase( mojoExecution.getLifecyclePhase(), getHighestCompletedGoal() ) )
            {
                list.add( mojoExecution );
            }
        }
        return list;
    }

    public DigestItemType findArtifact( Dependency dependency )
    {

        if ( ProjectUtils.isPom( dependency ) )
        {
            throw new IllegalArgumentException( "Pom dependencies should not be treated as artifacts: " + dependency );
        }
        List<ArtifactType> artifacts = new ArrayList<>( getAttachedArtifacts() );
        artifacts.add( getArtifact() );
        for ( ArtifactType artifact : artifacts )
        {
            if ( isEquals( dependency, artifact ) )
            {
                return DtoUtils.createdDigestedByProjectChecksum( artifact, dto.getProjectsInputInfo().getChecksum() );
            }
        }
        return null;
    }

    private boolean isEquals( Dependency dependency, ArtifactType artifact )
    {
        return Objects.equals( dependency.getGroupId(), artifact.getArtifactId() ) && Objects.equals(
                dependency.getArtifactId(), artifact.getArtifactId() ) && Objects.equals( dependency.getType(),
                artifact.getType() ) && Objects.equals( dependency.getClassifier(), artifact.getClassifier() );
    }
}
