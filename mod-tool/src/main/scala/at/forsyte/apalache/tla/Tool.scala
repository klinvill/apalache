package at.forsyte.apalache.tla

import java.io.{File, FileNotFoundException}
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import at.forsyte.apalache.infra.log.LogbackConfigurator
import at.forsyte.apalache.infra.passes.{PassChainExecutor, TlaModuleMixin}
import at.forsyte.apalache.infra.{ExceptionAdapter, FailureMessage, NormalErrorMessage, PassOptionException}
import at.forsyte.apalache.tla.bmcmt.config.CheckerModule
import at.forsyte.apalache.tla.imp.passes.ParserModule
import at.forsyte.apalache.tla.tooling.{ExitCodes, Version}
import at.forsyte.apalache.tla.tooling.opt.{CheckCmd, ConfigCmd, General, ParseCmd, TestCmd, TypeCheckCmd}
import at.forsyte.apalache.tla.typecheck.passes.TypeCheckerModule
import com.google.inject.{Guice, Injector}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.configuration2.builder.fluent.Configurations
import org.apache.commons.configuration2.ex.ConfigurationException
import org.backuity.clist.{Cli, Command}
import util.ExecutionStatisticsCollector
import util.ExecutionStatisticsCollector.Selection

import scala.collection.JavaConverters._
import scala.util.Random

/**
 * Command line access to the APALACHE tools.
 *
 * @author Igor Konnov
 */
object Tool extends LazyLogging {
  lazy val ISSUES_LINK: String = "[https://github.com/informalsystems/apalache/issues]"
  lazy val ERROR_EXIT_CODE = 99
  lazy val OK_EXIT_CODE = 0

  /**
   * Run the tool in the standalone mode with the provided arguments.
   * This method calls System.exit with the computed exit code.
   * If you like to call the tool without System.exit, use the the Tool#run.
   *
   * @param args the command line arguments
   */
  def main(args: Array[String]): Unit = {
    val exitcode = run(args)
    if (exitcode == OK_EXIT_CODE) {
      Console.out.println("EXITCODE: OK")
    } else {
      Console.out.println(s"EXITCODE: ERROR ($exitcode)")
    }
    System.exit(exitcode)
  }

  /**
   * Run the tool in a library mode, that is, with a call to System.exit.
   *
   * @param args the command line arguments
   * @return the exit code; as usual, 0 means success.
   */
  def run(args: Array[String]): Int = {
    printHeaderAndStatsConfig()
    // force our programmatic logback configuration, as the autoconfiguration works unpredictably
    new LogbackConfigurator().configureDefaultContext()

    // first, call the arguments parser, which can also handle the standard commands such as version
    val command =
      Cli
        .parse(args)
        .withProgramName("apalache-mc")
        .version("%s build %s".format(Version.version, Version.build), "version")
        .withCommands(new ParseCmd, new CheckCmd, new TypeCheckCmd, new TestCmd, new ConfigCmd)

    if (!command.isInstanceOf[Some[General]]) {
      // A standard option, e.g., --version or --help. No header, no timer.
      OK_EXIT_CODE
    } else {
      // One of our commands. Print the header and measure time
      val startTime = LocalDateTime.now()

      try {
        command match {
          case Some(parse: ParseCmd) =>
            logger.info("Parse " + parse.file)
            submitStatisticsIfEnabled(Map("tool" -> "apalache", "mode" -> "parse", "workers" -> "1"))
            val injector = injectorFactory(parse)
            handleExceptions(injector, runParse(injector, parse))

          case Some(check: CheckCmd) =>
            logger.info(
                "Checker options: filename=%s, init=%s, next=%s, inv=%s"
                  .format(check.file, check.init, check.next, check.inv))
            // TODO: update workers when the multicore branch is integrated
            submitStatisticsIfEnabled(Map("tool" -> "apalache", "mode" -> "check", "workers" -> "1"))
            val injector = injectorFactory(check)
            handleExceptions(injector, runCheck(injector, check))

          case Some(test: TestCmd) =>
            logger.info(
                "Checker options: filename=%s, before=%s, action=%s, after=%s"
                  .format(test.file, test.before, test.action, test.assertion))
            submitStatisticsIfEnabled(Map("tool" -> "apalache", "mode" -> "test", "workers" -> "1"))
            val injector = injectorFactory(test)
            handleExceptions(injector, runTest(injector, test))

          case Some(typecheck: TypeCheckCmd) =>
            logger.info("Type checking " + typecheck.file)
            submitStatisticsIfEnabled(Map("tool" -> "apalache", "mode" -> "typecheck", "workers" -> "1"))
            val injector = injectorFactory(typecheck)
            handleExceptions(injector, runTypeCheck(injector, typecheck))

          case Some(config: ConfigCmd) =>
            logger.info("Configuring Apalache")
            configure(config)

          case _ =>
            OK_EXIT_CODE // nothing to do
        }
      } finally {
        printTimeDiff(startTime)
      }
    }
  }

  private def printTimeDiff(startTime: LocalDateTime): Unit = {
    val endTime = LocalDateTime.now()
    logger.info(
        "It took me %d days %2d hours %2d min %2d sec"
          .format(ChronoUnit.DAYS.between(startTime, endTime), ChronoUnit.HOURS.between(startTime, endTime) % 24,
              ChronoUnit.MINUTES.between(startTime, endTime) % 60, ChronoUnit.SECONDS.between(startTime, endTime) % 60))
    logger.info(
        "Total time: %d.%d sec"
          .format(ChronoUnit.SECONDS.between(startTime, endTime), ChronoUnit.MILLIS.between(startTime, endTime) % 1000))
  }

  private def runParse(injector: => Injector, parse: ParseCmd): Int = {
    // here, we implement a terminal pass to get the parse results
    val executor = injector.getInstance(classOf[PassChainExecutor])
    executor.options.set("io.outdir", createOutputDir())
    executor.options.set("parser.filename", parse.file.getAbsolutePath)
    executor.options.set("parser.output", parse.output)

    val result = executor.run()
    if (result.isDefined) {
      logger.info("Parsed successfully")
      val tlaModule = result.get.asInstanceOf[TlaModuleMixin].unsafeGetModule
      logger.info("Root module: %s with %d declarations".format(tlaModule.name, tlaModule.declarations.length))
      ExitCodes.NO_ERROR
    } else {
      logger.info("Parser has failed")
      ExitCodes.ERROR
    }
  }

  private def runCheck(injector: => Injector, check: CheckCmd): Int = {
    val executor = injector.getInstance(classOf[PassChainExecutor])
    executor.options.set("io.outdir", createOutputDir())
    var tuning =
      if (check.tuning != "") loadProperties(check.tuning) else Map[String, String]()
    tuning = overrideProperties(tuning, check.tuningOptions)
    logger.info("Tuning: " + tuning.toList.map { case (k, v) => s"$k=$v" }.mkString(":"))

    executor.options.set("general.tuning", tuning)
    executor.options.set("general.debug", check.debug)
    executor.options.set("smt.prof", check.smtprof)
    executor.options.set("parser.filename", check.file.getAbsolutePath)
    if (check.config != "")
      executor.options.set("checker.config", check.config)
    if (check.init != "")
      executor.options.set("checker.init", check.init)
    if (check.next != "")
      executor.options.set("checker.next", check.next)
    if (check.inv != "")
      executor.options.set("checker.inv", List(check.inv))
    if (check.cinit != "")
      executor.options.set("checker.cinit", check.cinit)
    executor.options.set("checker.nworkers", check.nworkers)
    executor.options.set("checker.length", check.length)
    executor.options.set("checker.discardDisabled", check.discardDisabled)
    executor.options.set("checker.noDeadlocks", check.noDeadlocks)
    executor.options.set("checker.algo", check.algo)
    // for now, enable polymorphic types. We probably want to disable this option for the type checker
    executor.options.set("typechecker.inferPoly", true)

    val result = executor.run()
    if (result.isDefined) {
      logger.info("Checker reports no error up to computation length " + check.length)
      ExitCodes.NO_ERROR
    } else {
      logger.info("Checker has found an error")
      ExitCodes.ERROR_COUNTEREXAMPLE
    }
  }

  private def runTest(injector: => Injector, test: TestCmd): Int = {
    // This is a special version of the `check` command that is tuned towards testing scenarios
    val executor = injector.getInstance(classOf[PassChainExecutor])
    executor.options.set("io.outdir", createOutputDir())
    // Tune for testing:
    //   1. Check the invariant only after the action took place.
    //   2. Randomize
    val seed = Math.abs(System.currentTimeMillis().toInt)
    val tuning = Map("search.invariantFilter" -> "1", "smt.randomSeed" -> seed.toString)
    logger.info("Tuning: " + tuning.toList.map { case (k, v) => s"$k=$v" }.mkString(":"))

    executor.options.set("general.tuning", tuning)
    executor.options.set("general.debug", test.debug)
    executor.options.set("smt.prof", test.smtprof)
    executor.options.set("parser.filename", test.file.getAbsolutePath)
    executor.options.set("checker.init", test.before)
    executor.options.set("checker.next", test.action)
    executor.options.set("checker.inv", List(test.assertion))
    if (test.cinit != "") {
      executor.options.set("checker.cinit", test.cinit)
    }
    executor.options.set("checker.nworkers", 1)
    // check only one instance of the action
    executor.options.set("checker.length", 1)
    // no preliminary pruning of disabled transitions
    executor.options.set("checker.discardDisabled", false)
    executor.options.set("checker.noDeadlocks", false)
    // prefer the offline mode as we have a single query
    executor.options.set("checker.algo", "offline")
    // for now, enable polymorphic types. We probably want to disable this option for the type checker
    executor.options.set("typechecker.inferPoly", true)

    val result = executor.run()
    if (result.isDefined) {
      logger.info("No example found")
      ExitCodes.NO_ERROR
    } else {
      logger.info("Checker has found an example. Check counterexample.tla.")
      ExitCodes.ERROR_COUNTEREXAMPLE
    }
  }

  private def runTypeCheck(injector: => Injector, typecheck: TypeCheckCmd): Int = {
    // type checker
    val executor = injector.getInstance(classOf[PassChainExecutor])
    executor.options.set("io.outdir", createOutputDir())
    executor.options.set("parser.filename", typecheck.file.getAbsolutePath)
    executor.options.set("typechecker.inferPoly", typecheck.inferPoly)

    executor.run() match {
      case None =>
        logger.info("Type checker [FAILED]")
        ExitCodes.ERROR

      case Some(_) =>
        logger.info("Type checker [OK]")
        ExitCodes.NO_ERROR
    }
  }

  private def loadProperties(filename: String): Map[String, String] = {
    // use an apache-commons library, as it supports variable substitution
    try {
      val config = new Configurations().properties(new File(filename))
      // access configuration properties
      var map = Map[String, String]()
      for (name: String <- config.getKeys.asScala) {
        map += (name -> config.getString(name))
      }
      map
    } catch {
      case _: FileNotFoundException =>
        throw new PassOptionException(s"The properties file $filename not found")

      case e: ConfigurationException =>
        throw new PassOptionException(s"Error in the properties file $filename: ${e.getMessage}")
    }
  }

  private def overrideProperties(props: Map[String, String], propsAsString: String): Map[String, String] = {
    def parseKeyValue(text: String): (String, String) = {
      val parts = text.split('=')
      if (parts.length != 2 || parts.head.trim == "" || parts(1) == "") {
        throw new PassOptionException(s"Expected key=value in --tune-here=$propsAsString")
      } else {
        // trim to remove surrounding whitespace from the key, but allow the value to have white spaces
        (parts.head.trim, parts(1))
      }
    }

    val hereProps = {
      if (propsAsString.trim.nonEmpty) {
        propsAsString.split(':').map(parseKeyValue).toMap
      } else {
        Map.empty
      }
    }
    // hereProps may override the values in props
    props ++ hereProps
  }

  private def createOutputDir(): Path = {
    // here we use the order 'hours-minutes and then the date', as it is much easier to use with completion
    val nicetime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH.mm-dd.MM.uuuu-"))
    val xdir = new File(System.getProperty("user.dir"), "x")
    if (!xdir.exists()) {
      xdir.mkdir()
    }
    Files.createTempDirectory(Paths.get(xdir.getAbsolutePath), nicetime)
  }

  private def injectorFactory(cmd: Command): Injector = {
    cmd match {
      case _: ParseCmd     => Guice.createInjector(new ParserModule)
      case _: CheckCmd     => Guice.createInjector(new CheckerModule)
      case _: TestCmd      => Guice.createInjector(new CheckerModule)
      case _: TypeCheckCmd => Guice.createInjector(new TypeCheckerModule)
      case _               => throw new RuntimeException("Unexpected command: " + cmd)
    }
  }

  private def handleExceptions(injector: Injector, fun: => Int): Int = {
    val adapter = injector.getInstance(classOf[ExceptionAdapter])

    try {
      fun
    } catch {
      case e: Exception if adapter.toMessage.isDefinedAt(e) =>
        adapter.toMessage(e) match {
          case NormalErrorMessage(text) =>
            logger.error(text)

          case FailureMessage(text) =>
            Console.err.println("Please report an issue at: " + ISSUES_LINK, e)
            logger.error(text, e)
        }
        ExitCodes.ERROR

      case e: PassOptionException =>
        logger.error(e.getMessage)
        ExitCodes.ERROR

      case e: Throwable =>
        Console.err.println("Please report an issue at: " + ISSUES_LINK, e)
        logger.error("Unhandled exception", e)
        ExitCodes.ERROR
    }
  }

  private def printHeaderAndStatsConfig(): Unit = {
    Console.println("# APALACHE version %s build %s".format(Version.version, Version.build))
    Console.println("#")
    Console.println("# WARNING: This tool is in the experimental stage.")
    Console.println("#          Please report bugs at: " + ISSUES_LINK)
    Console.println("# ")

    if (ExecutionStatisticsCollector.promptUser()) {
      // Statistics collection is not enabled. Cry for help.
      Console.println("# Usage statistics is OFF. We care about your privacy.")
      Console.println(
          "# If you want to help our project, consider enabling statistics with config --enable-stats=true.")
    } else {
      // Statistic collection is enabled. Thank the user
      Console.println("# Usage statistics is ON. Thank you!")
      Console.println("# If you have changed your mind, disable the statistics with config --enable-stats=false.")
    }
    Console.println("")
  }

  private def configure(config: ConfigCmd): Int = {
    config.submitStats match {
      case Some(isEnabled) =>
        val warning = "Unable to update statistics configuration. The other features will keep working."

        if (!configDirExistsOrCreated()) {
          logger.warn(warning)
        } else {
          val statCollector = new ExecutionStatisticsCollector()
          // protect against potential exceptions in the tla2tools code
          try {
            if (isEnabled) {
              statCollector.set(Selection.ON)
              logger.info("Statistics collection is ON.")
              logger.info("This also enabled TLC and TLA+ Toolbox statistics.")
            } else {
              statCollector.set(Selection.NO_ESC)
              logger.info("Statistics collection is OFF.")
              logger.info("This also disabled TLC and TLA+ Toolbox statistics.")
            }
          } catch {
            case e: Exception =>
              logger.warn(e.getMessage)
              logger.warn(warning)
          }
        }

      case None =>
        ()
    }

    ExitCodes.NO_ERROR
  }

  private def configDirExistsOrCreated(): Boolean = {
    // A temporary fix for the issue #762: create ~/.tlaplus if it does not exist
    val configDir = new File(System.getProperty("user.home", ""), ".tlaplus")
    if (configDir.exists()) {
      true
    } else {
      try {
        configDir.mkdir()
        true
      } catch {
        case e: Exception =>
          logger.warn(e.getMessage)
          logger.warn(s"Unable to create the directory $configDir.")
          false
      }
    }
  }

  // If the user has opted-in, collect statistics with the code from tlatools:
  //
  // https://github.com/tlaplus/tlaplus/blob/master/tlatools/org.lamport.tlatools/src/util/ExecutionStatisticsCollector.java
  //
  // See how TLC does it:
  // https://github.com/tlaplus/tlaplus/blob/master/tlatools/org.lamport.tlatools/src/tlc2/TLC.java
  private def submitStatisticsIfEnabled(commandParams: Map[String, String]): Unit = {
    val statCollector = new ExecutionStatisticsCollector()
    if (statCollector.isEnabled) {
      val params = new java.util.HashMap[String, String]()
      params.put("ver", "apalache-%s-%s".format(Version.version, Version.build))
      params.put("osName", System.getProperty("os.name"))
      params.put("osArch", System.getProperty("os.arch"))
      params.put("osVersion", System.getProperty("os.version"))
      params.put("jvmVendor", System.getProperty("java.vendor"))
      params.put("jvmVersion", System.getProperty("java.version"))
      params.put("jvmArch", System.getProperty("os.arch"))
      params.put("cores", Runtime.getRuntime.availableProcessors.toString)
      val heapMemory = Runtime.getRuntime.maxMemory / 1024L / 1024L
      params.put("jvmHeapMem", heapMemory.toString)
      val saltSec = Random.nextInt(600) // a random salt to introduce a bit of entropy in the timestamp
      val timestampSec = System.currentTimeMillis() / 1000 - saltSec
      params.put("ts", timestampSec.toString)
      params.putAll(commandParams.asJava)
      // fix #288: one more parameter to keep compatibility with TLC reporting
      params.put("jvmOffHeapMem", "0")
      statCollector.collect(params)
    }
  }
}
