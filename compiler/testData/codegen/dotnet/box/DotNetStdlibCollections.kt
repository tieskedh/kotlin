package user.bootstrap

// A user source file may share a bootstrap implementation filename. Its package and declarations
// still belong exclusively to the user assembly and must not rename or leak into Kotlin.Stdlib.
private val result = "OK"

fun box(): String = result
