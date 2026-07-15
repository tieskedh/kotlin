// TARGET_BACKEND: DOTNET

private data class Box<T>(val value: T)

private data class Other<T>(val value: T)

private class Token(val text: String)

fun box(): String {
    val nullAny = Box<Any?>(null)
    val nullString = Box<String?>(null)
    if (!nullAny.equals(nullString) || !nullString.equals(nullAny)) return "fail 1: erased null equality"
    if (nullAny.hashCode() != nullString.hashCode()) return "fail 2: erased null hash"
    if (nullAny.toString() != "Box(value=null)") return "fail 3: generic toString $nullAny"

    val token = Token("same")
    val tokenAny = Box<Any>(token)
    val tokenExact = Box(token)
    if (!tokenAny.equals(tokenExact) || !tokenExact.equals(tokenAny)) return "fail 4: shared reference"
    if (tokenAny.equals(Other<Any>(token))) return "fail 5: distinct data-class identity"

    if (!Box(1).equals(Box<Any>(1))) return "fail 6: boxed Int"
    if (!Box(Double.NaN).equals(Box<Any>(Double.NaN))) return "fail 7: boxed NaN"
    if (Box(-0.0).equals(Box<Any>(0.0))) return "fail 8: signed zero"
    if (!Box<Int?>(null).equals(Box<String?>(null))) return "fail 9: nullable instantiations"

    return "OK"
}
