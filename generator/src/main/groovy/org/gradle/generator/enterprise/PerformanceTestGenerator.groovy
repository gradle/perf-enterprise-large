package org.gradle.generator.enterprise

import groovy.json.JsonSlurper
import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import org.gradle.generator.maven.MavenModule
import org.gradle.generator.maven.MavenRepository

class PerformanceTestGenerator {
    File jsonFile
    File outputDir
    File templateDir
    double sizeFactor = 1.0d // use to scale generated project size
    GavMapper gavMapper = new GavMapper()
    Collection<String> defaultConfigurationNames = ['compile', 'testCompile', 'compileOnly', 'testCompileOnly', 'runtime', 'testRuntime',
                                                    'default', 'archives',
                                                    'classpath', 'compileClasspath', 'testCompileClasspath', 'testRuntimeClasspath'] as Set

    // project names of all sub projects
    List<String> projectNames = []

    // JSON of all java projects
    List<Map> javaProjects = []

    // all exclude rules found in all projects
    Set<List<Map>> allExcludedRules = [] as Set
    // all force modules rules found in all projects
    Set<List<Map>> allForcedModules = [] as Set

    // all external dependencies that have been found
    // since buildsizeinfo.json doesn't include all external dependencies, this is a guess of the external dependencies
    Map<String, Map> allExternalDependencies = [:]

    // resolved shared exclude rules that get applied to all projects
    // this is determined from the exclude rules of different projects
    List<Map> excludedRules
    // resolved force modules resolved from projects
    List<Map> forcedModules

    // the parsed JSON object
    def buildSizeJson

    // resolved shared configurations that are present in all/most projects
    Map<String, Map> sharedConfigurations

    TemplateEngine templateEngine

    int generatedFilesCounter = 0

    void generate() {
        templateEngine = new TemplateEngine(dir: templateDir)

        buildSizeJson = new JsonSlurper().parse(jsonFile, 'UTF-8')
        outputDir.mkdirs()

        sharedConfigurations = resolveSharedConfigurations()

        buildSizeJson.projects.each { project ->
            if (project.name != 'project_root') {
                generateSubProject(project)
            }
        }
        generateSettingsFile(projectNames)

        resolveSharedExcludedAndForcedModules()

        generateRootBuildFile()

        generateMavenRepository()

        println "Creating Java sources and test files... project size factor is ${sizeFactor}"
        generateJavaSourceFiles()
        println "Done. Generated ${generatedFilesCounter} files."
    }


    void generateJavaSourceFiles() {
        javaProjects.each { project ->
            if (project.name != 'project_root') {
                def projectDir = new File(outputDir, project.name)
                def mainSourceSet = project.sourceSets?.find { it.name == 'main' }
                List<Map> generatedJavaClassInfos = createJavaSources(projectDir, mainSourceSet, project)
                def testSourceSet = project.sourceSets?.find { it.name == 'test' }
                createJavaTestSources(projectDir, testSourceSet, project, generatedJavaClassInfos)
            }
        }
    }

    private void createJavaTestSources(File projectDir, testSourceSet, project, List<Map> generatedJavaClassInfos) {
        if (!testSourceSet.sourceFileCounts.java) {
            return
        }

        File sourceDir = new File(projectDir, 'src/test/java')
        sourceDir.mkdirs()

        int loc = scaleValue(testSourceSet.loc.java, 1)
        int files = scaleValue(testSourceSet.sourceFileCounts.java, 1)
        int avgloc = loc / files
        int testMethodCount = Math.max((int) ((avgloc - 5) / 4), 1)

        int count = 0
        for (Map javaClassInfo : generatedJavaClassInfos) {
            if (count++ > files) {
                break
            }
            File packageDir = new File(sourceDir, javaClassInfo.packagePath)
            if (!packageDir.exists()) {
                packageDir.mkdirs()
            }

            String testClassName = "${javaClassInfo.productionClassName}Test"
            Template javaSourceTemplate = templateEngine.createTemplate('Test.java')

            File classFile = new File(packageDir, "${testClassName}.java")
            Map testClassInfo = [
                    testClassName  : testClassName,
                    testMethodCount: testMethodCount
            ] + javaClassInfo
            classFile.withWriter { it << javaSourceTemplate.make(testClassInfo) }
            generatedFilesCounter++
        }
    }

    private int scaleValue(Integer referenceValue, int minValue, int maxValue) {
        Math.max(scaleValue(Math.min(referenceValue ?: 0, maxValue)), minValue)
    }

    private int scaleValue(Integer referenceValue, int minValue) {
        Math.max(scaleValue(referenceValue ?: 0), minValue)
    }

    private int scaleValue(Integer referenceValue) {
        if (sizeFactor != 1.0d) {
            (referenceValue ?: 0) * sizeFactor
        } else {
            referenceValue ?: 0
        }
    }

    private List<Map> createJavaSources(File projectDir, mainSourceSet, project) {
        File sourceDir = new File(projectDir, 'src/main/java')
        sourceDir.mkdirs()

        int loc = scaleValue(mainSourceSet.loc.java, 1)
        int files = scaleValue(mainSourceSet.sourceFileCounts.java, 1)
        int packages = scaleValue(mainSourceSet.packagesPerExtension.java, 1)

        int avgloc = loc / files
        // Production.java template has propertyCount argument
        // each property creates 5 source code lines
        // limit to 10 properties per file since Lombok is used
        int propertyCount = Math.min(Math.max((int) ((avgloc - 9) / 5), 1), 10)

        List<Map> generatedJavaClassInfos = []

        for (int i = 0; i < files; i++) {
            int packageNum = i % packages

            String packageName = "com.enterprise.large.${project.name}.package${packageNum}".toLowerCase()
            String packagePath = packageName.replace('.', '/')
            File packageDir = new File(sourceDir, packagePath)
            if (!packageDir.exists()) {
                packageDir.mkdirs()
            }
            String productionClassName = "Production_${project.name}_${i}"
            Template javaSourceTemplate = templateEngine.createTemplate('Production.java')

            File classFile = new File(packageDir, "${productionClassName}.java")

            def classInfo = [
                    productionClassName: productionClassName,
                    packageName        : packageName,
                    propertyCount      : propertyCount,
                    packagePath        : packagePath
            ]
            classFile.withWriter { it << javaSourceTemplate.make(classInfo) }
            generatedFilesCounter++
            generatedJavaClassInfos << classInfo
        }

        generatedJavaClassInfos
    }

    private void generateRootBuildFile() {
        def rootBuildFile = new File(outputDir, 'build.gradle').canonicalFile
        println "Generating root build file ${rootBuildFile.absolutePath}"
        rootBuildFile.withPrintWriter { output ->
            renderApplyMeasurementPlugin(output)

            renderApplyRootPlugins(output)

            addGeneratedMavenRepoToAllProjects(output)

            output.println "apply from: 'gradle/idea.gradle'"
            output.println "apply from: 'gradle/codegen-demo.gradle'"

            output.println 'subprojects { project ->'

            output.println 'project.plugins.withType(JavaPlugin) {'

            renderConfigurationsBlock(output)

            appendResolveDependenciesTask(output)

            output.println '}'

            output.println '}'

            output << '''
if(measurementPluginEnabled) {
    org.gradle.performance.plugin.BuildEventTimeStamps.settingsEvaluated()
}
'''
        }
    }

    private Writer addGeneratedMavenRepoToAllProjects(PrintWriter output) {
        output << '''
def mavenRepoUrl = project.hasProperty('useFileRepo') ? rootProject.file("mavenRepo").toURI().toURL() : "http://localhost:8000/"
allprojects { project ->
    repositories {
        maven {
            url mavenRepoUrl
        }
    }
}
'''
    }

    private Writer renderApplyRootPlugins(PrintWriter output) {
        output << '''
apply plugin:'java'
'''
    }

    private Writer renderApplyMeasurementPlugin(PrintWriter output) {
        output << '''
buildscript {
    dependencies {
        def measurementPluginJar = project.hasProperty('measurementPluginJar') ? project.property('measurementPluginJar') : null
        if(measurementPluginJar) {
            classpath files(measurementPluginJar)
        }
    }
}
def measurementPluginEnabled = project.hasProperty('measurementPluginJar')
if(measurementPluginEnabled) {
    apply plugin: org.gradle.performance.plugin.MeasurementPlugin
}
'''
    }

    private void renderConfigurationsBlock(output) {
        output.println("    configurations {")
        sharedConfigurations.each { name, configuration ->
            renderConfiguration(output, configuration)
        }
        renderAllConfigurationsBlock(output)
        output.println("    }")
    }

    private void renderAllConfigurationsBlock(PrintWriter output) {
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
    }

    private void appendStartMavenRepoTask(PrintWriter output) {
        output << '''
def stopMavenServer() {
    try {
       new URL('http://localhost:8000/stop').text
    } catch (e) {
    }
}
if (!gradle.startParameter.projectProperties.useFileRepo) {
  gradle.projectsLoaded {
      stopMavenServer()
      def process = ["${gradle.gradleHomeDir}/bin/gradle", '--no-daemon', '-g', file("maven-server/gradleHomeDir").absolutePath, "run"].execute(null, file("maven-server"))
      process.consumeProcessOutput(System.out, System.err)
      process.waitFor()
  }
  gradle.buildFinished {
      stopMavenServer()
  }
}
'''
    }

    private void appendResolveDependenciesTask(PrintWriter output) {
        output << '''
        def resolvableConfigurations = configurations.findAll { it.canBeResolved }
        tasks.register("resolveDependencies") {
            dependsOn resolvableConfigurations
            // Need this to ensure that configuration is actually resolved
            doLast {
                resolvableConfigurations.each {
                    println "project: ${project.path} configuration: ${it.name} size: ${it.files.size()}"
                }
            }
        }
'''
    }

    private void resolveSharedExcludedAndForcedModules() {
        def findElementWithMostElements = { collection ->
            collection.sort(false) { a, b -> b.size() <=> a.size() }.find { it }
        }
        excludedRules = findElementWithMostElements(allExcludedRules)
        forcedModules = findElementWithMostElements(allForcedModules)
    }

    private void generateSubProject(project) {
        Map<String, Collection<Map>> configurationDependencies = [:]
        Map<String, Map> configurations = [:]

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
                configurationDependencies.put(configuration.name, configuration.dependencies)
            }
        }

        if (!(configurations || configurationDependencies
                || project.sourceSets?.find { it.name == 'main' }?.loc?.java)) {
            println "Skipping non-Java project ${project.name}"
            return
        }

        projectNames << project.name
        javaProjects << project
        println project.name
        File projectDir = new File(outputDir, project.name)
        projectDir.mkdir()

        File buildFile = new File(projectDir, "build.gradle")
        buildFile.withPrintWriter { out ->
            if (configurations || configurationDependencies) {
                out << '''
apply plugin:'java'

tasks.withType(JavaCompile).configureEach {
    options.fork = true
    configure(options.forkOptions) {
        memoryMaximumSize = '2g'
        memoryInitialSize = '2g'
        jvmArgs = ['-Xverify:none', '-XX:+UseConcMarkSweepGC', '-XX:+ParallelRefProcEnabled']
    }
}
'''
            }
            if (configurations) {
                out.println("configurations {")
                configurations.each { configurationName, configuration ->
                    renderConfiguration(out, configuration)
                }
                out.println("}")
            }
            if (configurations || configurationDependencies) {
                out.println("dependencies {")
                out.println("compile 'org.slf4j:slf4j-api:1.7.21'");
                out.println("compile 'org.slf4j:slf4j-simple:1.7.21'");
                out.println("annotationProcessor 'org.projectlombok:lombok:1.16.14'");
                out.println("testCompile 'junit:junit:4.12'")
                configurationDependencies.each { configurationName, deps ->
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

    private void generateSettingsFile(projectNames) {
        def settingsFile = new File(outputDir, 'settings.gradle').canonicalFile
        println "Generating settings file ${settingsFile.absolutePath}"
        settingsFile.withPrintWriter { out ->
            appendStartMavenRepoTask(out)
            appendCreatePidFile(out)
            out.println("include([${projectNames.collect { "'${it}'" }.join(', ')}] as String[])")
        }
    }

    def appendCreatePidFile(PrintWriter out) {
        out << '''
import java.lang.management.ManagementFactory

def pidFile = new File(rootDir, 'gradle.pid')
pidFile.deleteOnExit()
pidFile.text = ManagementFactory.getRuntimeMXBean().getName().split('@')[0]
'''

    }

    private void generateMavenRepository() {
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

        def dependenciesForExternalDependencies = [:]

        traverseDependencies(null, buildSizeJson.largest_dependency_graph.graph.dependencies, allExternalDependencies, dependenciesForExternalDependencies)
        traverseDependencies(null, buildSizeJson.deepest_dependency_graph.graph.dependencies, allExternalDependencies, dependenciesForExternalDependencies)

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

    // reverse-engineer the external dependencies from the dependency graphs that are stored in the buildsizeinfo.json file
    // the buildsizeinfo.json file lacks information about all external dependencies used in the build and that's the reason to do this
    def traverseDependencies(parent, dependencyGraph, allExternalDependencies, dependenciesForExternalDependencies) {
        def parentDeps
        if (parent != null) {
            def parentId = dependencyId(parent)
            parentDeps = dependenciesForExternalDependencies.get(parentId)
            if (parentDeps == null) {
                parentDeps = []
                dependenciesForExternalDependencies.put(parentId, parentDeps)
            }
            if (!allExternalDependencies.containsKey(parentId)) {
                allExternalDependencies.put(parentId, parent)
            }
        }
        for (def dep : dependencyGraph) {
            if (parentDeps != null) {
                def subdep = [group: dep.group, name: dep.name, version: dep.version]
                def subid = dependencyId(subdep)
                if (!allExternalDependencies.containsKey(subid)) {
                    allExternalDependencies.put(subid, subdep)
                }
                parentDeps << subdep
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

    // finds configurations that exist in all projects (that contain configurations)
    private Map resolveSharedConfigurations() {
        Map allConfigurations = null
        buildSizeJson.projects.each { project ->
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

    // maps a masked version String in buildsizeinfo.json to a unique version number starting from 1.0
    // the reason for doing this is the feature of Gradle that non-parseable version strings might be handled
    // in a different way than normal version numbers
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

    static class TemplateEngine {
        File dir
        SimpleTemplateEngine simpleTemplateEngine = new SimpleTemplateEngine()
        Map<String, Template> templateCache = [:]

        Template createTemplate(String templateName) {
            Template template = templateCache.get(templateName)
            if (template == null) {
                template = simpleTemplateEngine.createTemplate(new File(dir, templateName))
                templateCache.put(templateName, template)
            }
            template
        }
    }

    def List<Map> convertToListOfMaps(sourceListOfMaps) {
        sourceListOfMaps.collect { new LinkedHashMap(it) }
    }

    static void main(String[] args) {
        double sizeFactor = 1.0d
        if (args.length > 0) {
            sizeFactor = Double.parseDouble(args[0])
        }
        def generator = new PerformanceTestGenerator(jsonFile: new File('buildsizeinfo.json'), outputDir: new File('..'), templateDir: new File('src/main/templates'), sizeFactor: sizeFactor)
        generator.generate()
    }
}
