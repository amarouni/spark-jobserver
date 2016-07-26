package org.talend.sjs.beam

import com.typesafe.config.Config
import org.apache.beam.runners.spark.{EvaluationResult, SparkPipelineOptions, SparkRunner}
import org.apache.beam.sdk.Pipeline
import org.apache.beam.sdk.coders.StringUtf8Coder
import org.apache.beam.sdk.options.PipelineOptionsFactory
import org.apache.beam.sdk.transforms.Create
import org.apache.spark.SparkContext
import org.apache.spark.api.java.JavaSparkContext
import org.talend.sjs.beam.utils.CountWords
import spark.jobserver.{SparkJob, SparkJobValid, SparkJobValidation}

import scala.collection.JavaConversions

/**
  * Created by abbass on 27/05/16.
  */
object BeamWordCount extends SparkJob{
  override def validate(sc: SparkContext, config: Config): SparkJobValidation = SparkJobValid

  override def runJob(sc: SparkContext, jobConfig: Config): Any = {

    // Input test list
    val WORDS = Seq("hi there", "hi", "hi sue bob", "hi sue", "", "bob hi")

    // Pipeline options
    val sparkPipelineOptions = PipelineOptionsFactory.as(classOf[SparkPipelineOptions])
    sparkPipelineOptions.setAppName("Beam SJS sample job")
    sparkPipelineOptions.setRunner(classOf[SparkRunner])
    sparkPipelineOptions.setUsesProvidedSparkContext(true)
    sparkPipelineOptions.setProvidedSparkContext(new JavaSparkContext(sc))

    // Pipeline
    val pipeline = Pipeline.create(sparkPipelineOptions)

    // Input + processing + OutputS
    val output = pipeline
      .apply(Create.of(JavaConversions.seqAsJavaList(WORDS)).withCoder(StringUtf8Coder.of()))
      .apply(new CountWords())

    // Result
    val result: EvaluationResult = pipeline.run().asInstanceOf[EvaluationResult]

    result.get(output)
  }
}
