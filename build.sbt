import sbt._
import sbt.Keys._

//import org.allenai.plugins.CoreDependencies._
//import org.allenai.plugins.CoreRepositories.Resolvers
//import org.allenai.plugins.StylePlugin

val cogcompNLPVersion = "3.0.89"
val ccgGroupId = "edu.illinois.cs.cogcomp"

lazy val commonSettings = Seq(
  organization := ccgGroupId,
  version := "1.5",
  scalaVersion := "2.11.8",
  javaOptions ++= Seq("-Xmx20G"),
  // Make sure SCIP libraries are locatable.
  javaOptions += s"-Djava.library.path=lib",
  envVars ++= Map(
    "LD_LIBRARY_PATH" -> "lib",
    "DYLD_LIBRARY_PATH" -> "lib"
  ),
  includeFilter in unmanagedJars := "*.jar" || "*.so" || "*.dylib",
  fork := true
)

// TODO(danm): This is used enough projects to be in allenai/sbt-plugins CoreDependencies.
def nlpstack(component: String) = ("org.allenai.nlpstack" %% s"nlpstack-$component" % "1.17") // exclude("org.slf4j", "log4j-over-slf4j")
  .exclude("commons-logging", "commons-logging")
  .exclude("edu.stanford.nlp", "stanford-corenlp")
  .exclude("org.slf4j", "log4j-over-slf4j")

def textualEntailment(component: String) = ("org.allenai.textual-entailment" %% component % "1.0.6-SNAPSHOT")
    .exclude("org.slf4j", "log4j-over-slf4j")
    .exclude("edu.stanford.nlp", "stanford-corenlp")

def ccgLib(component: String) = (ccgGroupId % component % cogcompNLPVersion withSources)

val sprayVersion = "1.3.3"
def sprayModule(id: String): ModuleID = "io.spray" %% s"spray-$id" % sprayVersion
val sprayClient = sprayModule("client")

lazy val envUser = System.getenv("COGCOMP_USER")
lazy val user = if (envUser == null) System.getProperty("user.name") else envUser
lazy val keyFile = new java.io.File(Path.userHome.absolutePath + "/.ssh/id_rsa")

lazy val publishSettings = Seq(
  publishTo := Some(
    Resolver.ssh(
      "CogcompSoftwareRepo", "bilbo.cs.illinois.edu",
      "/mounts/bilbo/disks/0/www/cogcomp/html/m2repo/") as (user, keyFile)
  )
)

lazy val root = (project in file(".")).
  //enablePlugins(StylePlugin).
  settings(commonSettings: _*).
  settings(publishSettings: _*).
  settings(
    name := "text-ilp",
    libraryDependencies ++= Seq(
      textualEntailment("interface"),
      textualEntailment("service"),
      //  allenAiTestkit % "test",
      "org.allenai.common" %% "common-core" % "1.4.6",
      "org.allenai.common" %% "common-cache" % "1.4.6",
      "commons-io" % "commons-io" % "2.4",
      "net.sf.opencsv" % "opencsv" % "2.1",
      "com.typesafe.play" % "play-json_2.11" % "2.5.9",
      "org.rogach" %% "scallop" % "2.0.5",
      "com.google.inject" % "guice" % "4.0",
      "net.debasishg" %% "redisclient" % "3.0",
      "com.medallia.word2vec" % "Word2VecJava" % "0.10.3",
      ccgLib("illinois-core-utilities"),
      ccgLib("illinois-inference"),
      ccgLib("illinois-nlp-pipeline"),
      ccgGroupId % "saul-examples_2.11" % "0.5.7-SNAPSHOT",
      ccgGroupId % "scip-jni" % "3.1.1",
      nlpstack("chunk") ,
      nlpstack("lemmatize"),
      nlpstack("tokenize"),
      nlpstack("postag"),
      nlpstack("core"),
      sprayClient,
      "org.scalatest" % "scalatest_2.11" % "2.2.4",
      "org.elasticsearch" % "elasticsearch" % "2.4.1"
    ),
    resolvers ++= Seq(
//      "Artima Maven Repository" at "http://repo.artima.com/releases"
//      Resolver.mavenLocal,
      Resolver.bintrayRepo("allenai", "maven"),
      Resolver.bintrayRepo("allenai", "private"),
      "CogcompSoftware" at "http://cogcomp.cs.illinois.edu/m2repo/"
    )
  )

lazy val viz = (project in file("viz")).
  settings(commonSettings: _*).
  dependsOn(root).
  aggregate(root).
  enablePlugins(PlayScala).
  settings(
    name:= "text-ilp-visualization",
    libraryDependencies ++= Seq(
      filters,
      "org.scalatestplus.play" %% "scalatestplus-play" % "1.5.1" % Test,
      "com.typesafe.play" % "play_2.11" % "2.5.10",
      "org.webjars" %% "webjars-play" % "2.4.0-1",
      "org.webjars" % "bootstrap" % "3.3.7",
      "org.webjars" % "jquery" % "3.1.1",
      "org.webjars" % "headjs" % "1.0.3"
    ),
    resolvers ++= Seq("scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"),
    projectDependencies := {
      Seq((projectID in root).value.exclude("org.slf4j", "slf4j-log4j12"))
    }
  )