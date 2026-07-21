package publishedabi

@PublishedApi
internal const val PUBLISHED_CONST: Int = 20

@PublishedApi
internal val publishedProperty: Int
    get() = 21

@PublishedApi
internal fun publishedFunction(value: Int): Int = value + 1

@PublishedApi
internal class PublishedBox(public val value: Int)

public class PublicBox {
    @PublishedApi
    internal fun publishedMember(value: Int): Int = value + 1
}

fun main() {
    println(
        PUBLISHED_CONST + publishedProperty + publishedFunction(0) +
                PublishedBox(0).value + PublicBox().publishedMember(0)
    )
}
