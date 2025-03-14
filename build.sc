import mill._, scalalib._

object `package` extends RootModule with ScalaModule {
  def scalaVersion = "3.6.4"

  override def ivyDeps = Agg(
    ivy"com.monovore::decline:2.5.0",
    ivy"com.monovore::decline-effect:2.5.0",
    ivy"org.typelevel::cats-effect:3.5.4",
    ivy"co.fs2::fs2-core:3.11.0",
    ivy"co.fs2::fs2-io:3.11.0",
    ivy"com.monovore::decline:2.4.1",
    ivy"org.jupnp:org.jupnp:3.0.2",
    ivy"ch.qos.logback:logback-classic:1.3.6",
    ivy"javax.servlet:javax.servlet-api:4.0.1",
    ivy"org.eclipse.jetty:jetty-client:9.4.53.v20231009",
    ivy"org.eclipse.jetty:jetty-server:9.4.53.v20231009",
    ivy"org.eclipse.jetty:jetty-servlet:9.4.53.v20231009",
    ivy"org.typelevel::log4cats-core:2.7.0",
    ivy"org.typelevel::log4cats-slf4j:2.7.0",
    ivy"com.softwaremill.sttp.client4::fs2:4.0.0-M17",
    ivy"io.circe::circe-core:0.14.10",
    ivy"io.circe::circe-generic:0.14.10",
    ivy"io.circe::circe-parser:0.14.10"
  )

  override def scalacOptions = Seq("-deprecation", "-feature", "-new-syntax", "-unchecked", "-Xkind-projector:underscores")

  object test extends ScalaTests with TestModule.ScalaTest {
    def ivyDeps = Agg(
      ivy"org.scalatest::scalatest:3.2.19",
      ivy"org.typelevel::cats-effect-testing-scalatest:1.5.0",
      ivy"org.typelevel::cats-effect-testkit:3.5.5"
    )
  }
}
