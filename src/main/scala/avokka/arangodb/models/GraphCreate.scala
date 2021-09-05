/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package avokka.arangodb
package models

import avokka.arangodb.models.GraphInfo.GraphEdgeDefinition
import avokka.arangodb.types.CollectionName
import avokka.velocypack.VPackEncoder


final case class GraphCreate(
    name: String,
    edgeDefinitions: List[GraphEdgeDefinition],
    orphanCollections: List[CollectionName],
    isSmart: Boolean = false,
    isDisjoint: Boolean = false,
    options: Option[GraphCreate.Options] = None
) {
  def parameters = Map()
}

object GraphCreate { self =>

  final case class Options(
      smartGraphAttribute: Option[String] = None,
      numberOfShards: Long = 1,
      replicationFactor: Long = 1,
      writeConcern: Option[Long] = None
  )

  object Options {
    implicit val encoder: VPackEncoder[Options] = VPackEncoder.gen
  }

  implicit val encoder: VPackEncoder[CollectionCreate] = VPackEncoder.gen
}
