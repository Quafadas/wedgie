package wedgie.example

import upickle.default.ReadWriter

/** The shared state type, and the point of the whole exercise.
  *
  * This one file is compiled twice: into `example.jvm`, which the kernel uses,
  * and into `example.js`, which the Laminar frontend uses. Both ends therefore
  * agree about the shape of the state by construction, checked by the compiler,
  * rather than by two hand-written JSON mappings staying in step.
  *
  * Field names become traitlet keys, so none may start with `_`.
  */
case class Params(
    n: Int,
    label: String,
    enabled: Boolean
) derives ReadWriter

object Params:
  /** Only the key set is used by the frontend bridge; the values are irrelevant. */
  val prototype: Params = Params(0, "", false)
