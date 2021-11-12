package org.apache.maven.extensions.caching;

import org.apache.maven.extensions.caching.xml.CacheConfig;
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
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.maven.extensions.caching.ProjectUtils.isPomPackaging;

@Component(role = RawModelProvider.class)
public class DefaultRawModelProvider implements RawModelProvider {

    @Requirement
    private CacheConfig cacheConfig;

    @Requirement
    private CacheItemProvider cacheItemProvider;

    private final ConcurrentMap<String, Model> modelCache = new ConcurrentHashMap<>();

    @Override
    public Model effectiveModel(MavenProject project) {
        MavenProject validatedProject = Objects.requireNonNull(project, "project");
        return modelCache.computeIfAbsent(BuilderCommon.getKey(validatedProject), k -> calculateModel(validatedProject));
    }

    private Model calculateModel(MavenProject project) {
        return new InnerModelBuilder(project).calculateModel();
    }

    /**
     * calculates for a single particular project
     */
    class InnerModelBuilder {

        private final MavenProject project;

        InnerModelBuilder(MavenProject project) {
            this.project = project;
        }

        Model calculateModel() {
            Model prototype = this.project.getModel();

            // TODO validate status of the model - it should be in resolved state
            Model resultModel = new Model();

            resultModel.setGroupId(prototype.getGroupId());
            resultModel.setArtifactId(prototype.getArtifactId());
            //does not make sense to add project version to calculate hash
            resultModel.setVersion( /*prototype.getVersion()*/ "");
            resultModel.setModules(prototype.getModules());

            List<Dependency> effectiveModelDependencies = prototype.getDependencies();

            List<Dependency> dependencies = normalizeDependencies(effectiveModelDependencies, collectAllRawDependencies());
            resultModel.setDependencies(dependencies);

            Build build = new Build();
            List<Plugin> plugins = prototype.getBuild().getPlugins();
            Map<String, Plugin> rawPluginsDependencies = collectAllRawPlugins();
            build.setPlugins(normalizePlugins(plugins, rawPluginsDependencies));

            //no need to track plugin management section in effective pom as it contributes into plugins section
            if (isPomPackaging(this.project)) {
                PluginManagement pluginManagement = prototype.getBuild().getPluginManagement();
                PluginManagement pm = pluginManagement.clone();
                pm.setPlugins(normalizePlugins(pm.getPlugins(), rawPluginsDependencies));
                build.setPluginManagement(pm);
            }

            resultModel.setBuild(build);
            return resultModel;
        }

        private Map<String, Dependency> collectAllRawDependencies() {
            Map<String, Dependency> dependencyMap = new HashMap<>();
            MavenProject currentProject = this.project;

            while (currentProject != null) {
                Model rawModel = currentProject.getOriginalModel();
                collectRawDependenciesWithVersion(rawModel.getDependencies(), dependencyMap);
                DependencyManagement dependencyManagement = rawModel.getDependencyManagement();
                if (dependencyManagement != null) {
                    collectRawDependenciesWithVersion(dependencyManagement.getDependencies(), dependencyMap);
                }

                getActiveProfiles(currentProject, rawModel).forEach(p -> {
                    collectRawDependenciesWithVersion(p.getDependencies(), dependencyMap);
                    DependencyManagement dm = p.getDependencyManagement();
                    if (dm != null) {
                        collectRawDependenciesWithVersion(dm.getDependencies(), dependencyMap);
                    }
                });

                currentProject = currentProject.getParent();
            }
            return dependencyMap;
        }

        private void collectRawDependenciesWithVersion(@Nullable List<Dependency> in, Map<String, Dependency> out) {
            if (in == null) {
                return;
            }
            in.stream().filter(it -> it.getVersion() != null)
                    .forEach(it -> out.putIfAbsent(
                            makeArtifactId(
                                    normalizeGroupId(it.getGroupId()),
                                    normalizeArtifactId(it.getArtifactId())
                            ), it));
        }

        private Map<String, Plugin> collectAllRawPlugins() {
            MavenProject currentProject = this.project;

            Map<String, Plugin> result = new HashMap<>();
            Model originalModel = currentProject.getOriginalModel();

            //collect all plugin dependencies from build section
            getActiveProfiles(currentProject, originalModel)
                    .forEach(p -> collectPluginDependencies(p.getBuild(), result));
            collectPluginDependencies(originalModel.getBuild(), result);

            //collect all plugin dependencies from current and all ancestors pluginManagement sections
            for (; currentProject != null; currentProject = currentProject.getParent()) {
                Model rawModel = currentProject.getOriginalModel();
                Build build = rawModel.getBuild();
                if (build == null) {
                    continue;
                }
                PluginManagement pluginManagement = build.getPluginManagement();
                if (pluginManagement == null) {
                    continue;
                }

                Stream<Plugin> pluginManagementStream = getActiveProfiles(currentProject, rawModel)
                        .filter(p -> p.getBuild() != null && p.getBuild().getPluginManagement() != null)
                        .flatMap(p -> p.getBuild().getPluginManagement().getPlugins().stream());

                Stream.concat(pluginManagement.getPlugins().stream(), pluginManagementStream)
                        .forEach(plugin -> {
                            String groupId = normalizeGroupId(plugin.getGroupId());
                            String artifactId = normalizeArtifactId(plugin.getArtifactId());
                            String key = makeArtifactId(groupId, artifactId);
                            Plugin plug = result.computeIfAbsent(key, k -> {
                                Plugin p = plugin.clone();
                                p.setArtifactId(artifactId);
                                p.setGroupId(groupId);
                                return p;
                            });
                            if (plug.getVersion() == null) {
                                plug.setVersion(plugin.getVersion());
                            }
                            if (plug.getDependencies() == null || plug.getDependencies().isEmpty()) {
                                plug.setDependencies(plugin.getDependencies());
                            }
                        });
            }

            return result;
        }

        private void collectPluginDependencies(BuildBase build, Map<String, Plugin> out) {
            if (build == null) {
                return;
            }
            for (Plugin plugin : build.getPlugins()) {
                String groupId = normalizeGroupId(plugin.getGroupId());
                String artifactId = normalizeArtifactId(plugin.getArtifactId());
                List<Dependency> dependencies = plugin.getDependencies();
                String key = makeArtifactId(groupId, artifactId);
                Plugin plug = out.computeIfAbsent(key, k -> {
                    Plugin p = plugin.clone();
                    p.setArtifactId(artifactId);
                    p.setGroupId(groupId);
                    //to make mutable list
                    p.setDependencies(new ArrayList<>());
                    return p;
                });
                if (dependencies != null) {
                    plug.getDependencies().addAll(dependencies);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private Stream<Profile> getActiveProfiles(MavenProject project, Model model) {
            Set<String> activeProfileIds = cacheItemProvider.getCache("activeProfileIds", String.class, Set.class)
                    .computeIfAbsent(project.getId(), k -> project.getActiveProfiles().stream()
                            .map(Profile::getId)
                            .collect(Collectors.toSet()));
            List<Profile> profiles = model.getProfiles();
            if (profiles != null) {
                return profiles.stream().filter(p -> activeProfileIds.contains(p.getId()));
            }
            return Stream.empty();
        }

        private List<Dependency> normalizeDependencies(List<Dependency> effectiveDependencies,
                                                       Map<String, Dependency> rawDependencies) {
            if (effectiveDependencies.isEmpty() || rawDependencies.isEmpty()) {
                return effectiveDependencies;
            }
            Map<String, Dependency> dependencyMap = new HashMap<>(effectiveDependencies.size() * 2);
            effectiveDependencies.forEach(dependency -> dependencyMap.put(dependency.getGroupId() + ":" + dependency.getArtifactId(), dependency));

            rawDependencies.entrySet().stream()
                    .filter(it -> {
                        String rawVersion = resolveRawPropertyValue(it.getValue().getVersion());
                        if (isProjectPlaceholder(rawVersion)) {
                            it.getValue().setVersion(rawVersion);
                            return true;
                        }
                        return false;
                    })
                    .forEach(it -> {
                        Dependency dependency = it.getValue();
                        String groupId = normalizeGroupId(dependency.getGroupId());
                        String artifactId = normalizeArtifactId(dependency.getArtifactId());
                        String key = makeArtifactId(groupId, artifactId);
                        Dependency removed = dependencyMap.remove(key);
                        if (removed != null) {
                            Dependency clone = removed.clone();
                            clone.setGroupId(groupId);
                            clone.setVersion(dependency.getVersion().replace("pom.", "project."));
                            dependencyMap.put(key, clone);
                        }
                    });
            return dependencyMap.values().stream().sorted(DefaultRawModelProvider::compareDependencies).collect(Collectors.toList());
        }

        private boolean isProjectPlaceholder(@Nullable String propertyName) {
            return propertyName != null
                    && (propertyName.startsWith("${project.") || propertyName.startsWith("${pom."));
        }

        /*
            <abfx-configuration.version>${module-dependencies.version}</abfx-configuration.version>
            <module-dependencies.version>${project.version}</module-dependencies.version>

            this resolve abfx-configuration.version to ${project.version}

         */
        @Nullable
        private String resolveRawPropertyValue(@Nullable String propertyName) {
            //Not a placeholder or project placeholder return value as is
            if (!isPropertyPlaceholder(propertyName) || isProjectPlaceholder(propertyName)) {
                return propertyName;
            }
            String currentResult = propertyName;
            String propertyToFind = getPropertyFromPlaceholder(propertyName);
            String sysPropertyName = System.getProperties().getProperty(propertyToFind);
            if (sysPropertyName != null) {
                if (!isPropertyPlaceholder(sysPropertyName) || isProjectPlaceholder(sysPropertyName)) {
                    return sysPropertyName;
                }
                propertyToFind = getPropertyFromPlaceholder(sysPropertyName);
                currentResult = sysPropertyName;
            }
            MavenProject currentProject = this.project;

            while (currentProject != null) {
                String projectProperty = currentProject.getOriginalModel().getProperties().getProperty(propertyToFind);
                if (projectProperty != null) {
                    currentResult = projectProperty;
                }
                if (isPropertyPlaceholder(projectProperty)) {
                    //This is a placeholder let's try another attempt to find in this project before we go to parent
                    propertyToFind = getPropertyFromPlaceholder(projectProperty);
                    continue;
                } else if (projectProperty != null) {
                    //This is not a placeholder no need to lookup in parent project
                    break;
                }

                currentProject = currentProject.getParent();
            }
            return currentResult;
        }

        private String getPropertyFromPlaceholder(String propertyName) {
            return propertyName.substring(2, propertyName.length() - 1);
        }

        private boolean isPropertyPlaceholder(@Nullable String property) {
            return property != null && property.startsWith("${") && property.endsWith("}");
        }

        private String makeArtifactId(String groupId, String artifactId) {
            return groupId + ":" + artifactId;
        }

        private String normalizeGroupId(String gId) {
            String groupId = gId;
            if (groupId.contains("${project.groupId}") || groupId.contains("${pom.groupId}")) {
                groupId = groupId
                        .replace("${project.groupId}", project.getGroupId())
                        .replace("${pom.groupId}", project.getGroupId());
            } else if (groupId.contains("${project.parent.groupId}") || groupId.contains("${pom.parent.groupId}")) {
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

        private String normalizeArtifactId(String aId) {
            String artifactId = aId;
            if (artifactId.contains("${project.artifactId}") || artifactId.contains("${pom.artifactId}")) {
                artifactId = artifactId
                        .replace("${project.artifactId}", project.getArtifactId())
                        .replace("${pom.artifactId}", project.getArtifactId());
            } else if (artifactId.contains("${project.parent.artifactId}") || artifactId.contains("${pom.parent.artifactId}")) {
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

        private List<Plugin> normalizePlugins(List<Plugin> plugins, Map<String, Plugin> rawPluginsMap) {
            if (plugins.isEmpty() || rawPluginsMap.isEmpty()) {
                return plugins;
            }

            List<Plugin> result = new ArrayList<>(plugins.size());
            for (Plugin plugin : plugins) {
                Plugin copy = plugin.clone();
                result.add(copy);
                List<String> excludeProperties = cacheConfig.getEffectivePomExcludeProperties(copy);
                removeBlacklistedAttributes((Xpp3Dom) copy.getConfiguration(), excludeProperties);
                for (PluginExecution execution : copy.getExecutions()) {
                    Xpp3Dom config = (Xpp3Dom) execution.getConfiguration();
                    removeBlacklistedAttributes(config, excludeProperties);
                }

                Plugin rawPlugin = rawPluginsMap.get(copy.getGroupId() + ":" + copy.getArtifactId());
                List<Dependency> rawPluginDependencies = rawPlugin == null ? null : rawPlugin.getDependencies();
                List<Dependency> dependencies;

                if (rawPlugin == null || rawPluginDependencies == null) {
                    dependencies = copy.getDependencies();
                } else {
                    Map<String, Dependency> dependencyMap = new HashMap<>(rawPluginDependencies.size() * 2);
                    collectRawDependenciesWithVersion(rawPluginDependencies, dependencyMap);
                    dependencies = normalizeDependencies(copy.getDependencies(), dependencyMap);
                }

                copy.setDependencies(
                        dependencies
                                .stream()
                                .sorted(DefaultRawModelProvider::compareDependencies)
                                .collect(Collectors.toList())
                );
                if (rawPlugin != null && rawPlugin.getVersion() != null) {
                    String rawVersion = resolveRawPropertyValue(rawPlugin.getVersion());
                    if (isProjectPlaceholder(rawVersion)) {
                        copy.setVersion(rawPlugin.getVersion());
                    }
                }
            }
            return result;
        }

        private void removeBlacklistedAttributes(Xpp3Dom node, List<String> excludeProperties) {
            if (node == null) {
                return;
            }

            Xpp3Dom[] children = node.getChildren();
            int indexToRemove = 0;
            for (Xpp3Dom child : children) {
                if (excludeProperties.contains(child.getName())) {
                    node.removeChild(indexToRemove);
                    continue;
                }
                indexToRemove++;
                removeBlacklistedAttributes(child, excludeProperties);
            }
        }
    }

    private static int compareDependencies(Dependency d1, Dependency d2) {
        return d1.getArtifactId().compareTo(d2.getArtifactId());
    }


}
