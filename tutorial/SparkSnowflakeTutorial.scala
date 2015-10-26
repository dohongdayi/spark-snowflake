/*
 * Copyright 2015 Databricks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.snowflakedb.spark.snowflakedb.tutorial
import org.apache.spark.{SparkConf,SparkContext}
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.types.{StructType,StructField,DecimalType,IntegerType,LongType,StringType}
import org.slf4j.LoggerFactory


/**
 * Source code accompanying the spark-redshift tutorial.
 * The following parameters need to be passed
 * 1. AWS Access Key
 * 2. AWS Secret Access Key
 * 3. Database UserId
 * 4. Database Password
 */
object SparkSnowflakeTutorial {
  // Helpers to log various steps
  private val log = LoggerFactory.getLogger(getClass)
  def step(msg : String) = {
    var line = "* "
    line += (" " * ((66 - msg.length) / 2))
    line += msg
    line = line + " " * (68 - line.length) + " *"
    log.info("*" * 70)
    log.info(line)
    log.info("*" * 70)
  }

  /*
   * For Windows Users only
   * 1. Download contents from link 
   *      https://github.com/srccodes/hadoop-common-2.2.0-bin/archive/master.zip
   * 2. Unzip the file in step 1 into your %HADOOP_HOME%/bin. 
   * 3. pass System parameter -Dhadoop.home.dir=%HADOOP_HOME/bin  where %HADOOP_HOME 
   *    must be an absolute not relative path
   */

  def main(args: Array[String]): Unit = {
    // Note: we use 4 args from command line, the rest is hardcoded for now
    if (args.length != 4) {
      println("Needs 4 parameters only passed " + args.length)
      println("parameters needed - $awsAccessKey $awsSecretKey $sfUser $sfPassword")
      System.exit(1)
    }
    val awsAccessKey = args(0)
    val awsSecretKey = args(1)
    val sfUser = args(2)
    val sfPassword = args(3)
    // These are hardcoded for now
    val tempS3Dir = "s3n://sfc-dev1-regression/SPARK-SNOWFLAKE"
    val sfDatabase = "sparkdb"
    val sfSchema = "public"
    val sfWarehouse = "sparkwh"
    val sfAccount = "snowflake"
    val sfURL = "FDB1-gs.dyn.int.snowflakecomputing.com:8080"
    val sfSSL = "off"

    // Prepare Snowflake connection options as a map
    var sfOptions = Map(
      "sfURL" -> sfURL,
      "sfDatabase" -> sfDatabase,
      "sfSchema" -> sfSchema,
      "sfWarehouse" -> sfWarehouse,
      "sfUser" -> sfUser,
      "sfPassword" -> sfPassword,
      "sfAccount" -> sfAccount,
      "sfSSL" -> sfSSL
    )
    // Add the S3 temporary directory to the options to simplify further code
    sfOptions += ("tempdir" -> tempS3Dir)

    val sc = new SparkContext(new SparkConf().setAppName("SparkSQL").setMaster("local"))

    sc.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", awsAccessKey)
    sc.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", awsSecretKey)

    val sqlContext = new SQLContext(sc)

    import sqlContext.implicits._

    // df1: Load from a table
    step("Creating df1")
    val df1 = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .options(sfOptions)
      .option("dbtable", "testdt")
      .load()
    df1.printSchema()
    df1.show()


    // df2: Load from a query
    step("Creating df2")
    val df2 = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .options(sfOptions)
      .option("query", "select * from testdt where i > 2")
      .load()
    df2.printSchema()
    df2.show()

    // Test pushdowns
    step("Creating df3")
    val df3 = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .options(sfOptions)
      .option("dbtable", "testdt")
      .load()
    df3.filter(df3("S") > "o'ne").show()
    df3.filter(df3("S") > "o'ne\\").show()
    df3.filter(df3("T") > "2013-04-05 01:02:03").show()

    /*
     * Register our df1 table as temporary table 'mytab'
     * so that it can be queried via sqlContext.sql
     */
    df1.registerTempTable("mytab")

    /*
     * Create a new table sftab (overwriting any existing sftab table)
     * from spark "mytab" table,
     * filtering records via query and renaming a column
     */
    step("Creating Snowflake table sftab")
    sqlContext.sql("SELECT * FROM mytab WHERE I != 7").withColumnRenamed("I", "II")
      .write.format("com.snowflakedb.spark.snowflakedb")
      .options(sfOptions)
      .option("dbtable", "sftab")
      .mode(SaveMode.Overwrite)
      .save()

    /*
     * Append to "sftab" with a different query
     * from spark "mytab" table.
     * Note - we don't rename the columns, but since the schemas match, it works
     */
    step("Appending to Snowflake table sftab")
    sqlContext.sql("SELECT * FROM mytab WHERE I >= 7")
      .write.format("com.snowflakedb.spark.snowflakedb")
      .options(sfOptions)
      .option("dbtable", "sftab")
      .mode(SaveMode.Append)
      .save()

    /*** ------------------------------------ FINITO
    //Load from a query
    val salesQuery = """SELECT salesid, listid, sellerid, buyerid, 
                               eventid, dateid, qtysold, pricepaid, commission 
                        FROM sales 
                        ORDER BY saletime DESC LIMIT 10000"""
    val salesDF = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("query", salesQuery)
      .load()
    salesDF.show()

    val eventQuery = "SELECT * FROM event"
    val eventDF = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("query", eventQuery)
      .load()

    /*
     * Register 'event' table as temporary table 'myevent' 
     * so that it can be queried via sqlContext.sql  
     */
    eventsDF.registerTempTable("myevent")

    //Save to a Redshift table from a table registered in Spark

    /*
     * Create a new table redshiftevent after dropping any existing redshiftevent table
     * and write event records with event id less than 1000
     */
    sqlContext.sql("SELECT * FROM myevent WHERE eventid<=1000").withColumnRenamed("eventid", "id")
      .write.format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("dbtable", "redshiftevent")
      .mode(SaveMode.Overwrite)
      .save()

    /*
     * Append to an existing table redshiftevent if it exists or create a new one if it does not
     * exist and write event records with event id greater than 1000
     */
    sqlContext.sql("SELECT * FROM myevent WHERE eventid>1000").withColumnRenamed("eventid", "id")
      .write.format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("dbtable", "redshiftevent")
      .mode(SaveMode.Append)
      .save()

    /** Demonstration of interoperability */
    val salesAGGQuery = """SELECT sales.eventid AS id, SUM(qtysold) AS totalqty, SUM(pricepaid) AS salesamt
                           FROM sales
                           GROUP BY (sales.eventid)
                           """
    val salesAGGDF = sqlContext.read
      .format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("query", salesAGGQuery)
      .load()
    salesAGGDF.registerTempTable("salesagg")

    /*
     * Join two DataFrame instances. Each could be sourced from any 
     * compatible Data Source
     */
    val salesAGGDF2 = salesAGGDF.join(eventsDF, salesAGGDF("id") === eventsDF("eventid"))
      .select("id", "eventname", "totalqty", "salesamt")

    salesAGGDF2.registerTempTable("redshift_sales_agg")

    sqlContext.sql("SELECT * FROM redshift_sales_agg")
      .write.format("com.snowflakedb.spark.snowflakedb")
      .option("url", jdbcURL)
      .option("tempdir", tempS3Dir)
      .option("dbtable", "redshift_sales_agg")
      .mode(SaveMode.Overwrite)
      .save()

    }
*** ------------------------------------ FINITO ***/
  }
}