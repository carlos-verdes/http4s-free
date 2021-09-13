/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package avokka.arangodb.models

import avokka.arangodb.models.GraphInfo.GraphEdgeDefinition
import avokka.velocypack.VPackEncoder

case class EdgeDefinitionReplace(
    collection: String,
    from: List[String],
    to: List[String]
) {
  def parameters = Map()
}

object EdgeDefinitionReplace {

  def apply(ged: GraphEdgeDefinition): EdgeDefinitionReplace = EdgeDefinitionReplace(ged.collection, ged.from, ged.to)

  implicit val encoderEdgeDefinitionCreate: VPackEncoder[EdgeDefinitionCreate] = VPackEncoder.gen
}
