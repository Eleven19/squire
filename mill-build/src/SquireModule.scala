package millbuild

import mill.*, scalalib.*

trait SquireModule extends ScalaModule {
  def scalaVersion  = "3.8.4"
  def scalacOptions = Seq("-deprecation", "-feature", "-Wunused:all", "-release:25")

  /** Exact dynver snapshot matching the .ref/getkyo/kyo checkout (commit 87bb088c). */
  val kyoVersion = "1.0.0-RC4+55-87bb088c-SNAPSHOT"

  def repositories = super.repositories() ++ Seq(
    "https://central.sonatype.com/repository/maven-snapshots/"
  )

  /** kyo dependency by artifact short-name, e.g. kyoDep("kyo-core"). */
  def kyoDep(name: String) = mvn"io.getkyo::$name:$kyoVersion"
}
