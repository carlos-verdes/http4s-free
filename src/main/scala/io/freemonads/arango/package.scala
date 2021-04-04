/*
 * TODO: License goes here!
 */


package io.freemonads

import avokka.arangodb.fs2.Arango
import avokka.arangodb.protocol.ArangoError
import avokka.arangodb.types.{CollectionName, DocumentKey}
import avokka.velocypack._
import cats.effect.{IO, Resource}
import cats.implicits.catsSyntaxApplicativeId
import cats.~>
import org.http4s.dsl.Http4sDsl
import org.log4s.getLogger

package object arango {

  import http.api._
  import http.resource._

  private val logger = getLogger

  // scalastyle:off
  def arangoResourceInterpret(clientR: Resource[IO, Arango[IO]]): ResourceAlgebra ~> IO = new (ResourceAlgebra ~> IO) {

    override def apply[A](op: ResourceAlgebra[A]): IO[A] = op match {
      case Store(resourceUri, r, ser, deser) =>

        implicit val serializer: VPackEncoder[A] = ser.asInstanceOf[VPackEncoder[A]]
        implicit val deserializer: VPackDecoder[A] = deser.asInstanceOf[VPackDecoder[A]]
        val document: A = r.asInstanceOf[A]

        logger.info(s"Storing $resourceUri with model $r")

        val dsl = new Http4sDsl[IO]{}
        import dsl._

        Path(resourceUri.path) match {
          case Root / collection =>
            logger.info(s"path matching collection: $collection and id")

            clientR.use(client =>
              client
                  .db
                  .collection(CollectionName(collection))
                  .insert(document = document, returnNew = true)
                  .map(_.body.`new`.getOrElse(()).asInstanceOf[A].resultOk)
                  .handleErrorWith(arangoErrorToApiResult)
                  .map(_.asInstanceOf[A]))
        }

      case Fetch(resourceUri, deser) =>

        implicit val deserializer: VPackDecoder[A] = deser.asInstanceOf[VPackDecoder[A]]

        logger.info(s"Retrieveing $resourceUri from ArangoDB")

        val dsl = new Http4sDsl[IO]{}
        import dsl._

        Path(resourceUri.path) match {

          case Root / collection / id =>
            logger.info(s"path matching collection: $collection and id: $id")

            clientR.use(client =>
              client
                  .db
                  .collection(CollectionName(collection))
                  .document(DocumentKey(id))
                  .read()
                  .map(_.body.resultOk)
                  .handleErrorWith(arangoErrorToApiResult)
                  .map(_.asInstanceOf[A]))
        }
    }
  }

  def arangoErrorToApiResult[R, A](t: Throwable): IO[ApiResult[A]] = t match {
    case ArangoError.Response(header, _) => (header.responseCode match {
      case 400 => RequestFormatError(cause = Some(t))
      case 404 => ResourceNotFoundError(Some(t))
      case 409 => ConflictError(Some(t))
  }).resultError[A].pure[IO]}
}
