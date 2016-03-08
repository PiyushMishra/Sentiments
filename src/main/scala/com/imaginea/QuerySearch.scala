package com.imaginea

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.routing.RoundRobinPool
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import twitter4j.{Query, Status, TwitterFactory, TwitterObjectFactory}

import scala.collection.JavaConversions._

case class TweetsSentiment(tweet: Status, sentiment: Double)

trait TwitterInstance {
  val twitter = new TwitterFactory(ConfigurationBuilderUtil.buildConfiguration).getInstance()
}

object QuerySearch extends TwitterInstance {
  val host = "172.16.50.201"
  val port = 9300
  val transportClient = new TransportClient(ImmutableSettings.settingsBuilder().put("cluster.name", "elasticsearch")
    .put("client.transport.sniff", true).build())
  val client = transportClient.addTransportAddress(new InetSocketTransportAddress(host, port))

  val actorSystem = ActorSystem("twitter")
  val router: ActorRef =
    actorSystem.actorOf(RoundRobinPool(2 * Runtime.getRuntime.availableProcessors()).props(Props[TwitterQueryFetcher]), "router")

  def main(args: Array[String]) {
    List("Modi", "Obama", "Steve Jobs").foreach(term => router ! QueryTwitter(term))
  }

  def fetchAndSaveTweets(term: String): Unit = {
    val bulkRequest = client.prepareBulk()
    var query = new Query(term).lang("en")
    query.setCount(100)
    var queryResult = twitter.search(query)
    var x = 0

    while (queryResult.hasNext) {
      x = x + queryResult.getCount
      val tweetList = queryResult.getTweets.map {
        x => println(TwitterObjectFactory.getRawJSON(x))
          bulkRequest.add(client.prepareIndex("twitter", "tweet").setSource(TwitterObjectFactory.getRawJSON(x)))
      }
      query = queryResult.nextQuery()
      queryResult = twitter.search(query)
    }
    bulkRequest.execute()
    println("count " + x, "term " + term)
  }

  def toJson[T <: AnyRef <% Product with Serializable](t: T, addESHeader: Boolean = true,
                                                       isToken: Boolean = false): String = {
    import org.json4s._
    import org.json4s.jackson.Serialization
    import org.json4s.jackson.Serialization.write
    implicit val formats = Serialization.formats(NoTypeHints)
    val indexName = t.productPrefix.toLowerCase
    if (addESHeader && isToken) {
      """|{ "index" : { "_index" : "twitter", "_type" : "custom" } }
        | """.stripMargin + write(t)
    } else if (addESHeader) {
      s"""|{ "index" : { "_index" : "$indexName", "_type" : "type$indexName" } }
                                                                             |""".stripMargin + write(t)
    } else "" + write(t)

  }
}


case class QueryTwitter(term: String)

class TwitterQueryFetcher extends Actor {
  override def receive: Receive = {
    case QueryTwitter(term) => QuerySearch.fetchAndSaveTweets(term)
  }
}