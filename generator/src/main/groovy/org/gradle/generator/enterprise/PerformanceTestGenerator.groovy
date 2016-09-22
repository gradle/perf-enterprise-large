package org.gradle.generator.enterprise

import groovy.json.JsonSlurper
import groovy.transform.TupleConstructor

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

        Map sharedConfigurations = resolveSharedConfigurations(json)

        json.projects.each { project ->
            if(project.name != 'project_root') {
                projectNames << project.name
                println project.name
                File projectDir = new File(outputDir, project.name)
                projectDir.mkdir()

                def dependencies = [:]
                def configurations = [:]

                project.configurations.each { configuration ->
                    if(configuration.excludeRules) {
                        allExcludedRules << convertToListOfMaps(configuration.excludeRules)
                    }
                    if(configuration.resolutionStrategy?.forcedModules) {
                        allForcedModules << convertToListOfMaps(configuration.resolutionStrategy.forcedModules)
                    }

                    if(configuration.dependencies) {
                        if(!sharedConfigurations.containsKey(configuration.name)) {
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
            collection.sort(false) { a, b -> b.size() <=> a.size() }.find{it}
        }
        def excludedRules = findElementWithMostElements(allExcludedRules)
        def forcedModules = findElementWithMostElements(allForcedModules)

        def rootBuildFile = new File(outputDir, 'build.gradle')
        rootBuildFile.withPrintWriter { output ->
            output.println("plugin:'java'")
            output.println("subprojects { project ->")
            output.println("    configurations {")
            sharedConfigurations.each { name, configuration ->
                renderConfiguration(output, configuration)
            }

            output.println("        all {")
            excludedRules.each {
                def parts = []
                if(it.group) {
                    parts << "group: '${it.group}'"
                }
                if(it.module) {
                    parts << "module: '${it.module}'"
                }
                if(parts) {
                    output.println("            exclude ${parts.join(', ')}")
                }
            }
            output.println("            resolutionStrategy {")
            output.println("                force ${forcedModules.collect { "'${gavMapper.mapGAVToString(it)}'" }.join(', ')}")
            output.println("            }")
            output.println("        }")
            output.println("    }")
            output.println("}")
        }
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
                if(configurationsInProject) {
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
        Map<String, String> mappedGav = [:]

        String mapGAVToString(Map<String, String> gav) {
            String groupAndName = "${gav.group}:${gav.name}"
            String key = "${groupAndName}:${gav.version}"
            String mapped = mappedGav.get(key)
            if (mapped == null) {
                Integer versionNumber = (versionNumberCounter.get(groupAndName) ?: -1) + 1
                versionNumberCounter.put(groupAndName, versionNumber)
                mapped = "${groupAndName}:1.${versionNumber}"
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
