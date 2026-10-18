package app.hakusan.reader.san

import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrowsExactly
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks the Kotlin declarations of the native ABI against the Bindings
 * cross-check table: the constants in type and value, and the JNI methods by
 * name, descriptor, and shape. The header check covers the other rows.
 */
class SanAbiTableTest {
  @Test
  fun `every constant matches its row in type and value`() {
    val rows = readTable().filter { it.kind in CONSTANT_KINDS }

    val fields =
      SanAbi::class.java.declaredFields.filterNot { it.name == "INSTANCE" }

    assertEquals(
      rows.associate { it.name to "${it.kind} ${it.value}" }.toSortedMap(),
      fields.associate { "SAN_${it.name}" to constant(it) }.toSortedMap(),
    )
  }

  @Test
  fun `the native methods match the JNI rows`() {
    val rows = readTable().filter { it.kind == JNI }

    val natives = jniClasses(rows).flatMap(::nativeMethods)

    assertEquals(
      rows.map { "${it.name} ${it.value}" }.sorted(),
      natives.map { "${jniName(it)} ${methodDescriptor(it)}" }.sorted(),
    )
  }

  @Test
  fun `every native method is static and uses JVM primitives only`() {
    val rows = readTable().filter { it.kind == JNI }

    val natives = jniClasses(rows).flatMap(::nativeMethods)

    val instance = natives.filterNot { Modifier.isStatic(it.modifiers) }

    val nonPrimitive = natives.filterNot { method ->
      val types = method.parameterTypes.toList() + method.returnType

      types.all(::isPrimitiveOrArray)
    }

    assertTrue(instance.isEmpty()) {
      instance.map(::jniName).sorted().joinToString(", ", "Instance natives: ")
    }

    assertTrue(nonPrimitive.isEmpty()) {
      nonPrimitive
        .map(::jniName)
        .sorted()
        .joinToString(", ", "Natives with a type that is not primitive: ")
    }
  }

  @Test
  fun `no member of a JNI class has a mangled name`() {
    val rows = readTable().filter { it.kind == JNI }

    val members =
      jniClasses(rows).flatMap {
        it.declaredFields.toList<Member>() + it.declaredMethods
      }

    val mangled =
      members
        .filter { '$' in it.name }
        .map { "${it.declaringClass.name}.${it.name}" }
        .sorted()

    assertTrue(mangled.isEmpty()) {
      mangled.joinToString(", ", "Mangled member names: ")
    }
  }

  @Test
  fun `the table reader rejects every format violation`() {
    val row = "i32\tSAN_A\t*\t-1\n"

    assertEquals(listOf(Row("i32", "SAN_A", "*", "-1")), parse("$HEADER\n$row"))

    val rejected =
      mapOf(
        "a character outside ASCII" to "$HEADER\ni32\tSAN_\u00c4\t*\t-1\n",
        "a control character" to "$HEADER\ni32\tSAN_A\t*\t-1\u0007\n",
        "a carriage return" to "$HEADER\r\n$row",
        "no final newline" to "$HEADER\n${row.removeSuffix("\n")}",
        "a blank line" to "$HEADER\n\n$row",
        "another header" to "kind\tname\tvalue\tabi\n$row",
        "three fields" to "$HEADER\ni32\tSAN_A\t-1\n",
        "an empty field" to "$HEADER\ni32\tSAN_A\t\t-1\n",
        "a quoted field" to "$HEADER\ni32\t\"SAN_A\"\t*\t-1\n",
        "a comment" to "$HEADER\n# i32\tSAN_A\t*\t-1\n",
        "an unknown kind" to "$HEADER\nu32\tSAN_A\t*\t1\n",
        "an unknown ABI" to "$HEADER\ni32\tSAN_A\tarm64\t-1\n",
        "a repeated row" to "$HEADER\n$row$row",
        "mixed ABI rows" to "$HEADER\nsize\ts\t*\t4\nsize\ts\tx86_64\t4\n",
        "one per-ABI row" to "$HEADER\nsize\ts\tx86_64\t4\n",
        "a per-ABI constant" to
          "$HEADER\ni32\tSAN_A\tarm64-v8a\t-1\ni32\tSAN_A\tx86_64\t-1\n",
        "a constant without the prefix" to "$HEADER\ni32\tA\t*\t-1\n",
        "an i32 outside its range" to "$HEADER\ni32\tSAN_A\t*\t2147483648\n",
        "an i64 outside its range" to
          "$HEADER\ni64\tSAN_A\t*\t9223372036854775808\n",
        "a noncanonical integer" to "$HEADER\ni32\tSAN_A\t*\t01\n",
        "a negative size" to "$HEADER\nsize\ts\t*\t-4\n",
        "a type without a fixed width" to "$HEADER\ntype\ts\t*\tint\n",
        "an offset without a field" to "$HEADER\noffset\ts\t*\t0\n",
        "a JNI name without a method" to "$HEADER\njni\ta/S\t*\t(I)I\n",
        "a JNI value that is not a descriptor" to
          "$HEADER\njni\ta/S.f\t*\t(I)\n",
      )

    for ((violation, table) in rejected) {
      assertThrowsExactly(
        IllegalArgumentException::class.java,
        { parse(table) },
        violation,
      )
    }
  }

  /** One row of the cross-check table. */
  private data class Row(
    val kind: String,
    val name: String,
    val abi: String,
    val value: String,
  )

  /** The patterns of the name and the value of one row kind. */
  private class Kind(val name: Regex, val value: Regex)

  /** Returns the rows of the Bindings table that Gradle passes. */
  private fun readTable(): List<Row> {
    val path =
      System.getProperty(TABLE_PROPERTY)
        ?: error("Run the test through Gradle, which sets $TABLE_PROPERTY.")

    return parse(Files.readString(Path.of(path)))
  }

  /** Returns the rows of [table] after checking every format rule. */
  private fun parse(table: String): List<Row> {
    require('\r' !in table) { "The table has a carriage return." }
    require(table.endsWith('\n')) { "The table has no final newline." }

    require(table.all { it == '\t' || it == '\n' || it in ' '..'~' }) {
      "The table holds a character other than printable ASCII, tab, and LF."
    }

    val lines = table.removeSuffix("\n").split('\n')

    require(lines.none { it.isEmpty() }) { "The table has a blank line." }
    require(lines.first() == HEADER) { "The table header is not $HEADER." }

    val rows = lines.drop(1).map(::row)

    for ((key, abis) in rows.groupBy({ it.kind to it.name }, { it.abi })) {
      val (kind, name) = key

      val sorted = abis.sorted()

      require(sorted == EVERY_ABI || sorted == PER_ABI) {
        "The $kind rows of $name are neither one row for every ABI nor one " +
          "row per ABI."
      }

      require(sorted == EVERY_ABI || kind !in JVM_KINDS) {
        "The $kind rows of $name differ by ABI, but the JVM declarations " +
          "are the same on every ABI."
      }
    }

    return rows
  }

  /** Returns the row of one table [line] after checking its fields. */
  private fun row(line: String): Row {
    val fields = line.split('\t')

    require(fields.size == 4 && fields.none { it.isEmpty() }) {
      "The row $line does not have four nonempty fields."
    }

    val (kind, name, abi, value) = fields

    val format = KINDS[kind]

    require('"' !in line) { "The row $line quotes a field." }
    require(format != null) { "The row $line has an unknown kind." }
    require(abi in EVERY_ABI + PER_ABI) { "The row $line has an unknown ABI." }
    require(format.name.matches(name)) { "The row $line has an invalid name." }

    require(format.value.matches(value)) {
      "The row $line has an invalid value."
    }

    require(kind != "i32" || value.toIntOrNull() != null) {
      "The row $line holds a value outside the Int range."
    }

    require(kind != "i64" || value.toLongOrNull() != null) {
      "The row $line holds a value outside the Long range."
    }

    return Row(kind, name, abi, value)
  }

  /** Returns the kind and value of a constant [field], or why it is none. */
  private fun constant(field: Field): String {
    val modifiers = field.modifiers

    val constant =
      Modifier.isPublic(modifiers) &&
        Modifier.isStatic(modifiers) &&
        Modifier.isFinal(modifiers)

    return when {
      !constant -> "a field that is not public static final"
      field.type == Int::class.java -> "i32 ${field.getInt(null)}"
      field.type == Long::class.java -> "i64 ${field.getLong(null)}"
      else -> "a field of type ${field.type.name}"
    }
  }

  /** Returns [SanAbi] and every class that a JNI row in [rows] names. */
  private fun jniClasses(rows: List<Row>): Set<Class<*>> {
    val loader = SanAbi::class.java.classLoader

    val named = rows.map {
      val binary = it.name.substringBeforeLast('.')

      Class.forName(binary.replace('/', '.'), false, loader)
    }

    return named.toSet() + SanAbi::class.java
  }

  /** Returns the native methods that [type] declares. */
  private fun nativeMethods(type: Class<*>): List<Method> =
    type.declaredMethods.filter { Modifier.isNative(it.modifiers) }

  /** Returns the name of [method] as a JNI row writes it. */
  private fun jniName(method: Method): String =
    "${method.declaringClass.name.replace('.', '/')}.${method.name}"

  /** Returns the JNI descriptor of [method]. */
  private fun methodDescriptor(method: Method): String =
    method.parameterTypes.joinToString("", "(", ")", transform = ::descriptor) +
      descriptor(method.returnType)

  /** Returns the JNI descriptor of [type]. */
  private fun descriptor(type: Class<*>): String {
    val component = type.componentType

    return when {
      component != null -> "[" + descriptor(component)
      type.isPrimitive -> PRIMITIVES.getValue(type)
      else -> "L${type.name.replace('.', '/')};"
    }
  }

  /**
   * Returns whether [type] is a JVM primitive or a one-dimensional primitive
   * array.
   */
  private fun isPrimitiveOrArray(type: Class<*>): Boolean =
    type.isPrimitive || type.componentType?.isPrimitive == true

  private companion object {
    const val TABLE_PROPERTY = "app.hakusan.reader.bindingsTable"
    const val HEADER = "kind\tname\tabi\tvalue"
    const val JNI = "jni"
    const val ID = "[A-Za-z_][A-Za-z0-9_]*"
    const val FIELD_TYPE = "\\[*(?:[ZBCSIJFD]|L(?:$ID/)*$ID;)"

    val CONSTANT_KINDS = setOf("i32", "i64")
    val JVM_KINDS = CONSTANT_KINDS + JNI
    val EVERY_ABI = listOf("*")
    val PER_ABI = listOf("arm64-v8a", "x86_64")
    val INTEGER = Regex("0|-?[1-9][0-9]*")
    val BYTES = Regex("0|[1-9][0-9]*")
    val MACRO = Regex("SAN_[A-Z][A-Z0-9_]*")

    /** The name and value patterns of each row kind. */
    val KINDS: Map<String, Kind> =
      mapOf(
        "i32" to Kind(MACRO, INTEGER),
        "i64" to Kind(MACRO, INTEGER),
        "type" to Kind(Regex("$ID(?:\\.$ID)?"), Regex("u?int(?:8|16|32|64)_t")),
        "size" to Kind(Regex(ID), BYTES),
        "align" to Kind(Regex(ID), BYTES),
        "offset" to Kind(Regex("$ID\\.$ID"), BYTES),
        JNI to
          Kind(
            Regex("(?:$ID/)*$ID\\.$ID"),
            Regex("\\((?:$FIELD_TYPE)*\\)(?:$FIELD_TYPE|V)"),
          ),
      )

    /** The JNI descriptor of each JVM primitive type. */
    val PRIMITIVES: Map<Class<*>, String> =
      mapOf(
        Boolean::class.java to "Z",
        Byte::class.java to "B",
        Char::class.java to "C",
        Short::class.java to "S",
        Int::class.java to "I",
        Long::class.java to "J",
        Float::class.java to "F",
        Double::class.java to "D",
        Void.TYPE to "V",
      )
  }
}
