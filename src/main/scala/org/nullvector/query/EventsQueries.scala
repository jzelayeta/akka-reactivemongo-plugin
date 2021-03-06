package org.nullvector.query

import akka.NotUsed
import akka.persistence.query.{EventEnvelope, NoOffset, Offset}
import akka.stream.scaladsl.{Flow, Source}
import org.nullvector.Fields
import reactivemongo.akkastream.cursorProducer
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.{BSONDocument, BSONObjectID}

import scala.concurrent.Future

trait EventsQueries
  extends akka.persistence.query.scaladsl.EventsByTagQuery
    with akka.persistence.query.scaladsl.EventsByPersistenceIdQuery
    with akka.persistence.query.scaladsl.CurrentEventsByTagQuery
    with akka.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery {

  this: ReactiveMongoScalaReadJournal =>

  val greaterOffsetOf: (Offset, Offset) => Offset = (leftOffset: Offset, rightOffset: Offset) => {
    (leftOffset, rightOffset) match {
      case (NoOffset, _) => rightOffset
      case (leftId: ObjectIdOffset, rightId: ObjectIdOffset) if leftId < rightId => rightOffset
      case _ => leftOffset
    }

  }

  override def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    Source
      .fromGraph(new PullerGraph[EventEnvelope, (Long, Long)](
        (fromSequenceNr, toSequenceNr),
        defaultRefreshInterval,
        e => (e.sequenceNr, Long.MaxValue),
        (_, r) => r,
        offset => currentEventsByPersistenceId(persistenceId, offset._1, offset._2)
      ))
      .flatMapConcat(identity)
  }

  override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    Source
      .fromFuture(rxDriver.journalCollection(persistenceId))
      .flatMapConcat(coll => buildFindEventsByIdQuery(coll, persistenceId, fromSequenceNr, toSequenceNr))
      .via(document2Envelope)
  }

  override def eventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = Source
    .fromGraph(new PullerGraph[EventEnvelope, Offset](
      offset, defaultRefreshInterval, _.offset, greaterOffsetOf, o => currentEventsByTag(tag, o))
    )
    .flatMapConcat(identity)

  override def currentEventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] = {
    currentEventsByTags(Seq(tag), offset)
  }


  def currentEventsByTags(tags: Seq[String], offset: Offset): Source[EventEnvelope, NotUsed] = {
    Source.fromFuture(rxDriver.journals())
      .mapConcat(identity)
      .groupBy(100, _.name)
      .flatMapConcat(coll => buildFindEventsByTagsQuery(coll, offset, tags))
      .mergeSubstreamsWithParallelism(100)
      .via(document2Envelope)
  }

  private def document2Envelope: Flow[BSONDocument, EventEnvelope, NotUsed] = {
    Flow[BSONDocument]
      .mapAsync(Runtime.getRuntime.availableProcessors()) { doc =>
        val event = doc.getAs[BSONDocument](Fields.events).get
        val rawPayload = event.getAs[BSONDocument](Fields.payload).get
        (event.getAs[String](Fields.manifest).get match {
          case Fields.manifest_doc => Future.successful(rawPayload)
          case manifest => serializer.deserialize(manifest, rawPayload)
        })
        .map(payload => EventEnvelope(
            ObjectIdOffset(doc.getAs[BSONObjectID]("_id").get),
            event.getAs[String](Fields.persistenceId).get,
            event.getAs[Long](Fields.sequence).get,
            payload,
          )
        )
      }
  }

  private def buildFindEventsByTagsQuery(collection: BSONCollection, offset: Offset, tags: Seq[String]) = {
    import collection.BatchCommands.AggregationFramework._
    val $1stMatch = Match(BSONDocument(Fields.tags -> BSONDocument("$all" -> tags)) ++ filterByOffset(offset))
    val $unwind = UnwindField(Fields.events)
    val $2ndMatch = Match(BSONDocument(s"${Fields.events}.${Fields.tags}" -> BSONDocument("$all" -> tags)))

    collection
      .aggregateWith[BSONDocument]()(_ => ($1stMatch, List($unwind, $2ndMatch)))
      .documentSource()
  }

  private def buildFindEventsByIdQuery(collection: BSONCollection, persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long) = {
    import collection.BatchCommands.AggregationFramework._
    val $match = Match(BSONDocument(
      Fields.persistenceId -> persistenceId,
      Fields.from_sn -> BSONDocument("$gt" -> fromSequenceNr),
      Fields.to_sn -> BSONDocument("$lte" -> toSequenceNr)
    ))
    val $unwind = UnwindField(Fields.events)
    val $sort = Sort(Ascending(s"${Fields.events}.${Fields.sequence}"))

    collection
      .aggregateWith[BSONDocument]()(_ => ($match, List($unwind, $sort)))
      .documentSource()
  }

}
