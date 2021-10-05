/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads.tagless
package interpreters

import avokka.arangodb.fs2.Arango
import avokka.arangodb.models.CollectionCreate.KeyOptions
import avokka.arangodb.models.GraphInfo.{GraphEdgeDefinition, GraphRepresentation}
import avokka.arangodb.models.{CollectionInfo, CollectionType}
import avokka.arangodb.protocol.{ArangoClient, ArangoError, ArangoResponse}
import avokka.arangodb.types.{CollectionName, DocumentKey}
import avokka.arangodb.{ArangoCollection, ArangoGraph}
import avokka.velocypack.{VObject, VPack, VPackDecoder, VPackEncoder, VPackError}
import cats.MonadThrow
import cats.effect.{IO, Resource}
import cats.implicits._
import org.http4s.Uri.Path.Root
import org.http4s.dsl.io./
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{Status, Uri}
import org.log4s.getLogger

object arangoStore {

  import io.freemonads.error._
  import http._
  import httpStore._

  type ArangoStoreAlgebra[F[_]] = HttpStoreAlgebra[F, VPackEncoder, VPackDecoder]

  val RESOURCE_RELS_GRAPH = "resource-rels"

  private val logger = getLogger

  case class ColKeyOp(collectionName: CollectionName, key: Option[DocumentKey])
  case class ColKey(collectionName: CollectionName, key: DocumentKey)


  object ColKey {

    def fromUriOp[F[_]](uri: Uri)(implicit F: MonadThrow[F]): F[ColKeyOp] =
      uri.path match {
        case Root / col / key => F.pure(ColKeyOp(CollectionName(col), DocumentKey(key).some))
        case Root / col => F.pure(ColKeyOp(CollectionName(col), None))
        case _ => F.raiseError(RequestFormatError(s"Url not supported for storage: $uri".some))
      }

    def fromUri[F[_]](uri: Uri)(implicit F: MonadThrow[F]): F[ColKey] =
      for {
        ColKeyOp(collectionName, keyOp) <- ColKey.fromUriOp[F](uri)
        key <- F.fromOption(keyOp, RequestFormatError(s"Uri not supported to fetch docs: $uri".some))
      } yield ColKey(collectionName, key)
  }

  def buildDocument[R](
      body: R,
      keyOp: Option[DocumentKey])(
      implicit E: VPackEncoder[R]): VPack = {

    val baseDocument = E.encode(body)

    (E.encode(body), keyOp) match {
      case (doc: VObject, Some(key)) => doc.updated(DocumentKey.key, key)
      case _ => baseDocument
    }
  }

  def buildEdgeDoc(key: String, leftUri: Uri, rightUri: Uri): VPack =
    VObject
        .empty
        .updated("_key", key)
        .updated("_from", leftUri.path.toString().substring(1))
        .updated("_to", rightUri.path.toString().substring(1))

  def handleErrors[F[_], R](arangoError: Throwable)(implicit F: MonadThrow[F]): F[R] =
    arangoError match {
      case ArangoError.Response(ArangoResponse.Header(_, _, Status.BadRequest.code, _), _) =>
        F.raiseError(RequestFormatError(cause = arangoError.some))
      case ArangoError.Response(ArangoResponse.Header(_, _, Status.NotFound.code, _), _) =>
        F.raiseError(ResourceNotFoundError(cause = arangoError.some))
      case ArangoError.Response(ArangoResponse.Header(_, _, Status.Conflict.code, _), _) =>
        F.raiseError(ConflictError(cause = arangoError.some))
      case vPackError: VPackError =>
        F.raiseError(RequestFormatError(s"Error coding/decoding VPack".some, vPackError.some))
    }

  implicit class ArangoResponseOps[F[_], R](arangoResponse: F[ArangoResponse[R]])(implicit F: MonadThrow[F]) {

    def handleResponse(): F[R] = arangoResponse.map(_.body).handleErrorWith(handleErrors[F, R])
  }

  def createCollection[F[_]: MonadThrow](
      collection: ArangoCollection[F],
      collectionType: CollectionType = CollectionType.Document): F[CollectionInfo] =
    collection
        .create(_.copy(keyOptions = KeyOptions(allowUserKeys = true.some).some, `type` = collectionType))
        .handleResponse()
        .inConflict(collection.info().handleResponse())

  def createEdge[F[_]: MonadThrow](collection: ArangoCollection[F]): F[CollectionInfo] =
    createCollection(collection, CollectionType.Edge)


  implicit class ArangoStoreInterpreter(
      clientR: Resource[IO, Arango[IO]]) extends HttpStoreAlgebra[IO, VPackEncoder, VPackDecoder] {

    override def store[R](uri: Uri, resource: R)(implicit S: VPackEncoder[R], D: VPackDecoder[R]): IO[HttpResource[R]] =
      clientR.use { client => upsertResource(client, uri, resource) }

    override def fetch[R](resourceUri: Uri)(implicit deserializer: VPackDecoder[R]): IO[HttpResource[R]] =
      clientR.use { client =>
        for {
          ColKey(collectionName, key) <- ColKey.fromUri[IO](resourceUri)
          collection = client.db.collection(collectionName)
          document <- collection.document(key).read[R]().handleResponse()
        } yield HttpResource(resourceUri, document)
      }

    override def linkResources(leftUri: Uri, rightUri: Uri, relType: String): IO[Unit] =
      clientR.use { client =>

        implicit val _client = client

        for {
          ColKey(leftCol, leftKey) <- ColKey.fromUri[IO](leftUri)
          ColKey(rightCol, rightKey) <- ColKey.fromUri[IO](rightUri)
          edge = client.db.collection(CollectionName(relType))
          _ <- edge.info().handleResponse().ifNotFound(createEdge(edge))
          edgeDoc = buildEdgeDoc(leftKey.repr + "-" + rightKey.repr, leftUri, rightUri)
          _ <- edge.documents.insert(document = edgeDoc, overwrite = true, returnNew = true).handleResponse()
          edgeDefinition = GraphEdgeDefinition(relType, List(leftCol.repr), List(rightCol.repr))
          _ <- updateGraphDefinition(edgeDefinition)
        } yield ()
      }
  }

  def updateGraphDefinition[F[_]](
      ed: GraphEdgeDefinition)(
      implicit client: ArangoClient[F],
      F: MonadThrow[F]): F[Unit] = {

    val graph = ArangoGraph(client.db.name, RESOURCE_RELS_GRAPH)

    for {
      edgeDefinitions <- getCreateGraph(graph).map(_.edgeDefinitions)
      _ <- {
        edgeDefinitions.filter(_.collection == ed.collection).headOption match {
          case Some(current) =>
            if ((current.from.intersect(ed.from) != ed.from) || (current.to.intersect(ed.to) != ed.to)) {
              logger.info(s"Update edge definition, adding: $ed, \ncurrent: $current")
              val from = ed.from.concat(current.from)
              val to = ed.to.concat(current.to)
              graph.replaceEdgeDefinition(GraphEdgeDefinition(ed.collection, from, to))
            } else {
              logger.info(s"all settle for edge definitions")
              ().pure[F]
            }
          case None =>
            logger.info(s"Creating edge definition first time: $ed")
            graph.addEdgeDefinition(ed)
        }

      }
    } yield ()
  }

  private def getCreateGraph[F[_]: MonadThrow, A](graph: ArangoGraph[F]): F[GraphRepresentation] = {
    graph.info().handleResponse().ifNotFound(graph.create().handleResponse()).map(_.graph)
  }

  private def upsertResource[R](
      client: Arango[IO],
      uri: Uri,
      resource: R)(
      implicit E: VPackEncoder[R],
      D: VPackDecoder[R]): IO[HttpResource[R]] = {
    for {
      ColKeyOp(collectionName, keyOp) <- ColKey.fromUriOp[IO](uri)
      doc = buildDocument(resource, keyOp)
      collection = client.db.collection(collectionName)
      _ <- collection.info().handleResponse().ifNotFound(createCollection(collection))
      stored <- collection.documents.insert(document = doc, overwrite = true, returnNew = true).handleResponse()
      newDoc <- IO.fromOption(stored.`new`)(RuntimeError(new IllegalAccessException("Not expected error").some))
      parsedDocument <- IO.fromEither(D.decode(newDoc)).handleErrorWith[R](handleErrors[IO, R])
    } yield HttpResource(uri"/" / collectionName.repr / stored._key.repr, parsedDocument)
  }
}
