package org.talend.sjs.beam

import com.typesafe.config.Config
import org.apache.spark.SparkContext
import spark.jobserver.{SparkJobValid, SparkJobValidation, SparkJob}

object SomkeTest extends SparkJob{
  override def validate(sc: SparkContext, config: Config): SparkJobValidation = SparkJobValid

  override def runJob(sc: SparkContext, jobConfig: Config): Any = {
    val randomGen = new java.util.Random()
    sc.parallelize(for(i <- 1 to 10) yield Seq(randomGen.nextInt()))
      .countByValue()
      .toString()
  }
}
