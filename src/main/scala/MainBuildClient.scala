import build_client.TestBuildClient
import java.io.{File, InputStream, OutputStream}
import java.util
import java.util.concurrent.CompletableFuture

import play.api.libs.json._
import java.util.Collections

import ch.epfl.scala.bsp4j._
import org.eclipse.lsp4j.jsonrpc.Launcher

import collection.JavaConverters._
import scala.io.Source
import os._


trait BspServer extends BuildServer with ScalaBuildServer

case class ServerInformation(serverName: String,
                             serverVersion: String,
                             serverBspVersion: String,
                             serverLanguages: Seq[String],
                             serverStartCommand: String)

/**
  * Main class for running a build client. Command line arguments
  * are used to interact with the TestBuildClient. Example commands:
  *
  * - initialize
  * - initialized
  * - shutdown
  * - exit
  * - compile
  * - test
  * - run
  * - clan-cache
  * - target-source
  *
  */
object MainBuildClient extends App {

  var uniqueTargetid = 0
  val build_client = new TestBuildClient

  val file = Source.fromFile(os.list(os.pwd / ".bsp").head.toNIO.toFile)

  var canCompile = false
  var canRun = false
  var canTest = false

  val jsonResult = file.mkString("")
  file.close()
  val serverInfo = getServerInformation(jsonResult)
  println("Command: " + serverInfo.serverStartCommand)
  val process = Runtime.getRuntime.exec(serverInfo.serverStartCommand, null)
  println("Started process")
  val inStream = process.getInputStream
  val outStream = process.getOutputStream
  val errStream = process.getErrorStream
  println("Got streams")
  //println(Source.fromInputStream(errStream).getLines().mkString("\n"))
  //println("Read from streams")
  val buildServer = generateBuildServer(build_client, inStream, outStream)
  val serverName = serverInfo.serverName
  val serverVersion = serverInfo.serverVersion
  val serverBspVersion = serverInfo.serverBspVersion
  val serverLanguages = serverInfo.serverLanguages

  println(process.isAlive)
  println(buildServer.getClass)
  val allTargets = buildServer.workspaceBuildTargets().get().getTargets.asScala.toList
  println("Got targets")
  println(buildServer.buildTargetSources(new SourcesParams(allTargets.map(target => target.getId).asJava)).get)
  var canCompileTargets = Seq.empty[BuildTarget]
  var canRunTargets = Seq.empty[BuildTarget]
  var canTestTargets = Seq.empty[BuildTarget]


  println(Console.WHITE + "Started process and got streams")
  println(allTargets)
  while ( true ) {

    val request = scala.io.StdIn.readLine()

    try {
      request match {
        case "initialize" =>
          val result = initialize().get()
          setServerCapabilities(result)
          filterTargets(allTargets)
          assertInitializeResult(result)
          println(Console.WHITE + "Server initialization OK")

        case "initialized" =>
          buildServer.onBuildInitialized()
          println(Console.WHITE + "Server initialized OK")

        case "build-targets" =>
          for (target <- allTargets) {
            println(Console.WHITE+ "Target: " + target + " \n")
          }

        case "compile" =>

          if (canCompile) {
            for (compileTarget <- canCompileTargets) {
              assertCompileResult(compile(compileTarget).get())
            }
            println(Console.WHITE + "Compilation OK")
          } else "Your server does not have compile capabilities"

        case "run" =>
          if (canRun) {
            for (runTarget <- canRunTargets) {
              assertRunResult(run(runTarget).get())
            }
            println(Console.WHITE + "Running OK")
          } else println(Console.WHITE + "Your server does not have run capabilities")

        case "test" =>
          if (canTest) {
            for (runTarget <- canRunTargets) {
              assertRunResult(run(runTarget).get())
            }
            println(Console.WHITE + "Testing OK")
          } else println(Console.WHITE + "Your server does not have test capabilities")

        case "clean-cache" =>
          for (cleanTarget <- allTargets) {
            assertCleanCacheResult(cleanCache(cleanTarget).get)
          }
          println(Console.WHITE + "Clean cache OK")

        case "dependencies" =>
          for (target <- allTargets) {
            val depend = assertDependencies(dependencies(target).get(), target.getId)
            println("Fetching dependencies: " + depend.mkString("\n") + " OK")
          }

        case "sources" =>
          println(buildServer.buildTargetSources(new SourcesParams(allTargets.map(target => target.getId).asJava)).get)

        case "inverse-sources" =>
          println(buildServer.buildTargetInverseSources(new InverseSourcesParams(new TextDocumentIdentifier(
            "/home/alexandra/mill_exercise/mill_exercise/src/Bill.scala"
          ))).get)

        case "scalac-options" =>
          println(buildServer.asInstanceOf[BuildServer with ScalaBuildServer].buildTargetScalacOptions(
            new ScalacOptionsParams(allTargets.map(target => target.getId).asJava)
          ).get)

        case "target-source" =>
          assertTargetSourceRelation()
          println(Console.WHITE + "Targets - Source correlation OK")

        case "shutdown" =>
          buildServer.buildShutdown()
          assert(process.isAlive, message = "The server should not die before sending the response to the shutdown request")
          println(Console.WHITE + "Server shutdown OK - the server did not stop yet, needs to wait for exit notification")

        case "exit" =>
          buildServer.onBuildExit()
          Thread.sleep(1000)
          assert(!process.isAlive, message = "The server should stop after receiving the exit notification from the client")
          println(Console.WHITE + "Server exit OK - the server stopped")

        case _ => println(Console.WHITE + "Not a valid command, try again")
      }
    } catch {
      case exp: Error => println(Console.RED + "An exception/error occurred: " + exp.getMessage + " " + exp.getStackTrace.toString)
    }
  }

  // return a tuple of the server display name, server version, bsp version and language capabilities
  def getServerInformation(jsonString: String): ServerInformation = {

    val jsValConnection = Json.parse(jsonString)
    val displayname = (jsValConnection \\ "name").head.as[String]
    val version = (jsValConnection \\ "version").head.as[String]
    val bspVersion = (jsValConnection \\ "bspVersion").head.as[String]
    val languages = (jsValConnection \\ "languages").map(jsVal => jsVal.as[List[String]]).head
    val command = (jsValConnection \\ "argv").map(jsVal => jsVal.as[List[String]]).head.mkString(" ")
    ServerInformation(displayname, version, bspVersion, languages, command)

  }

  def generateBuildServer(client: BuildClient, inS: InputStream, outS: OutputStream): BuildServer = {

    val launcher = new Launcher.Builder[BspServer]()
      .setRemoteInterface(classOf[BspServer])
      .setInput(inStream)
      .setOutput(outStream)
      .setLocalService(client)
      .create()
    launcher.startListening()
    val bspServer = launcher.getRemoteProxy
    client.onConnectWithServer(bspServer)

    bspServer
  }

  def initialize(): CompletableFuture[InitializeBuildResult] = {
    val supportedLanguages = new util.ArrayList[String] ()
    supportedLanguages.add("scala")
    supportedLanguages.add("java")

    val params = new InitializeBuildParams("test_client",
      "0.0.1",
      "2.0",
      "file:/home/alexandra/build_client/",
      new BuildClientCapabilities(supportedLanguages))

    buildServer.buildInitialize(params)
  }

  def setServerCapabilities(result: InitializeBuildResult): Unit = {
    try {
      result.getCapabilities.getCompileProvider.getLanguageIds
      canCompile = true
    } catch {
      case _: Exception => println(Console.WHITE + "Your server does not have compile capabilities")
    }

    try {
      result.getCapabilities.getTestProvider.getLanguageIds
      canTest = true
    } catch {
      case _: Exception => println(Console.WHITE + "Your server does not have test capabilities")
    }

    try {
      result.getCapabilities.getRunProvider.getLanguageIds
      canRun = true
    } catch {
      case _: Exception => println(Console.WHITE + "Your server does not have run capabilities")
    }
  }

  def filterTargets(targets: List[BuildTarget]): Unit = {
    canCompileTargets = allTargets.filter( target => target.getCapabilities.getCanCompile)
    canRunTargets = allTargets.filter( target => target.getCapabilities.getCanRun)
    canTestTargets = allTargets.filter( target => target.getCapabilities.getCanTest)
  }

  def assertInitializeResult(result: InitializeBuildResult): Unit = {
    assert (result.getDisplayName == serverName,
      message = "The display name was not transmitted correctly: ")
    assert (result.getBspVersion.substring(0, 3) == serverBspVersion.substring(0, 3),
      message = "The bsp version was not transmitted correctly: " + result.getBspVersion + s" but should be $serverBspVersion")
    assert (result.getVersion == serverVersion,
      message = "The server version was not transmitted correctly: " + result.getVersion + s" but should be $serverVersion" )

    if ( canCompile ) {
      assert (result.getCapabilities.getCompileProvider.getLanguageIds.asScala == serverLanguages.toList,
        message = "The supported languages for compilation  are not as in the connection file")
    }

    if ( canRun ) {
      assert (result.getCapabilities.getRunProvider.getLanguageIds.asScala == serverLanguages.toList,
        message = "The supported languages for testing are not as in the connection file")
    }

    if ( canTest ) {
      assert (result.getCapabilities.getTestProvider.getLanguageIds.asScala == serverLanguages.toList,
        message = "The supported languages for running are not as in the connection file")
    }
  }

  def compile(compileTarget: BuildTarget): CompletableFuture[CompileResult] = {

    val compileParams = new CompileParams(Collections.singletonList(compileTarget.getId))
    compileParams.setOriginId(uniqueTargetid.toString)
    buildServer.buildTargetCompile(compileParams)
  }

  def assertCompileResult(result: CompileResult): Unit = {
    //    assert(result.getOriginId == uniqueTargetid.toString,
    //      message = "The origin id assigned by the client was not transmitted back correctly, got: " + result.getOriginId +
    //                "but expected : " + uniqueTargetid.toString)
    println(result)
    println("Task starts: ", build_client.taskStarts)
    println("Task finishes: ", build_client.taskFinishes)
    println("Compile report: ", build_client.compileReports)
    uniqueTargetid = uniqueTargetid + 1
  }

  def run(runTarget: BuildTarget): CompletableFuture[RunResult] = {
    val runParams = new RunParams(runTarget.getId)
    runParams.setOriginId(uniqueTargetid.toString)
    buildServer.buildTargetRun(runParams)
  }

  def assertRunResult(result: RunResult): Unit = {
    assert(result.getOriginId == uniqueTargetid.toString,
      message = "The origin id assigned by the client was not transmitted back correctly, got: " + result.getOriginId +
        "but expected : " + uniqueTargetid.toString)
    uniqueTargetid = uniqueTargetid + 1
  }

  def test(testTarget: BuildTarget): CompletableFuture[TestResult] = {
    val testParams = new TestParams(Collections.singletonList(testTarget.getId))
    testParams.setOriginId(uniqueTargetid.toString)
    buildServer.buildTargetTest(testParams)
  }

  def assertTestResult(result: TestResult): Unit = {
    assert(result.getOriginId == uniqueTargetid.toString,
      message = "The origin id assigned by the client was not transmitted back correctly, got: " + result.getOriginId +
        "but expected : " + uniqueTargetid.toString)
    uniqueTargetid = uniqueTargetid + 1
  }

  def cleanCache(cleanTarget: BuildTarget): CompletableFuture[CleanCacheResult] = {
    val cleanParams = new CleanCacheParams(Collections.singletonList(cleanTarget.getId))
    buildServer.buildTargetCleanCache(cleanParams)
  }

  def assertCleanCacheResult(result: CleanCacheResult): Unit = {
    assert( result.getCleaned, message = "The server reported that the cache was not cleaned")
  }

  def dependencies(target: BuildTarget): CompletableFuture[DependencySourcesResult] = {
    val dependencyParams = new DependencySourcesParams(Collections.singletonList(target.getId))
    buildServer.buildTargetDependencySources(dependencyParams)
  }

  def assertDependencies(result: DependencySourcesResult, id: BuildTargetIdentifier): List[String] = {
    assert(result.getItems.get(0).getTarget == id, message = "The target id from the response is incorrect")
    result.getItems.get(0).getSources.asScala.toList
  }

  def getTargetSourcesList(target: BuildTarget): List[String] = {
    val result = buildServer.buildTargetSources(
      new SourcesParams(Collections.singletonList(target.getId))).get
    result.getItems.get(0).getSources.asScala.map(sourceItem => sourceItem.getUri).toList
  }

  def getTargetToSourceMap: Map[BuildTarget, List[String]] = {
    (for (target <- allTargets) yield (target, getTargetSourcesList(target))).toMap
  }

  def getAllSources: List[String] = {
    getTargetToSourceMap.values.reduceLeft((B, sourceList) => B ++ sourceList)
  }

  def getSourceTargetsList(source: String): List[BuildTargetIdentifier] = {
    val inverseSourcesParams = new InverseSourcesParams(new TextDocumentIdentifier(source))
    buildServer.buildTargetInverseSources(inverseSourcesParams).get().getTargets.asScala.toList
  }

  def getSourceToTargetMap(allSources: List[String]): Map[String, List[BuildTargetIdentifier]] = {
    (for (source <- allSources) yield (source, getSourceTargetsList(source))).toMap
  }

  def assertTargetSourceRelation(): Unit = {
    val targetToSource = getTargetToSourceMap
    val sourceToTarget = getSourceToTargetMap(getAllSources)

    for ( (target, sources) <- targetToSource ) {
      for ( source <- sources ) {
        assert( sourceToTarget(source).contains(target.getId),
          message = "Target is associated with a source, but the source is not associated with the target ( or the other way )")
      }
    }
  }
}