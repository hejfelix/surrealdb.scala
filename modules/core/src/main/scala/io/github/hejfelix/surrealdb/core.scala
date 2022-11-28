package io.github.hejfelix.surrealdb

import cats.effect.std.Console
import cats.syntax.all.*
import cats.{Applicative, Show}
import fs2.{Chunk, Pipe, Stream}
import sttp.capabilities.*
import sttp.client3.*
import sttp.model.Uri
import sttp.ws.WebSocketFrame.{Binary, Data, Text}
import sttp.ws.{WebSocket, WebSocketFrame}

trait JsonEncoder[T, J]:
  def encode(t: T): J

trait JsonDecoder[T]:
  def decode(text: String): Either[String, T]

type RpcRequestId = RpcRequestId.Type
object RpcRequestId:
  def apply(s: String): Type = s
  opaque type Type = String

  extension (id: Type)
    def value: String   = id
    def increment: Type = (id.value.toLong + 1).toString

end RpcRequestId

case class RpcRequest[J](id: RpcRequestId, method: String, params: List[RpcParameter[J]])

case class RpcErrorBody(code: Int, message: String)

case class QueryResult[A](result: List[A], status: String, time: String)

enum RpcResponse[+J](val id: RpcRequestId):
  case Result(override val id: RpcRequestId, result: J)          extends RpcResponse[J](id)
  case Error(override val id: RpcRequestId, error: RpcErrorBody) extends RpcResponse(id)

type RpcParameter[Json] = String | Map[String, String] | Json | List[Map[String, String | Json]]

enum Thing:
  case Table(name: String)
  case Row(tableName: String, id: String)

  def asParameterString = this match
    case Thing.Table(name)        => name
    case Thing.Row(tableName, id) => s"$tableName:$id"

enum Patch[+J]:
  case Change(path: String, value: J)
  case Replace(path: String, value: J)
  case Add(path: String, value: J)
  case Remove(path: String)

  def asParameters: Map[String, String | J] = this match
    case Change(path, value)  => Map("op" -> "change", "path" -> path, "value" -> value)
    case Replace(path, value) => Map("op" -> "replace", "path" -> path, "value" -> value)
    case Add(path, value)     => Map("op" -> "add", "path" -> path, "value" -> value)
    case Remove(path)         => Map("op" -> "remove", "path" -> path)
end Patch

enum RpcCommand[+J]:
  case SignIn(user: String, pass: String)
  case Use(namespace: String, database: String)
  case Ping
  case Info
  case Update(thing: Thing, data: J)
  case Change(thing: Thing, data: J)
  case Select(thing: Thing)
  case Query(query: String, variables: Map[String, String])
  case Let(key: String, value: String)
  case Create(thing: Thing, data: J)
  case Delete(thing: Thing)
  case Modify(thing: Thing, patches: List[Patch[J]])

  def methodName = this match
    case SignIn(_, _) => "signin"
    case Use(_, _)    => "use"
    case Ping         => "ping"
    case Info         => "info"
    case _: Modify[?] => "modify"
    case _: Change[?] => "change"
    case _: Update[?] => "update"
    case _: Select[?] => "select"
    case _: Query[?]  => "query"
    case _: Let[?]    => "let"
    case _: Create[?] => "create"
    case _: Delete[?] => "delete"

  def parameters: List[RpcParameter[J]] = this match
    case SignIn(user, pass)       => List(Map("user" -> user, "pass" -> pass))
    case Use(namespace, database) => List(namespace, database)
    case Query(query, variables)  => List(query, variables)
    case Let(key, value)          => List(key, value)
    case Modify(thing, patches)   => List(thing.asParameterString, patches.map(_.asParameters))
    case Select(thing)            => List(thing.asParameterString)
    case Delete(thing)            => List(thing.asParameterString)
    case Create(thing, data)      => List(thing.asParameterString, data)
    case Update(thing, data)      => List(thing.asParameterString, data)
    case Change(thing, data)      => List(thing.asParameterString, data)
    case Ping | Info              => Nil

end RpcCommand

object WebsocketConnection:

  type Fs2Streams[F[_], S] = Streams[S] { type BinaryStream = Stream[F, Byte]; type Pipe[A, B] = fs2.Pipe[F, A, B] }

  def combinedTextFrames[F[_]: Applicative: Console]: Pipe[F, WebSocketFrame.Data[?], String] =
    _.collect { case tf: WebSocketFrame.Text => tf }
      .flatMap { tf =>
        if tf.finalFragment then Stream(tf.copy(finalFragment = false), tf.copy(payload = ""))
        else Stream(tf)
      }
      .split(_.finalFragment)
      .map(chunks => chunks.map(_.payload).toList.mkString)
      .evalTap(Console[F].println)

  def asRpcResponses[F[_]: Applicative: Console, J](using
      JsonDecoder[RpcResponse[J]]
  ): Pipe[F, WebSocketFrame.Data[?], RpcResponse[J]] =
    combinedTextFrames.andThen(
      _.map(summon[JsonDecoder[RpcResponse[J]]].decode)
        .evalTap(Console[F].println)
        .evalMapFilter {
          case Right(value) => value.some.pure
          case Left(value)  => Console[F].errorln(s"Failed to decode: ${value}").as(None)
        }
    )

  def wrap[F[_], J]: Pipe[F, (RpcRequestId, RpcCommand[J]), RpcRequest[J]] =
    _.map((id, command) => RpcRequest(RpcRequestId(id.toString), command.methodName, command.parameters))

  def connect[F[_]: Applicative: Console, S, J](
      host: Uri,
      s: Fs2Streams[F, S],
      pipe: fs2.Pipe[F, RpcResponse[J], (RpcRequestId, RpcCommand[J])]
  )(using
      JsonEncoder[RpcRequest[J], J],
      JsonDecoder[RpcResponse[J]],
      Show[J]
  ): RequestT[Identity, Unit, S & WebSockets] =
    println(s"Connecting to $host")
    val rpcPipe: fs2.Pipe[F, WebSocketFrame.Data[?], RpcResponse[J]] = asRpcResponses[F, J]
    val pip2: Stream[F, Data[?]] => Stream[F, WebSocketFrame] =
      rpcPipe
        .andThen(pipe)
        .andThen(wrap[F, J])
        .andThen(_.map(summon[JsonEncoder[RpcRequest[J], J]].encode).map(txt =>
          println(s">>>> WIRE SEND: $txt")
          WebSocketFrame.Text(txt.show, true, None)
        ))
    basicRequest.post(host).response(asWebSocketStreamAlways(s)(pip2))
  end connect

end WebsocketConnection
