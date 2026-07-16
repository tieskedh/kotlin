// DOTNET_EXPORT_PROPERTY: demo.count=Count
// DOTNET_EXPORT_PROPERTY: demo.maybeText=MaybeText
// DOTNET_EXPORT_PROPERTY: demo.transform=Transform
// DOTNET_EXPORT_PROPERTY: demo.consume=Consume
// DOTNET_EXPORT_PROPERTY: demo.readOnly=ReadOnly
// DOTNET_EXPORT_PROPERTY: demo.restricted=Restricted

package demo

var count: Int = 1

var maybeText: String? = null

var transform: (Int) -> Int = { value -> value + count }

var consume: (Int) -> Unit = { value -> count = value }

val readOnly: String
    get() = maybeText ?: "none"

var restricted: Int = 7
    private set

fun updateRestricted(value: Int) {
    restricted = value
}
