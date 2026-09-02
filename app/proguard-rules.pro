# Project-specific ProGuard / R8 rules

# Preserve line numbers and source file attributes for Play Console de-obfuscation
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Apache Commons Net – keep NTP classes and suppress non-critical warnings
-keep class org.apache.commons.net.ntp.** { *; }
-dontwarn org.apache.commons.net.**

# dnsjava – keep DNS classes/records instantiated via reflection or SPI
-keep class org.xbill.DNS.** { *; }
-dontwarn org.xbill.DNS.spi.**
-dontwarn org.xbill.DNS.**

# SLF4J – optional logger binder used transitively by dnsjava
-dontwarn org.slf4j.**


