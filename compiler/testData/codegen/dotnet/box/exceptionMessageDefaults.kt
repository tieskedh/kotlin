// Pins the accepted message-nullability delta: 'message' keeps its Kotlin type String? but is
// never null at runtime for mapped exceptions — a no-arg constructor yields the CLR default
// message text of the mapped System type (probe-verified verbatim), where the JVM would yield
// null. An explicit message is passed through unchanged.

fun box(): String {
    if (Throwable().message != "Exception of type 'System.Exception' was thrown.") return "FAIL 1"
    if (Exception("explicit").message != "explicit") return "FAIL 2"
    if (IllegalStateException().message != "Operation is not valid due to the current state of the object.") return "FAIL 3"
    if (IllegalArgumentException().message != "Value does not fall within the expected range.") return "FAIL 4"
    return "OK"
}
