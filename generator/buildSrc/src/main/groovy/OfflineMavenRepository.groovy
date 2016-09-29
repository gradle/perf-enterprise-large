import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.language.java.artifact.JavadocArtifact
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.gradle.util.GFileUtils

class OfflineMavenRepository extends DefaultTask {
    @Input
    String configurationName = 'compile'

    @OutputDirectory
    File repoDir = new File(project.buildDir, 'offline-repo')

    @TaskAction
    void build() {
        Configuration configuration = project.configurations.getByName(configurationName)
        copyJars(configuration)
        copyPoms(configuration)
    }

    private void copyJars(Configuration configuration) {

        configuration.resolvedConfiguration.resolvedArtifacts.each { artifact ->
            def moduleVersionId = artifact.moduleVersion.id
            File moduleDir = new File(repoDir, "${moduleVersionId.group.replace('.', '/')}/${moduleVersionId.name}/${moduleVersionId.version}")
            GFileUtils.mkdirs(moduleDir)
            GFileUtils.copyFile(artifact.file, new File(moduleDir, artifact.file.name))
        }

        copyArtifacts(configuration, JvmLibrary, SourcesArtifact, JavadocArtifact)
    }

    private void copyPoms(Configuration configuration) {
        copyArtifacts(configuration, MavenModule, MavenPomArtifact)
    }

    private void copyArtifacts(Configuration configuration, Class type, Class... artifactTypes) {
        def componentIds = configuration.incoming.resolutionResult.allDependencies.collect { it.selected.id }

        def result = project.dependencies.createArtifactResolutionQuery()
                .forComponents(componentIds)
                .withArtifacts(type, artifactTypes)
                .execute()

        for (component in result.resolvedComponents) {
            def componentId = component.id

            if (componentId instanceof ModuleComponentIdentifier) {
                File moduleDir = new File(repoDir, "${componentId.group.replace('.', '/')}/${componentId.module}/${componentId.version}")
                GFileUtils.mkdirs(moduleDir)
                artifactTypes.each { artifactType ->
                    def artifacts = component.getArtifacts(artifactType)
                    artifacts.each { artifact ->
                        File file = artifact.file
                        GFileUtils.copyFile(file, new File(moduleDir, file.name))
                    }
                }
            }
        }
    }
}
