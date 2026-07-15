// TARGET_BACKEND: DOTNET

private data object Ready

private class DataObjectHost {
    data object Nested
}

private data object DataObjectParent {
    data object Child
}

private class GenericDataObjectHost<T> {
    data object Nested
}

fun box(): String {
    val asAny: Any = Ready
    if (Ready !== Ready || asAny != Ready || Ready != asAny) return "fail 1: singleton equality"
    if (Ready.toString() != "Ready") return "fail 2: top-level text $Ready"
    if (DataObjectHost.Nested.toString() != "Nested") return "fail 3: class-nested text"
    if (DataObjectParent.Child.toString() != "Child") return "fail 4: object-nested text"
    if (GenericDataObjectHost.Nested.toString() != "Nested") return "fail 5: generic-owner text"

    if (Ready.hashCode() != 78834051) return "fail 6: top-level declaration hash ${Ready.hashCode()}"
    if (DataObjectHost.Nested.hashCode() != -1755033420) {
        return "fail 7: class-nested declaration hash ${DataObjectHost.Nested.hashCode()}"
    }
    if (DataObjectParent.Child.hashCode() != 1004070145) {
        return "fail 8: object-nested declaration hash ${DataObjectParent.Child.hashCode()}"
    }
    if (GenericDataObjectHost.Nested.hashCode() != -1651919011) {
        return "fail 9: generic-owner declaration hash ${GenericDataObjectHost.Nested.hashCode()}"
    }

    return "OK"
}
