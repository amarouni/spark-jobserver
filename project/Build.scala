import bintray.Plugin.bintrayPublishSettings
import com.typesafe.sbt.SbtScalariform._
import sbt.Keys._
import sbt.{Credentials, _}
import sbtassembly.AssemblyPlugin.autoImport._
import scoverage.ScoverageKeys._

import scalariform.formatter.preferences._

// There are advantages to using real Scala build files with SBT:
//  - Multi-JVM testing won't work without it, for now
//  - You get full IDE support
object JobServerBuild extends Build {

  lazy val dirSettings = Seq(
    unmanagedSourceDirectories in Compile <<= Seq(baseDirectory(_ / "src" )).join,
    unmanagedSourceDirectories in Test <<= Seq(baseDirectory(_ / "test" )).join,
    scalaSource in Compile <<= baseDirectory(_ / "src" ),
    scalaSource in Test <<= baseDirectory(_ / "test" )
  )

  import Dependencies._
  import JobServerRelease._

  lazy val akkaApp = Project(id = "akka-app", base = file("akka-app"),
    settings = commonSettings ++ Seq(
      description := "Common Akka application stack: metrics, tracing, logging, and more.",
      libraryDependencies ++= coreTestDeps ++ akkaDeps
    ) ++ publishSettings
  )

  lazy val jobServer = Project(id = "job-server", base = file("job-server"),
    settings = commonSettings ++ revolverSettings ++ Assembly.settings ++ Seq(
      description  := "Spark as a Service: a RESTful job server for Apache Spark",
      libraryDependencies ++= sparkDeps ++ slickDeps ++ securityDeps ++ coreTestDeps,

      // Automatically package the test jar when we run tests here
      // And always do a clean before package (package depends on clean) to clear out multiple versions
      test in Test <<= (test in Test).dependsOn(packageBin in Compile in jobServerTestJar)
                                     .dependsOn(clean in Compile in jobServerTestJar),

      console in Compile <<= Defaults.consoleTask(fullClasspath in Compile, console in Compile),

      // Adds the path of extra jars to the front of the classpath
      fullClasspath in Compile <<= (fullClasspath in Compile).map { classpath =>
        extraJarPaths ++ classpath
      },
      // Must disable this due to a bug with sbt-assembly 0.14's shading.... :(
      // See https://github.com/sbt/sbt-assembly/issues/172#issuecomment-169013214
      test in assembly := {},
      // Must run the examples and tests in separate JVMs to avoid mysterious
      // scala.reflect.internal.MissingRequirementError errors. (TODO)
      // TODO: Remove this once we upgrade to Spark 1.4 ... see resolution of SPARK-5281.
      // Also: note that fork won't work when VPN is on or other funny networking
      fork in Test := true
      ) ++ publishSettings
  ) dependsOn(akkaApp, jobServerApi)

  lazy val jobServerTestJar = Project(id = "job-server-tests", base = file("job-server-tests"),
                                      settings = commonSettings ++ jobServerTestJarSettings
                                     ) dependsOn(jobServerApi)

  // copy dependencies with : mvn -f beam-job-server_2.11-0.6.2.pom clean dependency:copy-dependencies
  // -DexcludeScope=provided -DexcludeGroupIds=spark.jobserver
  lazy val beamJobServer = Project(id = "beam-job-server", base = file("beam-job-server"),
                                      settings = commonSettings ++ beamSettings
                                    ) dependsOn(jobServerApi)

  lazy val jobServerApi = Project(id = "job-server-api",
                                  base = file("job-server-api"),
                                  settings = commonSettings ++ publishSettings)

  lazy val jobServerExtras = Project(id = "job-server-extras",
                                     base = file("job-server-extras"),
                                     settings = commonSettings ++ jobServerExtrasSettings
                                    ) dependsOn(jobServerApi,
                                                jobServer % "compile->compile; test->test")

  // This meta-project aggregates all of the sub-projects and can be used to compile/test/style check
  // all of them with a single command.
  //
  // NOTE: if we don't define a root project, SBT does it for us, but without our settings
  lazy val root = Project(id = "root", base = file("."),
                    settings = commonSettings ++ ourReleaseSettings ++ rootSettings ++ dockerSettings
                  ).aggregate(jobServer, jobServerApi, jobServerTestJar, akkaApp, jobServerExtras).
                   dependsOn(jobServer, jobServerExtras)

  lazy val jobServerExtrasSettings = revolverSettings ++ Assembly.settings ++ publishSettings ++ Seq(
    libraryDependencies ++= sparkExtraDeps,
    // Extras packages up its own jar for testing itself
    test in Test <<= (test in Test).dependsOn(packageBin in Compile)
                                   .dependsOn(clean in Compile),
    fork in Test := true,
    // Temporarily disable test for assembly builds so folks can package and get started.  Some tests
    // are flaky in extras esp involving paths.
    test in assembly := {},
    exportJars := true
  )

  lazy val jobServerTestJarSettings = Seq(
    libraryDependencies ++= sparkDeps ++ apiDeps,
    publishArtifact := false,
    description := "Test jar for Spark Job Server",
    exportJars := true        // use the jar instead of target/classes
  )

  lazy val beamSettings = Seq(
    libraryDependencies ++= beamJobServerDeps,
    description := "Beam env in SJS",
    resolvers += "Datastreams" at "http://newbuild.talend.com:8081/" +
      "nexus/content/repositories/snapshots",
    resolvers += "beam nightly" at "https://repository.apache.org/content/groups/snapshots/",
    scalaVersion := "2.11.8",
    copyJarsTask,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
  )

  import sbtdocker.DockerKeys._

  lazy val dockerSettings = Seq(
    // Make the docker task depend on the assembly task, which generates a fat JAR file
    docker <<= (docker dependsOn (assembly in jobServerExtras)),
    docker <<= (docker dependsOn ((Keys.`package` in Compile) in beamJobServer)),
    docker <<= (docker dependsOn ((Keys.makePom in Compile) in beamJobServer)),
    dockerfile in docker := {
      val artifact = (outputPath in assembly in jobServerExtras).value
      val artifactTargetPath = s"/app/${artifact.name}"
      val beamArtifact = (artifactPath in (Compile, packageBin) in beamJobServer).value
      val beamArtifactTargetPath = s"/app/${beamArtifact.name}"
      val pom = ((Keys.makePom in Compile) in beamJobServer).value
      val pomPath = s"/app/${pom.name}"
      new sbtdocker.mutable.Dockerfile {
        from(s"java:${javaVersion}")
        // Dockerfile best practices: https://docs.docker.com/articles/dockerfile_best-practices/
        expose(8090)
        expose(9999)

        copy(artifact, artifactTargetPath)
        copy(beamArtifact, beamArtifactTargetPath)
        copy(pom, pomPath)
        copy(baseDirectory(_ / "bin" / "server_start.sh").value, file("app/server_start.sh"))
        copy(baseDirectory(_ / "bin" / "server_stop.sh").value, file("app/server_stop.sh"))
        copy(baseDirectory(_ / "bin" / "manager_start.sh").value, file("app/manager_start.sh"))
        copy(baseDirectory(_ / "bin" / "setenv.sh").value, file("app/setenv.sh"))
        copy(baseDirectory(_ / "config" / "log4j-server.properties").value, file("app/log4j-server.properties"))
        copy(baseDirectory(_ / "config" / "docker.conf").value, file("app/docker.conf"))

        // test yarn cluster mode
        copy(baseDirectory(_ / "config" / "yarn.conf").value, file("app/docker.conf"))
        copy(baseDirectory(_ / "config" / "core-site.xml").value, file("/cluster-config/core-site.xml"))
        copy(baseDirectory(_ / "config" / "hdfs-site.xml").value, file("/cluster-config/hdfs-site.xml"))
        copy(baseDirectory(_ / "config" / "yarn-site.xml").value, file("/cluster-config/yarn-site.xml"))
        env("YARN_CONF_DIR", "/cluster-config")
        env("HADOOP_CONF_DIR", "/cluster-config")

        copy(baseDirectory(_ / "config" / "docker.sh").value, file("app/settings.sh"))
        copy(baseDirectory(_ / "beam-job-server" / "pom.xml").value, file("app/datastreams_pom.xml"))
        copy(baseDirectory(_ / "beam-job-server" / "settings.xml").value, file("app/datastreams_settings.xml"))

        // Resolve & download beam job server dependencies with mvn
        runRaw("wget http://apache.mirrors.ovh.net/ftp.apache.org/dist/maven/maven-3/3.3.9/binaries/apache-maven-3.3.9-bin.tar.gz -O /opt/apache-maven-3.3.9-bin.tar.gz && cd /opt && tar -xzvf apache-maven-3.3.9-bin.tar.gz && mkdir -p /opt/datastreams-deps  && /opt/apache-maven-3.3.9/bin/mvn -s /app/datastreams_settings.xml -f /app/datastreams_pom.xml clean dependency:copy-dependencies -DoutputDirectory=/opt/datastreams-deps")

        // Including envs in Dockerfile makes it easy to override from docker command
        env("JOBSERVER_MEMORY", "1G")
        env("SPARK_HOME", "/spark")
        env("SPARK_BUILD", s"spark-${sparkVersion}-bin-hadoop2.6")
        // Use a volume to persist database between container invocations
        run("mkdir", "-p", "/database")

        // Download and install spark
        // runRaw("wget \"https://talend365-my.sharepoint.com/personal/amarouni_talend_com/_layouts/15/guestaccess.aspx?guestaccesstoken=rvL4igFEB61xrZJj154bhOQCTbWDe4l4QgFhiHSluWI%3d&docid=0e108980753554592a0efeb365726b1ea&rev=1\" -O /tmp/spark-1.6.2-bin-hadoop-2.6-scala-2.11.tgz && tar -xvf /tmp/spark-1.6.2-bin-hadoop-2.6-scala-2.11.tgz -C /tmp && mv /tmp/spark-1.6.2-bin-hadoop-2.6-scala-2.11 /spark && rm /tmp/spark-1.6.2-bin-hadoop-2.6-scala-2.11.tgz")
        copy(baseDirectory(_ / "datastreams-deps/" / "spark-1.6.2-bin-hadoop-2.6-scala-2.11.tgz").value, file("/tmp/spark-1.6.2-bin-hadoop-2.6-scala-2.11.tgz"))
        runRaw("tar -xvf /tmp/spark-1.6.2-bin-hadoop-2.6-scala-2.11.tgz -C /tmp && mv /tmp/spark-1.6.2-bin-hadoop-2.6-scala-2.11 /spark && rm /tmp/spark-1.6.2-bin-hadoop-2.6-scala-2.11.tgz")
        volume("/database")
        entryPoint("app/server_start.sh")
      }
    },
    imageNames in docker := Seq(
      sbtdocker.ImageName(namespace = Some("talend"),
                          repository = "spark-jobserver",
                          tag = Some(s"${version.value}-datastreams-0.1.0"))
    )
  )

  lazy val rootSettings = Seq(
    // Must run Spark tests sequentially because they compete for port 4040!
    parallelExecution in Test := false,
    publishArtifact := false,
    concurrentRestrictions := Seq(
      Tags.limit(Tags.CPU, java.lang.Runtime.getRuntime().availableProcessors()),
      // limit to 1 concurrent test task, even across sub-projects
      // Note: some components of tests seem to have the "Untagged" tag rather than "Test" tag.
      // So, we limit the sum of "Test", "Untagged" tags to 1 concurrent
      Tags.limitSum(1, Tags.Test, Tags.Untagged))
  )

  import spray.revolver.RevolverPlugin.autoImport._
  lazy val revolverSettings = Seq(
    javaOptions in reStart += jobServerLogging,
    // Give job server a bit more PermGen since it does classloading
    javaOptions in reStart += "-XX:MaxPermSize=256m",
    javaOptions in reStart += "-Djava.security.krb5.realm= -Djava.security.krb5.kdc=",
    // This lets us add Spark back to the classpath without assembly barfing
    fullClasspath in reStart := (fullClasspath in Compile).value,
    mainClass in reStart := Some("spark.jobserver.JobServer")
  )

  // To add an extra jar to the classpath when doing "re-start" for quick development, set the
  // env var EXTRA_JAR to the absolute full path to the jar
  lazy val extraJarPaths = Option(System.getenv("EXTRA_JAR"))
                             .map(jarpath => Seq(Attributed.blank(file(jarpath))))
                             .getOrElse(Nil)

  // Create a default Scala style task to run with compiles
  lazy val runScalaStyle = taskKey[Unit]("testScalaStyle")

  lazy val commonSettings = Defaults.coreDefaultSettings ++ dirSettings ++ implicitlySettings ++ Seq(
    organization := "spark.jobserver",
    crossPaths   := true,
    crossScalaVersions := Seq("2.10.6","2.11.8"),
    scalaVersion := "2.11.8",
    publishTo    := Some(Resolver.file("Unused repo", file("target/unusedrepo"))),

    // scalastyleFailOnError := true,
    runScalaStyle := {
      org.scalastyle.sbt.ScalastylePlugin.scalastyle.in(Compile).toTask("").value
    },
    (compile in Compile) <<= (compile in Compile) dependsOn runScalaStyle,

    // In Scala 2.10, certain language features are disabled by default, such as implicit conversions.
    // Need to pass in language options or import scala.language.* to enable them.
    // See SIP-18 (https://docs.google.com/document/d/1nlkvpoIRkx7at1qJEZafJwthZ3GeIklTFhqmXMvTX9Q/edit)
    scalacOptions := Seq("-deprecation", "-feature",
                         "-language:implicitConversions", "-language:postfixOps"),
    resolvers    ++= Dependencies.repos,
    libraryDependencies ++= apiDeps,
    parallelExecution in Test := false,

    // We need to exclude jms/jmxtools/etc because it causes undecipherable SBT errors  :(
    ivyXML :=
      <dependencies>
        <exclude module="jms"/>
        <exclude module="jmxtools"/>
        <exclude module="jmxri"/>
      </dependencies>
  ) ++ scalariformPrefs ++ scoverageSettings

  lazy val scoverageSettings = {
    // Semicolon-separated list of regexs matching classes to exclude
    coverageExcludedPackages := ".+Benchmark.*"
  }

  lazy val publishSettings = bintrayPublishSettings ++ Seq(
    licenses += ("Apache-2.0", url("http://choosealicense.com/licenses/apache/")),
    bintray.Keys.bintrayOrganization in bintray.Keys.bintray := Some("spark-jobserver")
  )

  // change to scalariformSettings for auto format on compile; defaultScalariformSettings to disable
  // See https://github.com/mdr/scalariform for formatting options
  lazy val scalariformPrefs = defaultScalariformSettings ++ Seq(
    ScalariformKeys.preferences := FormattingPreferences()
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      // This was deprecated.
      //.setPreference(PreserveDanglingCloseParenthesis, false)
  )

  // This is here so we can easily switch back to Logback when Spark fixes its log4j dependency.
  lazy val jobServerLogbackLogging = "-Dlogback.configurationFile=config/logback-local.xml"
  lazy val jobServerLogging = "-Dlog4j.configuration=config/log4j-local.properties"

  val copyJars = TaskKey[Unit]("copyJars", "Copy all dependency jars to target/lib")
  val  copyJarsTask = copyJars := {
    val files: Seq[File] = (fullClasspath in Compile).value.files.filter( !_.isDirectory)
    //files.foreach(println)
    //println("file=" + file("segmenthandler/target"))
    files.foreach( f => IO.copyFile(f, file("beamDeps/" + f.getName())))
  }

}
