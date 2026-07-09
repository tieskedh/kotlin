// `const val` members of an object are CLR `literal` fields on the object class; every read is
// inlined by the frontend and never touches the singleton.

object Config {
    const val LIMIT = 42
    const val NAME = "cfg"
    const val FLAG = true
}

fun box(): String {
    if (Config.LIMIT != 42) return "FAIL LIMIT: " + Config.LIMIT
    if (Config.NAME != "cfg") return "FAIL NAME: " + Config.NAME
    if (!Config.FLAG) return "FAIL FLAG"
    return "OK"
}
