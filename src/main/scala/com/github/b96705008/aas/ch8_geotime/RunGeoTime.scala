package com.github.b96705008.aas.ch8_geotime

import java.text.SimpleDateFormat
import java.util.Locale

import org.apache.spark.{HashPartitioner, Partitioner}
import org.joda.time.{DateTime, Duration}
import com.esri.core.geometry.Point
import com.github.b96705008.context.Env
import GeoJsonProtocol._
import org.apache.spark.rdd.RDD
import org.apache.spark.util.StatCounter
import org.joda.time
import spray.json._

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

case class Trip(pickupTime: DateTime,
                dropoffTime: DateTime,
                pickupLoc: Point,
                dropoffLoc: Point)

object RunGeoTime extends Serializable {
  val base = "file:///Volumes/RogerDrive/Developer/dataset/aas/ch8-taxi/"

  val formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)

  def main(args: Array[String]) {
    val sc = Env.setupContext("GeoTime")
    val taxiRaw = sc.textFile(base + "trip_data_1.csv")

    // parse taxi data
    val safeParse = safe(parse)
    val taxiParsed = taxiRaw.map(safeParse)
//    taxiParsed.cache()

//    val taxiBad = taxiParsed.collect({
//      case t if t.isRight => t.right.get
//    })
//    taxiBad.take(10).foreach(println)

    // get good and clean taxi data by time
    val taxiGood = taxiParsed.collect({
      case t if t.isLeft => t.left.get
    })
    taxiGood.cache()

    def hours(trip: Trip): Long = {
      val d = new Duration(trip.pickupTime, trip.dropoffTime)
      d.getStandardHours
    }

    val taxiClean = taxiGood.filter {
      case (lic, trip) =>
        try {
          val hrs = hours(trip)
          0L <= hrs && hrs < 3L
        } catch {
          case e: Exception => false
        }
    }

    // process geo data
    val geojson = scala.io.Source
      .fromFile("/Volumes/RogerDrive/Developer/dataset/aas/ch8-taxi/nyc-boroughs.geojson")
      .mkString

    val features = geojson.parseJson.convertTo[FeatureCollection]
    val areaSortedFeatures = features.sortBy(f => {
      val borough = f("boroughCode").convertTo[Int]
      (borough, -f.geometry.area2D)
    })

    val bFeatures = sc.broadcast(areaSortedFeatures)

    def borough(trip: Trip): Option[String] = {
      val feature: Option[Feature] = bFeatures.value.find(f => {
        f.geometry.contains(trip.dropoffLoc)
      })
      feature.map(f => {
        f("borough").convertTo[String]
      })
    }
    println("taxiClean:")
    taxiClean.values.map(borough).countByValue().toSeq.sortBy(-_._2).foreach(println)

    def hasZero(trip: Trip): Boolean = {
      val zero = new Point(0.0, 0.0)
      zero.equals(trip.pickupLoc) || zero.equals(trip.dropoffLoc)
    }

    val taxiDone = taxiClean.filter {
      case (lic, trip) => !hasZero(trip)
    }.cache()
    println("taxiDone:")
    taxiDone.values.map(borough).countByValue().toSeq.sortBy(-_._2).foreach(println)

    // split sessions
    def secondaryKeyFunc(trip: Trip) = trip.pickupTime.getMillis

    def split(t1: Trip, t2: Trip): Boolean = {
      val p1 = t1.pickupTime
      val p2 = t2.pickupTime
      val d = new time.Duration(p1, p2)
      d.getStandardHours >= 4
    }

    val sessions = groupByKeyAndSortValues(taxiDone, secondaryKeyFunc, split, 30)
    sessions.cache()

    def boroughDuration(t1: Trip, t2: Trip) = {
      val b = borough(t1)
      val d = new time.Duration(t1.dropoffTime, t2.pickupTime)
      (b, d)
    }

    val boroughDurations: RDD[(Option[String], Duration)] =
      sessions.values.flatMap(trips => {
        val iter: Iterator[Seq[Trip]] = trips.sliding(2)
        val viter = iter.filter(_.size == 2)
        viter.map(p => boroughDuration(p(0), p(1)))
      }).cache()

    println("wait durations statics per borough:")
    boroughDurations.filter {
      case (b, d) => d.getMillis >= 0
    }.mapValues(d => {
      val s = new StatCounter()
      s.merge(d.getStandardSeconds)
    }).reduceByKey((a, b) => a.merge(b)).collect().foreach(println)
  }

  def point(longitude: String, latitude: String): Point = {
    new Point(longitude.toDouble, latitude.toDouble)
  }

  def parse(line: String): (String, Trip) = {
    val fields = line.split(",")
    val license = fields(1)
    val pickupTime = new DateTime(formatter.parse(fields(5)))
    val dropoffTime = new DateTime(formatter.parse(fields(6)))
    val pickupLoc = point(fields(10), fields(11))
    val dropoffLoc = point(fields(12), fields(13))
    val trip = Trip(pickupTime, dropoffTime, pickupLoc, dropoffLoc)
    (license, trip)
  }

  def safe[S, T](f: S => T): S => Either[T, (S, Exception)] = {
    new Function[S, Either[T, (S, Exception)]] with Serializable {
      def apply(s: S): Either[T, (S, Exception)] = {
        try {
          Left(f(s))
        } catch {
          case e: Exception => Right((s, e))
        }
      }
    }
  }

  /**
    * groupByKeyAndSortValues
    */
  def groupByKeyAndSortValues[K : Ordering : ClassTag, V : ClassTag, S: Ordering]
    (rdd: RDD[(K, V)],
     secondaryKeyFunc: (V) => S,
     splitFunc: (V, V) => Boolean,
     numPartitions: Int): RDD[(K, List[V])] = {
    val presess = rdd.map {
      case (lic, trip) => ((lic, secondaryKeyFunc(trip)), trip)
    }
    val partitioner = new FirstKeyPartitioner[K, S](numPartitions)
    presess
      .repartitionAndSortWithinPartitions(partitioner)
      .mapPartitions(it => groupSorted(it, splitFunc))
  }

  def groupSorted[K, V, S](it: Iterator[((K, S), V)],
                           splitFunc: (V, V) => Boolean): Iterator[(K, List[V])] = {
    val res = List[(K, ArrayBuffer[V])]() // a collection of sessions (lic, trips(shift))
    it.foldLeft(res)((list, next) => list match {
      case Nil => {
        val ((lic, _), trip) = next
        List((lic, ArrayBuffer(trip)))
      }
      case cur :: rest => {
        // head is the newest session
        val (curLic, trips) = cur
        val ((lic, _), trip) = next
        if (!lic.equals(curLic) || splitFunc(trips.last, trip)) {
          // new session
          (lic, ArrayBuffer(trip)) :: list
        } else {
          // append trip to old session
          trips.append(trip)
          list
        }
      }
    }).map {case (lic, buf) => (lic, buf.toList)}.iterator
  }

  class FirstKeyPartitioner[K1, K2](partitions: Int) extends Partitioner {
    val delegate = new HashPartitioner(partitions)
    override def numPartitions: Int = delegate.numPartitions
    // key is tuple
    override def getPartition(key: Any): Int = {
      val k = key.asInstanceOf[(K1, K2)]
      delegate.getPartition(k._1)
    }
  }
}