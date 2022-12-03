package io.github.hejfelix.surrealdb.circe

import cats.syntax.all.*
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.github.hejfelix.surrealdb.*
import io.github.hejfelix.surrealdb.WebsocketConnection.ShowCompact

import scala.annotation.nowarn

object CirceSupport:

  given ShowCompact[Json] = _.noSpaces

  given Decoder[Null] = cursor =>
    cursor.as[Json].flatMap {
      case Json.Null => Right(null: Null)
      case _         => DecodingFailure("failed to decode Null", Nil).asLeft
    }

  @nowarn
  given Encoder[RpcParameter[Json]] = x =>
    x match
      case s: String                    => s.asJson
      case j: Json                      => j
      case m: Map[String, String]       => m.asJson
      case l: List[Map[String, String]] => l.asJson

  given Encoder[RpcRequestId] = r => Json.fromString(r.toString)
  given Decoder[RpcRequestId] = Decoder[String].map(RpcRequestId.apply)

  given Encoder[RpcRequest[Json]]         = deriveEncoder
  given Decoder[RpcErrorBody]             = deriveDecoder
  given Decoder[RpcResponse.Result[Json]] = deriveDecoder
  given Decoder[RpcResponse.Error]        = deriveDecoder
  given Decoder[RpcResponse[Json]] =
    Decoder[RpcResponse.Result[Json]].or(Decoder[RpcResponse.Error].widen[RpcResponse[Json]])

  given JsonEncoder[RpcRequest[Json], Json] = (x: RpcRequest[Json]) => summon[Encoder[RpcRequest[Json]]](x)

  given [T: Decoder]: Decoder[QueryResult[T]] = deriveDecoder

  given [T](using Decoder[T]): JsonDecoder[T] = s => decode[T](s).leftMap(_.toString)

end CirceSupport
