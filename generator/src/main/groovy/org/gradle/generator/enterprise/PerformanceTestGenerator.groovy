package org.gradle.generator.enterprise

import groovy.json.JsonSlurper
import groovy.transform.TupleConstructor
import org.gradle.generator.maven.MavenModule
import org.gradle.generator.maven.MavenRepository

@TupleConstructor
class PerformanceTestGenerator {
    File jsonFile
    File outputDir
    GavMapper gavMapper = new GavMapper()
    Collection<String> defaultConfigurationNames = ['compile', 'testCompile', 'compileOnly', 'testCompileOnly', 'runtime', 'testRuntime',
                                                    'default', 'archives',
                                                    'classpath', 'compileClasspath', 'testCompileClasspath', 'testRuntimeClasspath'] as Set


    void generate() {
        def json = new JsonSlurper().parse(jsonFile, 'UTF-8')
        outputDir.mkdirs()
        def projectNames = []

        def allExcludedRules = [] as Set
        def allForcedModules = [] as Set

        def allExternalDependencies = [:]

        Map sharedConfigurations = resolveSharedConfigurations(json)

        json.projects.each { project ->
            if (project.name != 'project_root') {
                projectNames << project.name
                println project.name
                File projectDir = new File(outputDir, project.name)
                projectDir.mkdir()

                def dependencies = [:]
                def configurations = [:]

                project.configurations.each { configuration ->
                    if (configuration.excludeRules) {
                        allExcludedRules << convertToListOfMaps(configuration.excludeRules)
                    }
                    if (configuration.resolutionStrategy?.forcedModules) {
                        allForcedModules << convertToListOfMaps(configuration.resolutionStrategy.forcedModules)
                    }

                    if (configuration.dependencies) {
                        if (!sharedConfigurations.containsKey(configuration.name)) {
                            configurations.put(configuration.name, configuration)
                        }
                        dependencies.put(configuration.name, configuration.dependencies)
                    }
                }

                File buildFile = new File(projectDir, "build.gradle")
                buildFile.withPrintWriter { out ->
                    out.println("apply plugin:'java'")
                    if (configurations) {
                        out.println("configurations {")
                        configurations.each { configurationName, configuration ->
                            renderConfiguration(out, configuration)
                        }
                        out.println("}")
                    }
                    if (dependencies) {
                        out.println("dependencies {")
                        dependencies.each { configurationName, deps ->
                            deps.each { dep ->
                                renderDependency(out, '    ', configurationName, dep)
                                if (dep.type == 'external_module') {
                                    allExternalDependencies.put(dependencyId(dep), dep)
                                }
                            }
                        }
                        out.println("}")
                    }
                }
            }
        }
        def settingsFile = new File(outputDir, 'settings.gradle')
        settingsFile.withPrintWriter { out ->
            out.println("include ${projectNames.collect { "'${it}'" }.join(', ')}")
        }

        def findElementWithMostElements = { collection ->
            collection.sort(false) { a, b -> b.size() <=> a.size() }.find { it }
        }
        def excludedRules = findElementWithMostElements(allExcludedRules)
        def forcedModules = findElementWithMostElements(allForcedModules)

        def rootBuildFile = new File(outputDir, 'build.gradle')
        rootBuildFile.withPrintWriter { output ->
            output.println("plugin:'java'")

            output << '''
allprojects { project ->
    repositories {
        maven {
            url rootProject.file("mavenRepo").toURI().toURL()
        }
    }
}
'''

            output.println("subprojects { project ->")
            output.println("    configurations {")
            sharedConfigurations.each { name, configuration ->
                renderConfiguration(output, configuration)
            }

            output.println("        all {")
            excludedRules.each {
                def parts = []
                if (it.group) {
                    parts << "group: '${it.group}'"
                }
                if (it.module) {
                    parts << "module: '${it.module}'"
                }
                if (parts) {
                    output.println("            exclude ${parts.join(', ')}")
                }
            }
            output.println("            resolutionStrategy {")
            output.println("                force ${forcedModules.collect { "'${gavMapper.mapGAVToString(it)}'" }.join(', ')}")
            output.println("            }")
            output.println("        }")
            output.println("    }")


            output << '''
        task resolveDependencies {
            dependsOn configurations
            // Need this to ensure that configuration is actually resolved
            doLast {
                configurations.each {
                    println "project: ${project.path} configuration: ${it.name} size: ${it.files.size()}"
                }
            }
        }
'''

            output.println("}")
        }

        // Generate artifact in repo for exclusions
        excludedRules.each { it ->
            if (it.group && it.module) {
                def dep = [version: '1.0']
                dep.putAll(it)
                allExternalDependencies.put(dependencyId(dep), dep)
            }
        }

        // Generate artifact in repo for forced modules
        forcedModules.each { dep ->
            allExternalDependencies.put(dependencyId(dep), dep)
        }

        generateMavenRepository(allExternalDependencies, json)
    }

    private void generateMavenRepository(allExternalDependencies, json) {
        def dependenciesForExternalDependencies = [:]

        traverseDependencies(null, json.largest_dependency_graph.graph.dependencies, allExternalDependencies, dependenciesForExternalDependencies)
        traverseDependencies(null, json.deepest_dependency_graph.graph.dependencies, allExternalDependencies, dependenciesForExternalDependencies)

        MavenRepository repo = new MavenRepository(new File(outputDir, "mavenRepo"))
        //repo.mavenJarCreator.minimumSizeKB = 1024
        //repo.mavenJarCreator.maximumSizeKB = 1024

        allExternalDependencies.each { depId, dep ->
            def mapped = gavMapper.mapGAV(dep)
            MavenModule module = repo.addModule(mapped.group, mapped.name, mapped.version)
            def deps = dependenciesForExternalDependencies.get(depId)
            if (deps) {
                for (def subdep : deps) {
                    def mappedSub = gavMapper.mapGAV(subdep)
                    module.dependsOn(mappedSub.group, mappedSub.name, mappedSub.version)
                }
            }
        }

        println "Creating maven repository..."
        repo.publish()
        println "Done."
    }

    def traverseDependencies(parent, dependencyGraph, allExternalDependencies, dependenciesForExternalDependencies) {
        def parentDeps
        if (parent != null) {
            def parentId = dependencyId(parent)
            if (allExternalDependencies.containsKey(parentId)) {
                parentDeps = dependenciesForExternalDependencies.get(parentId)
                if (parentDeps == null) {
                    parentDeps = []
                    dependenciesForExternalDependencies.put(parentId, parentDeps)
                }
            }
        }
        for (def dep : dependencyGraph) {
            if (parentDeps != null) {
                parentDeps << [group: dep.group, name: dep.name, version: dep.version]
            }
            if (dep.type == 'resolved' && dep.dependencies) {
                traverseDependencies(dep, dep.dependencies, allExternalDependencies, dependenciesForExternalDependencies)
            }
        }
    }

    static String dependencyId(Map<String, String> dep) {
        "${dep.group}:${dep.name}:${dep.version}".toString()
    }

    def renderDependency(PrintWriter out, indent, def configurationName, def dep) {
        def mapped
        def transitive = true
        switch (dep.type) {
            case 'external_module':
                mapped = "'${gavMapper.mapGAVToString(dep)}'".toString()
                transitive = dep.transitive
                break
            case 'project':
                mapped = "project(':${dep.project}')".toString()
                break
        }
        if (mapped) {
            if (transitive) {
                out.println "${indent}${configurationName} ${mapped}"
            } else {
                out.println "${indent}${configurationName}(${mapped}) { transitive = false }"
            }
        }
    }

    private void renderConfiguration(output, configuration) {
        if (!(configuration.name in defaultConfigurationNames)) {
            output.print("        ${configuration.name}")
            if (configuration.extendsFrom) {
                output.print(".extendsFrom(${configuration.extendsFrom.collect { it.configuration }.join(', ')})")
            }
            output.println()
            if (!configuration.transitive) {
                output.println("        ${configuration.name} { transitive = false }")
            }
        }
    }

    private Map resolveSharedConfigurations(json) {
        Map allConfigurations = null
        json.projects.each { project ->
            if (project.name != 'project_root') {
                def configurationsInProject = [:]
                project.configurations.each { configuration ->
                    if (configuration.dependencies) {
                        configurationsInProject.put(configuration.name, configuration)
                    }
                }
                if (configurationsInProject) {
                    if (allConfigurations == null) {
                        allConfigurations = configurationsInProject
                    } else {
                        for (Iterator<Map.Entry> iterator = allConfigurations.entrySet().iterator(); iterator.hasNext();) {
                            Map.Entry entry = iterator.next()
                            if (!configurationsInProject.containsKey(entry.key)) {
                                iterator.remove()
                            }
                        }
                    }
                }
            }
        }
        allConfigurations
    }

    static class GavMapper {
        Map<String, Integer> versionNumberCounter = [:]
        Map<String, Map<String, String>> mappedGav = [:]

        String mapGAVToString(Map<String, String> gav) {
            dependencyId(mapGAV(gav))
        }

        Map<String, String> mapGAV(Map<String, String> gav) {
            String groupAndName = "${gav.group}:${gav.name}"
            String key = "${groupAndName}:${gav.version}"
            Map<String, String> mapped = mappedGav.get(key)
            if (mapped == null) {
                Integer versionNumber = (versionNumberCounter.get(groupAndName) ?: -1) + 1
                versionNumberCounter.put(groupAndName, versionNumber)
                mapped = [group: gav.group, name: gav.name, version: "1.${versionNumber}".toString()]
                mappedGav.put(key, mapped)
            }
            return mapped
        }
    }

    def List<Map> convertToListOfMaps(sourceListOfMaps) {
        sourceListOfMaps.collect { new LinkedHashMap(it) }
    }

    static void main(String[] args) {
        def generator = new PerformanceTestGenerator(new File('buildsizeinfo.json'), new File('..'))
        generator.generate()
    }
}
