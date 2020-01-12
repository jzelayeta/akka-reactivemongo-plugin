package org.nullvector.query

import akka.NotUsed
import akka.persistence.query.{NoOffset, Offset}
import akka.stream.scaladsl.Source
import org.nullvector.Fields
import org.nullvector.query.PersistenceIdsQueries.PersistenceId
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.bson.{BSONDocument, BSONObjectID}
import reactivemongo.akkastream.{State, cursorProducer}

import scala.concurrent.Future

object PersistenceIdsQueries {

  case class PersistenceId(persistenceId: String, offset: ObjectIdOffset)

}

trait PersistenceIdsQueries
  extends akka.persistence.query.scaladsl.PersistenceIdsQuery
    with akka.persistence.query.scaladsl.CurrentPersistenceIdsQuery {

  this: ReactiveMongoScalaReadJournal =>

  private val amountOfCores: Int = Runtime.getRuntime.availableProcessors()

  override def persistenceIds(): Source[String, NotUsed] = {
    Source.fromGraph(new PullerGraph[PersistenceId, Offset](
      NoOffset,
      defaultRefreshInterval,
      _.offset,
      greaterOffsetOf,
      o => currentPersistenceIds(o)
    ))
      .flatMapConcat(identity)
      .map(_.persistenceId)
  }

  override def currentPersistenceIds(): Source[String, NotUsed] = {
    currentPersistenceIds(NoOffset).map(_.persistenceId)
  }

  def currentPersistenceIds(offset: Offset): Source[PersistenceId, NotUsed] = {
    Source.lazyFuture(() => rxDriver.journals())
      .mapConcat(identity)
      .splitWhen(_ => true)
      .flatMapConcat(buildFindAllIds(_, offset))
      .mergeSubstreams
  }

  private def buildFindAllIds(collection: BSONCollection, offset: Offset): Source[PersistenceId, Future[State]] = {
    collection
      .find(BSONDocument(Fields.from_sn -> 1L) ++ filterByOffset(offset), None)
      .sort(BSONDocument("_id" -> 1))
      .cursor[BSONDocument]()
      .documentSource()
      .map(doc => PersistenceId(doc.getAs[String](Fields.persistenceId).get, ObjectIdOffset(doc.getAs[BSONObjectID]("_id").get)))
  }

}
