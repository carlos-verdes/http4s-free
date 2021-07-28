/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import avokka.arangodb.ArangoCollection
import avokka.arangodb.models.CollectionCreate
import avokka.arangodb.models.CollectionCreate.KeyOptions
import avokka.arangodb.fs2.Arango
import avokka.arangodb.protocol.{ArangoError, ArangoResponse}
import avokka.arangodb.types.{CollectionName, DocumentKey}
import avokka.velocypack._
import cats.effect.{IO, Resource}
import cats.implicits.catsSyntaxApplicativeId
import cats.~>
import org.http4s.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import org.log4s.getLogger

package object arango {

  import api._
  import http.resource._

  private val logger = getLogger

  // scalastyle:off
  def arangoResourceInterpreter(clientR: Resource[IO, Arango[IO]]): ResourceAlgebra ~> IO = new (ResourceAlgebra ~> IO) {

    def withCollection[A](collectionName: String)(body: ArangoCollection[IO] => IO[A]): IO[A] = {
      clientR.use(client => {

        val collection = client.db.collection(CollectionName(collectionName))

        collection.info()
            .handleErrorWith {
              case ArangoError.Response(ArangoResponse.Header(_, _, 404, _), _) =>
                logger.info(s"""collection $collectionName doesn't exist, creating new one""".stripMargin)
                val colOptions = (c: CollectionCreate) => c.copy(keyOptions = Some(KeyOptions(allowUserKeys = Some(true))))
                collection.create(colOptions).handleErrorWith {
                  case ArangoError.Response(ArangoResponse.Header(_, _, 409, _), _) =>
                    logger.info(s"2 threads creating same collection: $collectionName, ignoring error")
                    collection.info()
                }
            }
            .flatMap(_ => body(collection))
      })
    }

    override def apply[A](op: ResourceAlgebra[A]): IO[A] = (op match {
      case Store(resourceUri, r, ser, deser) =>

        implicit val serializer: VPackEncoder[A] = ser.asInstanceOf[VPackEncoder[A]]
        implicit val deserializer: VPackDecoder[A] = deser.asInstanceOf[VPackDecoder[A]]
        val document: A = r.asInstanceOf[A]

        resourceUri.path match {
          case Root / collection =>
            withCollection(collection)(_.documents.insert(document = document, returnNew = true))
                .map(d => RestResource(uri"/" / collection / d.body._key.toString, d.body.`new`.get).resultOk)
          case Root / collection / id =>

            val docWithKey = (serializer.encode(document) match {
              case v: VObject => v.updated("_key", id)
              case any => any
            })

            withCollection(collection)(_.documents.insert(document = docWithKey, overwrite = true, returnNew = true))
                .map(resp => deserializer.decode(resp.body.`new`.get) match {
                  case Left(error) => arangoErrorToApiResult[RestResource[A]](error)
                  case Right(value) => RestResource(uri"/" / collection / resp.body._key.toString, value).resultOk
                })
        }

      case Fetch(resourceUri, deser) =>

        implicit val deserializer: VPackDecoder[A] = deser.asInstanceOf[VPackDecoder[A]]

        resourceUri.path match {

          case Root / collection / id =>
            withCollection(collection)(_.document(DocumentKey(id)).read())
                .map(d => RestResource(resourceUri, d.body).resultOk)
        }
    }).handleErrorWith(t => arangoErrorToApiResult(t).pure[IO]).map(_.asInstanceOf[A])
  }

  def arangoErrorToApiResult[A](t: Throwable): ApiResult[A] = t match {
    case ArangoError.Response(header, _) =>
      (header.responseCode match {
        case 400 => RequestFormatError(cause = Some(t))
        case 404 => ResourceNotFoundError(Some(t))
        case 409 => ConflictError(Some(t))
      }).resultError[A]
  }
}
