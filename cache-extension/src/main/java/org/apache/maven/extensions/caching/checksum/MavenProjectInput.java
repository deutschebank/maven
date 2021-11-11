package org.apache.maven.extensions.caching.checksum;

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


import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.extensions.caching.CacheItemProvider;
import org.apache.maven.extensions.caching.Clock;
import org.apache.maven.extensions.caching.LocalArtifactsRepository;
import org.apache.maven.extensions.caching.PluginScanConfig;
import org.apache.maven.extensions.caching.ProjectUtils;
import org.apache.maven.extensions.caching.RemoteArtifactsRepository;
import org.apache.maven.extensions.caching.ScanConfigProperties;
import org.apache.maven.extensions.caching.hash.HashAlgorithm;
import org.apache.maven.extensions.caching.hash.HashChecksum;
import org.apache.maven.extensions.caching.hash.HashFactory;
import org.apache.maven.extensions.caching.jaxb.DigestItemType;
import org.apache.maven.extensions.caching.jaxb.ProjectsInputInfoType;
import org.apache.maven.extensions.caching.xml.BuildInfo;
import org.apache.maven.extensions.caching.xml.CacheConfig;
import org.apache.maven.extensions.caching.xml.DtoUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.builder.BuilderCommon;
import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Resource;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.contains;
import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;
import static org.apache.commons.lang3.StringUtils.equalsAnyIgnoreCase;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.replaceEachRepeatedly;
import static org.apache.commons.lang3.StringUtils.startsWithAny;
import static org.apache.commons.lang3.StringUtils.stripToEmpty;
import static org.apache.maven.extensions.caching.ProjectUtils.isPomPackaging;
import static org.apache.maven.extensions.caching.ProjectUtils.isSnapshot;
import static org.apache.maven.extensions.caching.jaxb.PathSetType.Include;

/**
 * MavenProjectInput
 */
public class MavenProjectInput
{

    /**
     * Version ov hashing algorithm implementation. It is recommended to change to simplify remote cache maintenance
     */
    public static final String CACHE_IMPLEMENTATION_VERSION = "v20-SNAPSHOT";

    /**
     * property name to pass glob value. The glob to be used to list directory files in plugins scanning
     */
    private static final String CACHE_INPUT_GLOB_NAME = "remote.cache.input.glob";
    /**
     * default glob, bbsdk/abfx specific
     */
    public static final String DEFAULT_GLOB = "{*.java,*.groovy,*.yaml,*.svcd,*.proto,*assembly.xml,assembly"
            + "*.xml,*logback.xml,*.vm,*.ini,*.jks,*.properties,*.sh,*.bat}";
    /**
     * property name prefix to pass input files with project properties. smth like remote.cache.input.1 will be
     * accepted
     */
    private static final String CACHE_INPUT_NAME = "remote.cache.input";
    /**
     * property name prefix to exclude files from input. smth like remote.cache.exclude.1 should be set in project
     * props
     */
    private static final String CACHE_EXCLUDE_NAME = "remote.cache.exclude";
    /**
     * Flag to control if we should check values from plugin configs as file system objects
     */
    private static final String CACHE_PROCESS_PLUGINS = "remote.cache.processPlugins";
    private final Logger logger;
    private final MavenProject project;
    private final MavenSession session;
    private final LocalArtifactsRepository localCache;
    private final RemoteArtifactsRepository remoteCache;
    private final RepositorySystem repoSystem;
    private final ArtifactHandlerManager artifactHandlerManager;
    private final CacheConfig config;
    private final ConcurrentMap<String, DigestItemType> projectArtifactsByKey;
    private final PathIgnoringCaseComparator fileComparator;
    private final DependencyComparator dependencyComparator;
    private final List<Path> filteredOutPaths;
    private final Path baseDirPath;
    private final String dirGlob;
    private final boolean processPlugins;
    private final Map<String, MavenProject> projectIndex;
    private final CacheItemProvider cacheItemProvider;

    @SuppressWarnings( "checkstyle:parameternumber" )
    public MavenProjectInput( MavenProject project,
                              MavenSession session,
                              Map<String, MavenProject> projectIndex,
                              CacheConfig config,
                              ConcurrentMap<String, DigestItemType> artifactsByKey,
                              RepositorySystem repoSystem,
                              ArtifactHandlerManager artifactHandlerManager,
                              Logger logger,
                              LocalArtifactsRepository localCache,
                              RemoteArtifactsRepository remoteCache,
                              CacheItemProvider cacheItemProvider )
    {
        this.project = project;
        this.session = session;
        this.config = config;
        this.projectIndex = projectIndex;
        this.projectArtifactsByKey = artifactsByKey;
        this.baseDirPath = project.getBasedir().toPath().toAbsolutePath();
        this.repoSystem = repoSystem;
        this.artifactHandlerManager = artifactHandlerManager;
        this.logger = logger;
        this.localCache = localCache;
        this.remoteCache = remoteCache;
        Properties properties = project.getProperties();
        this.dirGlob = properties.getProperty( CACHE_INPUT_GLOB_NAME, config.getDefaultGlob() );
        this.processPlugins = Boolean.parseBoolean(
                properties.getProperty( CACHE_PROCESS_PLUGINS, config.isProcessPlugins() ) );

        Build build = project.getBuild();
        filteredOutPaths = new ArrayList<>( Arrays.asList( normalizedPath( build.getDirectory() ), // target by default
                normalizedPath( build.getOutputDirectory() ), normalizedPath( build.getTestOutputDirectory() ) ) );

        for ( String excludePath : config.getGlobalExcludePaths() )
        {
            filteredOutPaths.add( Paths.get( excludePath ) );
        }

        for ( String propertyName : properties.stringPropertyNames() )
        {
            if ( propertyName.startsWith( CACHE_EXCLUDE_NAME ) )
            {
                filteredOutPaths.add( Paths.get( properties.getProperty( propertyName ) ) );
            }
        }

        this.fileComparator = new PathIgnoringCaseComparator();
        this.dependencyComparator = new DependencyComparator();
        this.cacheItemProvider = cacheItemProvider;
    }

    public ProjectsInputInfoType calculateChecksum( HashFactory hashFactory ) throws IOException
    {
        long time = Clock.time();

        final String effectivePom = getEffectivePom();
        final SortedSet<Path> inputFiles = isPomPackaging( project ) ? Collections.emptySortedSet() : getInputFiles();
        final SortedMap<String, DigestItemType> dependenciesChecksum = getMutableDependencies();

        final long inputTime = Clock.elapsed( time );
        time = Clock.time();

        // hash items: effective pom + input files + dependencies
        final int count = 1 + inputFiles.size() + dependenciesChecksum.size();
        final List<DigestItemType> items = new ArrayList<>( count );
        final HashChecksum checksum = hashFactory.createChecksum( count );

        Optional<ProjectsInputInfoType> baselineHolder = Optional.absent();
        if ( config.isBaselineDiffEnabled() )
        {
            baselineHolder =
                    remoteCache.findBaselineBuild( project ).transform( b -> b.getDto().getProjectsInputInfo() );
        }

        DigestItemType effectivePomChecksum = DigestUtils.pom( checksum, effectivePom );
        items.add( effectivePomChecksum );
        final boolean compareWithBaseline = config.isBaselineDiffEnabled() && baselineHolder.isPresent();
        if ( compareWithBaseline )
        {
            checkEffectivePomMatch( baselineHolder.get(), effectivePomChecksum );
        }

        boolean sourcesMatched = true;
        for ( Path file : inputFiles )
        {
            DigestItemType fileDigest = DigestUtils.file( checksum, baseDirPath, file );
            items.add( fileDigest );
            if ( compareWithBaseline )
            {
                sourcesMatched &= checkItemMatchesBaseline( baselineHolder.get(), fileDigest );
            }
        }
        if ( compareWithBaseline )
        {
            logInfo( "Source code: " + ( sourcesMatched ? "MATCHED" : "OUT OF DATE" ) );
        }

        boolean dependenciesMatched = true;
        for ( Map.Entry<String, DigestItemType> entry : dependenciesChecksum.entrySet() )
        {
            DigestItemType dependencyDigest =
                    DigestUtils.dependency( checksum, entry.getKey(), entry.getValue().getHash() );
            items.add( dependencyDigest );
            if ( compareWithBaseline )
            {
                dependenciesMatched &= checkItemMatchesBaseline( baselineHolder.get(), dependencyDigest );
            }
        }

        if ( compareWithBaseline )
        {
            logInfo( "Dependencies: " + ( dependenciesMatched ? "MATCHED" : "OUT OF DATE" ) );
        }

        final ProjectsInputInfoType projectsInputInfoType = new ProjectsInputInfoType();
        projectsInputInfoType.setChecksum( checksum.digest() );
        projectsInputInfoType.getItem().addAll( items );

        final long checksumTime = Clock.elapsed( time );

        if ( logger.isDebugEnabled() )
        {
            for ( DigestItemType item : projectsInputInfoType.getItem() )
            {
                logger.debug( "Hash calculated, item: " + item.getType() + ", hash: " + item.getHash() );
            }
        }
        logInfo(
                "Project inputs calculated in " + inputTime + " ms. " + hashFactory.getAlgorithm()
                        + " checksum [" + projectsInputInfoType.getChecksum() + "] calculated in "
                        + checksumTime + " ms." );
        return projectsInputInfoType;
    }

    private void checkEffectivePomMatch( ProjectsInputInfoType baselineBuild, DigestItemType effectivePomChecksum )
    {
        Optional<DigestItemType> pomHolder = Optional.absent();
        for ( DigestItemType it : baselineBuild.getItem() )
        {
            if ( it.getType().equals( "pom" ) )
            {
                pomHolder = Optional.of( it );
                break;
            }
        }

        if ( pomHolder.isPresent() )
        {
            DigestItemType pomItem = pomHolder.get();
            final boolean matches = StringUtils.equals( pomItem.getHash(), effectivePomChecksum.getHash() );
            if ( !matches )
            {
                logInfo(
                        "Mismatch in effective poms. Current: " + effectivePomChecksum.getHash() + ", remote: "
                                + pomItem.getHash() );
            }
            logInfo( "Effective pom: " + ( matches ? "MATCHED" : "OUT OF DATE" ) );
        }
    }

    private boolean checkItemMatchesBaseline( ProjectsInputInfoType baselineBuild, DigestItemType fileDigest )
    {
        Optional<DigestItemType> baselineFileDigest = Optional.absent();
        for ( DigestItemType it : baselineBuild.getItem() )
        {
            if ( it.getType().equals( fileDigest.getType() )
                    && fileDigest.getValue().equals( it.getValue().trim() ) )
            {
                baselineFileDigest = Optional.of( it );
                break;
            }
        }

        boolean matched = false;
        if ( baselineFileDigest.isPresent() )
        {
            String hash = baselineFileDigest.get().getHash();
            matched = StringUtils.equals( hash, fileDigest.getHash() );
            if ( !matched )
            {
                logInfo(
                        "Mismatch in " + fileDigest.getType() + ": " + fileDigest.getValue() + ". Local hash: "
                                + fileDigest.getHash() + ", remote: " + hash );
            }
        }
        else
        {
            logInfo(
                    "Mismatch in " + fileDigest.getType() + ": " + fileDigest.getValue()
                            + ". Not found in remote cache" );
        }
        return matched;
    }

    private String getEffectivePom() throws IOException
    {

        Model prototype = this.project.getModel();

        // TODO validate status of the model - it should be in resolved state
        Model toHash = new Model();

        toHash.setGroupId( prototype.getGroupId() );
        toHash.setArtifactId( prototype.getArtifactId() );
        //does not make sense to add project version to calculate hash
        toHash.setVersion( /*prototype.getVersion()*/ "" );
        toHash.setModules( prototype.getModules() );

        List<Dependency> effectiveModelDependencies = prototype.getDependencies();

        List<Dependency> dependencies = normalizeDependencies( effectiveModelDependencies, collectAllRawDependencies() );
        toHash.setDependencies(dependencies);

        Build build = new Build();
        List<Plugin> plugins = prototype.getBuild().getPlugins();
        Map<String, List<Dependency>> rawPluginsDependencies = collectAllRawPluginsDependencies();
        build.setPlugins( normalizePlugins( plugins, rawPluginsDependencies ) );

        //no need to track plugin management section in effective pom as it contributes into plugins section
        //but we need to replace configuration from plugin management section if any for some particular plugin
        if ( isPomPackaging(this.project) )
        {
            PluginManagement pluginManagement = prototype.getBuild().getPluginManagement();
            PluginManagement pm = pluginManagement.clone();
            pm.setPlugins( normalizePlugins( pm.getPlugins(), rawPluginsDependencies ) );
            build.setPluginManagement(pm);
        }

        toHash.setBuild( build );

        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Writer writer = null;
        try
        {
            writer = WriterFactory.newXmlWriter( output );
            new MavenXpp3Writer().write( writer, toHash );

            //normalize env specifics
            final String[] searchList = {baseDirPath.toString(), "\\", "windows", "linux"};
            final String[] replacementList = {"", "/", "os.classifier", "os.classifier"};

            return replaceEachRepeatedly( output.toString(), searchList, replacementList );

        }
        finally
        {
            IOUtil.close( writer );
        }
    }


    private Map<String, Dependency> collectAllRawDependencies()
    {
        Map<String, Dependency> dependencyMap = new HashMap<>();
        MavenProject currentProject = this.project;

        while( currentProject != null )
        {
            Model rawModel = currentProject.getOriginalModel();
            collectRawDependenciesWithVersion( rawModel.getDependencies(), dependencyMap );
            DependencyManagement dependencyManagement = rawModel.getDependencyManagement();
            if ( dependencyManagement != null )
            {
                collectRawDependenciesWithVersion( dependencyManagement.getDependencies(), dependencyMap );
            }

            getActiveProfiles( currentProject, rawModel ).forEach( p -> {
                collectRawDependenciesWithVersion( p.getDependencies(), dependencyMap );
                DependencyManagement dm = p.getDependencyManagement();
                if ( dm != null )
                {
                    collectRawDependenciesWithVersion( dm.getDependencies(), dependencyMap );
                }
            });

            currentProject = currentProject.getParent();
        }
        return dependencyMap;
    }

    private void collectRawDependenciesWithVersion(@Nullable List<Dependency> in, Map<String, Dependency> out)
    {
        if (in == null)
        {
            return;
        }
        in.stream().filter(it -> it.getVersion() != null)
                .forEach(it -> out.putIfAbsent(
                        makeArtifactId(
                                normalizeGroupId(it.getGroupId()),
                                normalizeArtifactId(it.getArtifactId())
                        ), it));
    }

    private Map<String, List<Dependency>> collectAllRawPluginsDependencies()
    {
        MavenProject currentProject = this.project;

        Map<String, List<Dependency>> result = new HashMap<>();
        Model originalModel = currentProject.getOriginalModel();

        getActiveProfiles( currentProject, originalModel ).forEach( p -> collectPluginDependencies( p.getBuild(), result ) );
        collectPluginDependencies( originalModel.getBuild(), result );

        for( ; currentProject != null; currentProject = currentProject.getParent() )
        {
            Model rawModel = currentProject.getOriginalModel();
            Build build = rawModel.getBuild();
            if (build == null)
            {
                continue;
            }
            PluginManagement pluginManagement = build.getPluginManagement();
            if (pluginManagement == null)
            {
                continue;
            }

            Stream<Plugin> pluginManagementStream = getActiveProfiles( currentProject, rawModel)
                    .filter(p -> p.getBuild() != null && p.getBuild().getPluginManagement() != null)
                    .flatMap(p -> p.getBuild().getPluginManagement().getPlugins().stream() );

            Stream.concat( pluginManagement.getPlugins().stream(), pluginManagementStream )
                    .forEach( plugin -> {
                        String groupId = normalizeGroupId(plugin.getGroupId());
                        String artifactId = normalizeArtifactId(plugin.getArtifactId());
                        String key = makeArtifactId(groupId, artifactId);
                        if (!result.getOrDefault(key, Collections.emptyList()).isEmpty() || plugin.getDependencies() == null) {
                            //already have these dependencies
                            return;
                        }
                        result.put(key, plugin.getDependencies());
            } );
        }

        return result;
    }

    private void collectPluginDependencies(BuildBase build, Map<String, List<Dependency>> out )
    {
        if ( build == null )
        {
            return;
        }
        for ( Plugin plugin : build.getPlugins() )
        {
            String groupId = normalizeGroupId( plugin.getGroupId() );
            String artifactId = normalizeArtifactId( plugin.getArtifactId() );
            List<Dependency> dependencies = plugin.getDependencies();
            if( dependencies != null )
            {
                out.put( makeArtifactId(groupId, artifactId), dependencies );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Stream<Profile> getActiveProfiles( MavenProject project, Model model )
    {
        Set<String> activeProfileIds = cacheItemProvider.getCache("activeProfileIds", String.class, Set.class)
                .computeIfAbsent(project.getId(), k -> project.getActiveProfiles().stream()
                        .map(Profile::getId)
                        .collect(Collectors.toSet()));
        List<Profile> profiles = model.getProfiles();
        if ( profiles != null ) {
            return profiles.stream().filter(p -> activeProfileIds.contains(p.getId()));
        }
        return Stream.empty();
    }

    private List<Dependency> normalizeDependencies(List<Dependency> effectiveDependencies,
                                                   Map<String, Dependency> rawDependencies )
    {
        if(effectiveDependencies.isEmpty())
        {
            return effectiveDependencies;
        }
        Map<String, Dependency> dependencyMap = new HashMap<>( effectiveDependencies.size() * 2 );
        effectiveDependencies.forEach( dependency -> dependencyMap.put(dependency.getGroupId() + ":" + dependency.getArtifactId(), dependency));

        rawDependencies.entrySet().stream()
                .filter(it -> isRawProjectPlaceholder(it.getValue().getVersion()))
                .forEach(it -> {
                    Dependency dependency = it.getValue();
                    String groupId = normalizeGroupId( dependency.getGroupId() );
                    String artifactId = normalizeArtifactId( dependency.getArtifactId() );
                    String key = makeArtifactId(groupId, artifactId);
                    Dependency removed = dependencyMap.remove(key);
                    if (removed != null)
                    {
                        Dependency clone = removed.clone();
                        clone.setGroupId(groupId);
                        clone.setVersion(dependency.getVersion().replace("pom.", "project."));
                        dependencyMap.put(key, clone);
                    }
                });
        return dependencyMap.values().stream().sorted(dependencyComparator).collect(Collectors.toList());
    }

    private boolean isRawProjectPlaceholder(String version) {

        if ( isProjectPlaceholder(version) )
        {
            return true;
        }

        return isProjectPlaceholder( resolveRawPropertyValue( version ) );
    }

    private boolean isProjectPlaceholder(@Nullable String propertyName )
    {
        return propertyName != null
                && ( propertyName.startsWith("${project.") || propertyName.startsWith("${pom.") );
    }

    /*
        <abfx-configuration.version>${module-dependencies.version}</abfx-configuration.version>
        <module-dependencies.version>${project.version}</module-dependencies.version>

        this resolve abfx-configuration.version to ${project.version}

     */
    @Nullable
    private String resolveRawPropertyValue(@Nullable String propertyName)
    {
        //Not a placeholder return value as is
        if ( !isPropertyPlaceholder(propertyName) )
        {
            return propertyName;
        }
        String propertyToFind = getPropertyFromPlaceholder(propertyName);
        String property = System.getProperties().getProperty(propertyToFind);
        if (property != null)
        {
            if ( !isPropertyPlaceholder(property) )
            {
                return property;
            }
            propertyToFind = getPropertyFromPlaceholder( propertyName );
        }
        MavenProject currentProject = this.project;
        String currentResult = null;

        while( currentProject != null )
        {
            String projectProperty = currentProject.getOriginalModel().getProperties().getProperty(propertyToFind);
            if (projectProperty != null)
            {
                currentResult = projectProperty;
            }
            if ( isPropertyPlaceholder(projectProperty) )
            {
                //This is a placeholder let's try another attempt to find in this project before we go to parent
                propertyToFind = getPropertyFromPlaceholder( projectProperty );
                continue;
            } else if ( projectProperty != null )
            {
                //This is not a placeholder no need to lookup in parent project
                break;
            }

            currentProject = currentProject.getParent();
        }
       return currentResult;
    }

    private static String getPropertyFromPlaceholder(String propertyName) {
        return propertyName.substring(2, propertyName.length() - 1);
    }

    private static boolean isPropertyPlaceholder(@Nullable String property)
    {
        return property != null && property.startsWith("${") && property.endsWith("}");
    }

    private static String makeArtifactId( String groupId, String artifactId )
    {
        return groupId + ":" + artifactId;
    }

    private String normalizeGroupId( String gId )
    {
        String groupId = gId;
        if(groupId.contains("${project.groupId}") || groupId.contains("${pom.groupId}"))
        {
            groupId = groupId
                    .replace("${project.groupId}", project.getGroupId())
                    .replace("${pom.groupId}", project.getGroupId());
        } else if (groupId.contains("${project.parent.groupId}") || groupId.contains("${pom.parent.groupId}"))
        {
            String parentGroupId = Objects.requireNonNull(
                    project.getParent(),
                    "parent project is null"
            ).getGroupId();
            groupId = groupId
                    .replace("${project.parent.groupId}", parentGroupId)
                    .replace("${pom.parent.groupId}", parentGroupId);
        }
        return groupId;
    }

    private String normalizeArtifactId( String aId )
    {
        String artifactId = aId;
        if(artifactId.contains("${project.artifactId}") || artifactId.contains("${pom.artifactId}"))
        {
            artifactId = artifactId
                    .replace("${project.artifactId}", project.getArtifactId())
                    .replace("${pom.artifactId}", project.getArtifactId());
        } else if (artifactId.contains("${project.parent.artifactId}") || artifactId.contains("${pom.parent.artifactId}"))
        {
            String parentArtifactId = Objects.requireNonNull(
                    project.getParent(),
                    "parent project is null"
            ).getArtifactId();
            artifactId = artifactId
                    .replace("${project.parent.artifactId}", parentArtifactId)
                    .replace("${pom.parent.artifactId}", parentArtifactId);
        }
        return artifactId;
    }

    private SortedSet<Path> getInputFiles()
    {
        long start = System.currentTimeMillis();
        HashSet<WalkKey> visitedDirs = new HashSet<>();
        ArrayList<Path> collectedFiles = new ArrayList<>();

        Build build = project.getBuild();

        final boolean recursive = true;
        startWalk( Paths.get( build.getSourceDirectory() ), dirGlob, recursive, collectedFiles, visitedDirs );
        for ( Resource resource : build.getResources() )
        {
            startWalk( Paths.get( resource.getDirectory() ), dirGlob, recursive, collectedFiles, visitedDirs );
        }

        startWalk( Paths.get( build.getTestSourceDirectory() ), dirGlob, recursive, collectedFiles, visitedDirs );
        for ( Resource testResource : build.getTestResources() )
        {
            startWalk( Paths.get( testResource.getDirectory() ), dirGlob, recursive, collectedFiles, visitedDirs );
        }

        Properties properties = project.getProperties();
        for ( String name : properties.stringPropertyNames() )
        {
            if ( name.startsWith( CACHE_INPUT_NAME ) )
            {
                String path = properties.getProperty( name );
                startWalk( Paths.get( path ), dirGlob, recursive, collectedFiles, visitedDirs );
            }
        }

        List<Include> includes = config.getGlobalIncludePaths();
        for ( Include include : includes )
        {
            final String path = include.getValue();
            final String glob = defaultIfEmpty( include.getGlob(), dirGlob );
            startWalk( Paths.get( path ), glob, include.isRecursive(), collectedFiles, visitedDirs );
        }

        long walkKnownPathsFinished = System.currentTimeMillis() - start;

        String message = processPlugins ? "enabled, values will be checked for presence in file system" : "disabled, "
                + "only tags with attribute " + CACHE_INPUT_NAME + "=\"true\" will be added";
        logInfo( "Scanning plugins configurations to find input files. Probing is " + message );

        if ( processPlugins )
        {
            collectFromPlugins( collectedFiles, visitedDirs );
        }
        else
        {
            logInfo( "Skipping check plugins scan (probing is disabled by config)" );
        }

        long pluginsFinished = System.currentTimeMillis() - start - walkKnownPathsFinished;

        TreeSet<Path> sorted = new TreeSet<>( fileComparator );
        for ( Path collectedFile : collectedFiles )
        {
            sorted.add( collectedFile.normalize().toAbsolutePath() );
        }

        logInfo(
                "Found " + sorted.size() + " input files. Project dir processing: " + walkKnownPathsFinished
                        + ", plugins: " + pluginsFinished + " millis" );
        if ( logger.isDebugEnabled() )
        {
            logDebug( "Src input: " + sorted );
        }

        return sorted;
    }

    /**
     * entry point for directory walk
     */
    private void startWalk( Path candidate,
                            String glob,
                            boolean recursive,
                            List<Path> collectedFiles,
                            Set<WalkKey> visitedDirs )
    {

        Path normalized = candidate.isAbsolute() ? candidate : baseDirPath.resolve( candidate );
        normalized = normalized.toAbsolutePath().normalize();
        WalkKey key = new WalkKey( normalized, glob, recursive );
        if ( visitedDirs.contains( key ) || !Files.exists( normalized ) )
        {
            return;
        }

        if ( Files.isDirectory( normalized ) )
        {
            if ( baseDirPath.startsWith( normalized ) )
            { // requested to walk parent, can do only non recursive
                key = new WalkKey( normalized, glob, false );
            }
            try
            {
                walkDir( key, collectedFiles, visitedDirs );
                visitedDirs.add( key );
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e );
            }
        }
        else
        {
            if ( !isFilteredOutSubpath( normalized ) )
            {
                if ( logger.isDebugEnabled() )
                {
                    logDebug( "Adding: " + normalized );
                }
                collectedFiles.add( normalized );
            }
        }
    }

    private Path normalizedPath( String directory )
    {
        return Paths.get( directory ).normalize();
    }

    private void collectFromPlugins( List<Path> files, HashSet<WalkKey> visitedDirs )
    {

        List<Plugin> plugins = project.getBuild().getPlugins();
        for ( Plugin plugin : plugins )
        {

            PluginScanConfig scanConfig = config.getPluginDirScanConfig( plugin );

            if ( scanConfig.isSkip() )
            {
                logDebug( "Skipping plugin config scan (skip by config): " + plugin.getArtifactId() );
                continue;
            }

            Xpp3Dom configuration = (Xpp3Dom) plugin.getConfiguration();
            logDebug( "Processing plugin config: " + plugin.getArtifactId() );
            if ( configuration != null )
            {
                addInputsFromPluginConfigs( configuration.getChildren(), scanConfig, files, visitedDirs );
            }

            for ( PluginExecution exec : plugin.getExecutions() )
            {

                final PluginScanConfig executionScanConfig = config.getExecutionDirScanConfig( plugin, exec );
                PluginScanConfig mergedConfig = scanConfig.mergeWith( executionScanConfig );

                if ( mergedConfig.isSkip() )
                {
                    logDebug(
                            "Skipping plugin execution config scan (skip by config): "
                                    + plugin.getArtifactId() + ", execId: " + exec.getId() );
                    continue;
                }

                Xpp3Dom execConfiguration = (Xpp3Dom) exec.getConfiguration();
                logDebug( "Processing plugin: " + plugin.getArtifactId() + ", execution: " + exec.getId() );

                if ( execConfiguration != null )
                {
                    addInputsFromPluginConfigs( execConfiguration.getChildren(), mergedConfig, files, visitedDirs );
                }
            }
        }
    }

    private Path walkDir( final WalkKey key,
                          final List<Path> collectedFiles,
                          final Set<WalkKey> visitedDirs ) throws IOException
    {
        return Files.walkFileTree( key.getPath(), new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult preVisitDirectory( Path path,
                                                      BasicFileAttributes basicFileAttributes ) throws IOException
            {
                WalkKey currentDirKey = new WalkKey( path.toAbsolutePath().normalize(), key.getGlob(),
                        key.isRecursive() );
                if ( isHidden( path ) )
                {
                    if ( logger.isDebugEnabled() )
                    {
                        logDebug( "Skipping subtree (hidden): " + path );
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                else if ( isFilteredOutSubpath( path ) )
                {
                    if ( logger.isDebugEnabled() )
                    {
                        logDebug( "Skipping subtree (blacklisted): " + path );
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                else if ( visitedDirs.contains( currentDirKey ) )
                {
                    if ( logger.isDebugEnabled() )
                    {
                        logDebug( "Skipping subtree (visited): " + path );
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }

                walkDirectoryFiles( path, collectedFiles, key.getGlob() );

                if ( !key.isRecursive() )
                {
                    if ( logger.isDebugEnabled() )
                    {
                        logDebug( "Skipping subtree (non recursive): " + path );
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if ( logger.isDebugEnabled() )
                {
                    logDebug( "Visiting subtree: " + path );
                }
                return FileVisitResult.CONTINUE;
            }
        } );
    }

    private void addInputsFromPluginConfigs( Xpp3Dom[] configurationChildren,
                                             PluginScanConfig scanConfig,
                                             List<Path> files, HashSet<WalkKey> visitedDirs )
    {

        if ( configurationChildren == null )
        {
            return;
        }

        for ( Xpp3Dom configChild : configurationChildren )
        {

            String tagValue = configChild.getValue();
            String tagName = configChild.getName();

            if ( !scanConfig.accept( tagName ) )
            {
                logDebug( "Skipping property (scan config)): " + tagName + ", value: " + stripToEmpty( tagValue ) );
                continue;
            }

            logDebug( "Checking xml tag. Tag: " + tagName + ", value: " + stripToEmpty( tagValue ) );

            addInputsFromPluginConfigs( configChild.getChildren(), scanConfig, files, visitedDirs );

            final ScanConfigProperties propertyConfig = scanConfig.getTagScanProperties( tagName );
            final String glob = defaultIfEmpty( propertyConfig.getGlob(), dirGlob );
            if ( "true".equals( configChild.getAttribute( CACHE_INPUT_NAME ) ) )
            {
                logInfo(
                        "Found tag marked with " + CACHE_INPUT_NAME + " attribute. Tag: " + tagName
                                + ", value: " + tagValue );
                startWalk( Paths.get( tagValue ), glob, propertyConfig.isRecursive(), files, visitedDirs );
            }
            else
            {
                final Path candidate = getPathOrNull( tagValue );
                if ( candidate != null )
                {
                    startWalk( candidate, glob, propertyConfig.isRecursive(), files, visitedDirs );
                    if ( "descriptorRef".equals( tagName ) )
                    { // hardcoded logic for assembly plugin which could reference files omitting .xml suffix
                        startWalk( Paths.get( tagValue + ".xml" ), glob, propertyConfig.isRecursive(), files,
                                visitedDirs );
                    }
                }
            }
        }
    }

    private Path getPathOrNull( String text )
    {
        // small optimization to not probe not-paths
        boolean blacklisted = isBlank( text )
                || equalsAnyIgnoreCase( text, "true", "false", "utf-8", "null", "\\" ) // common values
                || contains( text, "*" ) // tag value is a glob or regex - unclear how to process
                || ( contains( text, ":" ) && !contains( text, ":\\" ) )// artifactId
                || startsWithAny( text, "com.", "org.", "io.", "java.", "javax." ) // java packages
                || startsWithAny( text, "${env." ) // env variables in maven notation
                || startsWithAny( text, "http:", "https:", "scm:", "ssh:", "git:", "svn:", "cp:",
                "classpath:" ); // urls identified by common protocols
        if ( !blacklisted )
        {
            try
            {
                return Paths.get( text );
            }
            catch ( Exception ignore )
            {
            }
        }
        if ( logger.isDebugEnabled() )
        {
            logDebug( text + ( blacklisted ? ": skipped(blacklisted literal)" : ": invalid path" ) );
        }
        return null;
    }

    private void logDebug( String message )
    {
        if ( logger.isDebugEnabled() )
        {
            logger.debug( "[CACHE][" + project.getArtifactId() + "] " + message );
        }
    }

    private void logInfo( String message )
    {
        if ( logger.isInfoEnabled() )
        {
            logger.info( "[CACHE][" + project.getArtifactId() + "] " + message );
        }
    }

    void walkDirectoryFiles( Path dir, List<Path> collectedFiles, String glob )
    {
        if ( !Files.isDirectory( dir ) )
        {
            return;
        }

        try
        {
            try ( DirectoryStream<Path> stream = Files.newDirectoryStream( dir, glob ) )
            {
                for ( Path entry : stream )
                {
                    if ( filteredOutPaths.stream().anyMatch( path -> path.getFileName().equals( entry.getFileName() ) ) )
                    {
                        continue;
                    }

                    File file = entry.toFile();
                    if ( file.isFile() && !isHidden( entry ) )
                    {
                        collectedFiles.add( entry );
                    }
                }
            }
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Cannot process directory: " + dir, e );
        }
    }

    private static boolean isHidden( Path entry ) throws IOException
    {
        return Files.isHidden( entry ) || entry.toFile().getName().startsWith( "." );
    }

    private boolean isFilteredOutSubpath( Path path )
    {
        Path normalized = path.normalize();
        for ( Path filteredOutDir : filteredOutPaths )
        {
            if ( normalized.startsWith( filteredOutDir ) )
            {
                return true;
            }
        }
        return false;
    }

    private SortedMap<String, DigestItemType> getMutableDependencies() throws IOException
    {
        MultimoduleDiscoveryStrategy strategy = config.getMultimoduleDiscoveryStrategy();
        SortedMap<String, DigestItemType> result = new TreeMap<>();

        for ( Dependency dependency : project.getDependencies() )
        {
            // saved to index by the end of dependency build
            final boolean currentlyBuilding = isBuilding( dependency );
            final boolean partOfMultiModule = strategy.isPartOfMultiModule( dependency );
            if ( !currentlyBuilding && !partOfMultiModule && !isSnapshot( dependency.getVersion() ) )
            {
                // external immutable dependency, should skip
                continue;
            }

            if ( ProjectUtils.isPom( dependency ) )
            {
                // POM dependency will be resolved by maven system to actual dependencies
                // and will contribute to effective pom.
                // Effective result will be recorded by #getNormalizedPom
                // so pom dependencies must be skipped as meaningless by themselves
                continue;
            }

            final Artifact dependencyArtifact = repoSystem.createDependencyArtifact( dependency );
            final String artifactKey = KeyUtils.getArtifactKey( dependencyArtifact );
            DigestItemType resolved = null;
            if ( currentlyBuilding )
            {
                resolved = projectArtifactsByKey.get( artifactKey );
                if ( resolved == null )
                {
                    throw new DependencyNotResolvedException( "Expected dependency not resolved: " + dependency );
                }
            }
            else
            {
                if ( partOfMultiModule )
                {
                    // TODO lookup in remote cache is not necessary for abfx, for versioned projects - make sense
                    final Optional<BuildInfo> bestMatchResult = localCache.findBestMatchingBuild( session, dependency );
                    if ( bestMatchResult.isPresent() )
                    {
                        final BuildInfo bestMatched = bestMatchResult.get();
                        resolved = bestMatched.findArtifact( dependency );
                    }
                }
                if ( resolved == null && !ProjectUtils.isPom( dependency ) )
                {
                    try
                    {
                        resolved = resolveArtifact( dependencyArtifact, strategy );
                    }
                    catch ( Exception e )
                    {
                        throw new RuntimeException( "Cannot resolve dependency " + dependency, e );
                    }
                }
            }
            result.put( artifactKey, resolved );
        }
        return result;
    }

    private boolean isBuilding( Dependency dependency )
    {
        final MavenProject key = new MavenProject();
        key.setGroupId( dependency.getGroupId() );
        key.setArtifactId( dependency.getArtifactId() );
        key.setVersion( dependency.getVersion() );
        return projectIndex.containsKey( BuilderCommon.getKey( key ) );
    }

    @Nonnull
    private DigestItemType resolveArtifact( final Artifact dependencyArtifact,
                                            MultimoduleDiscoveryStrategy strategy ) throws IOException
    {

        ArtifactResolutionRequest request = new ArtifactResolutionRequest().setArtifact(
                dependencyArtifact ).setResolveRoot( true ).setResolveTransitively( false ).setLocalRepository(
                session.getLocalRepository() ).setRemoteRepositories(
                project.getRemoteArtifactRepositories() ).setOffline(
                session.isOffline() || !strategy.isLookupRemoteMavenRepo( dependencyArtifact ) ).setForceUpdate(
                session.getRequest().isUpdateSnapshots() ).setServers( session.getRequest().getServers() ).setMirrors(
                session.getRequest().getMirrors() ).setProxies( session.getRequest().getProxies() );

        final ArtifactResolutionResult result = repoSystem.resolve( request );

        if ( !result.isSuccess() )
        {
            throw new DependencyNotResolvedException( "Cannot resolve in-project dependency: " + dependencyArtifact );
        }

        if ( !result.getMissingArtifacts().isEmpty() )
        {
            throw new DependencyNotResolvedException(
                    "Cannot resolve artifact: " + dependencyArtifact + ", missing: " + result.getMissingArtifacts() );
        }

        if ( result.getArtifacts().size() > 1 )
        {
            throw new IllegalStateException(
                    "Unexpected number of artifacts returned. Requested: " + dependencyArtifact
                            + ", expected: 1, actual: " + result.getArtifacts() );
        }

        final Artifact resolved = Iterables.getOnlyElement( result.getArtifacts() );

        final HashAlgorithm algorithm = config.getHashFactory().createAlgorithm();
        final String hash = algorithm.hash( resolved.getFile().toPath() );
        return DtoUtils.createDigestedFile( resolved, hash );
    }

    /**
     * PathIgnoringCaseComparator
     */
    public static class PathIgnoringCaseComparator implements Comparator<Path>
    {
        @Override
        public int compare( Path f1, Path f2 )
        {
            String s1 = f1.toAbsolutePath().toString();
            String s2 = f2.toAbsolutePath().toString();
            if ( File.separator.equals( "\\" ) )
            {
                s1 = s1.replaceAll( "\\\\", "/" );
                s2 = s2.replaceAll( "\\\\", "/" );
            }
            return s1.compareToIgnoreCase( s2 );
        }
    }

    /**
     * DependencyComparator
     */
    public static class DependencyComparator implements Comparator<Dependency>
    {
        @Override
        public int compare( Dependency d1, Dependency d2 )
        {
            return d1.getArtifactId().compareTo( d2.getArtifactId() );
        }
    }

    private List<Plugin> normalizePlugins( List<Plugin> plugins, Map<String, List<Dependency>> rawPluginsMap )
    {
        List<Plugin> result = new ArrayList<>(plugins.size());
        for ( Plugin plugin : plugins )
        {
            Plugin copy = plugin.clone();
            result.add(copy);
            List<String> excludeProperties = config.getEffectivePomExcludeProperties( copy );
            removeBlacklistedAttributes( (Xpp3Dom) copy.getConfiguration(), excludeProperties );
            for ( PluginExecution execution : copy.getExecutions() )
            {
                Xpp3Dom config = (Xpp3Dom) execution.getConfiguration();
                removeBlacklistedAttributes( config, excludeProperties );
            }

            List<Dependency> rawPluginDependencies = rawPluginsMap.get( copy.getGroupId() + ":" + copy.getArtifactId() );
            List<Dependency> dependencies;

            if ( rawPluginDependencies == null )
            {
                dependencies = copy.getDependencies();
            } else
            {
                Map<String, Dependency> dependencyMap = new HashMap<>( rawPluginDependencies.size() * 2 );
                collectRawDependenciesWithVersion(rawPluginDependencies, dependencyMap);
                dependencies = normalizeDependencies( copy.getDependencies(), dependencyMap );
            }

            copy.setDependencies(
                    dependencies
                            .stream()
                            .sorted(dependencyComparator)
                            .collect( Collectors.toList() )
            );
        }
        return result;
    }

    private void removeBlacklistedAttributes( Xpp3Dom node, List<String> excludeProperties )
    {
        if ( node == null )
        {
            return;
        }

        Xpp3Dom[] children = node.getChildren();
        int indexToRemove = 0;
        for ( Xpp3Dom child : children )
        {
            if ( excludeProperties.contains( child.getName() ) )
            {
                node.removeChild( indexToRemove );
                continue;
            }
            indexToRemove++;
            removeBlacklistedAttributes( child, excludeProperties );
        }
    }

    public Logger getLogger()
    {
        return logger;
    }

    public CacheConfig getConfig()
    {
        return config;
    }

    public MavenSession getSession()
    {
        return session;
    }

    public MavenProject getProject()
    {
        return project;
    }
}
