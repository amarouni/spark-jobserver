package org.talend.sjs.beam

import com.typesafe.config.{Config, ConfigRenderOptions}
import org.apache.beam.runners.spark.SparkPipelineOptions
import org.apache.beam.sdk.Pipeline
import org.apache.spark.SparkContext
import org.apache.spark.api.java.JavaSparkContext
import org.slf4j.LoggerFactory
import org.talend.datastreams.beam.compiler.{PipelineSpecCompiler, PipelineSpecContext}
import org.talend.datastreams.beam.compiler.common.IBeamCompiler
import spark.jobserver.{SparkJob, SparkJobInvalid, SparkJobValid, SparkJobValidation}

object DatastreamsJob extends SparkJob {

  val logger = LoggerFactory.getLogger(getClass)
  val renderOpts = ConfigRenderOptions.defaults()
    .setOriginComments(false)
    .setComments(false)
    .setJson(true)
    .setFormatted(false)
  val bc: IBeamCompiler[PipelineSpecContext] = PipelineSpecCompiler.getInstance()
  var pipelineSpecContext: PipelineSpecContext = _

  override def validate(sc: SparkContext, config: Config): SparkJobValidation = {

    logger.debug("Starting SparkJobValidation")

    // Get the DSL from jobConfig
    val dslConfig = config.getConfig("job")
    val dsl: String = dslConfig.root().render(renderOpts)

    logger.debug(dsl)

    // Create the compiler context
    pipelineSpecContext = new PipelineSpecContext(dsl)

    // Validate before execution
    try {
      logger.info("Validating Input DSL")
      pipelineSpecContext.validate()
      logger.info("Input DSL is valid")
      SparkJobValid
    }catch {
      case e: Exception => {
        logger.error("Error validating the input DSL")
        SparkJobInvalid(e.getCause.toString)
      }
    }
  }

  override def runJob(sc: SparkContext, config: Config): Any = {

    logger.debug("Starting SparkJobExecution")

    // Pre compilation
    logger.debug("Starting the Pre-compilation Phase")
    val OptimizedPipelineSpecContext: PipelineSpecContext = bc.preCompile(pipelineSpecContext)
    logger.debug("Pre-compilation Phase is done")
    // compilation
    logger.debug("Starting the compilation Phase")
    val compiledBeamPipeline: Pipeline = bc.compile(OptimizedPipelineSpecContext)
    logger.debug("Compilation Phase is done")
    // post compilation
    logger.debug("Starting the post-compilation Phase")
    val optimizedCompiledBeamPipeline: Pipeline = bc.postCompile(
      OptimizedPipelineSpecContext,compiledBeamPipeline)
    logger.debug("Post-compilation Phase is done")

    // Inject Spark context into PipelineOptions at the last moment
    logger.debug("Injecting Spark Context in Beam Pipeline Options")
    val options: SparkPipelineOptions = optimizedCompiledBeamPipeline
      .getOptions
      .as(classOf[SparkPipelineOptions])
    options.setProvidedSparkContext(new JavaSparkContext(sc))

    // Run Pipeline
    try {
      logger.debug("Starting Beam pipeline execution")
      optimizedCompiledBeamPipeline.run()
    } catch  {
      // TODO : Pipeline failed now what ??
      case e: Exception => throw new RuntimeException(e);
    }

    logger.debug("Done SparkJobExecution")

    sc.getConf.get("spark.ui.port")
  }

}
