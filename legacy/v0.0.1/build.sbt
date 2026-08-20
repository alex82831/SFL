import scala.scalanative.build.*

scalaVersion := "3.3.1"

nativeConfig ~= {
	_.withLTO(LTO.none)
	  .withMode(Mode.default)
	  .withGC(GC.commix)
	  .withLinkStubs(true)
	  .withOptimize(true)
		.withLinkingOptions(Seq("-L/opt/homebrew/opt/openssl@3/lib", "-L/opt/homebrew/Cellar/libidn2/2.3.8/lib", "-isysroot", "/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"))
    .withCompileOptions(Seq("-isysroot", "/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk"))
}

libraryDependencies += "org.scala-lang.modules" %%% "scala-parser-combinators" % "2.4.0"
libraryDependencies += "com.github.lolgab" %%% "scala-native-crypto" % "0.2.1"
libraryDependencies += "com.softwaremill.sttp.client4" %%% "core" % "4.0.9"

enablePlugins(ScalaNativeJUnitPlugin)
enablePlugins(ScalaNativePlugin)
