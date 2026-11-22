# The San library registers the native methods of the bindings by name and
# descriptor when it loads, and it fails to load when one is missing. The rule
# keeps the methods themselves, also those that no code calls yet: a rule that
# kept only their names would let the optimizer remove them.
-keep class app.hakusan.reader.san.SanAbi {
  native <methods>;
}
