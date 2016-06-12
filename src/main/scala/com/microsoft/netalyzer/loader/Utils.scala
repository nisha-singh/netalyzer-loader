package com.microsoft.netalyzer.loader

import java.util.UUID

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.types._

object Utils {

  def initializeDb(path: String, sc: SQLContext): Unit = {
    sc.sql(
      s"""
        CREATE DATABASE IF NOT EXISTS netalyzer
        LOCATION "$path"
      """.stripMargin
    )

    sc.sql(
      """
        CREATE TABLE IF NOT EXISTS netalyzer.samples (
          datetime TIMESTAMP,
          hostname VARCHAR(255),
          portname VARCHAR(255),
          portspeed DECIMAL(38,0),
          totalrxbytes DECIMAL(38,0),
          totaltxbytes DECIMAL(38,0),
          id VARCHAR(36)
        )
        CLUSTERED BY(id) INTO 16 BUCKETS
        STORED AS ORC
        TBLPROPERTIES("transactional"="true")
      """.stripMargin
    )

    sc.sql(
      """
        CREATE TABLE IF NOT EXISTS netalyzer.deltas (
          deltaseconds INT,
          deltarxbytes INT,
          deltatxbytes INT,
          id VARCHAR(36)
        )
        CLUSTERED BY(id) INTO 16 BUCKETS
        STORED AS ORC
        TBLPROPERTIES("transactional"="true")
      """.stripMargin
    )
  }


  // https://docs.oracle.com/javase/7/docs/api/java/text/SimpleDateFormat.html
  def importCsvData(path: String, sc: SQLContext): Unit = {

    val customSchema = StructType(
      Array(
        StructField("datetime", TimestampType, nullable = false),
        StructField("hostname", StringType, nullable = false),
        StructField("portname", StringType, nullable = false),
        StructField("portspeed", DecimalType(38, 0), nullable = false),
        StructField("totalrxbytes", DecimalType(38, 0), nullable = false),
        StructField("totaltxbytes", DecimalType(38, 0), nullable = false)
      )
    )

    val fileSystem = FileSystem.get(sc.sparkContext.hadoopConfiguration)
    val tmpPath = path + "_LOADING"
    val genId = udf(UUID.randomUUID().toString)

    if (fileSystem.exists(new Path(tmpPath))) {
      val rawDf = sc.read
        .format("com.databricks.spark.csv")
        .option("mode", "FAILFAST")
        .option("header", "true")
        .option("dateFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .schema(customSchema)
        .load(tmpPath)
        .repartition(16)

      val newDf = rawDf.withColumn("id", genId())
      newDf.printSchema()
      newDf.show(100)
      newDf.write.mode("append").saveAsTable("netalyzer.samples")
      fileSystem.delete(new Path(tmpPath), true)
    }
    else if (fileSystem.exists(new Path(path))) {
      fileSystem.rename(new Path(path), new Path(tmpPath))

      val rawDf = sc.read
        .format("com.databricks.spark.csv")
        .option("mode", "FAILFAST")
        .option("header", "true")
        .option("dateFormat", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        .schema(customSchema)
        .load(tmpPath)
        .repartition(16)

      val newDf = rawDf.withColumn("id", genId())
      newDf.printSchema()
      newDf.show(100)
      newDf.write.mode("append").saveAsTable("netalyzer.samples")
      fileSystem.delete(new Path(tmpPath), true)
    }
  }

  def materializeDeltas(sc: SQLContext): Unit = {

    val deltasDf = sc.sql(
      """
        SELECT unix_timestamp(datetime) - lag(unix_timestamp(datetime)) OVER (PARTITION BY hostname, portname ORDER BY datetime) AS deltaseconds,
          CASE WHEN (lag(totalrxbytes) OVER (PARTITION BY hostname, portname ORDER BY datetime) > totalrxbytes)
            THEN round(18446744073709551615 - lag(totalrxbytes) OVER (PARTITION BY hostname, portname ORDER BY datetime) + totalrxbytes)
            ELSE round(totalrxbytes - lag(totalrxbytes) OVER (PARTITION BY hostname, portname ORDER BY datetime))
          END AS deltarxbytes,
          CASE WHEN (lag(totaltxbytes) OVER (PARTITION BY hostname, portname ORDER BY datetime) > totaltxbytes)
            THEN round(18446744073709551615 - lag(totaltxbytes) OVER (PARTITION BY hostname, portname ORDER BY datetime) + totaltxbytes)
            ELSE round(totaltxbytes - lag(totaltxbytes) OVER (PARTITION BY hostname, portname ORDER BY datetime))
          END AS deltatxbytes,
          id
        FROM netalyzer.samples
      """.stripMargin
    ).repartition(16)

    deltasDf.printSchema()
    deltasDf.show(100)

    sc.sql(
      """
        TRUNCATE TABLE netalyzer.deltas
      """.stripMargin
    )

    deltasDf.write.mode("append").saveAsTable("netalyzer.deltas")
  }

}
