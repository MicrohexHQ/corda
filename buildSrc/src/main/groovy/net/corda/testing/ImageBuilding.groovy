package net.corda.testing

import com.bmuschko.gradle.docker.DockerRegistryCredentials
import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
import com.bmuschko.gradle.docker.tasks.container.DockerLogsContainer
import com.bmuschko.gradle.docker.tasks.container.DockerStartContainer
import com.bmuschko.gradle.docker.tasks.container.DockerWaitContainer
import com.bmuschko.gradle.docker.tasks.image.*
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 this plugin is responsible for setting up all the required docker image building tasks required for producing and pushing an
 image of the current build output to a remote container registry
 */
class ImageBuilding implements Plugin<Project> {

    DockerPushImage pushTask

    @Override
    void apply(Project project) {

        def registryCredentialsForPush = new DockerRegistryCredentials(project.getObjects())
        registryCredentialsForPush.username.set("stefanotestingcr")
        registryCredentialsForPush.password.set(System.getProperty("docker.push.password") ? System.getProperty("docker.push.password") : "")

        DockerPullImage pullTask = project.tasks.create("pullBaseImage", DockerPullImage){
            repository = "stefanotestingcr.azurecr.io/buildbase"
            tag = "latest"
            doFirst {
                registryCredentials = registryCredentialsForPush
            }
        }

        DockerBuildImage buildDockerImageForSource = project.tasks.create('buildDockerImageForSource', DockerBuildImage) {
            dependsOn Arrays.asList(project.rootProject.getTasksByName("clean", true), pullTask)
            inputDir.set(new File("."))
            dockerFile.set(new File(new File("testing"), "Dockerfile"))
        }

        DockerCreateContainer createBuildContainer = project.tasks.create('createBuildContainer', DockerCreateContainer) {
            File baseWorkingDir = new File(System.getProperty("docker.work.dir") ? System.getProperty("docker.work.dir") : System.getProperty("java.io.tmpdir"))
            File gradleDir = new File(baseWorkingDir, "gradle")
            File mavenDir = new File(baseWorkingDir, "maven")
            doFirst {
                if (!gradleDir.exists()) {
                    gradleDir.mkdirs()
                }
                if (!mavenDir.exists()) {
                    mavenDir.mkdirs()
                }
            }

            dependsOn buildDockerImageForSource
            targetImageId buildDockerImageForSource.getImageId()
            binds = [(gradleDir.absolutePath): "/tmp/gradle", (mavenDir.absolutePath): "/home/root/.m2"]
        }

        DockerStartContainer startBuildContainer = project.tasks.create('startBuildContainer', DockerStartContainer) {
            dependsOn createBuildContainer
            targetContainerId createBuildContainer.getContainerId()
        }

        DockerLogsContainer logBuildContainer = project.tasks.create('logBuildContainer', DockerLogsContainer) {
            dependsOn startBuildContainer
            targetContainerId createBuildContainer.getContainerId()
            follow = true
        }

        DockerWaitContainer waitForBuildContainer = project.tasks.create('waitForBuildContainer', DockerWaitContainer) {
            dependsOn logBuildContainer
            targetContainerId createBuildContainer.getContainerId()
            doLast {
                if (getExitCode() != 0) {
                    throw new GradleException("Failed to build docker image, aborting build")
                }
            }
        }

        DockerCommitImage commitBuildImageResult = project.tasks.create('commitBuildImageResult', DockerCommitImage) {
            dependsOn waitForBuildContainer
            targetContainerId createBuildContainer.getContainerId()
        }

        DockerTagImage tagBuildImageResult = project.tasks.create('tagBuildImageResult', DockerTagImage) {
            dependsOn commitBuildImageResult
            imageId = commitBuildImageResult.getImageId()
            tag = System.getProperty("docker.provided.tag") ? System.getProperty("docker.provided.tag") :  "${UUID.randomUUID().toString().toLowerCase().subSequence(0, 12)}"
            repository = "stefanotestingcr.azurecr.io/testing"
        }

        if (System.getProperty("docker.tag")) {
            DockerPushImage pushBuildImage = project.tasks.create('pushBuildImage', DockerPushImage) {
                doFirst {
                    registryCredentials = registryCredentialsForPush
                }
                imageName = "stefanotestingcr.azurecr.io/testing"
                tag = System.getProperty("docker.tag")
            }
            this.pushTask = pushBuildImage
        } else {
            DockerPushImage pushBuildImage = project.tasks.create('pushBuildImage', DockerPushImage) {
                dependsOn tagBuildImageResult
                doFirst {
                    registryCredentials = registryCredentialsForPush
                }
                imageName = "stefanotestingcr.azurecr.io/testing"
                tag = tagBuildImageResult.tag
            }
            this.pushTask = pushBuildImage
        }

    }
}