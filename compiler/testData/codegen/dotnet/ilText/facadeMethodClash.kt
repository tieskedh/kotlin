// Facade IL method-identity clashes, gated by the facade analogue of the class-member pre-pass
// (ilasm rejects duplicate method declarations; probed on the modern ilasm 10.0.9). All three
// flavors evict EVERY callable of the clashing identity — keeping one half would be an
// arbitrary pick between legal Kotlin overloads:
// - `g(String)`/`g(String?)`: reference nullability erases, both map to IL 'g'(string);
// - `h(Any)`/`h(Any?)`: both map to IL 'h'(object) — the hybrid model's object storage type;
// - `val x` vs `fun get_x()`: accessor mangling, both map to IL 'get_x'() — and because `val x`
//   carries a backing field, its eviction takes the file's whole property group (here just
//   itself) and the facade `.cctor` with it.
// Only `survivor` and `main` (which must not call a clashing callable) remain on the facade.
fun g(x: String): String = x

fun g(x: String?): String = x ?: ""

fun h(x: Any): Int = 1

fun h(x: Any?): Int = 2

val x: String? = "a"

fun get_x(): String? = "b"

fun survivor(): Int = 42

fun main() {
    println(survivor())
}
