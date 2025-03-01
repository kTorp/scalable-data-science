// Databricks notebook source
// MAGIC %md
// MAGIC ScaDaMaLe Course [site](https://lamastex.github.io/scalable-data-science/sds/3/x/) and [book](https://lamastex.github.io/ScaDaMaLe/index.html)

// COMMAND ----------

// MAGIC %md
// MAGIC ## Scalable Spatio-Temporal Constraint Satisfaction Problem (ST-CSP)
// MAGIC 
// MAGIC Suppose you have base spatial sets in 2D space (points, lines, polygons, etc) given by: 
// MAGIC 
// MAGIC \\[\\{S\_i \\}\_{i=1}^m\\] 
// MAGIC 
// MAGIC and base temporal sets in 1D given by intervals in time:
// MAGIC \\[ \\{T\_j \\}\_{j=1}^n .\\] 
// MAGIC 
// MAGIC Then one can obtain space-time set in 3D as unions of Cartesian products of such base spatial and temporal sets.
// MAGIC 
// MAGIC A simple constraint satisfaction problem can be posed as a Boolean-valued statement involving such space-time sets.
// MAGIC 
// MAGIC Often, the first seps that a geospatial scientist or analyst needs to take in order to build intuition about the phenomenon of interest invovles such CSP problems.
// MAGIC 
// MAGIC Some simple examples of such CSP problems are:
// MAGIC * Given a set of GPS trajectories for multiple entities find the set of entities that intersect with a given space-time set or a collection of space-time sets.
// MAGIC * Find the set of entitites that were close to one another based on their trajectories during a given period of time.
// MAGIC * Find entities that are spatio-temporally inconsistent - such as being in two places at the same time.
// MAGIC * ...

// COMMAND ----------

// MAGIC %md 
// MAGIC ## Microsoft Research's Beijing Taxicab Trajectories using Magellan
// MAGIC 
// MAGIC ### T-Drive trajectory data sample
// MAGIC 
// MAGIC Yu Zheng
// MAGIC 
// MAGIC 12 August 2011
// MAGIC 
// MAGIC **Abstract**
// MAGIC 
// MAGIC This is a sample of T-Drive trajectory dataset that contains a one-week trajectories of 10,357 taxis. The total number of points in this dataset is about 15 million and the total distance of the trajectories reaches 9 million kilometers.
// MAGIC 
// MAGIC Please cite the following papers when using the dataset: [1] Jing Yuan, Yu Zheng, Xing Xie, and Guangzhong Sun. Driving with knowledge from the physical world. In The 17th ACM SIGKDD international conference on Knowledge Discovery and Data mining, KDD'11, New York, NY, USA, 2011. ACM. [2] Jing Yuan, Yu Zheng, Chengyang Zhang, Wenlei Xie, Xing Xie, Guangzhong Sun, and Yan Huang. T-drive: driving directions based on taxi trajectories. In Proceedings of the 18th SIGSPATIAL International Conference on Advances in Geographic Information Systems, GIS '10, pages 99-108, New York, NY, USA,2010. ACM.
// MAGIC 
// MAGIC More details on the dataset and related papers are available [here](http://research.microsoft.com/apps/pubs/?id=152883).

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC ## Steps in this notebook
// MAGIC 
// MAGIC 1. Download the taxi trips in Beijing from MSR.
// MAGIC * Turn them into Dataframes.
// MAGIC * Define generic functions for the CSP problem.

// COMMAND ----------

import org.apache.hadoop.io.BytesWritable
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce.Job

// COMMAND ----------

// MAGIC %md 
// MAGIC ### 1. Download the Taxi Trips

// COMMAND ----------

// MAGIC %sh
// MAGIC # latest available MSR Beijing Taxi trajectory downloaded...
// MAGIC # if you are in the databricks community edition you should NOT execute this cell and download the premade all.txt.gz file as explained in the next line
// MAGIC # wget http://lamastex.org/datasets/public/geospatial/msr/all.txt.gz
// MAGIC wget http://lamastex.org/datasets/public/geospatial/msr/T-driveTaxiTrajectories.zip

// COMMAND ----------

// MAGIC %sh
// MAGIC # Check that the file is there
// MAGIC ls -al

// COMMAND ----------

// MAGIC %sh
// MAGIC du -sh T-driveTaxiTrajectories.zip

// COMMAND ----------

// MAGIC %md
// MAGIC ### Below we illustrate the small files problem
// MAGIC 
// MAGIC Having lots of small files is bad for distributed file store as the metadata needed to store each small file starts to interefere with performance.
// MAGIC 
// MAGIC We show how to avoid this performance hit when naively loading lots of small files by concatenating them and loading a large file which automagically get chopped into optimally sized blocks when we load it into distributed file system dbfs in databricks (s3 on AWS).

// COMMAND ----------

// MAGIC %sh
// MAGIC 
// MAGIC # Ensure directory exists use '-p' flag to avoid directory already exists warning
// MAGIC mkdir /dbfs/files/trajectories
// MAGIC 
// MAGIC # This command will unzip the files, concatenate all the data to a single file, gzip it and then put this single file in dbfs.
// MAGIC # It takes about 3 minutes to run.
// MAGIC 
// MAGIC unzip -o T-driveTaxiTrajectories.zip
// MAGIC rm all.txt
// MAGIC cat release/taxi_log_2008_by_id/*.txt >> all.txt
// MAGIC gzip -k all.txt
// MAGIC cp all.txt.gz /dbfs/files/trajectories/  # or you can just wget http://lamastex.org/datasets/public/geospatial/msr/all.txt.gz

// COMMAND ----------

// MAGIC %sh
// MAGIC 
// MAGIC # This command will copy over each file to dbfs without any form of processing. It will take a long time and
// MAGIC # also surpass the limits of the free community edition.
// MAGIC 
// MAGIC unzip -o T-driveTaxiTrajectories.zip -d /dbfs/files/trajectories

// COMMAND ----------

// MAGIC %fs ls "dbfs:/files/trajectories/release/taxi_log_2008_by_id"

// COMMAND ----------

// Load files into an RDD
val fileContentsRDD = sc.textFile("dbfs:/files/trajectories/all.txt.gz")
val linesRDD = fileContentsRDD.flatMap(l => l.split("\n"))

// COMMAND ----------

display(linesRDD.toDF)

// COMMAND ----------

// MAGIC %md
// MAGIC ### Now that the data is in DBFS, lets turn it into a dataframe.

// COMMAND ----------

import magellan.{Point, Polygon, PolyLine}

import org.apache.spark.sql.magellan.dsl.expressions._
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._

import java.sql.Timestamp

// COMMAND ----------

// MAGIC %md 
// MAGIC Now we define the schema for our the rows in our taxi data frame. This follows directly from the Raam Sriharsha's Uber Example

// COMMAND ----------

case class taxiRecord(
  taxiId: Int,
  time: String,
  point: Point
  )

// COMMAND ----------

// MAGIC %md 
// MAGIC Use Java date/time utilities to parse the date strings in the zip files.

// COMMAND ----------

import java.text.SimpleDateFormat 
val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

// COMMAND ----------

// MAGIC %md 
// MAGIC Now parse the data line by line, splitting by commas and casting to the correct datatypes. Wrapping in a try-catch block will avoid crashes when invalid data is encountered. Currently this invalid entries are discarded, but may be of interest to see if some data can be recovered. For further information on data cleaning of location data in chapter 8 of [Advanced Analytics with Spark](http://shop.oreilly.com/product/0636920035091.do)

// COMMAND ----------

val taxiData = linesRDD.map{line =>
  try {
    val parts = line.split(",")
    val id = parts(0).toInt
    val time = parts(1)
    
    val point = Point(parts(2).toDouble, 
                      parts(3).toDouble)
    
    taxiRecord(id, time, point)
    
  } catch {
    // Label invalid datapoints
      case e: Throwable => {
        val p = Point(-1.0, -1.0)
        val id = -1
        val time = "0000-00-00 00:00:00"
        taxiRecord( id, time, p)
      }
  }
}
.toDF
.select($"taxiId", to_utc_timestamp($"time", "yyyy-MM-dd HH:mm:ss").as("timeStamp"), $"point") // warning unixTime
.repartition(100)
.where($"taxiId" > -1)
.cache()

// COMMAND ----------

taxiData.show

// COMMAND ----------

taxiData.count

// COMMAND ----------

// MAGIC %md 
// MAGIC To use this data in new versions of spark not supported by Magellan (yet!), the dataframe containing the trips can be saved to a parquet file and accessed in other notebooks.
// MAGIC 
// MAGIC __Note:__ The datatypes defined by Magellan cannot be stored in a parquet file. To work around this simply store the raw latitude and longitude values instead of the Magellan objects themselves.

// COMMAND ----------

taxiData

// COMMAND ----------

// Helper function to extract latitude and longitued
val pointToTuple = udf( (point: Point) => Array(point.getX(), point.getY()))

val taxiDataNoMagellan = taxiData.select($"taxiId", $"timeStamp", pointToTuple($"point").as("latlon")).drop("point")

taxiDataNoMagellan.write.mode("overwrite").parquet("dbfs:/datasets/t-drive-trips")

// COMMAND ----------

taxiData.write.mode("overwrite").parquet("dbfs:/datasets/t-drive-trips-magellan") // magellan

// COMMAND ----------

spark.read.parquet("dbfs:/datasets/t-drive-trips-magellan").count()

// COMMAND ----------

// MAGIC %md
// MAGIC ### Let's do some diagnostic tuning

// COMMAND ----------

// MAGIC %run scalable-data-science/sds-2-x-tuning/00_dbcTuningUtilities2run 

// COMMAND ----------

printRecordsPerPartition(taxiData)

// COMMAND ----------

taxiData.count()

// COMMAND ----------

taxiData.rdd.getNumPartitions

// COMMAND ----------

taxiData.rdd.repartition

// COMMAND ----------

taxiData.unpersist()
taxiRepartioned.unpersist()

// COMMAND ----------

val taxiRepartioned = taxiData.rdd.repartition(96).cache()
taxiRepartioned.count()

// COMMAND ----------

taxiRepartioned.count() // 55 secs; 8 :: 27 secs for 32 :: 17 secs for 96

// COMMAND ----------

linesRDD

// COMMAND ----------

val taxiDataSpark = linesRDD.repartition(96).map{line =>
  try {
    val parts = line.split(",")
    val id = parts(0).toInt
    val time = parts(1)
    
    val point = Point(parts(2).toDouble, 
                      parts(3).toDouble)
    
    taxiRecord(id, time, point)
    
  } catch {
    // Label invalid datapoints
      case e: Throwable => {
        val p = Point(-1.0, -1.0)
        val id = -1
        val time = "0000-00-00 00:00:00"
        taxiRecord( id, time, p)
      }
  }
}
.toDF
.select($"taxiId", to_utc_timestamp($"time", "yyyy-MM-dd HH:mm:ss").as("timeStamp"), $"point") // warning unixTime
//.repartition(100)
.where($"taxiId" > -1)
.cache()

// COMMAND ----------

taxiDataSpark.count

// COMMAND ----------

taxiDataSpark.count

// COMMAND ----------

taxiDataSpark.rdd.getNumPartitions

// COMMAND ----------

printRecordsPerPartition(taxiDataSpark)

// COMMAND ----------

val cores = sc.defaultParallelism

println(s"You have $cores cores, or slots.")
println("-"*100)

// COMMAND ----------

// MAGIC %md
// MAGIC Spark's optimizer has picked a multiple of 8 close to 100.

// COMMAND ----------

taxiDataSpark.write.mode("overwrite").parquet("dbfs:/datasets/t-drive-trips-magellan") // magellan

// COMMAND ----------

spark.read.parquet("dbfs:/datasets/t-drive-trips-magellan").count()

// COMMAND ----------

val taxiDataSparkParquetRead = spark.read.parquet("dbfs:/datasets/t-drive-trips-magellan").cache()
taxiDataSparkParquetRead.count()

// COMMAND ----------

taxiDataSparkParquetRead.count()

// COMMAND ----------

taxiDataSparkParquetRead.rdd.getNumPartitions

// COMMAND ----------

// MAGIC %md
// MAGIC 
// MAGIC Remember you can `paritionByKey` or even set `maxRecordsPerFile` to optimize parquet for downstream analytics:
// MAGIC 
// MAGIC  - https://stackoverflow.com/questions/44808415/spark-parquet-partitioning-large-number-of-files

// COMMAND ----------

// MAGIC %md 
// MAGIC ###Now to do Some further work with this data 

// COMMAND ----------

taxiDataSparkParquetRead.count

// COMMAND ----------

// MAGIC %md 
// MAGIC First for additional functionality we use the ESRI Geometry api. This is a Java library with various functions for working with geometric data.
// MAGIC 
// MAGIC Note that Magellan does built functionality ontop of this library but the functions we need are not exposed through Magellan.
// MAGIC 
// MAGIC For further information and documentation see [here.](https://github.com/Esri/geometry-api-java/wiki)

// COMMAND ----------

import org.apache.spark.sql.{DataFrame, Column, Row}

import com.esri.core.geometry.{Point => ESRIPoint}
import com.esri.core.geometry.{GeometryEngine, SpatialReference}
import com.esri.core.geometry.GeometryEngine.geodesicDistanceOnWGS84

// COMMAND ----------

// MAGIC %md 
// MAGIC Implicit conversion from a Magellan Point to a Esri Point. This makes things easier when going between Magellan and ESRI points.

// COMMAND ----------

implicit def toEsri(point: Point) = {
  new ESRIPoint(point.getX(), point.getY())
}

// COMMAND ----------

// MAGIC %md 
// MAGIC ###Outlining the Geospatial Constraint Satisfaction Problem (CSP)
// MAGIC 
// MAGIC The problem being addressed in this section can be considered as finding trajectories that satisfy some constraints on time and spatial location. These constraints can be visualised as a three dimensional object with dimensions of longitude, latitude and time. Then trajectory segments that intersect this object are those segments that satisfy the given constraints.

// COMMAND ----------

// MAGIC %md 
// MAGIC We wish to implement generic functions that find these trajectories of interest.
// MAGIC 
// MAGIC To begin with we first define a circle datatype to represent circular regions in space. The circle is defined in terms of its center, and radius.

// COMMAND ----------

case class Circle(radius: Double, center: Point)

// COMMAND ----------

// MAGIC %md 
// MAGIC A point then intersects the circle when the distance between the point and the circles center is less than its radius.
// MAGIC 
// MAGIC To codify this define a user defined function(udf) to act on the a column of points given a circle returning the geodesic distance of the points from the center returning true when the point lies within the center. For more information on using udfs see the follwing helpful [blogpost](http://www.sparktutorials.net/using-sparksql-udfs-to-create-date-times-in-spark-1.5), and the official [documentation.](https://spark.apache.org/docs/1.5.2/api/scala/#org.apache.spark.sql.UserDefinedFunction) 
// MAGIC 
// MAGIC For information on the geodesic distance function see the relevant ESRI documentation [here](https://github.com/Esri/geometry-api-java/wiki). The implicit function defined above allows us to call ESRI functions using magellan datatypes.

// COMMAND ----------

val intersectsCircle = udf( (center: Point, radius: Double, point: Point) => geodesicDistanceOnWGS84(center, point) <= radius )

// COMMAND ----------

// MAGIC %md 
// MAGIC Here below generic functions are defined for Geospatial Constraint Satisfaction problems.
// MAGIC 
// MAGIC The SpaceTimeVolume trait, provides an 'blackbox' interface that can be queried to find trajectories satisfying constraints.

// COMMAND ----------

// MAGIC %md 
// MAGIC What are traits in scala [official documentation](http://docs.scala-lang.org/tutorials/tour/traits.html)
// MAGIC 
// MAGIC Information on Sealed traits [here](http://underscore.io/blog/posts/2015/06/02/everything-about-sealed.html) and [here.](http://underscore.io/blog/posts/2015/06/04/more-on-sealed.html)

// COMMAND ----------

// MAGIC %md 
// MAGIC To make things generic we define a trait ```SpaceTimeVolume``` as the interface to the CSP functionality. Then specific functionality for each type of geometric region is defined in the case classes that extend this trait.

// COMMAND ----------

sealed trait SpaceTimeVolume {  
  def getIntersectingTrips(trajectories: DataFrame, startTime: Timestamp, endTime:Timestamp): DataFrame
  
  def pointsIntersectingTime(trajectories: DataFrame, startTime: Timestamp, endTime:Timestamp) = {
      trajectories.filter($"timestamp" >= startTime && $"timestamp" <= endTime)
    }
}

case class CircleContainer(circles: DataFrame, startTime: Timestamp, endTime:Timestamp) extends SpaceTimeVolume {
  
  def getIntersectingTrips(trajectories: DataFrame, startTime: Timestamp, endTime:Timestamp) = {
      val tripsIntersectingTime = pointsIntersectingTime(trajectories, startTime, endTime)
    
      val numCircles = circles.count()
      val intersectingPoints = circles.join(tripsIntersectingTime).where(intersectsCircle($"center", $"radius", $"point")).cache()
  
      intersectingPoints

  }
}

case class PolygonContainer(polygons: DataFrame) extends SpaceTimeVolume {
  
    def getIntersectingTrips(trajectories: DataFrame, startTime: Timestamp, endTime:Timestamp) = {
      val tripsIntersectingTime = pointsIntersectingTime(trajectories, startTime, endTime)

      val numPolygons = polygons.count() //See if this can be avoided, possibly pass in this parameter and do count when polygon df is being created?

      val intersectingPoints = polygons.join(tripsIntersectingTime).where($"point" within $"polygon").cache()

      intersectingPoints
    }
}

// COMMAND ----------

// MAGIC %md 
// MAGIC ### __Example__: Taxis going past Tiananmen Square

// COMMAND ----------

// MAGIC %md 
// MAGIC To show the result of this consider the following polygon.

// COMMAND ----------

case class PolygonRecord(polygon: Polygon)

val tiananmenSquare = Array(
    Point(116.3931388,39.9063043),
    Point(116.3938048,39.8986498),
    Point(116.3892702,39.8980945),
    Point(116.3894568,39.9061898)
)

val polygonDF = PolygonContainer(
  sc.parallelize(Seq(
    PolygonRecord(Polygon(Array(0), tiananmenSquare)))).toDF
  )

polygonDF.polygons.show()

// COMMAND ----------

// MAGIC %md 
// MAGIC This is a polygon covering an area approximating that around Tiananmen Square. And we wish to find all the all the taxis that travel around the square over a timeframe given below.

// COMMAND ----------

def genLeafletHTML(): String = {

  val accessToken = "pk.eyJ1IjoiZHRnIiwiYSI6ImNpaWF6MGdiNDAwanNtemx6MmIyNXoyOWIifQ.ndbNtExCMXZHKyfNtEN0Vg"

  val generatedHTML = f"""<!DOCTYPE html>
  <html>
  <head>
  <title>Maps</title>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/leaflet.css">
  <style>
  #map {width: 600px; height:400px;}
  </style>

  </head>
  <body>
  <div id="map" style="width: 1000px; height: 600px"></div>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/0.7.7/leaflet.js"></script>
  <script type="text/javascript">
  var map = L.map('map').setView([39.913818, 116.363625], 14);

  L.tileLayer('https://api.tiles.mapbox.com/v4/{id}/{z}/{x}/{y}.png?access_token=$accessToken', {
  maxZoom: 18,
  attribution: 'Map data &copy; <a href="http://openstreetmap.org">OpenStreetMap</a> contributors, ' +
  '<a href="http://creativecommons.org/licenses/by-sa/2.0/">CC-BY-SA</a>, ' +
  'Imagery © <a href="http://mapbox.com">Mapbox</a>',
  id: 'mapbox.streets'
  }).addTo(map);

  var polygon = L.polygon([
    [39.9063043, 116.3931388],
    [39.8986498, 116.3938048],
    [39.8980945, 116.3892702],
    [39.9061898, 116.3894568]
    ]).addTo(map);
  </script>


  </body>
  """
  generatedHTML
}
displayHTML(genLeafletHTML)

// COMMAND ----------

// MAGIC %md 
// MAGIC Specifying the Time frame we are interested in.

// COMMAND ----------

val startTime: Timestamp = Timestamp.valueOf("2008-02-03 00:00:00.0")
val endTime: Timestamp = Timestamp.valueOf("2008-02-03 01:00:00.0")

// COMMAND ----------

// MAGIC %md 
// MAGIC Now the `getIntersectingTrips` function can be run and the data points that intersect the space time volume are found.

// COMMAND ----------

val intersectingTrips = polygonDF.getIntersectingTrips(taxiDataSparkParquetRead, startTime, endTime) // taxiData

// COMMAND ----------

// MAGIC %md 
// MAGIC Here are all the points that pass through the polygon:

// COMMAND ----------

display(intersectingTrips.select($"taxiId", $"timeStamp"))

// COMMAND ----------

// MAGIC %md 
// MAGIC A list of all the taxis that take a trip around the square:

// COMMAND ----------

display(intersectingTrips.select($"taxiId").distinct)

// COMMAND ----------

display(intersectingTrips.groupBy($"taxiId").count.orderBy(-$"count"))

// COMMAND ----------

// MAGIC %md
// MAGIC Cleanup your mess in distributed RAM

// COMMAND ----------

taxiDataSparkParquetRead.unpersist()

// COMMAND ----------

taxiDataSpark.unpersist()

// COMMAND ----------

taxiRepartioned.unpersist()

// COMMAND ----------

taxiData.unpersist()

// COMMAND ----------

// MAGIC %fs
// MAGIC ls dbfs:/datasets/t-drive-trips

// COMMAND ----------

// MAGIC %fs
// MAGIC ls dbfs:/datasets/t-drive-trips-magellan

// COMMAND ----------

