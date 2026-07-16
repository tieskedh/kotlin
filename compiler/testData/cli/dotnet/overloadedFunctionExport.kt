package sample

fun convert(value: Int): String = "int:" + value

fun convert(value: String?): String = value ?: "null"
