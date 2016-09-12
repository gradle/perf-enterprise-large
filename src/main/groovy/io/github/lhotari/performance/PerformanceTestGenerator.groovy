package io.github.lhotari.performance

import groovy.json.JsonSlurper
import groovy.transform.TupleConstructor

@TupleConstructor
class PerformanceTestGenerator {
    File jsonFile
    File outputDir
    GavMapper gavMapper = new GavMapper()

    void generate() {
        def json = new JsonSlurper().parse(jsonFile, 'UTF-8')
        outputDir.mkdirs()
        def projectNames = []

        def allExcludedRules = [] as Set
        def allForcedModules = [] as Set

        json.projects.each { project ->
            if(project.name != 'project_root') {
                projectNames << project.name
                println project.name
                File projectDir = new File(outputDir, project.name)
                projectDir.mkdir()
                File buildFile = new File(projectDir, "build.gradle")
                buildFile.text = """
                            apply plugin:'java'

                            """.stripIndent()

                project.configurations.each { configuration ->
                    if(configuration.excludeRules) {
                        allExcludedRules << convertToListOfMaps(configuration.excludeRules)
                    }
                    if(configuration.resolutionStrategy?.forcedModules) {
                        allForcedModules << convertToListOfMaps(configuration.resolutionStrategy.forcedModules)
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
        rootBuildFile.text = """
                            apply plugin:'java'

                            subprojects { project ->
                                configurations {
                                    all {
${
                                            excludedRules.collect {
                                                def parts = []
                                                if(it.group) {
                                                    parts << "group: '${it.group}'"
                                                }
                                                if(it.module) {
                                                    parts << "module: '${it.module}'"
                                                }
                                                if(parts) {
                                                    "                                        exclude ${parts.join(', ')}"
                                                } else {
                                                    ''
                                                }
                                            }.join('\n')
                                        }

                                        resolutionStrategy {
                                            force ${forcedModules.collect{ "'${gavMapper.mapGAVToString(it)}'" }.join(', ')}
                                        }
                                    }
                                }
                            }
                            """.stripIndent()


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
        def generator = new PerformanceTestGenerator(new File('../buildsizeinfo.json'), new File('/tmp/perf-test'))
        generator.generate()
    }
}
