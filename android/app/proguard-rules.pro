# The engine calls nothing back into Kotlin; Native's methods keep their names through
# the default rules (native methods are never renamed). Models are (de)serialized by
# kotlinx.serialization, whose generated serializers R8 keeps via its bundled rules.
-keepattributes *Annotation*, InnerClasses
-dontwarn org.slf4j.**
