/*
 * TODO: License goes here!
 */
package io.freemonads.arango.test

import com.whisk.docker.DockerContainer
import com.whisk.docker.config.DockerKitConfig

trait DockerKitConfigWithArango extends DockerKitConfig {

  val DefaultArangoPort = 8529
  val TestDefaultArangoPort = 18529

  val arangoContainer: DockerContainer = configureDockerContainer("docker.arango")

  abstract override def dockerContainers: List[DockerContainer] = arangoContainer :: super.dockerContainers
}
