package io.github.hejfelix.surrealdb.client

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all.*
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import io.github.hejfelix.surrealdb.*
import io.github.hejfelix.surrealdb.WebsocketConnection.ShowCompact
import io.github.hejfelix.surrealdb.circe.CirceSupport.given
import io.github.hejfelix.surrealdb.client.ClientSuite.{expect, test}
import org.legogroup.woof.*
import org.legogroup.woof.Logger.withLogContext
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.UriContext
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import weaver.IOSuite

import scala.util.Random

class MyColorPrinter extends ColorPrinter:
  override def toPrint(
      epochMillis: EpochMillis,
      level: LogLevel,
      info: LogInfo,
      message: String,
      context: List[(String, String)]
  ): String =
    super.toPrint(
      epochMillis,
      level,
      info.copy(enclosingClass = info.enclosingClass.copy(lineLength = 40)),
      message,
      context
    )
end MyColorPrinter

object ClientSuite extends IOSuite:

  val peopleTable = Thing.Table("people")

  given Printer = MyColorPrinter()
  given Filter  = Filter.everything

  override type Res = Client[IO, Json]
  override def sharedResource: Resource[IO, Client[IO, Json]] = for
    given Logger[IO] <- Resource.eval(DefaultLogger.makeIo(Output.fromConsole))
    backend          <- HttpClientFs2Backend.resource[IO]()
    client           <- Client.make(configuration, s, backend)
    _                <- Resource.make(client.create(peopleTable, Json.obj()))(_ => client.delete(peopleTable).void)
  yield client

  val s: Fs2Streams[cats.effect.IO] = Fs2Streams[IO]

  case class Person(name: String, age: Int, cool: Boolean) derives Encoder.AsObject
  given Decoder[Person] = deriveDecoder

  lazy val configuration =
    ClientConfiguration(Authentication("root", "root"), uri"ws://localhost:8000/rpc", "test", "test")

  test("delete") { client =>

    val felix = Person("Felix", 35, true).asJson
    val id    = Random.nextInt(9999).toString

    val expected = Json.arr(felix.deepMerge(Json.obj("id" := s"people:$id")))

    for
      createResult <- client.send(RpcCommand.Create(Thing.Row("people", id), felix))
      deleteResult <- client.delete(Thing.Row("people", id))
    yield expect.eql(Json.arr(), deleteResult.result) and expect.eql(expected, createResult.result)

  }

  test("query") { client =>

    val people = List(
      Person("Felix", 35, true),
      Person("Hannibal", 0, true),
      Person("Louise", 34, true)
    )

    val randomId              = Random.nextInt(99999)
    val newTable: Thing.Table = Thing.Table(s"querypeople$randomId")

    for
      _           <- people.traverse(person => client.create(newTable, person.asJson))
      queryResult <- client.singleQuery[Person]("SELECT * FROM type::table($tb);", Map("tb" -> newTable.name))
      names = queryResult.result.map(_.name)
      _ <- client.delete(newTable)
    yield expect.eql(List("Felix", "Hannibal", "Louise").sorted, names.sorted)

  }

end ClientSuite
