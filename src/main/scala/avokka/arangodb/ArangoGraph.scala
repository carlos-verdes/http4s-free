/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package avokka.arangodb

import avokka.arangodb.models.GraphInfo
import avokka.arangodb.models.GraphInfo.GraphRepresentation
import avokka.arangodb.protocol.{ArangoClient, ArangoResponse}
import avokka.arangodb.types.DatabaseName
import cats.Functor

case class GraphResponse(graph: GraphRepresentation)

trait ArangoGraph[F[_]] {

  /** graph name */
  def name: String

  /**
   * Create the named graph
   *
   * @param setup modify creation options
   * @return named graph information
   */
//  def create(setup: GraphCreate => GraphCreate = identity): F[ArangoResponse[GraphInfo]]

  /**
   * Return information about collection
   *
   * @return collection information
   */
  def info(): F[ArangoResponse[GraphInfo.Response]]

  /**
   * Load collection
   *
   * @return collection information
   */
  //def load(): F[ArangoResponse[CollectionInfo]]

  /**
   * Unload collection
   *
   * @return collection information
   */
  //def unload(): F[ArangoResponse[CollectionInfo]]
}

object ArangoGraph {

  def apply[F[_]: ArangoClient: Functor](database: DatabaseName, _name: String): ArangoGraph[F] =
    new ArangoGraph[F] {
      override def name: String = _name

      private val path: String = "/_api/gharial/" + name

      override def info(): F[ArangoResponse[GraphInfo.Response]] =
        GET(database, path).execute
    }

  implicit class ArangoDatabaseGrapOps[F[_]: ArangoClient: Functor](db: ArangoDatabase[F]) {

    def graph(graphName: String): ArangoGraph[F] = ArangoGraph[F](db.name, graphName)
  }
}
