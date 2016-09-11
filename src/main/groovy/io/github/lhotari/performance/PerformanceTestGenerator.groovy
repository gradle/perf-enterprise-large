package io.github.lhotari.performance

import groovy.json.JsonSlurper
import groovy.transform.TupleConstructor

@TupleConstructor
class PerformanceTestGenerator {
    File jsonFile
    File outputDir

    void generate() {
        def json = new JsonSlurper().parse(jsonFile, 'UTF-8')
        outputDir.mkdirs()
        def projectNames = []
        json.projects.each { project ->
            if(project.name != 'project_root') {
                projectNames << project.name
                println project.name
                println
            }
        }
        def settingsFile = new File(outputDir, 'settings.gradle')
        settingsFile.withPrintWriter { out ->
            out.print("include '")
            boolean first = true
            for(String name : projectNames) {
                if(!first) {
                    out.print("', '")
                }
                out.print(name)
                first = false
            }
            out.println("'")
        }

    }

    static void main(String[] args) {
        def generator = new PerformanceTestGenerator(new File('../buildsizeinfo.json'), new File('/tmp/perf-test'))
        generator.generate()
    }
}
