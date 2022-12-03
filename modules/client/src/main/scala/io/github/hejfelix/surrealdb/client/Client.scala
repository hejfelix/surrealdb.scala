package io.github.hejfelix.surrealdb.client

import cats.effect.kernel.{Async, Ref, Resource, Sync}
import cats.effect.std.{Console, Dispatcher, Queue, Semaphore}
import cats.effect.syntax.all.*
import cats.effect.{Concurrent, Spawn, Temporal}
import cats.syntax.all.{*, given}
import cats.{Applicative, ApplicativeThrow, Functor, MonadThrow, Show, Traverse}
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef, Topic}
import io.github.hejfelix.surrealdb.*
import io.github.hejfelix.surrealdb.WebsocketConnection.{Fs2Streams, ShowCompact}
import sttp.capabilities.{Streams, WebSockets}
import sttp.client3.SttpBackend
import sttp.model.Uri
import org.legogroup.woof.Logger

import scala.concurrent.duration.*

case class ClientConfiguration(auth: Authentication, host: Uri, namespace: String, database: String)
case class Authentication(user: String, password: String)

private class Client[F[_]: Async: ApplicativeThrow, J](
    configuration: ClientConfiguration,
    outgoingTopic: Topic[F, (RpcRequestId, RpcCommand[J])],
    callbacks: Ref[F, Map[RpcRequestId, RpcResponse[J] => F[Unit]]],
    counter: Ref[F, RpcRequestId]
):

  def send(rpcCommand: RpcCommand[J]): F[RpcResponse.Result[J]] =
    for
      nextRequestId <- counter.getAndUpdate(_.increment)
      _             <- outgoingTopic.publish1(nextRequestId -> rpcCommand)
      a <- Async[F].async[RpcResponse.Result[J]] { onComplete =>
        val completer: RpcResponse[J] => F[Unit] = _ match
          case r @ RpcResponse.Result(id, result) => Sync[F].delay(onComplete(r.asRight))
          case e @ RpcResponse.Error(id, error)   => Sync[F].delay(onComplete(Exception(e.toString).asLeft))

        callbacks.update(_ + (nextRequestId -> completer)).as(None)
      }
    yield a

  def create(thing: Thing, data: J): F[RpcResponse.Result[J]] = send(RpcCommand.Create(thing, data))

  def delete(thing: Thing): F[RpcResponse.Result[J]] = send(RpcCommand.Delete(thing))

  def use(namespace: String, database: String): F[RpcResponse.Result[J]] = send(RpcCommand.Use(namespace, database))

  def info: F[RpcResponse.Result[J]] = send(RpcCommand.Info)

  def let(key: String, value: String): F[RpcResponse.Result[J]] = send(RpcCommand.Let(key, value))

  def select(thing: Thing): F[RpcResponse.Result[J]] = send(RpcCommand.Select(thing))

  def query[A](query: String, variables: Map[String, String])(using
      JsonDecoder[List[QueryResult[A]]],
      Show[J]
  ): F[List[QueryResult[A]]] =
    send(RpcCommand.Query(query, variables))
      .flatMap { r =>
        summon[JsonDecoder[List[QueryResult[A]]]].decode(r.result.show).leftMap(Exception(_)).liftTo
      }

  def singleQuery[A](query: String, variables: Map[String, String])(using
      JsonDecoder[List[QueryResult[A]]],
      Show[J]
  ): F[QueryResult[A]] = this.query[A](query, variables).map(_.head)

end Client

object Client:

  def make[F[_]: Async: Logger, S, J](
      configuration: ClientConfiguration,
      s: Fs2Streams[F, S],
      backend: SttpBackend[F, S & WebSockets]
  )(using JsonEncoder[RpcRequest[J], J], JsonDecoder[RpcResponse[J]], ShowCompact[J]): Resource[F, Client[F, J]] =
    for
      callbacks      <- Resource.eval(Ref[F].of(Map.empty[RpcRequestId, RpcResponse[J] => F[Unit]]))
      topic          <- Resource.eval(Topic[F, (RpcRequestId, RpcCommand[J])])
      outgoingStream <- topic.subscribeAwaitUnbounded
      counter        <- Resource.eval(Ref[F].of(RpcRequestId("0")))
      _ <- Resource.make(
        WebsocketConnection
          .connect[F, S, J](
            configuration.host,
            s,
            incoming =>
              val callbackStream = incoming.evalTap { r =>
                callbacks.get.map(m => m(r.id)).flatMap(f => f(r))
              }
              outgoingStream.concurrently(callbackStream)
          )
          .send(backend)
          .start
      )(_.cancel)
      client = Client(configuration, topic, callbacks, counter)
      _ <- Resource.eval(client.send(RpcCommand.SignIn(configuration.auth.user, configuration.auth.password)))
      _ <- Resource.eval(client.send(RpcCommand.Use(configuration.namespace, configuration.database)))
    yield client
end Client
