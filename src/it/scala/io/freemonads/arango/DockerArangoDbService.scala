/*
 * TODO: License goes here!
 */


package io.freemonads.arango

import scala.concurrent.duration._

import com.whisk.docker.{DockerContainer, DockerKit, DockerPortMapping, DockerReadyChecker}

trait DockerArangoDbService extends DockerKit {

  val DefaultArangoPort = 8529
  val TestDefaultArangoPort = 18529

  val arangoContainer: DockerContainer = DockerContainer("arangodb/arangodb:3.7.10")
      .withPortMapping(DefaultArangoPort -> DockerPortMapping(Some(TestDefaultArangoPort)))
      .withEnv("ARANGO_ROOT_PASSWORD=rootpassword")
      .withReadyChecker(
        DockerReadyChecker
            .HttpResponseCode(DefaultArangoPort, "/", Some("0.0.0.0"))
            .within(100.millis)
            .looped(20, 1250.millis))

  abstract override def dockerContainers: List[DockerContainer] =
    arangoContainer :: super.dockerContainers
}
