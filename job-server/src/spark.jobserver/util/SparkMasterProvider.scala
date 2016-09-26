package spark.jobserver.util

import scala.reflect.runtime.{universe => ru}
import com.typesafe.config.{Config, ConfigException}
import org.slf4j.LoggerFactory

import scala.util.Try


trait SparkMasterProvider {

  /**
   * Implementing classes will determine what the appropriate SparkMaster is and
   * return it
   * @return A Spark Master Address
   */
  def getSparkMaster(config: Config, contextConfig: Config): String

}

object SparkMasterProvider {
  val logger = LoggerFactory.getLogger(getClass)
  val SparkMasterProperty = "spark.master-provider"

  /**
   * Will look for an Object with the name provided in the Config file and return it
   * or the DefaultSparkMasterProvider if no spark.master-provider was specified
   * @param config SparkJobserver Config
   * @return A SparkMasterProvider
   */
  def fromConfig(config: Config, contextConfig: Config): SparkMasterProvider = {

    try {
      val sparkMasterObjectName = config.getString(SparkMasterProperty)
      logger.info(s"Using $sparkMasterObjectName to determine Spark Master")
      val m = ru.runtimeMirror(getClass.getClassLoader)
      val module = m.staticModule(sparkMasterObjectName)
      val sparkMasterProviderObject = m.reflectModule(module).instance
        .asInstanceOf[SparkMasterProvider]
      sparkMasterProviderObject
    } catch {
      case me: ConfigException => DefaultSparkMasterProvider
      case e: Exception => throw e
    }
  }
}

/**
 * Default Spark Master Provider always returns "spark.master" from the passed in config
 */
object DefaultSparkMasterProvider extends SparkMasterProvider {
  val logger = LoggerFactory.getLogger(getClass)

  def getSparkMaster(config: Config, contextConfig: Config): String = {
    // get mode from submitted context's configs
    // else fail back to global (as defined for all contexts)
    Try(contextConfig.getString("mode")) getOrElse("") match {
      case "local" => {
        logger.info("Starting a local spark context with local[*]")
        "local[*]"
      }
      case _ => {
        logger.info(s"Starting the predefined spark context with ${config.getString("spark.master")}")
        config.getString("spark.master")
      }
    }
  }

}
