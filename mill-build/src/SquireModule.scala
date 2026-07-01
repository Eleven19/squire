package millbuild

import mill.*, scalalib.*

trait SquireModule extends ScalaModule {
  def scalaVersion  = "3.8.4"
  def scalacOptions = Seq("-deprecation", "-feature", "-Wunused:all", "-release:25")

  /** Released kyo version (Maven Central); no snapshot repository needed. */
  val kyoVersion = "1.0.0-RC5"

  /** kyo dependency by artifact short-name, e.g. kyoDep("kyo-core"). */
  def kyoDep(name: String) = mvn"io.getkyo::$name:$kyoVersion"

  /**
   * Release version shared by every publishable module so their published
   * coordinates (and the cli POM's deps on core/tools) never skew.  Reads
   * `RELEASE_VERSION` from the CI-set environment, defaulting to "0.1.0" for
   * local development.
   *
   * Uses `Task.env` (the client-forwarded environment), NOT `sys.env`: Mill
   * runs tasks in a long-lived daemon whose `sys.env` reflects the daemon's
   * stale startup environment, so a per-invocation env var would be missed.
   */
  def releaseVersion: T[String] = Task.Input {
    Task.env.getOrElse("RELEASE_VERSION", "0.1.0")
  }
}
