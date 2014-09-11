package mypipe.kafka

import mypipe._
import mypipe.avro.GenericInMemorySchemaRepo
import mypipe.avro.schema.GenericSchemaRepository
import mypipe.mysql.{BinlogConsumer, BinlogFilePos}
import mypipe.producer.{KafkaMutationSpecificAvroProducer, KafkaMutationGenericAvroProducer}
import org.apache.avro.Schema
import org.apache.avro.util.Utf8
import org.scalatest.BeforeAndAfterAll
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class KafkaSpecificSpec extends UnitSpec with DatabaseSpec with ActorSystemSpec with BeforeAndAfterAll {

  val log = LoggerFactory.getLogger(getClass)
  @volatile var connected = false
  @volatile var done = false
  val kafkaProducer = new KafkaMutationSpecificAvroProducer(
    conf.getConfig("mypipe.test.kafka-specific-producer"))

  val binlogConsumer = BinlogConsumer(hostname, port.toInt, username, password, BinlogFilePos.current)
  val pipe = new Pipe("test-pipe-kafka-specific", List(binlogConsumer), kafkaProducer)

  override def beforeAll() {

    db.connect
    pipe.connect()

    while (!db.connection.isConnected || !pipe.isConnected) { Thread.sleep(10) }

    Await.result(db.connection.sendQuery(Queries.CREATE.statement), 1 second)
    Await.result(db.connection.sendQuery(Queries.TRUNCATE.statement), 1 second)
  }

  override def afterAll() {
    pipe.disconnect()
    db.disconnect
  }

  "A specific Kafka Avro producer and consumer" should "properly produce and consume insert, update, and delete events" in withDatabase { db ⇒

    val username = new Utf8("username")

    val kafkaConsumer = new KafkaGenericMutationAvroConsumer[Short](
      topic     = "mypipe_user_specific",
      zkConnect = "localhost:2181",
      groupId   = s"mypipe_user_insert-${System.currentTimeMillis()}",
      schemaIdSizeInBytes = 2)(

      insertCallback = { insertMutation ⇒
        log.debug("consumed insert mutation: " + insertMutation)
        try {
          assert(insertMutation.getDatabase.toString == "mypipe")
          assert(insertMutation.getTable.toString == "user")
          assert(insertMutation.getStrings().get(username).toString.equals("username"))
        }
        true
      },

      updateCallback = { updateMutation ⇒
        log.debug("consumed update mutation: " + updateMutation)
        try {
          assert(updateMutation.getDatabase.toString == "mypipe")
          assert(updateMutation.getTable.toString == "user")
          assert(updateMutation.getOldStrings().get(username).toString == "username")
          assert(updateMutation.getNewStrings().get(username).toString == "username2")
        }
        true
      },

      deleteCallback = { deleteMutation ⇒
        log.debug("consumed delete mutation: " + deleteMutation)
        try {
          assert(deleteMutation.getDatabase.toString == "mypipe")
          assert(deleteMutation.getTable.toString == "user")
          assert(deleteMutation.getStrings().get(username).toString == "username2")
        }
        done = true
        true
      }) {

      protected val schemaRepoClient: GenericSchemaRepository[Short, Schema] = GenericInMemorySchemaRepo
      override def bytesToSchemaId(bytes: Array[Byte], offset: Int): Short = byteArray2Short(bytes, offset)
      private def byteArray2Short(data: Array[Byte], offset: Int) = (((data(offset) << 8)) | ((data(offset + 1) & 0xff))).toShort
    }

    val future = kafkaConsumer.start

    Await.result(db.connection.sendQuery(Queries.INSERT.statement), 2 seconds)
    Await.result(db.connection.sendQuery(Queries.UPDATE.statement), 2 seconds)
    Await.result(db.connection.sendQuery(Queries.DELETE.statement), 2 seconds)
    Await.result(Future { while (!done) Thread.sleep(100) }, 20 seconds)

    try {
      kafkaConsumer.stop
      Await.result(future, 5 seconds)
    }

    if (!done) assert(false)
  }
}
