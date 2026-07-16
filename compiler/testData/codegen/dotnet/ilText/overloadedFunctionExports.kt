// DOTNET_EXPORT: demo.convert(kotlin.Int)=ConvertInt
// DOTNET_EXPORT: demo.convert(kotlin.String?)=ConvertText
// DOTNET_EXPORT: demo.apply(kotlin.Int,kotlin.Function1<kotlin.Int,kotlin.Int>)=Apply
// DOTNET_EXPORT: demo.decorate(kotlin.Int,kotlin.String)=Decorate
// DOTNET_EXPORT: demo.echoBox(demo.Box<kotlin.String?>)=EchoBox

package demo

class Box<T>(val value: T)

typealias NullableTextBox = Box<String?>

fun convert(value: Int): String = "int:" + value

fun convert(value: String?): String = value ?: "null"

fun apply(value: Int, transform: (Int) -> Int): Int = transform(value)

fun Int.decorate(suffix: String = "!"): String = this.toString() + suffix

fun echoBox(value: NullableTextBox): NullableTextBox = value
