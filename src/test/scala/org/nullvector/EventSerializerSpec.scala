package org.nullvector

import akka.actor.{ActorRef, ActorSystem, ExtendedActorSystem, Kill, Props}
import akka.persistence.journal.{EventSeq, Tagged}
import akka.persistence.{PersistentActor, PersistentRepr}
import akka.testkit.{ImplicitSender, TestKit}
import org.nullvector.EventSerializerSpec.{AnEvent, AnEventEventAdapter, OtherLegacyEvent, SomeLegacyEvent}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers, WordSpecLike}
import reactivemongo.bson.{BSONDocument, BSONDocumentHandler, Macros}
import util.AutoRestartFactory

import scala.collection.immutable._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

class EventSerializerSpec() extends TestKit(ActorSystem("ReactiveMongoPlugin")) with ImplicitSender
  with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import system.dispatcher

  private val serializer = ReactiveMongoEventSerializer(system)
  serializer.addEventAdapter(new AnEventEventAdapter)
  serializer.loadAkkaAdaptersFrom("custom.akka.persistent.adapters")

  "EventSerializer" should "serialize an Event" in {
    val eventualTuple = serializer.serialize(PersistentReprProbe(AnEvent("Hello World"), "AnEvent"))
    val document = Await.result(eventualTuple, 1.second)._1.payload.asInstanceOf[BSONDocument]

    document.getAs[String]("string").get shouldBe ("Hello World")
  }

  it should "dserialize an Event" in {
    val eventualEvent = serializer.deserialize("AnEvent", BSONDocument("string" -> "Charlie")).map(_.asInstanceOf[AnEvent])
    val anEvent = Await.result(eventualEvent, 1.second)

    anEvent.string shouldBe ("Charlie")
  }

  it should "serialize an AkkaEvent" in {
    val eventualTuple = serializer.serialize(PersistentReprProbe(SomeLegacyEvent("John", "Coltrane"), "SomeLegacyEvent"))
    val document = Await.result(eventualTuple, 1.second)._1.payload.asInstanceOf[BSONDocument]

    document.getAs[String]("firstName").get shouldBe ("John")
    document.getAs[String]("lastName").get shouldBe ("Coltrane")
  }

  it should "dserialize an AkkaEvent" in {
    val eventualEvent = serializer.deserialize("SomeLegacyEvent", BSONDocument("firstName" -> "Charlie", "lastName" -> "Parker")).map(_.asInstanceOf[SomeLegacyEvent])
    val someLegacyEvent = Await.result(eventualEvent, 1.second)

    someLegacyEvent.firstName shouldBe ("Charlie")
    someLegacyEvent.lastName shouldBe ("Parker")
  }

  it should "dserialize other AkkaEvent" in {
    val eventualEvent = serializer.deserialize("OtherLegacyEvent", BSONDocument("firstName" -> "Charlie", "lastName" -> "Parker")).map(_.asInstanceOf[OtherLegacyEvent])
    val someLegacyEvent = Await.result(eventualEvent, 1.second)

    someLegacyEvent.firstName shouldBe ("Charlie")
    someLegacyEvent.lastName shouldBe ("Parker")
  }

  it should "serialize other AkkaEvent" in {
    val eventualTuple = serializer.serialize(PersistentReprProbe(OtherLegacyEvent("John", "Coltrane"), "OtherLegacyEvent"))
    val document = Await.result(eventualTuple, 1.second)._1.payload.asInstanceOf[BSONDocument]

    document.getAs[String]("firstName").get shouldBe ("John")
    document.getAs[String]("lastName").get shouldBe ("Coltrane")
  }

  it should "try to dserialize with non registered adapter" in {
    val eventualEvent = serializer.deserialize("NonRegisteredEvent", BSONDocument("firstName" -> "Charlie", "lastName" -> "Parker")).map(_.asInstanceOf[OtherLegacyEvent])
    an[Exception] should be thrownBy Await.result(eventualEvent, 1.second)
  }

  it should "serialize other AkkaEvent with Tag" in {
    val eventualTuple = serializer.serialize(PersistentReprProbe(Tagged(OtherLegacyEvent("John", "Coltrane"), Set("tag")), "OtherLegacyEvent"))
    val document = Await.result(eventualTuple, 1.second)._1.payload.asInstanceOf[BSONDocument]
    document.getAs[String]("firstName").get shouldBe ("John")
    document.getAs[String]("lastName").get shouldBe ("Coltrane")

  }

  it should "try serialize non registered event" in {
    val eventualTuple = serializer.serialize(PersistentReprProbe(BigDecimal("678"), "BogDecimalEvent"))
    an[Exception] should be thrownBy Await.result(eventualTuple, 1.second)
  }

  override def afterAll: Unit = {
    shutdown()
  }

  case class PersistentReprProbe(payload: Any, manifest: String) extends PersistentRepr {

    override def persistenceId: String = ???

    override def sequenceNr: Long = ???

    override def writerUuid: String = ???

    override def withPayload(payload: Any): PersistentRepr = copy(payload = payload)

    override def withManifest(manifest: String): PersistentRepr = copy(manifest = manifest)

    override def deleted: Boolean = ???

    override def sender: ActorRef = ???

    override def update(sequenceNr: Long, persistenceId: String, deleted: Boolean, sender: ActorRef, writerUuid: String): PersistentRepr = ???
  }

}

object EventSerializerSpec {

  case class AnEvent(string: String)

  case class SomeLegacyEvent(firstName: String, lastName: String)

  case class OtherLegacyEvent(firstName: String, lastName: String)

  class AnEventEventAdapter extends EventAdapter[AnEvent] {
    override val manifest: String = "AnEvent"

    override def tags(payload: Any): Set[String] = Set("tag_1", "tag_2")

    private val anEventMapper: BSONDocumentHandler[AnEvent] = Macros.handler[AnEvent]

    override def payloadToBson(payload: AnEvent): BSONDocument = anEventMapper.write(payload)

    override def bsonToPayload(doc: BSONDocument): AnEvent = anEventMapper.read(doc)
  }

  class SomeAkkaEventAdapter(system: ExtendedActorSystem) extends akka.persistence.journal.EventAdapter {
    private val mapper: BSONDocumentHandler[SomeLegacyEvent] = Macros.handler[SomeLegacyEvent]

    override def fromJournal(event: Any, manifest: String): EventSeq = {
      val document = event.asInstanceOf[BSONDocument]
      EventSeq.single(mapper.read(document))
    }

    override def manifest(event: Any): String = "SomeLegacyEvent"

    override def toJournal(event: Any): Any = {
      val legacyEvent = event.asInstanceOf[SomeLegacyEvent]
      mapper.write(legacyEvent)
    }
  }

  class OtherAkkaEventAdapter() extends akka.persistence.journal.EventAdapter {
    private val mapper: BSONDocumentHandler[OtherLegacyEvent] = Macros.handler[OtherLegacyEvent]

    override def fromJournal(event: Any, manifest: String): EventSeq = {
      val document = event.asInstanceOf[BSONDocument]
      EventSeq.single(mapper.read(document))
    }

    override def manifest(event: Any): String = "OtherLegacyEvent"

    override def toJournal(event: Any): Any = {
      val legacyEvent = event.asInstanceOf[OtherLegacyEvent]
      mapper.write(legacyEvent)
    }
  }

}
